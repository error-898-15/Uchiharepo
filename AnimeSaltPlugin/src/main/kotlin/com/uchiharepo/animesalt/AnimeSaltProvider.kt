package com.uchiharepo.animesalt

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

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

            fun parseEpisodesFromDoc(doc: Document, defaultSeason: Int = 1) {
                doc.select("article.episodes, li:has(article.episodes)").forEach { el ->
                    val link = el.selectFirst("a.lnk-blk, a[href*='/episode/']")?.attr("href")?.trim() ?: return@forEach
                    if (seenUrls.contains(link)) return@forEach
                    seenUrls.add(link)

                    val epNumberMatch = Regex("""(\d+)x(\d+)""").find(link)
                    val season = epNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: defaultSeason
                    val epNumber = epNumberMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1

                    val epTitle = el.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
                        ?.replace("""^(?:Episode|Ep\.?)\s*\d+\s*[:\-]?\s*""".toRegex(RegexOption.IGNORE_CASE), "")
                        ?.ifBlank { null }
                        ?: "Episode $epNumber"

                    var thumb = el.selectFirst("img")?.let {
                        val ds = it.attr("data-src").trim()
                        if (ds.isNotBlank()) ds else it.attr("src").trim()
                    }
                    if (thumb?.startsWith("//") == true) thumb = "https:$thumb"
                    if (thumb?.contains("data:image") == true) thumb = null

                    episodes.add(
                        newEpisode(link) {
                            this.name = epTitle
                            this.season = season
                            this.episode = epNumber
                            this.posterUrl = thumb
                        }
                    )
                }
            }

            // 1. Initial season episodes
            parseEpisodesFromDoc(document, 1)

            // 2. Fetch multi-seasons via AJAX (e.g. Naruto Shippuden Seasons 2-22)
            val seasonButtons = document.select("a.season-btn[data-season][data-post], [data-season][data-post]")
            for (btn in seasonButtons) {
                val sNum = btn.attr("data-season").toIntOrNull() ?: continue
                val postId = btn.attr("data-post").ifBlank { null } ?: continue
                if (sNum <= 1) continue
                try {
                    val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$sNum&post=$postId"
                    val seasonDoc = app.get(
                        ajaxUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to url,
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        timeout = 15L
                    ).document
                    parseEpisodesFromDoc(seasonDoc, sNum)
                } catch (_: Exception) {}
            }

            // Fallback
            if (episodes.isEmpty()) {
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

        // 1. High-speed Fast Original Servers & Downloads (Mega.nz, Drive, etc.)
        val downloadButtons = document.select("a[href*='trdownload='], a.btn.sm.rnd.blk").mapNotNull {
            val href = it.attr("href").trim()
            if (href.contains("trdownload=")) href else null
        }

        for (dlLink in downloadButtons) {
            try {
                val fullDl = if (dlLink.startsWith("http")) dlLink else "$mainUrl$dlLink"
                val res = app.get(
                    fullDl,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data),
                    timeout = 8L,
                    followRedirects = true
                )
                val targetUrl = res.url
                if (targetUrl.contains("mega.nz") || targetUrl.contains("drive.google.com") || targetUrl.contains("streamtape") || targetUrl.contains("filelions")) {
                    if (loadExtractor(targetUrl, data, subtitleCallback, callback)) {
                        loadedAny = true
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Find all embed/player iframes
        val embedUrls = mutableListOf<String>()
        document.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.isNotBlank()) embedUrls.add(src)
        }
        document.select("[data-src]").forEach {
            val src = it.attr("data-src").trim()
            if (src.isNotBlank() && (src.contains("embed") || src.contains("player") || src.contains("stream") || src.contains("multi-lang-plyr"))) {
                embedUrls.add(src)
            }
        }

        // 3. Process Iframes
        for (rawEmbed in embedUrls.distinct()) {
            val cleanUrl = if (rawEmbed.startsWith("//")) "https:$rawEmbed" else rawEmbed

            // 3.1 Multi-Language Player
            if (cleanUrl.contains("multi-lang-plyr.php") || cleanUrl.contains("player.php?data=")) {
                try {
                    val rawData = cleanUrl.substringAfter("data=").substringBefore("&")
                    val decodedJson = String(Base64.decode(URLDecoder.decode(rawData, "UTF-8"), Base64.DEFAULT), Charsets.UTF_8)
                    val langList = parseJson<List<MultiLangItem>>(decodedJson)

                    for (item in langList) {
                        val directLink = item.link ?: continue
                        val langLabel = item.language ?: "Audio"

                        try {
                            if (loadExtractor(directLink, data, subtitleCallback, callback)) {
                                loadedAny = true
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            // 3.2 MegaPlay (HLS stream found in Boruto, etc.)
            else if (cleanUrl.contains("megaplay")) {
                try {
                    val mpHtml = app.get(cleanUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/"), timeout = 15L).text
                    val m3u8Match = Regex("""file:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(mpHtml)
                    if (m3u8Match != null) {
                        val m3u8Url = m3u8Match.groupValues[1]
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$name (MegaPlay HLS)",
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = cleanUrl
                                this.headers = mapOf("Referer" to cleanUrl, "User-Agent" to USER_AGENT)
                            }
                        )
                        loadedAny = true
                    }
                } catch (_: Exception) {}
            }
            // 3.3 MegaVid
            else if (cleanUrl.contains("megavid")) {
                try {
                    val mvHtml = app.get(cleanUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/"), timeout = 15L).text
                    val m3u8Match = Regex("""source:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(mvHtml)
                    if (m3u8Match != null) {
                        val m3u8Url = m3u8Match.groupValues[1]
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$name (MegaVid HLS)",
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = cleanUrl
                                this.headers = mapOf("Referer" to cleanUrl, "User-Agent" to USER_AGENT)
                            }
                        )
                        loadedAny = true
                    }
                } catch (_: Exception) {}
            }
            // 3.4 General / External extractors
            else {
                try {
                    if (loadExtractor(cleanUrl, data, subtitleCallback, callback)) {
                        loadedAny = true
                    }
                } catch (_: Exception) {}
            }
        }

        return loadedAny
    }

    data class MultiLangItem(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("link") val link: String? = null
    )
}
