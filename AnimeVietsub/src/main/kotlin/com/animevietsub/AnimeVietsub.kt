package com.animevietsub

import android.os.Build
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.EnumSet

/**
 * AnimeVietsub plugin for CloudStream 3.
 *
 * CLOUDFLARE TURNSTILE BYPASS:
 *   animevietsub.pl is behind Cloudflare Turnstile (managed challenge).
 *   The site returns HTTP 403 with `cf-mitigated: challenge` and an ~895 KB
 *   "Just a moment..." page that the browser must execute JS to solve.
 *
 *   We use WebViewResolver with `useOkhttp=false`:
 *     - WebView loads the real browser UA + executes Turnstile JS natively
 *     - WebView's CookieManager receives `cf_clearance` once challenge solves
 *     - CookieManager.flush() pushes cookies back to OkHttp jar so subsequent
 *       `app.get(...)` calls succeed
 *     - 18s timeout covers normal Turnstile solve (5–10s typical, 15s worst)
 *
 *   Each request:
 *     1. Try OkHttp with whatever cookies CookieManager currently has (fast)
 *     2. If 403 / challenge page returns → fire background WebView to (re)solve
 *     3. Retry once WebView finishes & cookies are flushed
 *
 * Player flow (verified from inline JS in watch.html):
 *   1. Watch page sets _epHash, _epID, filmInfo.filmID, filmInfo.playTech
 *   2. JS calls AnimeVsub(_epHash, filmInfo.filmID) → POST /ajax/player
 *      with form body { link: epHash, id: filmID }
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
    override var mainUrl = "https://animevietsub.pl"
    override var name = "AnimeVietsub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    // Raised so the home page has time to wait for Turnstile solve + 1 retry.
    override val getMainPageTimeoutMs: Long = 45_000L

    private val TAG = "AnimeVietsub"
    private val debugLog: (String) -> Unit = { msg -> println("[$TAG] $msg") }

    private val fallbackUserAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val USER_AGENT: String
        get() = try {
            com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent
                ?: fallbackUserAgent
        } catch (e: Throwable) {
            fallbackUserAgent
        }

    // ═════════════════════════════════════════════════════════════════════
    //  Cloudflare Turnstile bypass — WebViewResolver
    //
    //  `useOkhttp = false` is CRITICAL.
    //  Turnstile's challenge JS makes XHR/fetch to /cdn-cgi/challenge-platform/
    //  to solve the challenge. Routing those through OkHttp (which doesn't have
    //  cf_clearance yet) makes the challenge re-solve forever.
    //
    //  With `useOkhttp = false`:
    //    1. WebView loads animevietsub.pl with the challenge page
    //    2. WebView's JS engine runs Turnstile natively
    //    3. JS calls XHR → handled inside WebView (cookies are kept by WebView)
    //    4. Challenge solves → cf_clearance stored in WebView's CookieManager
    //    5. We call CookieManager.flush() to push cookies to OkHttp jar
    //    6. Subsequent `app.get(...)` carries cf_clearance → 200 OK
    //
    //  `interceptUrl = Regex(""".^""")` — matches nothing, so WebView runs
    //  to completion (timer expiry) instead of being short-circuited by an
    //  early page-load match.
    //
    //  `additionalUrls` lets us also catch the post-challenge redirect.
    // ═════════════════════════════════════════════════════════════════════

    private val cfWebView: WebViewResolver by lazy {
        WebViewResolver(
            interceptUrl = Regex(""".^"""),                       // never match early
            additionalUrls = listOf(Regex(""".*animevietsub\.pl.*""")),
            useOkhttp = false,                                    // WebView handles all
            timeout = 18_000L                                     // 18s — covers Turnstile
        )
    }

    private val cfSolving = java.util.concurrent.atomic.AtomicBoolean(false)

    private val commonHeaders by lazy {
        mapOf(
            "User-Agent"      to USER_AGENT,
            "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
    }

    /** Pulls every cookie for our origin from WebView's CookieManager (with cf_clearance if solved). */
    private fun getCookiesFromWebView(): String? {
        return try {
            val cm = CookieManager.getInstance()
            val cookies = cm.getCookie(mainUrl)
            if (!cookies.isNullOrBlank()) cookies else null
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Push current WebView cookies into the on-disk OkHttp jar so the next
     * `app.get(...)` call sees `cf_clearance`.
     */
    private fun flushCloudCookiesToOkHttp() {
        try {
            val cm = CookieManager.getInstance() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.flush()
            }
            // Give the system a moment to persist before the next OkHttp call reads cookies.
            Thread.sleep(150)
        } catch (_: Throwable) { /* best effort */ }
    }

    /**
     * Detect anything that is NOT real content:
     *   - CF Turnstile challenge page: ~895 KB containing "Just a moment..."
     *   - IP-block page:                ~798 chars ("IP Bị Chặn")
     *   - Server-error page:            ~807 chars ("Lỗi Server 5xx")
     *   - Redirect stubs / empty pages
     * Real animevietsub pages are 150 KB – 480 KB and contain .TPostMv cards.
     */
    private fun looksLikeChallenge(html: String?): Boolean {
        if (html.isNullOrBlank() || html.length < 2000) return true
        if (html.length > 700_000) return true
        val markers = listOf(
            "Just a moment...", "cf-challenge", "_cf_chl_opt", "cf-mitigated",
            "challenge-platform", "cf-browser-verification", "cf_chl_",
            "/cdn-cgi/challenge-platform/", "cf-spinner-please-wait",
            "cf-turnstile", "Turnstile", "captcha-placeholder",
            "Xác Minh", "IP Bị Chặn", "bị chặn",
            "Lỗi Server", "5xx", "unknown error"
        )
        return markers.any { html.contains(it, ignoreCase = true) }
    }

    /**
     * HTTP GET with Cloudflare Turnstile bypass.
     *
     * Strategy:
     *   1. Try OkHttp with whatever cf cookies are currently in CookieManager.
     *      (Fast path — returns immediately once Turnstile has been solved once.)
     *   2. If we still see a challenge page (size > 700K or < 2K, or markers),
     *      fire WebViewResolver to solve. We WAIT for it (blocking) so the
     *      user-facing home-page call doesn't silently return empty.
     *   3. flush() WebView → OkHttp cookie sync.
     *   4. Retry the original request with the new cookies.
     *   5. If retry still yields a challenge, throw a user-readable error
     *      (user can then open the page manually via WebView to seed cookies).
     *
     * Multi-call safety: cfSolving is an AtomicBoolean so concurrent callers
     * join the same WebView trip rather than spawning parallel webviews.
     */
    private suspend fun httpGet(url: String): String {
        // ── Phase 1: fast path with cached cookies ──────────────────────────
        val cookies1 = getCookiesFromWebView()
        val headers1 = if (cookies1 != null) commonHeaders + ("Cookie" to cookies1) else commonHeaders

        val firstTry: String? = try {
            app.get(
                url,
                headers = headers1,
                timeout = 8_000L,
                cacheTime = 0,
                cacheUnit = java.util.concurrent.TimeUnit.SECONDS
            ).text
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            debugLog("httpGet phase1 failed: $url :: ${e.message}")
            null
        }

        if (!looksLikeChallenge(firstTry)) {
            debugLog("httpGet phase1 OK: $url (${firstTry?.length ?: 0} chars)")
            return firstTry ?: ""
        }

        debugLog("Turnstile challenge on $url (html=${firstTry?.length ?: 0}) — solving via WebView")

        // ── Phase 2: solve Turnstile in a WebView ───────────────────────────
        val solved = solveTurnstileBlocking()
        flushCloudCookiesToOkHttp()

        // ── Phase 3: retry with refreshed cookies ───────────────────────────
        val cookies2 = getCookiesFromWebView()
        val headers2 = if (cookies2 != null) commonHeaders + ("Cookie" to cookies2) else commonHeaders

        val secondTry: String? = try {
            app.get(
                url,
                headers = headers2,
                timeout = 8_000L,
                cacheTime = 0,
                cacheUnit = java.util.concurrent.TimeUnit.SECONDS
            ).text
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            debugLog("httpGet phase2 failed: $url :: ${e.message}")
            null
        }

        if (!looksLikeChallenge(secondTry)) {
            debugLog("httpGet phase2 OK: $url (${secondTry?.length ?: 0} chars)")
            return secondTry ?: ""
        }

        // ── Phase 4: last-ditch single retry after a short backoff ──────────
        delay(1500L)
        val thirdTry: String? = try {
            app.get(
                url,
                headers = if (getCookiesFromWebView()?.let { true } == true)
                    commonHeaders + ("Cookie" to getCookiesFromWebView()!!)
                else commonHeaders,
                timeout = 8_000L,
                cacheTime = 0,
                cacheUnit = java.util.concurrent.TimeUnit.SECONDS
            ).text
        } catch (e: Throwable) { null }

        if (!looksLikeChallenge(thirdTry)) {
            debugLog("httpGet phase3 OK: $url (${thirdTry?.length ?: 0} chars)")
            return thirdTry ?: ""
        }

        throw ErrorLoadingException(
            "Cloudflare Turnstile chặn ($url). Hãy mở trang bằng trình duyệt một lần để xác minh."
        )
    }

    /**
     * Runs the WebView until either:
     *   - the page enters "post-challenge" state (redirect or real content), OR
     *   - the WebViewResolver timeout expires.
     *
     * Coalesces concurrent callers via [cfSolving]: only one solver runs at a
     * time, others wait for the in-flight one to finish (saves memory/CPU).
     */
    private suspend fun solveTurnstileBlocking(): Boolean {
        // Single-flight: become solver or join.
        if (!cfSolving.compareAndSet(false, true)) {
            debugLog("Join in-flight Turnstile solver")
            while (cfSolving.get()) delay(200)
            return hasCfClearance()
        }
        try {
            debugLog("WebView solver starting for $mainUrl")
            cfWebView.resolveUsingWebView(mainUrl, referer = mainUrl) { req ->
                val c = CookieManager.getInstance().getCookie(req.url.toString())
                c?.contains("cf_clearance") == true
            }
            debugLog("WebView solver finished")
            return hasCfClearance()
        } catch (t: Throwable) {
            debugLog("WebView solver error: ${t.message}")
            return false
        } finally {
            cfSolving.set(false)
        }
    }

    private fun hasCfClearance(): Boolean {
        val c = getCookiesFromWebView() ?: return false
        return c.contains("cf_clearance")
    }

    private suspend fun httpGetDoc(url: String) = Jsoup.parse(httpGet(url), url)

    // ═════════════════════════════════════════════════════════════════════
    //  Home page sections
    // ═════════════════════════════════════════════════════════════════════
    override val mainPage = mainPageOf(
        "/"                 to "Mới Cập Nhật",
        "/anime-bo/"        to "Anime Bộ",
        "/anime-le/"        to "Anime Lẻ"
    )

    // ═════════════════════════════════════════════════════════════════════
    //  URL helpers
    // ═════════════════════════════════════════════════════════════════════
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        var u = url.trim().replace("\\/", "/")
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:$u"
        val base = mainUrl.removeSuffix("/")
        return if (u.startsWith("/")) "$base$u" else "$base/$u"
    }

    private suspend fun fetchDoc(url: String): org.jsoup.nodes.Document? =
        runCatching { httpGetDoc(url) }.getOrNull()

    private suspend fun fetchText(url: String): String? =
        runCatching { httpGet(url).ifBlank { null } }.getOrNull()

    // ═════════════════════════════════════════════════════════════════════
    //  Description builder (HTML-formatted)
    // ═════════════════════════════════════════════════════════════════════
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
        fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
            if (!value.isNullOrBlank())
                append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
        }
        return buildString {
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
            if (genres.isNotEmpty()) addInfo("🎭", "Thể loại", genres.joinToString(", "), "#4CAF50")

            if (!description.isNullOrBlank()) {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(description.trim())
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Parse a .TPostMv card
    //  Verified HTML structure (home page):
    //    <div class="TPostMv">
    //      <div class="TPost B">
    //        <a href="https://animevietsub.pl/phim/<slug>">
    //          <div class="Image">
    //            <img src="https://cdn.animevietsub.pl/data/poster/..." />
    //            <span class="mli-eps">TẬP <i>12</i></span>
    //          </div>
    //          <div class="Title">…</div>
    //          <div class="Qlty">FHD</div>            ← optional
    //        </a>
    //      </div>
    //    </div>
    // ═════════════════════════════════════════════════════════════════════
    private fun parseAnimeCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        if (!href.contains("/phim/")) return null

        val title = a.attr("title").ifBlank { el.selectFirst(".Title")?.text() }
            ?.trim() ?: return null

        val poster = el.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank {
                img.attr("data-original").ifBlank { img.attr("src") }
            }
        }?.let { if (it.startsWith("http")) it else fixUrl(it) }

        val epCount = el.selectFirst(".mli-eps i")?.text()?.trim()?.toIntOrNull()
        val qualityText = el.selectFirst(".Qlty")?.text()?.trim().orEmpty()
        val quality = when {
            qualityText.contains("FHD", true) || qualityText.contains("HD", true) -> SearchQuality.HD
            qualityText.contains("4K", true) -> SearchQuality.HD
            qualityText.contains("CAM", true) -> SearchQuality.Cam
            else -> null
        }

        val tvType = when {
            href.contains("/anime-le/") -> TvType.Movie
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            if (quality != null) this.quality = quality
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            if (epCount != null && epCount > 0) {
                this.episodes = mutableMapOf(DubStatus.Subbed to epCount)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Main page
    //  Pagination: page 1 = "/<section>/", page N = "/<section>/trang-N.html"
    // ═════════════════════════════════════════════════════════════════════
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionPath = request.data.removeSuffix("/")
        val url = when {
            sectionPath.isEmpty() && page == 1 -> "$mainUrl/"
            sectionPath.isEmpty()              -> "$mainUrl/trang-$page.html"
            page == 1                          -> "$mainUrl$sectionPath/"
            else                               -> "$mainUrl$sectionPath/trang-$page.html"
        }

        debugLog("getMainPage: $url")
        val doc = fetchDoc(url)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val items = doc.select(".TPostMv").mapNotNull { parseAnimeCard(it) }
        val hasNext = items.isNotEmpty() && (
            doc.selectFirst(".wp-pagenavi a[href*='trang-${page + 1}.html']") != null ||
            (doc.selectFirst("a:contains(Trang Cuối)") != null && page < 196)
        )

        debugLog("  items=${items.size}, hasNext=$hasNext")
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Search — POST /tim-kiem/  (form: action="tim-kiem/" method="post", name="keyword")
    //          Server returns /tim-kiem/<query>/
    // ═════════════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/"
        debugLog("search: $searchUrl")
        val doc = fetchDoc(searchUrl) ?: return emptyList()
        val items = doc.select(".TPostMv").mapNotNull { parseAnimeCard(it) }
        debugLog("  found ${items.size} results")
        return items
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Load detail page
    // ═════════════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse {
        debugLog("load: $url")
        val doc = fetchDoc(url) ?: throw ErrorLoadingException("Không tải được trang (Cloudflare?)")

        val title = doc.selectFirst("h1.Title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Anime"

        val originalName = doc.selectFirst("h2.SubTitle")?.text()?.trim()

        val poster = doc.selectFirst(".Image img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-original").ifBlank { img.attr("src") } }
        }?.let { if (it.startsWith("http")) it else fixUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst(".Description")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

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
            val value = li.ownText().trim().ifBlank {
                li.text().substringAfter(strong).trim().removePrefix(":").trim()
            }
            val linkTexts = li.select("a").map { it.text().trim() }.filter { it.isNotBlank() }

            when (strong) {
                "Trạng thái" -> status = value.ifBlank { linkTexts.joinToString(", ") }
                "Lịch chiếu" -> schedule = value
                "Thời lượng" -> duration = value
                "Chất lượng" -> quality = li.selectFirst(".Qlty")?.text()?.trim() ?: value
                "Studio"     -> studio = linkTexts.joinToString(", ").ifBlank { value }
                "Quốc gia"   -> country = linkTexts.joinToString(", ").ifBlank { value }
                "Đạo diễn"   -> director = value
                "Season"     -> season = value
                "Rating"     -> rating = li.selectFirst(".imdb")?.text()?.trim() ?: value
                "Thể loại"   -> genres += linkTexts
            }
        }

        val releaseYear = season?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1) }

        val plot = buildBeautifulDescription(
            title, description, originalName, releaseYear,
            status, studio, genres, country, duration,
            quality, schedule, rating, director
        )

        // Episodes
        val watchUrl = doc.selectFirst("a[href*='xem-phim.html']")?.attr("href")?.let { fixUrl(it) }
        val latestEpisodes = doc.select(".InfoList li.latest_eps a[href*='/tap-']").mapNotNull { a ->
            val epHref = fixUrl(a.attr("href"))
            val epTitle = a.attr("title").ifBlank { a.text().trim() }
            val epNum = Regex("""Tập\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: a.text().trim().toIntOrNull()
            if (epHref.isNotBlank()) newEpisode(epHref) {
                this.name = "Tập ${epNum ?: epTitle}"
                this.episode = epNum
            } else null
        }.reversed()

        val episodes = when {
            latestEpisodes.isNotEmpty() -> latestEpisodes
            watchUrl != null -> listOf(newEpisode(watchUrl) { this.name = "Xem Phim" })
            else -> listOf(newEpisode(url) { this.name = "Xem Phim" })
        }

        val recommendations = doc.select(".MovieListRelated .TPostMv")
            .mapNotNull { parseAnimeCard(it) }
            .filter { it.url != url }
            .take(20)

        val isMovie = episodes.size == 1 && (url.contains("/anime-le/"))
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

    // ═════════════════════════════════════════════════════════════════════
    //  loadLinks — fetch watch.html, extract epHash + epID + filmID, then
    //              POST /ajax/player to grab the m3u8 / iframe source.
    // ═════════════════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = if (data.startsWith("http")) data else fixUrl(data)
        debugLog("loadLinks: ${watchUrl.take(120)}")
        var linkFound = false

        val watchHtml = fetchText(watchUrl)
        var epHash: String? = null
        var epID: String? = null
        var filmID: String? = null
        var playTech: String? = null

        if (watchHtml != null) {
            epHash  = Regex("""_epHash\s*=\s*['"]([^'"]+)['"]""").find(watchHtml)?.groupValues?.get(1)
            epID    = Regex("""_epID\s*=\s*(\d+)""").find(watchHtml)?.groupValues?.get(1)
                ?: Regex("""filmInfo\.episodeID\s*=\s*parseInt\s*\(\s*['"](\d+)['"]\s*\)""").find(watchHtml)?.groupValues?.get(1)
            filmID  = Regex("""filmInfo\.filmID\s*=\s*parseInt\s*\(\s*['"](\d+)['"]\s*\)""").find(watchHtml)?.groupValues?.get(1)
            playTech= Regex("""filmInfo\.playTech\s*=\s*['"](\w+)['"]""").find(watchHtml)?.groupValues?.get(1)

            debugLog("  epHash=${epHash?.take(40)}… epID=$epID filmID=$filmID playTech=$playTech")

            // Direct m3u8 in HTML
            val directM3u8s = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                .findAll(watchHtml)
                .map { it.value }
                .filter { !it.contains("blob:") }
                .toSet()
            for (u in directM3u8s) {
                if (tryM3U8Link(u, watchUrl, callback)) linkFound = true
            }

            // Iframe srcs
            val iframeSrcs = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(watchHtml)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() && !it.startsWith("about:") && !it.startsWith("javascript:") }
                .toSet()
            for (iframeUrl in iframeSrcs) {
                val full = fixUrl(iframeUrl)
                if (processEmbedUrl(full, watchUrl, callback)) linkFound = true
            }
        }

        // POST /ajax/player — exact format from pl.watchbk2.js
        if (epHash != null && filmID != null) {
            debugLog("S2: POST /ajax/player with link + id")
            if (postAjaxPlayer(epHash, filmID, watchUrl, callback)) linkFound = true
        }

        if (!linkFound) debugLog("All strategies failed for: $watchUrl")
        return linkFound
    }

    /**
     * POST /ajax/player.
     *
     * EXACT request body reverse-engineered from pl.watchbk2.js:
     *   AnimeVsub(_epHash, filmInfo.filmID) →
     *     data: { "link": <epHash>, "id": <filmID> }
     * epID is *not* sent to the server — it's only used client-side for DOM.
     */
    private suspend fun postAjaxPlayer(
        epHash: String,
        filmID: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ajaxUrl = "$mainUrl/ajax/player"
        val ajaxHeaders = mapOf(
            "User-Agent"       to USER_AGENT,
            "Referer"          to referer,
            "Origin"           to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept"           to "application/json, text/javascript, */*; q=0.01"
        )
        val params = mapOf("link" to epHash, "id" to filmID)
        return try {
            debugLog("  POST /ajax/player link=${epHash.take(40)}… id=$filmID")
            val resp = app.post(ajaxUrl, headers = ajaxHeaders, data = params)
            val body = resp.text
            debugLog("  response (${body.length} chars): ${body.take(300)}")
            if (body.isBlank()) false else parsePlayerResponse(body, referer, callback)
        } catch (e: Throwable) {
            debugLog("  POST error: ${e.message}")
            false
        }
    }

    /**
     * Parses JSON response from /ajax/player.
     *   { "playTech": "api"|"all"|"embed"|"iframe", "link": string | array, "success": 1? }
     * - "api"/"all" + array link  → each item = { file, label, type }
     * - "embed"                   → link = single mp4 string
     * - "iframe"                  → link = iframe URL (recursively extracted)
     */
    private suspend fun parsePlayerResponse(
        body: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anyFound = false
        val playTech = Regex(""""playTech"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        val success  = Regex(""""success"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull()
        debugLog("  playTech=$playTech success=$success")

        if (playTech != null) {
            when (playTech.lowercase()) {
                "api", "all" -> {
                    val linkArrayMatch = Regex(""""link"\s*:\s*\[(\{[\s\S]*?\})]""").find(body)
                    if (linkArrayMatch != null) {
                        val fileObjects = Regex("""\{[^{}]*"file"\s*:\s*"([^"]+)"[^{}]*\}""").findAll(body)
                        for (m in fileObjects) {
                            var fileUrl = m.groupValues[1].replace("\\/", "/").replace("&http", "http")
                            if (fileUrl.startsWith("http") && !fileUrl.contains("blob:")) {
                                val label = Regex(""""label"\s*:\s*"([^"]+)"""").find(m.value)?.groupValues?.get(1)
                                debugLog("  source: label=$label url=${fileUrl.take(80)}")
                                if (tryM3U8Link(fileUrl, referer, callback, label)) anyFound = true
                            }
                        }
                    } else {
                        val linkStr = Regex(""""link"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                        if (linkStr != null && (linkStr.startsWith("http") || linkStr.contains(".m3u8"))) {
                            val url = linkStr.replace("\\/", "/").replace("&http", "http")
                            if (tryM3U8Link(url, referer, callback, null)) anyFound = true
                        } else {
                            debugLog("  link is string (likely error): ${linkStr?.take(100)}")
                        }
                    }
                }
                "embed" -> {
                    val linkStr = Regex(""""link"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                    if (linkStr != null) {
                        val url = linkStr.replace("\\/", "/").replace("&http", "http")
                        if (url.startsWith("http") && tryM3U8Link(url, referer, callback, "720")) anyFound = true
                    }
                }
                "iframe" -> {
                    val linkStr = Regex(""""link"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                    if (linkStr != null) {
                        val url = linkStr.replace("\\/", "/")
                        if (url.startsWith("http") && processEmbedUrl(url, referer, callback)) anyFound = true
                    }
                }
            }
        }

        // Fallback: scan response for any media URLs
        if (!anyFound) {
            val mediaUrls = Regex("""https?://[^\s"'<>\\]+(?:\.m3u8|\.mp4)[^\s"'<>\\]*""")
                .findAll(body)
                .map { it.value.replace("\\/", "/").replace("&http", "http") }
                .filter { !it.contains("blob:") }
                .toSet()
            for (u in mediaUrls) {
                if (tryM3U8Link(u, referer, callback, null)) anyFound = true
            }
        }
        return anyFound
    }

    private suspend fun tryM3U8Link(
        m3u8Url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        label: String? = null
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer"    to referer,
                "Origin"     to mainUrl
            )
            val resp = app.get(m3u8Url, headers = headers)
            if (resp.code != 200 || !resp.text.contains("#EXTM3U")) {
                if (m3u8Url.contains(".mp4")) {
                    val q = labelToQuality(label)
                    val dn = if (label != null) "AnimeVietsub $label" else "AnimeVietsub MP4"
                    callback(newExtractorLink(name, dn, m3u8Url, ExtractorLinkType.VIDEO) {
                        this.quality = q ?: Qualities.P1080.value
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
                debugLog("  master playlist ${variants.size} variants: ${variants.map { it.first }}")
                variants.forEach { (vLabel, variantUrl, quality) ->
                    callback(newExtractorLink(name, "AVSB $vLabel", variantUrl, ExtractorLinkType.M3U8) {
                        this.quality = quality
                        this.headers = headers
                        this.referer = referer
                    })
                }
            } else {
                val q = labelToQuality(label)
                val dn = if (label != null) "AnimeVietsub $label" else "AnimeVietsub"
                callback(newExtractorLink(name, dn, m3u8Url, ExtractorLinkType.M3U8) {
                    this.quality = q ?: Qualities.P1080.value
                    this.headers = headers
                    this.referer = referer
                })
            }
            true
        } catch (e: Throwable) {
            debugLog("  m3u8 fetch error: ${e.message}")
            false
        }
    }

    private fun labelToQuality(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        val u = label.uppercase().trim()
        return when {
            u.contains("4K") || u.contains("2160") -> Qualities.P2160.value
            u.contains("2K") || u.contains("1440") -> Qualities.P1440.value
            u.contains("1080") || u.contains("FHD") -> Qualities.P1080.value
            u.contains("720") || u == "HD" -> Qualities.P720.value
            u.contains("480") || u == "SD" -> Qualities.P480.value
            u.contains("360") -> Qualities.P360.value
            else -> null
        }
    }

    /**
     * Master playlist → list of (label, url, Qualities.value).
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

    /** Fetch an embed/iframe page and pull out any m3u8/mp4 URLs it contains. */
    private suspend fun processEmbedUrl(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedHtml = try {
            app.get(embedUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer"    to referer,
                "Accept"     to "text/html,application/xhtml+xml,*/*;q=0.8"
            )).text
        } catch (e: Throwable) {
            debugLog("  embed fetch failed: ${e.message}")
            return false
        }

        val urlPatterns = listOf(
            Regex("""file\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""source\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""file\s*[:=]\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""source\s*[:=]\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']([^"']+\.mp4[^"']*)["']""")
        )
        val mediaUrls = mutableSetOf<String>()
        for (p in urlPatterns) {
            p.findAll(embedHtml).forEach { m ->
                val u = m.groupValues[1].replace("\\/", "/")
                if (u.isNotBlank() && !u.contains("blob:") && (u.startsWith("http") || u.startsWith("//"))) {
                    mediaUrls.add(if (u.startsWith("//")) "https:$u" else u)
                }
            }
            if (mediaUrls.isNotEmpty()) break
        }

        debugLog("  embed ${embedUrl.take(50)} → ${mediaUrls.size} media URLs")
        var any = false
        for (u in mediaUrls) {
            if (tryM3U8Link(u, embedUrl, callback)) any = true
        }
        return any
    }
}
