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
        registerMainAPI(JavtifulProvider())
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
    //  Cloudflare bypass
    //
    //  javtiful.com sits behind Cloudflare and serves a JS challenge to
    //  non-browser clients (especially from certain IP ranges — Vietnam
    //  mobile carriers get challenged aggressively). OkHttp alone cannot
    //  solve the challenge because it doesn't execute JavaScript.
    //
    //  WebViewResolver launches a real Android WebView, loads the URL,
    //  lets the JS challenge execute, captures the resulting HTML + cookies,
    //  and returns them. Subsequent requests to the same host reuse the
    //  cookies via OkHttp's cookie jar — fast.
    //
    //  The Regex matches javtiful.com URLs (and only those). We pass this
    //  interceptor to every app.get() call so the FIRST request to the site
    //  triggers the WebView, and all later requests are fast OkHttp calls.
    // ────────────────────────────────────────────────────────────────────

    private val cfInterceptor = WebViewResolver(
        interceptUrl = Regex("""https?://([a-z0-9-]+\.)*javtiful\.com(/.*)?"""),
        // useOkhttp = true (default): try OkHttp first, only fall back to WebView
        // if a Cloudflare challenge is detected. This keeps fast paths fast.
        // timeout = 30s: the JS challenge usually resolves in 1-3 seconds;
        // 30s is the safety ceiling for slow mobile networks.
        timeout = 30_000L
    )

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
    //  Every request goes through cfInterceptor (WebViewResolver):
    //    - First request: WebView loads the page, solves the Cloudflare JS
    //      challenge, captures cookies. Takes 2-5 seconds but only happens
    //      ONCE per app session — cookies persist in OkHttp's cookie jar.
    //    - Subsequent requests: interceptor sees a normal 200 response,
    //      passes it straight through (no WebView overhead).
    //
    //  cacheTime = 300 (5 min) ensures repeat visits to the home page are
    //  instant even if cookies expired.
    // ────────────────────────────────────────────────────────────────────

    private suspend fun httpGet(url: String): String {
        return try {
            app.get(
                url,
                headers     = commonHeaders,
                interceptor = cfInterceptor,
                timeout     = 30_000L,              // 30s — WebView challenge needs time
                cacheTime   = listCacheTime,        // cache 5 minutes
                cacheUnit   = java.util.concurrent.TimeUnit.SECONDS
            ).text
        } catch (t: Throwable) {
            Log.w(TAG, "httpGet failed: $url :: ${t.message}")
            ""
        }
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
