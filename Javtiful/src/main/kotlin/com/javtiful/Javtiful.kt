package com.javtiful

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * Javtiful CloudStream Plugin
 *
 * Site: https://javtiful.com/vn/main
 * Stream extraction: Direct MP4 from Cloudflare R2 storage, embedded in
 *                   `<script id="frontWatchConfig" type="application/json">` as `playerSources[]`.
 *                   Each source: { src, type:"video/mp4", size:<height> }
 *                   URLs are AWS S3-style signed (X-Amz-Expires=3600), so always fetch fresh.
 *
 * Sections supported:
 *  - mainPage: Latest / Trending / Most Viewed / Most Liked
 *              + Censored / Uncensored / Reducing-mosaic collections
 *              + 12 popular categories
 *              + 12 popular actresses
 *              + 12 popular channels
 *  - search  : /vn/search?q={query}
 *  - load    : metadata + parallel recommendations from actress + first category page
 *  - loadLinks : direct MP4 from `playerSources` array (refreshed each call)
 *
 * Card filter: skip `<article class="front-video-card front-partner-card">` (ad cards).
 */
/**
 * Plugin entry point — the CloudStream Gradle plugin scans for a class
 * annotated with `@CloudstreamPlugin` and uses it as the registration hook.
 * Without this class, the build fails with:
 *   "No plugin class annotated with @CloudstreamPlugin was found"
 */
@CloudstreamPlugin
class JavtifulPlugin : Plugin() {
    override fun load() {
        val provider = JavtifulProvider()
        registerMainAPI(provider)
        // Pre-warm Cloudflare cookies immediately when the plugin loads.
        // CloudStream loads plugins at app startup — typically 2-3 seconds
        // BEFORE the user navigates to the home page. By firing the
        // background WebView NOW, the Cloudflare JS challenge gets solved
        // during that window, and by the time the user opens the Javtiful
        // home page, cookies are already cached in OkHttp's jar.
        // This means the FIRST home page load can succeed instantly,
        // instead of being empty and requiring a manual refresh.
        provider.preWarmCloudflareCookies()
    }
}

class JavtifulProvider : MainAPI() {
    override var mainUrl              = "https://javtiful.com"
    override var name                 = "Javtiful"
    override val supportedTypes       = setOf(TvType.NSFW)
    override var lang                 = "vn"
    override val hasMainPage          = true
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true

    private val TAG = "Javtiful"

    /** Mobile UA — site serves a lighter payload for mobile clients. */
    private val UA = "Mozilla/5.0 (Linux; Android 10; SM-G973F) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** Vietnamese-language prefix used on every page we render. */
    private val VN = "/vn"

    private val commonHeaders = mapOf(
        "User-Agent"      to UA,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
        "Referer"         to mainUrl + "/"
    )

    // ────────────────────────────────────────────────────────────────────
    //  Cloudflare bypass — Multi-proxy strategy
    //
    //  javtiful.com sits behind Cloudflare and serves a JS challenge to
    //  Vietnam mobile IPs. CloudStream's home page timeout (~2s) is shorter
    //  than Cloudflare's challenge solve time (3-5s), so WebView-based
    //  approaches cannot work within the home page coroutine.
    //
    //  SOLUTION: Route requests through proxy.cors.sh — a Cloudflare Worker.
    //  When it fetches javtiful.com, the request goes Cloudflare-edge →
    //  Cloudflare-edge (loopback), so NO challenge is served. This is
    //  essentially "using Cloudflare to bypass Cloudflare".
    //
    //  The plugin tries each proxy in [corsProxies] order. The first that
    //  returns real content wins. If all fail, fall back to a background
    //  WebView (fire-and-forget, survives home page cancellation).
    // ────────────────────────────────────────────────────────────────────

