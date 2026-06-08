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

    // ── Local proxy server ────────────────────────────────────────────────────
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

    private fun rewriteM3U8(m3u8: String, proxyBase: String, m3u8BaseUrl: String = ""): String {
        return m3u8.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isBlank()) {
                line
            } else if (trimmed.startsWith("http")) {
                // Absolute URL - proxy it
                "$proxyBase/seg/${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                // Relative URL or segment filename - resolve against base URL
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

    // ── Streamc.xyz data-obf extraction ──────────────────────────────────────
    // Embed page HTML: <div id="player" data-obf="BASE64_JSON">
    // Decoded JSON: {"sUb":"BASE64_OF_{"h":"hash","t":"token"}","hD":"hash","kX":"aes256_key_hex"}
    //
    // sUb = base64 path for m3u8 URL (append .m3u8 or .m3u9)
    // hD  = hash (32 hex chars)
    // kX  = AES-256-GCM decryption key (32 hex chars as UTF-8 = 32 bytes = AES-256)

    private data class StreamcObfData(
        val sUb: String,   // base64 of {"h":"hash","t":"token"}
        val hD: String,    // hash
        val kX: String     // AES key in hex
    )

    private fun parseStreamcObf(html: String): StreamcObfData? {
        return try {
            // Try double-quoted attribute first, then single-quoted
            val obfBase64 = Regex("""data-obf="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""data-obf='([^']+)'""").find(html)?.groupValues?.getOrNull(1)
                ?: return null
            val obfJson = String(Base64.decode(obfBase64, Base64.DEFAULT), Charsets.UTF_8)
            println("[NguonC] data-obf decoded: ${obfJson.take(80)}...")
            val sUb = Regex(""""sUb"\s*:\s*"([^"]+)"""").find(obfJson)?.groupValues?.get(1) ?: return null
            val hD  = Regex(""""hD"\s*:\s*"([^"]+)"""").find(obfJson)?.groupValues?.get(1) ?: return null
            val kX  = Regex(""""kX"\s*:\s*"([^"]+)"""").find(obfJson)?.groupValues?.get(1) ?: return null
            StreamcObfData(sUb, hD, kX)
        } catch (e: Exception) {
            println("[NguonC] parseStreamcObf error: ${e.message}")
            null
        }
    }

    /** Search for kX (AES key) in external JS files linked from embed page */
    private suspend fun findKXInJS(html: String, embedDomain: String, referer: String): String? {
        val jsPatterns = Regex("""src=["']([^"']*(?:player1|debug|player|embed|stream)[^"']*\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.distinct().toList()

        for (jsPath in jsPatterns) {
            val jsUrl = if (jsPath.startsWith("http")) jsPath else "$embedDomain/$jsPath"
            try {
                val jsContent = app.get(jsUrl, headers = mapOf(
                    "Referer" to referer, "User-Agent" to USER_AGENT
                )).text
                // Search for kX pattern in JS
                val kxMatch = Regex(""""kX"\s*[:=]\s*"([a-f0-9]{32})"""").find(jsContent)?.groupValues?.get(1)
                if (kxMatch != null) return kxMatch
            } catch (_: Exception) {}
        }
        return null
    }

    /** Search for sUb (m3u8 URL path) in external JS files, or build from hash+token */
    private suspend fun findSUbInJS(html: String, embedDomain: String, referer: String, hash: String): String? {
        val jsPatterns = Regex("""src=["']([^"']*(?:player1|debug|player|embed|stream)[^"']*\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.distinct().toList()

        for (jsPath in jsPatterns) {
            val jsUrl = if (jsPath.startsWith("http")) jsPath else "$embedDomain/$jsPath"
            try {
                val jsContent = app.get(jsUrl, headers = mapOf(
                    "Referer" to referer, "User-Agent" to USER_AGENT
                )).text
                // Search for sUb pattern in JS
                val subMatch = Regex(""""sUb"\s*[:=]\s*"([^"]+)"""").find(jsContent)?.groupValues?.get(1)
                if (subMatch != null) return subMatch
                // Search for token to build sUb ourselves
                val tokenMatch = Regex("""["']t["']\s*:\s*["']([a-f0-9]{28,64})["']""").find(jsContent)?.groupValues?.get(1)
                if (tokenMatch != null) {
                    return buildM3u8Path(hash, tokenMatch)
                }
            } catch (_: Exception) {}
        }

        // Last resort: search for token in inline HTML script tags
        val inlineToken = Regex("""["']t["']\s*:\s*["']([a-f0-9]{28,64})["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""token\s*[:=]\s*["']([a-f0-9]{28,64})["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        if (inlineToken != null) {
            return buildM3u8Path(hash, inlineToken)
        }
        return null
    }

    /** Build m3u8 URL path from hash and token: Base64({"h":"hash","t":"token"}) */
    private fun buildM3u8Path(hash: String, token: String): String {
        val json = """{"h":"$hash","t":"$token"}"""
        return Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /** Decrypt AES-256-GCM encrypted m3u8 content from streamc.xyz
     *  Format: #EXTM3U\n#ENC-AESGCM;iv=HEX_IV\n#EXT-X-B65:...\nBASE64_ENCRYPTED_DATA
     *
     *  CRITICAL: The key kX is a 32-char hex string. The AES key is the
     *  UTF-8 bytes of that hex string (32 bytes = AES-256), NOT the hex-decoded bytes (16 bytes).
     *  Example: kX="2bb80d537b0da3e38bd30361aa85568a" → key = b"2bb80d537b0da3e38bd30361aa85568a" (32 bytes)
     */
    private fun decryptStreamcM3u8(content: String, keyHex: String): String? {
        return try {
            val ivMatch = Regex("""#ENC-AESGCM;iv=([a-f0-9A-F]+)""").find(content) ?: return null
            val ivHex = ivMatch.groupValues[1]
            if (ivHex.length != 24) return null
            val iv = hexToBytes(ivHex)

            val lines = content.lines()
            val dataLines = lines.dropWhile { it.startsWith("#") || it.isBlank() }
            val b64Data = dataLines.joinToString("").trim()
            if (b64Data.isEmpty()) return null

            val encryptedData = Base64.decode(b64Data, Base64.DEFAULT)

            // CRITICAL FIX: kX is used as UTF-8 string bytes (32 bytes = AES-256-GCM)
            // NOT hex-decoded bytes (which would be 16 bytes = AES-128-GCM)
            val keyBytes = keyHex.toByteArray(Charsets.UTF_8)

            println("[NguonC] Decrypting m3u8: keyLen=${keyBytes.size}, ivLen=${iv.size}, encLen=${encryptedData.size}")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
            val decrypted = cipher.doFinal(encryptedData)
            val result = String(decrypted, Charsets.UTF_8)
            println("[NguonC] Decryption successful, result length=${result.length}")
            result
        } catch (e: Exception) {
            println("[NguonC] Decryption FAILED: ${e.message}")
            null
        }
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
                            targetUrl = "https://embed15.streamc.xyz/embed.php?hash=$hash"
                        } else return@async
                    }

                    val embedDomain: String = Regex("""(https?://embed\d+\.streamc\.xyz)""").find(targetUrl)?.groupValues?.getOrNull(1)
                        ?: Regex("""(https?://[^/]+)""").find(targetUrl)?.groupValues?.getOrNull(1)
                        ?: "https://embed15.streamc.xyz"

                    // ── Case 1: streamc.xyz embed page ─────────────────────────────
                    if (targetUrl.contains("streamc.xyz") && targetUrl.contains("embed.php") && targetUrl.contains("hash=")) {
                        try {
                            val embedRes = app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf(
                                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                            ))
                            val html = embedRes.text
                            val hash = Regex("""hash=([a-f0-9]+)""").find(targetUrl)?.groupValues?.getOrNull(1) ?: ""

                            // Strategy 1: Parse data-obf attribute from HTML
                            var obfData = parseStreamcObf(html)

                            // Strategy 2: Search for kX in external JS files
                            if (obfData == null) {
                                println("[NguonC] data-obf not found, searching JS files for kX...")
                                val jsKX = findKXInJS(html, embedDomain, targetUrl)
                                val jsSUb = findSUbInJS(html, embedDomain, targetUrl, hash)
                                if (jsKX != null && jsSUb != null) {
                                    obfData = StreamcObfData(jsSUb, hash, jsKX)
                                    println("[NguonC] Found kX=${jsKX.take(8)}... sUb=${jsSUb.take(8)}... from JS")
                                }
                            }

                            if (obfData != null) {
                                println("[NguonC] data-obf found: sUb=${obfData.sUb.take(12)}... kX=${obfData.kX.take(8)}... hD=${obfData.hD.take(8)}...")

                                val originValue: String = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: ""
                                val m3u8Headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to targetUrl,
                                    "Origin" to originValue,
                                    "Accept" to "*/*"
                                )

                                // Try both .m3u8 and .m3u9 extensions
                                val extensions = listOf(".m3u8", ".m3u9")

                                for (ext in extensions) {
                                    val m3u8Url = "$embedDomain/${obfData.sUb}$ext"
                                    println("[NguonC] Trying m3u8 URL: $m3u8Url")
                                    try {
                                        val m3u8Resp = app.get(m3u8Url, headers = m3u8Headers, interceptor = cfInterceptor)
                                        val m3u8Content = m3u8Resp.text
                                        println("[NguonC] m3u8 response length=${m3u8Content.length}, starts with: ${m3u8Content.take(50)}")

                                        if (m3u8Content.contains("#ENC-AESGCM")) {
                                            // Encrypted m3u8 - decrypt with kX key (AES-256-GCM)
                                            val decrypted = decryptStreamcM3u8(m3u8Content, obfData.kX)

                                            if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                println("[NguonC] Decrypted m3u8 OK, length=${decrypted.length}")
                                                val server = NguonCProxyServer(targetUrl)
                                                server.start(); activeServers.add(server)
                                                val proxyBase = "http://127.0.0.1:${server.port}"
                                                server.setM3U8(rewriteM3U8(decrypted, proxyBase))
                                                callback(newExtractorLink("NguonC", serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
                                                    quality = Qualities.P1080.value
                                                    headers = mapOf("User-Agent" to USER_AGENT)
                                                })
                                                linkFound = true
                                                break
                                            } else {
                                                println("[NguonC] Decryption returned invalid content")
                                            }
                                        } else if (m3u8Content.contains("#EXTM3U")) {
                                            // Plain m3u8 (no encryption)
                                            println("[NguonC] Plain m3u8 found")
                                            val server = NguonCProxyServer(targetUrl)
                                            server.start(); activeServers.add(server)
                                            val proxyBase = "http://127.0.0.1:${server.port}"
                                            server.setM3U8(rewriteM3U8(m3u8Content, proxyBase))
                                            callback(newExtractorLink("NguonC", serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
                                                quality = Qualities.P1080.value
                                                headers = mapOf("User-Agent" to USER_AGENT)
                                            })
                                            linkFound = true
                                            break
                                        } else {
                                            println("[NguonC] m3u8 response is neither encrypted nor valid m3u8")
                                        }
                                    } catch (e: Exception) {
                                        println("[NguonC] Error fetching m3u8: ${e.message}")
                                        continue
                                    }
                                }

                                if (!linkFound) {
                                    println("[NguonC] All strategies failed for streamc.xyz embed")
                                }
                            } else {
                                println("[NguonC] Could not find data-obf or kX in embed page")
                            }
                        } catch (e: Exception) {
                            println("[NguonC] Error loading embed page: ${e.message}")
                            e.printStackTrace()
                        }
                        return@async
                    }

                    // ── Case 2: Direct m3u8 URL ──────────────────────────────────────
                    if (targetUrl.contains(".m3u8") || targetUrl.contains(".m3u9")) {
                        try {
                            val m3u8Resp = app.get(targetUrl, headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "$mainUrl/"
                            ), interceptor = cfInterceptor)
                            val content = m3u8Resp.text
                            val m3u8Base = targetUrl.substringBeforeLast("/") + "/"

                            if (content.contains("#ENC-AESGCM")) {
                                // Encrypted direct m3u8 - try to find the key
                                // For streamc.xyz direct URLs, we need the kX key
                                println("[NguonC] Direct m3u8 is encrypted, trying to find key...")
                                if (targetUrl.contains("streamc.xyz")) {
                                    // Extract sUb from URL path (the base64 part before .m3u8)
                                    val sUbMatch = Regex("""streamc\.xyz/([A-Za-z0-9_\-]+)\.m3u8?""").find(targetUrl)?.groupValues?.getOrNull(1)
                                    if (sUbMatch != null) {
                                        // Try to get kX from the embed page that contains this m3u8
                                        val embedUrl = "$embedDomain/embed.php?hash=${Regex("""/([a-f0-9]{32})/""").find(targetUrl)?.groupValues?.getOrNull(1) ?: ""}"
                                        if (embedUrl.contains("hash=")) {
                                            try {
                                                val embedHtml = app.get(embedUrl, interceptor = cfInterceptor, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)).text
                                                val obfData = parseStreamcObf(embedHtml) ?: StreamcObfData(sUbMatch, "", findKXInJS(embedHtml, embedDomain, embedUrl) ?: "")
                                                if (obfData.kX.isNotEmpty()) {
                                                    val decrypted = decryptStreamcM3u8(content, obfData.kX)
                                                    if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                        val server = NguonCProxyServer(targetUrl)
                                                        server.start(); activeServers.add(server)
                                                        val proxyBase = "http://127.0.0.1:${server.port}"
                                                        server.setM3U8(rewriteM3U8(decrypted, proxyBase, m3u8Base))
                                                        callback(newExtractorLink("NguonC", serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
                                                            quality = Qualities.P1080.value
                                                            headers = mapOf("User-Agent" to USER_AGENT)
                                                        })
                                                        linkFound = true
                                                    }
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                            } else if (content.contains("#EXTM3U")) {
                                val server = NguonCProxyServer(targetUrl)
                                server.start(); activeServers.add(server)
                                val proxyBase = "http://127.0.0.1:${server.port}"
                                server.setM3U8(rewriteM3U8(content, proxyBase, m3u8Base))
                                callback(newExtractorLink("NguonC", serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
                                    quality = Qualities.P1080.value
                                    headers = mapOf("User-Agent" to USER_AGENT)
                                })
                                linkFound = true
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                        return@async
                    }

                    // ── Case 3: Other embed pages (non-streamc) ─────────────────────
                    try {
                        val embedRes = app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf(
                            "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                        ))
                        val html = embedRes.text

                        val m3u8Url = Regex("""https?://[^\s"']+\.(?:m3u8|m3u9)[^\s"']*""").find(html)?.value
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
                                val server = NguonCProxyServer(targetUrl)
                                server.start(); activeServers.add(server)
                                val proxyBase = "http://127.0.0.1:${server.port}"
                                server.setM3U8(rewriteM3U8(m3u8Content, proxyBase))
                                callback(newExtractorLink("NguonC", serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
                                    quality = Qualities.P1080.value
                                    headers = mapOf("User-Agent" to USER_AGENT)
                                })
                                linkFound = true
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
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
