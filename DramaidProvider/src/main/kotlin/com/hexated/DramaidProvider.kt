package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class DramaidProvider : MainAPI() {
    override var mainUrl = "https://dramaid.nl"
    override var name = "DramaId"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.AsianDrama)

    companion object {
        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        fun getType(t: String?): TvType {
            return when {
                t?.contains("Movie", true) == true -> TvType.Movie
                t?.contains("Anime", true) == true -> TvType.Anime
                else -> TvType.AsianDrama
            }
        }
    }

    override val mainPage = mainPageOf(
        "&status=&type=&order=update" to "Drama Terbaru",
        "&order=latest" to "Baru Ditambahkan",
        "&status=&type=&order=popular" to "Drama Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/series/?page=$page${request.data}").document
        val home = document.select("article[itemscope=itemscope]").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperDramaLink(uri: String): String {
        return if (uri.contains("-episode-")) {
            "$mainUrl/series/" + Regex("$mainUrl/(.+)-ep.+").find(uri)?.groupValues?.get(1)
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = getProperDramaLink(this.selectFirst("a.tip")!!.attr("href"))
        val title = this.selectFirst("h2[itemprop=headline]")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.select("img:last-child").attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article[itemscope=itemscope]").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.select("div.thumb img:last-child").attr("src"))
        val tags = document.select(".genxed > a").map { it.text() }
        val type = document.selectFirst(".info-content .spe span:contains(Tipe:)")?.ownText()
        val year = Regex("\\d, ([0-9]*)").find(
            document.selectFirst(".info-content > .spe > span > time")!!.text().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val status = getStatus(
            document.select(".info-content > .spe > span:nth-child(1)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".entry-content > p").text().trim()

        val episodes = document.select(".eplister > ul > li").mapNotNull {
            val name = it.selectFirst("a > .epl-title")?.text()
            val link = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            newEpisode(link) { this.name = name }
        }.reversed()

        val recommendations =
            document.select(".listupd > article[itemscope=itemscope]").mapNotNull { rec ->
                rec.toSearchResult()
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            getType(type),
            episodes = episodes
        ) {
            posterUrl = poster
            this.year = year
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val sources = document.select(".mobius > .mirror > option").mapNotNull {
            fixUrl(Jsoup.parse(base64Decode(it.attr("value"))).select("iframe").attr("src"))
        }

        sources.amap {
            loadExtractor(it, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }

}

