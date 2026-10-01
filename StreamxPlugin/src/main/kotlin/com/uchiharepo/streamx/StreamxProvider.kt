package com.uchiharepo.streamx

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.regex.Pattern

class StreamxProvider : MainAPI() {
    override var mainUrl = "https://streamxtv.tech"
    override var name = "StreamXTV"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie
    )

    companion object {
        private const val API_BASE = "https://api.streamxtv.sbs/api"
        private const val API_FAILOVER = "https://streamx-backend-myr0.onrender.com/api"
        private const val CINEJOY_ENC_API = "https://enc-dec.app/api"
        private const val WING_GATEWAY = "https://api.wing.st/g"
        private const val CINEJOY_REFERER = "https://cinejoy.pk/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val httpClient = OkHttpClient()
    }

    data class StreamxListResponse(
        @JsonProperty("results") val results: List<StreamxMediaItem>? = null
    )

    data class StreamxMediaItem(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("year") val year: Any? = null,
        @JsonProperty("description") val description: String? = null
    )

    data class StreamxDetail(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("year") val year: Any? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("backdropImage") val backdropImage: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("totalSeasons") val totalSeasons: Int? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("isTv") val isTv: Boolean? = null,
        @JsonProperty("cast") val cast: List<String>? = null
    )

    data class StreamxSeasonEpisodes(
        @JsonProperty("episodes") val episodes: List<StreamxEpisodeItem>? = null
    )

    data class StreamxEpisodeItem(
        @JsonProperty("num") val num: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("duration") val duration: String? = null,
        @JsonProperty("desc") val desc: String? = null,
        @JsonProperty("image") val image: String? = null
    )

    data class StreamxPassData(
        val id: String,
        val season: Int,
        val episode: Int,
        val isMovie: Boolean,
        val isAnime: Boolean = false
    )

    data class EncCinejoyResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: EncCinejoyResult? = null,
        @JsonProperty("error") val error: String? = null
    )

    data class EncCinejoyResult(
        @JsonProperty("data") val data: String? = null,
        @JsonProperty("state") val state: Any? = null
    )

    data class DecCinejoyResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: DecCinejoyResult? = null
    )

    data class DecCinejoyResult(
        @JsonProperty("data") val data: DecCinejoyData? = null,
        @JsonProperty("stream") val stream: List<DecCinejoyStream>? = null
    )

    data class DecCinejoyData(
        @JsonProperty("stream") val stream: List<DecCinejoyStream>? = null
    )

    data class DecCinejoyStream(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("playlist") val playlist: String? = null
    )

    private fun decodeBase64(str: String): ByteArray {
        var s = str.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) {
            s += "="
        }
        return try {
            android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        } catch (e: Throwable) {
            java.util.Base64.getDecoder().decode(s)
        }
    }

    private fun encodeBase64Url(bytes: ByteArray): String {
        return try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP).trimEnd('=')
        } catch (e: Throwable) {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    private suspend fun apiGet(endpoint: String): String? {
        val headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")
        return try {
            app.get("$API_BASE$endpoint", headers = headers).text
        } catch (e: Exception) {
            try {
                app.get("$API_FAILOVER$endpoint", headers = headers).text
            } catch (e2: Exception) {
                null
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = ArrayList<HomePageList>()

        val trendingJson = apiGet("/trending?page=1&type=all&time_window=day")
        if (!trendingJson.isNullOrBlank()) {
            try {
                val items = parseJson<StreamxListResponse>(trendingJson).results.orEmpty()
                if (items.isNotEmpty()) {
                    sections.add(HomePageList("Trending Today", items.mapNotNull { it.toSearchResponse() }))
                }
            } catch (e: Exception) {}
        }

        val moviesJson = apiGet("/movies?page=1&sort=popularity.desc")
        if (!moviesJson.isNullOrBlank()) {
            try {
                val items = parseJson<StreamxListResponse>(moviesJson).results.orEmpty()
                if (items.isNotEmpty()) {
                    sections.add(HomePageList("Popular Movies", items.mapNotNull { it.toSearchResponse() }))
                }
            } catch (e: Exception) {}
        }

        val tvJson = apiGet("/tv?page=1&sort=popularity.desc")
        if (!tvJson.isNullOrBlank()) {
            try {
                val items = parseJson<StreamxListResponse>(tvJson).results.orEmpty()
                if (items.isNotEmpty()) {
                    sections.add(HomePageList("Popular TV Shows", items.mapNotNull { it.toSearchResponse() }))
                }
            } catch (e: Exception) {}
        }

        val animeJson = apiGet("/anime/trending")
        if (!animeJson.isNullOrBlank()) {
            try {
                val items = parseJson<StreamxListResponse>(animeJson).results.orEmpty()
                if (items.isNotEmpty()) {
                    sections.add(HomePageList("Trending Anime", items.mapNotNull { it.toSearchResponse(forceAnime = true) }))
                }
            } catch (e: Exception) {}
        }

        return newHomePageResponse(sections)
    }

    private fun StreamxMediaItem.toSearchResponse(forceAnime: Boolean = false): SearchResponse? {
        val rawId = id ?: return null
        val itemId = rawId.toString()
        val itemTitle = title ?: name ?: return null
        val posterUrl = image ?: poster

        val isTv = type.equals("tv", ignoreCase = true)
        val isAnime = forceAnime || type.equals("anime", ignoreCase = true)

        val targetUrl = when {
            isAnime -> "$mainUrl/anime/$itemId"
            isTv -> "$mainUrl/tv/$itemId"
            else -> "$mainUrl/movie/$itemId"
        }

        val tvType = when {
            isAnime -> TvType.Anime
            isTv -> TvType.TvSeries
            else -> TvType.Movie
        }

        return newMovieSearchResponse(itemTitle, targetUrl, tvType) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val results = ArrayList<SearchResponse>()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")

        val mediaJson = apiGet("/search?q=$encoded")
        if (!mediaJson.isNullOrBlank()) {
            try {
                val items = parseJson<StreamxListResponse>(mediaJson).results.orEmpty()
                results.addAll(items.mapNotNull { it.toSearchResponse() })
            } catch (e: Exception) {}
        }

        val animeJson = apiGet("/anime/search?q=$encoded")
        if (!animeJson.isNullOrBlank()) {
            try {
                val animeItems = parseJson<StreamxListResponse>(animeJson).results.orEmpty()
                results.addAll(animeItems.mapNotNull { it.toSearchResponse(forceAnime = true) })
            } catch (e: Exception) {}
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val isAnime = url.contains("/anime/")
        val isTv = url.contains("/tv/")
        val id = url.substringAfterLast("/").substringBefore("?").trim()
        if (id.isBlank()) return null

        if (isAnime) {
            val json = apiGet("/anime/$id") ?: return null
            val detail = parseJson<StreamxDetail>(json)
            val title = detail.title ?: detail.name ?: "Anime"
            val poster = detail.image ?: detail.backdropImage
            val plot = detail.description

            val epJson = apiGet("/anime/$id/episodes")
            val epItems = if (!epJson.isNullOrBlank()) {
                parseJson<StreamxSeasonEpisodes>(epJson).episodes.orEmpty()
            } else {
                emptyList()
            }

            val episodes = if (epItems.isNotEmpty()) {
                epItems.map { ep ->
                    val epNum = ep.num ?: 1
                    val passData = StreamxPassData(id, 1, epNum, isMovie = false, isAnime = true).toJson()
                    newEpisode(passData) {
                        this.name = ep.title ?: "Episode $epNum"
                        this.season = 1
                        this.episode = epNum
                        this.posterUrl = ep.image ?: poster
                        this.description = ep.desc
                    }
                }
            } else {
                listOf(
                    newEpisode(StreamxPassData(id, 1, 1, isMovie = false, isAnime = true).toJson()) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = poster
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = detail.backdropImage ?: poster
                this.plot = plot
                this.rating = detail.rating?.toDoubleOrNull()?.let { (it * 1000).toInt() }
            }
        }

        if (isTv) {
            val json = apiGet("/tv/$id") ?: return null
            val detail = parseJson<StreamxDetail>(json)
            val title = detail.title ?: detail.name ?: "TV Show"
            val poster = detail.backdropImage ?: detail.image
            val plot = detail.description
            val totalSeasons = detail.totalSeasons ?: 1

            val episodes = ArrayList<Episode>()
            for (s in 1..totalSeasons) {
                val seasonJson = apiGet("/tv/$id/season/$s")
                if (!seasonJson.isNullOrBlank()) {
                    val epItems = parseJson<StreamxSeasonEpisodes>(seasonJson).episodes.orEmpty()
                    for (ep in epItems) {
                        val epNum = ep.num ?: 1
                        val passData = StreamxPassData(id, s, epNum, isMovie = false, isAnime = false).toJson()
                        episodes.add(
                            newEpisode(passData) {
                                this.name = ep.title ?: "Season $s Episode $epNum"
                                this.season = s
                                this.episode = epNum
                                this.posterUrl = ep.image ?: poster
                                this.description = ep.desc
                            }
                        )
                    }
                }
            }

            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode(StreamxPassData(id, 1, 1, isMovie = false, isAnime = false).toJson()) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = poster
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.rating = detail.rating?.toDoubleOrNull()?.let { (it * 1000).toInt() }
            }
        }

        val json = apiGet("/movies/$id") ?: return null
        val detail = parseJson<StreamxDetail>(json)
        val title = detail.title ?: "Movie"
        val poster = detail.backdropImage ?: detail.image
        val passData = StreamxPassData(id, 1, 1, isMovie = true, isAnime = false).toJson()

        return newMovieLoadResponse(title, url, TvType.Movie, passData) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = detail.description
            this.rating = detail.rating?.toDoubleOrNull()?.let { (it * 1000).toInt() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val passData = try {
            parseJson<StreamxPassData>(data)
        } catch (e: Exception) {
            return false
        }

        val id = passData.id
        val season = passData.season
        val episode = passData.episode
        val isMovie = passData.isMovie
        val isAnime = passData.isAnime

        var loadedAny = false

        if (isAnime) {
            val megaPlayUrl = "https://megaplay.buzz/stream/ani/$id/$episode/sub"
            try {
                val mpHtml = app.get(megaPlayUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
                val m3u8Match = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(mpHtml)
                if (m3u8Match != null) {
                    val m3u8Url = m3u8Match.groupValues[1]
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "StreamX Anime (MegaPlay HLS)",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://megaplay.buzz/"
                            this.headers = mapOf(
                                "Referer" to "https://megaplay.buzz/",
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                    loadedAny = true
                }
            } catch (e: Exception) {}

            try {
                if (loadExtractor("https://vidnest.fun/animepahe/$id/$episode/sub", data, subtitleCallback, callback)) {
                    loadedAny = true
                }
            } catch (e: Exception) {}

            return loadedAny
        }

        val primaryServers = listOf("Lisbon", "Nebula", "Solara", "Athens")
        for (serverName in primaryServers) {
            try {
                val mediaTypeParam = if (isMovie) "movie" else "series"
                var targetToEnc = "https://api.wing.st/?title=movie&type=$mediaTypeParam&year=&imdb=&tmdb=$id&server=$serverName"
                if (!isMovie) {
                    targetToEnc += "&season=$season&episode=$episode"
                }

                val encApiUrl = "$CINEJOY_ENC_API/enc-cinejoy?url=${URLEncoder.encode(targetToEnc, "UTF-8")}"
                val encRespText = app.get(encApiUrl, headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")).text
                val encResp = parseJson<EncCinejoyResponse>(encRespText)

                val b64Data = encResp.result?.data
                val stateObj = encResp.result?.state

                if (!b64Data.isNullOrBlank()) {
                    val rawPayload = decodeBase64(b64Data)
                    val reqBody = rawPayload.toRequestBody("application/octet-stream".toMediaTypeOrNull())

                    val req = Request.Builder()
                        .url(WING_GATEWAY)
                        .post(reqBody)
                        .addHeader("Content-Type", "application/octet-stream")
                        .addHeader("Accept", "application/json, text/plain, */*")
                        .addHeader("User-Agent", USER_AGENT)
                        .build()

                    val gBytes = httpClient.newCall(req).execute().body?.bytes() ?: ByteArray(0)

                    if (gBytes.isNotEmpty()) {
                        val b64G = encodeBase64Url(gBytes)
                        val decPayload = mapOf("text" to b64G, "state" to stateObj)
                        val decRespText = app.post(
                            "$CINEJOY_ENC_API/dec-cinejoy",
                            headers = mapOf("Content-Type" to "application/json", "User-Agent" to USER_AGENT),
                            json = decPayload
                        ).text

                        val decResp = parseJson<DecCinejoyResponse>(decRespText)
                        val streamList = decResp.result?.data?.stream ?: decResp.result?.stream

                        if (!streamList.isNullOrEmpty()) {
                            for (stream in streamList) {
                                val playlist = stream.playlist?.trim().orEmpty()
                                if (playlist.isNotBlank() && playlist.contains(".m3u8")) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "StreamX $serverName (1080p HLS)",
                                            url = playlist,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = CINEJOY_REFERER
                                            this.headers = mapOf(
                                                "Referer" to CINEJOY_REFERER,
                                                "Origin" to "https://cinejoy.pk",
                                                "User-Agent" to USER_AGENT
                                            )
                                            this.quality = Qualities.P1080.value
                                        }
                                    )
                                    loadedAny = true

                                    try {
                                        M3u8Helper.generateM3u8(
                                            source = this.name,
                                            streamUrl = playlist,
                                            referer = CINEJOY_REFERER,
                                            headers = mapOf(
                                                "Referer" to CINEJOY_REFERER,
                                                "Origin" to "https://cinejoy.pk",
                                                "User-Agent" to USER_AGENT
                                            ),
                                            name = "StreamX $serverName"
                                        ).forEach { subLink ->
                                            callback.invoke(subLink)
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        val mirrorEmbeds = if (isMovie) {
            listOf(
                "https://vidsrc.cc/v2/embed/movie/$id",
                "https://www.2embed.cc/embed/$id",
                "https://vidcore.net/movie/$id",
                "https://www.vidking.net/embed/movie/$id",
                "https://rivestream.org/embed?type=movie&id=$id",
                "https://vidfast.pro/movie/$id"
            )
        } else {
            listOf(
                "https://vidsrc.cc/v2/embed/tv/$id/$season/$episode",
                "https://www.2embed.cc/embedtv/$id&s=$season&e=$episode",
                "https://vidcore.net/tv/$id/$season/$episode",
                "https://www.vidking.net/embed/tv/$id/$season/$episode",
                "https://rivestream.org/embed?type=tv&id=$id&season=$season&episode=$episode",
                "https://vidfast.pro/tv/$id/$season/$episode"
            )
        }

        for (embedUrl in mirrorEmbeds) {
            try {
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                    loadedAny = true
                }
            } catch (e: Exception) {}
        }

        return loadedAny
    }
}
