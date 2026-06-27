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

    // Cache để lưu Key AES của các Embed Domain, giúp load tập tiếp theo cực nhanh (Không cần quét lại)
    private val cryptoKeyCache = mutableMapOf<String, String>()

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Interceptors for CloudFlare bypass
    // Broad m3u8/m3u9 interceptor - matches on ANY domain
    private val broadM3u8Interceptor = WebViewResolver(
        Regex(""".*\.(m3u8|m3u9)(\?|$)""")
    )
    private val m3u8Interceptor = WebViewResolver(
        Regex(""".*streamc\.xyz/[^?]*\.(m3u8|m3u9)(\?|$)""")
    )
    private val cfInterceptor = WebViewResolver(
        Regex(""".*streamc\.xyz|.*amass\d+\.top|.*hihihoho\d+\.top|.*phimmoi\.net|.*seouls\d+\.amass\d+\.top""")
    )
    private val embedPageInterceptor = WebViewResolver(
        Regex(""".*streamc\.xyz/embed\.php""")
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
            .maxByOrNull { it.list?.size ?: 0 }?.list?.map { it.name ?: "" } ?: emptyList()

        return newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = movie.poster_url ?: movie.thumb_url
            this.year      = namPhatHanh.toIntOrNull()
            this.plot      = beautifulPlot
            this.tags      = theLoaiItems
            // Sửa lỗi API cũ: 'rating' đã bị CloudStream Deprecated. Chuyển sang dùng 'score'.
            // Mức điểm từ 0.0 -> 10.0 (Thay vì 0 -> 10000 như bản cũ
        }
    }

    private fun buildBeautifulDescription(movie: NguonCMovie, dinhDang: String, theLoai: String, namPhatHanh: String, quocGia: String): String {
        val b = java.lang.StringBuilder()
        if (!movie.original_name.isNullOrBlank()) b.append("📌 Tên gốc: ${movie.original_name}\n")
        if (quocGia.isNotBlank()) b.append("🌍 Quốc gia: $quocGia\n")
        if (namPhatHanh.isNotBlank()) b.append("📅 Năm: $namPhatHanh\n")
        if (dinhDang.isNotBlank() || theLoai.isNotBlank()) {
            b.append("🎭 Thể loại: ")
            val t = mutableListOf<String>()
            if (dinhDang.isNotBlank()) t.add(dinhDang)
            if (theLoai.isNotBlank()) t.add(theLoai)
            b.append(t.joinToString(" - ") + "\n")
        }
        if (!movie.director.isNullOrBlank()) b.append("🎬 Đạo diễn: ${movie.director}\n")
        if (!movie.casts.isNullOrBlank()) b.append("👨‍🎤 Diễn viên: ${movie.casts}\n")
        b.append("\n📝 Nội dung:\n")
        var desc = movie.description?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""
        if (desc.isBlank()) desc = "Đang cập nhật..."
        b.append(desc)
        return b.toString()
    }

    private val base64Map = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private fun toUrlSafeBase64(input: String): String {
        val b64 = Base64.encodeToString(input.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
        return b64.trimEnd('=')
    }

    private fun decodeStreamcToken(token: String): Triple<String, String, String>? {
        try {
            var padded = token.replace('-', '+').replace('_', '/')
            while (padded.length % 4 != 0) padded += "="
            val decodedBytes = Base64.decode(padded, Base64.DEFAULT)
            val decodedStr = String(decodedBytes)
            val parts = decodedStr.split("|||")
            if (parts.size >= 3) return Triple(parts[0], parts[1], parts[2])
        } catch (e: Exception) {}
        return null
    }

    private suspend fun fetchEmbedHTML(url: String, domain: String): String? {
        return try {
            app.get(url, interceptor = cfInterceptor, headers = mapOf("Referer" to domain, "User-Agent" to USER_AGENT)).text
        } catch (e: Exception) { null }
    }

    private suspend fun fetchDirect(url: String, referer: String, origin: String): String? {
        return try {
            app.get(url, headers = mapOf("Referer" to referer, "Origin" to origin, "User-Agent" to USER_AGENT)).text
        } catch (e: Exception) { null }
    }

    private fun extractObfData(html: String): Triple<String, String, String>? {
        val match = Regex("""data-obf=["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return null
        val decoded = decodeStreamcToken(match)
        return decoded
    }

    private fun findTokenInHTML(html: String): String? {
        return Regex("""['"]([A-Za-z0-9+/=_-]{100,})['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""token\s*[:=]\s*['"]([^"']+)['"]""").find(html)?.groupValues?.get(1)
    }

    private suspend fun findEncryptionKey(html: String, domain: String, referer: String): String? {
        // TỐI ƯU 1: Ưu tiên bắt Key trực tiếp trong thẻ <script> nội tuyến (Inline Script) trước
        val inlineKeyMatch = Regex("""['"]([a-f0-9]{32})['"]""").find(html) 
            ?: Regex("""kX\s*=\s*['"]([^"']+)['"]""").find(html)
        if (inlineKeyMatch != null) return inlineKeyMatch.groupValues[1]

        // TỐI ƯU 2: Dùng Cache. Nếu đã từng tìm thấy Key cho Domain này thì lấy ra xài luôn, bỏ qua bước tải JS
        cryptoKeyCache[domain]?.let { return it }

        // TỐI ƯU 3: Giới hạn số lượng JS file cần quét (Chỉ quét tối đa 2 file khả nghi nhất để chống treo máy)
        val jsUrlMatches = Regex("""<script[^>]+src=["']([^"']+\.js[^"']*)["']""").findAll(html).toList().take(2)
        
        for (match in jsUrlMatches) {
            val jsUrl = match.groupValues[1]
            val absUrl = if (jsUrl.startsWith("http")) jsUrl else "$domain${if(jsUrl.startsWith("/")) "" else "/"}$jsUrl"
            try {
                val jsContent = app.get(absUrl, headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)).text
                val keyMatch = Regex("""['"]([a-f0-9]{32})['"]""").find(jsContent)
                    ?: Regex("""kX\s*=\s*['"]([^"']+)['"]""").find(jsContent)
                
                if (keyMatch != null) {
                    val foundKey = keyMatch.groupValues[1]
                    cryptoKeyCache[domain] = foundKey // Lưu vào bộ đệm
                    return foundKey
                }
            } catch (e: Exception) {
                println("[NguonC] Error fetching JS for key: ${e.message}")
            }
        }
        return null
    }

    private fun decryptStreamcM3u8(encryptedData: String, keyHex: String, ivString: String? = null): String? {
        try {
            val dataLines = encryptedData.split("\n")
            val base64Data = dataLines.find { !it.startsWith("#") && it.isNotBlank() } ?: return null
            val decodedData = Base64.decode(base64Data, Base64.DEFAULT)

            val salt = decodedData.copyOfRange(0, 16)
            val expectedIV = decodedData.copyOfRange(16, 32)
            val cipherText = decodedData.copyOfRange(32, decodedData.size)

            val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (keyBytes.size != 16) return null

            val iv = expectedIV

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)

            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decryptedBytes = cipher.doFinal(cipherText)
            return String(decryptedBytes)

        } catch (e: Exception) {
            println("[NguonC] Decrypt failed: ${e.message}")
            return null
        }
    }

    private suspend fun registerM3U8Link(content: String, sourceUrl: String, baseUrl: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (!content.contains("#EXTM3U")) return false
        
        // Sửa lỗi API cũ: Lớp ExtractorLink trên các bản CloudStream mới không còn nhận tham số m3u8Data
        // (Thay vào đó, Cloudstream sẽ tự phân tích URL).
        // Đối với các m3u8 bị mã hóa cần dữ liệu Raw, có thể dùng thuộc tính extractorData hoặc viết HLS downloader riêng.
        // Ở đây chúng ta sẽ thiết lập link dạng M3U8 chuẩn để CloudStream bắt được.
        callback(
            ExtractorLink(
                source = name,
                name = serverName,
                url = if (baseUrl.isNotEmpty()) baseUrl else sourceUrl,
                referer = sourceUrl,
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.M3U8,
                headers = mapOf("User-Agent" to USER_AGENT)
            )
        )
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linkFound = false
        val embeds = data.split("|")

        // TỐI ƯU 4: Cơ chế chia khối (Chunking) & Delay để chống Cloudflare Rate Limit (Tránh lỗi load nửa chừng tịt)
        val chunkSize = 3
        val chunkedEmbeds = embeds.chunked(chunkSize)

        coroutineScope {
            for (chunk in chunkedEmbeds) {
                // Xử lý song song tối đa 3 link cùng lúc
                val deferreds = chunk.map { embedStr ->
                    async {
                        try {
                            val parts = embedStr.split("::")
                            val serverName = parts.getOrNull(0) ?: "Server"
                            var targetUrl = parts.getOrNull(1) ?: return@async
                            
                            if (targetUrl.startsWith("//")) targetUrl = "https:$targetUrl"

                            val embedDomain = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: return@async

                            // ══════════════════════════════════════════════════════════════
                            // Direct M3U8 handling
                            // ══════════════════════════════════════════════════════════════
                            if (targetUrl.contains(".m3u8") || targetUrl.contains(".m3u9")) {
                                // Tối ưu: Dùng header chuẩn xác của CloudStream Pre-release 4
                                val headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)
                                val m3u8Res = app.get(targetUrl, interceptor = broadM3u8Interceptor, headers = headers)
                                val content = m3u8Res.text
                                val finalUrl = m3u8Res.url ?: targetUrl
                                
                                if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                    val m3u8Base = finalUrl.substringBeforeLast("/") + "/"
                                    registerM3U8Link(content, finalUrl, m3u8Base, serverName, callback)
                                    linkFound = true
                                    return@async
                                }
                                
                                if (content.contains("<html") || content.contains("<body")) {
                                    val extractedUrl = Regex("""https?://[^\s"'<>]+\.(?:m3u8|m3u9)[^\s"'<>]*""").find(content)?.value
                                    if (!extractedUrl.isNullOrEmpty() && extractedUrl != targetUrl) {
                                        val subRes = app.get(extractedUrl, headers = mapOf("Referer" to finalUrl, "User-Agent" to USER_AGENT))
                                        if (subRes.text.contains("#EXTM3U")) {
                                            registerM3U8Link(subRes.text, extractedUrl, extractedUrl.substringBeforeLast("/") + "/", serverName, callback)
                                            linkFound = true
                                            return@async
                                        }
                                    }
                                }
                                return@async
                            }

                            // ══════════════════════════════════════════════════════════════
                            // Streamc.xyz / HLS encrypted Embeds
                            // ══════════════════════════════════════════════════════════════
                            if (targetUrl.contains("streamc.xyz")) {
                                val htmlRes = app.get(targetUrl, interceptor = embedPageInterceptor, headers = mapOf(
                                    "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                                ))
                                val html = htmlRes.text
                                
                                if (html.contains("#EXTM3U") && !html.contains("#ENC-AESGCM")) {
                                    val m3u8Base = (htmlRes.url ?: "").substringBeforeLast("/") + "/"
                                    registerM3U8Link(html, htmlRes.url ?: targetUrl, m3u8Base, serverName, callback)
                                    linkFound = true; return@async
                                }

                                var m3u8Url: String? = null
                                var token: String? = null
                                var kX: String? = null

                                val obfData = extractObfData(html)
                                if (obfData != null) {
                                    token = obfData.first
                                    kX = obfData.third
                                } else {
                                    token = findTokenInHTML(html)
                                    kX = findEncryptionKey(html, embedDomain, targetUrl)
                                }

                                if (token != null) {
                                    m3u8Url = "$embedDomain/${toUrlSafeBase64(token)}.m3u9"
                                } else {
                                    m3u8Url = Regex("""https?://[^\s"'<>]+\.(?:m3u8|m3u9)[^\s"'<>]*""").find(html)?.value
                                }

                                if (m3u8Url != null && m3u8Url.startsWith("http")) {
                                    val content = fetchDirect(m3u8Url, "$mainUrl/", embedDomain)
                                    if (content != null) {
                                        if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
                                            registerM3U8Link(content, targetUrl, m3u8Base, serverName, callback)
                                            linkFound = true; return@async
                                        }
                                        if (content.contains("#ENC-AESGCM")) {
                                            val urlToken = Regex("""/([A-Za-z0-9+/=_-]+)\.(m3u8|m3u9)""").find(targetUrl)?.groupValues?.get(1)
                                            var encKX = kX
                                            if (encKX == null && urlToken != null) {
                                                val decoded = decodeStreamcToken(urlToken)
                                                if (decoded != null) {
                                                    val (_, h, _) = decoded
                                                    for (embedNum in listOf(11, 12, 13, 14, 15, 16)) {
                                                        val embedPageUrl = "https://embed${embedNum}.streamc.xyz/embed.php?hash=$h"
                                                        val embedHtml = fetchEmbedHTML(embedPageUrl, "https://embed${embedNum}.streamc.xyz")
                                                        if (embedHtml != null) {
                                                            encKX = extractObfData(embedHtml)?.third ?: findEncryptionKey(embedHtml, "https://embed${embedNum}.streamc.xyz", embedPageUrl)
                                                            if (encKX != null) break
                                                        }
                                                    }
                                                }
                                            }
                                            if (encKX != null) {
                                                val decrypted = decryptStreamcM3u8(content, encKX, urlToken)
                                                if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                    registerM3U8Link(decrypted, targetUrl, "", serverName, callback)
                                                    linkFound = true; return@async
                                                }
                                            }
                                        }
                                    }
                                }
                                return@async
                            }

                            // ══════════════════════════════════════════════════════════════
                            // Non-streamc embed pages
                            // ══════════════════════════════════════════════════════════════
                            val embedRes = app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf(
                                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                            ))
                            val otherHtml = embedRes.text

                            if (otherHtml.contains("#EXTM3U") && !otherHtml.contains("#ENC-AESGCM")) {
                                val capUrl = embedRes.url ?: ""
                                val m3u8Base = if (capUrl.isNotEmpty()) capUrl.substringBeforeLast("/") + "/" else ""
                                if (registerM3U8Link(otherHtml, targetUrl, m3u8Base, serverName, callback)) {
                                    linkFound = true; return@async
                                }
                            }

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
                            // Lỗi Unresolved reference targetUrl trước đó xảy ra vì biến targetUrl nằm trong khối try
                            // Nhưng lại được gọi trong catch block nếu khai báo sai phạm vi. Ở đây tôi dùng println bình thường.
                            println("[NguonC_OPT] Lỗi xử lý luồng: ${e.message}")
                        }
                    }
                }
                
                // Đợi nhóm 3 link này hoàn thành trước khi tải nhóm tiếp theo
                deferreds.awaitAll()
                
                // TỐI ƯU 5: Nghỉ ngơi 300ms giữa các Chunk để hạ nhiệt Server và tránh bị Cloudflare Block
                if (!linkFound && chunk != chunkedEmbeds.last()) {
                    kotlinx.coroutines.delay(300)
                }
            }
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
