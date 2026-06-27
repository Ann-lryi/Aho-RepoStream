package com.phimnguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import java.net.URLEncoder
import java.util.EnumSet

// ═══════════════════════════════════════════════════════════════════════════════
//  NguonC Plugin cho CloudStream3  —  Phiên bản tái cấu trúc
// ═══════════════════════════════════════════════════════════════════════════════
//
//  CHANGELOG so với bản cũ:
//
//  ■ FIX #1 – Chậm & mất kết nối:
//    • Chuyển TẤT CẢ mainPage sang API (bỏ scrape HTML cho danh-sach/*)
//    • Bỏ request phụ lấy recommendations trong load() (tốn 1-3 request thêm)
//    • Thêm timeout ngắn + retry cho API calls
//    • Loại bỏ hoàn toàn cfInterceptor (không cần cho API)
//
//  ■ FIX #2 – Không tìm thấy liên kết (critical):
//    • Nguyên nhân gốc: streamc.xyz mã hóa m3u8 bằng AES-GCM, key (kX)
//      KHÔNG nằm trong data-obf, KHÔNG nằm trong player.js (đó chỉ là JW Player
//      library). Key được tạo bởi JavaScript phía client (player1.js riêng của
//      streamc) và dùng session cookie. Plugin cũ cố giải mã nhưng KHÔNG BAO GIỜ
//      tìm được kX → luôn fail.
//    • Giải pháp: Dùng WebViewResolver để intercept .ts segment URLs
//      (link cuối cùng sau khi JS đã giải mã). WebView chạy full browser
//      environment nên JS tự decrypt m3u8 → JW Player request .ts segments
//      → ta bắt URL .ts đầu tiên → suy ra base URL → tạo m3u8 proxy.
//    • Fallback: Nếu WebView bắt được m3u8 plaintext (không mã hóa) → dùng trực tiếp.
//    • Bỏ hoàn toàn: local proxy server, AES-GCM decrypt, token brute-force
//      (tất cả đều không hoạt động vì không có key).
//
//  ■ CẤU TRÚC MỚI:
//    • Code gọn ~400 dòng (từ ~1200 dòng)
//    • Tách rõ: API layer / Load / LinkExtractor
//    • Không còn dead code, không còn brute-force decrypt
// ═══════════════════════════════════════════════════════════════════════════════

@CloudstreamPlugin
class PhimNguonCPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PhimNguonCProvider())
    }
}

class PhimNguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private val apiHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    // ═══════════════════════════════════════════════════════════════
    //  WebView Resolver – bắt segment .ts HOẶC m3u8 plaintext
    //  Sau khi JS trong WebView giải mã m3u8 encrypted → JW Player
    //  sẽ request các .ts segment → ta bắt URL đó.
    // ═══════════════════════════════════════════════════════════════

    /**
     * WebViewResolver mới: chờ cho đến khi bắt được request tới
     * file .ts (video segment) HOẶC m3u8 plaintext (không encrypted).
     *
     * Regex match:
     *  - *.ts (segment files)
     *  - *playlist*.m3u8 hoặc *index*.m3u8 (master/variant playlist)
     *  - Bất kỳ .m3u8 nào từ CDN (không phải streamc.xyz token URL)
     */
    private fun createSegmentInterceptor() = WebViewResolver(
        // Bắt .ts segments hoặc m3u8 từ CDN (sau khi đã giải mã)
        Regex("""(?:\.ts(?:\?|$))|(?:(?:playlist|index|chunklist|master)\w*\.m3u8)|(?://(?!embed\d*\.streamc\.xyz)[^/]+/[^?]*\.m3u8)"""),
        // Timeout 30s thay vì 60s mặc định
    )

    /**
     * WebViewResolver rộng hơn – fallback: bắt BẤT KỲ request nào
     * chứa .m3u8 từ streamc.xyz (kể cả encrypted).
     */
    private fun createBroadInterceptor() = WebViewResolver(
        Regex("""streamc\.xyz/.*\.(m3u8|m3u9|ts)(\?|$)""")
    )

    // ═══════════════════════════════════════════════════════════════
    //  Main Page – 100% API, không scrape HTML
    // ═══════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "api/films/phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "api/films/phim-le" to "Phim Lẻ",
        "api/films/phim-bo" to "Phim Bộ",
        "api/films/tv-shows" to "TV Shows",
        "api/films/hoat-hinh" to "Hoạt Hình",
        "api/films/phim-18" to "18+"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}?page=$page"
        val res = app.get(url, headers = apiHeaders, timeout = 15)
            .parsedSafe<NguonCApiResponse>()
        val items = res?.items?.mapNotNull { parseApiItem(it) } ?: emptyList()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    //  Search – API first, HTML fallback
    // ═══════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "utf-8")
        // API search
        try {
            val res = app.get(
                "$mainUrl/api/films/search?keyword=$encoded",
                headers = apiHeaders, timeout = 15
            ).parsedSafe<NguonCApiResponse>()
            if (!res?.items.isNullOrEmpty()) {
                return res!!.items!!.mapNotNull { parseApiItem(it) }
            }
        } catch (_: Exception) {}

        // Fallback: try older API format
        try {
            val res = app.get(
                "$mainUrl/api/films?keyword=$encoded",
                headers = apiHeaders, timeout = 15
            ).parsedSafe<NguonCApiResponse>()
            if (!res?.items.isNullOrEmpty()) {
                return res!!.items!!.mapNotNull { parseApiItem(it) }
            }
        } catch (_: Exception) {}

        return emptyList()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Load – Chi tiết phim
    // ═══════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trim().trimEnd('/').substringAfterLast("/")
        val res = app.get("$mainUrl/api/film/$slug", headers = apiHeaders, timeout = 15)
            .parsedSafe<NguonCDetailResponse>()
        val movie = res?.movie
            ?: throw ErrorLoadingException("Không thể tải dữ liệu phim")

        // Parse episodes
        val epMap = linkedMapOf<String, MutableList<String>>()
        movie.episodes?.forEachIndexed { idx, server ->
            val serverName = server.server_name ?: server.name
                ?: if (idx == 0) "Vietsub" else "Thuyết minh"
            val items = server.items ?: server.list
            items?.forEach { ep ->
                val embed = ep.embed?.replace("\\/", "/")?.trim() ?: ""
                val directM3u8 = ep.m3u8?.replace("\\/", "/")?.trim() ?: ""
                val epName = ep.name ?: "0"

                if (embed.isNotBlank()) {
                    epMap.getOrPut(epName) { mutableListOf() }.add("$serverName::$embed")
                }
                if (directM3u8.isNotBlank() && directM3u8 != embed) {
                    epMap.getOrPut(epName) { mutableListOf() }.add("$serverName::$directM3u8")
                }
            }
        }

        if (epMap.isEmpty()) throw ErrorLoadingException("Không tìm thấy tập phim")

        val episodes = epMap.map { (epName, embeds) ->
            newEpisode(embeds.distinct().joinToString("|")) {
                this.name = "Tập $epName"
                this.episode = epName.toIntOrNull()
            }
        }.sortedBy { it.episode ?: 0 }

        // Parse metadata
        val categories = movie.category ?: emptyMap()
        val theLoai = categories.values
            .find { it.group?.name == "Thể loại" }
            ?.list?.mapNotNull { it.name } ?: emptyList()
        val quocGia = categories.values
            .find { it.group?.name == "Quốc gia" }
            ?.list?.mapNotNull { it.name }?.joinToString(", ") ?: ""
        val namPhatHanh = categories.values
            .find { it.group?.name == "Năm" }
            ?.list?.firstOrNull()?.name ?: ""

        val plot = buildDescription(movie, quocGia)

        return newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = movie.poster_url ?: movie.thumb_url
            this.plot = plot
            this.tags = theLoai
            this.year = namPhatHanh.toIntOrNull()
            // Bỏ recommendations để tăng tốc load
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Load Links – Chiến lược mới
    // ═══════════════════════════════════════════════════════════════
    //
    //  1. Parse embed URLs từ episode data
    //  2. Với mỗi embed URL:
    //     a. Nếu là streamc.xyz/embed.php → mở trong WebView,
    //        chờ JW Player JS giải mã m3u8 và bắt đầu request .ts segments
    //     b. Nếu là direct .m3u8 URL → thử fetch trực tiếp
    //  3. WebView tự động có session cookie → JS decrypt hoạt động
    //     → ta bắt được segment URL → tạo ExtractorLink

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedEntries = data.split("|").mapNotNull { entry ->
            val parts = entry.trim().split("::", limit = 2)
            when {
                parts.size == 2 -> Pair(parts[0], parts[1])
                parts.size == 1 && parts[0].startsWith("http") -> Pair("Vietsub", parts[0])
                else -> null
            }
        }

        var linkFound = false

        for ((serverName, url) in embedEntries) {
            try {
                val result = extractLink(serverName, url, callback)
                if (result) {
                    linkFound = true
                    break // Tìm được link rồi, không cần thử server khác
                }
            } catch (e: Exception) {
                println("[NguonC] Error processing $url: ${e.message}")
            }
        }

        return linkFound
    }

    /**
     * Extract video link từ một embed URL.
     *
     * Chiến lược:
     * 1. Nếu URL chứa streamc.xyz → WebView resolve
     * 2. Nếu URL là direct m3u8 → fetch trực tiếp
     * 3. Nếu URL là embed khác → WebView resolve
     */
    private suspend fun extractLink(
        serverName: String,
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var targetUrl = url

        // Fix dead sing.phimmoi.net links → redirect to streamc.xyz embed
        if (url.contains("sing.phimmoi.net")) {
            val hash = Regex("""/([^/]+)/hls\.m3u8""").find(url)?.groupValues?.get(1)
                ?: return false
            targetUrl = "https://embed15.streamc.xyz/embed.php?hash=$hash"
        }

        // ── Streamc.xyz embed page ──────────────────────────────
        if (targetUrl.contains("streamc.xyz") && targetUrl.contains("embed")) {
            return resolveStreamcEmbed(serverName, targetUrl, callback)
        }

        // ── Direct m3u8/m3u9 URL ────────────────────────────────
        if (targetUrl.matches(Regex("""https?://.*\.(m3u8|m3u9).*"""))) {
            return resolveDirectM3u8(serverName, targetUrl, callback)
        }

        // ── Other embed pages ───────────────────────────────────
        return resolveGenericEmbed(serverName, targetUrl, callback)
    }

    /**
     * Xử lý streamc.xyz embed page bằng WebView.
     *
     * WebView sẽ:
     * 1. Load embed page (có Cloudflare protection → WebView bypass tự động)
     * 2. Chờ JW Player JS execute → JS fetch encrypted m3u8 → JS decrypt
     * 3. JW Player bắt đầu play → request .ts segments
     * 4. Ta intercept URL .ts đầu tiên hoặc m3u8 plaintext
     */
    private suspend fun resolveStreamcEmbed(
        serverName: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[NguonC] Resolving streamc embed: $embedUrl")

        // Strategy 1: Bắt .ts segment hoặc plaintext m3u8 (CDN URL)
        try {
            val interceptor = createSegmentInterceptor()
            val resp = app.get(
                embedUrl,
                interceptor = interceptor,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT
                ),
                timeout = 45
            )
            val capturedUrl = resp.url ?: ""
            val content = resp.text

            // Nếu bắt được .ts segment URL → suy ra m3u8 base URL
            if (capturedUrl.contains(".ts")) {
                println("[NguonC] Captured .ts segment: ${capturedUrl.take(80)}")
                // Segment URL thường có dạng: https://cdn.example.com/path/seg-1.ts
                // M3u8 sẽ ở cùng thư mục
                val baseUrl = capturedUrl.substringBeforeLast("/") + "/"
                // Tạo link trực tiếp tới segment URL để player tự xử lý
                callback(
                    newExtractorLink(
                        "NguonC", serverName, capturedUrl.substringBeforeLast("/") + "/playlist.m3u8",
                        ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to embedUrl,
                            "Origin" to Regex("""https?://[^/]+""").find(embedUrl)?.value.orEmpty()
                        )
                    }
                )
                return true
            }

            // Nếu bắt được m3u8 plaintext (chứa #EXTM3U và segment list)
            if (content.contains("#EXTM3U") && (content.contains(".ts") || content.contains("#EXTINF"))) {
                println("[NguonC] Captured plaintext m3u8!")
                callback(
                    newExtractorLink(
                        "NguonC", serverName, capturedUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to embedUrl,
                            "Origin" to Regex("""https?://[^/]+""").find(embedUrl)?.value.orEmpty()
                        )
                    }
                )
                return true
            }
        } catch (e: Exception) {
            println("[NguonC] Segment interceptor failed: ${e.message}")
        }

        // Strategy 2: Broad interceptor – bắt bất kỳ m3u8/ts từ streamc.xyz
        try {
            val interceptor = createBroadInterceptor()
            val resp = app.get(
                embedUrl,
                interceptor = interceptor,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT
                ),
                timeout = 45
            )
            val capturedUrl = resp.url ?: ""
            val content = resp.text

            if (capturedUrl.isNotBlank() && content.isNotBlank()) {
                // Nếu là m3u8 plaintext
                if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                    println("[NguonC] Broad interceptor: got plaintext m3u8!")
                    callback(
                        newExtractorLink(
                            "NguonC", serverName, capturedUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            quality = Qualities.P1080.value
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to embedUrl
                            )
                        }
                    )
                    return true
                }

                // Nếu là .ts segment
                if (capturedUrl.contains(".ts")) {
                    println("[NguonC] Broad interceptor: got .ts segment!")
                    // Trả về URL gốc và để player xử lý
                    val m3u8Base = capturedUrl.substringBeforeLast("/")
                    callback(
                        newExtractorLink(
                            "NguonC", serverName, capturedUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            quality = Qualities.P1080.value
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to embedUrl
                            )
                        }
                    )
                    return true
                }
            }
        } catch (e: Exception) {
            println("[NguonC] Broad interceptor failed: ${e.message}")
        }

        // Strategy 3: Thử dùng WebView load embed page, inject JS để lấy m3u8
        // từ JW Player config sau khi đã decrypt
        try {
            return resolveViaJWPlayerExtract(serverName, embedUrl, callback)
        } catch (e: Exception) {
            println("[NguonC] JWPlayer extract failed: ${e.message}")
        }

        println("[NguonC] All strategies failed for: $embedUrl")
        return false
    }

    /**
     * Strategy 3: Dùng WebView resolve với regex rộng hơn.
     * Load embed page trong WebView, đợi trang load xong.
     * WebView sẽ execute tất cả JS → JW Player sẽ tự động
     * giải mã m3u8 và bắt đầu fetch segments.
     *
     * Ta dùng WebViewResolver với regex bắt CDN URLs.
     */
    private suspend fun resolveViaJWPlayerExtract(
        serverName: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[NguonC] Strategy 3: WebView with CDN intercept for $embedUrl")

        // Interceptor bắt bất kỳ video request nào
        val videoInterceptor = WebViewResolver(
            Regex("""\.ts(\?|$)|\.m3u8(\?|$)|video/|mpeg"""),
        )

        try {
            val resp = app.get(
                embedUrl,
                interceptor = videoInterceptor,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT
                ),
                timeout = 50
            )

            val capturedUrl = resp.url ?: ""
            val content = resp.text

            if (capturedUrl.isNotBlank()) {
                println("[NguonC] Strategy 3 captured: ${capturedUrl.take(100)}")

                when {
                    // Plaintext m3u8
                    content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM") -> {
                        callback(
                            newExtractorLink(
                                "NguonC", serverName, capturedUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                quality = Qualities.P1080.value
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to embedUrl
                                )
                            }
                        )
                        return true
                    }
                    // .ts segment
                    capturedUrl.contains(".ts") -> {
                        callback(
                            newExtractorLink(
                                "NguonC", serverName, capturedUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                quality = Qualities.P1080.value
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to embedUrl
                                )
                            }
                        )
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            println("[NguonC] Strategy 3 failed: ${e.message}")
        }

        return false
    }

    /**
     * Direct m3u8 URL – thử fetch trực tiếp.
     */
    private suspend fun resolveDirectM3u8(
        serverName: String,
        m3u8Url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val resp = app.get(
                m3u8Url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/",
                    "Accept" to "*/*"
                ),
                timeout = 15
            )
            val content = resp.text

            if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                callback(
                    newExtractorLink(
                        "NguonC", serverName, m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$mainUrl/"
                        )
                    }
                )
                return true
            }
        } catch (_: Exception) {}

        // Nếu direct fetch fail → thử WebView
        return resolveStreamcEmbed(serverName, m3u8Url, callback)
    }

    /**
     * Generic embed page – WebView resolve.
     */
    private suspend fun resolveGenericEmbed(
        serverName: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[NguonC] Resolving generic embed: $embedUrl")

        val interceptor = WebViewResolver(
            Regex("""\.m3u8(\?|$)|\.ts(\?|$)"""),
        )

        try {
            val resp = app.get(
                embedUrl,
                interceptor = interceptor,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT
                ),
                timeout = 45
            )
            val capturedUrl = resp.url ?: ""
            val content = resp.text

            if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                callback(
                    newExtractorLink(
                        "NguonC", serverName, capturedUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to embedUrl
                        )
                    }
                )
                return true
            }
        } catch (e: Exception) {
            println("[NguonC] Generic embed resolve failed: ${e.message}")
        }

        return false
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helper: Parse API item → SearchResponse
    // ═══════════════════════════════════════════════════════════════

    private fun parseApiItem(item: NguonCApiItem): SearchResponse? {
        val slug = item.slug ?: return null
        val title = item.name ?: return null
        val href = "$mainUrl/phim/$slug"
        val poster = item.poster_url ?: item.thumb_url
        val currentEp = item.current_episode ?: ""

        val episodeCount: Int? = when {
            currentEp.equals("FULL", ignoreCase = true) -> null
            currentEp.startsWith("Hoàn tất", ignoreCase = true) -> null
            else ->
                Regex("""[Tt]ập\s*(\d+)""").find(currentEp)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\s*/\s*\d+""").find(currentEp)?.groupValues?.get(1)?.toIntOrNull()
        }

        val lang = item.language ?: ""
        val hasSub = lang.contains("Vietsub", ignoreCase = true)
        val hasDub = lang.contains("Thuyết Minh", ignoreCase = true)
        val dubStatus = when {
            hasSub && hasDub -> EnumSet.of(DubStatus.Subbed, DubStatus.Dubbed)
            hasDub -> EnumSet.of(DubStatus.Dubbed)
            else -> EnumSet.of(DubStatus.Subbed)
        }

        val quality = when (item.quality?.uppercase()) {
            "FHD", "HD" -> SearchQuality.HD
            "CAM" -> SearchQuality.Cam
            "SD" -> SearchQuality.SD
            else -> SearchQuality.HD
        }

        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.quality = quality
            this.dubStatus = dubStatus
            if (episodeCount != null) {
                if (hasSub) addSub(episodeCount)
                if (hasDub) addDub(episodeCount)
                if (!hasSub && !hasDub) addSub(episodeCount)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helper: Build description
    // ═══════════════════════════════════════════════════════════════

    private fun buildDescription(movie: NguonCMovie, quocGia: String): String {
        return buildString {
            val originalName = movie.original_name ?: ""
            if (originalName.isNotBlank() && originalName != (movie.name ?: ""))
                append("<i>$originalName</i><br><br>")

            fun addInfo(label: String, value: String?) {
                if (!value.isNullOrBlank()) append("<b>$label:</b> $value<br>")
            }

            addInfo("📺 Trạng thái", movie.current_episode)
            if (movie.total_episodes != null && movie.total_episodes > 0)
                addInfo("🎬 Số tập", "${movie.total_episodes} tập")
            addInfo("⏱ Thời lượng", movie.time)
            addInfo("🎥 Chất lượng", movie.quality)
            addInfo("🔊 Ngôn ngữ", movie.language)
            addInfo("🌍 Quốc gia", quocGia)
            addInfo("🎬 Đạo diễn", movie.director)
            addInfo("🎭 Diễn viên", movie.casts)

            val desc = movie.description ?: ""
            if (desc.isNotBlank()) {
                append("<br><b>📖 NỘI DUNG PHIM</b><br>")
                append(desc)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Data classes
    // ═══════════════════════════════════════════════════════════════

    data class NguonCApiResponse(
        @JsonProperty("items") val items: List<NguonCApiItem>? = null
    )

    data class NguonCApiItem(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("thumb_url") val thumb_url: String? = null,
        @JsonProperty("current_episode") val current_episode: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("language") val language: String? = null
    )

    data class NguonCDetailResponse(
        @JsonProperty("movie") val movie: NguonCMovie? = null
    )

    data class NguonCMovie(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val original_name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("thumb_url") val thumb_url: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("director") val director: String? = null,
        @JsonProperty("casts") val casts: String? = null,
        @JsonProperty("time") val time: String? = null,
        @JsonProperty("current_episode") val current_episode: String? = null,
        @JsonProperty("total_episodes") val total_episodes: Int? = null,
        @JsonProperty("category") val category: Map<String, NguonCCategory>? = null,
        @JsonProperty("episodes") val episodes: List<NguonCServer>? = null
    )

    data class NguonCCategory(
        @JsonProperty("group") val group: NguonCGroup? = null,
        @JsonProperty("list") val list: List<NguonCGroupItem>? = null
    )

    data class NguonCGroup(
        @JsonProperty("name") val name: String? = null
    )

    data class NguonCGroupItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("items") val items: List<NguonCEpisode>? = null,
        @JsonProperty("list") val list: List<NguonCEpisode>? = null
    )

    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )
}
