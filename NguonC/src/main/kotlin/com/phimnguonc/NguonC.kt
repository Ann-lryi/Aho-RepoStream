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
    private val cfInterceptor = WebViewResolver(Regex("""phim\.nguonc\.com(?!/\?__cf)|.*streamc\.xyz|.*amass15\.top|.*hihihoho2\.top"""))


    private val commonHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    private val API_PREFIX = "API::"

    override val mainPage = mainPageOf(
        "${API_PREFIX}api/films/phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "${API_PREFIX}api/films/phim-le"            to "Phim Lẻ",
        "${API_PREFIX}api/films/phim-bo"            to "Phim Bộ",
        "${API_PREFIX}api/films/hoat-hinh"          to "Nhật Bản + Anime"
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
            val ep = episodeCount
            this.episodes = mutableMapOf(DubStatus.Subbed to (ep ?: 0))
                .also { map -> if (hasDub) map[DubStatus.Dubbed] = ep ?: 0 }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return if (request.data.startsWith(API_PREFIX)) {
            val path  = request.data.removePrefix(API_PREFIX)
            val url   = "$mainUrl/$path?page=$page"
            val res   = app.get(url, headers = commonHeaders, interceptor = cfInterceptor)
                           .parsedSafe<NguonCApiResponse>()
            val items = res?.items?.mapNotNull { parseApiItem(it) } ?: emptyList()
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } else {
            val url   = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
            val doc   = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
            val items = doc.select("table tbody tr").mapNotNull { parseCard(it) }
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = try {
            app.get("$mainUrl/api/films/search?keyword=${URLEncoder.encode(query, "utf-8")}",
                headers = commonHeaders, interceptor = cfInterceptor)
               .parsedSafe<NguonCApiResponse>()
        } catch (_: Exception) { null }
        return res?.items?.mapNotNull { parseApiItem(it) } ?: emptyList()
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
                val embed = ep.embed?.replace("\\/", "/") ?: ""
                if (embed.isNotBlank()) {
                    epMap.getOrPut(ep.name ?: "0") { mutableListOf() }
                        .add("$serverName::$embed")
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

        return newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = movie.poster_url ?: movie.thumb_url
            this.plot      = beautifulPlot
            this.tags      = theLoai.split(", ").filter { it.isNotBlank() }
            this.year      = namPhatHanh.toIntOrNull()
        }
    }

    private fun buildBeautifulDescription(
        movie: NguonCMovie,
        dinhDang: String,
        theLoai: String,
        namPhatHanh: String,
        quocGia: String
    ): String {
        val description = movie.description ?: ""
        val director = movie.director ?: ""
        val casts = movie.casts ?: ""
        val time = movie.time ?: ""
        val quality = movie.quality ?: ""
        val language = movie.language ?: ""
        val currentEpisode = movie.current_episode ?: ""
        val totalEpisodes = movie.total_episodes?.toString() ?: ""
        val originalName = movie.original_name ?: ""

        return """
        <html>
        <head>
        <style>
            @import url('https://fonts.googleapis.com/css2?family=Roboto:wght@300;400;500;700&display=swap');
            
            * {
                margin: 0;
                padding: 0;
                box-sizing: border-box;
            }
            
            body {
                font-family: 'Roboto', sans-serif;
                background: linear-gradient(180deg, #0d1117 0%, #161b22 100%);
                color: #e6edf3;
                line-height: 1.6;
                padding: 16px;
            }
            
            .movie-header {
                background: linear-gradient(135deg, #1a1f2e 0%, #21262d 100%);
                border-radius: 16px;
                padding: 20px;
                margin-bottom: 16px;
                border: 1px solid #30363d;
                box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
            }
            
            .movie-title {
                font-size: 24px;
                font-weight: 700;
                color: #ffffff;
                margin-bottom: 8px;
                text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
            }
            
            .original-title {
                font-size: 14px;
                color: #8b949e;
                font-style: italic;
                margin-bottom: 12px;
            }
            
            .tags-container {
                display: flex;
                flex-wrap: wrap;
                gap: 8px;
                margin-bottom: 16px;
            }
            
            .tag {
                background: linear-gradient(135deg, #238636 0%, #2ea043 100%);
                color: white;
                padding: 4px 12px;
                border-radius: 20px;
                font-size: 12px;
                font-weight: 500;
            }
            
            .tag.quality {
                background: linear-gradient(135deg, #8957e5 0%, #a371f7 100%);
            }
            
            .tag.year {
                background: linear-gradient(135deg, #1f6feb 0%, #58a6ff 100%);
            }
            
            .tag.language {
                background: linear-gradient(135deg, #d29922 0%, #e3b341 100%);
            }
            
            .rating-section {
                display: flex;
                align-items: center;
                gap: 12px;
                margin-bottom: 16px;
                padding: 12px;
                background: rgba(0, 0, 0, 0.2);
                border-radius: 12px;
            }
            
            .stars {
                color: #fbbc04;
                font-size: 18px;
                letter-spacing: 2px;
            }
            
            .rating-text {
                font-size: 14px;
                color: #8b949e;
            }
            
            .rating-score {
                color: #fbbc04;
                font-weight: 700;
                font-size: 16px;
            }
            
            .info-grid {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
                gap: 12px;
                margin-bottom: 16px;
            }
            
            .info-item {
                background: rgba(48, 54, 61, 0.6);
                padding: 12px;
                border-radius: 10px;
                border-left: 3px solid #58a6ff;
            }
            
            .info-label {
                font-size: 11px;
                color: #8b949e;
                text-transform: uppercase;
                letter-spacing: 0.5px;
                margin-bottom: 4px;
            }
            
            .info-value {
                font-size: 14px;
                color: #e6edf3;
                font-weight: 500;
            }
            
            .section-title {
                font-size: 16px;
                font-weight: 700;
                color: #f0883e;
                margin: 20px 0 12px 0;
                display: flex;
                align-items: center;
                gap: 8px;
            }
            
            .section-title::before {
                content: "◆";
                color: #f0883e;
            }
            
            .description-box {
                background: linear-gradient(135deg, #1c2128 0%, #21262d 100%);
                border-radius: 12px;
                padding: 16px;
                border: 1px solid #30363d;
                position: relative;
                overflow: hidden;
            }
            
            .description-box::before {
                content: "";
                position: absolute;
                top: 0;
                left: 0;
                right: 0;
                height: 3px;
                background: linear-gradient(90deg, #f0883e, #fb8500, #f0883e);
            }
            
            .description-text {
                font-size: 14px;
                color: #c9d1d9;
                line-height: 1.8;
                text-align: justify;
            }
            
            .meta-section {
                margin-top: 16px;
                padding: 16px;
                background: rgba(48, 54, 61, 0.4);
                border-radius: 12px;
            }
            
            .meta-row {
                display: flex;
                margin-bottom: 10px;
                align-items: flex-start;
            }
            
            .meta-label {
                min-width: 100px;
                font-size: 13px;
                color: #58a6ff;
                font-weight: 500;
            }
            
            .meta-value {
                flex: 1;
                font-size: 13px;
                color: #e6edf3;
            }
            
            .episode-badge {
                display: inline-block;
                background: linear-gradient(135deg, #da3633 0%, #f85149 100%);
                color: white;
                padding: 6px 16px;
                border-radius: 20px;
                font-size: 14px;
                font-weight: 600;
                margin-bottom: 12px;
            }
            
            .divider {
                height: 1px;
                background: linear-gradient(90deg, transparent, #30363d, transparent);
                margin: 16px 0;
            }
        </style>
        </head>
        <body>
            <div class="movie-header">
                ${if (originalName.isNotBlank()) "<div class=\"original-title\">$originalName</div>" else ""}
                
                <div class="tags-container">
                    ${if (language.isNotBlank()) "<span class=\"tag language\">$language</span>" else ""}
                    ${if (quality.isNotBlank()) "<span class=\"tag quality\">$quality</span>" else ""}
                    ${if (namPhatHanh.isNotBlank()) "<span class=\"tag year\">$namPhatHanh</span>" else ""}
                    ${if (dinhDang.isNotBlank()) "<span class=\"tag\">$dinhDang</span>" else ""}
                </div>
                
                <div class="info-grid">
                    ${if (currentEpisode.isNotBlank()) """
                    <div class="info-item">
                        <div class="info-label">📺 Trạng thái</div>
                        <div class="info-value">$currentEpisode</div>
                    </div>
                    """ else ""}
                    
                    ${if (totalEpisodes.isNotBlank() && totalEpisodes != "0") """
                    <div class="info-item">
                        <div class="info-label">🎬 Số tập</div>
                        <div class="info-value">$totalEpisodes</div>
                    </div>
                    """ else ""}
                    
                    ${if (time.isNotBlank()) """
                    <div class="info-item">
                        <div class="info-label">⏱️ Thờ lượng</div>
                        <div class="info-value">$time</div>
                    </div>
                    """ else ""}
                    
                    ${if (quocGia.isNotBlank()) """
                    <div class="info-item">
                        <div class="info-label">🌍 Quốc gia</div>
                        <div class="info-value">$quocGia</div>
                    </div>
                    """ else ""}
                </div>
                
                ${if (director.isNotBlank() || casts.isNotBlank() || theLoai.isNotBlank()) """
                <div class="meta-section">
                    ${if (director.isNotBlank()) """
                    <div class="meta-row">
                        <span class="meta-label">🎬 Đạo diễn:</span>
                        <span class="meta-value">$director</span>
                    </div>
                    """ else ""}
                    
                    ${if (casts.isNotBlank()) """
                    <div class="meta-row">
                        <span class="meta-label">🎭 Diễn viên:</span>
                        <span class="meta-value">$casts</span>
                    </div>
                    """ else ""}
                    
                    ${if (theLoai.isNotBlank()) """
                    <div class="meta-row">
                        <span class="meta-label">🏷️ Thể loại:</span>
                        <span class="meta-value">$theLoai</span>
                    </div>
                    """ else ""}
                </div>
                """ else ""}
            </div>
            
            ${if (description.isNotBlank()) """
            <div class="section-title">NỘI DUNG PHIM</div>
            <div class="description-box">
                <div class="description-text">$description</div>
            </div>
            """ else ""}
        </body>
        </html>
        """.trimIndent()
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
            embedEntries.map { (serverName, embedUrl) ->
                async {
            val embedDomain = Regex("""https?://[^/]+""").find(embedUrl)?.value ?: return@async
            try {
                val embedRes = app.get(
                    embedUrl,
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
                    "Referer"         to embedUrl,
                    "Origin"          to embedDomain,
                    "Cookie"          to cookies,
                    "Accept"          to "*/*",
                    "Accept-Language" to "vi-VN,vi;q=0.9"
                )

            suspend fun serveStream(m3u8Url: String, serverName: String) {
                try {
                    val m3u8Raw = app.get(m3u8Url, headers = fetchHdr).text
                    if (!m3u8Raw.contains("#EXTM3U")) return

                    val server = NguonCProxyServer("", embedUrl)
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
        @JsonProperty("status") val status: String?             = null,
        @JsonProperty("items")  val items:  List<NguonCApiItem>? = null
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