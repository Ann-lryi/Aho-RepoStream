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
    val nsfw = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // cfInterceptor CHỈ dùng khi direct request fail — không dùng mặc định
    private val cfInterceptor = WebViewResolver(
        Regex(""".*hentaiz\.chat|.*haiten\.org|.*x\.haiten\.org""")
    )

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    private val apiHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json,*/*;q=0.9",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    private val imgCdn = "https://storage.haiten.org"

    // ═══════════════════════════════════════════════════════════════════════════
    //  SvelteKit __data.json parser
    //  FIX: Thử direct HTTP trước, chỉ dùng WebView (cfInterceptor) khi fail
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun fetchSvelteData(url: String): List<List<Any?>>? {
        return try {
            val dataUrl = if (url.contains("__data.json")) url else {
                val base = url.substringBefore("?")
                val query = url.substringAfter("?", "")
                if (query.isNotEmpty()) "$base/__data.json?$query" else "$base/__data.json"
            }
            println("[HentaiZ] Fetching: ${dataUrl.take(120)}")

            // FIX: Thử direct trước (không WebView) — nhanh hơn ~60 lần
            val directResp = try {
                val r = app.get(dataUrl, headers = apiHeaders)
                if (r.isSuccessful && r.text.let { it.contains("\"type\"") || it.contains("\"nodes\"") }) r
                else null
            } catch (e: Exception) {
                println("[HentaiZ] Direct request failed: ${e.message}, falling back to WebView...")
                null
            }

            // Chỉ dùng cfInterceptor (WebView) khi direct request thật sự fail
            val resp = directResp ?: run {
                println("[HentaiZ] Using cfInterceptor (WebView) for: ${dataUrl.take(80)}")
                app.get(dataUrl, headers = commonHeaders, interceptor = cfInterceptor)
            }

            val text = resp.text
            val jsonStr = when {
                text.contains("\"type\":\"data\"") -> text
                text.contains("\"nodes\"") -> text
                text.contains("<pre") -> Regex("<pre[^>]*>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL)
                    .find(text)?.groupValues?.get(1) ?: return null
                else -> return null
            }

            val svelteData = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                .readValue(jsonStr, Map::class.java)
            val nodes = svelteData["nodes"] as? List<Map<String, Any?>> ?: return null
            nodes.map { node -> (node["data"] as? List<Any?>) ?: emptyList() }
        } catch (e: Exception) {
            println("[HentaiZ] fetchSvelteData error: ${e.message}")
            null
        }
    }

    private fun resolveValue(data: List<Any?>, index: Int): Any? {
        if (index < 0 || index >= data.size) return null
        val value = data[index] ?: return null
        return when (value) {
            is Int -> if (value >= 0 && value < data.size) data[value] else value
            else -> value
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveMap(data: List<Any?>, map: Map<String, Any?>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((key, value) in map) {
            result[key] = when (value) {
                is Int -> if (value >= 0 && value < data.size) data[value] else value
                is Map<*, *> -> resolveMap(data, value as Map<String, Any?>)
                is List<*> -> {
                    if (value.isNotEmpty() && value[0] is Map<*, *>)
                        value.map { if (it is Map<*, *>) resolveMap(data, it as Map<String, Any?>) else it }
                    else value
                }
                else -> value
            }
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Data classes & parsers
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

    @Suppress("UNCHECKED_CAST")
    private fun parseBrowseEpisodes(nodeData: List<Any?>): Pair<List<HentaiZEpisode>, Int> {
        val episodes = mutableListOf<HentaiZEpisode>()
        var totalPages = 1

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

        // Fallback
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

    @Suppress("UNCHECKED_CAST")
    private fun parseWatchData(nodeData: List<Any?>): HentaiZEpisode? {
        for (item in nodeData) {
            if (item is Map<*, *>) {
                val resolved = resolveMap(nodeData, item as Map<String, Any?>)
                if (resolved.containsKey("embedUrl")) {
                    val posterImg = resolveMap(nodeData, (resolved["posterImage"] as? Map<String, Any?>) ?: emptyMap())
                    val backdropImg = resolveMap(nodeData, (resolved["backdropImage"] as? Map<String, Any?>) ?: emptyMap())

                    val genresList = mutableListOf<String>()
                    (resolved["genres"] as? List<Any?>)?.forEach { genreItem ->
                        if (genreItem is Map<*, *>) {
                            val g = resolveMap(nodeData, genreItem as Map<String, Any?>)
                            val gData = g["genre"] as? Map<String, Any?>
                            if (gData != null) resolveMap(nodeData, gData)["name"]?.let { genresList.add(it.toString()) }
                        }
                    }

                    val studiosList = mutableListOf<String>()
                    (resolved["studios"] as? List<Any?>)?.forEach { studioItem ->
                        if (studioItem is Map<*, *>) {
                            val s = resolveMap(nodeData, studioItem as Map<String, Any?>)
                            val sData = s["studio"] as? Map<String, Any?>
                            if (sData != null) resolveMap(nodeData, sData)["name"]?.let { studiosList.add(it.toString()) }
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
        "publishedAt_desc" to "Phim Mới Nhất",
        "views_desc" to "Xem Nhiều",
        "likes_desc" to "Thích Nhất",
        "UNCENSORED" to "Không Che",
        "3D" to "3D"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sort = request.data
        val isContentRating = sort in listOf("UNCENSORED", "CENSORED")
        val isAnimType = sort in listOf("2D", "3D")

        val url = when {
            isContentRating -> "$mainUrl/browse/__data.json?sort=publishedAt_desc&page=$page&limit=24&animationType=ALL&contentRating=$sort&isTrailer=ALL&year=ALL"
            isAnimType -> "$mainUrl/browse/__data.json?sort=publishedAt_desc&page=$page&limit=24&animationType=$sort&contentRating=ALL&isTrailer=ALL&year=ALL"
            else -> "$mainUrl/browse/__data.json?sort=$sort&page=$page&limit=24&animationType=ALL&contentRating=ALL&isTrailer=ALL&year=ALL"
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
        val slug = url.trim().trimEnd('/').substringAfterLast("/watch/", "").substringBefore("?")
        if (slug.isBlank()) throw ErrorLoadingException("URL không hợp lệ")

        val watchUrl = "$mainUrl/watch/$slug"
        val nodes = fetchSvelteData("$watchUrl/__data.json")
            ?: throw ErrorLoadingException("Không thể tải dữ liệu")

        val nodeData = nodes.getOrNull(2) ?: throw ErrorLoadingException("Không tìm thấy dữ liệu tập phim")
        val epInfo = parseWatchData(nodeData) ?: throw ErrorLoadingException("Không thể parse dữ liệu")

        val title = epInfo.title ?: "Unknown"
        val poster = epInfo.posterPath?.let { "$imgCdn$it" }
        val backdrop = epInfo.backdropPath?.let { "$imgCdn$it" }
        val description = epInfo.description?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""

        val plot = buildString {
            if (description.isNotBlank()) append(description)
            if (epInfo.contentRating != null) append("\n\nCensure: ${epInfo.contentRating}")
            if (epInfo.animationType != null) append(" | Type: ${epInfo.animationType}")
            if (epInfo.releaseYear != null) append(" | Year: ${epInfo.releaseYear}")
            if (epInfo.duration != null && epInfo.duration > 0) append(" | Duration: ${epInfo.duration} min")
            if (epInfo.studios.isNotEmpty()) append("\nStudio: ${epInfo.studios.joinToString(", ")}")
            if (epInfo.genres.isNotEmpty()) append("\nGenres: ${epInfo.genres.joinToString(", ")}")
        }

        val embedUrl = epInfo.embedUrl
        val episodeData = if (embedUrl != null) "$slug::EMBED::$embedUrl" else slug

        val episodes = listOf(
            newEpisode(episodeData) {
                this.name = if (epInfo.episodeNumber != null) "Tập ${epInfo.episodeNumber}" else "Full"
                this.episode = epInfo.episodeNumber
            }
        )

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.tags = epInfo.genres
            this.year = epInfo.releaseYear
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Load links — FIX: Giảm từ 4 WebView xuống còn tối đa 1
    // ═══════════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[HentaiZ] loadLinks: ${data.take(100)}")

        var embedUrl: String? = null
        var slug: String? = null

        if (data.contains("::EMBED::")) {
            val parts = data.split("::EMBED::", limit = 2)
            slug = parts.getOrNull(0)
            embedUrl = parts.getOrNull(1)
        } else {
            slug = data
        }

        // Nếu không có embedUrl, lấy từ watch __data.json (dùng fetchSvelteData cải tiến)
        if (embedUrl.isNullOrBlank() && slug != null) {
            println("[HentaiZ] No embed URL in data, fetching from watch page...")
            val nodes = fetchSvelteData("$mainUrl/watch/$slug")
            val nodeData = nodes?.getOrNull(2)
            if (nodeData != null) {
                embedUrl = parseWatchData(nodeData)?.embedUrl
            }
        }

        if (embedUrl.isNullOrBlank()) {
            println("[HentaiZ] No embed URL found!")
            return false
        }

        println("[HentaiZ] Embed URL: $embedUrl")

        // ── Strategy 0 (NHANH ~1-3s, 0 WebView) ──────────────────────────────
        // x.haiten.org là SvelteKit → thử /watch/__data.json?v=UUID trực tiếp
        // Đây là nơi player lấy stream URL, không cần render JS
        val uuid = Regex("""[?&]v=([^&\s]+)""").find(embedUrl)?.groupValues?.get(1)
        if (uuid != null) {
            println("[HentaiZ] Strategy 0: trying embed __data.json, uuid=$uuid")
            try {
                val dataUrl = "https://x.haiten.org/watch/__data.json?v=$uuid"
                val resp = app.get(dataUrl, headers = apiHeaders + mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                ))
                if (resp.isSuccessful) {
                    val text = resp.text
                    println("[HentaiZ] __data.json response length: ${text.length}")

                    // Tìm m3u8 hoặc mp4 trong JSON
                    val videoUrl =
                        Regex("""["'`](https?://[^"'`\s\\]+\.m3u[89][^"'`\s\\]*)["'`]""").find(text)?.groupValues?.get(1)
                        ?: Regex("""["'`](https?://[^"'`\s\\]+\.mp4[^"'`\s\\]*)["'`]""").find(text)?.groupValues?.get(1)

                    if (!videoUrl.isNullOrBlank() && !videoUrl.startsWith("blob:")) {
                        println("[HentaiZ] Strategy 0 SUCCESS: ${videoUrl.take(80)}")
                        val isM3u8 = videoUrl.contains(".m3u")
                        callback(newExtractorLink(
                            "HentaiZ", "HentaiZ Stream", videoUrl,
                            if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            quality = Qualities.P1080.value
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to embedUrl,
                                "Origin" to "https://x.haiten.org"
                            )
                        })
                        return true
                    } else {
                        println("[HentaiZ] Strategy 0: no video URL in __data.json, response snippet: ${text.take(300)}")
                    }
                }
            } catch (e: Exception) {
                println("[HentaiZ] Strategy 0 failed: ${e.message}")
            }
        }

        // ── Strategy 1 (~30-60s, 1 WebView) ──────────────────────────────────
        // WebViewResolver bắt đúng network request đến file video
        // Không dùng cfInterceptor — chỉ cần intercept HLS/MP4 request
        println("[HentaiZ] Strategy 1: WebViewResolver for HLS/MP4 capture...")
        try {
            val videoInterceptor = WebViewResolver(
                Regex(""".*\.(m3u8|m3u9)(\?.*)?${'$'}|.*master\.m3u8.*|.*playlist\.m3u8.*|.*index\.m3u8.*""")
            )
            val resp = app.get(
                embedUrl,
                interceptor = videoInterceptor,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT
                )
            )
            val capturedUrl = resp.url ?: ""
            val content = resp.text

            // Kiểm tra m3u8
            val m3u8Url = when {
                capturedUrl.contains(".m3u") -> capturedUrl
                content.contains("#EXTM3U") ->
                    Regex("""(https?://[^\s"']+\.m3u[89][^\s"']*)""").find(content)?.groupValues?.get(1) ?: ""
                else -> ""
            }

            if (m3u8Url.isNotBlank()) {
                println("[HentaiZ] Strategy 1 HLS SUCCESS: ${m3u8Url.take(80)}")
                callback(newExtractorLink("HentaiZ", "HentaiZ HLS", m3u8Url, ExtractorLinkType.M3U8) {
                    quality = Qualities.P1080.value
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl,
                        "Origin" to "https://x.haiten.org"
                    )
                })
                return true
            }

            // Kiểm tra mp4
            if (capturedUrl.contains(".mp4")) {
                println("[HentaiZ] Strategy 1 MP4 SUCCESS: ${capturedUrl.take(80)}")
                callback(newExtractorLink("HentaiZ", "HentaiZ MP4", capturedUrl, ExtractorLinkType.VIDEO) {
                    quality = Qualities.P1080.value
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl
                    )
                })
                return true
            }

            println("[HentaiZ] Strategy 1: captured URL=${capturedUrl.take(80)}, content length=${content.length}")
        } catch (e: Exception) {
            println("[HentaiZ] Strategy 1 failed: ${e.message}")
        }

        println("[HentaiZ] All strategies exhausted for: $embedUrl")
        return false
    }
}
