package com.animevietsub

import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder

/**
 * AnimeVietsub plugin for CloudStream 3.
 *
 * CLOUDFLARE TURNSTILE BYPASS:
 *   animevietsub.pl is behind Cloudflare Turnstile.
 *
 *   2026-07-06: rebuilt to match a version of this same plugin the user confirmed
 *   ran successfully (provided directly, from a different chat) — replacing a
 *   fire-and-forget background-WebView approach that failed across 3 device test
 *   cycles (logs showed IP/server-error-sized stubs, a JS-redirect stub, and a
 *   genuine 888783-char Turnstile page — none ever produced a cf_clearance cookie).
 *
 *   Two concrete bugs found by diffing against the confirmed-working version:
 *   1. cfWebView below did not pass userAgent explicitly. Kotlin resolves a
 *      constructor's default parameter value in the DECLARING file's scope, not
 *      the caller's — so it defaulted to com.lagradost.cloudstream3.USER_AGENT
 *      (verified from real source, library/.../MainAPI.kt: a desktop Windows
 *      Chrome string), NOT this file's own local USER_AGENT below, even though
 *      they share a name. commonHeaders (same file scope as the constant) was
 *      NOT affected — it always used the correct local Android/Mobile constant.
 *   2. interceptUrl was "__cf_never_match__" (never matches — WebView always
 *      burns the full timeout, then a separate fireBackgroundChallengeSolver +
 *      manual cookie-sync tried to reuse the result on the NEXT request). The
 *      confirmed-working version instead matches mainUrl itself and passes
 *      cfWebView directly as `interceptor` to app.get() — WebViewResolver
 *      implements OkHttp's Interceptor (verified from real source), so the
 *      whole exchange resolves synchronously inside one call.
 *
 *   CAVEAT (unresolved): a prior comment in this file (removed here) claimed
 *   matching mainUrl in interceptUrl caused "immediate 0ms capture" before
 *   Turnstile could solve, and that this was the reason it was changed to
 *   "__cf_never_match__" in the first place. That claim could not be verified
 *   or ruled out against real WebViewResolver.android.kt source in this session.
 *   It directly contradicts the confirmed-working reference version using this
 *   exact pattern. Weighed the user's direct empirical test (worked) above an
 *   unverified past comment. If httpGet starts returning short/challenge-sized
 *   content again, this is the first thing to revisit.
 *
 * Player flow (verified from inline JS in watch.html):
 *   1. Watch page contains: _epHash, _epID, filmInfo.filmID, filmInfo.playTech
 *   2. JS calls AnimeVsub(_epHash, filmInfo.filmID) → POST /ajax/player
 *   3. Response sets PLAYER_DATA = { playTech, link }
 *   4. JWPlayer plays m3u8/mp4 sources
 */
@CloudstreamPlugin
class AnimeVietsubPlugin : Plugin() {
    override fun load() {
        // KEEP THIS SIMPLE — only register the provider.
        // Do NOT do any network/WebView work here (Context may not be ready).
        registerMainAPI(AnimeVietsubProvider())
    }
}

class AnimeVietsubProvider : MainAPI() {
    override var mainUrl = "https://animevietsub.love"
    override var name = "AnimeVietsub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    private val TAG = "AnimeVietsub"

    private val USER_AGENT =
        // Match Android System WebView shown in the user's log. The site appears
        // to bind its JS anti-bot cookies to the browser fingerprint/UA; using a
        // desktop/Chrome UA after solving in WebView causes the 798-byte JS stub
        // to be returned again.
        "Mozilla/5.0 (Linux; Android 16; PPG-AN00 Build/HONORPPG-AN00; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/138.0.7204.179 Mobile Safari/537.36"

    // ═══════════════════════════════════════════════════════════════════════
    //  Cloudflare Turnstile bypass — shared background WebView solve, awaited
    //  with a short bounded timeout so it can never trip CloudStream's own
    //  outer framework timeout (confirmed on-device: fully synchronous
    //  resolveUsingWebView() here caused "Timed out waiting for 120000 ms").
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Config matches CloudStream's own official CloudflareKiller class
     * (app/.../network/CloudflareKiller.kt — not accessible from a plugin
     * directly since it lives in the app module, not library, so replicated
     * here) rather than values I picked myself:
     *   - userAgent = null: CloudflareKiller's own comment says "Cloudflare
     *     needs default user agent" — i.e. the WebView's real one, not a
     *     custom string. Contradicts an explicit UA I set in an earlier fix;
     *     deferring to the framework author's tested value.
     *   - additionalUrls matching everything + requestCallBack checking for
     *     cf_clearance in resolveUsingWebView() below: exits AS SOON AS the
     *     cookie appears on ANY request (checked continuously), instead of
     *     blindly waiting a fixed duration and only checking at the end.
     */
    private val cfWebView: WebViewResolver by lazy {
        WebViewResolver(
            interceptUrl = Regex(""".^"""),   // never matches (CloudflareKiller's own trick)
            additionalUrls = listOf(Regex(".")),  // matches every request
            useOkhttp = false,
            userAgent = null
        )
    }

    private val mediaWebView: WebViewResolver by lazy {
        val mediaRegex = Regex(""".*(\.(m3u8|m3u|mp4)(\?.*)?$|/manifest(\?.*)?$|/playlist(\?.*)?$|/stream(\?.*)?$|/source(\?.*)?$|videoplayback)""", RegexOption.IGNORE_CASE)
        WebViewResolver(
            interceptUrl = mediaRegex,
            // Do NOT include ajax/player here. In practice additionalUrls can
            // cause WebViewResolver to return non-media side requests, which is
            // how we previously captured /cdn-cgi/speculation instead of HTML.
            additionalUrls = emptyList(),
            useOkhttp = false,
            userAgent = null
        )
    }

    private var cachedCfCookies: String? = null

    private fun hasSolvedCookies(url: String): Boolean {
        val cookies = android.webkit.CookieManager.getInstance().getCookie(url) ?: return false
        // animevietsub.love does NOT set cf_clearance. It serves a tiny JS page
        // that eventually sets BOTH:
        //   1) app/session cookies (PHPSESSID/token...)
        //   2) a dynamic 32-hex-name anti-bot cookie (e.g. a42a20...=...)
        // The latest failing log showed we returned as soon as PHPSESSID/token
        // existed, before the dynamic anti-bot cookie was present, so the retry
        // still got the 798-byte challenge page. Wait for both classes.
        val hasSession = cookies.contains("PHPSESSID") || cookies.contains("token")
        val hasDynamicAntiBot = Regex("""(?:^|;\s*)[a-f0-9]{32}=""", RegexOption.IGNORE_CASE).containsMatchIn(cookies)
        return hasSession && hasDynamicAntiBot
    }

