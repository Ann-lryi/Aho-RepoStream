package com.phimnguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.EnumSet
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    private val cfInterceptor = WebViewResolver(Regex(""".*streamc\.xyz|.*amass\d+\.top|.*hihihoho\d+\.top|.*phimmoi\.net|.*seouls\d+\.amass\d+\.top"""))

    private val commonHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    private val API_PREFIX = "API::"

    override val mainPage = mainPageOf(
        "${API_PREFIX}api/films/phim-moi-cap-nhat"    to "Phim M\u1EDBi C\u1EADp Nh\u1EADt",
        "danh-sach/phim-le"                           to "Phim L\u1EBB",
        "danh-sach/phim-bo"                           to "Phim B\u1ED9",
        "danh-sach/tv-shows"                          to "Nh\u1EADt B\u1EA3n + Anime"
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
                Regex("""[Tt]\u1EADp\s*(\d+)""").find(statusText)?.groupValues?.get(1)?.toIntOrNull()
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
            currentEp.equals("FULL", ignoreCase = true) -> null
            currentEp.startsWith("Ho\u00E0n t\u1EA5t", ignoreCase = true) -> null
            else ->
                Regex("""[Tt]\u1EADp\s*(\d+)""").find(currentEp)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\s*/\s*\d+""").find(currentEp)?.groupValues?.get(1)?.toIntOrNull()
        }

        val lang      = item.language ?: ""
        val hasSub    = lang.contains("Vietsub",     ignoreCase = true)
        val hasDub    = lang.contains("Thuy\u1EBFt Minh", ignoreCase = true)
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
        val movie = res?.movie ?: throw ErrorLoadingException("Kh\u00F4ng th\u1EC3 t\u1EA3i d\u1EEF li\u1EC7u phim")

        val epMap = linkedMapOf<String, MutableList<String>>()
        movie.episodes?.forEachIndexed { idx, server ->
            val serverName = server.server_name ?: server.name ?: if (idx == 0) "Vietsub" else "Thuy\u1EBFt minh"
            val items = server.items ?: server.list
            items?.forEach { ep ->
                val directM3u8 = ep.m3u8
                if (!directM3u8.isNullOrBlank()) {
                    epMap.getOrPut(ep.name ?: "0") { mutableListOf() }.add("$serverName::$directM3u8")
                } else {
                    val embed = ep.embed?.replace("\\/", "/") ?: ""
                    if (embed.isNotBlank()) {
                        epMap.getOrPut(ep.name ?: "0") { mutableListOf() }.add("$serverName::$embed")
                    }
                }
            }
        }

        if (epMap.isEmpty()) throw ErrorLoadingException("Kh\u00F4ng t\u00ECm th\u1EA5y t\u1EADp phim")

        val episodes = epMap.map { (epName, embeds) ->
            newEpisode(embeds.distinct().joinToString("|")) {
                this.name    = "T\u1EADp $epName"
                this.episode = epName.toIntOrNull()
            }
        }.sortedBy { it.episode ?: 0 }

        val categories = movie.category ?: emptyMap()
        val dinhDang = categories.values.find { it.group?.name == "\u0110\u1ECBnh d\u1EA1ng" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val theLoai = categories.values.find { it.group?.name == "Th\u1EC3 lo\u1EA1i" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val namPhatHanh = categories.values.find { it.group?.name == "N\u0103m" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val quocGia = categories.values.find { it.group?.name == "Qu\u1ED1c gia" }?.list?.map { it.name }?.joinToString(", ") ?: ""

        val beautifulPlot = buildBeautifulDescription(movie, dinhDang, theLoai, namPhatHanh, quocGia)

        val genreItems = categories.values.flatMap { it.list ?: emptyList() }.filter { !it.id.isNullOrBlank() }
        val theLoaiItems = categories.values
            .filter { cat ->
                val gname = cat.group?.name ?: ""
                !gname.contains("\u0103m") && !gname.contains("gia") && !gname.contains("nh d") && (cat.list?.size ?: 0) >= 2
            }
            .maxByOrNull { it.list?.size ?: 0 }?.list ?: genreItems.take(5)

        fun nameToSlug(name: String): String {
            val map = mapOf(
                '\u00E0' to "a", '\u00E1' to "a", '\u00E2' to "a", '\u00E3' to "a", '\u00E4' to "a", '\u00E5' to "a",
                '\u0103' to "a", '\u1EAF' to "a", '\u1EB7' to "a", '\u1EB1' to "a", '\u1EB3' to "a", '\u1EB5' to "a",
                '\u1EA5' to "a", '\u1EA7' to "a", '\u1EA9' to "a", '\u1EAB' to "a", '\u1EAD' to "a", '\u1EA3' to "a", '\u1EA1' to "a",
                '\u00E8' to "e", '\u00E9' to "e", '\u00EA' to "e", '\u00EB' to "e",
                '\u1EC1' to "e", '\u1EBF' to "e", '\u1EC7' to "e", '\u1EC3' to "e", '\u1EC5' to "e", '\u1EB9' to "e", '\u1EBB' to "e", '\u1EBD' to "e",
                '\u00EC' to "i", '\u00ED' to "i", '\u00EE' to "i", '\u00EF' to "i", '\u1ECB' to "i", '\u1EC9' to "i", '\u0129' to "i",
                '\u00F2' to "o", '\u00F3' to "o", '\u00F4' to "o", '\u00F5' to "o", '\u00F6' to "o",
                '\u1ED3' to "o", '\u1ED1' to "o", '\u1ED9' to "o", '\u1ED5' to "o", '\u1ED7' to "o",
                '\u1EDD' to "o", '\u1EDB' to "o", '\u1EE3' to "o", '\u1EDF' to "o", '\u1EE1' to "o", '\u1ECD' to "o", '\u1ECF' to "o",
                '\u00F9' to "u", '\u00FA' to "u", '\u00FB' to "u", '\u00FC' to "u",
                '\u1EEB' to "u", '\u1EE9' to "u", '\u1EF1' to "u", '\u1EED' to "u", '\u1EEF' to "u", '\u1EE5' to "u", '\u1EE7' to "u", '\u0169' to "u",
                '\u1EF3' to "y", '\u00FD' to "y", '\u1EF5' to "y", '\u1EF7' to "y", '\u1EF9' to "y",
                '\u0111' to "d", '\u0110' to "d", '\u01B0' to "u", '\u01A1' to "o"
            )
            return name.lowercase().trim().map { c ->
                map[c] ?: if (c in 'a'..'z' || c in '0'..'9') c.toString() else if (c == ' ') "-" else ""
            }.joinToString("").replace(Regex("-{2,}"), "-").trim('-')
        }

        val recommendations: List<SearchResponse> = try {
            var result: List<SearchResponse> = emptyList()
            for (genre in theLoaiItems.take(3)) {
                val genreName = genre.name ?: continue
                val slug2 = nameToSlug(genreName)
                if (slug2.isBlank()) continue
                val items = try {
                    app.get("$mainUrl/api/films/$slug2?page=1", headers = commonHeaders).parsedSafe<NguonCApiResponse>()?.items
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
                currentEp.contains("ho\u00E0n t\u1EA5t", ignoreCase = true) -> "#2196F3"
                currentEp.contains("full",     ignoreCase = true) -> "#9C27B0"
                else -> "#4CAF50"
            }

            addInfo("\uD83D\uDCFA", "Tr\u1EA1ng th\u00E1i",  currentEp,  statusColor)
            if (totalEp.isNotBlank() && totalEp != "0") addInfo("\uD83C\uDFDE",  "S\u1ED1 t\u1EADp",     "$totalEp t\u1EADp")
            addInfo("\u23F1",  "Th\u1EDDi l\u01B0\u1EE3ng",  time)
            addInfo("\uD83C\uDFAC",  "Ch\u1EA5t l\u01B0\u1EE3ng",  quality,    "#E91E63")
            addInfo("\uD83D\uDD0A",  "Ng\u00F4n ng\u1EEF",   language)
            addInfo("\uD83C\uDF0D",  "Qu\u1ED1c gia",   quocGia)
            addInfo("\uD83D\uDCC5",  "N\u0103m",        namPhatHanh)
            addInfo("\uD83D\uDCFD",  "\u0110\u1ECBnh d\u1EA1ng",  dinhDang)
            addInfo("\uD83C\uDFA5",  "\u0110\u1EA1o di\u1EC5n",   director)
            addInfo("\uD83C\uDFAD",  "Di\u1EC5n vi\u00EAn",  casts)
            addInfo("\uD83C\uDFF7",  "Th\u1EC3 lo\u1EA1i",   theLoai)

            if (description.isNotBlank()) {
                append("<br><b><font color='#FFEB3B'>\u2726 N\u1ED8I DUNG PHIM</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(description)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Local proxy server for segment delivery
    // ═══════════════════════════════════════════════════════════════════════════

    private val activeServers = mutableListOf<NguonCProxyServer>()

    inner class NguonCProxyServer(
        private val segReferer: String
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        val port: Int get() = serverSocket?.localPort ?: 0
        private val threadPool = java.util.concurrent.Executors.newCachedThreadPool()
        @Volatile private var _m3u8: String = ""

        fun setM3U8(content: String) { _m3u8 = content }
        private fun getM3U8(): String = _m3u8

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

        fun stop() {
            try { serverSocket?.close() } catch (_: Exception) {}
            try { threadPool.shutdownNow() } catch (_: Exception) {}
        }
    }

    /** Rewrite m3u8 segment URLs to go through our local proxy */
    private fun rewriteM3U8(m3u8: String, proxyBase: String, m3u8BaseUrl: String = ""): String {
        return m3u8.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isBlank()) {
                line
            } else if (trimmed.startsWith("http")) {
                "$proxyBase/seg/${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val resolvedUrl = if (m3u8BaseUrl.isNotEmpty()) {
                    val base = m3u8BaseUrl.trimEnd('/')
                    "$base/$trimmed"
                } else {
                    trimmed
                }
                if (resolvedUrl.startsWith("http")) {
                    "$proxyBase/seg/${java.net.URLEncoder.encode(resolvedUrl, "UTF-8")}"
                } else {
                    line
                }
            } else {
                line
            }
        }
    }

    /** Create proxy server and register m3u8 link - returns true if link was added */
    private suspend fun registerM3U8Link(
        m3u8Content: String, referer: String, m3u8BaseUrl: String,
        serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!m3u8Content.contains("#EXTM3U")) return false
        val server = NguonCProxyServer(referer)
        server.start(); activeServers.add(server)
        val proxyBase = "http://127.0.0.1:${server.port}"
        server.setM3U8(rewriteM3U8(m3u8Content, proxyBase, m3u8BaseUrl))
        callback(newExtractorLink("NguonC", serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
            quality = Qualities.P1080.value
            headers = mapOf("User-Agent" to USER_AGENT)
        })
        return true
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Streamc.xyz token & key extraction
    // ═══════════════════════════════════════════════════════════════════════════
    //
    //  The embed page uses JWPlayer with file parameter:
    //    OLD way: {"file":"BASE64_TOKEN.m3u9"}   → token directly in HTML
    //    NEW way: {"file":"blob:https://..."}     → JS constructs blob from XHR to TOKEN.m3u9
    //
    //  BASE64_TOKEN = Base64URL of {"h":"32hex_hash","t":"40hex_token"}
    //  .m3u9 extension → plain m3u8 response
    //  .m3u8 extension → AES-256-GCM encrypted response (#ENC-AESGCM header)

    /** Search HTML for the JWPlayer file token.
     *  Returns Pair(token, hadBlobUrl):
     *    token = the base64 string WITHOUT .m3u8/.m3u9 extension, or null
     *    hadBlobUrl = true if a blob: URL was detected (need WebView approach) */
    private fun findTokenInHTML(html: String): Pair<String?, Boolean> {
        var hadBlobUrl = false

        // Pattern 1: JWPlayer file with blob URL
        val blobMatch = Regex(""""file"\s*:\s*"(blob:[^"]+)"""").find(html)
        if (blobMatch != null) {
            println("[NguonC] Found blob URL in jwplayer: ${blobMatch.groupValues[1].take(40)}...")
            hadBlobUrl = true
        }

        // Pattern 2: JWPlayer file with token.m3u9 or token.m3u8
        // Use permissive regex to catch all base64 variants (standard + url-safe + padding)
        val tokenPatterns = listOf(
            Regex(""""file"\s*:\s*"([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)""""),
            Regex("""file\s*[:=]\s*["']([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)["']"""),
            Regex("""["']([A-Za-z0-9_+/=\\-]{20,})\.(m3u8|m3u9)["']"""),
            // Also catch: var xxx = "TOKEN.m3u9" or url: "TOKEN.m3u9"
            Regex("""(?:var|let|const)\s+\w+\s*=\s*["']([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)["']"""),
            Regex("""(?:url|src|source|stream)\s*[:=]\s*["']([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)["']""")
        )

        for (pattern in tokenPatterns) {
            val match = pattern.find(html)
            if (match != null) {
                val token = match.groupValues.getOrNull(1)
                if (token != null && token.length > 10 && !token.startsWith("blob:")) {
                    println("[NguonC] Found token in HTML: ${token.take(20)}... (length=${token.length})")
                    return Pair(token, hadBlobUrl)
                }
            }
        }
        return Pair(null, hadBlobUrl)
    }

    /** Try to find token in data-obf attribute (older embed pages) */
    private fun parseStreamcObf(html: String): String? {
        val obfBase64 = Regex("""data-obf="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""data-obf='([^']+)'""").find(html)?.groupValues?.getOrNull(1)
            ?: return null
        try {
            val obfJson = String(Base64.decode(obfBase64, Base64.DEFAULT), Charsets.UTF_8)
            // Try sUb first (it's the URL slug/token)
            val sUb = Regex(""""sUb"\s*:\s*"([^"]+)"""").find(obfJson)?.groupValues?.get(1)
            if (sUb != null && sUb.length > 10) {
                println("[NguonC] Found sUb from data-obf: ${sUb.take(20)}...")
                return sUb
            }
            // Also try "file" or "url" inside obf JSON
            val fileToken = Regex(""""(?:file|url)"\s*:\s*"([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)"""").find(obfJson)
            if (fileToken != null) {
                val t = fileToken.groupValues.getOrNull(1)
                if (t != null && t.length > 10) {
                    println("[NguonC] Found token from data-obf file field: ${t.take(20)}...")
                    return t
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** Find AES-GCM encryption key (kX) from multiple sources:
     *  1. data-obf attribute in HTML
     *  2. Inline <script> tags
     *  3. player1.js
     *  4. debug.js */
    private suspend fun findEncryptionKey(html: String, embedDomain: String, referer: String): String? {
        // Source 1: data-obf attribute
        val obfBase64 = Regex("""data-obf="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""data-obf='([^']+)'""").find(html)?.groupValues?.getOrNull(1)
        if (obfBase64 != null) {
            try {
                val obfJson = String(Base64.decode(obfBase64, Base64.DEFAULT), Charsets.UTF_8)
                val kX = Regex(""""kX"\s*:\s*"([^"]+)"""").find(obfJson)?.groupValues?.get(1)
                if (kX != null && kX.length >= 16) {
                    println("[NguonC] Found kX from data-obf: length=${kX.length}, value=${kX}")
                    return kX
                }
            } catch (_: Exception) {}
        }

        // Source 2: Inline <script> tags - look for key patterns
        val scriptContents = Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.toList()
        for (script in scriptContents) {
            // Look for kX, key, or aesKey variable assignments
            val keyPatterns = listOf(
                Regex("""(?:kX|key|aesKey|encKey)\s*[:=]\s*["']([a-fA-F0-9]{32,})["']"""),
                Regex("""["'](kX|key|aesKey)["']\s*:\s*["']([a-fA-F0-9]{32,})["']""")
            )
            for (pattern in keyPatterns) {
                val match = pattern.find(script)
                if (match != null) {
                    val key = match.groupValues.last()  // last group has the actual key value
                    if (key.length >= 32) {
                        println("[NguonC] Found encryption key in inline script: ${key.take(20)}...")
                        return key
                    }
                }
            }
        }

        // Source 3: player1.js
        val kXFromPlayer = findKeyInJS(html, embedDomain, referer, "player1")
        if (kXFromPlayer != null) return kXFromPlayer

        // Source 4: debug.js
        val kXFromDebug = findKeyInJS(html, embedDomain, referer, "debug")
        if (kXFromDebug != null) return kXFromDebug

        return null
    }

    /** Search a JS file for encryption key patterns */
    private suspend fun findKeyInJS(html: String, embedDomain: String, referer: String, jsName: String): String? {
        val jsMatch = Regex("""src=["']([^"']*${jsName}[^"']*\.js[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
        if (jsMatch != null) {
            val jsPath = jsMatch.groupValues[1]
            val jsUrl = if (jsPath.startsWith("http")) jsPath else "$embedDomain/$jsPath"
            try {
                val jsContent = app.get(jsUrl, headers = mapOf(
                    "Referer" to referer, "User-Agent" to USER_AGENT
                )).text

                val keyPatterns = listOf(
                    Regex("""(?:kX|key|aesKey|encKey)\s*[:=]\s*["']([a-fA-F0-9]{32,})["']"""),
                    Regex("""["'](kX|key|aesKey|encKey)["']\s*:\s*["']([a-fA-F0-9]{32,})["']"""),
                    // Also look for the key as a hex string in variable assignments
                    Regex("""(?:var|let|const)\s+\w*[Kk]ey\w*\s*=\s*["']([a-fA-F0-9]{32,})["']"""),
                    // Look for key derivation patterns
                    Regex("""decrypt\s*\(\s*(?:data|encrypted|enc)\s*,\s*["']([a-fA-F0-9]{32,})["']""")
                )

                for (pattern in keyPatterns) {
                    val match = pattern.find(jsContent)
                    if (match != null) {
                        val key = match.groupValues.last()
                        if (key.length >= 32) {
                            println("[NguonC] Found encryption key in $jsName.js: ${key.take(20)}...")
                            return key
                        }
                    }
                }
            } catch (e: Exception) {
                println("[NguonC] Failed to fetch $jsName.js: ${e.message}")
            }
        }
        return null
    }

    /** Find token from external JS files (player1.js, debug.js) */
    private suspend fun findTokenInJS(html: String, embedDomain: String, referer: String): String? {
        val jsNames = listOf("player1", "player", "debug", "config")
        for (jsName in jsNames) {
            val jsMatch = Regex("""src=["']([^"']*${jsName}[^"']*\.js[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
            if (jsMatch != null) {
                val jsPath = jsMatch.groupValues[1]
                val jsUrl = if (jsPath.startsWith("http")) jsPath else "$embedDomain/$jsPath"
                try {
                    val jsContent = app.get(jsUrl, headers = mapOf(
                        "Referer" to referer, "User-Agent" to USER_AGENT
                    )).text

                    val jsTokenPatterns = listOf(
                        Regex(""""file"\s*:\s*"([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)""""),
                        Regex("""(?:streamURL|fileUrl|videoUrl|sourceUrl)\s*=\s*["']([A-Za-z0-9_+/=\\-]{20,})"""),
                        Regex("""["']([A-Za-z0-9_+/=\\-]{20,})\.(m3u8|m3u9)["']"""),
                        // Look for URL construction patterns
                        Regex("""(?:url|src)\s*[+=]\s*["']([A-Za-z0-9_+/=\\-]{20,})"""),
                        Regex("""["']([A-Za-z0-9_+/=\\-]{20,})\.(m3u8|m3u9)""""),
                        // Look for base64-looking strings that could be the token
                        Regex("""(?:var|let|const)\s+\w*[Tt]oken\w*\s*=\s*["']([A-Za-z0-9_+/=\\-]{20,})["']""")
                    )
                    for (pattern in jsTokenPatterns) {
                        val match = pattern.find(jsContent)
                        if (match != null) {
                            val candidate = match.groupValues.getOrNull(1)
                            if (candidate != null && candidate.length > 10) {
                                println("[NguonC] Found token in $jsName.js: ${candidate.take(20)}...")
                                return candidate
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("[NguonC] Failed to fetch $jsName.js for token: ${e.message}")
                }
            }
        }
        return null
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /** Derive AES key from token using multiple methods (SHA-256, MD5, hex parse, etc.)
     *  Key insight: the AES key is NOT kX directly, but a HASH of the token.
     *  The player1.js derives the key this way. */
    private fun deriveKeyFromToken(token: String): List<Pair<String, ByteArray>> {
        val keys = mutableListOf<Pair<String, ByteArray>>()

        // SHA-256 of token UTF-8, take first 16 bytes (AES-128)
        try {
            val sha256 = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            keys.add(Pair("SHA-256(utf8).16", sha256.copyOf(16)))
            keys.add(Pair("SHA-256(utf8).32", sha256))
        } catch (_: Exception) {}

        // MD5 of token UTF-8 (16 bytes = AES-128)
        try {
            val md5 = MessageDigest.getInstance("MD5").digest(token.toByteArray(Charsets.UTF_8))
            keys.add(Pair("MD5(utf8)", md5))
        } catch (_: Exception) {}

        // SHA-256 of hex-decoded token bytes
        try {
            val hexBytes = hexToBytes(token)
            val sha256hex = MessageDigest.getInstance("SHA-256").digest(hexBytes)
            keys.add(Pair("SHA-256(hexBytes).16", sha256hex.copyOf(16)))
            keys.add(Pair("SHA-256(hexBytes).32", sha256hex))
        } catch (_: Exception) {}

        // First 32 hex chars as bytes → 16 bytes AES-128
        if (token.length >= 32) {
            try { keys.add(Pair("hex(first32)", hexToBytes(token.take(32)))) } catch (_: Exception) {}
        }

        // Full token as hex bytes
        if (token.length >= 16 && token.length % 2 == 0) {
            try {
                val fullBytes = hexToBytes(token)
                if (fullBytes.size == 16 || fullBytes.size == 24 || fullBytes.size == 32) {
                    keys.add(Pair("hex(full)", fullBytes))
                }
            } catch (_: Exception) {}
        }

        return keys
    }

    /** Decrypt AES-GCM encrypted m3u8 content.
     *  Format: #ENC-AESGCM;iv=HEX12BYTES\n#EXT-X-B65:offset-length\nBASE64_CIPHERTEXT
     *  Tries kX directly AND derived keys from token */
    private fun decryptStreamcM3u8(content: String, keyHex: String, token: String? = null): String? {
        return try {
            val ivMatch = Regex("""#ENC-AESGCM;iv=([a-f0-9A-F]+)""").find(content) ?: return null
            val ivHex = ivMatch.groupValues[1]
            if (ivHex.length != 24) {
                println("[NguonC] IV length ${ivHex.length} != 24, trying anyway...")
            }
            val iv = hexToBytes(ivHex)

            // Parse #EXT-X-B65 header for offset-length if present
            val b65Match = Regex("""#EXT-X-B65:(\d+)-(\d+)""").find(content)
            val (offset, length) = if (b65Match != null) {
                Pair(b65Match.groupValues[1].toIntOrNull() ?: 0, b65Match.groupValues[2].toIntOrNull() ?: 0)
            } else {
                Pair(0, 0)
            }

            val lines = content.lines()
            val dataLines = lines.dropWhile { it.startsWith("#") || it.isBlank() }
            val b64Data = dataLines.joinToString("").trim()
            if (b64Data.isEmpty()) return null

            val encryptedData = Base64.decode(b64Data, Base64.DEFAULT)

            // Build list of key attempts from multiple sources
            val keyAttempts = mutableListOf<Pair<String, ByteArray>>()

            // Source 1: kX from data-obf - try various interpretations
            if (keyHex.length >= 16 && keyHex.length % 2 == 0) {
                try {
                    val fullBytes = hexToBytes(keyHex)
                    if (fullBytes.size == 16 || fullBytes.size == 24 || fullBytes.size == 32) {
                        keyAttempts.add(Pair("kX-hex(full)", fullBytes))
                    }
                } catch (_: Exception) {}
            }
            if (keyHex.length >= 32) {
                try { keyAttempts.add(Pair("kX-hex(first32)", hexToBytes(keyHex.take(32)))) } catch (_: Exception) {}
            }
            if (keyHex.length >= 64) {
                try { keyAttempts.add(Pair("kX-hex(first64)", hexToBytes(keyHex.take(64)))) } catch (_: Exception) {}
            }
            // SHA-256 and MD5 of kX
            try {
                val sha256 = MessageDigest.getInstance("SHA-256").digest(keyHex.toByteArray(Charsets.UTF_8))
                keyAttempts.add(Pair("SHA-256(kX-utf8).16", sha256.copyOf(16)))
                keyAttempts.add(Pair("SHA-256(kX-utf8).32", sha256))
            } catch (_: Exception) {}
            try {
                keyAttempts.add(Pair("MD5(kX-utf8)", MessageDigest.getInstance("MD5").digest(keyHex.toByteArray(Charsets.UTF_8))))
            } catch (_: Exception) {}
            // SHA-256 of kX hex bytes
            try {
                val kxHexBytes = hexToBytes(keyHex)
                val sha256hex = MessageDigest.getInstance("SHA-256").digest(kxHexBytes)
                keyAttempts.add(Pair("SHA-256(kX-hexBytes).16", sha256hex.copyOf(16)))
                keyAttempts.add(Pair("SHA-256(kX-hexBytes).32", sha256hex))
            } catch (_: Exception) {}

            // Source 2: Derived keys from token (the correct approach per original plugin!)
            if (token != null && token.length > 10) {
                keyAttempts.addAll(deriveKeyFromToken(token))
            }

            // Source 3: Also try deriving from sUb (the URL slug token)
            if (token != null && token.length > 10) {
                // The sUb is the base64 token used in the URL - derive key from it too
                try {
                    val sha256 = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                    keyAttempts.add(Pair("SHA-256(token-utf8).16", sha256.copyOf(16)))
                    keyAttempts.add(Pair("SHA-256(token-utf8).32", sha256))
                } catch (_: Exception) {}
            }

            println("[NguonC] kX length=${keyHex.length}, token=${if (token != null) token.length else "null"}, key attempts: ${keyAttempts.size}")

            for ((desc, keyBytes) in keyAttempts) {
                try {
                    if (keyBytes.size != 16 && keyBytes.size != 24 && keyBytes.size != 32) continue
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val spec = GCMParameterSpec(128, iv)
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
                    val decrypted = cipher.doFinal(encryptedData)
                    val result = String(decrypted, Charsets.UTF_8)
                    if (result.contains("#EXTM3U")) {
                        println("[NguonC] Decryption OK using $desc")
                        return result
                    }
                } catch (_: Exception) {
                    // Try next method
                }
            }
            println("[NguonC] All ${keyAttempts.size} key methods failed for kX=${keyHex.take(20)}...")
            null
        } catch (e: Exception) {
            println("[NguonC] Decryption FAILED: ${e.message}")
            null
        }
    }

    /** Use WebView to intercept the .m3u9/.m3u8 XHR request.
     *  When JWPlayer uses a blob URL, the JS (player1.js) makes an XHR to TOKEN.m3u9.
     *  We intercept this request via WebViewResolver to get the m3u8 content. */
    private suspend fun captureViaWebView(
        embedUrl: String, embedDomain: String
    ): Pair<String, String>? {
        // Regex to match the streamc.xyz m3u9/m3u8 URL
        val m3u8Interceptor = WebViewResolver(
            Regex(""".*streamc\.xyz.*\.(m3u8|m3u9)""")
        )
        try {
            println("[NguonC] Trying WebView interception for: $embedUrl")
            val resp = app.get(embedUrl, interceptor = m3u8Interceptor, headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to USER_AGENT
            ))
            val content = resp.text
            val capturedUrl = resp.url ?: ""

            if (content.isNotEmpty() && (content.contains("#EXTM3U") || content.contains("#ENC-AESGCM") || content.contains("m3u8"))) {
                println("[NguonC] WebView captured response: length=${content.length}, starts=${content.take(40)}")
                return Pair(content, capturedUrl)
            } else {
                println("[NguonC] WebView response doesn't look like m3u8: ${content.take(80)}")
            }
        } catch (e: Exception) {
            println("[NguonC] WebView interception failed: ${e.message}")
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Main link loading logic
    // ═══════════════════════════════════════════════════════════════════════════

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
                            targetUrl = "https://embed15.streamc.xyz/embed.php?hash=$hash"
                        } else return@async
                    }

                    val embedDomain: String = Regex("""(https?://embed\d+\.streamc\.xyz)""").find(targetUrl)?.groupValues?.getOrNull(1)
                        ?: Regex("""(https?://[^/]+)""").find(targetUrl)?.groupValues?.getOrNull(1)
                        ?: "https://embed15.streamc.xyz"

                    // ══════════════════════════════════════════════════════════════
                    // Case 1: streamc.xyz embed page
                    // ══════════════════════════════════════════════════════════════
                    if (targetUrl.contains("streamc.xyz") && targetUrl.contains("embed.php") && targetUrl.contains("hash=")) {
                        try {
                            val embedRes = app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf(
                                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                            ))
                            val html = embedRes.text
                            println("[NguonC] Embed page HTML length=${html.length}")

                            // ── Step 1: Try to find token in HTML ──────────────
                            val (tokenFromHTML, hadBlobUrl) = findTokenInHTML(html)
                            var token = tokenFromHTML

                            // ── Step 2: Try data-obf attribute ─────────────────
                            if (token == null) {
                                token = parseStreamcObf(html)
                            }

                            // ── Step 3: Try external JS files ──────────────────
                            if (token == null) {
                                println("[NguonC] Token not in HTML/data-obf, trying JS files...")
                                token = findTokenInJS(html, embedDomain, targetUrl)
                            }

                            // ── Step 4: Direct fetch approach (if token found) ─
                            if (token != null && !hadBlobUrl) {
                                println("[NguonC] Using token: ${token!!.take(20)}... (length=${token!!.length})")
                                val originValue: String = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: ""
                                val m3u8Headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to targetUrl,
                                    "Origin" to originValue,
                                    "Accept" to "*/*"
                                )

                                // Try .m3u9 FIRST (returns plain m3u8), then .m3u8 (may be encrypted)
                                for (ext in listOf(".m3u9", ".m3u8")) {
                                    val m3u8Url = "$embedDomain/${token}$ext"
                                    println("[NguonC] Trying direct fetch: $m3u8Url")
                                    try {
                                        val m3u8Resp = app.get(m3u8Url, headers = m3u8Headers, interceptor = cfInterceptor)
                                        val m3u8Content = m3u8Resp.text
                                        println("[NguonC] Response length=${m3u8Content.length}, starts: ${m3u8Content.take(60)}")

                                        // Check if response is HTML error page (not m3u8 at all)
                                        if (m3u8Content.contains("<!DOCTYPE") || m3u8Content.contains("<html") || m3u8Content.contains("<HTML")) {
                                            println("[NguonC] Got HTML error page instead of m3u8 (token may be expired)")
                                            continue
                                        }

                                        if (m3u8Content.contains("#EXTM3U") && !m3u8Content.contains("#ENC-AESGCM")) {
                                            // Plain m3u8!
                                            println("[NguonC] Plain m3u8 found with $ext")
                                            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
                                            if (registerM3U8Link(m3u8Content, targetUrl, m3u8Base, serverName, callback)) {
                                                linkFound = true
                                                break
                                            }
                                        } else if (m3u8Content.contains("#ENC-AESGCM")) {
                                            // Encrypted m3u8 - need decryption key
                                            println("[NguonC] Encrypted m3u8 with $ext, searching for key...")
                                            val kX = findEncryptionKey(html, embedDomain, targetUrl)
                                            if (kX != null) {
                                                val decrypted = decryptStreamcM3u8(m3u8Content, kX, token)
                                                if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                    println("[NguonC] Decrypted OK")
                                                    if (registerM3U8Link(decrypted, targetUrl, "", serverName, callback)) {
                                                        linkFound = true
                                                        break
                                                    }
                                                }
                                            } else {
                                                println("[NguonC] No encryption key found")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        println("[NguonC] Error fetching $ext: ${e.message}")
                                        continue
                                    }
                                }
                            }

                            // ── Step 5: WebView interception fallback ─────────
                            //    Used when: token not found, blob URL detected,
                            //    or direct fetch approach failed
                            if (!linkFound) {
                                println("[NguonC] Direct approach failed or blob URL detected, trying WebView interception...")
                                val captured = captureViaWebView(targetUrl, embedDomain)
                                if (captured != null) {
                                    val (m3u8Content, capturedUrl) = captured

                                    if (m3u8Content.contains("#EXTM3U") && !m3u8Content.contains("#ENC-AESGCM")) {
                                        // Plain m3u8 from WebView
                                        println("[NguonC] WebView captured plain m3u8")
                                        val m3u8Base = if (capturedUrl.isNotEmpty()) capturedUrl.substringBeforeLast("/") + "/" else ""
                                        if (registerM3U8Link(m3u8Content, targetUrl, m3u8Base, serverName, callback)) {
                                            linkFound = true
                                        }
                                    } else if (m3u8Content.contains("#ENC-AESGCM")) {
                                        // Encrypted m3u8 from WebView
                                        println("[NguonC] WebView captured encrypted m3u8, searching for key...")
                                        val kX = findEncryptionKey(html, embedDomain, targetUrl)
                                        if (kX != null) {
                                            val decrypted = decryptStreamcM3u8(m3u8Content, kX, token)
                                            if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                println("[NguonC] Decrypted WebView content OK")
                                                if (registerM3U8Link(decrypted, targetUrl, "", serverName, callback)) {
                                                    linkFound = true
                                                }
                                            }
                                        } else {
                                            println("[NguonC] No encryption key found for WebView content")
                                        }
                                    }
                                }
                            }

                            // ── Step 6: Last resort - try token with blob URL ──
                            //    Even if blob URL was detected, the token might still work
                            if (!linkFound && token != null && hadBlobUrl) {
                                println("[NguonC] Last resort: trying token despite blob URL...")
                                val originValue: String = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: ""
                                val m3u8Headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to targetUrl,
                                    "Origin" to originValue,
                                    "Accept" to "*/*"
                                )
                                for (ext in listOf(".m3u9", ".m3u8")) {
                                    val m3u8Url = "$embedDomain/${token}$ext"
                                    try {
                                        val m3u8Resp = app.get(m3u8Url, headers = m3u8Headers, interceptor = cfInterceptor)
                                        val m3u8Content = m3u8Resp.text
                                        if (m3u8Content.contains("#EXTM3U") && !m3u8Content.contains("#ENC-AESGCM")) {
                                            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
                                            if (registerM3U8Link(m3u8Content, targetUrl, m3u8Base, serverName, callback)) {
                                                linkFound = true
                                                break
                                            }
                                        } else if (m3u8Content.contains("#ENC-AESGCM")) {
                                            val kX = findEncryptionKey(html, embedDomain, targetUrl)
                                            if (kX != null) {
                                                val decrypted = decryptStreamcM3u8(m3u8Content, kX, token)
                                                if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                    if (registerM3U8Link(decrypted, targetUrl, "", serverName, callback)) {
                                                        linkFound = true
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    } catch (_: Exception) { continue }
                                }
                            }

                            if (!linkFound) {
                                println("[NguonC] All methods failed for streamc.xyz embed: $targetUrl")
                            }
                        } catch (e: Exception) {
                            println("[NguonC] Error loading embed page: ${e.message}")
                            e.printStackTrace()
                        }
                        return@async
                    }

                    // ══════════════════════════════════════════════════════════════
                    // Case 2: Direct m3u8/m3u9 URL (from API m3u8 field)
                    // ══════════════════════════════════════════════════════════════
                    if (targetUrl.contains(".m3u8") || targetUrl.contains(".m3u9")) {
                        try {
                            val m3u8Resp = app.get(targetUrl, headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "$mainUrl/"
                            ), interceptor = cfInterceptor)
                            val content = m3u8Resp.text
                            val m3u8Base = targetUrl.substringBeforeLast("/") + "/"

                            if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                registerM3U8Link(content, targetUrl, m3u8Base, serverName, callback)
                                linkFound = true
                            } else if (content.contains("#ENC-AESGCM")) {
                                // Even direct URLs can be encrypted
                                println("[NguonC] Direct URL returned encrypted content, trying to find key...")
                                // Try to derive key from URL domain
                                val kX = findEncryptionKey("", embedDomain, targetUrl)
                                if (kX != null) {
                                    val decrypted = decryptStreamcM3u8(content, kX, null)
                                    if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                        registerM3U8Link(decrypted, targetUrl, "", serverName, callback)
                                        linkFound = true
                                    }
                                }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                        return@async
                    }

                    // ══════════════════════════════════════════════════════════════
                    // Case 3: Other embed pages (non-streamc)
                    // ══════════════════════════════════════════════════════════════
                    try {
                        val embedRes = app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf(
                            "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                        ))
                        val html = embedRes.text

                        // Try to find m3u8 URL in the page
                        val m3u8Patterns = listOf(
                            Regex("""https?://[^\s"'<>]+\.(?:m3u8|m3u9)[^\s"'<>]*"""),
                            Regex("""["'](https?://[^\s"']+)["']""").let { pattern ->
                                pattern.findAll(html).map { it.groupValues[1] }.firstOrNull {
                                    it.contains(".m3u8") || it.contains(".m3u9") || it.contains("streamc")
                                }
                            }?.let { Regex(Regex.escape(it)) } ?: Regex("""$^""") // never-match fallback
                        )

                        var m3u8Url: String? = null
                        var streamcToken: String? = null
                        for (pattern in m3u8Patterns) {
                            val match = pattern.find(html)?.value
                            if (!match.isNullOrEmpty() && !match.startsWith("blob:")) {
                                m3u8Url = match
                                break
                            }
                        }

                        // Also check if this is a streamc-style embed without the standard pattern
                        if (m3u8Url == null && (html.contains("streamc") || html.contains("jwplayer"))) {
                            val (tokenFromHTML3, hadBlob3) = findTokenInHTML(html)
                            streamcToken = tokenFromHTML3
                            if (streamcToken == null) streamcToken = parseStreamcObf(html)
                            if (streamcToken != null && !hadBlob3) {
                                val domain = Regex("""(https?://[^/]+)""").find(targetUrl)?.groupValues?.get(1) ?: embedDomain
                                m3u8Url = "$domain/${streamcToken}.m3u9"
                            }
                        }

                        if (!m3u8Url.isNullOrEmpty() && !m3u8Url.startsWith("blob:")) {
                            val originValue2: String = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: ""
                            val m3u8Headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to targetUrl,
                                "Origin" to originValue2,
                                "Accept" to "*/*"
                            )

                            val m3u8Resp = app.get(m3u8Url, headers = m3u8Headers, interceptor = cfInterceptor)
                            val m3u8Content = m3u8Resp.text

                            if (m3u8Content.contains("#EXTM3U") && !m3u8Content.contains("#ENC-AESGCM")) {
                                registerM3U8Link(m3u8Content, targetUrl, "", serverName, callback)
                                linkFound = true
                            } else if (m3u8Content.contains("#ENC-AESGCM")) {
                                val kX = findEncryptionKey(html, embedDomain, targetUrl)
                                if (kX != null) {
                                    val decrypted = decryptStreamcM3u8(m3u8Content, kX, streamcToken)
                                    if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                        registerM3U8Link(decrypted, targetUrl, "", serverName, callback)
                                        linkFound = true
                                    }
                                }
                            }
                        } else if (m3u8Url == null && (html.contains("streamc") || html.contains("jwplayer"))) {
                            // Last resort: try WebView interception for non-standard streamc embeds
                            println("[NguonC] Non-standard streamc embed, trying WebView...")
                            val captured = captureViaWebView(targetUrl, embedDomain)
                            if (captured != null) {
                                val (m3u8Content, capturedUrl) = captured
                                // Try to extract token from captured URL for decryption
                                val capturedToken = Regex("""/([A-Za-z0-9+/=_-]+)\.m3u9?""").find(capturedUrl)?.groupValues?.get(1)
                                val decryptToken = capturedToken ?: streamcToken
                                if (m3u8Content.contains("#EXTM3U") && !m3u8Content.contains("#ENC-AESGCM")) {
                                    registerM3U8Link(m3u8Content, targetUrl, "", serverName, callback)
                                    linkFound = true
                                } else if (m3u8Content.contains("#ENC-AESGCM")) {
                                    val kX = findEncryptionKey(html, embedDomain, targetUrl)
                                    if (kX != null) {
                                        val decrypted = decryptStreamcM3u8(m3u8Content, kX, decryptToken)
                                        if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                            registerM3U8Link(decrypted, targetUrl, "", serverName, callback)
                                            linkFound = true
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }.awaitAll()
        }
        return linkFound
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Data classes
    // ═══════════════════════════════════════════════════════════════════════════

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
