package com.hentaiz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder

@CloudstreamPlugin
class HentaiZPlugin : Plugin() {
    override fun load() { registerMainAPI(HentaiZProvider()) }
}

class HentaiZProvider : MainAPI() {
    override var mainUrl = "https://hentaiz.chat"
    override var name = "HentaiZ"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    val nsfw = true  // NSFW plugin

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val cfInterceptor = WebViewResolver(
        Regex(""".*hentaiz\.chat|.*haiten\.org|.*x\.haiten\.org""")
    )

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    // Image CDN base URL
    private val imgCdn = "https://storage.haiten.org"

    // ═══════════════════════════════════════════════════════════════════════════
    //  SvelteKit __data.json parser
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Fetch SvelteKit __data.json endpoint and return the parsed node data.
     * SvelteKit uses a indexed format where integers reference other positions in the array.
     */
    private suspend fun fetchSvelteData(url: String): List<List<Any?>>? {
        return try {
            val dataUrl = if (url.contains("__data.json")) url else {
                val base = url.substringBefore("?")
                val query = url.substringAfter("?", "")
                if (query.isNotEmpty()) "$base/__data.json?$query" else "$base/__data.json"
            }
            println("[HentaiZ] Fetching SvelteKit data: ${dataUrl.take(100)}")
            val resp = app.get(dataUrl, headers = commonHeaders, interceptor = cfInterceptor)
            val text = resp.text

            // Try to parse as SvelteKit data
            val jsonStr = if (text.contains("\"type\":\"data\"")) {
                text
            } else if (text.contains("<pre")) {
                // Extract from pre tag if wrapped
                Regex("<pre[^>]*>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: return null
            } else {
                return null
            }

            val svelteData = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readValue(jsonStr, Map::class.java)
            val nodes = svelteData["nodes"] as? List<Map<String, Any?>> ?: return null

            nodes.map { node ->
                (node["data"] as? List<Any?>) ?: emptyList()
            }
        } catch (e: Exception) {
            println("[HentaiZ] fetchSvelteData error: ${e.message}")
            null
        }
    }

    /**
     * Resolve SvelteKit indexed references to actual values.
     * In SvelteKit __data.json, integers are references to other positions in the array.
     */
    private fun resolveValue(data: List<Any?>, index: Int): Any? {
        if (index < 0 || index >= data.size) return null
        val value = data[index] ?: return null

        return when (value) {
            is Int -> {
                // Integers reference other positions - but only in certain contexts
                // In the top-level data array, integers ARE references
                // We need to be careful not to recursively resolve
                if (value >= 0 && value < data.size) {
                    val resolved = data[value]
                    resolved
                } else {
                    value
                }
            }
            else -> value
        }
    }

    /**
     * Resolve a map's values, replacing integer references with actual values
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveMap(data: List<Any?>, map: Map<String, Any?>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((key, value) in map) {
            result[key] = when (value) {
                is Int -> {
                    if (value >= 0 && value < data.size) data[value] else value
                }
                is Map<*, *> -> resolveMap(data, value as Map<String, Any?>)
                is List<*> -> {
                    if (value.isNotEmpty() && value[0] is Map<*, *>) {
                        value.map { if (it is Map<*, *>) resolveMap(data, it as Map<String, Any?>) else it }
                    } else {
                        value
                    }
                }
                else -> value
            }
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Episode data extraction from browse/watch pages
    // ═══════════════════════════════════════════════════════════════════════════

    data class HentaiZEpisode(
        val id: String? = null,
        val title: String? = null,
        val slug: String? = null,
        val episodeNumber: Int? = null,
        val posterPath: String? = null,
        val backdropPath: String? = null,
        val embedUrl: String? = null,
        val description: String? = null,
        val contentRating: String? = null,
        val animationType: String? = null,
        val releaseYear: Int? = null,
        val duration: Int? = null,
        val genres: List<String> = emptyList(),
        val studios: List<String> = emptyList()
    )

    /**
     * Parse episode items from browse __data.json Node 2
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseBrowseEpisodes(nodeData: List<Any?>): Pair<List<HentaiZEpisode>, Int> {
        val episodes = mutableListOf<HentaiZEpisode>()
        var totalPages = 1

        // Find the first dict in nodeData which has 'episodes' and 'totalCount' keys
        for (item in nodeData) {
            if (item is Map<*, *>) {
                val resolved = resolveMap(nodeData, item as Map<String, Any?>)
                if (resolved.containsKey("episodes") && resolved.containsKey("totalCount")) {
                    val episodeList = resolved["episodes"] as? List<Any?> ?: continue
                    totalPages = (resolved["totalPages"] as? Number)?.toInt() ?: 1

                    for (epItem in episodeList) {
                        if (epItem is Map<*, *>) {
                            val ep = resolveMap(nodeData, epItem as Map<String, Any?>)
                            val posterImg = resolveMap(nodeData, (ep["posterImage"] as? Map<String, Any?>) ?: emptyMap())
                            val backdropImg = resolveMap(nodeData, (ep["backdropImage"] as? Map<String, Any?>) ?: emptyMap())

                            episodes.add(HentaiZEpisode(
                                id = ep["id"]?.toString(),
                                title = ep["title"]?.toString(),
                                slug = ep["slug"]?.toString(),
                                episodeNumber = (ep["episodeNumber"] as? Number)?.toInt(),
                                posterPath = posterImg["filePath"]?.toString(),
                                backdropPath = backdropImg["filePath"]?.toString()
                            ))
                        }
                    }
                    break
                }
            }
        }

        // Fallback: find episode dicts directly by scanning for dicts with 'slug' and 'posterImage'
        if (episodes.isEmpty()) {
            for (item in nodeData) {
                if (item is Map<*, *>) {
                    val ep = item as Map<String, Any?>
                    if (ep.containsKey("slug") && ep.containsKey("posterImage")) {
                        val resolved = resolveMap(nodeData, ep)
                        val posterImg = resolveMap(nodeData, (resolved["posterImage"] as? Map<String, Any?>) ?: emptyMap())
                        val backdropImg = resolveMap(nodeData, (resolved["backdropImage"] as? Map<String, Any?>) ?: emptyMap())

                        episodes.add(HentaiZEpisode(
                            id = resolved["id"]?.toString(),
                            title = resolved["title"]?.toString(),
                            slug = resolved["slug"]?.toString(),
                            episodeNumber = (resolved["episodeNumber"] as? Number)?.toInt(),
                            posterPath = posterImg["filePath"]?.toString(),
                            backdropPath = backdropImg["filePath"]?.toString()
                        ))
                    }
                }
            }
        }

        return Pair(episodes, totalPages)
    }

    /**
     * Parse watch page data from __data.json Node 2
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseWatchData(nodeData: List<Any?>): HentaiZEpisode? {
        // Find the dict with 'embedUrl' key
        for (item in nodeData) {
            if (item is Map<*, *>) {
                val resolved = resolveMap(nodeData, item as Map<String, Any?>)
                if (resolved.containsKey("embedUrl")) {
                    val posterImg = resolveMap(nodeData, (resolved["posterImage"] as? Map<String, Any?>) ?: emptyMap())
                    val backdropImg = resolveMap(nodeData, (resolved["backdropImage"] as? Map<String, Any?>) ?: emptyMap())

                    // Extract genres
                    val genresList = mutableListOf<String>()
                    (resolved["genres"] as? List<Any?>)?.forEach { genreItem ->
                        if (genreItem is Map<*, *>) {
                            val genreResolved = resolveMap(nodeData, genreItem as Map<String, Any?>)
                            val genreData = genreResolved["genre"] as? Map<String, Any?>
                            if (genreData != null) {
                                val genreResolved2 = resolveMap(nodeData, genreData)
                                genreResolved2["name"]?.let { genresList.add(it.toString()) }
                            }
                        }
                    }

                    // Extract studios
                    val studiosList = mutableListOf<String>()
                    (resolved["studios"] as? List<Any?>)?.forEach { studioItem ->
                        if (studioItem is Map<*, *>) {
                            val studioResolved = resolveMap(nodeData, studioItem as Map<String, Any?>)
                            val studioData = studioResolved["studio"] as? Map<String, Any?>
                            if (studioData != null) {
                                val studioResolved2 = resolveMap(nodeData, studioData)
                                studioResolved2["name"]?.let { studiosList.add(it.toString()) }
                            }
                        }
                    }

                    return HentaiZEpisode(
                        id = resolved["id"]?.toString(),
                        title = resolved["title"]?.toString(),
                        slug = resolved["slug"]?.toString(),
                        episodeNumber = (resolved["episodeNumber"] as? Number)?.toInt(),
                        posterPath = posterImg["filePath"]?.toString(),
                        backdropPath = backdropImg["filePath"]?.toString(),
                        embedUrl = resolved["embedUrl"]?.toString(),
                        description = resolved["description"]?.toString(),
                        contentRating = resolved["contentRating"]?.toString(),
                        animationType = resolved["animationType"]?.toString(),
                        releaseYear = (resolved["releaseYear"] as? Number)?.toInt(),
                        duration = (resolved["duration"] as? Number)?.toInt(),
                        genres = genresList,
                        studios = studiosList
                    )
                }
            }
        }
        return null
    }

    /**
     * Get all genres list from watch page data
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseAllGenres(nodeData: List<Any?>): List<Pair<String, String>> {
        val genres = mutableListOf<Pair<String, String>>()
        for (item in nodeData) {
            if (item is Map<*, *>) {
                val resolved = resolveMap(nodeData, item as Map<String, Any?>)
                if (resolved.containsKey("allGenres")) {
                    val genreList = resolved["allGenres"] as? List<Any?> ?: continue
                    for (genreItem in genreList) {
                        if (genreItem is Map<*, *>) {
                            val g = resolveMap(nodeData, genreItem as Map<String, Any?>)
                            val name = g["name"]?.toString() ?: continue
                            val slug = g["slug"]?.toString() ?: continue
                            genres.add(Pair(name, slug))
                        }
                    }
                    break
                }
            }
        }
        return genres
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Main page & search
    // ═══════════════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "publishedAt_desc" to "Phim M\u1EDBi Nh\u1EA5t",
        "views_desc" to "Xem Nhi\u1EC1u",
        "likes_desc" to "Th\u00EDch Nh\u1EA5t",
        "UNCENSORED" to "Kh\u00F4ng Che",
        "3D" to "3D"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sort = request.data
        val isContentRating = sort in listOf("UNCENSORED", "CENSORED")
        val isAnimType = sort in listOf("2D", "3D")

        val url = if (isContentRating) {
            "$mainUrl/browse/__data.json?sort=publishedAt_desc&page=$page&limit=24&animationType=ALL&contentRating=$sort&isTrailer=ALL&year=ALL"
        } else if (isAnimType) {
            "$mainUrl/browse/__data.json?sort=publishedAt_desc&page=$page&limit=24&animationType=$sort&contentRating=ALL&isTrailer=ALL&year=ALL"
        } else {
            "$mainUrl/browse/__data.json?sort=$sort&page=$page&limit=24&animationType=ALL&contentRating=ALL&isTrailer=ALL&year=ALL"
        }

        val nodes = fetchSvelteData(url) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val nodeData = nodes.getOrNull(2) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val (episodes, totalPages) = parseBrowseEpisodes(nodeData)

        val items = episodes.mapNotNull { ep ->
            val title = ep.title ?: return@mapNotNull null
            val slug = ep.slug ?: return@mapNotNull null
            val poster = ep.posterPath?.let { "$imgCdn$it" }
            newAnimeSearchResponse(title, "$mainUrl/watch/$slug", TvType.Anime) {
                this.posterUrl = poster
                this.quality = SearchQuality.HD
            }
        }

        return newHomePageResponse(request.name, items, hasNext = page < totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browse/__data.json?sort=publishedAt_desc&page=1&limit=24&animationType=ALL&contentRating=ALL&isTrailer=ALL&year=ALL&q=${URLEncoder.encode(query, "UTF-8")}"
        val nodes = fetchSvelteData(url) ?: return emptyList()
        val nodeData = nodes.getOrNull(2) ?: return emptyList()
        val (episodes, _) = parseBrowseEpisodes(nodeData)

        return episodes.mapNotNull { ep ->
            val title = ep.title ?: return@mapNotNull null
            val slug = ep.slug ?: return@mapNotNull null
            val poster = ep.posterPath?.let { "$imgCdn$it" }
            newAnimeSearchResponse(title, "$mainUrl/watch/$slug", TvType.Anime) {
                this.posterUrl = poster
                this.quality = SearchQuality.HD
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Load detail & episodes
    // ═══════════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        // URL format: https://hentaiz.chat/watch/{slug}
        val slug = url.trim().trimEnd('/').substringAfterLast("/watch/", "").substringBefore("?")
        if (slug.isBlank()) throw ErrorLoadingException("URL kh\u00F4ng h\u1EE3p l\u1EC7")

        // Fetch watch page data
        val watchUrl = "$mainUrl/watch/$slug"
        val nodes = fetchSvelteData("$watchUrl/__data.json")
            ?: throw ErrorLoadingException("Kh\u00F4ng th\u1EC3 t\u1EA3i d\u1EEF li\u1EC7u")

        val nodeData = nodes.getOrNull(2) ?: throw ErrorLoadingException("Kh\u00F4ng t\u00ECm th\u1EA5y d\u1EEF li\u1EC7u t\u1EADp phim")
        val epInfo = parseWatchData(nodeData) ?: throw ErrorLoadingException("Kh\u00F4ng th\u1EC3 parse d\u1EEF li\u1EC7u")

        val title = epInfo.title ?: "Unknown"
        val poster = epInfo.posterPath?.let { "$imgCdn$it" }
        val backdrop = epInfo.backdropPath?.let { "$imgCdn$it" }
        val description = epInfo.description?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""

        // Build plot with info
        val plot = buildString {
            if (description.isNotBlank()) append(description)
            if (epInfo.contentRating != null) append("\n\nCensure: ${epInfo.contentRating}")
            if (epInfo.animationType != null) append(" | Type: ${epInfo.animationType}")
            if (epInfo.releaseYear != null) append(" | Year: ${epInfo.releaseYear}")
            if (epInfo.duration != null && epInfo.duration > 0) append(" | Duration: ${epInfo.duration} min")
            if (epInfo.studios.isNotEmpty()) append("\nStudio: ${epInfo.studios.joinToString(", ")}")
            if (epInfo.genres.isNotEmpty()) append("\nGenres: ${epInfo.genres.joinToString(", ")}")
        }

        // For hentai, each URL is a single episode
        // The embed URL is the video source
        val embedUrl = epInfo.embedUrl

        // Store both the slug and embedUrl in episode data
        val episodeData = if (embedUrl != null) {
            "$slug::EMBED::$embedUrl"
        } else {
            slug
        }

        val episodes = listOf(
            newEpisode(episodeData) {
                this.name = if (epInfo.episodeNumber != null) "T\u1EADp ${epInfo.episodeNumber}" else "Full"
                this.episode = epInfo.episodeNumber
            }
        )

        val tvType = if (epInfo.episodeNumber != null && epInfo.episodeNumber > 1) TvType.TvSeries else TvType.Movie

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.tags = epInfo.genres
            this.year = epInfo.releaseYear
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Load links - extract video from embed
    // ═══════════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[HentaiZ] loadLinks data: ${data.take(100)}")

        var embedUrl: String? = null
        var slug: String? = null

        // Parse episode data: "slug::EMBED::embedUrl" or just "slug"
        if (data.contains("::EMBED::")) {
            val parts = data.split("::EMBED::", limit = 2)
            slug = parts.getOrNull(0)
            embedUrl = parts.getOrNull(1)
        } else {
            slug = data
        }

        // If embedUrl was found from __data.json, use it directly
        if (embedUrl.isNullOrBlank()) {
            // Fallback: fetch the watch page __data.json to get embedUrl
            println("[HentaiZ] No embed URL in data, fetching from API...")
            val nodes = fetchSvelteData("$mainUrl/watch/$slug/__data.json")
            if (nodes != null) {
                val nodeData = nodes.getOrNull(2)
                if (nodeData != null) {
                    val epInfo = parseWatchData(nodeData)
                    embedUrl = epInfo?.embedUrl
                }
            }
        }

        if (embedUrl.isNullOrBlank()) {
            println("[HentaiZ] No embed URL found!")
            return false
        }

        println("[HentaiZ] Embed URL: $embedUrl")

        // The embed URL is like: https://x.haiten.org/watch?v=UUID
        // We need to extract the video source from this embed page
        try {
            val embedResp = app.get(embedUrl, headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ), interceptor = cfInterceptor)
            val embedHtml = embedResp.text

            // Strategy 1: Find m3u8 URL directly in the page
            val m3u8Patterns = listOf(
                Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"""),
                Regex("""file\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""src\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""source\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""url\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
            )

            for (pattern in m3u8Patterns) {
                val match = pattern.find(embedHtml)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    if (m3u8Url.isNotBlank() && !m3u8Url.startsWith("blob:")) {
                        println("[HentaiZ] Found m3u8: ${m3u8Url.take(80)}")
                        callback(newExtractorLink("HentaiZ", "HentaiZ", m3u8Url, ExtractorLinkType.M3U8) {
                            quality = Qualities.P1080.value
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to embedUrl,
                                "Origin" to Regex("""(https?://[^/]+)""").find(embedUrl)?.groupValues?.get(1).orEmpty()
                            )
                        })
                        return true
                    }
                }
            }

            // Strategy 2: Find mp4 URL
            val mp4Patterns = listOf(
                Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)"""),
                Regex("""file\s*[:=]\s*["']([^"']+\.mp4[^"']*)["']"""),
                Regex("""src\s*[:=]\s*["']([^"']+\.mp4[^"']*)["']""")
            )

            for (pattern in mp4Patterns) {
                val match = pattern.find(embedHtml)
                if (match != null) {
                    val mp4Url = match.groupValues[1]
                    if (mp4Url.isNotBlank()) {
                        println("[HentaiZ] Found mp4: ${mp4Url.take(80)}")
                        callback(newExtractorLink("HentaiZ", "HentaiZ", mp4Url, ExtractorLinkType.VIDEO) {
                            quality = Qualities.P1080.value
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to embedUrl
                            )
                        })
                        return true
                    }
                }
            }

            // Strategy 3: Use WebView to let the player load and capture the video URL
            println("[HentaiZ] Trying WebView interception for video URL...")
            val videoInterceptor = WebViewResolver(
                Regex(""".*\.(m3u8|m3u9|mp4)(\?|$)""")
            )
            try {
                val resp = app.get(embedUrl, interceptor = videoInterceptor, headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT
                ))
                val content = resp.text
                val capturedUrl = resp.url ?: ""

                if (content.contains("#EXTM3U") || capturedUrl.contains(".m3u8") || capturedUrl.contains(".m3u9")) {
                    val m3u8Url = if (capturedUrl.contains(".m3u8") || capturedUrl.contains(".m3u9")) capturedUrl else {
                        // Extract m3u8 URL from content
                        Regex("""(https?://[^\s]+\.m3u[89][^\s]*)""").find(content)?.groupValues?.get(1) ?: ""
                    }
                    if (m3u8Url.isNotBlank()) {
                        println("[HentaiZ] WebView captured: ${m3u8Url.take(80)}")
                        callback(newExtractorLink("HentaiZ", "HentaiZ", m3u8Url, ExtractorLinkType.M3U8) {
                            quality = Qualities.P1080.value
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to embedUrl
                            )
                        })
                        return true
                    }
                }

                if (content.contains("ftyp") || capturedUrl.contains(".mp4")) {
                    val mp4Url = if (capturedUrl.contains(".mp4")) capturedUrl else ""
                    if (mp4Url.isNotBlank()) {
                        callback(newExtractorLink("HentaiZ", "HentaiZ", mp4Url, ExtractorLinkType.VIDEO) {
                            quality = Qualities.P1080.value
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to embedUrl
                            )
                        })
                        return true
                    }
                }
            } catch (e: Exception) {
                println("[HentaiZ] WebView interception failed: ${e.message}")
            }

            // Strategy 4: If embedHtml contains a blob: URL or video player, try broader interceptor
            println("[HentaiZ] Trying broader WebView interceptor...")
            try {
                val broadInterceptor = WebViewResolver(
                    Regex(""".*haiten\.org.*""")
                )
                val resp = app.get(embedUrl, interceptor = broadInterceptor, headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT
                ))
                val content = resp.text
                val capturedUrl = resp.url ?: ""

                if (content.contains("#EXTM3U")) {
                    val m3u8Base = if (capturedUrl.isNotEmpty()) capturedUrl.substringBeforeLast("/") + "/" else ""
                    println("[HentaiZ] Broad interceptor got m3u8 content!")
                    // Register via local proxy
                    callback(newExtractorLink("HentaiZ", "HentaiZ", capturedUrl.ifBlank { embedUrl }, ExtractorLinkType.M3U8) {
                        quality = Qualities.P1080.value
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embedUrl)
                    })
                    return true
                }
            } catch (e: Exception) {
                println("[HentaiZ] Broad interceptor failed: ${e.message}")
            }

            println("[HentaiZ] All strategies failed for: $embedUrl")

        } catch (e: Exception) {
            println("[HentaiZ] loadLinks error: ${e.message}")
        }

        return false
    }
}