    private fun syncCookiesFromWebView() {
        val cookies = android.webkit.CookieManager.getInstance().getCookie(mainUrl)
        if (!cookies.isNullOrBlank()) {
            cachedCfCookies = cookies
            println("[AVSB] synced cookies: $cookies")
        } else {
            println("[AVSB] no cookies found in WebView CookieManager for $mainUrl")
        }
    }

    private val commonHeaders = mapOf(
        // Match the real mobile browser seen in DevTools as closely as possible.
        // Some animevietsub.love endpoints return short/block pages for plain
        // OkHttp-looking requests, especially /ajax/player.
        "User-Agent"           to USER_AGENT,
        "Accept-Language"      to "vi-VN,vi;q=0.9,en;q=0.8",
        "Accept"               to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Site"       to "none",
        "Sec-Fetch-Mode"       to "navigate",
        "Sec-Fetch-Dest"       to "document",
        "sec-ch-ua"            to "\"Chromium\";v=\"138\", \"Android WebView\";v=\"138\", \"Not.A/Brand\";v=\"99\"",
        "sec-ch-ua-mobile"     to "?1",
        "sec-ch-ua-platform"   to "\"Android\""
    )

    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var solveJob: Deferred<Unit>? = null

    /** Ensure exactly one WebView solve is running, shared by any concurrent
     *  caller. Runs in bgScope so it survives this specific call being
     *  cancelled/timed out — the job itself is NOT awaited unboundedly by
     *  httpGet (see waitMs below), so it can never cause the "Timed out
     *  waiting for 120000 ms" CloudStream framework error seen on-device
     *  when a previous version awaited resolveUsingWebView() directly here. */
    @Synchronized
    private fun ensureSolving(): Deferred<Unit> {
        solveJob?.let { if (it.isActive) return it }
        val job = bgScope.async {
            try {
                cfWebView.resolveUsingWebView(mainUrl, referer = mainUrl) { req ->
                    hasSolvedCookies(mainUrl) || hasSolvedCookies(req.url.toString())
                }
            } catch (e: Exception) {
                println("[AVSB] WebView solve failed: ${e.message}")
            }
            syncCookiesFromWebView()
        }
        solveJob = job
        return job
    }

