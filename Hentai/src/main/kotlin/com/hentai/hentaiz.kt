package com.hentaiz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.EnumSet

@CloudstreamPlugin
class HentaiZPlugin : Plugin() {
    override fun load() { registerMainAPI(HentaiZProvider()) }
}

class HentaiZProvider : MainAPI() {
    override var mainUrl = "https://hentaiz.ac"
    override var name = "HentaiZ"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    override val mainPage = mainPageOf(
        "browse?sort=publishedAt_desc&limit=24&animationType=TWO_D&contentRating=ALL&isTrailer=false&year=ALL" to "Mới Cập Nhật",
        "genres/hiep-dam" to "Hiếp Dâm",
        "genres/nu-sinh" to "Nữ Sinh",
        "genres/loan-luan" to "Loạn Luân",
        "genres/bu-liem" to "Bú Liếm",
        "genres/du-vu" to "Đụ Vú",
        "genres/yuri" to "Yuri"
    )

    // Bộ giải mã Unicode kép (\\u) và đơn (\u) bằng StringBuilder tốc độ cao, không bị lỗi gạch chéo ngược
    private fun unescapeJson(str: String): String {
        val cleanedStr = str.replace("\\\\u", "\\u")
        val sb = java.lang.StringBuilder()
        var i = 0
        while (i < cleanedStr.length) {
            val c = cleanedStr[i]
            if (c == '\\' && i + 1 < cleanedStr.length) {
                val next = cleanedStr[i + 1]
                if (next == 'u' && i + 5 < cleanedStr.length) {
                    val hex = cleanedStr.substring(i + 2, i + 6)
                    try {
                        val charCode = hex.toInt(16)
                        sb.append(charCode.toChar())
                        i += 6
                        continue
                    } catch (_: Exception) {}
                } else {
                    val escaped = when (next) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '\"' -> '\"'
                        '\'' -> '\''
                        '/' -> '/'
                        '\\' -> '\\'
                        else -> next
                    }
                    sb.append(escaped)
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun parseEpisodesFromHtml(html: String): List<SearchResponse> {
        val episodesBlockRegex = """episodes:\s*\[(.*?)]\s*,\s*totalCount:""".toRegex()
        val matchResult = episodesBlockRegex.find(html) ?: return emptyList()
        val episodesBlock = matchResult.groupValues[1]

        val episodeRegex = """\{id\s*:\s*"([^"]+)"\s*,\s*title\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*slug\s*:\s*"([^"]+)"\s*,\s*episodeNumber\s*:\s*(\d+)\s*,\s*duration\s*:\s*[^,]+\s*,\s*posterImage\s*:\s*(?:\{filePath\s*:\s*"([^"]+)"\}|null)""".toRegex()
        
        return episodeRegex.findAll(episodesBlock).mapNotNull { match ->
            val titleRaw = match.groupValues[2]
            val title = unescapeJson(titleRaw)
            val slug = match.groupValues[3]
            val epNum = match.groupValues[4].toIntOrNull() ?: 1
            val posterPath = match.groupValues[5].ifBlank { null }

            val detailUrl = if (posterPath != null) {
                "$mainUrl/watch/$slug?poster=${URLEncoder.encode(posterPath, "UTF-8")}"
            } else {
                "$mainUrl/watch/$slug"
            }

            val posterUrl = if (posterPath != null) {
                "https://storage.haiten.org$posterPath"
            } else {
                null
            }

            newAnimeSearchResponse(title, detailUrl, TvType.Anime) {
                this.posterUrl = posterUrl
                this.episodes = mutableMapOf(DubStatus.Subbed to epNum)
            }
        }.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            if (request.data.contains("?")) {
                "$mainUrl/${request.data}&page=$page"
            } else {
                "$mainUrl/${request.data}?page=$page"
            }
        }
        val response = app.get(url, headers = commonHeaders)
        val items = parseEpisodesFromHtml(response.text)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/browse?q=${URLEncoder.encode(query, "utf-8")}&sort=publishedAt_desc&limit=24&animationType=TWO_D&contentRating=ALL&isTrailer=false&year=ALL"
        val response = app.get(searchUrl, headers = commonHeaders)
        return parseEpisodesFromHtml(response.text)
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val html = response.text

        val episodeStartIndex = html.indexOf("episode:{")
        var episodeBlock = ""
        if (episodeStartIndex != -1) {
            val remainingHtml = html.substring(episodeStartIndex)
            val endIdx = listOf(
                remainingHtml.indexOf(",allGenres:"),
                remainingHtml.indexOf(",user:"),
                remainingHtml.indexOf("}}],user:")
            ).filter { it != -1 }.minOrNull()

            episodeBlock = if (endIdx != null) {
                remainingHtml.substring(0, endIdx)
            } else {
                remainingHtml.take(50000)
            }
        } else {
            throw ErrorLoadingException("Không thể tìm thấy thông tin tập phim")
        }

        val titleRaw = """\btitle\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex().find(episodeBlock)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy tiêu đề")
        val title = unescapeJson(titleRaw)

        val descriptionRaw = """\bdescription\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex().find(episodeBlock)?.groupValues?.get(1) ?: ""
        val description = unescapeJson(descriptionRaw)

        val embedUrl = """\bembedUrl\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex().find(episodeBlock)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy link embed")
        val releaseYear = """\breleaseYear\s*:\s*(\d+)""".toRegex().find(episodeBlock)?.groupValues?.get(1)?.toIntOrNull()

        val posterParam = url.substringAfter("?poster=", "").substringBefore("&")
        val posterPath = if (posterParam.isNotEmpty()) URLDecoder.decode(posterParam, "UTF-8") else null
        val posterUrl = if (posterPath != null) "https://storage.haiten.org$posterPath" else null

        val genreRegex = """genre:\{name:"((?:[^"\\]|\\.)*)"""".toRegex()
        val genres = genreRegex.findAll(episodeBlock).map { unescapeJson(it.groupValues[1]) }.toList()

        val beautifulPlot = buildBeautifulDescription(title, description, genres, releaseYear)

        val episodes = listOf(
            newEpisode(embedUrl) {
                this.name = title
                this.episode = 1
            }
        )

        val recommendations = try {
            parseEpisodesFromHtml(html).filter { !it.url.contains(url.substringBefore("?")) }
        } catch (_: Exception) {
            emptyList()
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = posterUrl
            this.plot = beautifulPlot
            this.tags = genres
            this.year = releaseYear
            this.recommendations = recommendations
        }
    }

    private fun buildBeautifulDescription(
        title: String,
        description: String,
        genres: List<String>,
        year: Int?
    ): String {
        return buildString {
            if (title.isNotBlank())
                append("<font color='#AAAAAA'><i>$title</i></font><br><br>")

            fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank())
                    append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
            }

            addInfo("📅", "Năm phát hành", year?.toString())
            addInfo("🏷", "Thể loại", genres.joinToString(", "))

            if (description.isNotBlank()) {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(description)
            }
        }
    }

    // ── Local proxy server giúp vượt qua kiểm tra Referer m3u8 ───────────
    private val activeServers = mutableListOf<HentaiZProxyServer>()

    inner class HentaiZProxyServer(
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
                "$proxyBase/seg/${URLEncoder.encode(trimmed, "UTF-8")}"
            } else line
        }
    }

    // Cơ chế phân tích SvelteKit URL, tích hợp chuẩn tham số x-sveltekit-invalidated=001 bắt buộc
    private fun getSvelteKitDataUrl(url: String): List<String> {
        val result = mutableListOf<String>()
        val cleanUrl = url.substringBefore("?").trimEnd('/')
        
        // Thử nghiệm hai biến thể phân dải x-sveltekit-invalidated phổ biến để đảm bảo luôn nhận được JSON thô
        result.add("$cleanUrl/__data.json?x-sveltekit-invalidated=001")
        result.add("$cleanUrl/__data.json?x-sveltekit-invalidated=100")
        
        if (url.contains("/watch/")) {
            val slug = url.substringAfter("/watch/").substringBefore("?")
            result.add("$mainUrl/watch/$slug/__data.json?x-sveltekit-invalidated=001")
            result.add("$mainUrl/watch/$slug/__data.json?x-sveltekit-invalidated=100")
        }
        
        return result.distinct()
    }

    // Quét liên kết: hỗ trợ m3u8 trực tiếp và phân rã các server CDN trong mảng phẳng SvelteKit
    private fun extractLinksFromText(text: String, embedDomain: String, videoId: String): List<String> {
        val cleanedText = text.replace("\\/", "/")
        val results = mutableListOf<String>()

        // 1. Quét tệp luồng phát có sẵn
        val fileRegex = """["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""".toRegex()
        fileRegex.findAll(cleanedText).forEach { match ->
            val path = match.groupValues[1].trim()
            val url = if (path.startsWith("http")) path else "$embedDomain/${path.trimStart('/')}"
            results.add(url)
        }

        // 2. Dự phòng sUb/hD
        val sUbRegex = """"(?:sUb|s_ub|sub)"\s*:\s*"([^"]+)"""".toRegex()
        val hDRegex = """"(?:hD|h_d|hd)"\s*:\s*"([^"]+)"""".toRegex()
        val sUbPath = sUbRegex.find(cleanedText)?.groupValues?.get(1)
        val hDPath = hDRegex.find(cleanedText)?.groupValues?.get(1)
        val chosenPath = sUbPath ?: hDPath

        if (!chosenPath.isNullOrBlank() && !chosenPath.contains(".m3u8")) {
            results.add("$embedDomain/${chosenPath.trimStart('/')}.m3u8")
        }

        // 3. Sử dụng tên miền CDN xuất hiện trong JSON (như c2.animez.top) kết hợp với ID video thô để dựng luồng phát
        if (videoId.isNotEmpty()) {
            val domainRegex = """([a-zA-Z0-9-]+\.[a-zA-Z0-9.-]+)""".toRegex()
            val domains = domainRegex.findAll(cleanedText)
                .map { it.groupValues[1] }
                .filter { dom ->
                    dom.contains(".") && 
                    !dom.contains("/") && 
                    !dom.contains("svelte") && 
                    !dom.contains("google") && 
                    !dom.contains("janitor") && 
                    !dom.contains("clammy") && 
                    !dom.contains("badland") &&
                    !dom.contains("haiten.org") &&
                    !dom.contains("mimix.cc")
                }
                .distinct()
                .toList()

            for (dom in domains) {
                val cleanDom = dom.trim().trimEnd('.')
                // Tạo toàn bộ biến thể đường dẫn HLS m3u8 có thể có trên máy chủ CDN đích
                results.add("https://$cleanDom/$videoId/playlist.m3u8")
                results.add("https://$cleanDom/$videoId/master.m3u8")
                results.add("https://$cleanDom/$videoId/index.m3u8")
                results.add("https://$cleanDom/hls/$videoId/playlist.m3u8")
                results.add("https://$cleanDom/hls/$videoId/master.m3u8")
                results.add("https://$cleanDom/hls/$videoId/index.m3u8")
            }
        }

        return results.distinct().toList()
    }

    override suspend fun loadLinks(
        data:             String, // Embed URL
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        var linkFound = false
        val embedDomain = Regex("""https?://[^/]+""").find(data)?.value ?: "https://x.haiten.org"

        // Trích xuất mã ID UUID của video phát
        val videoId = Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""", RegexOption.IGNORE_CASE)
            .find(data)?.groupValues?.get(0) ?: ""

        val urlsToTry = mutableListOf<String>()
        
        urlsToTry.addAll(getSvelteKitDataUrl(data))
        urlsToTry.add(data)

        if (videoId.isNotEmpty()) {
            urlsToTry.add("$embedDomain/api/video?id=$videoId")
            urlsToTry.add("$embedDomain/api/watch?v=$videoId")
            urlsToTry.add("$embedDomain/api/player?v=$videoId")
            urlsToTry.add("$embedDomain/api/source?v=$videoId")
            urlsToTry.add("$embedDomain/api/get-link?v=$videoId")
            urlsToTry.add("$embedDomain/api/get-stream?v=$videoId")
        }

        val svelteHeaders = mapOf(
            "Referer" to "$embedDomain/",
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "x-sveltekit-invalidated" to "1"
        )

        val fallbackHeaders = mapOf(
            "Referer" to "$embedDomain/",
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,application/json,*/*;q=0.8"
        )

        for (url in urlsToTry) {
            try {
                val response = try {
                    app.get(url, headers = svelteHeaders)
                } catch (_: Exception) {
                    app.get(url, headers = fallbackHeaders)
                }
                
                if (response.code != 200) continue
                
                val text = response.text
                val extractedLinks = extractLinksFromText(text, embedDomain, videoId)
                
                for (streamUrl in extractedLinks) {
                    val isM3u8 = streamUrl.contains(".m3u8")
                    
                    if (isM3u8) {
                        val server = HentaiZProxyServer("", data)
                        server.start()
                        activeServers.add(server)

                        val m3u8Raw = app.get(streamUrl, headers = mapOf("Referer" to data, "User-Agent" to USER_AGENT)).text
                        val proxyBase = "http://127.0.0.1:${server.port}"
                        val rewrittenM3U8 = rewriteM3U8(m3u8Raw, proxyBase)
                        server.setM3U8(rewrittenM3U8)

                        callback(newExtractorLink(
                            source = name,
                            name   = "HentaiZ Server (Proxy)",
                            url    = "$proxyBase/stream.m3u8",
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        })
                    }

                    callback(newExtractorLink(
                        source = name,
                        name   = if (isM3u8) "HentaiZ Server (Direct)" else "HentaiZ MP4 Server",
                        url    = streamUrl,
                        type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to data,
                            "Origin" to embedDomain
                        )
                    })
                    linkFound = true
                }
                
                if (linkFound) break
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return linkFound
    }
}
