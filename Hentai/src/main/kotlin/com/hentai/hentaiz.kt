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

    // Hàm giải mã Unicode chuẩn xác (nhập xuất ký tự HTML \u003C thành <, \u003E thành >)
    private fun unescapeJson(str: String): String {
        var result = str
        val unicodeRegex = "\\\\u([0-9a-fA-F]{4})".toRegex()
        result = unicodeRegex.replace(result) { match ->
            val charCode = match.groupValues[1].toInt(16)
            charCode.toChar().toString()
        }
        return result
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    private fun parseEpisodesFromHtml(html: String): List<SearchResponse> {
        val episodesBlockRegex = """episodes:\s*\[(.*?)]\s*,\s*totalCount:""".toRegex()
        val matchResult = episodesBlockRegex.find(html) ?: return emptyList()
        val episodesBlock = matchResult.groupValues[1]

        val episodeRegex = """\{id\s*:\s*"([^"]+)"\s*,\s*title\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"\s*,\s*slug\s*:\s*"([^"]+)"\s*,\s*episodeNumber\s*:\s*(\d+)\s*,\s*duration\s*:\s*[^,]+\s*,\s*posterImage\s*:\s*(?:\{filePath\s*:\s*"([^"]+)"\}|null)""".toRegex()
        
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

        val titleRaw = """\btitle\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex().find(episodeBlock)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy tiêu đề")
        val title = unescapeJson(titleRaw)

        val descriptionRaw = """\bdescription\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex().find(episodeBlock)?.groupValues?.get(1) ?: ""
        val description = unescapeJson(descriptionRaw)

        val embedUrl = """\bembedUrl\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex().find(episodeBlock)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy link embed")
        val releaseYear = """\breleaseYear\s*:\s*(\d+)""".toRegex().find(episodeBlock)?.groupValues?.get(1)?.toIntOrNull()

        val posterParam = url.substringAfter("?poster=", "").substringBefore("&")
        val posterPath = if (posterParam.isNotEmpty()) URLDecoder.decode(posterParam, "UTF-8") else null
        val posterUrl = if (posterPath != null) "https://storage.haiten.org$posterPath" else null

        val genreRegex = """genre:\{name:"([^"]+)"""".toRegex()
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

    // Cơ chế trích xuất liên kết linh hoạt, tự động vá lỗi link tương đối
    private fun extractLinksFromText(text: String, embedDomain: String): List<String> {
        val cleanedText = text.replace("\\/", "/")
        val fileRegex = """["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""".toRegex()
        
        return fileRegex.findAll(cleanedText).map { match ->
            val path = match.groupValues[1].trim()
            if (path.startsWith("http")) {
                path
            } else {
                "$embedDomain/${path.trimStart('/')}"
            }
        }.distinct().toList()
    }

    data class StreamData(
        @JsonProperty("sUb") val sUb: String? = null,
        @JsonProperty("hD")  val hD:  String? = null
    )

    override suspend fun loadLinks(
        data:             String, // Embed URL: https://x.haiten.org/watch?v=...
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        var linkFound = false
        val embedDomain = Regex("""https?://[^/]+""").find(data)?.value ?: "https://x.haiten.org"

        val urlsToTry = mutableListOf<String>()
        
        // 1. SvelteKit __data.json theo cấu trúc chuẩn
        val dataUrl1 = data.replace("/watch?", "/watch/__data.json?")
        urlsToTry.add(dataUrl1)
        
        // 2. Dự phòng 1: SvelteKit __data.json dạng truy vấn đuôi
        val dataUrl2 = if (data.contains("?")) "$data&__data.json" else "$data?__data.json"
        urlsToTry.add(dataUrl2)
        
        // 3. Dự phòng 2: HTML iframe thô
        urlsToTry.add(data)

        // 4. Dự phòng 3: Các API REST nội bộ thường thấy của Bunny Player
        val videoId = Regex("""[?&]v=([^&]+)""").find(data)?.groupValues?.get(1) ?: ""
        if (videoId.isNotEmpty()) {
            urlsToTry.add("$embedDomain/api/video?id=$videoId")
            urlsToTry.add("$embedDomain/api/watch?v=$videoId")
            urlsToTry.add("$embedDomain/api/player?v=$videoId")
            urlsToTry.add("$embedDomain/api/source?v=$videoId")
            urlsToTry.add("$embedDomain/api/get-link?v=$videoId")
            urlsToTry.add("$embedDomain/api/get-stream?v=$videoId")
        }

        // Tạo headers mô phỏng trình duyệt, gửi kèm x-sveltekit-invalidated để bắt buộc trả về JSON
        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,application/json,*/*;q=0.8",
            "x-sveltekit-invalidated" to "1"
        )

        for (url in urlsToTry) {
            try {
                val response = app.get(url, headers = headers)
                if (response.code != 200) continue
                
                val text = response.text
                val extractedLinks = extractLinksFromText(text, embedDomain)
                
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