    /**
     * List of CORS proxies to try, in order of preference.
     *   - "https://proxy.cors.sh/" = Cloudflare Worker — bypasses CF challenge
     *   - "" = direct connection (works with VPN or non-challenged IP)
     *   - allorigins / thingproxy = public backups (sometimes rate-limited)
     */
    private val corsProxies = listOf(
        "https://proxy.cors.sh/",
        "",
        "https://api.allorigins.win/raw?url=",
        "https://thingproxy.freeboard.io/fetch/"
    )

    /** Convert a direct URL into a proxied URL. */
    private fun proxify(url: String, proxy: String): String {
        if (proxy.isEmpty()) return url
        return if (proxy.endsWith("?url=") || proxy.endsWith("?q=")) {
            proxy + URLEncoder.encode(url, "UTF-8")
        } else {
            proxy + url
        }
    }

    /**
     * WebViewResolver used only as a last-resort background cookie solver.
     * `interceptUrl` never matches, so the WebView runs to completion and
     * routes all sub-requests through OkHttp (because useOkhttp=true),
     * which sets cf_clearance cookies in OkHttp's cookie jar.
     */
    private val cfWebView = WebViewResolver(
        interceptUrl = Regex("""__cf_never_match__"""),
        useOkhttp = true,
        timeout = 15_000L
    )

    /** Background scope that survives CloudStream's home page cancellation. */
    private val bgScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    /** Ensures only ONE background WebView runs at a time. */
    private val cfSolving = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Quick check: does this HTML look like a Cloudflare challenge page?
     *  IMPORTANT: must NOT match search-result pages with zero results —
     *  those return valid HTML without front-video-card but with a
     *  "Không tìm thấy video" message. We detect challenge pages by
     *  looking for actual Cloudflare markers, not by absence of content. */
    private fun looksLikeChallenge(html: String): Boolean {
        if (html.isBlank() || html.length < 500) return true
        return html.contains("Just a moment...", ignoreCase = true) ||
               html.contains("cf-challenge", ignoreCase = true) ||
               html.contains("_cf_chl_opt", ignoreCase = true) ||
               html.contains("cf-mitigated", ignoreCase = true) ||
               html.contains("challenge-platform", ignoreCase = true) ||
               html.contains("cf-browser-verification", ignoreCase = true) ||
               html.contains("cf_chl_", ignoreCase = true) ||
               html.contains("/cdn-cgi/challenge-platform/", ignoreCase = true) ||
               html.contains("cf-spinner-please-wait", ignoreCase = true) ||
               html.contains("cf-turnstile", ignoreCase = true)
    }

    /**
     * Fire a background WebView to solve the Cloudflare challenge.
     * Runs in [bgScope] — independent of the calling coroutine, so it
     * survives CloudStream's home page cancellation. The WebView loads
     * the URL, the JS challenge executes, and because `useOkhttp = true`,
     * the challenge-solving POST goes through OkHttp → cf_clearance cookie
     * gets set in OkHttp's cookie jar → future OkHttp requests succeed.
     */
    private fun fireBackgroundChallengeSolver() {
        if (!cfSolving.compareAndSet(false, true)) return
        bgScope.launch {
            try {
                Log.i(TAG, "Background Cloudflare solver starting")
                cfWebView.resolveUsingWebView(mainUrl + VN + "/videos", referer = mainUrl)
                Log.i(TAG, "Background Cloudflare solver finished")
            } catch (t: Throwable) {
                Log.w(TAG, "Background Cloudflare solver failed: ${t.message}")
            } finally {
                cfSolving.set(false)
            }
        }
    }

    /**
     * Public entry point for the Plugin class to pre-warm Cloudflare cookies
     * at plugin load time. Fires the background WebView 2-3 seconds BEFORE
     * the user opens the home page, so cookies are ready by the time they do.
     */
    fun preWarmCloudflareCookies() {
        Log.i(TAG, "Pre-warming Cloudflare cookies at plugin load")
        fireBackgroundChallengeSolver()
    }

