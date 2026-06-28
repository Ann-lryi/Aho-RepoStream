package com.hentaiz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@CloudstreamPlugin
class HentaiZPlugin : Plugin() {
    override fun load() { registerMainAPI(HentaiZProvider()) }
}

class HentaiZProvider : MainAPI() {
    override var mainUrl        = "https://hentaiz.chat"
    override var name           = "HentaiZ"
    override val hasMainPage    = true
    override var lang           = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    val nsfw = true

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val imgCdn     = "https://storage.haiten.org"

    // Chỉ dùng WebView khi direct HTTP thực sự fail
    private val cfInterceptor = WebViewResolver(
        Regex(""".*hentaiz\.chat|.*haiten\.org|.*x\.haiten\.org""")
    )

    private val commonHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8"
    )
    private val apiHeaders = commonHeaders + mapOf(
        "Accept" to "application/json,*/*;q=0.8"
    )

    // Danh sách CDN animez.top — thử tuần tự khi 404
    private val CDN_HOSTS = listOf(
        "c2.animez.top", "c1.animez.top", "c3.animez.top",
        "c4.animez.top", "c5.animez.top", "cdn.animez.top",
        "storage.animez.top", "media.animez.top"
    )
    private val M3U8_NAMES = listOf(
        "master.m3u8", "index.m3u8", "playlist.m3u8", "video.m3u8"
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  SvelteKit __data.json — thử direct trước, WebView sau
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun fetchSvelteData(url: String): List<List<Any?>>? {
        return try {
            val dataUrl = if (url.contains("__data.json")) url else {
                val base  = url.substringBefore("?")
                val query = url.substringAfter("?", "")
                if (query.isNotEmpty()) "$base/__data.json?$query" else "$base/__data.json"
            }
            println("[HentaiZ] Fetch: ${dataUrl.take(110)}")

            val direct = try {
                app.get(dataUrl, headers = apiHeaders).takeIf {
                    it.isSuccessful && it.text.let { t -> t.contains("\"nodes\"") || t.contains("\"type\"") }
                }
            } catch (e: Exception) { null }

            val resp = direct ?: run {
                println("[HentaiZ] direct fail → WebView")
                app.get(dataUrl, headers = commonHeaders, interceptor = cfInterceptor)
            }

            val text = resp.text
            val jsonStr = when {
                text.contains("\"nodes\"") -> text
                text.contains("\"type\":\"data\"") -> text
                text.contains("<pre") ->
                    Regex("<pre[^>]*>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL)
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

    // ═══════════════════════════════════════════════════════════════════════
    //  Build browse URL
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildBrowseUrl(
        page: Int,
        sort: String   = "publishedAt_desc",
        contentRating: String = "ALL",
        animationType: String = "ALL",
        genre: String  = "",
        query: String  = ""
    ): String {
        val sb = StringBuilder("$mainUrl/browse/__data.json")
        sb.append("?sort=$sort&page=$page&limit=24")
        sb.append("&animationType=$animationType")
        sb.append("&contentRating=$contentRating")
        sb.append("&isTrailer=ALL&year=ALL")
        if (genre.isNotEmpty())  sb.append("&genre=${URLEncoder.encode(genre, "UTF-8")}")
        if (query.isNotEmpty())  sb.append("&q=${URLEncoder.encode(query, "UTF-8")}")
        return sb.toString()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Data parsers
    // ═══════════════════════════════════════════════════════════════════════

    @Suppress("UNCHECKED_CAST")
    private fun resolveMap(data: List<Any?>, map: Map<String, Any?>): Map<String, Any?> {
        val r = mutableMapOf<String, Any?>()
        for ((k, v) in map) r[k] = when (v) {
            is Int -> if (v >= 0 && v < data.size) data[v] else v
            is Map<*, *> -> resolveMap(data, v as Map<String, Any?>)
            is List<*>   -> if (v.isNotEmpty() && v[0] is Map<*, *>)
                v.map { if (it is Map<*, *>) resolveMap(data, it as Map<String, Any?>) else it }
            else v
            else -> v
        }
        return r
    }

    data class EpInfo(
        val id: String?           = null,
        val title: String?        = null,
        val slug: String?         = null,
        val episodeNumber: Int?   = null,
        val posterPath: String?   = null,
        val backdropPath: String? = null,
        val embedUrl: String?     = null,
        val description: String?  = null,
        val contentRating: String?= null,
        val animationType: String?= null,
        val releaseYear: Int?     = null,
        val duration: Int?        = null,
        val genres: List<String>  = emptyList(),
        val genreSlugs: List<String> = emptyList(),
        val studios: List<String> = emptyList()
    )

    @Suppress("UNCHECKED_CAST")
    private fun parseBrowse(nodeData: List<Any?>): Pair<List<EpInfo>, Int> {
        val list  = mutableListOf<EpInfo>()
        var pages = 1
        for (item in nodeData) {
            if (item !is Map<*, *>) continue
            val r = resolveMap(nodeData, item as Map<String, Any?>)

            // Browse format: {episodes, totalCount, totalPages}
            // Genre format:  {genre, episodes, totalEpisodes}
            val hasEpisodes = r.containsKey("episodes")
            val isBrowse    = hasEpisodes && r.containsKey("totalCount")
            val isGenre     = hasEpisodes && r.containsKey("totalEpisodes")
            if (!isBrowse && !isGenre) continue

            pages = when {
                isBrowse -> (r["totalPages"] as? Number)?.toInt() ?: 1
                else -> {
                    val total = (r["totalEpisodes"] as? Number)?.toInt() ?: 0
                    kotlin.math.ceil(total / 24.0).toInt().coerceAtLeast(1)
                }
            }

            for (epItem in (r["episodes"] as? List<Any?> ?: continue)) {
                if (epItem !is Map<*, *>) continue
                val ep = resolveMap(nodeData, epItem as Map<String, Any?>)
                val poster   = resolveMap(nodeData, (ep["posterImage"]   as? Map<String,Any?>) ?: emptyMap())
                val backdrop = resolveMap(nodeData, (ep["backdropImage"] as? Map<String,Any?>) ?: emptyMap())
                list.add(EpInfo(
                    id            = ep["id"]?.toString(),
                    title         = ep["title"]?.toString(),
                    slug          = ep["slug"]?.toString(),
                    episodeNumber = (ep["episodeNumber"] as? Number)?.toInt(),
                    posterPath    = poster["filePath"]?.toString(),
                    backdropPath  = backdrop["filePath"]?.toString()
                ))
            }
            break
        }
        // fallback
        if (list.isEmpty()) {
            for (item in nodeData) {
                if (item !is Map<*, *>) continue
                val ep = item as Map<String,Any?>
                if (!ep.containsKey("slug") || !ep.containsKey("posterImage")) continue
                val r      = resolveMap(nodeData, ep)
                val poster = resolveMap(nodeData, (r["posterImage"]   as? Map<String,Any?>) ?: emptyMap())
                val back   = resolveMap(nodeData, (r["backdropImage"] as? Map<String,Any?>) ?: emptyMap())
                list.add(EpInfo(
                    id            = r["id"]?.toString(),
                    title         = r["title"]?.toString(),
                    slug          = r["slug"]?.toString(),
                    episodeNumber = (r["episodeNumber"] as? Number)?.toInt(),
                    posterPath    = poster["filePath"]?.toString(),
                    backdropPath  = back["filePath"]?.toString()
                ))
            }
        }
        return Pair(list, pages)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseWatch(nodeData: List<Any?>): EpInfo? {
        for (item in nodeData) {
            if (item !is Map<*, *>) continue
            val r = resolveMap(nodeData, item as Map<String, Any?>)
            if (!r.containsKey("embedUrl")) continue
            val poster   = resolveMap(nodeData, (r["posterImage"]   as? Map<String,Any?>) ?: emptyMap())
            val backdrop = resolveMap(nodeData, (r["backdropImage"] as? Map<String,Any?>) ?: emptyMap())

            val genreNames  = mutableListOf<String>()
            val genreSlugs  = mutableListOf<String>()
            for (g in (r["genres"] as? List<Any?> ?: emptyList())) {
                if (g !is Map<*, *>) continue
                val gMap  = resolveMap(nodeData, g as Map<String,Any?>)
                val inner = (gMap["genre"] as? Map<String,Any?>)?.let { resolveMap(nodeData, it) } ?: continue
                inner["name"]?.let  { genreNames.add(it.toString()) }
                inner["slug"]?.let  { genreSlugs.add(it.toString()) }
            }
            val studios = mutableListOf<String>()
            for (s in (r["studios"] as? List<Any?> ?: emptyList())) {
                if (s !is Map<*, *>) continue
                val sMap  = resolveMap(nodeData, s as Map<String,Any?>)
                val inner = (sMap["studio"] as? Map<String,Any?>)?.let { resolveMap(nodeData, it) } ?: continue
                inner["name"]?.let { studios.add(it.toString()) }
            }
            return EpInfo(
                id            = r["id"]?.toString(),
                title         = r["title"]?.toString(),
                slug          = r["slug"]?.toString(),
                episodeNumber = (r["episodeNumber"] as? Number)?.toInt(),
                posterPath    = poster["filePath"]?.toString(),
                backdropPath  = backdrop["filePath"]?.toString(),
                embedUrl      = r["embedUrl"]?.toString(),
                description   = r["description"]?.toString(),
                contentRating = r["contentRating"]?.toString(),
                animationType = r["animationType"]?.toString(),
                releaseYear   = (r["releaseYear"] as? Number)?.toInt(),
                duration      = (r["duration"] as? Number)?.toInt(),
                genres        = genreNames,
                genreSlugs    = genreSlugs,
                studios       = studios
            )
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseGenres(nodeData: List<Any?>): List<Pair<String,String>> {
        for (item in nodeData) {
            if (item !is Map<*, *>) continue
            val r = resolveMap(nodeData, item as Map<String,Any?>)
            val all = r["allGenres"] as? List<Any?> ?: continue
            return all.mapNotNull { g ->
                if (g !is Map<*, *>) return@mapNotNull null
                val gm   = resolveMap(nodeData, g as Map<String,Any?>)
                val name = gm["name"]?.toString() ?: return@mapNotNull null
                val slug = gm["slug"]?.toString() ?: return@mapNotNull null
                Pair(name, slug)
            }
        }
        return emptyList()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun posterUrl(path: String?): String? {
        if (path == null) return null
        return if (path.startsWith("http")) path
        else "$imgCdn${if (path.startsWith("/")) path else "/$path"}"
    }

    /**
     * Normalize CDN host name into a clean label for the picker.
     * Instead of "HentaiZ (c2.animez.top)" → "HentaiZ 1", "HentaiZ 2", etc.
     * (matches the polish level of NguonC's cleanServerName).
     */
    private fun cleanServerName(host: String?, idx: Int = 0): String {
        if (host.isNullOrBlank()) return "HentaiZ ${idx + 1}"
        // Extract subdomain number (e.g. "c2.animez.top" → "2")
        val num = Regex("""[a-z]+(\d+)\.""", RegexOption.IGNORE_CASE).find(host)?.groupValues?.get(1)?.toIntOrNull()
        return if (num != null) "HentaiZ $num" else "HentaiZ ${idx + 1}"
    }

    /**
     * Parse a master m3u8 playlist and return its quality variants.
     * Each variant is a (label, absolute_url, quality_value) tuple.
     *
     * Master playlists look like:
     *   #EXTM3U
     *   #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
     *   720p.m3u8
     *   #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
     *   1080p.m3u8
     *
     * Returns empty list for non-master playlists (single-variant).
     */
    private fun parseM3U8Variants(content: String, baseUrl: String): List<Triple<String, String, Int>> {
        if (!content.contains("#EXT-X-STREAM-INF")) return emptyList()
        val results = mutableListOf<Triple<String, String, Int>>()
        val lines = content.lines()
        var i = 0
        val baseDir = baseUrl.substringBeforeLast("/", "")
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val nextLine = lines.getOrNull(i + 1)?.trim() ?: ""
                if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                    // Extract resolution
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val height = resMatch?.groupValues?.get(2)?.toIntOrNull()
                    // Extract bandwidth
                    val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(line)
                    val bandwidth = bwMatch?.groupValues?.get(1)?.toLongOrNull()

                    // Build label and quality value
                    val (label, quality) = when {
                        height != null && height >= 2160 -> "4K" to Qualities.P2160.value
                        height != null && height >= 1440 -> "2K" to Qualities.P1440.value
                        height != null && height >= 1080 -> "1080p" to Qualities.P1080.value
                        height != null && height >= 720  -> "720p" to Qualities.P720.value
                        height != null && height >= 480  -> "480p" to Qualities.P480.value
                        bandwidth != null && bandwidth >= 8_000_000 -> "1080p" to Qualities.P1080.value
                        bandwidth != null && bandwidth >= 4_000_000 -> "720p"  to Qualities.P720.value
                        bandwidth != null && bandwidth >= 1_500_000 -> "480p"  to Qualities.P480.value
                        else -> "Auto" to Qualities.Unknown.value
                    }

                    // Resolve relative URL
                    val variantUrl = if (nextLine.startsWith("http")) nextLine
                                     else if (nextLine.startsWith("/")) "https://" + baseUrl.substringAfter("https://").substringBefore("/") + nextLine
                                     else "$baseDir/$nextLine"
                    results.add(Triple(label, variantUrl, quality))
                    i += 2
                    continue
                }
            }
            i++
        }
        return results
    }

    /**
     * Build a beautiful HTML-formatted description (matches NguonC's polish).
     * Uses Vietnamese labels + emoji icons + colored values, so the detail page
     * looks consistent with the rest of the user's plugin library.
     */
    private fun buildBeautifulDescription(ep: EpInfo): String {
        val description = ep.description?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
        return buildString {
            fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank())
                    append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
            }

            // Censor with color coding
            val censorColor = when (ep.contentRating?.uppercase()) {
                "UNCENSORED" -> "#F44336"  // red — spicy
                "CENSORED"   -> "#9C27B0"  // purple — soft
                else         -> "#FFEB3B"  // yellow — unknown
            }
            val censorLabel = when (ep.contentRating?.uppercase()) {
                "UNCENSORED" -> "Không Che"
                "CENSORED"   -> "Che Censor"
                else         -> ep.contentRating
            }
            addInfo("🔞", "Censor", censorLabel, censorColor)

            // Animation type
            val typeLabel = when (ep.animationType?.uppercase()) {
                "TWO_D"  -> "2D"
                "THREE_D"-> "3D"
                else     -> ep.animationType
            }
            addInfo("🎬", "Loại", typeLabel, "#03A9F4")

            addInfo("📅", "Năm", ep.releaseYear?.toString())
            ep.duration?.takeIf { it > 0 }?.let {
                val h = it / 60
                val m = it % 60
                val durStr = if (h > 0) "${h}h ${m}m" else "${m}m"
                addInfo("⏱", "Thời lượng", durStr)
            }
            if (ep.studios.isNotEmpty()) {
                addInfo("🎨", "Studio", ep.studios.joinToString(", "), "#E91E63")
            }
            if (ep.genres.isNotEmpty()) {
                addInfo("🎭", "Thể loại", ep.genres.joinToString(", "), "#4CAF50")
            }
            if (ep.episodeNumber != null && ep.episodeNumber > 1) {
                addInfo("📺", "Tập", "Tập ${ep.episodeNumber}", "#FF9800")
            }

            if (description.isNotBlank()) {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(description)
            }
        }
    }

    private fun epToSearch(ep: EpInfo): SearchResponse? {
        val title  = ep.title ?: return null
        val slug   = ep.slug  ?: return null
        val poster = posterUrl(ep.posterPath)
        val url    = "$mainUrl/watch/$slug"
        return if ((ep.episodeNumber ?: 1) <= 1) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.quality   = SearchQuality.HD
            }
        } else {
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.quality   = SearchQuality.HD
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main page — hỗ trợ sort: / rating: / type: / genre:
    // ═══════════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        // ── Sắp xếp theo độ mới / phổ biến ──────────────────
        "sort:publishedAt_desc"   to "Mới Nhất 🔥",
        "sort:likes_desc"         to "Được Thích Nhất ❤️",
        "sort:views_desc"         to "Xem Nhiều Nhất 👀",
        "sort:rating_desc"        to "Đánh Giá Cao ⭐",
        // ── Lọc theo loại hoạt hình ─────────────────────────
        "type:TWO_D"              to "Hoạt Hình 2D 🎨",
        "type:THREE_D"            to "Hoạt Hình 3D 🧊",
        // ── Lọc theo censor ─────────────────────────────────
        "rating:UNCENSORED"       to "Không Che 🥵",
        "rating:CENSORED"         to "Che Censor 🌸",
        // ── Thể loại phổ biến ───────────────────────────────
        "genre:loan-luan"         to "Loạn Luân 🥵",
        "genre:harem"             to "Harem 👯",
        "genre:netorare"          to "Netorare 💔",
        "genre:romance"           to "Tình Cảm 💕",
        "genre:action"            to "Hành Động ⚔️",
        "genre:fantasy"           to "Giả Tưởng 🐉",
        "genre:comedy"            to "Hài Hước 😂",
        "genre:milf"              to "MILF 💋",
        "genre:uncensored"        to "Uncensored 🔥"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data  = request.data
        val (browseUrl, hasNext) = buildUrlFromData(data, page)
        println("[HentaiZ] getMainPage data='$data' url='$browseUrl'")
        val nodes    = fetchSvelteData(browseUrl) ?: run {
            println("[HentaiZ] getMainPage: fetchSvelteData returned null")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        println("[HentaiZ] getMainPage: nodes.size=${nodes.size}")
        val nodeData = nodes.getOrNull(2) ?: run {
            println("[HentaiZ] getMainPage: nodeData(2) is null, available=${nodes.size}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        println("[HentaiZ] getMainPage: nodeData.size=${nodeData.size}, types=${nodeData.take(5).map{it?.javaClass?.simpleName}}")
        val (eps, totalPages) = parseBrowse(nodeData)
        println("[HentaiZ] getMainPage: eps=${eps.size}, totalPages=$totalPages, section='${request.name}'")
        eps.take(2).forEach { println("[HentaiZ]   ep: ${it.title} / ${it.slug}") }
        val items = eps.mapNotNull { epToSearch(it) }
        return newHomePageResponse(request.name, items, hasNext = page < totalPages)
    }

    private fun buildUrlFromData(data: String, page: Int): Pair<String, Boolean> {
        return when {
            data.startsWith("sort:")   -> buildBrowseUrl(page, sort = data.removePrefix("sort:")) to true
            data.startsWith("rating:") -> buildBrowseUrl(page, contentRating = data.removePrefix("rating:")) to true
            data.startsWith("type:")   -> buildBrowseUrl(page, animationType = when(data.removePrefix("type:")) { "TWO_D" -> "TWO_D"; "THREE_D" -> "THREE_D"; else -> data.removePrefix("type:") }) to true
            data.startsWith("genre:")  -> {
                // Genre page dùng path riêng, sort khác với browse
                val slug = data.removePrefix("genre:")
                "$mainUrl/genres/$slug/__data.json?page=$page&sort=published_desc&limit=24" to true
            }
            else -> buildBrowseUrl(page, sort = data) to true
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val url   = buildBrowseUrl(1, query = query)
        val nodes = fetchSvelteData(url) ?: return emptyList()
        val (eps, _) = parseBrowse(nodes.getOrNull(2) ?: return emptyList())
        return eps.mapNotNull { epToSearch(it) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Load detail + recommendations
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trim().trimEnd('/').substringAfterLast("/watch/", "").substringBefore("?")
        if (slug.isBlank()) throw ErrorLoadingException("URL không hợp lệ")

        val nodes    = fetchSvelteData("$mainUrl/watch/$slug") ?: throw ErrorLoadingException("Không tải được dữ liệu")
        val nodeData = nodes.getOrNull(2) ?: throw ErrorLoadingException("Không tìm thấy nodeData")
        val ep       = parseWatch(nodeData) ?: throw ErrorLoadingException("Không parse được dữ liệu")

        val title    = ep.title ?: "Unknown"
        val poster   = posterUrl(ep.posterPath)
        val backdrop = posterUrl(ep.backdropPath)

        // Beautiful HTML-formatted plot (matches NguonC polish)
        val plot = buildBeautifulDescription(ep)

        val episodeData = if (ep.embedUrl != null) "$slug::EMBED::${ep.embedUrl}" else slug
        val epNum       = ep.episodeNumber
        val isMovie     = epNum == null || epNum <= 1

        // ── Recommendations: try top 3 genres IN PARALLEL, paginate up to 2 pages ──
        // (was: only the first genre, single page, only 12 items)
        val recList: List<SearchResponse> = try {
            if (ep.genreSlugs.isEmpty()) emptyList()
            else coroutineScope {
                val recResults = ep.genreSlugs.take(3).map { genreSlug ->
                    async {
                        try {
                            val items = mutableListOf<EpInfo>()
                            for (p in 1..2) {
                                val recUrl = buildBrowseUrl(p, sort = "likes_desc", genre = genreSlug)
                                val recNodes = fetchSvelteData(recUrl) ?: break
                                val (recEps, totalPages) = parseBrowse(recNodes.getOrNull(2) ?: break)
                                items += recEps.filter { it.slug != slug }
                                if (p >= totalPages) break
                                if (items.size >= 30) break
                            }
                            items
                        } catch (_: Exception) { emptyList() }
                    }
                }.awaitAll()
                // Pick genre with most results, dedupe by slug, take 30
                recResults.maxByOrNull { it.size }?.orEmpty()
                    .distinctBy { it.slug }
                    .take(30)
                    .mapNotNull { epToSearch(it) }
            }
        } catch (e: Exception) {
            println("[HentaiZ] recommendations error: ${e.message}")
            emptyList()
        }

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, episodeData) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdrop
                this.plot                = plot
                this.tags                = ep.genres
                this.year                = ep.releaseYear
                this.recommendations     = recList
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime,
                listOf(newEpisode(episodeData) { this.episode = epNum })
            ) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdrop
                this.plot                = plot
                this.tags                = ep.genres
                this.year                = ep.releaseYear
                this.recommendations     = recList
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  loadLinks — CDN multi-host + WebView fallback
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[HentaiZ] loadLinks: ${data.take(100)}")

        var embedUrl: String? = null
        var slug: String?     = null

        if (data.contains("::EMBED::")) {
            val p = data.split("::EMBED::", limit = 2)
            slug     = p.getOrNull(0)
            embedUrl = p.getOrNull(1)
        } else {
            slug = data
        }

        if (embedUrl.isNullOrBlank() && slug != null) {
            val nodes = fetchSvelteData("$mainUrl/watch/$slug")
            embedUrl  = parseWatch(nodes?.getOrNull(2) ?: emptyList())?.embedUrl
        }

        if (embedUrl.isNullOrBlank()) {
            println("[HentaiZ] No embedUrl!")
            return false
        }
        println("[HentaiZ] Embed: $embedUrl")

        val uuid = Regex("""[?&]v=([^&\s]+)""").find(embedUrl)?.groupValues?.get(1)

        // ── Strategy 0: PARALLEL multi-CDN scan (all hosts × master.m3u8 at once) ──
        // Old code was sequential — could take 8+ seconds if first 7 hosts were
        // dead. Now we fire all hosts in parallel; first success wins, others
        // cancel via coroutineScope's structured concurrency.
        if (uuid != null) {
            println("[HentaiZ] S0: parallel multi-CDN scan, uuid=$uuid")
            val cdnHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer"    to "https://x.haiten.org/",
                "Origin"     to "https://x.haiten.org"
            )
            val cdnHeadersBasic = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer"    to "https://x.haiten.org/"
            )

            try {
                val found = coroutineScope {
                    // Try all CDN hosts × master.m3u8 IN PARALLEL
                    CDN_HOSTS.mapIndexed { idx, host ->
                        async {
                            try {
                                val tryUrl = "https://$host/$uuid/master.m3u8"
                                val resp = app.get(tryUrl, headers = cdnHeaders)
                                if (resp.isSuccessful && resp.text.trimStart().startsWith("#EXTM3U")) {
                                    Pair(host, resp.text)
                                } else null
                            } catch (_: Exception) { null }
                        }
                    }.awaitAll().firstOrNull { it != null }
                }

                if (found != null) {
                    val (host, m3u8Content) = found
                    val m3u8Url = "https://$host/$uuid/master.m3u8"
                    println("[HentaiZ] S0 SUCCESS: $m3u8Url")

                    // Parse master.m3u8 — if it has multiple quality variants, expose each
                    // as a separate ExtractorLink with the correct quality value.
                    // (Matches NguonC's behavior of offering multiple quality options.)
                    val variants = parseM3U8Variants(m3u8Content, m3u8Url)
                    if (variants.isNotEmpty()) {
                        // Multi-variant master playlist
                        variants.forEach { (name, url, quality) ->
                            callback(newExtractorLink("HentaiZ", "HentaiZ $name", url, ExtractorLinkType.M3U8) {
                                this.quality = quality
                                this.headers = cdnHeadersBasic
                            })
                        }
                        return true
                    } else {
                        // Single-variant playlist (no #EXT-X-STREAM-INF)
                        callback(newExtractorLink("HentaiZ", cleanServerName(host), m3u8Url, ExtractorLinkType.M3U8) {
                            quality = Qualities.P1080.value
                            headers = cdnHeaders
                        })
                        return true
                    }
                }

                // master.m3u8 failed on all hosts — try other m3u8 file names IN PARALLEL
                println("[HentaiZ] S0: master.m3u8 fail all hosts, trying other names in parallel...")
                val fallbackFound = coroutineScope {
                    M3U8_NAMES.drop(1).flatMap { m3u8Name ->
                        CDN_HOSTS.take(3).mapIndexed { idx, host ->
                            async {
                                try {
                                    val tryUrl = "https://$host/$uuid/$m3u8Name"
                                    val resp = app.get(tryUrl, headers = cdnHeadersBasic)
                                    if (resp.isSuccessful && resp.text.trimStart().startsWith("#EXTM3U")) {
                                        Triple(host, m3u8Name, resp.text)
                                    } else null
                                } catch (_: Exception) { null }
                            }
                        }
                    }.awaitAll().firstOrNull { it != null }
                }

                if (fallbackFound != null) {
                    val (host, m3u8Name, _) = fallbackFound
                    val m3u8Url = "https://$host/$uuid/$m3u8Name"
                    println("[HentaiZ] S0b SUCCESS: $m3u8Url")
                    callback(newExtractorLink("HentaiZ", cleanServerName(host), m3u8Url, ExtractorLinkType.M3U8) {
                        quality = Qualities.P1080.value
                        headers = cdnHeadersBasic
                    })
                    return true
                }
            } catch (e: Exception) {
                println("[HentaiZ] S0 parallel error: ${e.message}")
            }
        }

        // ── Strategy 1: WebView bắt BẤT KỲ request animez.top ───────────────
        println("[HentaiZ] S1: WebView broad animez.top intercept...")
        try {
            // Pattern rộng: bắt bất kỳ subdomain animez.top nào có path UUID
            val interceptor = WebViewResolver(
                Regex(""".*animez\.top/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/.*\.(m3u8|m3u9|mp4).*|.*animez\.top/[a-f0-9\-]{20,}/(master|index|playlist|video)(\?.*)?${'$'}""")
            )
            val resp = app.get(embedUrl, interceptor = interceptor, headers = mapOf(
                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
            ))
            val captured = resp.url ?: ""
            val body     = resp.text
            println("[HentaiZ] S1 captured: ${captured.take(120)}")

            if (captured.isNotBlank() && !captured.startsWith("blob:") && captured.contains("animez.top")) {
                val isM3u8 = body.trimStart().startsWith("#EXTM3U") || captured.contains(".m3u")
                callback(newExtractorLink("HentaiZ", "HentaiZ", captured,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    quality = Qualities.P1080.value
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://x.haiten.org/")
                })
                return true
            }
        } catch (e: Exception) {
            println("[HentaiZ] S1 error: ${e.message}")
        }

        // ── Strategy 2: WebView cực rộng — bắt bất kỳ .m3u8 nào ─────────────
        println("[HentaiZ] S2: WebView catch-all m3u8...")
        try {
            val broadInterceptor = WebViewResolver(
                Regex(""".*\.(m3u8|m3u9)(\?.*)?${'$'}""")
            )
            val resp = app.get(embedUrl, interceptor = broadInterceptor, headers = mapOf(
                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
            ))
            val captured = resp.url ?: ""
            val body     = resp.text
            println("[HentaiZ] S2 captured: ${captured.take(120)}")

            if (captured.isNotBlank() && !captured.startsWith("blob:")) {
                val isM3u8 = body.trimStart().startsWith("#EXTM3U") || captured.contains(".m3u")
                if (isM3u8 || captured.contains(".m3u")) {
                    callback(newExtractorLink("HentaiZ", "HentaiZ HLS", captured, ExtractorLinkType.M3U8) {
                        quality = Qualities.P1080.value
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embedUrl)
                    })
                    return true
                }
            }
        } catch (e: Exception) {
            println("[HentaiZ] S2 error: ${e.message}")
        }

        println("[HentaiZ] All strategies failed: $embedUrl")
        return false
    }
}
