package com.phimnguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder
import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.EnumSet

@CloudstreamPlugin
class PhimNguonCPlugin : Plugin() {
    override fun load() { registerMainAPI(PhimNguonCProvider()) }
}

class PhimNguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private val cfInterceptor = WebViewResolver(Regex(""".*streamc\.xyz|.*amass15\.top|.*hihihoho2\.top|.*phimmoi\.net"""))


    private val commonHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    private val API_PREFIX = "API::"

    override val mainPage = mainPageOf(
        "${API_PREFIX}api/films/phim-moi-cap-nhat"    to "Phim Mới Cập Nhật",
        "danh-sach/phim-le"                           to "Phim Lẻ",
        "danh-sach/phim-bo"                           to "Phim Bộ",
        "danh-sach/tv-shows"                          to "Nhật Bản + Anime"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val a      = el.selectFirst("a") ?: return null
        val href   = a.attr("href")
        val title  = el.selectFirst("h3")?.text()?.trim() ?: a.attr("title")
        val poster = el.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val statusText = el.selectFirst("span.bg-green-300")?.text()?.trim() ?: ""
        val episodeCount: Int? = when {
            statusText.equals("FULL", ignoreCase = true) -> null
            else ->
                Regex("""[Tt]ập\s*(\d+)""").find(statusText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\s*/\s*\d+""").find(statusText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""^(\d+)$""").find(statusText)?.groupValues?.get(1)?.toIntOrNull()
        }
        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.quality   = SearchQuality.HD
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            this.episodes  = mutableMapOf(DubStatus.Subbed to (episodeCount ?: 0))
        }
    }

    private fun parseApiItem(item: NguonCApiItem): SearchResponse? {
        val slug   = item.slug ?: return null
        val title  = item.name ?: return null
        val href   = "$mainUrl/phim/$slug"
        val poster = item.poster_url ?: item.thumb_url
        val currentEp = item.current_episode ?: ""
        val episodeCount: Int? = when {
            currentEp.equals("FULL", ignoreCase = true)         -> null
            currentEp.startsWith("Hoàn tất", ignoreCase = true) -> null
            else ->
                Regex("""[Tt]ập\s*(\d+)""").find(currentEp)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\s*/\s*\d+""").find(currentEp)?.groupValues?.get(1)?.toIntOrNull()
        }
        val lang      = item.language ?: ""
        val hasSub    = lang.contains("Vietsub",     ignoreCase = true)
        val hasDub    = lang.contains("Thuyết Minh", ignoreCase = true)
        val dubStatus = when {
            hasSub && hasDub -> EnumSet.of(DubStatus.Subbed, DubStatus.Dubbed)
            hasDub           -> EnumSet.of(DubStatus.Dubbed)
            else             -> EnumSet.of(DubStatus.Subbed)
        }
        val quality = when (item.quality?.uppercase()) {
            "FHD", "HD" -> SearchQuality.HD
            "CAM"       -> SearchQuality.Cam
            "SD"        -> SearchQuality.SD
            else        -> SearchQuality.HD
        }
        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.quality   = quality
            this.dubStatus = dubStatus
            if (episodeCount != null) {
                if (hasSub) addSub(episodeCount)
                if (hasDub) addDub(episodeCount)
                if (!hasSub && !hasDub) addSub(episodeCount)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return if (request.data.startsWith(API_PREFIX)) {
            val path  = request.data.removePrefix(API_PREFIX)
            val url   = "$mainUrl/$path?page=$page"
            val res   = app.get(url, headers = commonHeaders)
                           .parsedSafe<NguonCApiResponse>()
            val items = res?.items?.mapNotNull { parseApiItem(it) } ?: emptyList()
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } else {
            val url   = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
            // Use a per-request WebViewResolver that matches the exact page URL
            // This ensures WebView loads the full page past Cloudflare
            val pageInterceptor = com.lagradost.cloudstream3.network.WebViewResolver(
                Regex(Regex.escape(url))
            )
            val resp = app.get(url, headers = commonHeaders)
            val doc  = resp.document
            val items = doc.select("table tbody tr").mapNotNull { parseCard(it) }
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Try API search first
        val res = try {
            app.get("$mainUrl/api/films?keyword=${URLEncoder.encode(query, "utf-8")}",
                headers = commonHeaders)
               .parsedSafe<NguonCApiResponse>()
        } catch (_: Exception) { null }
        if (!res?.items.isNullOrEmpty())
            return res!!.items!!.mapNotNull { parseApiItem(it) }
        // Fallback HTML search
        val searchUrl = "$mainUrl/tim-kiem?keyword=${URLEncoder.encode(query, "utf-8")}"
        val pageInterceptor2 = com.lagradost.cloudstream3.network.WebViewResolver(
            Regex(Regex.escape(searchUrl))
        )
        val doc = app.get(searchUrl, headers = commonHeaders).document
        return doc.select("table tbody tr").mapNotNull { parseCard(it) }
    }

    
    override suspend fun load(url: String): LoadResponse {
        val slug  = url.trim().trimEnd('/').substringAfterLast("/")
        val res   = app.get("$mainUrl/api/film/$slug", headers = commonHeaders)
                       .parsedSafe<NguonCDetailResponse>()
        val movie = res?.movie ?: throw ErrorLoadingException("Không thể tải dữ liệu phim")

        val epMap = linkedMapOf<String, MutableList<String>>()
        movie.episodes?.forEachIndexed { idx, server ->
            val serverName = server.server_name ?: server.name
                ?: if (idx == 0) "Vietsub" else "Thuyết minh"
            val items = server.items ?: server.list
            items?.forEach { ep ->
                // Ưu tiên dùng trực tiếp m3u8 URL từ API (format mới)
                val directM3u8 = ep.m3u8
                if (!directM3u8.isNullOrBlank()) {
                    epMap.getOrPut(ep.name ?: "0") { mutableListOf() }
                        .add("$serverName::$directM3u8")
                } else {
                    // Fallback: dùng embed URL cũ (cần parse thêm)
                    val embed = ep.embed?.replace("\\/", "/") ?: ""
                    if (embed.isNotBlank()) {
                        epMap.getOrPut(ep.name ?: "0") { mutableListOf() }
                            .add("$serverName::$embed")
                    }
                }
            }
        }
        if (epMap.isEmpty()) throw ErrorLoadingException("Không tìm thấy tập phim")

        val episodes = epMap.map { (epName, embeds) ->
            newEpisode(embeds.distinct().joinToString("|")) {
                this.name    = "Tập $epName"
                this.episode = epName.toIntOrNull()
            }
        }.sortedBy { it.episode ?: 0 }

        // Extract metadata from categories
        val categories = movie.category ?: emptyMap()
        val dinhDang = categories.values.find { it.group?.name == "Định dạng" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val theLoai = categories.values.find { it.group?.name == "Thể loại" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val namPhatHanh = categories.values.find { it.group?.name == "Năm" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val quocGia = categories.values.find { it.group?.name == "Quốc gia" }?.list?.map { it.name }?.joinToString(", ") ?: ""

        // Build beautiful description HTML like Animevietsub
        val beautifulPlot = buildBeautifulDescription(
            movie = movie,
            dinhDang = dinhDang,
            theLoai = theLoai,
            namPhatHanh = namPhatHanh,
            quocGia = quocGia
        )

        // Recommendations: fetch films of same genre using category slug
        // Take genre items from any group (group name may vary by API response)
        val genreItems = categories.values
            .flatMap { it.list ?: emptyList() }
            .filter { !it.id.isNullOrBlank() }
        // Recommendations: convert genre name to slug for API
        // genre.id is UUID hash, not slug - must use name converted to slug
        // Find genre group - "Thể loại" is key "2" in API
        // Use group with most genre-like items (not năm/quốc gia/định dạng)
        val theLoaiItems = categories.values
            .filter { cat ->
                val gname = cat.group?.name ?: ""
                !gname.contains("ăm") &&      // Năm
                !gname.contains("gia") &&      // Quốc gia  
                !gname.contains("nh d") &&     // Định dạng
                (cat.list?.size ?: 0) >= 2     // Has multiple genres
            }
            .maxByOrNull { it.list?.size ?: 0 }
            ?.list ?: genreItems.take(5)

        fun nameToSlug(name: String): String {
            val map = mapOf(
                'à' to "a", 'á' to "a", 'â' to "a", 'ã' to "a", 'ä' to "a",
                'å' to "a", 'ă' to "a", 'ắ' to "a", 'ặ' to "a", 'ằ' to "a",
                'ẳ' to "a", 'ẵ' to "a", 'ấ' to "a", 'ầ' to "a", 'ẩ' to "a",
                'ẫ' to "a", 'ậ' to "a", 'ả' to "a", 'ạ' to "a",
                'è' to "e", 'é' to "e", 'ê' to "e", 'ë' to "e",
                'ề' to "e", 'ế' to "e", 'ệ' to "e", 'ể' to "e", 'ễ' to "e",
                'ẹ' to "e", 'ẻ' to "e", 'ẽ' to "e",
                'ì' to "i", 'í' to "i", 'î' to "i", 'ï' to "i",
                'ị' to "i", 'ỉ' to "i", 'ĩ' to "i",
                'ò' to "o", 'ó' to "o", 'ô' to "o", 'õ' to "o", 'ö' to "o",
                'ồ' to "o", 'ố' to "o", 'ộ' to "o", 'ổ' to "o", 'ỗ' to "o",
                'ờ' to "o", 'ớ' to "o", 'ợ' to "o", 'ở' to "o", 'ỡ' to "o",
                'ọ' to "o", 'ỏ' to "o",
                'ù' to "u", 'ú' to "u", 'û' to "u", 'ü' to "u",
                'ừ' to "u", 'ứ' to "u", 'ự' to "u", 'ử' to "u", 'ữ' to "u",
                'ụ' to "u", 'ủ' to "u", 'ũ' to "u",
                'ỳ' to "y", 'ý' to "y", 'ỵ' to "y", 'ỷ' to "y", 'ỹ' to "y",
                'đ' to "d", 'Đ' to "d",
                'ư' to "u", 'ơ' to "o",
                'Ă' to "a", 'Â' to "a", 'Ê' to "e", 'Ô' to "o", 'Ơ' to "o", 'Ư' to "u",
                'ớ' to "o", 'ờ' to "o", 'ở' to "o", 'ỡ' to "o", 'ợ' to "o"
            )
            return name.lowercase().trim().map { c ->
                map[c] ?: if (c in 'a'..'z' || c in '0'..'9') c.toString() else if (c == ' ') "-" else ""
            }.joinToString("").replace(Regex("-{2,}"), "-").trim('-')
        }

        val recommendations: List<SearchResponse> = try {
            var result: List<SearchResponse> = emptyList()
            for (genre in theLoaiItems.take(3)) {
                val genreName = genre.name ?: continue
                val slug = nameToSlug(genreName)
                if (slug.isBlank()) continue
                val items = try {
                    app.get(
                        "$mainUrl/api/films/$slug?page=1",
                        headers = commonHeaders
                    ).parsedSafe<NguonCApiResponse>()?.items
                } catch (_: Exception) { null }
                if (!items.isNullOrEmpty()) {
                    result = items
                        .filter { it.slug != movie.slug }
                        .take(20)
                        .mapNotNull { parseApiItem(it) }
                    break
                }
            }
            if (result.isEmpty()) {
                result = app.get(
                    "$mainUrl/api/films/phim-moi-cap-nhat?page=1",
                    headers = commonHeaders
                ).parsedSafe<NguonCApiResponse>()?.items
                    ?.filter { it.slug != movie.slug }
                    ?.take(20)?.mapNotNull { parseApiItem(it) } ?: emptyList()
            }
            result
        } catch (_: Exception) { emptyList() }

        return newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl       = movie.poster_url ?: movie.thumb_url
            this.plot            = beautifulPlot
            this.tags            = theLoai.split(", ").filter { it.isNotBlank() }
            this.year            = namPhatHanh.toIntOrNull()
            this.recommendations = recommendations
        }
    }

    private fun buildBeautifulDescription(
        movie: NguonCMovie,
        dinhDang: String,
        theLoai: String,
        namPhatHanh: String,
        quocGia: String
    ): String {
        val description  = movie.description ?: ""
        val director     = movie.director ?: ""
        val casts        = movie.casts ?: ""
        val time         = movie.time ?: ""
        val quality      = movie.quality ?: ""
        val language     = movie.language ?: ""
        val currentEp    = movie.current_episode ?: ""
        val totalEp      = movie.total_episodes?.toString() ?: ""
        val originalName = movie.original_name ?: ""

        return buildString {
            if (originalName.isNotBlank() && originalName != (movie.name ?: ""))
                append("<font color='#AAAAAA'><i>$originalName</i></font><br><br>")

            fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank())
                    append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
            }

            val statusColor = when {
                currentEp.contains("hoàn tất", ignoreCase = true) -> "#2196F3"
                currentEp.contains("full",     ignoreCase = true) -> "#9C27B0"
                else -> "#4CAF50"
            }

            addInfo("📺", "Trạng thái",  currentEp,  statusColor)
            if (totalEp.isNotBlank() && totalEp != "0")
                addInfo("🎞",  "Số tập",     "$totalEp tập")
            addInfo("⏱",  "Thời lượng",  time)
            addInfo("🎬",  "Chất lượng",  quality,    "#E91E63")
            addInfo("🔊",  "Ngôn ngữ",   language)
            addInfo("🌍",  "Quốc gia",   quocGia)
            addInfo("📅",  "Năm",        namPhatHanh)
            addInfo("📽",  "Định dạng",  dinhDang)
            addInfo("🎥",  "Đạo diễn",   director)
            addInfo("🎭",  "Diễn viên",  casts)
            addInfo("🏷",  "Thể loại",   theLoai)

            if (description.isNotBlank()) {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(description)
            }
        }
    }

    // ── Local proxy server ────────────────────────────────────────────────────
    private val activeServers = mutableListOf<NguonCProxyServer>()

    inner class NguonCProxyServer(
        private val m3u8Content: String,
        private val segReferer:  String
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        val port: Int get() = serverSocket?.localPort ?: 0
        private val threadPool = java.util.concurrent.Executors.newCachedThreadPool()

        fun start() {
            serverSocket = java.net.ServerSocket(0)
            Thread {
                val ss = serverSocket ?: return@Thread
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        threadPool.execute { handleClient(client) }
                    } catch (_: Exception) { break }
                }
            }.also { it.isDaemon = true }.start()
        }

        private fun handleClient(client: java.net.Socket) {
            try {
                val input  = client.getInputStream().bufferedReader()
                val output = client.getOutputStream()
                val requestLine = input.readLine() ?: return
                while (true) { if ((input.readLine() ?: "").isBlank()) break }
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val crlf = "\r\n"
                when {
                    path == "/stream.m3u8" -> {
                        val body = getM3U8().toByteArray(Charsets.UTF_8)
                        output.write(("HTTP/1.1 200 OK${crlf}Content-Type: application/vnd.apple.mpegurl${crlf}Content-Length: ${body.size}${crlf}Access-Control-Allow-Origin: *${crlf}${crlf}").toByteArray())
                        output.write(body)
                    }
                    path.startsWith("/seg/") -> {
                        val segUrl = java.net.URLDecoder.decode(path.removePrefix("/seg/"), "UTF-8")
                        try {
                            val conn = java.net.URL(segUrl).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 15000
                            conn.readTimeout    = 30000
                            conn.setRequestProperty("User-Agent", USER_AGENT)
                            conn.setRequestProperty("Referer", segReferer)
                            conn.connect()
                            val bytes = conn.inputStream.readBytes()
                            conn.disconnect()
                            output.write(("HTTP/1.1 200 OK${crlf}Content-Type: video/mp2t${crlf}Content-Length: ${bytes.size}${crlf}Access-Control-Allow-Origin: *${crlf}${crlf}").toByteArray())
                            output.write(bytes)
                        } catch (_: Exception) {
                            output.write("HTTP/1.1 502 Bad Gateway${crlf}${crlf}".toByteArray())
                        }
                    }
                    else -> output.write("HTTP/1.1 404 Not Found${crlf}${crlf}".toByteArray())
                }
                output.flush()
                client.close()
            } catch (_: Exception) { try { client.close() } catch (_: Exception) {} }
        }

        @Volatile private var _m3u8: String = ""
        fun setM3U8(content: String) { _m3u8 = content }
        private fun getM3U8(): String = _m3u8

        fun stop() {
            try { serverSocket?.close() } catch (_: Exception) {}
            try { threadPool.shutdownNow() } catch (_: Exception) {}
        }
    }

    private fun rewriteM3U8(m3u8: String, proxyBase: String): String {
        return m3u8.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("http") && !trimmed.startsWith("#")) {
                "$proxyBase/seg/${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
            } else line
        }
    }

    data class StreamData(
        @JsonProperty("sUb") val sUb: String? = null,
        @JsonProperty("hD")  val hD:  String? = null
    )

    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        val embedEntries = data.split("|").mapNotNull { entry ->
            val parts = entry.trim().split("::", limit = 2)
            if (parts.size == 2) Pair(parts[0], parts[1])
            else if (parts.size == 1 && parts[0].startsWith("http")) Pair("Vietsub", parts[0])
            else null
        }
        var linkFound = false

        coroutineScope {
            embedEntries.map { (serverName, url) ->
                async {
                    // Case 1: Direct m3u8 URL (format mới từ API)
                    // Cần fetch cookies từ embed page và proxy qua local server
                    if (url.contains(".m3u8")) {
                        // Extract hash từ m3u8 URL để tạo embed URL
                        val hash = Regex("""/([^/]+)/hls\.m3u8""").find(url)?.groupValues?.get(1)
                        val embedPageUrl = if (hash != null) {
                            "https://embed1.streamc.xyz/embed.php?hash=$hash"
                        } else null

                        // Fetch embed page trước để lấy cookies (bypass Cloudflare)
                        var cookies = ""
                        if (embedPageUrl != null) {
                            try {
                                val embedRes = app.get(
                                    embedPageUrl,
                                    interceptor = cfInterceptor,
                                    headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)
                                )
                                cookies = embedRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                            } catch (_: Exception) {}
                        }

                        val m3u8Domain = Regex("""https?://[^/]+""").find(url)?.value ?: ""
                        try {
                            // Fetch m3u8 trong plugin với cookies
                            val m3u8Headers = mapOf(
                                "User-Agent"      to USER_AGENT,
                                "Referer"         to (embedPageUrl ?: "https://phim.nguonc.com/"),
                                "Origin"          to m3u8Domain,
                                "Cookie"          to cookies,
                                "Accept"          to "*/*",
                                "Accept-Language" to "vi-VN,vi;q=0.9"
                            )
                            val m3u8Raw = app.get(url, interceptor = cfInterceptor, headers = m3u8Headers).text
                            val finalM3u8Raw = if (!m3u8Raw.contains("#EXTM3U")) {
                                // Thử fallback không có interceptor
                                val m3u8Raw2 = app.get(url, headers = m3u8Headers).text
                                if (!m3u8Raw2.contains("#EXTM3U")) return@async
                                m3u8Raw2
                            } else m3u8Raw

                            // Proxy qua local server
                            val server = NguonCProxyServer("", embedPageUrl ?: url)
                            server.start()
                            activeServers.add(server)

                            val proxyBase      = "http://127.0.0.1:${server.port}"
                            val rewrittenM3U8  = rewriteM3U8(finalM3u8Raw, proxyBase)
                            server.setM3U8(rewrittenM3U8)

                            callback(newExtractorLink(
                                source = "NguonC",
                                name   = serverName,
                                url    = "$proxyBase/stream.m3u8",
                                type   = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            })
                            linkFound = true
                        } catch (_: Exception) {}
                        return@async
                    }

                    // Case 2: Embed URL (format cũ - cần parse thêm)
                    // Sử dụng cfInterceptor để bypass Cloudflare
                    val embedDomain = Regex("""https?://[^/]+""").find(url)?.value ?: return@async
                    try {
                        val embedRes = app.get(
                            url,
                            interceptor = cfInterceptor,
                            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)
                        )
                        val html    = embedRes.text
                        val cookies = embedRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

                        val obfMatch = Regex("""data-obf\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(html)
                            ?: return@async

                        val jsonData   = String(Base64.decode(obfMatch.groupValues[1], Base64.DEFAULT))
                        val streamData = AppUtils.parseJson<StreamData>(jsonData)

                        val fetchHdr = mapOf(
                            "User-Agent"      to USER_AGENT,
                            "Referer"         to url,
                            "Origin"          to embedDomain,
                            "Cookie"          to cookies,
                            "Accept"          to "*/*",
                            "Accept-Language" to "vi-VN,vi;q=0.9"
                        )

                    suspend fun serveStream(m3u8Url: String, serverName: String) {
                        try {
                            val m3u8Domain = Regex("""https?://[^/]+""").find(m3u8Url)?.value ?: embedDomain
                            val streamHdr = mapOf(
                                "User-Agent"      to USER_AGENT,
                                "Referer"         to url,
                                "Origin"          to m3u8Domain,
                                "Cookie"          to cookies,
                                "Accept"          to "*/*",
                                "Accept-Language" to "vi-VN,vi;q=0.9"
                            )
                            // Dùng interceptor để bypass Cloudflare cho m3u8
                            val m3u8Raw = app.get(m3u8Url, interceptor = cfInterceptor, headers = streamHdr).text
                            if (!m3u8Raw.contains("#EXTM3U")) return

                            val server = NguonCProxyServer("", url)
                            server.start()
                            activeServers.add(server)

                            val proxyBase     = "http://127.0.0.1:${server.port}"
                            val rewrittenM3U8 = rewriteM3U8(m3u8Raw, proxyBase)
                            server.setM3U8(rewrittenM3U8)

                            callback(newExtractorLink(
                                source = "NguonC",
                                name   = serverName,
                                url    = "$proxyBase/stream.m3u8",
                                type   = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            })
                            linkFound = true
                        } catch (_: Exception) {}
                    }

                        val m3u8Path = streamData.sUb ?: streamData.hD
                        if (!m3u8Path.isNullOrBlank()) {
                            serveStream("$embedDomain/$m3u8Path.m3u8", serverName)
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }

        return linkFound
    }

    // ── Data classes ──────────────────────────────────────────────────────────
    data class NguonCApiResponse(
        @JsonProperty("status")     val status:     String?              = null,
        @JsonProperty("total_page") val total_page: Int?                 = null,
        @JsonProperty("items")      val items:      List<NguonCApiItem>? = null
    )
    
    data class NguonCApiItem(
        @JsonProperty("name")            val name:             String? = null,
        @JsonProperty("slug")            val slug:             String? = null,
        @JsonProperty("original_name")   val original_name:   String? = null,
        @JsonProperty("thumb_url")       val thumb_url:       String? = null,
        @JsonProperty("poster_url")      val poster_url:      String? = null,
        @JsonProperty("total_episodes")  val total_episodes:  Int?    = null,
        @JsonProperty("current_episode") val current_episode: String? = null,
        @JsonProperty("quality")         val quality:         String? = null,
        @JsonProperty("language")        val language:        String? = null
    )
    
    data class NguonCDetailResponse(
        @JsonProperty("movie") val movie: NguonCMovie? = null
    )
    
    data class NguonCMovie(
        @JsonProperty("id")               val id:               String?                      = null,
        @JsonProperty("name")             val name:             String?                      = null,
        @JsonProperty("slug")             val slug:             String?                      = null,
        @JsonProperty("original_name")    val original_name:    String?                      = null,
        @JsonProperty("description")      val description:      String?                      = null,
        @JsonProperty("thumb_url")        val thumb_url:        String?                      = null,
        @JsonProperty("poster_url")       val poster_url:       String?                      = null,
        @JsonProperty("total_episodes")   val total_episodes:   Int?                         = null,
        @JsonProperty("current_episode")  val current_episode:  String?                      = null,
        @JsonProperty("time")             val time:             String?                      = null,
        @JsonProperty("quality")          val quality:          String?                      = null,
        @JsonProperty("language")         val language:         String?                      = null,
        @JsonProperty("director")         val director:         String?                      = null,
        @JsonProperty("casts")            val casts:            String?                      = null,
        @JsonProperty("category")         val category:         Map<String, NguonCCategory>? = null,
        @JsonProperty("episodes")         val episodes:         List<NguonCServer>?          = null
    )
    
    data class NguonCCategory(
        @JsonProperty("group") val group: NguonCGroup?       = null,
        @JsonProperty("list")  val list:  List<NguonCGroupItem>? = null
    )
    
    data class NguonCGroup(
        @JsonProperty("id")   val id:   String? = null,
        @JsonProperty("name") val name: String? = null
    )
    
    data class NguonCGroupItem(
        @JsonProperty("id")   val id:   String? = null,
        @JsonProperty("name") val name: String? = null
    )
    
    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String?              = null,
        @JsonProperty("name")        val name:        String?              = null,
        @JsonProperty("items")       val items:       List<NguonCEpisode>? = null,
        @JsonProperty("list")        val list:        List<NguonCEpisode>? = null
    )
    
    data class NguonCEpisode(
        @JsonProperty("name")  val name:  String? = null,
        @JsonProperty("slug")  val slug:  String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8")  val m3u8:  String? = null
    )
}