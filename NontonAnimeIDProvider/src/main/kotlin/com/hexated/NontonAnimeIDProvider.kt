package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import android.content.Context
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Base64

class NontonAnimeIDProvider : MainAPI() {
    override var mainUrl = "https://s7.nontonanimeid.boats"
    override var name = "NontonAnimeID"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("TV", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when {
                t.contains("Finished", true) -> ShowStatus.Completed
                t.contains("Airing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "ongoing-list/" to "Ongoing",
        "popular-series/" to "Populer"
        "genres/super-power/" to "Super Power"
        "genres/detective/" to "Detective"
        "genres/sci-fi/" to "Sci-Fi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val doc = app.get("$mainUrl/${request.data}").document
        val items = doc.select(".animeseries").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, false)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst(".title")?.text()?.trim()
            ?: selectFirst("h2")?.text()?.trim() ?: return null
        val poster = fixUrlNull(selectFirst("img")?.getImageAttr())
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            posterUrl = poster
            addDubStatus(false, true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select(".result > ul > li").mapNotNull {
            val title = it.selectFirst("h2")?.text()?.trim() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.getImageAttr()
            val type = getType(it.selectFirst(".boxinfores > span.typeseries")?.text() ?: "")
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            newAnimeSearchResponse(title, fixUrl(href), type) {
                posterUrl = poster
                addDubStatus(false, true)
            }
        }
    }

    // Load Page
    override suspend fun load(url: String): LoadResponse? {
        val req = app.get(url)
        val doc = req.document
        mainUrl = getBaseUrl(req.url)

        val title = doc.selectFirst("h1.entry-title.cs")?.text()
            ?.replace("Nonton Anime", "")
            ?.replace("Sub Indo", "")
            ?.trim().orEmpty()

        val poster = doc.selectFirst(".poster img")?.getImageAttr()
        val tags = doc.select(".tagline a").map { it.text() }
        val year = Regex("\\d{4}").find(doc.select(".bottomtitle").text())?.value?.toIntOrNull()
        val status = getStatus(doc.select("span.statusseries").text())
        val type = getType(doc.select("span.typeseries").text())
        val score = doc.select("span.nilaiseries").text().toFloatOrNull()?.let { Score.from(it, 10) }

        val desc = doc.selectFirst(".entry-content.seriesdesc")?.text()
            ?: doc.select("p").text().takeIf { it.isNotBlank() } ?: ""

        val trailer = doc.selectFirst("a.trailerbutton")?.attr("href")

        val eps = doc.select(".epsleft a, ul.misha_posts_wrap2 li a, div.episode-list-items a.episode-item")
            .mapIndexedNotNull { i, el ->
                val num = Regex("Episode\\s?(\\d+)").find(el.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("(\\d+)").find(el.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (i + 1)
                val link = el.attr("href").ifEmpty { return@mapIndexedNotNull null }
                EpisodeData(num, fixUrl(link))
            }.distinctBy { it.number }
            .sortedBy { it.number }
            .map { e -> newEpisode(e.link) { episode = e.number } }

        val rec = doc.select(".result li").mapNotNull {
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val ttl = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val img = it.selectFirst("img")?.getImageAttr()
            newAnimeSearchResponse(ttl, fixUrl(href), TvType.Anime) {
                posterUrl = img
                addDubStatus(false, true)
            }
        }

        val t = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = t?.image ?: poster
            backgroundPosterUrl = t?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, eps)
            showStatus = status
            this.score = score
            plot = desc
            addTrailer(trailer)
            this.tags = tags
            recommendations = rec
            addMalId(t?.malId)
            addAniListId(t?.aniId?.toIntOrNull())
        }
    }

    // links & server extractor
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {

        val doc = app.get(data).document
        val iframes = mutableSetOf<String>()
        doc.select("iframe[data-src]")
            .mapNotNull { it.attr("data-src").takeIf(String::isNotBlank) }
            .forEach { iframes.add(fixUrl(it)) }

        iframes.forEach {
            loadExtractor(it, data, subtitleCallback, callback)
        }

        val base64 = doc.selectFirst("script[src^=data:text/javascript;base64,]")
            ?.attr("src")
            ?.substringAfter("base64,")

        val nonce = base64?.let {
            runCatching {
                val d = String(Base64.getDecoder().decode(it))
                Regex("\"nonce\":\"(\\S+?)\"").find(d)?.groupValues?.getOrNull(1)
            }.getOrNull()
        }.orEmpty()

        doc.select(".container1 ul.player li:not(.boxtab)").map {
            async {
                val post = it.attr("data-post")
                val nume = it.attr("data-nume")
                val type = it.attr("data-type")
                if (post.isBlank() || nume.isBlank() || type.isBlank()) return@async

                val res = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "nonce" to nonce,
                        "type" to type,
                        "nume" to nume,
                        "post" to post
                    ),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = data
                )

                val src = res.document.selectFirst("iframe")?.attr("src")
                    ?: Regex("src=['\"](https?://[^'\"]+)['\"]").find(res.text)?.groupValues?.getOrNull(1)

                src?.takeIf { it.isNotBlank() }?.let {
                    loadExtractor(it, mainUrl, subtitleCallback, callback)
                }
            }
        }.awaitAll()

        true
    }

    // Helper: Base URL
    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private fun Element.getImageAttr(): String? = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(",").substringBefore(" ")
        else -> attr("abs:src")
    }

    private data class EpisodeData(val number: Int, val link: String)
    private data class EpResponse(
        @JsonProperty("posts") val posts: String?,
        @JsonProperty("max_page") val max_page: Int?,
        @JsonProperty("found_posts") val found_posts: Int?,
        @JsonProperty("content") val content: String
    )
}
