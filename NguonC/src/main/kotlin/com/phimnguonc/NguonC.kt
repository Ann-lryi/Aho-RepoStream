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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

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

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Match any embed subdomain: embed11, embed15, etc.
    private val cfInterceptor = WebViewResolver(Regex(""".*streamc\.xyz|.*amass\d+\.top|.*hihihoho\d+\.top|.*phimmoi\.net|.*seouls\d+\.amass\d+\.top"""))

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
            val res   = app.get(url, headers = commonHeaders).parsedSafe<NguonCApiResponse>()
            val items = res?.items?.mapNotNull { parseApiItem(it) } ?: emptyList()
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } else {
            val url   = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
            val pageInterceptor = WebViewResolver(Regex(Regex.escape(url)))
            val resp = app.get(url, headers = commonHeaders, interceptor = pageInterceptor)
            val doc  = resp.document
            val items = doc.select("table tbody tr").mapNotNull { parseCard(it) }
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = try {
            app.get("$mainUrl/api/films?keyword=${URLEncoder.encode(query, "utf-8")}", headers = commonHeaders).parsedSafe<NguonCApiResponse>()
        } catch (_: Exception) { null }

        if (!res?.items.isNullOrEmpty())
            return res!!.items!!.mapNotNull { parseApiItem(it) }

        val searchUrl = "$mainUrl/tim-kiem?keyword=${URLEncoder.encode(query, "utf-8")}"
        val pageInterceptor2 = WebViewResolver(Regex(Regex.escape(searchUrl)))
        val doc = app.get(searchUrl, headers = commonHeaders, interceptor = pageInterceptor2).document
        return doc.select("table tbody tr").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug  = url.trim().trimEnd('/').substringAfterLast("/")
        val res   = app.get("$mainUrl/api/film/$slug", headers = commonHeaders).parsedSafe<NguonCDetailResponse>()
        val movie = res?.movie ?: throw ErrorLoadingException("Không thể tải dữ liệu phim")

        val epMap = linkedMapOf<String, MutableList<String>>()
        movie.episodes?.forEachIndexed { idx, server ->
            val serverName = server.server_name ?: server.name ?: if (idx == 0) "Vietsub" else "Thuyết minh"
            val items = server.items ?: server.list
            items?.forEach { ep ->
                val directM3u8 = ep.m3u8
                if (!directM3u8.isNullOrBlank()) {
                    epMap.getOrPut(ep.name ?: "0") { mutableListOf() }.add("$serverName::$directM3u8")
                } else {
                    val embed = ep.embed?.replace("\/", "/") ?: ""
                    if (embed.isNotBlank()) {
                        epMap.getOrPut(ep.name ?: "0") { mutableListOf() }.add("$serverName::$embed")
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

        val categories = movie.category ?: emptyMap()
        val dinhDang = categories.values.find { it.group?.name == "Định dạng" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val theLoai = categories.values.find { it.group?.name == "Thể loại" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val namPhatHanh = categories.values.find { it.group?.name == "Năm" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val quocGia = categories.values.find { it.group?.name == "Quốc gia" }?.list?.map { it.name }?.joinToString(", ") ?: ""

        val beautifulPlot = buildBeautifulDescription(movie, dinhDang, theLoai, namPhatHanh, quocGia)

        val genreItems = categories.values.flatMap { it.list ?: emptyList() }.filter { !it.id.isNullOrBlank() }
        val theLoaiItems = categories.values
            .filter { cat ->
                val gname = cat.group?.name ?: ""
                !gname.contains("ăm") && !gname.contains("gia") && !gname.contains("nh d") && (cat.list?.size ?: 0) >= 2
            }
            .maxByOrNull { it.list?.size ?: 0 }?.list ?: genreItems.take(5)

        fun nameToSlug(name: String): String {
            val map = mapOf(
                'à' to "a", 'á' to "a", 'â' to "a", 'ã' to "a", 'ä' to "a", 'å' to "a", 'ă' to "a", 'ắ' to "a", 'ặ' to "a", 'ằ' to "a", 'ẳ' to "a", 'ẵ' to "a", 'ấ' to "a", 'ầ' to "a", 'ẩ' to "a", 'ẫ' to "a", 'ậ' to "a", 'ả' to "a", 'ạ' to "a",
                'è' to "e", 'é' to "e", 'ê' to "e", 'ë' to "e", 'ề' to "e", 'ế' to "e", 'ệ' to "e", 'ể' to "e", 'ễ' to "e", 'ẹ' to "e", 'ẻ' to "e", 'ẽ' to "e",
                'ì' to "i", 'í' to "i", 'î' to "i", 'ï' to "i", 'ị' to "i", 'ỉ' to "i", 'ĩ' to "i",
                'ò' to "o", 'ó' to "o", 'ô' to "o", 'õ' to "o", 'ö' to "o", 'ồ' to "o", 'ố' to "o", 'ộ' to "o", 'ổ' to "o", 'ỗ' to "o", 'ờ' to "o", 'ớ' to "o", 'ợ' to "o", 'ở' to "o", 'ỡ' to "o", 'ọ' to "o", 'ỏ' to "o",
                'ù' to "u", 'ú' to "u", 'û' to "u", 'ü' to "u", 'ừ' to "u", 'ứ' to "u", 'ự' to "u", 'ử' to "u", 'ữ' to "u", 'ụ' to "u", 'ủ' to "u", 'ũ' to "u",
                'ỳ' to "y", 'ý' to "y", 'ỵ' to "y", 'ỷ' to "y", 'ỹ' to "y", 'đ' to "d", 'Đ' to "d", 'ư' to "u", 'ơ' to "o",
                'Ă' to "a", 'Â' to "a", 'Ê' to "e", 'Ô' to "o", 'Ơ' to "o", 'Ư' to "u", 'ớ' to "o", 'ờ' to "o", 'ở' to "o", 'ỡ' to "o", 'ợ' to "o"
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
                    app.get("$mainUrl/api/films/$slug?page=1", headers = commonHeaders).parsedSafe<NguonCApiResponse>()?.items
                } catch (_: Exception) { null }
                if (!items.isNullOrEmpty()) {
                    result = items.filter { it.slug != movie.slug }.take(20).mapNotNull { parseApiItem(it) }
                    break
                }
            }
            if (result.isEmpty()) {
                result = app.get("$mainUrl/api/films/phim-moi-cap-nhat?page=1", headers = commonHeaders).parsedSafe<NguonCApiResponse>()?.items?.filter { it.slug != movie.slug }?.take(20)?.mapNotNull { parseApiItem(it) } ?: emptyList()
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
        movie: NguonCMovie, dinhDang: String, theLoai: String, namPhatHanh: String, quocGia: String
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
            if (totalEp.isNotBlank() && totalEp != "0") addInfo("🎞",  "Số tập",     "$totalEp tập")
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

    // ── Encryption helpers ────────────────────────────────────────────────────

    private fun deriveKey(token: String): ByteArray {
        // Try multiple derivation methods
        val methods = listOf(
            // Method 1: SHA-256 of token string, take first 16 bytes
            { MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).copyOf(16) },
            // Method 2: MD5 of token string
            { MessageDigest.getInstance("MD5").digest(token.toByteArray()) },
            // Method 3: First 32 hex chars as direct key
            { token.take(32).chunked(2).map { it.toInt(16).toByte() }.toByteArray() },
            // Method 4: SHA-256 of hex-decoded token
            { 
                val hexBytes = token.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                MessageDigest.getInstance("SHA-256").digest(hexBytes).copyOf(16) 
            }
        )
        return methods.firstNotNullOfOrNull { try { it() } catch (_: Exception) { null } } 
            ?: ByteArray(16) { 0 }
    }

    private fun tryDecryptAesGcm(content: String, keyBytes: ByteArray): String? {
        return try {
            // Parse IV from #ENC-AESGCM;iv=...
            val ivHex = Regex("""#ENC-AESGCM;iv=([a-f0-9]+)""").find(content)?.groupValues?.getOrNull(1) ?: return null
            val iv = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            // Extract base64 data after headers
            val lines = content.lines()
            val dataLines = lines.dropWhile { it.startsWith("#") || it.isBlank() }
            val b64Data = dataLines.joinToString("").trim()
            if (b64Data.isEmpty()) return null

            val encryptedData = Base64.decode(b64Data, Base64.DEFAULT)

            // AES-128-GCM with 128-bit tag (default for GCM)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
            val decrypted = cipher.doFinal(encryptedData)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun findToken(html: String): String? {
        val patterns = listOf(
            Regex("""token\s*[:=]\s*["']([a-f0-9]{28,64})["']""", RegexOption.IGNORE_CASE),
            Regex("""["']t["']\s*:\s*["']([a-f0-9]{28,64})["']"""),
            Regex("""key\s*[:=]\s*["']([a-f0-9]{28,64})["']""", RegexOption.IGNORE_CASE),
            Regex("""var\s+(?:token|t|key)\s*=\s*["']([a-f0-9]{28,64})["']""", RegexOption.IGNORE_CASE),
            Regex("""data-token\s*=\s*["']([a-f0-9]{28,64})["']""", RegexOption.IGNORE_CASE),
            Regex("""["']([a-f0-9]{32,64})["']""")
        )

        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                val candidate = match.groupValues[1]
                if (candidate.toSet().size > 4) return candidate
            }
        }
        return null
    }

    private suspend fun findTokenDeep(html: String, embedDomain: String, referer: String, cookies: String): String? {
        var token = findToken(html)
        if (token != null) return token

        // Search in JS files
        val jsFiles = Regex("""["']([^"']*(?:player1|debug|player|embed|stream)[^"']*\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.distinct().toList()

        for (jsPath in jsFiles) {
            val jsUrl = if (jsPath.startsWith("http")) jsPath else "$embedDomain/$jsPath"
            try {
                val jsContent = app.get(jsUrl, headers = mapOf(
                    "Referer" to referer, "User-Agent" to USER_AGENT, "Cookie" to cookies
                )).text
                token = findToken(jsContent)
                if (token != null) return token
            } catch (_: Exception) { }
        }
        return null
    }

    private fun buildM3u8Url(embedDomain: String, hash: String, token: String): String {
        val json = """{"h":"$hash","t":"$token"}"""
        val base64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "$embedDomain/$base64.m3u8"
    }

    // ── Main link loading logic ───────────────────────────────────────────────

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
                    var targetUrl = url

                    // Fix dead sing.phimmoi.net links
                    if (url.contains("sing.phimmoi.net")) {
                        val hash = Regex("""/([^/]+)/hls\.m3u8""").find(url)?.groupValues?.get(1)
                        if (hash != null) {
                            // Use embed domain from API or default
                            targetUrl = "https://embed15.streamc.xyz/embed.php?hash=$hash"
                        } else return@async
                    }

                    // Extract embed domain dynamically (embed11, embed15, etc.)
                    val embedDomain = Regex("""(https?://embed\d+\.streamc\.xyz)""").find(targetUrl)?.groupValues?.getOrNull(1)
                        ?: Regex("""(https?://[^/]+)""").find(targetUrl)?.groupValues?.getOrNull(1)
                        ?: "https://embed15.streamc.xyz"

                    // Case 1: Direct m3u8 URL
                    if (targetUrl.contains(".m3u8") || targetUrl.contains(".m3u9")) {
                        try {
                            val m3u8Resp = app.get(targetUrl, headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "$mainUrl/"
                            ), interceptor = cfInterceptor)
                            val content = m3u8Resp.text

                            if (content.contains("#EXTM3U")) {
                                if (!content.contains("#ENC-AESGCM") && !content.contains("#EXT-X-KEY")) {
                                    // Plain m3u8
                                    val server = NguonCProxyServer("", targetUrl)
                                    server.start(); activeServers.add(server)
                                    val proxyBase = "http://127.0.0.1:${server.port}"
                                    server.setM3U8(rewriteM3U8(content, proxyBase))
                                    callback(newExtractorLink("NguonC", serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
                                        quality = Qualities.P1080.value
                                        headers = mapOf("User-Agent" to USER_AGENT)
                                    })
                                    linkFound = true
                                } else {
                                    // Encrypted - try decrypt
                                    val token = findToken(content) ?: findTokenDeep(content, embedDomain, targetUrl, m3u8Resp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                                    val decrypted = token?.let { tryDecryptAesGcm(content, deriveKey(it)) }

                                    if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                        val server = NguonCProxyServer("", targetUrl)
                                        server.start(); activeServers.add(server)
                                        val proxyBase = "http://127.0.0.1:${server.port}"
                                        server.setM3U8(rewriteM3U8(decrypted, proxyBase))
                                        callback(newExtractorLink("NguonC", "$serverName (Decrypted)", "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
                                            quality = Qualities.P1080.value
                                            headers = mapOf("User-Agent" to USER_AGENT)
                                        })
                                        linkFound = true
                                    } else {
                                        // Fallback: direct encrypted link
                                        callback(newExtractorLink("NguonC", "$serverName (Encrypted)", targetUrl, ExtractorLinkType.M3U8) {
                                            quality = Qualities.P1080.value
                                            headers = mapOf(
                                                "User-Agent" to USER_AGENT,
                                                "Referer" to targetUrl,
                                                "Origin" to embedDomain
                                            )
                                        })
                                        linkFound = true
                                    }
                                }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                        return@async
                    }

                    // Case 2: Embed page (embed.php?hash=...)
                    if (targetUrl.contains("embed.php") && targetUrl.contains("hash=")) {
                        val hash = Regex("""hash=([a-f0-9]+)""").find(targetUrl)?.groupValues?.get(1)
                        if (hash == null) return@async

                        // Fetch embed page
                        val embedRes = try {
                            app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf(
                                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                            ))
                        } catch (e: Exception) { return@async }

                        val html = embedRes.text
                        val cookies = embedRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

                        // Strategy 1: Find direct m3u8 in HTML
                        var m3u8Url = findDirectM3u8(html, targetUrl)

                        // Strategy 2: Build from token
                        if (m3u8Url.isNullOrEmpty()) {
                            val token = findTokenDeep(html, embedDomain, targetUrl, cookies)
                            if (token != null) {
                                m3u8Url = buildM3u8Url(embedDomain, hash, token)
                            }
                        }

                        // Strategy 3: Direct hash URL
                        if (m3u8Url.isNullOrEmpty()) {
                            m3u8Url = "$embedDomain/$hash.m3u8"
                        }

                        if (!m3u8Url.isNullOrEmpty()) {
                            val m3u8Headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to targetUrl,
                                "Origin" to embedDomain,
                                "Cookie" to cookies,
                                "Accept" to "*/*"
                            )

                            try {
                                val m3u8Resp = app.get(m3u8Url, headers = m3u8Headers, interceptor = cfInterceptor)
                                val m3u8Content = m3u8Resp.text

                                if (m3u8Content.contains("#EXTM3U")) {
                                    val isEncrypted = m3u8Content.contains("#ENC-AESGCM") || m3u8Content.contains("#EXT-X-KEY")

                                    if (!isEncrypted) {
                                        val server = NguonCProxyServer("", targetUrl)
                                        server.start(); activeServers.add(server)
                                        val proxyBase = "http://127.0.0.1:${server.port}"
                                        server.setM3U8(rewriteM3U8(m3u8Content, proxyBase))
                                        callback(newExtractorLink("NguonC", serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
                                            quality = Qualities.P1080.value
                                            headers = mapOf("User-Agent" to USER_AGENT)
                                        })
                                        linkFound = true
                                    } else {
                                        // Try decrypt with multiple key derivations
                                        val token = findTokenDeep(html, embedDomain, targetUrl, cookies)
                                        var decrypted: String? = null

                                        if (token != null) {
                                            val key = deriveKey(token)
                                            decrypted = tryDecryptAesGcm(m3u8Content, key)
                                        }

                                        if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                            val server = NguonCProxyServer("", targetUrl)
                                            server.start(); activeServers.add(server)
                                            val proxyBase = "http://127.0.0.1:${server.port}"
                                            server.setM3U8(rewriteM3U8(decrypted, proxyBase))
                                            callback(newExtractorLink("NguonC", "$serverName (Decrypted)", "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
                                                quality = Qualities.P1080.value
                                                headers = mapOf("User-Agent" to USER_AGENT)
                                            })
                                            linkFound = true
                                        } else {
                                            // Return encrypted with headers - let player handle it
                                            callback(newExtractorLink("NguonC", "$serverName (Encrypted)", m3u8Url, ExtractorLinkType.M3U8) {
                                                quality = Qualities.P1080.value
                                                headers = m3u8Headers
                                            })
                                            linkFound = true
                                        }
                                    }
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                        return@async
                    }

                    // Case 3: Other embed pages
                    try {
                        val embedRes = app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf(
                            "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                        ))
                        val html = embedRes.text
                        val cookies = embedRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

                        var m3u8Url = findDirectM3u8(html, targetUrl)
                        if (m3u8Url.isNullOrEmpty()) {
                            m3u8Url = Regex("""https?://[^\s"']+\.(?:m3u8|m3u9)[^\s"']*""").find(html)?.value
                        }

                        if (!m3u8Url.isNullOrEmpty()) {
                            val m3u8Headers = mapOf(
                                "User-Agent" to USER_AGENT, "Referer" to targetUrl,
                                "Origin" to Regex("""https?://[^/]+""").find(targetUrl)?.value ?: "",
                                "Cookie" to cookies, "Accept" to "*/*"
                            )

                            val m3u8Resp = app.get(m3u8Url, headers = m3u8Headers, interceptor = cfInterceptor)
                            val m3u8Content = m3u8Resp.text

                            if (m3u8Content.contains("#EXTM3U")) {
                                val isEncrypted = m3u8Content.contains("#ENC-AESGCM") || m3u8Content.contains("#EXT-X-KEY")

                                if (!isEncrypted) {
                                    val server = NguonCProxyServer("", targetUrl)
                                    server.start(); activeServers.add(server)
                                    val proxyBase = "http://127.0.0.1:${server.port}"
                                    server.setM3U8(rewriteM3U8(m3u8Content, proxyBase))
                                    callback(newExtractorLink("NguonC", serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
                                        quality = Qualities.P1080.value
                                        headers = mapOf("User-Agent" to USER_AGENT)
                                    })
                                    linkFound = true
                                } else {
                                    callback(newExtractorLink("NguonC", "$serverName (Encrypted)", m3u8Url, ExtractorLinkType.M3U8) {
                                        quality = Qualities.P1080.value
                                        headers = m3u8Headers
                                    })
                                    linkFound = true
                                }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }.awaitAll()
        }
        return linkFound
    }

    private fun findDirectM3u8(html: String, referer: String): String? {
        val patterns = listOf(
            Regex("""jwplayer\s*\(\s*\{[^}]*?file\s*:\s*["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL),
            Regex("""sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL),
            Regex("""data-m3u8\s*=\s*["']([^"']+)["']"""),
            Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)"""),
            Regex("""var\s+(?:url|m3u8|source|src)\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:fetch|xhr\.open)\(["'][^"']*["'],\s*["'](https?://[^"']*\.m3u8[^"']*)["']"""),
            Regex("""(?:url|m3u8|source|src)\s*[:=]\s*["'](https?://[^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                var url = match.groupValues.getOrNull(1)?.replace("\\/", "/")?.trim()
                if (!url.isNullOrEmpty() && !url.startsWith("blob:")) {
                    if (url.startsWith("/")) {
                        val domain = Regex("""https?://[^/]+""").find(referer)?.value ?: return url
                        url = domain + url
                    }
                    return url
                }
            }
        }
        return null
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
