package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object Extractors : Superstream() {

    suspend fun invokeInternalSource(
        id: Int? = null,
        type: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        fun LinkList.toExtractorLink(): ExtractorLink? {
            if (this.path.isNullOrBlank()) return null
            return runBlocking {
                newExtractorLink(
                    "Internal",
                    "Internal [${this@toExtractorLink.size}]",
                    this@toExtractorLink.path.replace("\\/", ""),
                    INFER_TYPE
                ) {
                    quality = getQualityFromName(this@toExtractorLink.quality)
                }
            }
        }

        // No childmode when getting links
        // New api does not return video links :(
        val query = if (type == ResponseTypes.Movies.value) {
            """{"childmode":"0","uid":"","app_version":"11.5","appid":"$appId","module":"Movie_downloadurl_v3","channel":"Website","mid":"$id","lang":"","expired_date":"${getExpiryDate()}","platform":"android","oss":"1","group":""}"""
        } else {
            """{"childmode":"0","app_version":"11.5","module":"TV_downloadurl_v3","channel":"Website","episode":"$episode","expired_date":"${getExpiryDate()}","platform":"android","tid":"$id","oss":"1","uid":"","appid":"$appId","season":"$season","lang":"en","group":""}"""
        }

        val linkData = queryApiParsed<LinkDataProp>(query, false)
        linkData.data?.list?.forEach {
            callback.invoke(it.toExtractorLink() ?: return@forEach)
        }

        // Should really run this query for every link :(
        val fid = linkData.data?.list?.firstOrNull { it.fid != null }?.fid

        val subtitleQuery = if (type == ResponseTypes.Movies.value) {
            """{"childmode":"0","fid":"$fid","uid":"","app_version":"11.5","appid":"$appId","module":"Movie_srt_list_v2","channel":"Website","mid":"$id","lang":"en","expired_date":"${getExpiryDate()}","platform":"android"}"""
        } else {
            """{"childmode":"0","fid":"$fid","app_version":"11.5","module":"TV_srt_list_v2","channel":"Website","episode":"$episode","expired_date":"${getExpiryDate()}","platform":"android","tid":"$id","uid":"","appid":"$appId","season":"$season","lang":"en"}"""
        }

        val subtitles = queryApiParsed<SubtitleDataProp>(subtitleQuery).data
        subtitles?.list?.forEach { subs ->
            val sub = subs.subtitles.maxByOrNull { it.support_total ?: 0 }
            subtitleCallback.invoke(
                SubtitleFile(
                    sub?.language ?: sub?.lang ?: return@forEach,
                    sub?.filePath ?: return@forEach
                )
            )
        }
    }

    suspend fun invokeExternalSource(
        mediaId: Int? = null,
        type: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val UI_COOKIES = base64Decode("ZXlKMGVYQWlPaUpLVjFRaUxDSmhiR2NpT2lKSVV6STFOaUo5LmV5SnBZWFFpT2pFM05UZ3dOakU0TkRVc0ltNWlaaUk2TVRjMU9EQTJNVGcwTlN3aVpYaHdJam94TnpnNU1UWTFPRFkxTENKa1lYUmhJanA3SW5WcFpDSTZNVEF4T0Rnek1pd2lkRzlyWlc0aU9pSXhPRFF3T0RWbVlXSXdOemt6WVRWaU5EVmpNek0zWW1FMFkyUXpZVGN4TXlKOWZRLjZfM0NvbTZvXzhxV3FCa1pyaTlabUtOVkdYRy1OSDRRVTdmaGxqZ3p0Z0U=")
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
        val shareKey = app.get("$thirdAPI/mbp/to_share_page?box_type=$type&mid=${mediaId}&json=1")
            .parsedSafe<ExternalResponse>()?.data?.link?.substringAfterLast("/") ?: return

        val headers = mapOf("Accept-Language" to "en")
        val shareRes =
            app.get("$thirdAPI/file/file_share_list?share_key=$shareKey", headers = headers)
                .parsedSafe<ExternalResponse>()?.data ?: return

        val fids = if (season == null) {
            shareRes.file_list
        } else {
            val parentId =
                shareRes.file_list?.find { it.file_name.equals("season $season", true) }?.fid
            app.get(
                "$thirdAPI/file/file_share_list?share_key=$shareKey&parent_id=$parentId&page=1",
                headers = headers
            ).parsedSafe<ExternalResponse>()?.data?.file_list?.filter {
                it.file_name?.contains("s${seasonSlug}e${episodeSlug}", true) == true
            }
        } ?: return

        fids.amapIndexed { index, fileList ->
            val player = app.post(
                "$thirdAPI/file/player", data = mapOf(
                    "fid" to "${fileList.fid}",
                    "share_key" to shareKey
                ), headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest"
                ), referer = "$thirdAPI/share/$shareKey", cookies = mapOf(
                    "ui" to UI_COOKIES
                )
            ).text
            val sources = "sources\\s*=\\s*(.*);".toRegex().find(player)?.groupValues?.get(1)

            AppUtils.tryParseJson<ArrayList<ExternalSources>>(sources)?.forEach { source ->
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        if (source.label.equals("ORG")) "${this.name} ${source.label}" else this.name,
                        source.file ?: return@forEach
                    ) {
                        this.quality = getQualityFromName(source.label)
                    }
                )
            }
        }
    }

    suspend fun invokeWatchsomuch(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val id = imdbId?.removePrefix("tt")
        val epsId = app.post(
            "$watchSomuchAPI/Watch/ajMovieTorrents.aspx",
            data = mapOf(
                "index" to "0",
                "mid" to "$id",
                "wsk" to "30fb68aa-1c71-4b8c-b5d4-4ca9222cfb45",
                "lid" to "",
                "liu" to ""
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<WatchsomuchResponses>()?.movie?.torrents?.let { eps ->
            if (season == null) {
                eps.firstOrNull()?.id
            } else {
                eps.find { it.episode == episode && it.season == season }?.id
            }
        } ?: return

        val (seasonSlug, episodeSlug) = getEpisodeSlug(
            season,
            episode
        )

        val subUrl = if (season == null) {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part="
        } else {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${seasonSlug}E${episodeSlug}"
        }

        app.get(subUrl)
            .parsedSafe<WatchsomuchSubResponses>()?.subtitles
            ?.map { sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        sub.label ?: "",
                        fixUrl(sub.url ?: return@map null, watchSomuchAPI)
                    )
                )
            }


    }

    suspend fun invokeWyzie(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val url = if (season == null) {
            "${wyzieAPI}/search?id=$imdbId"
        } else {
            "${wyzieAPI}/search?id=$imdbId&season=$season&episode=$episode"
        }

        val res = app.get(url).text

        tryParseJson<ArrayList<WyzieSubtitle>>(res)?.map { subtitle ->
            subtitleCallback.invoke(
                SubtitleFile(
                    subtitle.display ?: return@map,
                    subtitle.url ?: return@map,
                )
            )
        }

    }

    private fun fixUrl(url: String, domain: String): String {
        if (url.startsWith("http")) {
            return url
        }
        if (url.isEmpty()) {
            return ""
        }

        val startsWithNoHttp = url.startsWith("//")
        if (startsWithNoHttp) {
            return "https:$url"
        } else {
            if (url.startsWith('/')) {
                return domain + url
            }
            return "$domain/$url"
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun getEpisodeSlug(
        season: Int? = null,
        episode: Int? = null,
    ): Pair<String, String> {
        return if (season == null && episode == null) {
            "" to ""
        } else {
            (if (season!! < 10) "0$season" else "$season") to (if (episode!! < 10) "0$episode" else "$episode")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun base64DefaultEncode(byteArray: ByteArray): String {
        return Base64.Default.encode(byteArray)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun base64DefaultDecode(string: String): ByteArray {
        return Base64.Default.decode(string)
    }

}