    // ────────────────────────────────────────────────────────────────────
    //  mainPage
    //
    //  CRITICAL: CloudStream fires ONE parallel HTTP request per section when
    //  the home page loads, and the home-page coroutine supervisor cancels
    //  the ENTIRE batch after ~3.5 seconds. On a mobile network:
    //    - TLS handshake to Cloudflare: 0.5–1.5s
    //    - Server response: 0.3–0.8s
    //    - OkHttp per-host connection pool: 5 concurrent
    //  So with 10 sections, the 6th–10th requests don't even START before
    //  the 3.5s deadline. With 47 sections (my original code), NOTHING
    //  completes and the home page stays blank forever.
    //
    //  Solution: keep this list to 3 sections (fits within OkHttp's 5-connection
    //  per-host pool, so all 3 run in parallel and finish within the deadline).
    //  Everything else is reachable via Search.
    //
    //  cacheTime = 300 (5 minutes) means once a page is fetched, subsequent
    //  navigations back to the home page return instantly from cache.
    // ────────────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl$VN/videos"   to "Mới nhất",
        "$mainUrl$VN/trending" to "Đang thịnh hành",
        "$mainUrl$VN/censored" to "Có che"
    )

    /** Cache duration for list-page fetches (5 minutes). */
    private val listCacheTime = 300

    // ────────────────────────────────────────────────────────────────────
    //  HTTP helper (suspend — app.get() is suspend)
    //
    //  Multi-proxy Cloudflare bypass strategy:
    //
    //  1. Try each CORS proxy in [corsProxies] order:
    //     - proxy.cors.sh (Cloudflare Worker — bypasses CF challenge)
    //     - direct connection (works with VPN or non-challenged IP)
    //     - allorigins / thingproxy (backup, sometimes work)
    //  2. First proxy that returns real content (HTML containing
    //     "front-video-card" or "frontWatchConfig") wins — return immediately.
    //  3. If ALL proxies fail (challenge or empty), fire background WebView
    //     to solve challenge and set cookies in OkHttp jar.
    //
    //  proxy.cors.sh is the key: it's a Cloudflare Worker, so requests go
    //  Cloudflare-edge → Cloudflare-edge (loopback) → no challenge served.
    //  This works from ANY IP, including Vietnam mobile carriers.
    //
    //  IMPORTANT: Never catch CancellationException — let it propagate so
    //  the coroutine cancels cleanly. Use `catch (e: Exception)` not
    //  `catch (t: Throwable)`.
    // ────────────────────────────────────────────────────────────────────

    private suspend fun httpGet(url: String): String {
        // ── Phase 1: Try each CORS proxy in order ──
        for (proxy in corsProxies) {
            val proxiedUrl = proxify(url, proxy)
            val html = try {
                app.get(
                    proxiedUrl,
                    headers   = commonHeaders + mapOf("X-Requested-With" to "com.lagradost.cloudstream3.prerelease"),
                    timeout   = 3_000L,          // 3s per proxy — short enough to try all before CloudStream cancels
                    cacheTime = listCacheTime,
                    cacheUnit = java.util.concurrent.TimeUnit.SECONDS
                ).text
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e                                // NEVER catch CancellationException
            } catch (e: Exception) {
                Log.w(TAG, "httpGet proxy=$proxy failed: $url :: ${e.message}")
                ""
            }

            // Real content? Return immediately.
            if (html.isNotBlank() && !looksLikeChallenge(html)) {
                return html
            }
        }

        // ── Phase 2: All proxies failed → fire background WebView ──
        // This returns immediately. The WebView runs in bgScope (survives
        // home page cancellation). Cookies get set in OkHttp's jar.
        // The CURRENT home page load returns empty, but the NEXT one works.
        Log.i(TAG, "All CORS proxies failed for $url — firing background WebView solver")
        fireBackgroundChallengeSolver()

        return ""
    }

    private suspend fun httpGetDoc(url: String) =
        Jsoup.parse(httpGet(url), url)

    // ────────────────────────────────────────────────────────────────────
    //  Card parsing — single source of truth for list pages
    // ────────────────────────────────────────────────────────────────────

    /**
     * Parse one `<article class="front-video-card">` element into a SearchResponse.
     * Skips partner/ad cards (`front-partner-card` class).
     */
    private fun Element.toSearchResponse(): SearchResponse? {
        // Skip ad / partner cards
        if (classNames().contains("front-partner-card")) return null

        val thumbEl = selectFirst("a.front-video-thumb") ?: return null
        val href    = thumbEl.attr("href").ifEmpty { return null }
        if (!href.contains("/video/")) return null

        // Title from the dedicated title link; fall back to img alt
        val titleEl = selectFirst("a.front-video-title")
        val title   = titleEl?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.removePrefix("Thumbnail for ")?.trim()
            ?: return null

        // Thumbnail: site uses lazy-loading so the real URL is in data-front-lazy-src.
        // If that's missing, fall back to the fallback-src (which is pipe-separated).
        val img = selectFirst("img")
        var poster = img?.attr("data-front-lazy-src")?.takeIf { it.isNotBlank() }
        if (poster.isNullOrBlank()) {
            val fb = img?.attr("data-front-lazy-fallback-src") ?: ""
            poster = fb.split("|").firstOrNull { it.isNotBlank() }
        }
        if (poster.isNullOrBlank()) poster = img?.attr("src") ?: ""

        val absPoster = when {
            poster.startsWith("http") -> poster
            poster.startsWith("/")    -> mainUrl + poster
            else                      -> ""
        }

        // Optional quality badge → map to SearchQuality enum
        val qualityTag = selectFirst(".front-quality-tag")?.text()?.trim().orEmpty()
        val quality: SearchQuality? = when (qualityTag.uppercase()) {
            "4K", "UHD"   -> SearchQuality.FourK
            "FHD", "1080" -> SearchQuality.HD
            "HD",  "720"  -> SearchQuality.HD
            "SD",  "480"  -> SearchQuality.SD
            else          -> null
        }

        val absUrl = if (href.startsWith("http")) href else mainUrl + href

        return newMovieSearchResponse(title, absUrl, TvType.NSFW) {
            this.posterUrl = absPoster
            this.quality   = quality
        }
    }

    /**
     * Fetch a list page and convert every `<article class="front-video-card">`
     * (excluding partner cards) into a SearchResponse.
     */
    private suspend fun parseListPage(url: String): List<SearchResponse> {
        val html = httpGet(url)
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, url)
        return doc.select("article.front-video-card")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    // ────────────────────────────────────────────────────────────────────
    //  MainPage fetch
    // ────────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = request.data
        val sep  = if (base.contains("?")) "&" else "?"
        val url  = if (page <= 1) base else "$base${sep}page=$page"
        val items = parseListPage(url)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ────────────────────────────────────────────────────────────────────
    //  Search
    // ────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return parseListPage("$mainUrl$VN/search?q=$encoded")
    }

    // ────────────────────────────────────────────────────────────────────
    //  load (detail page)
    // ────────────────────────────────────────────────────────────────────

    /** JSON shape inside `#frontWatchConfig` — only the fields we actually use. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class WatchConfig(
        @param:JsonProperty("playerSources")  val playerSources: List<PlayerSource>? = null,
        @param:JsonProperty("videoTitle")     val videoTitle: String?    = null,
        @param:JsonProperty("videoPoster")    val videoPoster: String?   = null,
        @param:JsonProperty("defaultQuality") val defaultQuality: Int?   = null,
        @param:JsonProperty("qualityOptions") val qualityOptions: List<Int>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class PlayerSource(
        @param:JsonProperty("src")  val src: String?  = null,
        @param:JsonProperty("type") val type: String? = null,
        @param:JsonProperty("size") val size: Int?    = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = httpGetDoc(url)
        // 1) Pull the player config JSON out of the page.
        val configJson = doc.selectFirst("script#frontWatchConfig")?.data()
            ?: run {
                Log.w(TAG, "load: no #frontWatchConfig on $url")
                return null
            }

        val config: WatchConfig = AppUtils.tryParseJson<WatchConfig>(configJson) ?: run {
            Log.w(TAG, "load: failed to parse WatchConfig JSON")
            return null
        }

        // 2) Title & poster
        val title  = config.videoTitle?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1.front-watch-title")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: "Untitled"

        val poster = config.videoPoster?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http")) it else mainUrl + it
        } ?: doc.selectFirst("video#front-player")?.attr("poster")?.let {
            if (it.startsWith("http")) it else mainUrl + it
        }

        // 3) Metadata rows: actress / tags / categories / channel / date.
        //    Local collectors (NOT class-level) — concurrent load() calls must be isolated.
        var actress: String? = null
        val tags        = mutableListOf<String>()
        val categories  = mutableListOf<String>()
        var channel: String? = null
        var addedDate: String? = null
        val actressSlugs  = mutableSetOf<String>()
        val categorySlugs = mutableSetOf<String>()

        doc.select(".front-watch-detail").forEach { detail ->
            val label = detail.selectFirst("strong")?.text()?.trim().orEmpty()
            when {
                label.startsWith("Diễn viên") -> {
                    actress = detail.select("a.front-watch-actor-card")
                        .joinToString(", ") { it.text().trim() }
                        .ifBlank { null }
                    if (actress.isNullOrBlank()) {
                        actress = detail.select(".front-watch-actor-list a")
                            .joinToString(", ") { it.text().trim() }
                            .ifBlank { null }
                    }
                    // Also harvest actress slugs for parallel recommendations
                    detail.select("a[href*=/actress/]").forEach { a ->
                        val h = a.attr("href")
                        if (h.contains("/actress/")) actressSlugs.add(h)
                    }
                }
                label.startsWith("Thẻ") -> {
                    detail.select("a.front-watch-link-chip").forEach { c ->
                        c.text().trim().takeIf { it.isNotBlank() }?.let { tags.add(it) }
                    }
                }
                label.startsWith("Danh mục") -> {
                    detail.select("a.front-watch-link-chip").forEach { c ->
                        c.text().trim().takeIf { it.isNotBlank() }?.let { categories.add(it) }
                        val h = c.attr("href")
                        if (h.contains("/category/")) categorySlugs.add(h)
                    }
                }
                label.startsWith("Kênh") -> {
                    channel = detail.selectFirst("a.front-watch-inline-link")?.text()?.trim()
                }
                label.startsWith("Đã thêm vào") -> {
                    addedDate = detail.selectFirst("time")?.attr("datetime")
                }
            }
        }

        // 4) Censored / uncensored chip
        val censoredChip = doc.selectFirst(".front-watch-meta .front-chip")?.text()?.trim().orEmpty()

        // 5) Views & likes — best-effort, page may not always have them
        val views = doc.selectFirst("[data-front-views-count]")?.text()?.trim()
        val likes = doc.selectFirst("[data-front-likes-count]")?.text()?.trim()

        // 6) Recommendations — pull the in-page "More like this" grid first,
        //    then enhance with parallel fetches from the actress + first category page.
        val pageRecs = doc.select("article.front-video-card")
            .mapNotNull { runCatching { it.toSearchResponse() }.getOrNull() }
            .distinctBy { it.url }

        // Parallel enhancement — only fetch if we have somewhere to fetch from.
        val enhancedRecs = withContext(Dispatchers.IO) {
            val endpoints = mutableListOf<String>()
            actressSlugs.firstOrNull()?.let {
                endpoints.add(if (it.startsWith("http")) it else mainUrl + it)
            }
            categorySlugs.firstOrNull()?.let {
                endpoints.add(if (it.startsWith("http")) it else mainUrl + it)
            }

            val remote = endpoints.map { ep ->
                async {
                    runCatching { parseListPage(ep).take(8) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten()

            (pageRecs + remote)
                .distinctBy { it.url }
                .take(24)
        }

        // 7) Build a rich description in HTML — site descriptions are usually long-form
        //    Vietnamese sentences; we keep the original `<meta name="description">` plus
        //    a structured block so the user sees actress / tags / categories at a glance.
        val metaDesc = doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
        val description = buildString {
            if (metaDesc.isNotBlank()) {
                append("<p>").append(metaDesc).append("</p>")
            }
            append("<ul>")
            actress?.let { append("<li><b>Diễn viên:</b> ").append(it).append("</li>") }
            channel?.let { append("<li><b>Kênh:</b> ").append(it).append("</li>") }
            if (censoredChip.isNotBlank())
                append("<li><b>Loại:</b> ").append(censoredChip).append("</li>")
            if (categories.isNotEmpty())
                append("<li><b>Danh mục:</b> ").append(categories.joinToString(", ")).append("</li>")
            if (tags.isNotEmpty())
                append("<li><b>Thẻ:</b> ").append(tags.joinToString(", ")).append("</li>")
            addedDate?.let { isoDate ->
                val formatted = runCatching {
                    val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(isoDate)
                    if (parsed == null) null
                    else SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(parsed)
                }.getOrNull()
                val display = formatted ?: isoDate
                append("<li><b>Đăng ngày:</b> ").append(display).append("</li>")
            }
            views?.let { append("<li><b>Lượt xem:</b> ").append(it).append("</li>") }
            likes?.let { append("<li><b>Lượt thích:</b> ").append(it).append("</li>") }
            append("</ul>")
        }

        val tagList = (categories + tags).distinct().take(20).ifEmpty { null }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl      = poster
            this.plot           = description
            this.tags           = tagList
            this.recommendations = enhancedRecs.ifEmpty { null }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  loadLinks — direct MP4 from Cloudflare R2 (signed URLs)
    // ────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = httpGet(data)
        if (html.isBlank()) return false

        // Pull the WatchConfig JSON straight from the page — the signed MP4 URL
        // expires in ~1 hour, so we MUST refresh on every loadLinks call.
        val configJson = run {
            val m = Pattern.compile(
                "<script\\s+id=\"frontWatchConfig\"[^>]*>([\\s\\S]*?)</script>"
            ).matcher(html)
            if (!m.find()) {
                Log.w(TAG, "loadLinks: #frontWatchConfig not found")
                return false
            }
            m.group(1)?.trim().orEmpty()
        }
        if (configJson.isBlank()) return false

        val config: WatchConfig = AppUtils.tryParseJson<WatchConfig>(configJson) ?: run {
            Log.w(TAG, "loadLinks: WatchConfig parse failed")
            return false
        }

        val sources = config.playerSources.orEmpty().filter { !it.src.isNullOrBlank() }
        if (sources.isEmpty()) {
            Log.w(TAG, "loadLinks: no playerSources on $data")
            return false
        }

        sources.forEach { s ->
            val src     = s.src ?: return@forEach
            val srcAbs  = if (src.startsWith("http")) src else mainUrl + src
            val height  = s.size ?: 0
            val quality = when {
                height >= 2160 -> Qualities.P2160.value
                height >= 1080 -> Qualities.P1080.value
                height >= 720  -> Qualities.P720.value
                height >= 480  -> Qualities.P480.value
                height >= 360  -> Qualities.P360.value
                else           -> Qualities.Unknown.value
            }
            val qLabel = if (height > 0) "${height}p" else "Default"

            callback(
                newExtractorLink(
                    "Javtiful",
                    "Javtiful · $qLabel",
                    srcAbs,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = quality
                    this.headers = mapOf(
                        "User-Agent"      to UA,
                        "Referer"         to mainUrl,
                        "Accept"          to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8"
                    )
                }
            )
        }

        // Site doesn't ship subtitles. Returning true means we found at least one link.
        return true
    }
}
