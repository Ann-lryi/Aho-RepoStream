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

    // WebView interceptors
    // Capture m3u8/m3u9 served by streamc.xyz after player.js executes
    private val m3u8Interceptor = WebViewResolver(
        Regex(""".*streamc\.xyz/[^?]*\.(m3u8|m3u9)(\?|$)""")
    )
    // Broad CF bypass + capture for non-streamc embeds
    private val cfInterceptor = WebViewResolver(
        Regex(""".*streamc\.xyz|.*amass\d+\.top|.*hihihoho\d+\.top|.*phimmoi\.net|.*seouls\d+\.amass\d+\.top""")
    )

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
        "danh-sach/tv-shows"                          to "Nh\u1EADt B\u1EA3n + Anime",
        "the-loai/phim-18" to "18+"
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
            // Direct HTTP - nguonc.com không có CF cho listing pages
            val doc   = app.get(url, headers = commonHeaders).document
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
        val doc = app.get(searchUrl, headers = commonHeaders).document
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
                val embed = ep.embed?.replace("\\/", "/") ?: ""
                val directM3u8 = ep.m3u8?.replace("\\/", "/") ?: ""

                if (embed.isNotBlank()) {
                    epMap.getOrPut(ep.name ?: "0") { mutableListOf() }.add("$serverName::$embed")
                }
                if (directM3u8.isNotBlank() && directM3u8 != embed) {
                    epMap.getOrPut(ep.name ?: "0") { mutableListOf() }.add("$serverName::$directM3u8")
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
            } else if (trimmed.isNotEmpty()) {
                val resolvedUrl = if (m3u8BaseUrl.isNotEmpty()) {
                    "${m3u8BaseUrl.trimEnd('/')}/$trimmed"
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

    /** Create proxy server and register m3u8 link */
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
    //  Token & key extraction helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun padBase64(s: String): String {
        val padNeeded = (4 - s.length % 4) % 4
        return s + "=".repeat(padNeeded)
    }

    /** Convert token to URL-safe base64 (no + / =) for use in URL paths */
    private fun toUrlSafeBase64(token: String): String {
        return token.replace('+', '-').replace('/', '_').trimEnd('=')
    }

    /** Decode the token (base64 JSON) -> {"h":"32hex","t":"39-40hex"} */
    private fun decodeStreamcToken(token: String): Triple<String, String, String>? {
        val normalized = token.replace('-', '+').replace('_', '/').let { padBase64(it) }
        val decoders = listOf(
            Pair("URL_SAFE") { Base64.decode(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) },
            Pair("DEFAULT")  { Base64.decode(normalized, Base64.DEFAULT) }
        )
        for ((method, decoder) in decoders) {
            try {
                val decoded = String(decoder(), Charsets.UTF_8)
                val h = Regex(""""h"\s*:\s*"([a-fA-F0-9]+)"""").find(decoded)?.groupValues?.get(1)
                val t = Regex(""""t"\s*:\s*"([a-fA-F0-9]+)"""").find(decoded)?.groupValues?.get(1)
                if (h != null && t != null) {
                    println("[NguonC] Decoded token ($method): h=${h.length}hex, t=${t.length}hex")
                    return Triple(decoded, h, t)
                }
            } catch (_: Exception) { continue }
        }
        return null
    }

    /** Extract sUb (token), hD (hash), kX (encryption key) from data-obf attribute
     *  Tries both standard and URL-safe base64 decoding */
    private fun extractObfData(html: String): Triple<String?, String?, String?>? {
        val obfBase64 = Regex("""data-obf="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""data-obf='([^']+)'""").find(html)?.groupValues?.getOrNull(1)
            ?: return null

        // Try multiple base64 decode methods
        val decodeMethods = listOf<Pair<String, () -> ByteArray>>(
            "DEFAULT" to { Base64.decode(obfBase64, Base64.DEFAULT) },
            "URL_SAFE" to { Base64.decode(obfBase64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) },
            "NORMALIZED" to {
                val normalized = padBase64(obfBase64.replace('-', '+').replace('_', '/'))
                Base64.decode(normalized, Base64.DEFAULT)
            }
        )

        for ((methodName, decoder) in decodeMethods) {
            try {
                val obfJson = String(decoder(), Charsets.UTF_8)
                println("[NguonC] data-obf decoded ($methodName): ${obfJson.take(200)}")

                // Try multiple field name patterns (site may use different names)
                val tokenPatterns = listOf("sUb", "sub", "token", "tkn", "sU", "Su")
                val hashPatterns = listOf("hD", "hd", "hash", "hsh")
                val keyPatterns = listOf("kX", "kx", "key", "aesKey", "encKey", "kE", "Ke")

                var sUb: String? = null
                var hD: String? = null
                var kX: String? = null

                for (p in tokenPatterns) {
                    val match = Regex(""""${Regex.escape(p)}"\s*:\s*"([^"]+)"""").find(obfJson)
                    if (match != null) { sUb = match.groupValues[1]; break }
                }
                for (p in hashPatterns) {
                    val match = Regex(""""${Regex.escape(p)}"\s*:\s*"([^"]+)"""").find(obfJson)
                    if (match != null) { hD = match.groupValues[1]; break }
                }
                for (p in keyPatterns) {
                    val match = Regex(""""${Regex.escape(p)}"\s*:\s*"([^"]+)"""").find(obfJson)
                    if (match != null) { kX = match.groupValues[1]; break }
                }

                if (sUb != null || hD != null || kX != null) {
                    println("[NguonC] data-obf ($methodName): sUb=${sUb?.take(20)}... hD=${hD?.take(20)}... kX=${kX?.take(20)}...")
                    return Triple(sUb, hD, kX)
                }

                // If no named fields found, try to extract any JSON value that looks like a token
                if (sUb == null && hD == null && kX == null) {
                    // Look for any base64-like string that could be a token
                    val allValues = Regex(""""(\w+)"\s*:\s*"([A-Za-z0-9_+/=-]{20,})"""").findAll(obfJson).toList()
                    for (match in allValues) {
                        val key = match.groupValues[1]
                        val value = match.groupValues[2]
                        println("[NguonC] data-obf found field: $key = ${value.take(20)}...")
                        // Heuristic: if value looks like base64 JSON token, use it as sUb
                        if (value.length > 30 && (value.contains("=") || value.any { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/" })) {
                            if (sUb == null) {
                                sUb = value
                                println("[NguonC] data-obf heuristic: using $key as sUb")
                            }
                        }
                    }
                    if (sUb != null) return Triple(sUb, hD, kX)
                }
            } catch (e: Exception) {
                println("[NguonC] data-obf decode error ($methodName): ${e.message}")
            }
        }
        return null
    }

    /** Find token in HTML - returns token string (without .m3u8/.m3u9 extension) or null */
    private fun findTokenInHTML(html: String): String? {
        val patterns = listOf(
            // JWPlayer file config with token
            Regex(""""file"\s*:\s*"([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)""""),
            Regex("""file\s*[:=]\s*["']([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)["']"""),
            // Any quoted string ending in .m3u8/.m3u9
            Regex("""["']([A-Za-z0-9_+/=\\-]{20,})\.(m3u8|m3u9)["']"""),
            // Variable assignment
            Regex("""(?:var|let|const)\s+\w+\s*=\s*["']([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)["']"""),
            // URL in HTML src/href
            Regex("""(?:src|href)\s*=\s*["']([^"']*?(m3u8|m3u9))["']"""),
            // JWPlayer setup with sources array
            Regex(""""file"\s*:\s*"([^"]+\.(m3u8|m3u9))""""),
            // Any URL-like path ending in .m3u8/.m3u9
            Regex("""/([A-Za-z0-9_+/=\\-]{20,})\.(m3u8|m3u9)""")
        )
        for (pattern in patterns) {
            val matches = pattern.findAll(html)
            for (match in matches) {
                val token = match.groupValues.getOrNull(1)
                if (token != null && token.length > 10 && !token.startsWith("blob:") && !token.startsWith("http")) {
                    println("[NguonC] Found token in HTML: ${token.take(20)}... (len=${token.length}, pattern=${pattern.pattern.take(40)})")
                    return token
                }
            }
        }
        return null
    }

    /** Find kX (encryption key) from data-obf, inline scripts, or JS files */
    private suspend fun findEncryptionKey(html: String, embedDomain: String, referer: String): String? {
        // Source 1: data-obf
        val obfData = extractObfData(html)
        if (obfData != null && obfData.third != null) {
            println("[NguonC] Found kX from data-obf: ${obfData.third}")
            return obfData.third
        }

        // Source 2: Inline scripts
        val scripts = Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.toList()
        for (script in scripts) {
            val match = Regex("""(?:kX|key|aesKey|encKey)\s*[:=]\s*["']([a-fA-F0-9]{32,})["']""").find(script)
            if (match != null) {
                val key = match.groupValues[1]
                if (key.length >= 32) {
                    println("[NguonC] Found kX in inline script: ${key.take(20)}...")
                    return key
                }
            }
        }

        // Source 3: External JS files
        for (jsName in listOf("player1", "debug", "player", "config", "app")) {
            val jsMatch = Regex("""src=["']([^"']*${jsName}[^"']*\.js[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
            if (jsMatch != null) {
                val jsPath = jsMatch.groupValues[1]
                // FIX: tránh double-slash khi jsPath bắt đầu bằng "/"
                val jsUrl = when {
                    jsPath.startsWith("http") -> jsPath
                    jsPath.startsWith("/")    -> "${embedDomain.trimEnd('/')}$jsPath"
                    else                      -> "${embedDomain.trimEnd('/')}/$jsPath"
                }
                try {
                    val jsContent = app.get(jsUrl, headers = mapOf(
                        "Referer" to referer, "User-Agent" to USER_AGENT
                    )).text
                    println("[NguonC] JS $jsName.js (${jsContent.length} chars): ${jsContent.take(300)}")
                    // Try key patterns
                    for (pattern in listOf(
                        Regex("""(?:kX|key|aesKey|encKey|secretKey|decryptKey)\s*[:=]\s*["']([a-fA-F0-9]{32,})["']"""),
                        Regex("""["']([a-fA-F0-9]{64})["']"""),   // naked 64-hex (AES-256)
                        Regex("""["']([a-fA-F0-9]{32})["']"""),   // naked 32-hex (AES-128)
                        Regex("""["']([a-fA-F0-9]{32})['"]\s*,?\s*//\s*(?:key|aes|encrypt|decrypt)""", RegexOption.IGNORE_CASE)
                    )) {
                        val match = pattern.find(jsContent)
                        if (match != null) {
                            println("[NguonC] Found kX in $jsName.js via pattern: ${match.groupValues[1].take(20)}...")
                            return match.groupValues[1]
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  AES-GCM Decryption
    // ═══════════════════════════════════════════════════════════════════════════

    private fun decryptStreamcM3u8(content: String, kX: String, token: String? = null): String? {
        try {
            val ivMatch = Regex("""#ENC-AESGCM;iv=([a-f0-9A-F]+)""").find(content) ?: return null
            val ivHex = ivMatch.groupValues[1]
            val iv = hexToBytes(ivHex)
            println("[NguonC] Decrypt: iv=${ivHex.take(16)}... (${ivHex.length}hex), kX=$kX")

            // Parse B65 header - supports both #EXT-X-B65:offset-length and #EXT-X-B65:offset
            val b65Match = Regex("""#EXT-X-B65:(\d+)(?:-(\d+))?""").find(content)
            val offset = b65Match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val length = b65Match?.groupValues?.get(2)?.toIntOrNull() ?: 0

            // Extract base64 ciphertext
            val dataLines = content.lines().dropWhile { it.startsWith("#") || it.isBlank() }
            val b64Data = dataLines.joinToString("").trim()
            if (b64Data.isEmpty()) {
                println("[NguonC] Decrypt: no data after headers")
                return null
            }

            var encData = Base64.decode(b64Data, Base64.DEFAULT)
            if (offset > 0 || length > 0) {
                encData = if (length > 0) encData.copyOfRange(offset, minOf(offset + length, encData.size))
                          else if (offset > 0) encData.copyOfRange(offset, encData.size)
                          else encData
            }

            println("[NguonC] Decrypt: encData size=${encData.size}, offset=$offset, length=$length")

            // Build key candidates - ordered by likelihood
            val keyCandidates = mutableListOf<Pair<String, ByteArray>>()

            // 1. kX as UTF-8 bytes (JS: TextEncoder.encode(kX))
            val kxUtf8 = kX.toByteArray(Charsets.UTF_8)
            if (kxUtf8.size == 16 || kxUtf8.size == 32) keyCandidates.add(Pair("kX-utf8", kxUtf8))
            if (kxUtf8.size > 32) keyCandidates.add(Pair("kX-utf8-first32", kxUtf8.copyOf(32)))
            if (kxUtf8.size > 16) keyCandidates.add(Pair("kX-utf8-first16", kxUtf8.copyOf(16)))

            // 2. kX as hex-decoded bytes
            if (kX.length >= 32 && kX.length % 2 == 0 && kX.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                try {
                    val hexBytes = hexToBytes(kX)
                    if (hexBytes.size == 16 || hexBytes.size == 32) keyCandidates.add(Pair("kX-hexBytes", hexBytes))
                } catch (_: Exception) {}
            }

            // 3. Token-derived keys
            if (token != null) {
                val decoded = decodeStreamcToken(token)
                if (decoded != null) {
                    val (_, h, t) = decoded
                    if (t.length == 64) try { keyCandidates.add(Pair("t-hexBytes(AES256)", hexToBytes(t))) } catch (_: Exception) {}
                    if (h.length == 32) try { keyCandidates.add(Pair("h-hexBytes(AES128)", hexToBytes(h))) } catch (_: Exception) {}
                    if (t.length >= 32) try { keyCandidates.add(Pair("t-first16", hexToBytes(t.take(32)))) } catch (_: Exception) {}
                    if (t.length == 80) try { keyCandidates.add(Pair("t-hexBytes40-first32", hexToBytes(t.take(64)))) } catch (_: Exception) {}
                    // SHA256(raw t bytes) – pattern phổ biến khi t là nonce
                    if (t.length % 2 == 0) try {
                        val tBytes  = hexToBytes(t)
                        val sha256T = MessageDigest.getInstance("SHA-256").digest(tBytes)
                        keyCandidates.add(Pair("SHA256(t-bytes).32", sha256T))
                        keyCandidates.add(Pair("SHA256(t-bytes).16", sha256T.copyOf(16)))
                    } catch (_: Exception) {}
                    // MD5(raw t bytes)
                    if (t.length % 2 == 0) try {
                        val tBytes = hexToBytes(t)
                        val md5T   = MessageDigest.getInstance("MD5").digest(tBytes)
                        keyCandidates.add(Pair("MD5(t-bytes)", md5T))
                    } catch (_: Exception) {}
                    // SHA256(h+t string)
                    try {
                        val ht     = h + t
                        val sha256 = MessageDigest.getInstance("SHA-256").digest(ht.toByteArray(Charsets.UTF_8))
                        keyCandidates.add(Pair("SHA256(h+t-str).32", sha256))
                        keyCandidates.add(Pair("SHA256(h+t-str).16", sha256.copyOf(16)))
                    } catch (_: Exception) {}
                }
                try {
                    val sha256 = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                    keyCandidates.add(Pair("SHA256(token).16", sha256.copyOf(16)))
                    keyCandidates.add(Pair("SHA256(token).32", sha256))
                } catch (_: Exception) {}
                // Also try token itself as UTF-8 key
                val tokenUtf8 = token.toByteArray(Charsets.UTF_8)
                if (tokenUtf8.size == 16 || tokenUtf8.size == 32) keyCandidates.add(Pair("token-utf8", tokenUtf8))
                if (tokenUtf8.size > 16) keyCandidates.add(Pair("token-utf8-first16", tokenUtf8.copyOf(16)))
                if (tokenUtf8.size > 32) keyCandidates.add(Pair("token-utf8-first32", tokenUtf8.copyOf(32)))
            }

            // 4. If kX is short, try padding it
            if (kX.length in 17..31) {
                // Pad kX hex to 32 chars
                val padded = kX.padEnd(32, '0')
                try { keyCandidates.add(Pair("kX-padded-32hex", hexToBytes(padded))) } catch (_: Exception) {}
            }

            println("[NguonC] Trying ${keyCandidates.size} key candidates...")

            for ((desc, keyBytes) in keyCandidates) {
                if (keyBytes.size != 16 && keyBytes.size != 32) continue
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
                    val result = String(cipher.doFinal(encData), Charsets.UTF_8)
                    if (result.contains("#EXTM3U")) {
                        println("[NguonC] Decryption OK with $desc!")
                        return result
                    }
                } catch (_: Exception) {}
            }

            // 5. Try with different IV sizes (96 bits = 12 bytes is standard for GCM)
            // But some implementations might use different IV sizes
            if (iv.size != 12) {
                println("[NguonC] IV size is ${iv.size} bytes (expected 12), trying standard size...")
                for ((desc, keyBytes) in keyCandidates) {
                    if (keyBytes.size != 16 && keyBytes.size != 32) continue
                    try {
                        // Use first 12 bytes of IV
                        val truncatedIv = iv.copyOf(12)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, truncatedIv))
                        val result = String(cipher.doFinal(encData), Charsets.UTF_8)
                        if (result.contains("#EXTM3U")) {
                            println("[NguonC] Decryption OK with $desc (truncated IV)!")
                            return result
                        }
                    } catch (_: Exception) {}
                }
            }

            println("[NguonC] All decryption attempts failed")
        } catch (e: Exception) {
            println("[NguonC] Decryption error: ${e.message}")
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HTTP fetch helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Fetch embed page HTML: direct HTTP first, CF bypass fallback */
    private suspend fun fetchEmbedHTML(embedUrl: String, embedDomain: String): String? {
        // Method 1: Direct HTTP GET (fastest)
        try {
            val resp = app.get(embedUrl, headers = mapOf(
                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ))
            val html = resp.text
            if (html.contains("data-obf") || html.contains("jwplayer") || html.contains("player1") ||
                html.contains("streamc") || html.contains("embed") || html.contains("<video") ||
                html.contains("m3u8") || html.contains("m3u9")) {
                println("[NguonC] Direct HTML OK: ${html.length} chars")
                return html
            }
            if (html.length > 500 && html.contains("<")) {
                println("[NguonC] Direct HTML (no markers but substantial): ${html.take(200)}")
                return html
            }
            println("[NguonC] Direct HTML not embed page: ${html.take(100)}")
        } catch (e: Exception) {
            println("[NguonC] Direct HTML failed: ${e.message}")
        }

        // Method 2: CF bypass via cfInterceptor
        try {
            val resp = app.get(embedUrl, interceptor = cfInterceptor, headers = mapOf(
                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
            ))
            val html = resp.text
            if (html.contains("data-obf") || html.contains("jwplayer") || html.contains("player1") ||
                html.contains("streamc") || html.contains("embed") || html.contains("<video") ||
                html.contains("m3u8") || html.contains("m3u9") || html.length > 500) {
                println("[NguonC] cfInterceptor HTML OK: ${html.length} chars")
                return html
            }
        } catch (e: Exception) {
            println("[NguonC] cfInterceptor HTML failed: ${e.message}")
        }

        println("[NguonC] All HTML fetch methods failed for: $embedUrl")
        return null
    }

    /** Direct HTTP fetch of m3u8/m3u9 URL */
    private suspend fun fetchDirect(url: String, referer: String, origin: String): String? {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "*/*"
        )
        // Try direct fetch
        try {
            val resp = app.get(url, headers = headers)
            val content = resp.text
            if (content.contains("#EXTM3U") || content.contains("#ENC-AESGCM")) {
                println("[NguonC] Direct fetch OK: ${url.take(60)}")
                return content
            }
            println("[NguonC] Direct fetch response: ${content.take(80)}")
        } catch (_: Exception) {}
        // Try with cfInterceptor
        try {
            val resp = app.get(url, headers = headers, interceptor = cfInterceptor)
            val content = resp.text
            if (content.contains("#EXTM3U") || content.contains("#ENC-AESGCM")) {
                println("[NguonC] cfInterceptor fetch OK: ${url.take(60)}")
                return content
            }
        } catch (_: Exception) {}
        return null
    }

    /** Try fetching m3u8 with various token formats */
    private suspend fun tryFetchWithToken(
        token: String, embedDomain: String, referer: String,
        kX: String?, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Generate all possible URL variations for the token
        val urlVariations = mutableListOf<Pair<String, String>>()

        // 1. URL-safe base64 (standard for streamc.xyz)
        val urlSafeToken = toUrlSafeBase64(token)
        urlVariations.add(Pair(urlSafeToken, "urlSafe"))

        // 2. URL-encoded original token (in case + and / need encoding)
        if (token != urlSafeToken) {
            urlVariations.add(Pair(URLEncoder.encode(token, "UTF-8"), "urlEncoded"))
        }

        // 3. Original token as-is (in case it's already URL-safe)
        if (token != urlSafeToken) {
            urlVariations.add(Pair(token, "original"))
        }

        // 4. Normalized base64 (with + and / instead of - and _)
        val normalizedToken = token.replace('-', '+').replace('_', '/')
        if (normalizedToken != token && normalizedToken != urlSafeToken) {
            urlVariations.add(Pair(URLEncoder.encode(normalizedToken, "UTF-8"), "normalized"))
        }

        for ((tokenVariant, desc) in urlVariations) {
            for (ext in listOf(".m3u9", ".m3u8")) {
                val m3u8Url = "$embedDomain/$tokenVariant$ext"
                println("[NguonC] Trying $desc token: ${m3u8Url.take(80)}")
                val m3u8Content = fetchDirect(m3u8Url, referer, embedDomain)

                if (m3u8Content != null) {
                    // Plain m3u8
                    if (m3u8Content.contains("#EXTM3U") && !m3u8Content.contains("#ENC-AESGCM")) {
                        println("[NguonC] Got plain m3u8 with $desc + $ext!")
                        val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
                        if (registerM3U8Link(m3u8Content, referer, m3u8Base, serverName, callback)) {
                            return true
                        }
                    }
                    // Encrypted m3u8
                    if (m3u8Content.contains("#ENC-AESGCM")) {
                        println("[NguonC] Got encrypted m3u8 with $desc + $ext, trying decrypt...")
                        if (kX != null) {
                            val decrypted = decryptStreamcM3u8(m3u8Content, kX, tokenVariant)
                            if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                println("[NguonC] Decrypted m3u8 OK!")
                                if (registerM3U8Link(decrypted, referer, "", serverName, callback)) {
                                    return true
                                }
                            }
                        } else {
                            println("[NguonC] No kX for decryption")
                        }
                    }
                }
            }
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Main link loading logic
    // ═══════════════════════════════════════════════════════════════════════════
    //
    //  STRATEGY cho streamc.xyz:
    //  1. Fetch embed HTML → extract token (sUb) và kX từ data-obf
    //  2. Fetch player.js → tìm kX nếu hardcoded (sau khi fix double-slash bug)
    //  3. WebView intercept m3u8 (session cookie → server chấp nhận)
    //  4. Decrypt nếu kX tìm được, log chi tiết nếu không
    //  NOTE: Direct HTTP fetch luôn bị "Unauthorized" → bỏ hoàn toàn

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

                    // Extract hash from URL for fallback use

                    try {
                        // ══════════════════════════════════════════════════════════════
                        // Streamc.xyz embed URL processing
                        // STRATEGY (theo thứ tự):
                        //   1. Fetch HTML → extract token (sUb) + kX từ data-obf
                        //   2. Fetch player.js → tìm kX hardcoded (nếu có)
                        //   3. WebView intercept m3u8 (có session cookie → hoạt động)
                        //   4. Decrypt nếu có kX, hoặc log để debug
                        // NOTE: Direct fetch m3u8 luôn fail "Unauthorized" (no cookie) → SKIP
                        // ══════════════════════════════════════════════════════════════
                        if (targetUrl.contains("streamc.xyz") || targetUrl.contains("phimmoi.net")) {
                            println("[NguonC] === Processing: ${targetUrl.take(80)} ===")

                            var token: String? = null
                            var kX: String? = null

                            // ── BƯỚC 1: Fetch embed HTML → extract token + kX ──
                            val html = fetchEmbedHTML(targetUrl, embedDomain)
                            if (html != null) {
                                val obfData = extractObfData(html)
                                if (obfData != null) {
                                    token = obfData.first   // sUb
                                    kX    = obfData.third   // kX (thường null)
                                    println("[NguonC] data-obf: token=${token?.take(20)}... kX=${kX?.take(20)}... hD=${obfData.second?.take(20)}...")
                                }
                                if (token == null) token = findTokenInHTML(html)

                                // Thử lấy kX từ inline scripts nếu chưa có
                                if (kX == null) kX = findEncryptionKey(html, embedDomain, targetUrl)
                            }

                            // ── BƯỚC 2: Nếu token != null và kX != null → thử fetch m3u8 trực tiếp ──
                            // (chỉ có ý nghĩa nếu kX tìm được, vì server reject không có session cookie
                            //  nhưng đôi khi kX được dùng như auth token trong URL)
                            if (token != null && kX != null) {
                                if (tryFetchWithToken(token, embedDomain, targetUrl, kX, serverName, callback)) {
                                    linkFound = true; return@async
                                }
                            }

                            // ── BƯỚC 3: WebView intercept m3u8 (có full browser session) ──
                            // Đây là bước duy nhất hoạt động ổn định vì WebView có cookie session
                            if (!linkFound) {
                                println("[NguonC] WebView intercept m3u8...")
                                try {
                                    val resp = app.get(targetUrl, interceptor = m3u8Interceptor, headers = mapOf(
                                        "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                                    ))
                                    val content    = resp.text
                                    val capturedUrl = resp.url ?: ""

                                    if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                        println("[NguonC] WebView: plain m3u8!")
                                        val base = if (capturedUrl.isNotEmpty()) capturedUrl.substringBeforeLast("/") + "/" else ""
                                        if (registerM3U8Link(content, targetUrl, base, serverName, callback)) {
                                            linkFound = true; return@async
                                        }
                                    }

                                    if (content.contains("#ENC-AESGCM")) {
                                        println("[NguonC] WebView: encrypted m3u8, kX=$kX")
                                        // Lấy token từ URL đã capture (có thể khác token trong HTML)
                                        val capToken = Regex("""/([A-Za-z0-9+/=_-]+)\.(m3u8|m3u9)""").find(capturedUrl)?.groupValues?.get(1)

                                        // Nếu chưa có kX, thử lại một lần từ HTML
                                        val encKX = kX ?: html?.let {
                                            extractObfData(it)?.third ?: findEncryptionKey(it, embedDomain, targetUrl)
                                        }

                                        if (encKX != null) {
                                            val decrypted = decryptStreamcM3u8(content, encKX, capToken ?: token)
                                            if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                println("[NguonC] WebView: decrypt OK!")
                                                if (registerM3U8Link(decrypted, targetUrl, "", serverName, callback)) {
                                                    linkFound = true; return@async
                                                }
                                            }
                                        }

                                        // kX vẫn null → thử .m3u9 (đôi khi plain) từ token đã capture
                                        if (!linkFound && capToken != null) {
                                            val altExt = if (capturedUrl.contains(".m3u8")) ".m3u9" else ".m3u8"
                                            val altUrl = "$embedDomain/$capToken$altExt"
                                            println("[NguonC] Trying alt ext: $altUrl")
                                            val altContent = fetchDirect(altUrl, targetUrl, embedDomain)
                                            if (altContent != null && altContent.contains("#EXTM3U") && !altContent.contains("#ENC-AESGCM")) {
                                                val base = altUrl.substringBeforeLast("/") + "/"
                                                if (registerM3U8Link(altContent, targetUrl, base, serverName, callback)) {
                                                    linkFound = true; return@async
                                                }
                                            }
                                        }

                                        if (!linkFound) {
                                            println("[NguonC] DECRYPT FAILED - kX=$encKX - capToken=${capToken?.take(20)}...")
                                            println("[NguonC] HTML snippet: ${html?.take(500)}")
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("[NguonC] WebView intercept failed: ${e.message}")
                                }
                            }

                            if (!linkFound) {
                                println("[NguonC] FAILED all approaches: $targetUrl")
                            }
                            return@async
                        }

                        // ══════════════════════════════════════════════════════════════
                        // Direct m3u8/m3u9 URL (from API m3u8 field)
                        // ══════════════════════════════════════════════════════════════
                        if ((targetUrl.contains(".m3u8") || targetUrl.contains(".m3u9")) && targetUrl.contains("streamc.xyz")) {
                            println("[NguonC] Processing direct m3u8/m3u9 URL: ${targetUrl.take(80)}")
                            // Try .m3u9 first (plain), then .m3u8
                            val urlsToTry = mutableListOf(targetUrl)
                            if (targetUrl.endsWith(".m3u8")) {
                                urlsToTry.add(targetUrl.replace(".m3u8", ".m3u9"))
                            } else if (targetUrl.endsWith(".m3u9")) {
                                urlsToTry.add(targetUrl.replace(".m3u9", ".m3u8"))
                            }
                            for (m3u8Url in urlsToTry) {
                                val content = fetchDirect(m3u8Url, "$mainUrl/", embedDomain)
                                if (content != null) {
                                    if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                        val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
                                        registerM3U8Link(content, targetUrl, m3u8Base, serverName, callback)
                                        linkFound = true; break
                                    }
                                    if (content.contains("#ENC-AESGCM")) {
                                        // Decrypt nếu lấy được kX từ token URL
                                        val urlToken = Regex("""/([A-Za-z0-9+/=_-]+)\.(m3u8|m3u9)""").find(targetUrl)?.groupValues?.get(1)
                                        val encKX = urlToken?.let { findEncryptionKey("", embedDomain, targetUrl) }
                                        if (encKX != null) {
                                            val decrypted = decryptStreamcM3u8(content, encKX, urlToken)
                                            if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                registerM3U8Link(decrypted, targetUrl, "", serverName, callback)
                                                linkFound = true; break
                                            }
                                        } else {
                                            println("[NguonC] Direct m3u8 encrypted, kX=null → skip")
                                        }
                                    }
                                }
                            }
                            return@async
                        }

                        // ══════════════════════════════════════════════════════════════
                        // Non-streamc embed pages
                        // ══════════════════════════════════════════════════════════════
                        println("[NguonC] Processing other embed: ${targetUrl.take(80)}")
                        val embedRes = app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf(
                            "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                        ))
                        val otherHtml = embedRes.text

                        // Check if cfInterceptor already captured m3u8
                        if (otherHtml.contains("#EXTM3U") && !otherHtml.contains("#ENC-AESGCM")) {
                            val capUrl = embedRes.url ?: ""
                            val m3u8Base = if (capUrl.isNotEmpty()) capUrl.substringBeforeLast("/") + "/" else ""
                            if (registerM3U8Link(otherHtml, targetUrl, m3u8Base, serverName, callback)) {
                                linkFound = true; return@async
                            }
                        }

                        // Try to find m3u8 URL in HTML
                        var m3u8Url: String? = null
                        var extractedToken: String? = null
                        var extractedKX: String? = null

                        val urlMatch = Regex("""https?://[^\s"'<>]+\.(?:m3u8|m3u9)[^\s"'<>]*""").find(otherHtml)?.value
                        if (!urlMatch.isNullOrEmpty() && !urlMatch.startsWith("blob:")) {
                            m3u8Url = urlMatch
                        }

                        if (m3u8Url == null && (otherHtml.contains("streamc") || otherHtml.contains("jwplayer") || otherHtml.contains("data-obf"))) {
                            val obfData = extractObfData(otherHtml)
                            if (obfData != null) {
                                extractedToken = obfData.first
                                extractedKX = obfData.third
                            }
                            if (extractedToken == null) extractedToken = findTokenInHTML(otherHtml)
                            if (extractedToken != null) {
                                m3u8Url = "$embedDomain/${toUrlSafeBase64(extractedToken)}.m3u9"
                            }
                        }

                        if (!m3u8Url.isNullOrEmpty() && !m3u8Url.startsWith("blob:")) {
                            val originValue = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: ""
                            val m3u8Content = fetchDirect(m3u8Url, targetUrl, originValue)
                            if (m3u8Content != null) {
                                if (m3u8Content.contains("#EXTM3U") && !m3u8Content.contains("#ENC-AESGCM")) {
                                    registerM3U8Link(m3u8Content, targetUrl, "", serverName, callback)
                                    linkFound = true
                                } else if (m3u8Content.contains("#ENC-AESGCM")) {
                                    val encKey = extractedKX ?: findEncryptionKey(otherHtml, embedDomain, targetUrl)
                                    if (encKey != null) {
                                        val decrypted = decryptStreamcM3u8(m3u8Content, encKey, extractedToken)
                                        if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                            registerM3U8Link(decrypted, targetUrl, "", serverName, callback)
                                            linkFound = true
                                        }
                                    }
                                }
                            }
                        }

                    } catch (e: Exception) {
                        println("[NguonC] Error processing $targetUrl: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        return linkFound
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Data classes for API responses
    // ═══════════════════════════════════════════════════════════════════════════

    data class NguonCApiResponse(
        @JsonProperty("items") val items: List<NguonCApiItem>? = null
    )

    data class NguonCApiItem(
        @JsonProperty("slug")             val slug: String? = null,
        @JsonProperty("name")             val name: String? = null,
        @JsonProperty("poster_url")       val poster_url: String? = null,
        @JsonProperty("thumb_url")        val thumb_url: String? = null,
        @JsonProperty("current_episode")  val current_episode: String? = null,
        @JsonProperty("quality")          val quality: String? = null,
        @JsonProperty("language")         val language: String? = null
    )

    data class NguonCDetailResponse(
        @JsonProperty("movie") val movie: NguonCMovie? = null
    )

    data class NguonCMovie(
        @JsonProperty("name")             val name: String? = null,
        @JsonProperty("original_name")    val original_name: String? = null,
        @JsonProperty("slug")             val slug: String? = null,
        @JsonProperty("poster_url")       val poster_url: String? = null,
        @JsonProperty("thumb_url")        val thumb_url: String? = null,
        @JsonProperty("description")      val description: String? = null,
        @JsonProperty("quality")          val quality: String? = null,
        @JsonProperty("language")         val language: String? = null,
        @JsonProperty("director")         val director: String? = null,
        @JsonProperty("casts")            val casts: String? = null,
        @JsonProperty("time")             val time: String? = null,
        @JsonProperty("current_episode")  val current_episode: String? = null,
        @JsonProperty("total_episodes")   val total_episodes: Int? = null,
        @JsonProperty("category")         val category: Map<String, NguonCCategory>? = null,
        @JsonProperty("episodes")         val episodes: List<NguonCServer>? = null
    )

    data class NguonCCategory(
        @JsonProperty("group") val group: NguonCGroup? = null,
        @JsonProperty("list")  val list: List<NguonCGroupItem>? = null
    )

    data class NguonCGroup(
        @JsonProperty("name") val name: String? = null
    )

    data class NguonCGroupItem(
        @JsonProperty("id")   val id: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("name")        val name: String? = null,
        @JsonProperty("items")       val items: List<NguonCEpisode>? = null,
        @JsonProperty("list")        val list: List<NguonCEpisode>? = null
    )

    data class NguonCEpisode(
        @JsonProperty("name")   val name: String? = null,
        @JsonProperty("embed")  val embed: String? = null,
        @JsonProperty("m3u8")   val m3u8: String? = null
    )
}