    /**
     * 2026-07-08: 3 lần test WiFi trước → "Failed to connect" tới các IP IPv6
     * Cloudflare (nghĩ là vấn đề IPv4/IPv6, đã thêm ipv4Dns bên dưới).
     * 2026-07-08 (log mới nhất): DNS thất bại HOÀN TOÀN — "No address
     * associated with hostname" — khác hẳn, không phải chuyện chọn IPv4/IPv6
     * nữa. Đối chiếu cùng log: InAppUpdater gọi GitHub cách đó <1s, kết nối
     * thành công (lỗi của nó là parse JSON, không phải mạng) — nên DNS/mạng
     * nói chung vẫn ổn, chỉ riêng domain này bị chặn ở DNS. Nghi ngờ có căn cứ:
     * mạng WiFi đang test chặn DNS theo domain (phổ biến với site vi phạm bản
     * quyền tại VN) — chưa xác nhận 100% (chưa test được trên mạng khác).
     * Thêm DoH (Cloudflare 1.1.1.1) làm phương án dự phòng khi DNS hệ thống
     * thất bại — né được việc DNS nội bộ mạng chặn theo domain, vì hỏi thẳng
     * resolver công khai qua HTTPS. Dùng đúng tiện ích DnsOverHttps đã có sẵn
     * trong nicehttp (xác nhận từ source thật, không phải dependency mới).
     */
    private val dohDns: Dns by lazy {
        DnsOverHttps.Builder()
            .client(OkHttpClient.Builder().build())
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            )
            .build()
    }

    private val ipv4Dns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                println("[AVSB] system DNS failed for $hostname :: ${e.message} — trying DoH (1.1.1.1)")
                val dohResult = try {
                    dohDns.lookup(hostname)
                } catch (e2: Exception) {
                    println("[AVSB] DoH also failed for $hostname :: ${e2.message}")
                    throw e2
                }
                println("[AVSB] DoH resolved $hostname -> $dohResult")
                dohResult
            }
            val v4 = all.filterIsInstance<Inet4Address>()
            return v4.ifEmpty { all }
        }
    }
    private val ipv4Client = OkHttpClient.Builder().dns(ipv4Dns).build()

    private fun mergeSetCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val jar = linkedMapOf<String, String>()
        cachedCfCookies?.split(";")
            ?.map { it.trim() }
            ?.filter { it.contains("=") }
            ?.forEach { c ->
                val k = c.substringBefore("=").trim()
                if (k.isNotBlank()) jar[k] = c
            }
        setCookies.forEach { raw ->
            val pair = raw.substringBefore(";").trim()
            val k = pair.substringBefore("=").trim()
            if (k.isNotBlank() && pair.contains("=")) jar[k] = pair
        }
        cachedCfCookies = jar.values.joinToString("; ")
        println("[AVSB] merged cookies: $cachedCfCookies")
    }

    private fun withCookies(headers: Map<String, String>): Map<String, String> =
        cachedCfCookies?.let { headers + ("Cookie" to it) } ?: headers

    private suspend fun rawGet(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            ipv4Client.newCall(request).execute().use { resp ->
                mergeSetCookies(resp.headers("Set-Cookie"))
                resp.body?.string() ?: ""
            }
        }

    private suspend fun rawPostForm(
        url: String,
        headers: Map<String, String>,
        data: Map<String, String>
    ): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply {
            data.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder().url(url).post(form).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        ipv4Client.newCall(request).execute().use { resp ->
            mergeSetCookies(resp.headers("Set-Cookie"))
            resp.body?.string() ?: ""
        }
    }

    private suspend fun webViewFetch(url: String): String? {
        return try {
            // IMPORTANT: do NOT use additionalUrls = "." here. In the latest log
            // WebView did load the real site, but WebViewResolver returned the tiny
            // /cdn-cgi/speculation response (151 chars), so Cloudstream had nothing
            // to parse. Capture ONLY the requested document URL.
            val exactUrl = Regex("^${Regex.escape(url)}$".replace("/\$", "/?\$"))
            val resolver = WebViewResolver(
                interceptUrl = exactUrl,
                additionalUrls = emptyList(),
                useOkhttp = false,
                userAgent = null
            )
            val resp = app.get(url, headers = withCookies(commonHeaders), interceptor = resolver)
            syncCookiesFromWebView()
            val html = resp.text
            println("[AVSB] WebView document fetch: $url (${html.length} chars) preview=${html.take(120).replace("\n", " ")}")
            html.ifBlank { null }
        } catch (e: Exception) {
            println("[AVSB] WebView fetch failed: $url :: ${e.message}")
            null
        }
    }

    private fun looksBlocked(html: String): Boolean {
        val lower = html.lowercase()
        return html.isBlank() ||
            lower.contains("just a moment") ||
            lower.contains("checking your browser") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("attention required") ||
            lower.contains("enable javascript") ||
            (lower.contains("cloudflare") && lower.contains("challenge"))
    }

    /** HTTP GET with Cloudflare bypass. Tries a plain request first (fast path
     *  once a cookie is cached). On a short/non-content response, waits up to
     *  waitMs for a shared background WebView solve, then retries once —
     *  win either way: fast if the solve finishes quickly, and never blocks
     *  longer than waitMs regardless of how long the solve itself takes. */
    private suspend fun httpGet(url: String): String {
        val waitMs = 10_000L
        val html1 = try {
            rawGet(url, withCookies(commonHeaders))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[AVSB] httpGet failed: $url :: ${e.message}")
            ""
        }
        if (html1.length > 2000 && !looksBlocked(html1)) {
            println("[AVSB] httpGet: $url (${html1.length} chars)")
            return html1
        }

        // Direct OkHttp got the small JS anti-bot stub. Start exactly ONE shared
        // WebView browser session to execute the JS/cookie challenge, then retry
        // this same URL with the WebView-matching UA + synced cookies.
        println("[AVSB] httpGet short/blocked response for $url (${html1.length} chars) preview=${html1.take(120).replace("\n", " ")} — waiting for shared WebView cookie solve")

        println("[AVSB] waiting up to ${waitMs}ms for shared browser solve")
        withTimeoutOrNull(waitMs) { ensureSolving().await() }
        val html2 = try {
            rawGet(url, withCookies(commonHeaders))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[AVSB] httpGet retry failed: $url :: ${e.message}")
            ""
        }
        println("[AVSB] httpGet after wait: $url (${html2.length} chars)")
        return if (html2.length >= html1.length) html2 else html1
    }

    private suspend fun httpGetDoc(url: String) =
        Jsoup.parse(httpGet(url), url)

    // ═══════════════════════════════════════════════════════════════════════
    //  Home page — 3 sections (fits OkHttp's 5-connection pool, finishes
    //  within CloudStream's 2s deadline when cookies are cached)
    // ═══════════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "/"                 to "Mới Cập Nhật",
        "/anime-bo/"        to "Anime Bộ",
        "/anime-le/"        to "Anime Lẻ"
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        val cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }

    /**
     * storage.googleapiscdn.com/player blocks top-level playback unless the
     * browser/player flow appends isFinal=1. The user's DevTools screenshots show
     * successful player/status requests using the player URL with ?isFinal=1.
     */
    private fun finalPlayerUrl(url: String): String {
        if (!url.contains("storage.googleapiscdn.com/player", ignoreCase = true)) return url
        if (url.contains("isFinal=", ignoreCase = true)) return url
        return url + (if (url.contains("?")) "&" else "?") + "isFinal=1"
    }


    /** Fetch a Document via httpGet (Cloudflare bypass through cfWebView interceptor).
     *  Returns null if the response was blank. */
    private suspend fun fetchDoc(url: String, useCf: Boolean = true): org.jsoup.nodes.Document? {
        val html = httpGet(url)
        return if (html.isBlank()) null else Jsoup.parse(html, url)
    }

    /** Fetch raw HTML text via httpGet (Cloudflare bypass through cfWebView interceptor).
     *  Returns null if the response was blank. */
    private suspend fun fetchText(url: String, useCf: Boolean = true): String? {
        val html = httpGet(url)
        return html.ifBlank { null }
    }

    /**
     * Build a beautiful HTML-formatted description (NguonC polish).
     */
    private fun buildBeautifulDescription(
        title: String,
        description: String?,
        originalName: String?,
        releaseYear: String?,
        status: String?,
        studio: String?,
        genres: List<String>,
        country: String?,
        duration: String?,
        quality: String?,
        schedule: String?,
        rating: String?,
        director: String?
    ): String {
        return buildString {
            fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank())
                    append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
            }

            if (!originalName.isNullOrBlank() && originalName != title) {
                addInfo("🌐", "Tên gốc", originalName, "#AAAAAA")
            }
            addInfo("📊", "Trạng thái", status, "#4CAF50")
            addInfo("📅", "Lịch chiếu", schedule)
            addInfo("⏱", "Thời lượng", duration)
            addInfo("💎", "Chất lượng", quality, "#03A9F4")
            addInfo("🎬", "Đạo diễn", director)
            addInfo("🎨", "Studio", studio, "#E91E63")
            addInfo("🌍", "Quốc gia", country)
            addInfo("🔞", "Rating", rating)
            if (releaseYear != null) addInfo("📆", "Năm", releaseYear)
            if (genres.isNotEmpty()) {
                addInfo("🎭", "Thể loại", genres.joinToString(", "), "#4CAF50")
            }

            if (!description.isNullOrBlank()) {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(description.trim())
            }
        }
    }

    /**
     * Parse master m3u8 for multi-quality variants.
     */
    private fun parseM3U8Variants(content: String, baseUrl: String): List<Triple<String, String, Int>> {
        if (!content.contains("#EXT-X-STREAM-INF")) return emptyList()
        val results = mutableListOf<Triple<String, String, Int>>()
        val lines = content.lines()
        var i = 0
        val baseDir = baseUrl.substringBeforeLast("/", "")
        val baseHost = "https://" + baseUrl.substringAfter("https://").substringBefore("/")
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val nextLine = lines.getOrNull(i + 1)?.trim() ?: ""
                if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val height = resMatch?.groupValues?.get(2)?.toIntOrNull()
                    val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(line)
                    val bandwidth = bwMatch?.groupValues?.get(1)?.toLongOrNull()
                    val (label, quality) = when {
                        height != null && height >= 2160 -> "4K" to Qualities.P2160.value
                        height != null && height >= 1440 -> "2K" to Qualities.P1440.value
                        height != null && height >= 1080 -> "1080p" to Qualities.P1080.value
                        height != null && height >= 720  -> "720p" to Qualities.P720.value
                        height != null && height >= 480  -> "480p" to Qualities.P480.value
                        bandwidth != null && bandwidth >= 8_000_000 -> "1080p" to Qualities.P1080.value
                        bandwidth != null && bandwidth >= 4_000_000 -> "720p" to Qualities.P720.value
                        bandwidth != null && bandwidth >= 1_500_000 -> "480p" to Qualities.P480.value
                        else -> "Auto" to Qualities.Unknown.value
                    }
                    val variantUrl = when {
                        nextLine.startsWith("http") -> nextLine
                        nextLine.startsWith("/")    -> "$baseHost$nextLine"
                        else                        -> "$baseDir/$nextLine"
                    }
                    results.add(Triple(label, variantUrl, quality))
                    i += 2
                    continue
                }
            }
            i++
        }
        return results
    }

    /**
     * Parse a .TPostMv card element (verified structure from home.html):
     *
     *   <div class="TPostMv">
     *     <div class="TPost B">
     *       <a href="https://animevietsub.pl/phim/<slug>" title="...">
     *         <div class="Image">
     *           <figure class="Objf TpMvPlay AAIco-play_arrow">
     *             <img src="https://cdn.animevietsub.pl/data/poster/..." />
     *           </figure>
     *           <span class="mli-eps">TẬP <i>12</i></span>
     *         </div>
     *         <div class="Title">Hắc Miêu Và Lớp Học Phù Thủy</div>
     *       </a>
     *     </div>
     *   </div>
     */
    private fun parseAnimeCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        if (!href.contains("/phim/")) return null

        val title = a.attr("title").ifBlank {
            el.selectFirst(".Title")?.text()
        }?.trim() ?: return null

        val poster = el.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank {
                img.attr("data-original").ifBlank { img.attr("src") }
            }
        }?.let { if (it.startsWith("http")) it else fixUrl(it) }

        // Episode count from <span class="mli-eps">TẬP <i>12</i></span>
        val epCount = el.selectFirst(".mli-eps i")?.text()?.trim()?.toIntOrNull()
        // Quality from .Qlty
        val qualityText = el.selectFirst(".Qlty")?.text()?.trim().orEmpty()
        val quality = when {
            qualityText.contains("FHD", true) || qualityText.contains("HD", true) -> SearchQuality.HD
            qualityText.contains("4K", true) -> SearchQuality.HD
            qualityText.contains("CAM", true) -> SearchQuality.Cam
            else -> null
        }

        // Type: anime-bo → series, anime-le → movie, default to anime
        val tvType = when {
            href.contains("/anime-le/") -> TvType.Movie
            href.contains("/anime-bo/") -> TvType.Anime
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            if (quality != null) this.quality = quality
            // Do not set AnimeSearchResponse.dubStatus / episodes properties directly.
            // Some Cloudstream prerelease builds do not expose those setters at runtime
            // (NoSuchMethodError: setDubStatus). Use the official helper instead.
            if (epCount != null && epCount > 0) {
                addSub(epCount)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main page
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Pagination pattern (verified from category.html):
        //   page 1 = /<section>/
        //   page N = /<section>/trang-N.html
        val sectionPath = request.data.removeSuffix("/")
        val url = when {
            sectionPath.isEmpty() && page == 1 -> "$mainUrl/"
            sectionPath.isEmpty() -> "$mainUrl/trang-$page.html"
            page == 1 -> "$mainUrl$sectionPath/"
            else -> "$mainUrl$sectionPath/trang-$page.html"
        }

        println("[AVSB] getMainPage: $url")
        val doc = fetchDoc(url) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        // .TPostMv is the verified card selector (46 matches on home.html)
        val items = doc.select(".TPostMv").mapNotNull { parseAnimeCard(it) }

        // hasNext: look for "trang-(N+1).html" link in .wp-pagenavi
        val hasNext = items.isNotEmpty() && run {
            doc.selectFirst(".wp-pagenavi a[href*='trang-${page + 1}.html']") != null ||
            doc.selectFirst("a:contains(Trang Cuối)") != null && page < 196
        }

        println("[AVSB]   items=${items.size}, hasNext=$hasNext")
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search — POST to /tim-kiem/ (verified from search.html form)
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        // Form action="tim-kiem/" method="post" with input name="keyword"
        // The search results URL becomes /tim-kiem/<query>/
        val searchUrl = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/"
        println("[AVSB] search: $searchUrl")
        val doc = fetchDoc(searchUrl) ?: return emptyList()
        val items = doc.select(".TPostMv").mapNotNull { parseAnimeCard(it) }
        println("[AVSB]   found ${items.size} results")
        return items
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Load detail
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        println("[AVSB] load: $url")
        val doc = fetchDoc(url) ?: throw ErrorLoadingException("Không tải được trang (Cloudflare?)")

        // Title (verified): h1.Title
        val title = doc.selectFirst("h1.Title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Anime"

        // Original/subtitle: h2.SubTitle
        val originalName = doc.selectFirst("h2.SubTitle")?.text()?.trim()

        // Poster: .Image img (verified)
        val poster = doc.selectFirst(".Image img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-original").ifBlank { img.attr("src") } }
        }?.let { if (it.startsWith("http")) it else fixUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        // Description: .Description (verified)
        val description = doc.selectFirst(".Description")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // Parse InfoList items (verified structure):
        //   <li><strong>Lịch chiếu:</strong> Chủ Nhật...</li>
        //   <li><strong>Trạng thái:</strong> Phim đang chiếu...</li>
        //   <li><strong>Thể loại:</strong> <a>School</a>, <a>Fantasy</a>, ...</li>
        //   <li><strong>Đạo diễn:</strong> Tatsuwa Naoyuki</li>
        //   <li><strong>Quốc gia:</strong> <a>Nhật Bản</a>, ...</li>
        //   <li><strong>Thời lượng:</strong> 12/24</li>
        //   <li><strong>Chất lượng:</strong> <span class="Qlty">FHD</span></li>
        //   <li><strong>Studio:</strong> <a>LIDENFILMS</a></li>
        //   <li><strong>Season:</strong> Mùa Xuân - 2026</li>
        var status: String? = null
        var schedule: String? = null
        var duration: String? = null
        var quality: String? = null
        var studio: String? = null
        var country: String? = null
        var director: String? = null
        var season: String? = null
        var rating: String? = null
        val genres = mutableListOf<String>()

        for (li in doc.select(".InfoList li")) {
            val strong = li.selectFirst("strong")?.text()?.trim()?.removeSuffix(":") ?: continue
            // Get text after the <strong> (the value, may include link text)
            val value = li.ownText().trim().ifBlank {
                li.text().substringAfter(strong).trim().removePrefix(":").trim()
            }
            val linkTexts = li.select("a").map { it.text().trim() }.filter { it.isNotBlank() }

            when (strong) {
                "Trạng thái"   -> status = value.ifBlank { linkTexts.joinToString(", ") }
                "Lịch chiếu"   -> schedule = value
                "Thời lượng"   -> duration = value
                "Chất lượng"   -> quality = li.selectFirst(".Qlty")?.text()?.trim() ?: value
                "Studio"       -> studio = linkTexts.joinToString(", ").ifBlank { value }
                "Quốc gia"     -> country = linkTexts.joinToString(", ").ifBlank { value }
                "Đạo diễn"     -> director = value
                "Season"       -> season = value
                "Rating"       -> rating = li.selectFirst(".imdb")?.text()?.trim() ?: value
                "Thể loại"     -> genres += linkTexts
            }
        }

        // Extract year from Season ("Mùa Xuân - 2026" → 2026)
        val releaseYear = season?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1) }

        val plot = buildBeautifulDescription(
            title = title,
            description = description,
            originalName = originalName,
            releaseYear = releaseYear,
            status = status,
            studio = studio,
            genres = genres,
            country = country,
            duration = duration,
            quality = quality,
            schedule = schedule,
            rating = rating,
            director = director
        )

        // ── Episodes ──
        // Detail page only shows the latest 3 episodes (.InfoList li.latest_eps a).
        // The full list is on the watch page (after clicking "Xem phim").
        // For now, parse what's available + the "Xem phim" link.
        val watchUrl = doc.selectFirst("a[href*='xem-phim.html']")?.attr("href")?.let { fixUrl(it) }
        val latestEpisodes = doc.select(".InfoList li.latest_eps a[href*='/tap-']").mapNotNull { a ->
            val epHref = fixUrl(a.attr("href"))
            val epTitle = a.attr("title").ifBlank { a.text().trim() }
            val epNum = Regex("""Tập\s*(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: a.text().trim().toIntOrNull()
            if (epHref.isNotBlank()) {
                newEpisode(epHref) {
                    this.name = "Tập ${epNum ?: epTitle}"
                    this.episode = epNum
                }
            } else null
        }.reversed()  // latest_eps shows 12, 11, 10 — reverse to 10, 11, 12

        // If we have a watch URL but no episodes parsed, use watchUrl as single episode
        val episodes = if (latestEpisodes.isNotEmpty()) {
            latestEpisodes
        } else if (watchUrl != null) {
            listOf(newEpisode(watchUrl) {
                this.name = "Xem Phim"
            })
        } else {
            // Fall back to using the detail URL itself — loadLinks will try to extract from there
            listOf(newEpisode(url) { this.name = "Xem Phim" })
        }

        // ── Recommendations ──
        // .MovieListRelated owl-carousel contains .TPostMv items (verified)
        val recommendations = doc.select(".MovieListRelated .TPostMv")
            .mapNotNull { parseAnimeCard(it) }
            .filter { it.url != url }
            .take(20)

        val isMovie = episodes.size == 1 && (url.contains("/anime-le/") || quality?.contains("OVA", true) == true)
        val tvType = if (isMovie) TvType.Movie else TvType.Anime

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.tags = genres.ifEmpty { null }
                this.year = releaseYear?.toIntOrNull()
                this.recommendations = recommendations.ifEmpty { null }
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.tags = genres.ifEmpty { null }
                this.year = releaseYear?.toIntOrNull()
                this.recommendations = recommendations.ifEmpty { null }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  loadLinks
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AVSB] loadLinks: ${data.take(100)}")

        val watchUrl = if (data.startsWith("http")) data else fixUrl(data)
        var linkFound = false

        // ── Strategy 1: Fetch watch page, extract _epHash + _epID, then POST /ajax/player ──
        // Verified from inline JS in watch.html:
        //   var _epHash = '...';
        //   var _epID   = 114070;
        //   filmInfo.filmID = parseInt('908');
        //   filmInfo.playTech = 'html5';
        // Screenshot confirms POST /ajax/player is the player loader endpoint.
        println("[AVSB] S1: fetching watch page to extract epHash + epID + filmID...")
        val watchHtml = fetchText(watchUrl)

        var epHash: String? = null
        var epID: String? = null
        var filmID: String? = null
        var playTech: String? = null

        if (watchHtml != null) {
            // Extract _epHash
            epHash = Regex("""_epHash\s*=\s*['"]([^'"]+)['"]""").find(watchHtml)?.groupValues?.get(1)
            // Extract _epID
            epID = Regex("""_epID\s*=\s*(\d+)""").find(watchHtml)?.groupValues?.get(1)
                ?: Regex("""filmInfo\.episodeID\s*=\s*parseInt\s*\(\s*['"](\d+)['"]\s*\)""").find(watchHtml)?.groupValues?.get(1)
            // Extract filmID
            filmID = Regex("""filmInfo\.filmID\s*=\s*parseInt\s*\(\s*['"](\d+)['"]\s*\)""").find(watchHtml)?.groupValues?.get(1)
            // Extract playTech
            playTech = Regex("""filmInfo\.playTech\s*=\s*['"](\w+)['"]""").find(watchHtml)?.groupValues?.get(1)

            println("[AVSB]   epHash=${epHash?.take(40)}... epID=$epID filmID=$filmID playTech=$playTech")

            // Look for any direct m3u8 URLs in the watch HTML
            val directM3u8s = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(watchHtml)
                .map { it.value }
                .filter { !it.contains("blob:") }
                .toSet()
            if (directM3u8s.isNotEmpty()) {
                println("[AVSB]   found ${directM3u8s.size} direct m3u8 URLs in watch HTML")
                for (m3u8Url in directM3u8s) {
                    if (tryM3U8Link(m3u8Url, watchUrl, callback)) linkFound = true
                }
            }

            // Look for iframe srcs in the watch HTML
            val iframeSrcs = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(watchHtml)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() && !it.startsWith("about:") && !it.startsWith("javascript:") }
                .toSet()
            if (iframeSrcs.isNotEmpty()) {
                println("[AVSB]   found ${iframeSrcs.size} iframe srcs")
                for (iframeUrl in iframeSrcs) {
                    val fullUrl = fixUrl(iframeUrl)
                    if (processEmbedUrl(fullUrl, watchUrl, callback)) linkFound = true
                }
            }
        }

        // ── Strategy 2: POST /ajax/player with extracted hash + IDs ──
        // (endpoint confirmed from screenshot DevTools panel)
        val ajaxAttempted = epHash != null && filmID != null && epID != null
        if (ajaxAttempted) {
            println("[AVSB] S2: POST /ajax/player with hash + filmId + epId...")
            if (postAjaxPlayer(epHash!!, filmID!!, epID!!, playTech, watchUrl, callback)) {
                linkFound = true
            }
        }

        // If AJAX succeeded as a request but no playable link was extracted,
        // avoid burning 60s on the watch page WebView. The actionable player is
        // the iframe returned by /ajax/player, and processEmbedUrl() already tries
        // both static extraction and WebView media capture on that iframe.
        if (ajaxAttempted && !linkFound) {
            println("[AVSB] AJAX player path produced no playable media; skipping slow watch-page WebView fallbacks")
            return false
        }

        // ── Strategy 3: WebView m3u8 capture ──
        // Load watch page in WebView — the player JS will fire /ajax/player
        // automatically and then load the m3u8. We capture the m3u8 via interceptor.
        if (!linkFound) {
            println("[AVSB] S3: WebView m3u8 capture...")
            try {
                val resp = app.get(watchUrl, headers = withCookies(commonHeaders), interceptor = mediaWebView)
                val capturedUrl = resp.url ?: ""
                val content = resp.text
                println("[AVSB]   S3 captured URL: ${capturedUrl.take(120)}")

                if (capturedUrl.isNotBlank() && !capturedUrl.startsWith("blob:")) {
                    val isM3u8 = content.trimStart().startsWith("#EXTM3U") || capturedUrl.contains(".m3u")
                    if (isM3u8) {
                        val variants = parseM3U8Variants(content, capturedUrl)
                        if (variants.isNotEmpty()) {
                            variants.forEach { (label, variantUrl, quality) ->
                                callback(newExtractorLink(name, "AVSB $label", variantUrl, ExtractorLinkType.M3U8) {
                                    this.quality = quality
                                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)
                                    this.referer = watchUrl
                                })
                            }
                            linkFound = true
                        } else {
                            callback(newExtractorLink(name, "AnimeVietsub", capturedUrl, ExtractorLinkType.M3U8) {
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)
                                this.referer = watchUrl
                            })
                            linkFound = true
                        }
                    }
                }
            } catch (e: Exception) {
                println("[AVSB] S3 error: ${e.message}")
            }
        }

        // ── Strategy 4: CF interceptor broad capture ──
        if (!linkFound) {
            println("[AVSB] S4: CF interceptor broad capture...")
            try {
                val resp = app.get(watchUrl, headers = withCookies(commonHeaders), interceptor = mediaWebView)
                val capturedUrl = resp.url ?: ""
                val content = resp.text
                println("[AVSB]   S4 captured URL: ${capturedUrl.take(120)}")

                if (capturedUrl.isNotBlank() && !capturedUrl.startsWith("blob:")) {
                    val isM3u8 = content.trimStart().startsWith("#EXTM3U") || capturedUrl.contains(".m3u")
                    if (isM3u8) {
                        callback(newExtractorLink(name, "AnimeVietsub", capturedUrl, ExtractorLinkType.M3U8) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)
                            this.referer = watchUrl
                        })
                        linkFound = true
                    }
                }

                // Re-scan for m3u8 in case CF revealed them
                if (!linkFound && content.contains(".m3u8")) {
                    val m3u8Matches = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(content)
                    for (m in m3u8Matches) {
                        if (tryM3U8Link(m.value, watchUrl, callback)) linkFound = true
                    }
                }
            } catch (e: Exception) {
                println("[AVSB] S4 error: ${e.message}")
            }
        }

        if (!linkFound) {
            println("[AVSB] All strategies failed for: $watchUrl")
        }
        return linkFound
    }

    /**
     * POST to /ajax/player — EXACT request format reverse-engineered from pl.watchbk2.js
     *
     * From line 234 of pl.watchbk2.js:
     *   var AnimeVsub = function(level, deepDataAndEvents) {
     *       ...
     *       $.ajax({
     *           type: "POST",
     *           url: PlayerLoad,            // = MAIN_URL + "/ajax/player"
     *           dataType: "json",
     *           data: {
     *               "link": level,                  // ← the _epHash (long base64url token)
     *               "id": deepDataAndEvents         // ← the filmInfo.filmID (integer as string)
     *           },
     *           success: function(f) {
     *               // f.playTech = "api" | "all" | "embed" | "iframe"
     *               // f.link = string (error msg or iframe URL) OR array of {file, label, type, ...}
     *               // f.success = 1 (only checked in backup path)
     *               if (f.playTech == "api" || f.playTech == "all") {
     *                   if (typeof f.link === "string") {
     *                       // display error message — no playable source
     *                   } else {
     *                       sources = [];
     *                       jQuery.each(f.link, function(_, file) {
     *                           file.file = file.file.replace("&http", "http");
     *                           sources.push(file);
     *                       });
     *                       PLTV.Player(f.playTech, sources, href);
     *                   }
     *               } else if (f.playTech == "embed") {
     *                   // f.link is a single mp4 URL
     *               } else if (f.playTech == "iframe") {
     *                   // f.link is an iframe URL
     *               }
     *           }
     *       });
     *   };
     *
     * The function is called as: AnimeVsub(_epHash, filmInfo.filmID)
     * So:
     *   level              = _epHash     (the long base64url string from watch page)
     *   deepDataAndEvents  = filmID      (the integer film ID, e.g. "908")
     *
     * Therefore the EXACT POST body is:
     *   link=<epHash>&id=<filmID>
     *
     * NOTE: epID is NOT sent in this request — it's only used client-side for DOM manipulation.
     * The server resolves the episode from the hash.
     */
    private suspend fun postAjaxPlayer(
        epHash: String,
        filmID: String,
        epID: String,
        playTech: String?,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ajaxUrl = "$mainUrl/ajax/player"
        val ajaxHeaders = withCookies(mapOf(
            "User-Agent"       to USER_AGENT,
            "Referer"          to referer,
            "Origin"           to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "Sec-Fetch-Site"   to "same-origin",
            "Sec-Fetch-Mode"   to "cors",
            "Sec-Fetch-Dest"   to "empty",
            "sec-ch-ua"        to "\"Chromium\";v=\"138\", \"Android WebView\";v=\"138\", \"Not.A/Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\""
        ))

        // EXACT POST body from pl.watchbk2.js AnimeVsub() function:
        //   data: { "link": level, "id": deepDataAndEvents }
        val params = mapOf(
            "link" to epHash,
            "id"   to filmID
        )

        return try {
            println("[AVSB]   POST /ajax/player with EXACT params: link=${epHash.take(40)}... id=$filmID")
            val body = rawPostForm(ajaxUrl, ajaxHeaders, params)
            println("[AVSB]   response (${body.length} chars): ${body.take(300)}")

            if (body.isBlank()) {
                println("[AVSB]   empty response")
                return false
            }

            parsePlayerResponse(body, referer, callback)
        } catch (e: Exception) {
            println("[AVSB]   POST error: ${e.message}")
            false
        }
    }

    /**
     * Parse the JSON response from /ajax/player.
     *
     * Response structure (from pl.watchbk2.js success handler):
     *   {
     *     "playTech": "api" | "all" | "embed" | "iframe",
     *     "link": <string or array>,
     *     "success": 1   // optional, only in backup path
     *   }
     *
     * - If playTech is "api" or "all" and link is array → each item has {file, label, type, ...}
     *   where file.file is the m3u8/mp4 URL
     * - If playTech is "embed" → link is a single mp4 URL
     * - If playTech is "iframe" → link is an iframe URL
     */
    private suspend fun parsePlayerResponse(
        body: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anyFound = false

        // Try to parse as JSON
        val playTech = Regex(""""playTech"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        val success = Regex(""""success"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull()
        println("[AVSB]   playTech=$playTech success=$success")

        if (playTech != null) {
            when (playTech.lowercase()) {
                "api", "all" -> {
                    // link can be string (error) or array of sources
                    // Check if link is an array: "link":[{...},{...}]
                    val linkArrayMatch = Regex(""""link"\s*:\s*\[(\{[\s\S]*?\})\]""").find(body)
                    if (linkArrayMatch != null) {
                        // Extract all {file:"...",label:"..."} objects
                        val fileObjects = Regex("""\{[^{}]*"file"\s*:\s*"([^"]+)"[^{}]*\}""").findAll(body)
                        for (m in fileObjects) {
                            var fileUrl = m.groupValues[1].replace("\\/", "/").replace("&http", "http")
                            if (fileUrl.startsWith("http") && !fileUrl.contains("blob:")) {
                                // Extract label (quality) if present
                                val labelMatch = Regex(""""label"\s*:\s*"([^"]+)"""").find(m.value)
                                val label = labelMatch?.groupValues?.get(1)
                                println("[AVSB]   source: label=$label url=${fileUrl.take(80)}")
                                if (tryM3U8Link(fileUrl, referer, callback, label)) {
                                    anyFound = true
                                }
                            }
                        }
                    } else {
                        // link is a string — likely an error message, but check if it's a URL
                        val linkStr = Regex(""""link"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                        if (linkStr != null && (linkStr.startsWith("http") || linkStr.contains(".m3u8"))) {
                            val url = linkStr.replace("\\/", "/").replace("&http", "http")
                            if (tryM3U8Link(url, referer, callback, null)) anyFound = true
                        } else {
                            println("[AVSB]   link is string (likely error): ${linkStr?.take(100)}")
                        }
                    }
                }
                "embed" -> {
                    // link is a single mp4 URL
                    val linkStr = Regex(""""link"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                    if (linkStr != null) {
                        val url = linkStr.replace("\\/", "/").replace("&http", "http")
                        if (url.startsWith("http")) {
                            println("[AVSB]   embed URL: ${url.take(80)}")
                            if (tryM3U8Link(url, referer, callback, "720")) anyFound = true
                        }
                    }
                }
                "iframe" -> {
                    // link is an iframe URL
                    val linkStr = Regex(""""link"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                    if (linkStr != null) {
                        val url = linkStr.replace("\\/", "/")
                        if (url.startsWith("http")) {
                            println("[AVSB]   iframe URL: ${url.take(80)}")
                            // Fetch the iframe page and extract m3u8 from it.
                            // Add isFinal=1 for the protected storage.googleapiscdn player.
                            if (processEmbedUrl(finalPlayerUrl(url), referer, callback)) anyFound = true
                        }
                    }
                }
            }
        }

        // Fallback: scan for any m3u8/mp4 URLs in the response regardless of playTech
        if (!anyFound) {
            val mediaUrls = Regex("""https?://[^\s"'<>\\]+(?:\.m3u8|\.mp4)[^\s"'<>\\]*""")
                .findAll(body)
                .map { it.value.replace("\\/", "/").replace("&http", "http") }
                .filter { !it.contains("blob:") }
                .toSet()
            if (mediaUrls.isNotEmpty()) {
                println("[AVSB]   fallback: found ${mediaUrls.size} media URLs in response")
                for (u in mediaUrls) {
                    if (tryM3U8Link(u, referer, callback, null)) anyFound = true
                }
            }
        }

        return anyFound
    }

    /**
     * Try to register a single m3u8/mp4 link.
     *
     * @param label optional quality label from the source (e.g. "720", "1080", "FHD")
     *              — used to set the ExtractorLink quality if present
     */
    private suspend fun tryM3U8Link(
        m3u8Url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        label: String? = null
    ): Boolean {
        return try {
            val headers = withCookies(mapOf(
                "User-Agent" to USER_AGENT,
                "Referer"    to referer,
                "Origin"     to mainUrl,
                "Accept"     to "*/*",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty"
            ))
            val resp = app.get(m3u8Url, headers = headers)
            if (resp.code != 200 || !resp.text.contains("#EXTM3U")) {
                // Not m3u8 — try as mp4
                if (m3u8Url.contains(".mp4")) {
                    val qualityFromLabel = labelToQuality(label)
                    val displayName = if (label != null) "AnimeVietsub $label" else "AnimeVietsub MP4"
                    callback(newExtractorLink(name, displayName, m3u8Url, ExtractorLinkType.VIDEO) {
                        this.quality = qualityFromLabel ?: Qualities.P1080.value
                        this.headers = headers
                        this.referer = referer
                    })
                    return true
                }
                return false
            }
            val content = resp.text
            val variants = parseM3U8Variants(content, m3u8Url)
            if (variants.isNotEmpty()) {
                println("[AVSB]   master playlist with ${variants.size} variants: ${variants.map { it.first }}")
                variants.forEach { (vLabel, variantUrl, quality) ->
                    callback(newExtractorLink(name, "AVSB $vLabel", variantUrl, ExtractorLinkType.M3U8) {
                        this.quality = quality
                        this.headers = headers
                        this.referer = referer
                    })
                }
            } else {
                val qualityFromLabel = labelToQuality(label)
                val displayName = if (label != null) "AnimeVietsub $label" else "AnimeVietsub"
                println("[AVSB]   single-variant m3u8 OK: ${m3u8Url.take(80)} (label=$label)")
                callback(newExtractorLink(name, displayName, m3u8Url, ExtractorLinkType.M3U8) {
                    this.quality = qualityFromLabel ?: Qualities.P1080.value
                    this.headers = headers
                    this.referer = referer
                })
            }
            true
        } catch (e: Exception) {
            println("[AVSB]   m3u8 fetch error: ${e.message}")
            false
        }
    }

    /** Convert a label like "720", "1080", "FHD", "HD" to a Qualities enum value. */
    private fun labelToQuality(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        val upper = label.uppercase().trim()
        return when {
            upper.contains("4K") || upper.contains("2160") -> Qualities.P2160.value
            upper.contains("2K") || upper.contains("1440") -> Qualities.P1440.value
            upper.contains("1080") || upper.contains("FHD") -> Qualities.P1080.value
            upper.contains("720") || upper == "HD" -> Qualities.P720.value
            upper.contains("480") || upper == "SD" -> Qualities.P480.value
            upper.contains("360") -> Qualities.P360.value
            else -> null
        }
    }

    /**
     * Fetch an embed/iframe URL and extract m3u8/mp4 links from its HTML/JS.
     */
    private suspend fun processEmbedUrl(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = finalPlayerUrl(embedUrl)
        val embedHtml = try {
            if (playerUrl.startsWith(mainUrl)) {
                httpGet(playerUrl)
            } else {
                app.get(playerUrl, headers = withCookies(mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer"    to referer,
                    "Accept"     to "text/html,application/xhtml+xml,*/*;q=0.8"
                ))).text
            }
        } catch (e: Exception) {
            println("[AVSB]   embed fetch failed: ${e.message}")
            return false
        }

        println("[AVSB]   embed fetch ${playerUrl.take(90)} len=${embedHtml.length} preview=${embedHtml.take(180).replace("\n", " ")}")

        fun cleanMediaUrl(raw: String): String {
            return raw
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .let { if (it.startsWith("//")) "https:$it" else it }
        }

        fun scanMediaUrls(text: String): Set<String> {
            val out = linkedSetOf<String>()
            // 1) Any full URL that already contains a known media extension.
            Regex("""https?:\\?/\\?/[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { cleanMediaUrl(it.value) }
                .filter { !it.contains("blob:") && (it.contains(".m3u8", true) || it.contains(".m3u", true) || it.contains(".mp4", true)) }
                .forEach { out.add(it) }

            // 2) Common JS player fields: file/src/source/url.
            Regex("""(?i)(?:file|src|source|url)\s*[:=]\s*["']([^"']+)["']""")
                .findAll(text)
                .map { cleanMediaUrl(it.groupValues[1]) }
                .filter { !it.contains("blob:") && (it.startsWith("http") || it.startsWith("//")) && (it.contains(".m3u8", true) || it.contains(".m3u", true) || it.contains(".mp4", true)) }
                .forEach { out.add(it) }

            // 3) Base64 wrappers such as atob('...') sometimes hold the source.
            Regex("""atob\s*\(\s*["']([^"']{20,})["']\s*\)""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .forEach { m ->
                    try {
                        val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                        out.addAll(scanMediaUrls(decoded))
                    } catch (_: Exception) {}
                }
            return out
        }

        suspend fun tryStatusEndpoint(): Boolean {
            val origin = Regex("""https?://[^/]+""").find(playerUrl)?.value ?: return false
            val playerId = playerUrl.substringAfterLast("/").substringBefore("?")
            if (playerId.isBlank()) return false
            val statusUrl = "$origin/status/$playerId"
            val statusHeaders = withCookies(mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to playerUrl,
                "Origin" to origin,
                "Accept" to "application/json, text/plain, */*",
                "X-Requested-With" to "XMLHttpRequest",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty"
            ))

            // The iframe JS calls /status/{playerId}; log and parse it directly.
            // If the endpoint is cookie/fingerprint-protected, fall back to a
            // WebView capture of that exact XHR response.
            val statusBodies = mutableListOf<String>()
            try {
                val body = app.get(statusUrl, headers = statusHeaders).text
                println("[AVSB]   status direct $statusUrl len=${body.length} preview=${body.take(220).replace("\n", " ")}")
                statusBodies += body
            } catch (e: Exception) {
                println("[AVSB]   status direct failed: ${e.message}")
            }
            try {
                val statusResolver = WebViewResolver(
                    interceptUrl = Regex(Regex.escape(statusUrl)),
                    additionalUrls = emptyList(),
                    useOkhttp = false,
                    userAgent = null
                )
                val resp = app.get(playerUrl, headers = statusHeaders, interceptor = statusResolver)
                val body = resp.text
                println("[AVSB]   status WebView ${resp.url?.take(120)} len=${body.length} preview=${body.take(220).replace("\n", " ")}")
                statusBodies += body
            } catch (e: Exception) {
                println("[AVSB]   status WebView capture failed: ${e.message}")
            }

            for (body in statusBodies) {
                val found = scanMediaUrls(body)
                println("[AVSB]   status scan → ${found.size} media URLs")
                for (mediaUrl in found) {
                    if (tryM3U8Link(mediaUrl, playerUrl, callback)) return true
                }

                // Some player APIs return JSON-escaped strings or base64-ish blobs
                // outside atob(). Decode all long base64 candidates as a last static pass.
                Regex("""["']([A-Za-z0-9+/=_-]{60,})["']""").findAll(body).forEach { m ->
                    val raw = m.groupValues[1]
                    val variants = listOf(raw, raw.replace('-', '+').replace('_', '/'))
                    for (v in variants) {
                        try {
                            val decoded = String(Base64.decode(v, Base64.DEFAULT), Charsets.UTF_8)
                            val nested = scanMediaUrls(decoded)
                            if (nested.isNotEmpty()) println("[AVSB]   status base64 nested scan → ${nested.size} media URLs")
                            for (mediaUrl in nested) {
                                if (tryM3U8Link(mediaUrl, playerUrl, callback)) return true
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            return false
        }

        var mediaUrls = scanMediaUrls(embedHtml)
        println("[AVSB]   embed static scan → ${mediaUrls.size} media URLs")

        var anyFound = false
        for (mediaUrl in mediaUrls) {
            if (tryM3U8Link(mediaUrl, playerUrl, callback)) anyFound = true
        }
        if (anyFound) return true

        if (tryStatusEndpoint()) return true

        // Static HTML had no direct URL. Execute the iframe in a real WebView and
        // capture the first requested m3u8/mp4. This is the correct fallback for
        // /ajax/player responses with playTech='iframe'.
        println("[AVSB]   embed WebView media capture: ${playerUrl.take(100)}")
        try {
            val resp = app.get(playerUrl, headers = withCookies(mapOf(
                "User-Agent" to USER_AGENT,
                "Referer"    to referer,
                "Accept"     to "text/html,application/xhtml+xml,*/*;q=0.8"
            )), interceptor = mediaWebView)
            val capturedUrl = resp.url ?: ""
            val content = resp.text
            println("[AVSB]   embed WebView captured URL: ${capturedUrl.take(160)} len=${content.length} preview=${content.take(120).replace("\n", " ")}")
            if (capturedUrl.contains(".m3u", true) || capturedUrl.contains(".mp4", true)) {
                if (tryM3U8Link(capturedUrl, playerUrl, callback)) return true
            }
            mediaUrls = scanMediaUrls(content)
            for (mediaUrl in mediaUrls) {
                if (tryM3U8Link(mediaUrl, playerUrl, callback)) anyFound = true
            }
        } catch (e: Exception) {
            println("[AVSB]   embed WebView capture failed: ${e.message}")
        }
        return anyFound
    }
}
