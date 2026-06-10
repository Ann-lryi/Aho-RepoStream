package com.hentaiz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import java.net.URLEncoder

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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
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
        // ── Sắp xếp ─────────────────────────────────────────
        "sort:publishedAt_desc" to "🆕 Mới Nhất",
        "sort:views_desc"       to "🔥 Xem Nhiều",
        "sort:likes_desc"       to "❤️ Yêu Thích",
        // ── Phân loại ───────────────────────────────────────
        "rating:UNCENSORED"     to "🔞 Không Che",
        "rating:CENSORED"       to "📦 Có Che",
        "type:2D"               to "🎨 2D",
        // ── Thể loại thực từ hentaiz.chat ───────────────────
        "genre:big-boobs"          to "Big Boobs",
        "genre:bu-liem"            to "Bú liếm",
        "genre:nu-sinh"            to "Nữ sinh",
        "genre:du-vu"              to "Đụ Vú",
        "genre:stocking"           to "Stocking",
        "genre:hiep-dam"           to "Hiếp dâm",
        "genre:virgin"             to "Virgin",
        "genre:anal"               to "Anal",
        "genre:mind-break"         to "Mind Break",
        "genre:femdom"             to "Femdom",
        "genre:ahegao"             to "Ahegao",
        "genre:vanilla"            to "Vanilla",
        "genre:threesome"          to "Threesome",
        "genre:milf"               to "MILF",
        "genre:sex-toy"            to "Sex Toy",
        "genre:harem"              to "Harem",
        "genre:plot"               to "Plot",
        "genre:thu-dam"            to "Thủ Dâm",
        "genre:loan-luan"          to "Loạn luân",
        "genre:gang-bang"          to "Gang Bang",
        "genre:bondage"            to "Bondage",
        "genre:tsundere"           to "Tsundere",
        "genre:ntr"                to "NTR",
        "genre:double-penetration" to "Double Penetration",
        "genre:giao-vien"          to "Giáo viên",
        "genre:megane"             to "Megane",
        "genre:yuri"               to "Yuri",
        "genre:do-boi"             to "Đồ Bơi",
        "genre:ugly-bastard"       to "Ugly Bastard",
        "genre:thac-loan"          to "Thác loạn",
        "genre:maid"               to "Maid",
        "genre:bao-dam"            to "Bạo dâm",
        "genre:thoi-mien"          to "Thôi miên",
        "genre:sua-me"             to "Sữa mẹ",
        "genre:tong-tinh"          to "Tống tình",
        "genre:da-ngam"            to "Da ngăm",
        "genre:3d"                 to "3D",
        "genre:monster"            to "Monster",
        "genre:y-ta"               to "Y Tá",
        "genre:fantasy"            to "Fantasy",
        "genre:xuc-tu"             to "Xúc tu",
        "genre:foot-job"           to "Foot Job",
        "genre:x-ray"              to "X-Ray",
        "genre:kemonomimi"         to "Kemonomimi",
        "genre:futanari"           to "Futanari",
        "genre:wafuku"             to "Wafuku",
        "genre:elf"                to "Elf",
        "genre:softcore"           to "Softcore",
        "genre:big-girls"          to "Big girls",
        "genre:cong-cong"          to "Công cộng",
        "genre:josei"              to "Josei",
        "genre:gai-quay"           to "Gái quậy",
        "genre:idol"               to "Idol",
        "genre:thuoc-kich-duc"     to "Thuốc kích dục",
        "genre:succubus"           to "Succubus",
        "genre:cosplay"            to "Cosplay",
        "genre:mang-thai"          to "Mang thai",
        "genre:ngu"                to "Ngủ",
        "genre:trap"               to "Trap",
        "genre:yaoi"               to "Yaoi",
        "genre:vu-lep"             to "Vú lép",
        "genre:goblin"             to "Goblin",
        "genre:furry"              to "Furry"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data  = request.data
        val (browseUrl, hasNext) = buildUrlFromData(data, page)
        val nodes    = fetchSvelteData(browseUrl) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val nodeData = nodes.getOrNull(2)          ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val (eps, totalPages) = parseBrowse(nodeData)
        val items = eps.mapNotNull { epToSearch(it) }
        return newHomePageResponse(request.name, items, hasNext = page < totalPages)
    }

    private fun buildUrlFromData(data: String, page: Int): Pair<String, Boolean> {
        return when {
            data.startsWith("sort:")   -> buildBrowseUrl(page, sort = data.removePrefix("sort:")) to true
            data.startsWith("rating:") -> buildBrowseUrl(page, contentRating = data.removePrefix("rating:")) to true
            data.startsWith("type:")   -> buildBrowseUrl(page, animationType = data.removePrefix("type:")) to true
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

        val description = ep.description?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
        val plot = buildString {
            if (description.isNotBlank()) append(description)
            val meta = listOfNotNull(
                ep.contentRating?.let { "Censure: $it" },
                ep.animationType?.let { "Type: $it" },
                ep.releaseYear?.let   { "Year: $it" },
                ep.duration?.takeIf { it > 0 }?.let { "Duration: ${it}m" }
            ).joinToString(" | ")
            if (meta.isNotBlank()) append("\n\n$meta")
            if (ep.studios.isNotEmpty()) append("\nStudio: ${ep.studios.joinToString(", ")}")
        }

        val episodeData = if (ep.embedUrl != null) "$slug::EMBED::${ep.embedUrl}" else slug
        val epNum       = ep.episodeNumber
        val isMovie     = epNum == null || epNum <= 1

        // Gợi ý phim cùng thể loại
        val recList = mutableListOf<SearchResponse>()
        if (ep.genreSlugs.isNotEmpty()) {
            try {
                val recUrl   = buildBrowseUrl(1, sort = "likes_desc", genre = ep.genreSlugs.first())
                val recNodes = fetchSvelteData(recUrl)
                val (recEps, _) = parseBrowse(recNodes?.getOrNull(2) ?: emptyList())
                recEps.filter { it.slug != slug }
                    .take(12)
                    .mapNotNull { epToSearch(it) }
                    .let { recList.addAll(it) }
            } catch (e: Exception) {
                println("[HentaiZ] recommendations error: ${e.message}")
            }
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

        // ── Strategy 0: Thử từng CDN host (c1→c5 + variants), 0 WebView ─────
        if (uuid != null) {
            println("[HentaiZ] S0: multi-CDN scan, uuid=$uuid")
            outer@ for (host in CDN_HOSTS) {
                for (m3u8 in M3U8_NAMES) {
                    val tryUrl = "https://$host/$uuid/$m3u8"
                    try {
                        val resp = app.get(tryUrl, headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer"    to "https://x.haiten.org/"
                        ))
                        if (resp.isSuccessful && resp.text.trimStart().startsWith("#EXTM3U")) {
                            println("[HentaiZ] S0 SUCCESS: $tryUrl")
                            callback(newExtractorLink("HentaiZ", "HentaiZ ($host)", tryUrl, ExtractorLinkType.M3U8) {
                                quality = Qualities.P1080.value
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer"    to "https://x.haiten.org/",
                                    "Origin"     to "https://x.haiten.org"
                                )
                            })
                            return true
                        } else {
                            println("[HentaiZ] S0: $tryUrl → ${resp.code}")
                        }
                    } catch (e: Exception) {
                        println("[HentaiZ] S0 skip $tryUrl: ${e.message}")
                    }
                    // Chỉ thử master.m3u8 trước, nếu 404 thử host tiếp
                    if (m3u8 == "master.m3u8" && true) break
                }
            }
            // Nếu master.m3u8 fail trên tất cả host → thử hết file names trên c2
            println("[HentaiZ] S0: master.m3u8 fail all hosts, trying all names on c2...")
            for (m3u8 in M3U8_NAMES.drop(1)) {
                for (host in CDN_HOSTS.take(3)) {
                    val tryUrl = "https://$host/$uuid/$m3u8"
                    try {
                        val resp = app.get(tryUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://x.haiten.org/"))
                        if (resp.isSuccessful && resp.text.trimStart().startsWith("#EXTM3U")) {
                            println("[HentaiZ] S0b SUCCESS: $tryUrl")
                            callback(newExtractorLink("HentaiZ", "HentaiZ CDN", tryUrl, ExtractorLinkType.M3U8) {
                                quality = Qualities.P1080.value
                                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://x.haiten.org/")
                            })
                            return true
                        }
                    } catch (e: Exception) { /* skip */ }
                }
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
                callback(newExtractorLink("HentaiZ", "HentaiZ CDN", captured,
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
