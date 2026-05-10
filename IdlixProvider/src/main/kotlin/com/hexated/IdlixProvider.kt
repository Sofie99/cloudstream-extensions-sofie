package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.Normalizer

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://idlixian.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=%d&limit=36&sort=createdAt" to "Movie Terbaru",
        "$mainUrl/api/series?page=%d&limit=36&sort=createdAt" to "TV Series Terbaru",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=prime-video" to "Amazon Prime",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=netflix" to "Netflix",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val res =
            app.get(url, timeout = 10000L).parsedSafe<ApiResponse>() ?: return newHomePageResponse(
                request.name,
                emptyList()
            )
        val home = res.data.map { item ->
            val title = item.title ?: "UnKnown"
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            if (item.contentType == "movie") {
                val movieurl = "$mainUrl/api/movies/${item.slug}"
                newMovieSearchResponse(title, movieurl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.quality = getSearchQuality(item.quality)
                    this.score = Score.from10(item.voteAverage)
                }
            } else {
                val seriesurl = "$mainUrl/api/series/${item.slug}"
                newTvSeriesSearchResponse(title, seriesurl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.score = Score.from10(item.voteAverage)
                    this.quality = getSearchQuality(item.quality)
                }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/api/search?q=$query&page=$page&limit=8"
        val res = app.get(url).parsedSafe<SearchApiResponse>() ?: return null
        val items = res.results
        val results = items.mapNotNull { item ->
            val title = item.title
            val poster = item.posterPath.let { "https://image.tmdb.org/t/p/w342$it" }
            val year = (item.releaseDate ?: item.firstAirDate)?.substringBefore("-")?.toIntOrNull()

            val link = when (item.contentType) {
                "movie" -> "$mainUrl/api/movies/${item.slug}"
                "tv_series", "series" -> "$mainUrl/api/series/${item.slug}"
                else -> return@mapNotNull null
            }

            val rating = item.voteAverage

            if (item.contentType == "movie") {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = getQualityFromString(item.quality)
                    this.score = rating.let { Score.from10(it) }
                }
            } else {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = rating.let { Score.from10(it) }
                }
            }
        }

        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, timeout = 10000L)

        val data = response.parsedSafe<DetailResponse>()
            ?: throw ErrorLoadingException("Invalid JSON")

        val title = data.title ?: "Unknown"
        val poster = data.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = data.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }

        val year = (data.releaseDate ?: data.firstAirDate)
            ?.substringBefore("-")
            ?.toIntOrNull()

        val tags = data.genres?.mapNotNull { it.name } ?: emptyList()
        val logourl = "https://image.tmdb.org/t/p/w500" + data.logoPath
        val actors = data.cast?.map {
            Actor(it.name ?: "", it.profilePath?.let { p -> "https://image.tmdb.org/t/p/w185$p" })
        } ?: emptyList()

        val trailer = data.trailerUrl
        val rating = data.voteAverage

        val relatedUrl = if (data.seasons != null) {
            "$mainUrl/api/series/${data.slug}/related"
        } else {
            "$mainUrl/api/movies/${data.slug}/related"
        }

        val recommendations = try {
            app.get(relatedUrl, referer = mainUrl)
                .parsedSafe<ApiResponse>()?.data?.mapNotNull { item ->

                    val title = item.title ?: return@mapNotNull null
                    val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }

                    val link = if (item.contentType == "movie") {
                        "$mainUrl/api/movies/${item.slug}"
                    } else {
                        "$mainUrl/api/series/${item.slug}"
                    }

                    if (item.contentType == "movie") {
                        newMovieSearchResponse(title, link, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = (item.releaseDate ?: item.firstAirDate)
                                ?.substringBefore("-")
                                ?.toIntOrNull()
                        }
                    } else {
                        newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = (item.releaseDate ?: item.firstAirDate)
                                ?.substringBefore("-")
                                ?.toIntOrNull()
                        }
                    }

                } ?: emptyList()

        } catch (_: Exception) {
            emptyList()
        }

        return if (data.seasons != null) {
            val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()

            data.firstSeason?.episodes?.forEach { ep ->
                episodes.add(
                    newEpisode(
                        LoadData(
                            id = ep.id ?: return@forEach,
                            type = "episode"
                        ).toJson()
                    ) {
                        this.name = ep.name
                        this.season = data.firstSeason.seasonNumber
                        this.episode = ep.episodeNumber
                        this.description = ep.overview
                        this.runTime = ep.runtime
                        this.score = Score.from10(ep.voteAverage?.toString())
                        addDate(ep.airDate)
                        this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                    }
                )
            }

            data.seasons.forEach { season ->
                val seasonNum = season.seasonNumber ?: return@forEach
                if (seasonNum == data.firstSeason?.seasonNumber) return@forEach
                val seasonUrl = "$mainUrl/api/series/${data.slug}/season/$seasonNum"

                val seasonData = try {
                    val res = app.get(seasonUrl, referer = mainUrl)
                    res.parsedSafe<SeasonWrapper>()?.season
                } catch (_: Exception) {
                    null
                }

                seasonData?.episodes?.forEach { ep ->
                    episodes.add(
                        newEpisode(
                            LoadData(
                                id = ep.id ?: return@forEach,
                                type = "episode"
                            ).toJson()
                        ) {
                            this.name = ep.name
                            this.season = seasonNum
                            this.episode = ep.episodeNumber
                            this.description = ep.overview
                            this.runTime = ep.runtime
                            this.score = Score.from10(ep.voteAverage?.toString())
                            addDate(ep.airDate)
                            this.posterUrl =
                                ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logourl
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(rating?.toString())
                addActors(actors)
                addTrailer(trailer)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(
                title, url, TvType.Movie, LoadData(
                    id = data.id ?: "",
                    type = "movie"
                ).toJson()
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logourl
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(rating?.toString())
                addActors(actors)
                addTrailer(trailer)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parsed = try {
            AppUtils.parseJson<LoadData>(data)
        } catch (_: Exception) {
            null
        } ?: return false

        val contentId = parsed.id
        val contentType = parsed.type
        val res = app.get("$mainUrl/api/watch/play-info/$contentType/$contentId").parsedSafe<Res>()
        val solvejson = """
        {
            "claim": "${res?.claim}"
        }
        """.trimIndent()

        val redeemUrl = res?.redeemUrl ?: return false

        val iframeResponse = app.post(
            redeemUrl,
            requestBody = solvejson.toRequestBody("application/json".toMediaType())
        ).parsedSafe<Iframe>()

        iframeResponse?.let { iframe ->
            iframe.url.takeIf { it.isNotBlank() }
                ?.let { streamUrl ->
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            streamUrl,
                            INFER_TYPE
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }

            iframe.subtitles.forEach { subtitle ->
                subtitleCallback(
                    newSubtitleFile(
                        subtitle.label,
                        subtitle.path
                    )
                )
            }
        }
        return true
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val s = check ?: return null
        val u = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val patterns = listOf(
            Regex("\\b(4k|ds4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.FourK,

            // CAM / THEATRE SOURCES FIRST
            Regex("\\b(hdts|hdcam|hdtc)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HdCam,
            Regex("\\b(camrip|cam[- ]?rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
            Regex("\\b(cam)\\b", RegexOption.IGNORE_CASE) to SearchQuality.Cam,

            // WEB / RIP
            Regex(
                "\\b(web[- ]?dl|webrip|webdl)\\b",
                RegexOption.IGNORE_CASE
            ) to SearchQuality.WebRip,

            // BLURAY
            Regex(
                "\\b(bluray|bdrip|blu[- ]?ray)\\b",
                RegexOption.IGNORE_CASE
            ) to SearchQuality.BlueRay,

            // RESOLUTIONS
            Regex("\\b(1440p|qhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
            Regex("\\b(1080p|fullhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
            Regex("\\b(720p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.SD,

            // GENERIC HD LAST
            Regex("\\b(hdrip|hdtv)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,

            Regex("\\b(dvd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.DVD,
            Regex("\\b(hq)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HQ,
            Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip
        )


        for ((regex, quality) in patterns) if (regex.containsMatchIn(u)) return quality
        return null
    }


    data class Res(
        val claim: String,
        val redeemUrl: String,
    )

    data class Iframe(
        val code: String,
        val url: String,
        val expiresAt: Long,
        val subtitles: List<Subtitle>,
        val videoId: String,
    )

    data class Subtitle(
        val lang: String,
        val label: String,
        val path: String,
    )

    data class ApiResponse(
        val data: List<ApiItem> = emptyList(),
        val pagination: Pagination? = null,
        val meta: Meta? = null
    )

    data class ApiItem(
        val id: String? = null,
        val title: String? = null,
        val slug: String? = null,

        val posterPath: String? = null,
        val backdropPath: String? = null,

        val releaseDate: String? = null,
        val firstAirDate: String? = null,

        val voteAverage: String? = null,
        val viewCount: Any? = null,

        val quality: String? = null,
        val country: String? = null,
        val runtime: Int? = null,

        val createdAt: String? = null,
        val numberOfSeasons: Int? = null,
        val numberOfEpisodes: Int? = null,

        val contentType: String? = null,

        val commentCount: Int? = null,

        // optional extras (safe ignore)
        val originalLanguage: String? = null,
        val popularity: Any? = null,
        val genres: List<APIGenre>? = null,
        val hasVideo: Boolean? = null,
        val isPublished: Boolean? = null
    )

    data class APIGenre(
        val id: String? = null,
        val name: String? = null,
        val slug: String? = null
    )

    data class Pagination(
        val page: Int? = null,
        val limit: Int? = null,
        val total: Int? = null,
        val totalPages: Int? = null
    )

    data class Meta(
        val genre: String? = null,
        val country: String? = null,
        val year: String? = null,
        val network: String? = null,
        val sort: String? = null
    )

    data class DetailResponse(
        val id: String? = null,
        val title: String? = null,
        val slug: String? = null,
        val imdbId: String? = null,
        val tmdbId: String? = null,
        val overview: String? = null,
        val tagline: String? = null,

        val posterPath: String? = null,
        val backdropPath: String? = null,
        val logoPath: String? = null,

        val backdrops: List<String>? = null,

        val releaseDate: String? = null,
        val firstAirDate: String? = null,

        val runtime: Int? = null,
        val voteAverage: Any? = null,
        val popularity: Any? = null,

        val originalLanguage: String? = null,
        val country: String? = null,
        val status: String? = null,

        val trailerUrl: String? = null,
        val quality: String? = null,
        val director: String? = null,

        val genres: List<Genre>? = null,
        val cast: List<Cast>? = null,

        val seasons: List<Season>? = null, // TV only
        val firstSeason: Season? = null,

        val viewCount: Any? = null,
        val isPublished: Boolean? = null
    )

    data class Genre(
        val id: String? = null,
        val name: String? = null,
        val slug: String? = null
    )

    data class Cast(
        val id: String? = null,
        val name: String? = null,
        val character: String? = null,
        val profilePath: String? = null
    )

    data class Season(
        val id: String? = null,
        val seasonNumber: Int? = null,
        val name: String? = null,
        val posterPath: String? = null,
        val episodes: List<Episode>? = null
    )

    data class Episode(
        val id: String? = null,
        val episodeNumber: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val stillPath: String? = null,
        val airDate: String? = null,
        val runtime: Int? = null,
        val voteAverage: Any? = null
    )

    data class SeasonWrapper(
        val season: Season? = null
    )

    data class SearchApiResponse(
        val results: List<SearchApiResult>,
        val total: Long,
    )

    data class SearchApiResult(
        val id: String,
        val contentType: String,
        val title: String,
        val originalTitle: String,
        val overview: String,
        val genres: List<String>,
        val originalLanguage: String,
        val voteAverage: Double,
        val viewCount: Long,
        val popularity: Double,
        val posterPath: String,
        val backdropPath: String,
        val slug: String,
        val firstAirDate: String?,
        val numberOfSeasons: Long?,
        val releaseDate: String?,
        val quality: String?,
    )

    data class ChallengeResponse(
        val challenge: String,
        val signature: String,
        val difficulty: Int
    )

    data class SolveResponse(
        val embedUrl: String? = null
    )

    data class LoadData(
        val id: String,
        val type: String // "movie" or "episode"
    )
}
