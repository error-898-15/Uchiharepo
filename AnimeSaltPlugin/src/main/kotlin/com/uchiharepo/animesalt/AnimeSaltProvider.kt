package com.uchiharepo.animesalt

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeSaltProvider : MainAPI() {
    override var mainUrl = "https://animesalt.cx"
    override var name = "AnimeSalt"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private fun md5Hex(str: String): String {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(str.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun decryptAesCtr(cipherTextBytes: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            return cipher.doFinal(cipherTextBytes)
        }

        private fun encryptAesCtr(plainBytes: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            return cipher.doFinal(plainBytes)
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/series/page/" to "Latest Series",
        "$mainUrl/movies/page/" to "Latest Movies",
        "$mainUrl/category/anime/page/" to "Anime Series",
        "$mainUrl/category/cartoon/page/" to "Cartoons"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.endsWith("/page/")) {
            "${request.data}$page/"
        } else {
            "${request.data}$page"
        }

        val document = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                timeout = 30L
            ).document
        } catch (e: Exception) {
            app.get(
                request.data.removeSuffix("page/"),
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/"),
                timeout = 30L
            ).document
        }

        val items = document.select("article.post").mapNotNull { article ->
            article.toSearchResult()
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.lnk-blk, a[href]")?.attr("href")?.trim() ?: return null
        val title = this.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
            ?: this.selectFirst("img[alt]")?.attr("alt")?.replace("^Image\\s+".toRegex(), "")?.trim()
            ?: return null

        var poster = this.selectFirst("img")?.let {
            val ds = it.attr("data-src").trim()
            if (ds.isNotBlank()) ds else it.attr("src").trim()
        }
        if (poster?.startsWith("//") == true) {
            poster = "https:$poster"
        }

        val isMovie = link.contains("/movies/")
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, link, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(
            searchUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ),
            timeout = 30L
        ).document

        return document.select("article.post").mapNotNull { article ->
            article.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ),
            timeout = 30L
        ).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown Title"

        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: document.selectFirst(".post-thumbnail img, .poster img")?.attr("src")?.trim()
        if (poster?.startsWith("//") == true) {
            poster = "https:$poster"
        }

        var backdrop = document.selectFirst(".backdrop img")?.attr("src")?.trim()
        if (backdrop?.startsWith("//") == true) {
            backdrop = "https:$backdrop"
        }

        val plot = document.selectFirst(".entry-content p, .sinopsis, meta[property=og:description]")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(document.text())
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select(".genres a, a[href*='/genres/']").map { it.text().trim() }.filter { it.isNotBlank() }

        val isMovie = url.contains("/movies/")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val seenUrls = mutableSetOf<String>()

            val epRegex = Regex("""href=[\"'](https://animesalt\.cx/episode/([a-zA-Z0-9_-]+)-(\d+)x(\d+)/?)[\"']""", RegexOption.IGNORE_CASE)
            epRegex.findAll(document.html()).forEach { match ->
                val epUrl = match.groupValues[1]
                if (!seenUrls.contains(epUrl)) {
                    seenUrls.add(epUrl)
                    val season = match.groupValues[3].toIntOrNull() ?: 1
                    val epNumber = match.groupValues[4].toIntOrNull() ?: 1
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = "Episode $epNumber"
                            this.season = season
                            this.episode = epNumber
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                document.select("a[href*='/episode/']").forEach { a ->
                    val epUrl = a.attr("href").trim()
                    if (epUrl.isNotBlank() && !seenUrls.contains(epUrl)) {
                        seenUrls.add(epUrl)
                        val epNumberMatch = Regex("""(\d+)x(\d+)""").find(epUrl)
                        val season = epNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        val epNumber = epNumberMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = "Episode $epNumber"
                                this.season = season
                                this.episode = epNumber
                            }
                        )
                    }
                }
            }

            episodes.sortWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ),
            timeout = 30L
        ).document

        var loadedAny = false
        val embedUrls = mutableListOf<String>()

        document.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.isNotBlank()) embedUrls.add(src)
        }

        document.select("[data-src]").forEach {
            val src = it.attr("data-src").trim()
            if (src.isNotBlank() && (src.contains("/video/") || src.contains("embed") || src.contains("player") || src.contains("multi-lang-plyr"))) {
                embedUrls.add(src)
            }
        }

        for (rawEmbed in embedUrls.distinct()) {
            val cleanUrl = if (rawEmbed.startsWith("//")) "https:$rawEmbed" else rawEmbed

            if (cleanUrl.contains("multi-lang-plyr.php") || cleanUrl.contains("player.php?data=")) {
                try {
                    val rawData = cleanUrl.substringAfter("data=").substringBefore("&")
                    val decodedJson = String(Base64.decode(URLDecoder.decode(rawData, "UTF-8"), Base64.DEFAULT))
                    val langList = parseJson<List<MultiLangItem>>(decodedJson)

                    for (item in langList) {
                        val directLink = item.link ?: continue
                        val langLabel = item.language ?: "Audio"

                        if (directLink.contains("abyssplayer.com")) {
                            try {
                                if (extractAbyssDirect(directLink, langLabel, callback)) {
                                    loadedAny = true
                                    continue
                                }
                            } catch (e: Exception) {
                                // Fall through to standard extractor
                            }
                        }

                        try {
                            if (loadExtractor(directLink, data, subtitleCallback, callback)) {
                                loadedAny = true
                            }
                        } catch (e: Exception) {
                            // Continue to next language
                        }
                    }
                } catch (e: Exception) {
                    // Ignore malformed player params
                }
            } else if (cleanUrl.contains("abyssplayer.com")) {
                try {
                    if (extractAbyssDirect(cleanUrl, "Original", callback)) {
                        loadedAny = true
                    }
                } catch (e: Exception) {
                    try {
                        if (loadExtractor(cleanUrl, data, subtitleCallback, callback)) loadedAny = true
                    } catch (_: Exception) {}
                }
            } else if (cleanUrl.contains("/video/") || cleanUrl.contains("as-cdn")) {
                try {
                    val origin = Regex("""https?://[^/]+""").find(cleanUrl)?.value ?: continue
                    val hash = cleanUrl.substringAfterLast("/video/").substringBefore("?").substringBefore("/")
                    if (hash.isNotBlank()) {
                        val getVideoUrl = "$origin/player/index.php?data=$hash&do=getVideo"
                        val postRes = app.post(
                            getVideoUrl,
                            headers = mapOf(
                                "Referer" to cleanUrl,
                                "User-Agent" to USER_AGENT,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                            ),
                            data = mapOf("hash" to hash, "r" to "$mainUrl/"),
                            timeout = 30L
                        )

                        val videoData = try {
                            parseJson<AnimeSaltVideoResponse>(postRes.text)
                        } catch (e: Exception) {
                            null
                        }

                        val streamUrl = videoData?.videoSource ?: videoData?.securedLink
                        if (!streamUrl.isNullOrBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "$name [Server 2 - Play]",
                                    url = streamUrl,
                                    type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$origin/"
                                    this.headers = mapOf(
                                        "Referer" to "$origin/",
                                        "User-Agent" to USER_AGENT
                                    )
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            loadedAny = true
                        }
                    }
                } catch (e: Exception) {
                    // Silent failover
                }
            } else {
                try {
                    if (loadExtractor(cleanUrl, data, subtitleCallback, callback)) {
                        loadedAny = true
                    }
                } catch (e: Exception) {
                    // Ignore unsupported extractors
                }
            }
        }

        return loadedAny
    }

    private suspend fun extractAbyssDirect(
        abyssUrl: String,
        audioLang: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val abyssDoc = app.get(
            abyssUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            ),
            timeout = 30L
        ).text

        val datasMatch = Regex("""const\s+datas\s*=\s*[\"']([^\"']+)[\"']""").find(abyssDoc) ?: return false
        val base64Payload = datasMatch.groupValues[1]

        val rawJson = String(Base64.decode(base64Payload, Base64.DEFAULT), Charsets.ISO_8859_1)
        val abyssData = parseJson<AbyssPayload>(rawJson)
        val mediaStr = abyssData.media ?: return false
        val userId = abyssData.userId ?: return false
        val slug = abyssData.slug ?: return false
        val md5Id = abyssData.md5Id ?: return false

        val keyStr = "$userId:$slug:$md5Id"
        val md5Key = md5Hex(keyStr)
        val keyBytes = md5Key.toByteArray(Charsets.UTF_8)
        val ivBytes = keyBytes.copyOfRange(0, 16)

        val cipherBytes = ByteArray(mediaStr.length) { i -> mediaStr[i].code.toByte() }
        val plainBytes = decryptAesCtr(cipherBytes, keyBytes, ivBytes)
        val decryptedJson = String(plainBytes, Charsets.UTF_8)

        val mediaObj = parseJson<AbyssMediaResponse>(decryptedJson)
        val sources = mediaObj.mp4?.sources ?: emptyList()
        val domains = mediaObj.mp4?.domains ?: emptyList()

        var foundStream = false
        for (src in sources) {
            val label = src.label ?: "Stream"
            val size = src.size ?: continue
            val resId = src.resId ?: continue
            val sub = src.sub ?: ""
            val domain = domains.find { it.contains(sub) } ?: domains.firstOrNull() ?: continue

            val pathStr = "/mp4/$md5Id/$resId/$size?v=$slug"
            val tokenKey = md5Hex(size.toString()).toByteArray(Charsets.UTF_8)
            val tokenIv = tokenKey.copyOfRange(0, 16)
            val tokenPlain = pathStr.toByteArray(Charsets.UTF_8)
            val tokenCipher = encryptAesCtr(tokenPlain, tokenKey, tokenIv)

            val tokenStr = String(tokenCipher, Charsets.ISO_8859_1)
            val step1 = Base64.encodeToString(tokenStr.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP).replace("=", "")
            val token = Base64.encodeToString(step1.toByteArray(Charsets.UTF_8), Base64.NO_WRAP).replace("=", "")

            val streamUrl = "https://$domain/sora/$size/$token"
            val qualityInt = when (label.lowercase()) {
                "1080p" -> Qualities.P1080.value
                "720p" -> Qualities.P720.value
                "480p" -> Qualities.P480.value
                "360p" -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name [$audioLang] $label",
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://abyssplayer.com/"
                    this.headers = mapOf(
                        "Referer" to "https://abyssplayer.com/",
                        "User-Agent" to USER_AGENT
                    )
                    this.quality = qualityInt
                }
            )
            foundStream = true
        }

        return foundStream
    }

    data class MultiLangItem(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("link") val link: String? = null
    )

    data class AbyssPayload(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("user_id") val userId: Long? = null,
        @JsonProperty("md5_id") val md5Id: Long? = null,
        @JsonProperty("media") val media: String? = null
    )

    data class AbyssMediaResponse(
        @JsonProperty("mp4") val mp4: AbyssMp4Data? = null
    )

    data class AbyssMp4Data(
        @JsonProperty("sources") val sources: List<AbyssSource>? = null,
        @JsonProperty("domains") val domains: List<String>? = null
    )

    data class AbyssSource(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("res_id") val resId: Int? = null,
        @JsonProperty("size") val size: Long? = null,
        @JsonProperty("codec") val codec: String? = null,
        @JsonProperty("sub") val sub: String? = null
    )

    data class AnimeSaltVideoResponse(
        @JsonProperty("hls") val hls: Boolean? = null,
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )
}
