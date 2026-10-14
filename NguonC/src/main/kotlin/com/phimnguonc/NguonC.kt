// NguonC patch v4 (2026-09-08) + speed: /seg/ streaming 64KB + keep-alive,
// playlist cache TTL 150s per hash, Cloudflare-403 domain memo (skip step 0a).
// NguonC patch v5 (2026-10-12): tolerant AES-GCM envelope parser, direct flow now decrypts
// "aesgcm" playlists, /seg/ proxy tries several header profiles per host + logs upstream
// HTTP status (ExoPlayer 2004 = ERROR_CODE_IO_BAD_HTTP_STATUS), embed URL preferred over m3u8 field.
// NguonC patch v6 (2026-10-12): logcat unreadable on Android 17 -> in-app diagnostics: Toast
// per stage + file <externalFilesDir>/nguonc_diag.txt (see nlog / ntoast).
// NguonC patch v7 (2026-10-12): real log -> segment hosts answer plain HTTP with Cloudflare 403;
// segments are now downloaded through the Cloudflare-cleared Chromium WebView (WebSegFetcher)
// when HttpURLConnection fails.
// NguonC patch v8 (2026-10-12): v7 log -> HttpURLConnection and WebView fetch both rejected by
// Cloudflare ("Attention Required!"). Segment download tries CloudStream's OkHttp (app.baseClient)
// first, Referer = embed origin/ (as the working Stremio addon); extra "[Direct]" link lets
// ExoPlayer fetch segments with its own data source; WebView failures are classified (no-cors probe).
// NguonC patch v9 (2026-10-12): v8 log -> only streamaaa0000.png (first segment) is ever blocked;
// OkHttp downloads other segments (28/32). Preflight probes the leading segments and drops the
// blocked ones from the playlist; OkHttp gets a Range variant; successful segment URLs are logged.
// NguonC patch v9b (2026-10-13): the real page's own XHR for streamaaa0000.png is also CORS-blocked
// (StreamBrowser console, 3 videos, hosts cyin1.sbs / seouls11.amass11.top). Preflight now tries
// header/URL variants on the blocked first segment and adopts the first that passes.
// NguonC patch v10 (2026-10-13): v9b plays but starts slowly. Log: preflight blocked the link for
// 2.6 s / 9.4 s (sequential probes + 6 variants that all failed), the embed page kept running in the
// WebView during startup, bootstrap 403 retried per embedNN host. Now: parallel preflight + per-host
// memo, embed page stopped on capture, bootstrap memo per streamc.xyz, per-segment timing in the log.
// NguonC patch v11 (2026-10-13): sequential prefetch of the next 3 segments (hides slow/spiky
// per-segment latency seen on sings2.amass2.top and 1 s TTFB spikes), success toasts silenced.
// NguonC patch v12 (2026-10-14): server label bug (CloudStream fixUrl prefix), [Direct] link off by
// default, log no longer stops after 160 [Seg] lines (only success lines are capped), [Link] lines.
// NguonC patch v13 (2026-10-14): load time. Bootstrap POST skipped (always 403, 3.3 s once), preflight
// no longer blocks the link (trim=1 assumed, verified in background), DoH fallback when the system
// resolver fails for the CDN host (seen in the 17:17 log).
// NguonC patch v14 (2026-10-14): remaining wait = 1.4-2.3 s WebView capture (network bound). Playlist
// cache 150 s -> 6 h, headless pre-capture of the next episode 20 s after an episode starts,
// [WV] pageStarted/pageFinished/capture timings in the log.
package com.phimnguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import android.util.Base64
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  PERFORMANCE: CORS proxy for fast Cloudflare bypass
    //
    //  proxy.cors.sh is a Cloudflare Worker. When it fetches phim.nguonc.com,
    //  the request goes Cloudflare-edge → Cloudflare-edge (loopback), so NO
    //  Cloudflare JS challenge is served. This is dramatically faster than
    //  spinning up a WebView to solve the challenge (seconds vs milliseconds).
    //
    //  All fetch functions try the proxy FIRST. If it fails, they fall back
    //  to the original plain-HTTP → WebView chain.
    // ═══════════════════════════════════════════════════════════════════════════

    private val corsProxies = listOf(
        "https://proxy.cors.sh/",
        "",
        "https://api.allorigins.win/raw?url="
    )

    private fun proxify(url: String, proxy: String): String {
        if (proxy.isEmpty()) return url
        return if (proxy.endsWith("?url=")) {
            proxy + URLEncoder.encode(url, "UTF-8")
        } else {
            proxy + url
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PERFORMANCE: Cloudflare-bypass cookie cache + listing-page doc cache
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // The site's HTML listing pages (danh-sach/*, the-loai/*, tim-kiem) sit behind
    // Cloudflare, so a real WebViewResolver pass is needed to solve the JS
    // challenge. That's SLOW (multiple seconds to spin up a WebView + wait for the
    // challenge to resolve). The old code re-ran this WebView dance on literally
    // EVERY single HTML page request — every homepage tab, every search, and (worst
    // of all) every genre page used to compute "phim đề xuất" when opening a movie
    // (up to ~9 separate WebView loads before the movie page could even show).
    //
    // Fix: once we solve the Cloudflare challenge via WebView, we capture the
    // resulting cookies (cf_clearance etc.) and reuse them on subsequent PLAIN
    // HTTP requests (no WebView needed) for as long as they remain valid. We only
    // fall back to WebView again if a plain request comes back blocked/empty.
    // A short-lived per-URL document cache also avoids redundant network calls
    // entirely when the same listing page is requested again within a few minutes
    // (e.g. browsing multiple movies of the same genre back-to-back).
    // A mutex serializes the (rare, expensive) WebView fallback so a cold app
    // start doesn't spin up several WebViews in parallel for different tabs.

    private data class CachedDoc(val doc: Document, val time: Long)

    private val docCache = ConcurrentHashMap<String, CachedDoc>()
    private val DOC_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    private val cfCookieCache = ConcurrentHashMap<String, Map<String, String>>()
    private val cfMutex = Mutex()

    private fun looksBlocked(html: String): Boolean {
        if (html.isBlank()) return true
        val lower = html.lowercase()
        return lower.contains("just a moment") ||
               lower.contains("checking your browser") ||
               lower.contains("cf-browser-verification") ||
               lower.contains("attention required") ||
               (lower.contains("cloudflare") && lower.contains("challenge"))
    }

    /**
     * Fetch an HTML listing/genre/search page as fast as possible.
     *
     * Order of attempts:
     *  1. Short-lived cache (avoids duplicate fetches for the same URL).
     *  2. Plain HTTP GET, using any previously-captured Cloudflare cookie. This
     *     is dramatically faster than WebView and works fine once a valid
     *     clearance cookie exists.
     *  3. Plain HTTP GET with no cookie (cheap to try, sometimes works anyway).
     *  4. WebViewResolver fallback (slow) — serialized via mutex so concurrent
     *     callers (e.g. parallel genre lookups) don't all spin up a WebView at
     *     once. The resulting cookies are cached for next time.
     */
    /**
     * Fetch JSON API endpoint — direct first (fastest), proxy as fallback.
     * Direct connection to phim.nguonc.com is ~0.1s, proxy is ~2.5s.
     */
    private suspend inline fun <reified T : Any> fetchApi(url: String): T? {
        // Try direct first (fastest — phim.nguonc.com usually doesn't block)
        try {
            val res = app.get(url, headers = commonHeaders).parsedSafe<T>()
            if (res != null) return res
        } catch (_: Exception) {}
        // Fallback: CORS proxies (slower but bypasses Cloudflare if blocked)
        for (proxy in corsProxies) {
            if (proxy.isEmpty()) continue  // already tried direct above
            try {
                val proxiedUrl = proxify(url, proxy)
                val res = app.get(proxiedUrl, headers = commonHeaders).parsedSafe<T>()
                if (res != null) return res
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun fetchListingDoc(url: String): Document {
        docCache[url]?.let { cached ->
            if (System.currentTimeMillis() - cached.time < DOC_CACHE_TTL_MS) return cached.doc
        }

        suspend fun tryPlain(cookies: Map<String, String>?): Document? {
            return try {
                val resp = if (cookies != null) {
                    app.get(url, headers = commonHeaders, cookies = cookies)
                } else {
                    app.get(url, headers = commonHeaders)
                }
                val html = resp.text
                if (!looksBlocked(html)) {
                    val doc = resp.document
                    if (doc.select("table tbody tr").isNotEmpty()) {
                        docCache[url] = CachedDoc(doc, System.currentTimeMillis())
                        return doc
                    }
                }
                null
            } catch (_: Exception) { null }
        }

        // ── Fastest path: direct plain HTTP (phim.nguonc.com is ~0.1s direct) ──
        val cachedCookies = cfCookieCache[mainUrl]
        if (cachedCookies != null) {
            tryPlain(cachedCookies)?.let { return it }
        } else {
            tryPlain(null)?.let { return it }
        }

        // ── Fallback: CORS proxy (slower ~2.5s but bypasses Cloudflare if blocked) ──
        for (proxy in corsProxies) {
            if (proxy.isEmpty()) continue  // already tried direct above
            try {
                val proxiedUrl = proxify(url, proxy)
                val resp = app.get(proxiedUrl, headers = commonHeaders)
                val html = resp.text
                if (!looksBlocked(html)) {
                    val doc = resp.document
                    if (doc.select("table tbody tr").isNotEmpty()) {
                        docCache[url] = CachedDoc(doc, System.currentTimeMillis())
                        return doc
                    }
                }
            } catch (_: Exception) {}
        }

        // ── Last resort: WebView bypass, serialized ──
        return cfMutex.withLock {
            // Someone else may have solved it while we were waiting for the lock.
            docCache[url]?.let { cached ->
                if (System.currentTimeMillis() - cached.time < DOC_CACHE_TTL_MS) return@withLock cached.doc
            }
            val freshCookies = cfCookieCache[mainUrl]
            if (freshCookies != null && freshCookies != cachedCookies) {
                tryPlain(freshCookies)?.let { return@withLock it }
            }

            val interceptor = WebViewResolver(Regex(Regex.escape(url)))
            val resp = app.get(url, headers = commonHeaders, interceptor = interceptor)
            try {
                if (resp.cookies.isNotEmpty()) cfCookieCache[mainUrl] = resp.cookies
            } catch (_: Exception) {}
            val doc = resp.document
            docCache[url] = CachedDoc(doc, System.currentTimeMillis())
            doc
        }
    }

    override val mainPage = mainPageOf(
        "${API_PREFIX}api/films/phim-moi-cap-nhat"        to "Phim M\u1EDBi C\u1EADp Nh\u1EADt",
        "${API_PREFIX}api/films/danh-sach/phim-le"        to "Phim L\u1EBB",
        "${API_PREFIX}api/films/danh-sach/phim-bo"        to "Phim B\u1ED9",
        "${API_PREFIX}api/films/the-loai/hoat-hinh"       to "Anime~H.hình",
        "${API_PREFIX}api/films/the-loai/phim-18"         to "18+"
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
            if (episodeCount != null && episodeCount > 0) {
                addSub(episodeCount)
            }
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
    val hasDub    = lang.contains("Thuy\u1EBFt Minh", ignoreCase = true) ||
                     lang.contains("L\u1ED3ng Ti\u1EBFng", ignoreCase = true)

    val quality = when (item.quality?.uppercase()) {
        "FHD", "HD" -> SearchQuality.HD
        "CAM"       -> SearchQuality.Cam
        "SD"        -> SearchQuality.SD
        else        -> SearchQuality.HD
    }

    return newAnimeSearchResponse(title, href, TvType.TvSeries) {
        this.posterUrl = poster
        this.quality   = quality

        // ── Nhãn Vietsub / Thuyết Minh / Lồng Tiếng ──
        // Trước đây luôn addSub() bất kể ngôn ngữ thực tế -> mất nhãn.
        // Giờ đọc đúng "language" trả về từ API để hiển thị:
        //  - Chỉ có phụ đề           -> "Vietsub"  (Sub)
        //  - Chỉ có thuyết minh/lồng -> "Thuyết Minh" (Dub)
        //  - Có cả hai               -> "Vietsub" + "Thuyết Minh" (Sub+Dub)
        //  - Không xác định được     -> fallback về Vietsub (giữ hành vi cũ)
        val subExist = hasSub || (!hasSub && !hasDub)
        val dubExist = hasDub

        addDubStatus(
            dubExist    = dubExist,
            subExist    = subExist,
            dubEpisodes = if (dubExist) episodeCount else null,
            subEpisodes = if (subExist) episodeCount else null
        )
    }
}

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return if (request.data.startsWith(API_PREFIX)) {
            val path  = request.data.removePrefix(API_PREFIX)
            val url   = "$mainUrl/$path?page=$page"
            val res   = fetchApi<NguonCApiResponse>(url)
            val items = res?.items?.mapNotNull { parseApiItem(it) } ?: emptyList()
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } else {
            val url   = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
            // PERF: fetchListingDoc() reuses cached CF cookies / plain HTTP
            // whenever possible instead of always spinning up a full WebView.
            val doc   = fetchListingDoc(url)
            val items = doc.select("table tbody tr").mapNotNull { parseCard(it) }
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = URLEncoder.encode(query, "utf-8")

        // PERF: use the site's JSON search API instead of the HTML /tim-kiem page.
        // This avoids the same slow listing-page WebView/Cloudflare path seen in the log.
        val res = fetchApi<NguonCApiResponse>("$mainUrl/api/films/search?keyword=$keyword")

        if (!res?.items.isNullOrEmpty())
            return res!!.items!!.mapNotNull { parseApiItem(it) }

        // Backward-compatible API fallback only; do not fall back to HTML here,
        // because HTML listing fetches are exactly the source of the slow load.
        val legacyRes = fetchApi<NguonCApiResponse>("$mainUrl/api/films?keyword=$keyword")
        return legacyRes?.items?.mapNotNull { parseApiItem(it) } ?: emptyList()
    }

    /**
     * Normalize ugly API server names into clean, short labels for the picker.
     *
     * Strategy: KEYWORD DETECTION ON RAW INPUT FIRST. This handles ALL ugly
     * variants the API (or some downstream fork) might throw at us:
     *
     *   "Vietsub #1"                              → "Vietsub"
     *   "Thuyết minh #1"                          → "Thuyết Minh"
     *   "Lồng Tiếng #1"                           → "Lồng Tiếng"
     *   "https://phim.nguonc.com/Vietsub 1080p"   → "Vietsub"     (URL-prefixed)
     *   "Vietsub 1080p"                           → "Vietsub"     (quality suffix)
     *   "Server 2 (TM)"                           → "Thuyết Minh" (abbreviation)
     *
     * Falls back to URL/extension/quality stripping + Title Case if no keyword
     * matches, and finally to "Vietsub" / "Thuyết Minh" / "Server N" if blank.
     */
    private fun cleanServerName(raw: String?, fallbackIdx: Int = 0): String {
        val fallback = when (fallbackIdx) {
            0    -> "Vietsub"
            1    -> "Thuyết Minh"
            else -> "Server ${fallbackIdx + 1}"
        }
        if (raw.isNullOrBlank()) return fallback

        // ── Keyword detection on RAW input (before any cleaning) ──
        // This is the most reliable path — if the API string contains any of
        // these keywords (anywhere, in any case), we use the canonical label.
        val lower = raw.lowercase()
        when {
            lower.contains("thuyết minh") || lower.contains("thuyet minh") ||
            lower.contains("thuyét minh") || (lower.contains("tm") && lower.length <= 6) -> return "Thuyết Minh"
            lower.contains("lồng tiếng") || lower.contains("long tieng") ||
            lower.contains("lồng tieng") || lower.contains("long tiếng") -> return "Lồng Tiếng"
            lower.contains("vietsub")    || lower.contains("vsub") ||
            lower == "vs" || lower == "sub" ||
            (lower.contains("sub") && !lower.contains("thuyết") && !lower.contains("thuyet")) -> return "Vietsub"
        }

        // ── No keyword match — strip cruft and Title-Case the rest ──
        var name = raw.trim()
        // Strip URL prefix (e.g. "https://phim.nguonc.com/...")
        name = name.replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "")
        // Strip bare-domain prefix (e.g. "phim.nguonc.com/...")
        name = name.replace(Regex("""^[a-z0-9.-]+\.[a-z]{2,}/+""", RegexOption.IGNORE_CASE), "")
        // Strip file extensions + query strings
        name = name.replace(Regex("""\.(m3u8|m3u9|php|html?)(\?[^ ]*)?""", RegexOption.IGNORE_CASE), "")
        // Strip trailing "#N" (e.g. "Vietsub #1" → "Vietsub")
        name = name.replace(Regex("""\s*#\d+\s*$"""), "")
        // Strip trailing quality suffixes (e.g. "Vietsub 1080p" → "Vietsub")
        name = name.replace(Regex("""\s+(1080p|720p|480p|2160p|4k|hd|fhd|uhd|bluray|webrip)\s*$""", RegexOption.IGNORE_CASE), "")
        // Strip decorations
        name = name.trim(' ', '-', '_', '|', '#', ':', '/', '\\', '.', ',', '(', ')', '[', ']')
        // Collapse whitespace
        name = name.replace(Regex("\\s+"), " ").trim()

        if (name.isBlank()) return fallback
        return name.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    /**
     * Normalize episode names so the same episode from different servers gets
     * merged into a single Episode entry (instead of appearing twice in the
     * episode list).
     *
     *   "Tập 1", "Ep 1", "Episode 1", "1"  →  "1"
     *   "OVA"                                →  "OVA"
     */
    private fun normalizeEpName(raw: String?): String {
        if (raw.isNullOrBlank()) return "0"
        val s = raw.trim()
        // First, look for a number (handles "Tập 1", "Ep 1", "Episode 1", "1", "01")
        val numMatch = Regex("""(\d+)""").find(s)
        if (numMatch != null) return numMatch.groupValues[1].trimStart('0').ifBlank { "0" }
        // No number — return as-is (e.g., "OVA", "Special")
        return s
    }

    override suspend fun load(url: String): LoadResponse {
        val slug  = url.trim().trimEnd('/').substringAfterLast("/")
        val res   = fetchApi<NguonCDetailResponse>("$mainUrl/api/film/$slug")
        val movie = res?.movie ?: throw ErrorLoadingException("Kh\u00F4ng th\u1EC3 t\u1EA3i d\u1EEF li\u1EC7u phim")

        // ── Build episode map ──
        // epMap: epName(normalized) -> list of (cleanServerName, url)
        // Keyed by NORMALIZED episode name so the same episode from different
        // servers gets merged into one Episode entry (no duplicates).
        // Deduped by URL so we don't list the same source twice under two names.
        val epMap = linkedMapOf<String, MutableList<Pair<String, String>>>()
        movie.episodes?.forEachIndexed { idx, server ->
            val serverName = cleanServerName(server.server_name ?: server.name, idx)
            val items = server.items ?: server.list
            items?.forEach { ep ->
                val epName = normalizeEpName(ep.name)
                val embed = ep.embed?.replace("\\/", "/")?.trim() ?: ""
                val directM3u8 = ep.m3u8?.replace("\\/", "/")?.trim() ?: ""

                // Prefer direct m3u8 URL (already contains the token) over the
                // embed page URL — saves one HTTP round-trip in loadLinks().
                // v5: the current Stream Panel flow needs the `hash` of the embed URL (key
                // derivation + handshake). A token-style m3u8 URL carries no hash param, so
                // prefer the embed URL whenever it has one.
                val url2 = when {
                    embed.isNotBlank() && embed.contains("hash=") -> embed
                    directM3u8.isNotBlank()                       -> directM3u8
                    embed.isNotBlank()                            -> embed
                    else                                          -> return@forEach
                }

                val list = epMap.getOrPut(epName) { mutableListOf() }
                // Dedupe by URL — if this exact URL is already in the list (under
                // any server name), don't add it again. This is what was producing
                // "phim....vietsub, phim....thuyet minh" duplicates in the picker.
                if (list.none { it.second == url2 }) {
                    list.add(serverName to url2)
                }
            }
        }

        if (epMap.isEmpty()) throw ErrorLoadingException("Kh\u00F4ng t\u00ECm th\u1EA5y t\u1EADp phim")

        val episodes = epMap.map { (epName, embeds) ->
            // Join as "ServerName::url|ServerName::url|..." — loadLinks() will split this
            newEpisode(embeds.joinToString("|") { (sn, u) -> "$sn::$u" }) {
                this.name    = "T\u1EADp $epName"
                this.episode = epName.toIntOrNull()
            }
        }.sortedBy { it.episode ?: 0 }

        // v14: remember which episode follows which, for background pre-capture of the next playlist
        if (nextEpisodeRaw.size > 600) nextEpisodeRaw.clear()
        for (i in 0 until episodes.size - 1) {
            nextEpisodeRaw[rawEpisodeData(episodes[i].data)] = rawEpisodeData(episodes[i + 1].data)
        }

        val categories = movie.category ?: emptyMap()
        val dinhDang = categories.values.find { it.group?.name == "\u0110\u1ECBnh d\u1EA1ng" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val theLoai = categories.values.find { it.group?.name == "Th\u1EC3 lo\u1EA1i" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val namPhatHanh = categories.values.find { it.group?.name == "N\u0103m" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val quocGia = categories.values.find { it.group?.name == "Qu\u1ED1c gia" }?.list?.map { it.name }?.joinToString(", ") ?: ""

        val beautifulPlot = buildBeautifulDescription(movie, dinhDang, theLoai, namPhatHanh, quocGia)

        // IMPORTANT PERF FIX: only wait for the film detail API (`/api/film/{slug}`)
        // when opening a movie. The supplied log shows the delay comes from extra
        // listing/recommendation requests after the film data is already available
        // (SocketTimeoutException + WebViewResolver on /the-loai/...).
        // Cloudstream cannot update recommendations lazily after returning
        // LoadResponse, so we avoid all extra network work here to keep the movie
        // page instant. Main page/search still use the JSON listing APIs.
        val recommendations: List<SearchResponse> = emptyList()

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

    // ═══════════════════════════════════════════════════════════════════════════
    //  Local proxy server for segment delivery
    // ═══════════════════════════════════════════════════════════════════════════

    private val activeServers = mutableListOf<NguonCProxyServer>()

    // v5: index of the header profile (see NguonCProxyServer /seg/ handler) that last
    // succeeded per segment host, so a rejected profile is not retried on every segment.
    private val segProfileByHost = ConcurrentHashMap<String, Int>()

    // ═══════════════════════════════════════════════════════════════════════════
    //  v6 diagnostics — logcat is unreadable on the target device (Android 17), so every
    //  [NguonC] log line is ALSO appended to <externalFilesDir>/nguonc_diag.txt and the
    //  key stages are shown as Toasts.
    // ═══════════════════════════════════════════════════════════════════════════
    private val NG_BUILD = "v14"
    // v10: WebView segment fetch got 403 in every log (Chromium is blocked like the others) and the
    // kept-alive WebView only costs memory/CPU -> off. Code kept for experiments.
    private val SEG_WEBVIEW_FALLBACK = false
    private val PREFETCH_AHEAD = 3
    // v12: the proxy link works (v9b..v11 logs); the extra "[Direct]" link only cluttered the list.
    private val EMIT_DIRECT_LINK = false
    private val STREAMC_DIRECT_FLOW = false
    // v13: segment 0 was blocked and segment 1 downloadable in every preflight of every log -> assume
    // it, never wait for the probes (they now run in the background and correct the memo).
    private val ASSUMED_TRIM = 1
    private val diagLock = Any()
    @Volatile private var diagCtx: android.content.Context? = null
    @Volatile private var diagPathShown = false
    private val segLogCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val toastPending = java.util.concurrent.atomic.AtomicInteger(0)

    private fun diagContext(): android.content.Context? {
        diagCtx?.let { return it }
        val c = streamcAppContext() ?: return null
        diagCtx = c.applicationContext ?: c
        return diagCtx
    }

    // v11: info/success toasts used to show over the video; only failures are shown now.
    private val NG_TOAST_VERBOSE = false
    private fun ntoastV(msg: String) { if (NG_TOAST_VERBOSE) ntoast(msg) }

    private fun ntoast(msg: String) {
        try {
            val ctx = diagContext() ?: return
            if (toastPending.get() >= 5) return
            toastPending.incrementAndGet()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    android.widget.Toast.makeText(ctx, msg.take(160), android.widget.Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
                toastPending.decrementAndGet()
            }
        } catch (_: Throwable) {}
    }

    private fun nlog(msg: String) {
        println(msg)
        try {
            if (msg.contains("OKHTTP OK") || msg.contains("PREFETCH hit") || msg.contains("[Seg] stats")) {
                val c = segLogCount.incrementAndGet()
                if (c > 160 && c % 50 != 0) return   // v12: v11 log ended after 160 [Seg] lines and hid everything later
            }
            val ctx = diagContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = java.io.File(dir, "nguonc_diag.txt")
            synchronized(diagLock) {
                if (f.exists() && f.length() > 192 * 1024) f.writeText(f.readText().takeLast(64 * 1024))
                val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                f.appendText("$ts $NG_BUILD $msg\n")
            }
            if (!diagPathShown) {
                diagPathShown = true
                ntoastV("NguonC $NG_BUILD log: ${f.absolutePath}")
            }
        } catch (_: Throwable) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  v7: segment fetch through the Chromium WebView.
    //  Real log 2026-10-12: HttpURLConnection gets Cloudflare 403 on segment hosts
    //  (thais.hihihoho3.top, indoss15.amass15.top) and on the embed bootstrap, while the
    //  WebView flow on the same embed passes. The WebView that captured the playlist is
    //  kept alive, switched to a blank document whose base URL is the embed origin (so
    //  fetch() carries the same Origin/Referer/TLS fingerprint as the real player) and used
    //  to download segments; bytes come back through a JS bridge in 192 KB base64 chunks.
    //  Only used when plain HTTP fails (see /seg/ handler).
    // ═══════════════════════════════════════════════════════════════════════════
    private class SegJob {
        val out = java.io.ByteArrayOutputStream()
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var status = 0
        @Volatile var err: String? = null
    }

    private val segWebFetchers = LinkedHashMap<String, WebSegFetcher>()
    private val segHostWeb = ConcurrentHashMap<String, Boolean>()
    // v8: segment download method that last worked per host (0 OkHttp, 1 HttpURLConnection, 2 WebView)
    private val segMethodByHost = ConcurrentHashMap<String, Int>()
    private val segTry = java.util.concurrent.atomic.AtomicIntegerArray(3)
    private val segWin = java.util.concurrent.atomic.AtomicIntegerArray(3)
    private val segStatSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val segInflight = java.util.concurrent.atomic.AtomicInteger(0)
    private val segOkLog = java.util.concurrent.atomic.AtomicInteger(0)
    private fun segStat(method: Int, ok: Boolean) {
        segTry.incrementAndGet(method)
        if (ok) segWin.incrementAndGet(method)
        if (segStatSeq.incrementAndGet() % 20 == 0) {
            nlog("[NguonC] [Seg] stats okhttp=${segWin.get(0)}/${segTry.get(0)} urlconn=${segWin.get(1)}/${segTry.get(1)} web=${segWin.get(2)}/${segTry.get(2)}")
        }
    }
    private val segWebSeq = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var segWebOkToasted = false

    @Synchronized private fun webFetcherFor(ref: String): WebSegFetcher? = segWebFetchers[ref]

    @Synchronized private fun putWebFetcher(ref: String, f: WebSegFetcher) {
        segWebFetchers.remove(ref)?.let { if (it !== f) it.destroy() }
        segWebFetchers[ref] = f
        while (segWebFetchers.size > 4) {
            val k = segWebFetchers.keys.first()
            segWebFetchers.remove(k)?.destroy()
        }
    }

    private val segFetchBlankHtml: String =
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>seg</title></head><body><script>" +
        "window.__ngFetch=function(id,url){" +
        "fetch(url,{credentials:'omit',cache:'no-store'}).then(function(r){" +
        "if(!r.ok){__ngSeg.fail(id,r.status,'http '+r.status);return null;}" +
        "return r.arrayBuffer().then(function(buf){" +
        "var u8=new Uint8Array(buf),CH=196608;__ngSeg.begin(id,u8.length,r.status);" +
        "for(var o=0;o<u8.length;o+=CH){var e=Math.min(o+CH,u8.length),bin='';" +
        "for(var i=o;i<e;i+=8192){bin+=String.fromCharCode.apply(null,u8.subarray(i,Math.min(i+8192,e)));}" +
        "__ngSeg.chunk(id,btoa(bin));}" +
        "__ngSeg.end(id);});" +
        "}).catch(function(x){var m=String(x);" +
        "fetch(url,{mode:'no-cors',credentials:'omit',cache:'no-store'}).then(function(){" +
        "__ngSeg.fail(id,-2,m+' | no-cors opaque OK origin='+location.origin+' ref='+document.referrer);" +
        "}).catch(function(y){__ngSeg.fail(id,-3,m+' | no-cors failed '+String(y)+' origin='+location.origin);});});};" +
        "__ngSeg.ready();</script></body></html>"

    private inner class WebSegFetcher(private val wv: WebView, val origin: String) {
        private val jobs = ConcurrentHashMap<String, SegJob>()
        private val readyLatch = java.util.concurrent.CountDownLatch(1)
        private val gate = java.util.concurrent.Semaphore(2)

        @JavascriptInterface
        fun ready() { readyLatch.countDown() }

        @JavascriptInterface
        fun begin(id: String?, len: Int, status: Int) {
            val j = jobs[id ?: return] ?: return
            j.status = status
        }

        @JavascriptInterface
        fun chunk(id: String?, b64: String?) {
            val j = jobs[id ?: return] ?: return
            if (b64.isNullOrEmpty()) return
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                synchronized(j.out) { j.out.write(bytes) }
            } catch (e: Throwable) { j.err = "decode: ${e.message}" }
        }

        @JavascriptInterface
        fun end(id: String?) {
            val j = jobs[id ?: return] ?: return
            j.done.countDown()
        }

        @JavascriptInterface
        fun fail(id: String?, status: Int, msg: String?) {
            val j = jobs[id ?: return] ?: return
            j.status = status
            j.err = msg ?: "error"
            j.done.countDown()
        }

        fun fetch(url: String, timeoutMs: Long): ByteArray? {
            if (!readyLatch.await(6, java.util.concurrent.TimeUnit.SECONDS)) {
                nlog("[NguonC] [Seg] WEB fetcher not ready (blank document did not load)")
                return null
            }
            gate.acquire()
            val id = "j" + segWebSeq.incrementAndGet()
            val job = SegJob()
            jobs[id] = job
            try {
                val js = "window.__ngFetch(" + org.json.JSONObject.quote(id) + "," + org.json.JSONObject.quote(url) + ")"
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { wv.evaluateJavascript(js, null) } catch (e: Throwable) {
                        job.err = "eval: ${e.message}"
                        job.done.countDown()
                    }
                }
                if (!job.done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    nlog("[NguonC] [Seg] WEB fetch timeout ${url.takeLast(40)}")
                    return null
                }
                if (job.err != null || job.status !in 200..299) {
                    nlog("[NguonC] [Seg] WEB fetch failed status=${job.status} err=${job.err} url=...${url.takeLast(40)}")
                    return null
                }
                return synchronized(job.out) { job.out.toByteArray() }
            } finally {
                jobs.remove(id)
                gate.release()
            }
        }

        fun destroy() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { wv.stopLoading(); wv.destroy() } catch (_: Throwable) {}
            }
        }
    }

    inner class NguonCProxyServer(
        private val segReferer: String
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        val port: Int get() = serverSocket?.localPort ?: 0
        private val threadPool = java.util.concurrent.Executors.newCachedThreadPool()
        @Volatile private var _m3u8: String = ""
        @Volatile private var segFailToasted = false
        @Volatile private var segOkToasted = false
        private val okLogCount = java.util.concurrent.atomic.AtomicInteger(0)

        fun setM3U8(content: String) {
            _m3u8 = content
            // v11: remember the segment order (decoded from the /seg/ lines) for sequential prefetch
            val urls = content.lines().map { it.trim() }
                .filter { !it.startsWith("#") && it.contains("/seg/") }
                .map { java.net.URLDecoder.decode(it.substringAfter("/seg/"), "UTF-8") }
            segOrder = urls
            segIndex = urls.withIndex().associate { it.value to it.index }
        }
        private fun getM3U8(): String = _m3u8

        // ── v11 sequential prefetch ─────────────────────────────────────────────────────
        // v10 log: per segment 70-250 ms on fast hosts, but ~1.8 s (v9b, sings2.amass2.top) and
        // occasional 1 s TTFB spikes elsewhere. ExoPlayer loads one segment at a time, so a slow
        // request stalls playback. When requests are sequential (idx == previous + 1) the next
        // PREFETCH_AHEAD segments are fetched in the background and served from memory.
        // Seekbar-preview requests jump around (0817, 0409, 1225...) and never trigger it.
        @Volatile private var segOrder: List<String> = emptyList()
        @Volatile private var segIndex: Map<String, Int> = emptyMap()
        private val prefetchMap = ConcurrentHashMap<String, java.util.concurrent.FutureTask<ByteArray?>>()
        private val prefetchPool = java.util.concurrent.Executors.newFixedThreadPool(3) { r ->
            Thread(r).also { it.isDaemon = true }
        }
        private val cursorLock = Any()
        private val cursors = IntArray(4) { -100 }
        private var cursorNext = 0
        private val pfHit = java.util.concurrent.atomic.AtomicInteger(0)
        private val pfMiss = java.util.concurrent.atomic.AtomicInteger(0)

        /** true when [idx] continues one of the tracked request streams (idx == last + 1). */
        private fun trackSequential(idx: Int): Boolean {
            if (idx < 0) return false
            synchronized(cursorLock) {
                for (k in cursors.indices) {
                    if (cursors[k] >= 0 && idx == cursors[k] + 1) { cursors[k] = idx; return true }
                }
                cursorNext = (cursorNext + 1) % cursors.size
                cursors[cursorNext] = idx
                return false
            }
        }

        private fun fetchBytes(url: String): ByteArray? {
            val origin = Regex("""^(https?://[^/]+)""").find(segReferer)?.groupValues?.get(1) ?: ""
            for (ref in listOf("$origin/", segReferer)) {
                try {
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", ref)
                        .header("Accept", "*/*")
                        .build()
                    val resp = segClient.newCall(req).execute()
                    try {
                        if (resp.code in 200..299) return resp.body?.bytes()
                    } finally {
                        try { resp.close() } catch (_: Throwable) {}
                    }
                } catch (_: Exception) {}
            }
            return null
        }

        private fun schedulePrefetch(idx: Int) {
            val order = segOrder
            if (prefetchMap.size > 8) prefetchMap.clear()
            for (j in idx + 1..idx + PREFETCH_AHEAD) {
                val u = order.getOrNull(j) ?: break
                if (prefetchMap.containsKey(u)) continue
                val task = java.util.concurrent.FutureTask<ByteArray?> { fetchBytes(u) }
                if (prefetchMap.putIfAbsent(u, task) == null) prefetchPool.execute(task)
            }
        }
        @Volatile var label: String = ""
        @Volatile private var _direct: String = ""
        fun setDirect(content: String) { _direct = content }
        private fun getDirect(): String = _direct

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
                var reqRange: String? = null
                var reqUa: String? = null
                while (true) {
                    val h = input.readLine() ?: ""
                    if (h.isBlank()) break
                    val low = h.lowercase()
                    if (low.startsWith("range:")) reqRange = h.substringAfter(":").trim()
                    else if (low.startsWith("user-agent:")) reqUa = h.substringAfter(":").trim()
                }

                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val crlf = "\r\n"

                when {
                    path == "/stream.m3u8" -> {
                        val body = getM3U8().toByteArray(Charsets.UTF_8)
                        nlog("[NguonC] [Proxy] stream.m3u8 served name='$label' bytes=${body.size} port=$port")
                        output.write(("HTTP/1.1 200 OK${crlf}Content-Type: application/vnd.apple.mpegurl${crlf}Content-Length: ${body.size}${crlf}Access-Control-Allow-Origin: *${crlf}${crlf}").toByteArray())
                        output.write(body)
                    }
                    path == "/direct.m3u8" -> {
                        val body = getDirect().toByteArray(Charsets.UTF_8)
                        nlog("[NguonC] [Proxy] direct.m3u8 served name='$label' bytes=${body.size} port=$port")
                        output.write(("HTTP/1.1 200 OK${crlf}Content-Type: application/vnd.apple.mpegurl${crlf}Content-Length: ${body.size}${crlf}Access-Control-Allow-Origin: *${crlf}${crlf}").toByteArray())
                        output.write(body)
                    }
                    path.startsWith("/seg/") -> {
                        // v4 PERF: stream through, check HTTP status (a Cloudflare page must never
                        // be served as "video"), keep-alive reuse.
                        // v8: real logs (2026-10-12) -> HttpURLConnection AND the WebView fetch
                        // are rejected by Cloudflare ("Attention Required!" block page) on every
                        // segment host. Methods tried in order (the one that last worked for the
                        // host goes first): 0 = CloudStream's own OkHttp client (app.baseClient,
                        // the stack ExoPlayer itself uses), 1 = HttpURLConnection, 2 = Chromium
                        // WebView. Referer = embed ORIGIN + "/" first: that is what the working
                        // third-party addon sends (github.com/WallisSkill/streamio-hh, lib/streamc.js).
                        val segUrl = java.net.URLDecoder.decode(path.removePrefix("/seg/"), "UTF-8")
                        val segHost = runCatching { java.net.URL(segUrl).host }.getOrDefault("")
                        val segOrigin = Regex("""^(https?://[^/]+)""").find(segReferer)?.groupValues?.get(1) ?: ""
                        val wf = webFetcherFor(segReferer)
                        val t0 = System.currentTimeMillis()
                        val inflightNow = segInflight.incrementAndGet()
                        var ttfbMs = -1L

                        var ok = false
                        var headersSent = false
                        var lastCode = -1
                        var lastInfo = ""

                        fun cfInfo(ct: String?, srv: String?, cfm: String?, txt: String): String {
                            val title = Regex("<title>([^<]{0,80})</title>", RegexOption.IGNORE_CASE).find(txt)?.groupValues?.get(1)?.trim()
                            val ec = Regex("""(?:cf-error-code"?>|[Ee]rror code:?\s*)(\d{3,4})""").find(txt)?.groupValues?.get(1)
                            val h1 = Regex("<h1[^>]*>([^<]{0,80})</h1>", RegexOption.IGNORE_CASE).find(txt)?.groupValues?.get(1)?.trim()
                            return " ct=$ct srv=$srv cfm=$cfm title=$title ecode=$ec h1=$h1"
                        }

                        fun writeHead(len: Long) {
                            val head = StringBuilder("HTTP/1.1 200 OK").append(crlf)
                                .append("Content-Type: video/mp2t").append(crlf)
                                .append("Access-Control-Allow-Origin: *").append(crlf)
                            if (len > 0) head.append("Content-Length: ").append(len).append(crlf)
                            else head.append("Connection: close").append(crlf)
                            head.append(crlf)
                            output.write(head.toString().toByteArray())
                            headersSent = true
                        }

                        fun serveBytes(b: ByteArray) {
                            writeHead(b.size.toLong())
                            output.write(b)
                            output.flush()
                        }

                        // method 0: CloudStream OkHttp
                        fun viaOkHttp(variant: Int): Boolean {
                            val ref = if (variant == 1) segReferer else "$segOrigin/"
                            val rb = okhttp3.Request.Builder().url(segUrl)
                                .header("User-Agent", USER_AGENT)
                                .header("Referer", ref)
                                .header("Accept", "*/*")
                            // variant 2: "Range: bytes=0-" (the working addon asks segments with Range)
                            if (variant == 2) rb.header("Range", "bytes=0-")
                            val req = if (variant == 3) {
                                val sp = segSpecialByHost[segHost] ?: return false
                                variantRequest(sp, segUrl, segReferer)
                            } else rb.build()
                            return segClient.newCall(req).execute().use { resp ->
                                ttfbMs = System.currentTimeMillis() - t0
                                val code = resp.code
                                lastCode = code
                                if (code !in 200..299) {
                                    val txt = try { resp.peekBody(16384).string() } catch (_: Throwable) { "" }
                                    lastInfo = cfInfo(resp.header("Content-Type"), resp.header("Server"), resp.header("cf-mitigated"), txt)
                                    nlog("[NguonC] [Seg] OKHTTP $code v=$variant host=$segHost url=...${segUrl.takeLast(40)}$lastInfo")
                                    return@use false
                                }
                                val body = resp.body ?: return@use false
                                writeHead(body.contentLength())
                                val buf = ByteArray(65536)
                                var sent = 0L
                                body.byteStream().use { stream ->
                                    while (true) {
                                        val n = stream.read(buf)
                                        if (n < 0) break
                                        output.write(buf, 0, n)
                                        output.flush()
                                        sent += n
                                    }
                                }
                                if (okLogCount.incrementAndGet() <= 60) {
                                    nlog("[NguonC] [Seg] OKHTTP OK $code v=$variant bytes=$sent ttfb=${ttfbMs}ms total=${System.currentTimeMillis() - t0}ms inflight=$inflightNow range=${reqRange ?: "-"} ua=${(reqUa ?: "-").take(24)} url=...${segUrl.takeLast(26)}")
                                }
                                true
                            }
                        }

                        // method 1: HttpURLConnection
                        fun viaUrlConn(): Boolean {
                            val profs: List<Pair<String, String>> = listOf(
                                Pair(USER_AGENT, "$segOrigin/"),
                                Pair(USER_AGENT, segReferer)
                            )
                            for ((idx, p) in profs.withIndex()) {
                                var conn: java.net.HttpURLConnection? = null
                                try {
                                    conn = java.net.URL(segUrl).openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 8000
                                    conn.readTimeout    = 30000
                                    conn.setRequestProperty("User-Agent", p.first)
                                    conn.setRequestProperty("Referer", p.second)
                                    conn.setRequestProperty("Accept", "*/*")
                                    val code = conn.responseCode
                                    lastCode = code
                                    if (code !in 200..299) {
                                        var txt = ""
                                        runCatching {
                                            val es = conn.errorStream
                                            if (es != null) {
                                                val eb = ByteArray(16384)
                                                val en = es.read(eb)
                                                if (en > 0) txt = String(eb, 0, en, Charsets.UTF_8)
                                                runCatching { es.close() }
                                            }
                                        }
                                        lastInfo = cfInfo(conn.contentType, conn.getHeaderField("Server"), conn.getHeaderField("cf-mitigated"), txt)
                                        nlog("[NguonC] [Seg] URLCONN $code p=$idx host=$segHost url=...${segUrl.takeLast(40)}$lastInfo")
                                        runCatching { conn.disconnect() }
                                        continue
                                    }
                                    writeHead(conn.contentLength.toLong())
                                    val buf = ByteArray(65536)
                                    conn.inputStream.use { stream ->
                                        while (true) {
                                            val n = stream.read(buf)
                                            if (n < 0) break
                                            output.write(buf, 0, n)
                                            output.flush()
                                        }
                                    }
                                    return true
                                } catch (e: Exception) {
                                    nlog("[NguonC] [Seg] URLCONN error p=$idx host=$segHost: ${e.message}")
                                    runCatching { conn?.disconnect() }
                                    // headers already on the wire -> a second response on the same
                                    // socket would corrupt the stream; give up on this segment
                                    if (headersSent) return true
                                }
                            }
                            return false
                        }

                        // method 2: Chromium WebView (kept alive by the WebView flow)
                        fun viaWeb(): Boolean {
                            val f = wf ?: return false
                            val b = f.fetch(segUrl, 30_000L)
                            if (b == null || b.isEmpty()) return false
                            serveBytes(b)
                            return true
                        }

                        // v11: sequential request -> prefetch what comes next; serve from memory if ready
                        val idxNow = segIndex[segUrl] ?: -1
                        val sequential = trackSequential(idxNow)
                        val pf = prefetchMap.remove(segUrl)
                        if (pf != null) {
                            val tw = System.currentTimeMillis()
                            val pb = try { pf.get(25, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null }
                            if (pb != null && pb.isNotEmpty()) {
                                try {
                                    serveBytes(pb)
                                    ok = true
                                    val hits = pfHit.incrementAndGet()
                                    if (hits <= 20 || hits % 25 == 0) {
                                        nlog("[NguonC] [Seg] PREFETCH hit idx=$idxNow bytes=${pb.size} waited=${System.currentTimeMillis() - tw}ms hits=$hits miss=${pfMiss.get()}")
                                    }
                                } catch (e: Exception) {
                                    nlog("[NguonC] [Seg] PREFETCH serve error idx=$idxNow: ${e.message}")
                                    if (headersSent) ok = true
                                }
                            }
                        } else if (sequential) {
                            pfMiss.incrementAndGet()
                        }
                        if (sequential) schedulePrefetch(idxNow)

                        val pref = segMethodByHost[segHost]
                        val order: List<Int> = (if (pref != null) listOf(pref) else emptyList<Int>()) + listOf(0, 1, 2).filter { it != pref }
                        for (m in order) {
                            if (ok) break
                            if (m == 2 && wf == null) continue
                            try {
                                ok = when (m) {
                                    0 -> viaOkHttp(0) || viaOkHttp(1) || viaOkHttp(2) || viaOkHttp(3)
                                    1 -> viaUrlConn()
                                    else -> viaWeb()
                                }
                            } catch (e: Exception) {
                                nlog("[NguonC] [Seg] method $m error host=$segHost: ${e.message}")
                                if (headersSent) ok = true
                            }
                            segStat(m, ok)
                            if (ok) {
                                segMethodByHost[segHost] = m
                                if (!segOkToasted) {
                                    segOkToasted = true
                                    val mn = if (m == 0) "OkHttp" else if (m == 1) "HttpURLConnection" else "WebView"
                                    nlog("[NguonC] [Seg] first segment OK via $mn host=$segHost")
                                    ntoastV("NguonC $NG_BUILD: segment OK qua $mn ($segHost)")
                                }
                            }
                        }

                        segInflight.decrementAndGet()
                        if (!ok) {
                            if (!segFailToasted) {
                                segFailToasted = true
                                val ec = Regex("ecode=(\\S+)").find(lastInfo)?.groupValues?.get(1)
                                val h1 = Regex("h1=(.*)$").find(lastInfo)?.groupValues?.get(1)?.take(40)
                                ntoast("NguonC $NG_BUILD: segment HTTP $lastCode $segHost ecode=$ec $h1")
                            }
                            output.write("HTTP/1.1 502 Bad Gateway${crlf}X-Upstream-Status: $lastCode${crlf}Content-Length: 0${crlf}${crlf}".toByteArray())
                        }
                    }
                    else -> {
                        nlog("[NguonC] [Proxy] 404 for path=${path.take(80)}")
                        output.write("HTTP/1.1 404 Not Found${crlf}${crlf}".toByteArray())
                    }
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

    /** Rewrite m3u8 segment URLs to go through our local proxy */
    /** v8: playlist with the segment URLs left absolute (relative ones resolved) so ExoPlayer
     *  downloads segments itself through CloudStream's data source (OkHttp/Cronet). */
    private fun directM3U8(m3u8: String, baseUrl: String): String =
        m3u8.lines().joinToString("\n") { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("http")) line
            else if (baseUrl.isNotEmpty()) baseUrl.trimEnd('/') + "/" + t
            else line
        }

    private fun rewriteM3U8(m3u8: String, proxyBase: String, m3u8BaseUrl: String = ""): String {
        return m3u8.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isBlank()) {
                line
            } else if (trimmed.startsWith("http")) {
                "$proxyBase/seg/${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
            } else if (trimmed.isNotEmpty()) {
                val resolvedUrl = if (m3u8BaseUrl.isNotEmpty()) {
                    "${m3u8BaseUrl.trimEnd('/')}/$trimmed"
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  v9 preflight. v8 log (2026-10-12): every failing segment request is for
    //  streamaaa0000.png (the FIRST segment) while CloudStream's OkHttp downloads other
    //  segments fine (stats okhttp=28/32; the second request stream is CloudStream's
    //  M3u8PreviewGenerator, which reads middle segments, never index 0 first). Cloudflare
    //  answers seg 0000 with the "Sorry, you have been blocked" page for every client tried.
    //  Before the link is handed out, probe the leading segments; if the first ones are
    //  blocked but a later one is downloadable, drop the blocked ones from the playlist.
    // ═══════════════════════════════════════════════════════════════════════════
    // v9b: header/URL variants tried on a blocked first segment (diagnostic + automatic adoption).
    private val segVariantNames = listOf("noReferer", "cors", "browser", "img", "cacheBust", "fullRef")
    private val segSpecialByHost = ConcurrentHashMap<String, String>()
    private val streamBrowserUA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun variantRequest(name: String, url: String, embedUrl: String): okhttp3.Request {
        val origin = Regex("""^(https?://[^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: ""
        val b = okhttp3.Request.Builder()
        var u = url
        when (name) {
            "noReferer" -> {
                b.header("User-Agent", USER_AGENT)
            }
            "cors" -> {
                b.header("User-Agent", USER_AGENT).header("Origin", origin)
                    .header("Referer", "$origin/").header("Accept", "*/*")
            }
            "browser" -> {
                // what the real page's XHR sends (headers seen in the StreamBrowser HAR)
                b.header("User-Agent", streamBrowserUA).header("Origin", origin)
                    .header("Referer", "$origin/").header("Accept", "*/*")
                    .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("sec-ch-ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Android WebView\";v=\"150\"")
                    .header("sec-ch-ua-mobile", "?1").header("sec-ch-ua-platform", "\"Android\"")
                    .header("Sec-Fetch-Site", "cross-site").header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
            }
            "img" -> {
                b.header("User-Agent", streamBrowserUA).header("Referer", "$origin/")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("Sec-Fetch-Site", "cross-site").header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Dest", "image")
            }
            "cacheBust" -> {
                u = url + "?cb=" + System.nanoTime()
                b.header("User-Agent", USER_AGENT).header("Referer", "$origin/").header("Accept", "*/*")
            }
            else -> { // "fullRef"
                b.header("User-Agent", USER_AGENT).header("Referer", embedUrl).header("Accept", "*/*")
            }
        }
        return b.url(u).build()
    }

    private fun probeReq(client: okhttp3.OkHttpClient, req: okhttp3.Request): Pair<Int, String> {
        try {
            val r = client.newCall(req).execute()
            try {
                val code = r.code
                var info = ""
                if (code !in 200..299) {
                    val txt = try { r.peekBody(16384).string() } catch (_: Throwable) { "" }
                    val h1 = Regex("<h1[^>]*>([^<]{0,60})</h1>", RegexOption.IGNORE_CASE).find(txt)?.groupValues?.get(1)?.trim()
                    info = " h1=$h1"
                } else {
                    info = " acao=${r.header("Access-Control-Allow-Origin")} ct=${r.header("Content-Type")}"
                }
                return Pair(code, info)
            } finally {
                try { r.close() } catch (_: Throwable) {}
            }
        } catch (e: Exception) {
            return Pair(-1, " err=${e.message}")
        }
    }

    private fun probeSeg(client: okhttp3.OkHttpClient, url: String, ref: String, range: String?): Pair<Int, String> {
        val b = okhttp3.Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", ref)
            .header("Accept", "*/*")
        if (range != null) b.header("Range", range)
        return probeReq(client, b.build())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  v13 DNS fallback. Log 2026-10-14 17:17:05-09: "Unable to resolve host jps14.hihihoho4.top: No
    //  address associated with hostname" (HttpURLConnection) and UnknownHostException (OkHttp) for the
    //  CDN host while the playback was running -> segment requests failed for ~8 s. The system
    //  resolver is tried first (CloudStream's own DNS setting is kept); only on UnknownHostException
    //  the host is resolved through DNS-over-HTTPS (1.1.1.1 / 8.8.8.8, addressed by IP).
    // ═══════════════════════════════════════════════════════════════════════════
    private val dohCache = ConcurrentHashMap<String, Pair<List<java.net.InetAddress>, Long>>()
    private val dohClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun dohLookup(host: String): List<java.net.InetAddress> {
        val cached = dohCache[host]
        if (cached != null && System.currentTimeMillis() - cached.second < 5 * 60_000L) return cached.first
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$host&type=A",
            "https://8.8.8.8/resolve?name=$host&type=A"
        )
        for (u in endpoints) {
            try {
                val req = okhttp3.Request.Builder().url(u).header("accept", "application/dns-json").build()
                val resp = dohClient.newCall(req).execute()
                try {
                    if (resp.code !in 200..299) continue
                    val txt = resp.body?.string() ?: continue
                    val arr = org.json.JSONObject(txt).optJSONArray("Answer") ?: continue
                    val out = ArrayList<java.net.InetAddress>()
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONObject(i)
                        if (a.optInt("type") == 1) out.add(java.net.InetAddress.getByName(a.getString("data")))
                    }
                    if (out.isNotEmpty()) {
                        dohCache[host] = Pair(out, System.currentTimeMillis())
                        nlog("[NguonC] [DNS] system resolver failed for $host -> DoH ${out.joinToString { it.hostAddress ?: "?" }}")
                        return out
                    }
                } finally {
                    try { resp.close() } catch (_: Throwable) {}
                }
            } catch (_: Exception) {}
        }
        nlog("[NguonC] [DNS] DoH fallback found nothing for $host")
        return emptyList()
    }

    private val segDns: okhttp3.Dns by lazy {
        val base = app.baseClient.dns
        object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                try {
                    return base.lookup(hostname)
                } catch (e: java.net.UnknownHostException) {
                    val ips = dohLookup(hostname)
                    if (ips.isNotEmpty()) return ips
                    throw e
                }
            }
        }
    }

    /** CloudStream's OkHttp client + DoH fallback. Shares pool/dispatcher with app.baseClient. */
    private val segClient: okhttp3.OkHttpClient by lazy { app.baseClient.newBuilder().dns(segDns).build() }

    // v10: host -> (segments to drop, timestamp). Segment 0 was blocked on every host/video in
    // every log, so the verdict is remembered for 30 min and later plays skip the probes.
    private val segTrimByHost = ConcurrentHashMap<String, Pair<Int, Long>>()

    /** Returns Pair(trimForProxy, trimForDirect): number of leading segments to drop. Never blocks. */
    private suspend fun preflightSegments(segUrls: List<String>, referer: String): Pair<Int, Int> {
        if (segUrls.isEmpty()) return Pair(0, 0)
        val host0 = runCatching { java.net.URL(segUrls[0]).host }.getOrDefault("")
        val memo = segTrimByHost[host0]
        val fresh = memo != null && System.currentTimeMillis() - memo.second < 30 * 60_000L
        val assumed = if (memo != null && fresh) memo.first else ASSUMED_TRIM
        nlog("[NguonC] [Preflight] host=$host0 trim=$assumed (${if (fresh) "memo" else "assumed, verifying in background"})")
        if (!fresh) {
            CoroutineScope(Dispatchers.IO).launch {
                try { verifySegments(segUrls, referer, host0, assumed) }
                catch (e: Exception) { nlog("[NguonC] [Preflight] verify error: ${e.message}") }
            }
        }
        return Pair(assumed, assumed)
    }

    /** Probes seg0..2 in parallel (also warms DNS/TLS to the CDN host) and stores the verdict. */
    private suspend fun verifySegments(segUrls: List<String>, referer: String, host0: String, assumed: Int) {
        val origin = Regex("""^(https?://[^/]+)""").find(referer)?.groupValues?.get(1) ?: return
        val ref = "$origin/"
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val client = segClient.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            val n = minOf(3, segUrls.size)
            val results = coroutineScope {
                (0 until n).map { i -> async { probeSeg(client, segUrls[i], ref, null) } }.awaitAll()
            }
            var firstOk = -1
            for (i in 0 until n) {
                nlog("[NguonC] [Preflight] seg$i plain -> ${results[i].first}${results[i].second}")
                if (firstOk < 0 && results[i].first in 200..299) firstOk = i
            }
            if (firstOk < 0) {
                for (i in n until minOf(6, segUrls.size)) {
                    val (c, info) = probeSeg(client, segUrls[i], ref, null)
                    nlog("[NguonC] [Preflight] seg$i plain -> $c$info")
                    if (c in 200..299) { firstOk = i; break }
                }
            }
            val trim = if (firstOk > 0) firstOk else 0
            if (firstOk >= 0) segTrimByHost[host0] = Pair(trim, System.currentTimeMillis())
            nlog("[NguonC] [Preflight] verify firstOk=$firstOk trim=$trim assumed=$assumed ms=${System.currentTimeMillis() - t0}" +
                if (firstOk >= 0 && trim != assumed) "  MISMATCH (memo updated for the next play)" else "")
        }
    }

    /** Drop the first [count] segments (and the tags attached to them) from a media playlist. */
    private fun trimLeadingSegments(m3u8: String, count: Int): String {
        if (count <= 0) return m3u8
        val out = ArrayList<String>()
        val pending = ArrayList<String>()
        var idx = 0
        for (line in m3u8.lines()) {
            val t = line.trim()
            if (t.isEmpty()) { out.add(line); continue }
            if (t.startsWith("#EXTINF") || t.startsWith("#EXT-X-BYTERANGE") ||
                t.startsWith("#EXT-X-DISCONTINUITY") || t.startsWith("#EXT-X-PROGRAM-DATE-TIME")) {
                pending.add(line)
            } else if (t.startsWith("#")) {
                out.add(line)
            } else {
                if (idx >= count) { out.addAll(pending); out.add(line) }
                pending.clear()
                idx++
            }
        }
        return out.joinToString("\n")
    }

    /** Create proxy server and register m3u8 link */
    private suspend fun registerM3U8Link(
        m3u8Content: String, referer: String, m3u8BaseUrl: String,
        serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!m3u8Content.contains("#EXTM3U")) return false
        run {
            val segs = m3u8Content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            nlog("[NguonC] registerM3U8Link: segments=${segs.size} first=${segs.firstOrNull()?.take(110)} base=${m3u8BaseUrl.take(60)} referer=${referer.take(70)}")
            val firstHost = segs.firstOrNull()?.let { Regex("^https?://([^/]+)").find(it)?.groupValues?.get(1) } ?: "relative"
            ntoastV("NguonC $NG_BUILD: playlist OK ${segs.size} seg @ $firstHost")
        }
        // v9: probe the leading segments, drop the ones Cloudflare blocks (see preflightSegments)
        val segUrlsAbs = m3u8Content.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if (it.startsWith("http") || m3u8BaseUrl.isEmpty()) it else m3u8BaseUrl.trimEnd('/') + "/" + it }
        val (trimProxy, trimDirect) = try { preflightSegments(segUrlsAbs, referer) } catch (e: Exception) {
            nlog("[NguonC] [Preflight] error: ${e.message}")
            Pair(0, 0)
        }
        if (trimProxy > 0 || trimDirect > 0) {
            ntoastV("NguonC $NG_BUILD: bỏ $trimProxy segment đầu (bị Cloudflare chặn)")
        } else {
            ntoastV("NguonC $NG_BUILD: preflight - segment đầu tải được hoặc không có segment nào tải được")
        }
        val server = NguonCProxyServer(referer)
        server.label = serverName
        server.start(); activeServers.add(server)
        val proxyBase = "http://127.0.0.1:${server.port}"
        server.setM3U8(rewriteM3U8(trimLeadingSegments(m3u8Content, trimProxy), proxyBase, m3u8BaseUrl))
        callback(newExtractorLink("NguonC", serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
            quality = Qualities.P1080.value
            headers = mapOf("User-Agent" to USER_AGENT)
        })
        // v8: second link, segments fetched directly by ExoPlayer with Referer = embed origin
        if (EMIT_DIRECT_LINK) {
            val directReferer = Regex("""^(https?://[^/]+)""").find(referer)?.groupValues?.get(1)?.let { "$it/" } ?: referer
            server.setDirect(directM3U8(trimLeadingSegments(m3u8Content, trimDirect), m3u8BaseUrl))
            callback(newExtractorLink("NguonC", "$serverName [Direct]", "$proxyBase/direct.m3u8", ExtractorLinkType.M3U8) {
                quality = Qualities.P1080.value
                this.referer = directReferer
                headers = mapOf("User-Agent" to USER_AGENT)
            })
        }
        nlog("[NguonC] [Link] emitted name='$serverName' url=$proxyBase/stream.m3u8 segs=${segUrlsAbs.size} trim=$trimProxy first=${segUrlsAbs.getOrNull(trimProxy)?.takeLast(70)}")
        return true
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Token & key extraction helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun padBase64(s: String): String {
        val padNeeded = (4 - s.length % 4) % 4
        return s + "=".repeat(padNeeded)
    }

    /** Convert token to URL-safe base64 (no + / =) for use in URL paths */
    private fun toUrlSafeBase64(token: String): String {
        return token.replace('+', '-').replace('/', '_').trimEnd('=')
    }

    /** Decode the token (base64 JSON) -> {"h":"32hex","t":"39-40hex"} */
    private fun decodeStreamcToken(token: String): Triple<String, String, String>? {
        val normalized = token.replace('-', '+').replace('_', '/').let { padBase64(it) }
        val decoders = listOf(
            Pair("URL_SAFE") { Base64.decode(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) },
            Pair("DEFAULT")  { Base64.decode(normalized, Base64.DEFAULT) }
        )
        for ((method, decoder) in decoders) {
            try {
                val decoded = String(decoder(), Charsets.UTF_8)
                val h = Regex(""""h"\s*:\s*"([a-fA-F0-9]+)"""").find(decoded)?.groupValues?.get(1)
                val t = Regex(""""t"\s*:\s*"([a-fA-F0-9]+)"""").find(decoded)?.groupValues?.get(1)
                if (h != null && t != null) {
                    nlog("[NguonC] Decoded token ($method): h=${h.length}hex, t=${t.length}hex")
                    return Triple(decoded, h, t)
                }
            } catch (_: Exception) { continue }
        }
        return null
    }

    /** Extract sUb (token), hD (hash), kX (encryption key) from data-obf attribute
     *  Tries both standard and URL-safe base64 decoding */
    private fun extractObfData(html: String): Triple<String?, String?, String?>? {
        val obfBase64 = Regex("""data-obf="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""data-obf='([^']+)'""").find(html)?.groupValues?.getOrNull(1)
            ?: return null

        // Try multiple base64 decode methods
        val decodeMethods = listOf<Pair<String, () -> ByteArray>>(
            "DEFAULT" to { Base64.decode(obfBase64, Base64.DEFAULT) },
            "URL_SAFE" to { Base64.decode(obfBase64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) },
            "NORMALIZED" to {
                val normalized = padBase64(obfBase64.replace('-', '+').replace('_', '/'))
                Base64.decode(normalized, Base64.DEFAULT)
            }
        )

        for ((methodName, decoder) in decodeMethods) {
            try {
                val obfJson = String(decoder(), Charsets.UTF_8)
                nlog("[NguonC] data-obf decoded ($methodName): ${obfJson.take(200)}")

                // Try multiple field name patterns (site may use different names)
                val tokenPatterns = listOf("sUb", "sub", "token", "tkn", "sU", "Su")
                val hashPatterns = listOf("hD", "hd", "hash", "hsh")
                val keyPatterns = listOf("kX", "kx", "key", "aesKey", "encKey", "kE", "Ke")

                var sUb: String? = null
                var hD: String? = null
                var kX: String? = null

                for (p in tokenPatterns) {
                    val match = Regex(""""${Regex.escape(p)}"\s*:\s*"([^"]+)"""").find(obfJson)
                    if (match != null) { sUb = match.groupValues[1]; break }
                }
                for (p in hashPatterns) {
                    val match = Regex(""""${Regex.escape(p)}"\s*:\s*"([^"]+)"""").find(obfJson)
                    if (match != null) { hD = match.groupValues[1]; break }
                }
                for (p in keyPatterns) {
                    val match = Regex(""""${Regex.escape(p)}"\s*:\s*"([^"]+)"""").find(obfJson)
                    if (match != null) { kX = match.groupValues[1]; break }
                }

                if (sUb != null || hD != null || kX != null) {
                    nlog("[NguonC] data-obf ($methodName): sUb=${sUb?.take(20)}... hD=${hD?.take(20)}... kX=${kX?.take(20)}...")
                    return Triple(sUb, hD, kX)
                }

                // If no named fields found, try to extract any JSON value that looks like a token
                if (sUb == null && hD == null && kX == null) {
                    // Look for any base64-like string that could be a token
                    val allValues = Regex(""""(\w+)"\s*:\s*"([A-Za-z0-9_+/=-]{20,})"""").findAll(obfJson).toList()
                    for (match in allValues) {
                        val key = match.groupValues[1]
                        val value = match.groupValues[2]
                        nlog("[NguonC] data-obf found field: $key = ${value.take(20)}...")
                        // Heuristic: if value looks like base64 JSON token, use it as sUb
                        if (value.length > 30 && (value.contains("=") || value.any { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/" })) {
                            if (sUb == null) {
                                sUb = value
                                nlog("[NguonC] data-obf heuristic: using $key as sUb")
                            }
                        }
                    }
                    if (sUb != null) return Triple(sUb, hD, kX)
                }
            } catch (e: Exception) {
                nlog("[NguonC] data-obf decode error ($methodName): ${e.message}")
            }
        }
        return null
    }

    /** Find token in HTML - returns token string (without .m3u8/.m3u9 extension) or null */
    private fun findTokenInHTML(html: String): String? {
        val patterns = listOf(
            // JWPlayer file config with token
            Regex(""""file"\s*:\s*"([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)""""),
            Regex("""file\s*[:=]\s*["']([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)["']"""),
            // Any quoted string ending in .m3u8/.m3u9
            Regex("""["']([A-Za-z0-9_+/=\\-]{20,})\.(m3u8|m3u9)["']"""),
            // Variable assignment
            Regex("""(?:var|let|const)\s+\w+\s*=\s*["']([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)["']"""),
            // URL in HTML src/href
            Regex("""(?:src|href)\s*=\s*["']([^"']*?(m3u8|m3u9))["']"""),
            // JWPlayer setup with sources array
            Regex(""""file"\s*:\s*"([^"]+\.(m3u8|m3u9))""""),
            // Any URL-like path ending in .m3u8/.m3u9
            Regex("""/([A-Za-z0-9_+/=\\-]{20,})\.(m3u8|m3u9)""")
        )
        for (pattern in patterns) {
            val matches = pattern.findAll(html)
            for (match in matches) {
                val token = match.groupValues.getOrNull(1)
                if (token != null && token.length > 10 && !token.startsWith("blob:") && !token.startsWith("http")) {
                    nlog("[NguonC] Found token in HTML: ${token.take(20)}... (len=${token.length}, pattern=${pattern.pattern.take(40)})")
                    return token
                }
            }
        }
        return null
    }

    /** Find kX (encryption key) from data-obf, inline scripts, or JS files */
    private suspend fun findEncryptionKey(html: String, embedDomain: String, referer: String): String? {
        // Source 1: data-obf
        val obfData = extractObfData(html)
        if (obfData != null && obfData.third != null) {
            nlog("[NguonC] Found kX from data-obf: ${obfData.third}")
            return obfData.third
        }

        // Source 2: Inline scripts
        val scripts = Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.toList()
        for (script in scripts) {
            val match = Regex("""(?:kX|key|aesKey|encKey)\s*[:=]\s*["']([a-fA-F0-9]{32,})["']""").find(script)
            if (match != null) {
                val key = match.groupValues[1]
                if (key.length >= 32) {
                    nlog("[NguonC] Found kX in inline script: ${key.take(20)}...")
                    return key
                }
            }
        }

        // Source 3: External JS files
        for (jsName in listOf("player1", "debug", "player", "config", "app")) {
            val jsMatch = Regex("""src=["']([^"']*${jsName}[^"']*\.js[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
            if (jsMatch != null) {
                val jsPath = jsMatch.groupValues[1]
                val jsUrl = if (jsPath.startsWith("http")) jsPath else "$embedDomain/$jsPath"
                try {
                    val jsContent = app.get(jsUrl, headers = mapOf(
                        "Referer" to referer, "User-Agent" to USER_AGENT
                    )).text
                    // Try different key patterns
                    for (pattern in listOf(
                        Regex("""(?:kX|key|aesKey|encKey|secretKey|decryptKey)\s*[:=]\s*["']([a-fA-F0-9]{32,})["']"""),
                        Regex("""["']([a-fA-F0-9]{32})["']\s*,?\s*//\s*(?:key|aes|encrypt|decrypt)""", RegexOption.IGNORE_CASE)
                    )) {
                        val match = pattern.find(jsContent)
                        if (match != null) {
                            nlog("[NguonC] Found kX in $jsName.js: ${match.groupValues[1].take(20)}...")
                            return match.groupValues[1]
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }

    /** Parse player1.js to find token construction logic and extract token data */
    private suspend fun extractTokenFromJS(html: String, embedDomain: String, referer: String, urlHash: String?): Pair<String?, String?>? {
        // Find all script src URLs
        val scriptSrcs = Regex("""src=["']([^"']*\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.toList()

        nlog("[NguonC] Found ${scriptSrcs.size} script sources in HTML")

        for (jsPath in scriptSrcs) {
            val jsUrl = if (jsPath.startsWith("http")) jsPath else "$embedDomain/$jsPath"
            try {
                val jsContent = app.get(jsUrl, headers = mapOf(
                    "Referer" to referer, "User-Agent" to USER_AGENT
                )).text
                nlog("[NguonC] Downloaded JS: ${jsPath.take(40)} (${jsContent.length} chars)")

                // Look for token-like patterns: base64 strings that end up in URLs
                // Pattern 1: variable = "BASE64STRING" where BASE64 is long enough to be a token
                val b64Matches = Regex("""["']([A-Za-z0-9_+/=-]{40,})["']""").findAll(jsContent)
                for (match in b64Matches) {
                    val candidate = match.groupValues[1]
                    // Check if this could be a base64 JSON token
                    try {
                        val decoded = String(Base64.decode(padBase64(candidate.replace('-', '+').replace('_', '/')), Base64.DEFAULT), Charsets.UTF_8)
                        if (decoded.contains("\"h\"") && decoded.contains("\"t\"")) {
                            nlog("[NguonC] Found token in JS: ${candidate.take(20)}... (decoded: ${decoded.take(50)})")
                            // Also try to find kX in the same JS
                            val kXMatch = Regex("""(?:kX|key|aesKey)\s*[:=]\s*["']([a-fA-F0-9]{32,})["']""").find(jsContent)
                            return Pair(candidate, kXMatch?.groupValues?.get(1))
                        }
                    } catch (_: Exception) {}
                }

                // Pattern 2: URL construction like "/${token}.m3u9"
                val urlPattern = Regex("""["']/([A-Za-z0-9_+/=-]+)\.(m3u8|m3u9)["']""").find(jsContent)
                if (urlPattern != null) {
                    val tokenStr = urlPattern.groupValues[1]
                    if (tokenStr.length > 20) {
                        nlog("[NguonC] Found token-like URL in JS: ${tokenStr.take(20)}...")
                        val kXMatch = Regex("""(?:kX|key|aesKey)\s*[:=]\s*["']([a-fA-F0-9]{32,})["']""").find(jsContent)
                        return Pair(tokenStr, kXMatch?.groupValues?.get(1))
                    }
                }

                // Pattern 3: Look for btoa/atob usage with token construction
                val tokenConstruct = Regex("""(?:btoa|atob|Base64)\s*\(\s*(?:JSON\.stringify\s*\(\s*\{[^}]*"h"[^}]*\})""").find(jsContent)
                if (tokenConstruct != null) {
                    nlog("[NguonC] Found token construction in JS: ${tokenConstruct.value.take(60)}...")
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  AES-GCM Decryption
    // ═══════════════════════════════════════════════════════════════════════════

    private fun decryptStreamcM3u8(content: String, kX: String, token: String? = null): String? {
        try {
            val ivMatch = Regex("""#ENC-AESGCM;iv=([a-f0-9A-F]+)""").find(content) ?: return null
            val ivHex = ivMatch.groupValues[1]
            val iv = hexToBytes(ivHex)
            nlog("[NguonC] Decrypt: iv=${ivHex.take(16)}... (${ivHex.length}hex), kX=$kX")

            // Parse B65 header - supports both #EXT-X-B65:offset-length and #EXT-X-B65:offset
            val b65Match = Regex("""#EXT-X-B65:(\d+)(?:-(\d+))?""").find(content)
            val offset = b65Match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val length = b65Match?.groupValues?.get(2)?.toIntOrNull() ?: 0

            // Extract base64 ciphertext
            val dataLines = content.lines().dropWhile { it.startsWith("#") || it.isBlank() }
            val b64Data = dataLines.joinToString("").trim()
            if (b64Data.isEmpty()) {
                nlog("[NguonC] Decrypt: no data after headers")
                return null
            }

            var encData = Base64.decode(b64Data, Base64.DEFAULT)
            if (offset > 0 || length > 0) {
                encData = if (length > 0) encData.copyOfRange(offset, minOf(offset + length, encData.size))
                          else if (offset > 0) encData.copyOfRange(offset, encData.size)
                          else encData
            }

            nlog("[NguonC] Decrypt: encData size=${encData.size}, offset=$offset, length=$length")

            // Build key candidates - ordered by likelihood
            val keyCandidates = mutableListOf<Pair<String, ByteArray>>()

            // 1. kX as UTF-8 bytes (JS: TextEncoder.encode(kX))
            val kxUtf8 = kX.toByteArray(Charsets.UTF_8)
            if (kxUtf8.size == 16 || kxUtf8.size == 32) keyCandidates.add(Pair("kX-utf8", kxUtf8))
            if (kxUtf8.size > 32) keyCandidates.add(Pair("kX-utf8-first32", kxUtf8.copyOf(32)))
            if (kxUtf8.size > 16) keyCandidates.add(Pair("kX-utf8-first16", kxUtf8.copyOf(16)))

            // 2. kX as hex-decoded bytes
            if (kX.length >= 32 && kX.length % 2 == 0 && kX.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                try {
                    val hexBytes = hexToBytes(kX)
                    if (hexBytes.size == 16 || hexBytes.size == 32) keyCandidates.add(Pair("kX-hexBytes", hexBytes))
                } catch (_: Exception) {}
            }

            // 3. Token-derived keys
            if (token != null) {
                val decoded = decodeStreamcToken(token)
                if (decoded != null) {
                    val (_, h, t) = decoded
                    if (t.length == 64) try { keyCandidates.add(Pair("t-hexBytes(AES256)", hexToBytes(t))) } catch (_: Exception) {}
                    if (h.length == 32) try { keyCandidates.add(Pair("h-hexBytes(AES128)", hexToBytes(h))) } catch (_: Exception) {}
                    if (t.length >= 32) try { keyCandidates.add(Pair("t-first16", hexToBytes(t.take(32)))) } catch (_: Exception) {}
                    if (t.length == 80) try { keyCandidates.add(Pair("t-hexBytes40-first32", hexToBytes(t.take(64)))) } catch (_: Exception) {}
                }
                try {
                    val sha256 = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                    keyCandidates.add(Pair("SHA256(token).16", sha256.copyOf(16)))
                    keyCandidates.add(Pair("SHA256(token).32", sha256))
                } catch (_: Exception) {}
                // Also try token itself as UTF-8 key
                val tokenUtf8 = token.toByteArray(Charsets.UTF_8)
                if (tokenUtf8.size == 16 || tokenUtf8.size == 32) keyCandidates.add(Pair("token-utf8", tokenUtf8))
                if (tokenUtf8.size > 16) keyCandidates.add(Pair("token-utf8-first16", tokenUtf8.copyOf(16)))
                if (tokenUtf8.size > 32) keyCandidates.add(Pair("token-utf8-first32", tokenUtf8.copyOf(32)))
            }

            // 4. If kX is short, try padding it
            if (kX.length in 17..31) {
                // Pad kX hex to 32 chars
                val padded = kX.padEnd(32, '0')
                try { keyCandidates.add(Pair("kX-padded-32hex", hexToBytes(padded))) } catch (_: Exception) {}
            }

            nlog("[NguonC] Trying ${keyCandidates.size} key candidates...")

            for ((desc, keyBytes) in keyCandidates) {
                if (keyBytes.size != 16 && keyBytes.size != 32) continue
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
                    val result = String(cipher.doFinal(encData), Charsets.UTF_8)
                    if (result.contains("#EXTM3U")) {
                        nlog("[NguonC] Decryption OK with $desc!")
                        return result
                    }
                } catch (_: Exception) {}
            }

            // 5. Try with different IV sizes (96 bits = 12 bytes is standard for GCM)
            // But some implementations might use different IV sizes
            if (iv.size != 12) {
                nlog("[NguonC] IV size is ${iv.size} bytes (expected 12), trying standard size...")
                for ((desc, keyBytes) in keyCandidates) {
                    if (keyBytes.size != 16 && keyBytes.size != 32) continue
                    try {
                        // Use first 12 bytes of IV
                        val truncatedIv = iv.copyOf(12)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, truncatedIv))
                        val result = String(cipher.doFinal(encData), Charsets.UTF_8)
                        if (result.contains("#EXTM3U")) {
                            nlog("[NguonC] Decryption OK with $desc (truncated IV)!")
                            return result
                        }
                    } catch (_: Exception) {}
                }
            }

            nlog("[NguonC] All decryption attempts failed")
        } catch (e: Exception) {
            nlog("[NguonC] Decryption error: ${e.message}")
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HTTP fetch helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Fetch embed page HTML - tries multiple methods for maximum reliability */
    private suspend fun fetchEmbedHTML(embedUrl: String, embedDomain: String): String? {
        // Method 1: Direct HTTP GET (fastest, no WebView overhead)
        try {
            val resp = app.get(embedUrl, headers = mapOf(
                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ))
            val html = resp.text
            // Very lenient check - accept HTML if it has any sign of being an embed page
            if (html.contains("data-obf") || html.contains("jwplayer") || html.contains("player1") ||
                html.contains("streamc") || html.contains("embed") || html.contains("<video") ||
                html.contains("m3u8") || html.contains("m3u9")) {
                nlog("[NguonC] Direct HTML OK: ${html.length} chars")
                return html
            }
            // Even if no known markers, return it if it's a substantial HTML page
            if (html.length > 500 && html.contains("<")) {
                nlog("[NguonC] Direct HTML (no markers but substantial): ${html.take(200)}")
                return html
            }
            nlog("[NguonC] Direct HTML not embed page: ${html.take(100)}")
        } catch (e: Exception) {
            nlog("[NguonC] Direct HTML failed: ${e.message}")
        }

        // Method 2: WebView with embedPageInterceptor (for CloudFlare bypass)
        try {
            val resp = app.get(embedUrl, interceptor = embedPageInterceptor, headers = mapOf(
                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
            ))
            val html = resp.text
            if (html.contains("data-obf") || html.contains("jwplayer") || html.contains("player1") ||
                html.contains("streamc") || html.contains("embed") || html.contains("<video") ||
                html.contains("m3u8") || html.contains("m3u9") || html.length > 500) {
                nlog("[NguonC] WebView embed HTML OK: ${html.length} chars")
                return html
            }
        } catch (e: Exception) {
            nlog("[NguonC] WebView embed HTML failed: ${e.message}")
        }

        // Method 3: WebView with cfInterceptor (broader, for tough CloudFlare)
        try {
            val resp = app.get(embedUrl, interceptor = cfInterceptor, headers = mapOf(
                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
            ))
            val html = resp.text
            if (html.contains("data-obf") || html.contains("jwplayer") || html.contains("player1") ||
                html.contains("streamc") || html.contains("embed") || html.contains("<video") ||
                html.contains("m3u8") || html.contains("m3u9") || html.length > 500) {
                nlog("[NguonC] cfInterceptor HTML OK: ${html.length} chars")
                return html
            }
        } catch (e: Exception) {
            nlog("[NguonC] cfInterceptor HTML failed: ${e.message}")
        }

        nlog("[NguonC] All HTML fetch methods failed for: $embedUrl")
        return null
    }

    /** Direct HTTP fetch of m3u8/m3u9 URL */
    private suspend fun fetchDirect(url: String, referer: String, origin: String): String? {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "*/*"
        )
        // Try direct fetch
        try {
            val resp = app.get(url, headers = headers)
            val content = resp.text
            if (content.contains("#EXTM3U") || content.contains("#ENC-AESGCM")) {
                nlog("[NguonC] Direct fetch OK: ${url.take(60)}")
                return content
            }
            nlog("[NguonC] Direct fetch response: ${content.take(80)}")
        } catch (_: Exception) {}
        // Try with cfInterceptor
        try {
            val resp = app.get(url, headers = headers, interceptor = cfInterceptor)
            val content = resp.text
            if (content.contains("#EXTM3U") || content.contains("#ENC-AESGCM")) {
                nlog("[NguonC] cfInterceptor fetch OK: ${url.take(60)}")
                return content
            }
        } catch (_: Exception) {}
        return null
    }

    /** Try fetching m3u8 with various token formats */
    private suspend fun tryFetchWithToken(
        token: String, embedDomain: String, referer: String,
        kX: String?, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Generate all possible URL variations for the token
        val urlVariations = mutableListOf<Pair<String, String>>()

        // 1. URL-safe base64 (standard for streamc.xyz)
        val urlSafeToken = toUrlSafeBase64(token)
        urlVariations.add(Pair(urlSafeToken, "urlSafe"))

        // 2. URL-encoded original token (in case + and / need encoding)
        if (token != urlSafeToken) {
            urlVariations.add(Pair(URLEncoder.encode(token, "UTF-8"), "urlEncoded"))
        }

        // 3. Original token as-is (in case it's already URL-safe)
        if (token != urlSafeToken) {
            urlVariations.add(Pair(token, "original"))
        }

        // 4. Normalized base64 (with + and / instead of - and _)
        val normalizedToken = token.replace('-', '+').replace('_', '/')
        if (normalizedToken != token && normalizedToken != urlSafeToken) {
            urlVariations.add(Pair(URLEncoder.encode(normalizedToken, "UTF-8"), "normalized"))
        }

        for ((tokenVariant, desc) in urlVariations) {
            for (ext in listOf(".m3u9", ".m3u8")) {
                val m3u8Url = "$embedDomain/$tokenVariant$ext"
                nlog("[NguonC] Trying $desc token: ${m3u8Url.take(80)}")
                val m3u8Content = fetchDirect(m3u8Url, referer, embedDomain)

                if (m3u8Content != null) {
                    // Plain m3u8
                    if (m3u8Content.contains("#EXTM3U") && !m3u8Content.contains("#ENC-AESGCM")) {
                        nlog("[NguonC] Got plain m3u8 with $desc + $ext!")
                        val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
                        if (registerM3U8Link(m3u8Content, referer, m3u8Base, serverName, callback)) {
                            return true
                        }
                    }
                    // Encrypted m3u8
                    if (m3u8Content.contains("#ENC-AESGCM")) {
                        nlog("[NguonC] Got encrypted m3u8 with $desc + $ext, trying decrypt...")
                        if (kX != null) {
                            val decrypted = decryptStreamcM3u8(m3u8Content, kX, tokenVariant)
                            if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                nlog("[NguonC] Decrypted m3u8 OK!")
                                if (registerM3U8Link(decrypted, referer, "", serverName, callback)) {
                                    return true
                                }
                            }
                        } else {
                            nlog("[NguonC] No kX for decryption")
                        }
                    }
                }
            }
        }
        return false
    }

    /**
     * NEW WORKING FLOW (reverse-engineered 2026-06):
     * The streamc.xyz server now requires a two-step access handshake that the
     * existing tryFetchWithToken() does not implement, which is why every direct
     * fetch returns "Unauthorized: Invalid or expired session token.".
     *
     *   Step 1 (POST /{token}, Content-Type: application/json)
     *     -> {"ok":true,"xat":"<64 hex chars>"}
     *   Step 2 (GET /{token}.m3u9?xat=<xat>)
     *     -> PLAIN unencrypted M3U8 playlist (segments are .png disguised .ts)
     *
     * The .m3u9 mobile endpoint returns an UNENCRYPTED playlist, so none of the
     * AES-GCM / kX / data-obf decryption logic in the legacy fallbacks is needed.
     * Segments live on hihihoho*.top / amass*.top and require
     * Referer: https://embedXX.streamc.xyz/embed.php?hash=<hash> (the embed URL).
     */
    private suspend fun tryMobileM3U8Flow(
        embedUrl: String,
        embedDomain: String,
        token: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The token from data-obf `sUb` is already URL-safe base64 (no + / =),
        // but the access endpoint is happy with either form. Use it verbatim.
        val accessTokenPath = token

        // ── Step 1: POST /{token} to obtain the `xat` access token ──
        val postUrl = "$embedDomain/$accessTokenPath"
        val postHeaders = mapOf(
            "Referer"      to embedUrl,
            "Origin"       to embedDomain,
            "Content-Type" to "application/json",
            "Accept"       to "application/json, text/plain, */*",
            "User-Agent"   to USER_AGENT
        )
        nlog("[NguonC] [MobileFlow] POST $postUrl")
        val accessResp = try {
            app.post(postUrl, headers = postHeaders).parsedSafe<StreamcAccessResponse>()
        } catch (e: Exception) {
            nlog("[NguonC] [MobileFlow] POST failed: ${e.message}")
            null
        }
        val xat = accessResp?.xat
        if (xat.isNullOrBlank()) {
            nlog("[NguonC] [MobileFlow] No xat in response (ok=${accessResp?.ok})")
            return false
        }
        nlog("[NguonC] [MobileFlow] Got xat: ${xat.take(20)}... (len=${xat.length})")

        // ── Step 2: GET /{token}.m3u9?xat=<xat> to fetch the PLAIN m3u8 ──
        val m3u8Url = "$embedDomain/$accessTokenPath.m3u9?xat=$xat"
        val getHeaders = mapOf(
            "Referer"    to embedUrl,
            "Origin"     to embedDomain,
            "Accept"     to "*/*",
            "User-Agent" to USER_AGENT
        )
        nlog("[NguonC] [MobileFlow] GET $m3u8Url")
        val m3u8Content = try {
            app.get(m3u8Url, headers = getHeaders).text
        } catch (e: Exception) {
            nlog("[NguonC] [MobileFlow] GET failed: ${e.message}")
            null
        }
        if (m3u8Content.isNullOrBlank() || !m3u8Content.contains("#EXTM3U")) {
            nlog("[NguonC] [MobileFlow] Response is not a valid m3u8 (len=${m3u8Content?.length ?: 0})")
            // Try .m3u8 extension as a fallback (desktop endpoint, may be encrypted)
            val altUrl = "$embedDomain/$accessTokenPath.m3u8?xat=$xat"
            nlog("[NguonC] [MobileFlow] Trying .m3u8: $altUrl")
            val altContent = try { app.get(altUrl, headers = getHeaders).text } catch (_: Exception) { null }
            if (altContent.isNullOrBlank() || !altContent.contains("#EXTM3U")) {
                nlog("[NguonC] [MobileFlow] .m3u8 also failed")
                return false
            }
            // If the .m3u8 endpoint returned a PLAIN m3u8, register it.
            // (If it's encrypted #ENC-AESGCM, we can't decrypt it here — let the
            // legacy fallbacks try.)
            if (altContent.contains("#ENC-AESGCM")) {
                nlog("[NguonC] [MobileFlow] .m3u8 is encrypted, deferring to legacy fallbacks")
                return false
            }
            return registerM3U8Link(altContent, embedUrl, "", serverName, callback)
        }

        if (m3u8Content.contains("#ENC-AESGCM")) {
            nlog("[NguonC] [MobileFlow] .m3u9 unexpectedly encrypted, deferring to legacy fallbacks")
            return false
        }

        nlog("[NguonC] [MobileFlow] Got plain m3u8 (${m3u8Content.length} chars, ${m3u8Content.split("\n").count { it.startsWith("#EXTINF") }} segments)")
        // Segments in this playlist are absolute https URLs (e.g. on hihihoho*.top),
        // so pass empty m3u8BaseUrl. The proxy will rewrite them and fetch with
        // Referer = embedUrl, which is exactly what the segment CDN requires.
        return registerM3U8Link(m3u8Content, embedUrl, "", serverName, callback)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  NEW FLOW (reverse-engineered 2026-09): Stream Panel "lite-15-cf-free"
    //  + "guard-7" anti-bot protection on streamc.xyz
    //
    //  What changed on the site (this is why the old data-obf flow broke):
    //    1. GET /embed.php?hash=... now returns an EMPTY shell page — no
    //       data-obf, no token, only a bootstrap JSON + cdn-start.js loader.
    //    2. The page itself POSTs {"action":"bootstrap"} to the same embed.php
    //       (Origin header REQUIRED, else 403 {"error":"wrong_origin"}) and
    //       receives {bootstrap:<JWT ~3min>, nonce, turnstileEnabled, ...}.
    //    3. When turnstileEnabled=true, the page must solve a Cloudflare
    //       Turnstile challenge (widget #turnstile-container, action
    //       "playback", cData=nonce, 150s timeout) — impossible via plain HTTP.
    //    4. Then it POSTs {"action":"issue", bootstrap, turnstile_response,
    //       "playlist_format":"hls"|"aesgcm", pretty_url:true, path_chunks:true}
    //       -> {playlist:<url>, issuedAt, expiresAt}.
    //       Mobile UA => "hls" => PLAIN #EXTM3U playlist.
    //       No/invalid Turnstile token => 403 {"error":"verification_failed"}.
    //    5. The playlist URL is a "pretty URL": same embed origin, path made of
    //       80..5500 base64url chars, NO .m3u8/.m3u9 extension, query empty or
    //       "?d" (or /conf.php?t=...). Extension-less ON PURPOSE to defeat naive
    //       scrapers — hence the dedicated WebView regex below.
    // ═══════════════════════════════════════════════════════════════════════════

    /** Mobile UA so the server hands out the PLAIN "hls" playlist format */
    private val streamcMobileUA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    /** Serializes the cheap HTTP handshake so parallel servers don't trip 429 */
    private val streamcHttpMutex = Mutex()

    // ── v4 PERF: short-lived replay cache ──────────────────────────────────
    // The bootstrap JWT lives ~180s and CloudStream re-calls loadLinks more
    // often than people think (reopen episode, quality switch, second source
    // entry, player retry). Within 150s of an issue we replay the SAME
    // playlist instead of re-running the whole WebView/Turnstile flow again.
    // Key: video hash → (expiresAtEpochMs, playlistUrl, rawBody)
    private val streamcPlaylistCache =
        java.util.concurrent.ConcurrentHashMap<String, Triple<Long, String, String>>()

    // v4 PERF: remember which embed domains answered a Cloudflare 403 to our
    // plain HTTP bootstrap — skip the doomed 0a POST (~0.6s) for 10 minutes.
    private val streamcCfBlockedUntil =
        java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** v10: embed5/embed13/embed18... all answer the same 403, key the memo on streamc.xyz. */
    private fun cfKey(domain: String): String {
        val host = domain.removePrefix("https://").removePrefix("http://").substringBefore("/")
        val parts = host.split(".")
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  v14 next-episode pre-capture. v13 log: tap -> link is 1.4-2.3 s, all of it the WebView capture
    //  (network bound, nothing left to trim). While an episode plays, the playlists of the NEXT episode
    //  are captured headless (no dialog) and cached, so opening it takes the cached path.
    // ═══════════════════════════════════════════════════════════════════════════
    private val nextEpisodeRaw = ConcurrentHashMap<String, String>()
    private val prewarmRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** CloudStream's newEpisode(url) applies fixUrl(); undo it (see loadLinks). */
    private fun rawEpisodeData(d: String): String =
        if (d.startsWith("$mainUrl/") && d.contains("::")) d.removePrefix("$mainUrl/") else d

    private fun prewarmNext(rawData: String) {
        val next = nextEpisodeRaw[rawData] ?: return
        if (!prewarmRunning.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // not while the current episode is starting (WebView creation hitches the UI thread)
                kotlinx.coroutines.delay(20_000L)
                for (entry in next.split("|")) {
                    val parts = entry.trim().split("::", limit = 2)
                    if (parts.size != 2) continue
                    val name = cleanServerName(parts[0])
                    val url = parts[1]
                    val hash = Regex("""[?&]hash=([a-fA-F0-9]{8,})""").find(url)?.groupValues?.get(1) ?: continue
                    if (streamcCacheGet(hash) != null) continue
                    val domain = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: continue
                    val t0 = System.currentTimeMillis()
                    val ok = withTimeoutOrNull(25_000L) {
                        tryStreamcWebViewFlow(url, domain, name, { _ -> }, true)
                    } ?: false
                    nlog("[NguonC] [Prewarm] name='$name' hash=${hash.take(8)} ok=$ok ms=${System.currentTimeMillis() - t0}")
                }
            } catch (e: Exception) {
                nlog("[NguonC] [Prewarm] error: ${e.message}")
            } finally {
                prewarmRunning.set(false)
            }
        }
    }

    private fun streamcCacheGet(hash: String?): Pair<String, String>? {
        if (hash.isNullOrBlank()) return null
        val e = streamcPlaylistCache[hash] ?: return null
        return if (System.currentTimeMillis() < e.first) Pair(e.second, e.third)
        else { streamcPlaylistCache.remove(hash); null }
    }

    private fun streamcCachePut(hash: String?, url: String, rawBody: String) {
        if (hash.isNullOrBlank() || !rawBody.contains("#EXTM3U")) return
        if (streamcPlaylistCache.size >= 16) streamcPlaylistCache.clear()
        // v14: was 150 s. The playlist response carries "cache-control: public, s-maxage=1884584" and
        // "x-stream-playlist-reuse: 1884584" (~22 days); segment URLs need no token -> 6 h is safe and
        // makes a replayed / pre-captured episode start without the 1.4-2.3 s WebView step.
        streamcPlaylistCache[hash] =
            Triple(System.currentTimeMillis() + 6L * 3_600_000L, url, rawBody)
    }

    /** Minimal JSON string getter — avoids depending on JSON library signatures */
    private fun streamcJsonString(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json) ?: return null
        return m.groupValues[1].replace("\\/", "/")
    }

    /** Plain GET with the mobile identity (playlist fetches) */
    private fun streamcGet(url: String, embedDomain: String, referer: String): String? {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Origin", embedDomain)
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("User-Agent", streamcMobileUA)
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = try { stream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
            try { conn.disconnect() } catch (_: Exception) {}
            if (code in 200..299) text else null
        } catch (e: Exception) {
            nlog("[NguonC] [StreamcNew] GET failed: ${e.message}")
            null
        }
    }

    /** Raw JSON POST via HttpURLConnection (full control over Origin/UA) */
    private fun streamcPostJson(
        url: String, embedDomain: String, referer: String, body: String
    ): Pair<Int, String?> {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Origin", embedDomain)
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("User-Agent", streamcMobileUA)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = try { stream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
            try { conn.disconnect() } catch (_: Exception) {}
            Pair(code, text)
        } catch (e: Exception) {
            nlog("[NguonC] [StreamcNew] POST failed: ${e.message}")
            Pair(-1, null)
        }
    }

    /**
     * STEP 0a (NEW): fast plain-HTTP handshake.
     * bootstrap -> issue (only possible when the video has Turnstile DISABLED)
     * -> playlist -> register. If Turnstile is enabled we bail out instantly
     * (the token cannot be obtained over plain HTTP) and the WebView flow runs.
     */
    private suspend fun tryStreamcDirectFlow(
        embedUrl: String, embedDomain: String,
        serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean = streamcHttpMutex.withLock {
        try {
            // v13: every bootstrap POST in every log was answered with the Cloudflare 403 page
            // (17:16:52 -> 17:16:55 = 3.3 s wasted on the first play) -> skip it.
            if (!STREAMC_DIRECT_FLOW) return@withLock false
            // v4 PERF: if Cloudflare 403'd this domain recently, the plain HTTP
            // handshake is hopeless — skip straight to the WebView flow.
            val cfBlockedUntil = streamcCfBlockedUntil[cfKey(embedDomain)] ?: 0L
            if (System.currentTimeMillis() < cfBlockedUntil) {
                nlog("[NguonC] [StreamcNew] 0a skipped (Cloudflare blocked this domain recently)")
                return@withLock false
            }
            nlog("[NguonC] [StreamcNew] 0a: bootstrap POST ${embedUrl.take(70)}")
            val (code1, body1) = streamcPostJson(
                embedUrl, embedDomain, embedUrl,
                """{"action":"bootstrap","referrer":"$mainUrl/"}"""
            )
            if (code1 != 200 || body1 == null) {
                nlog("[NguonC] [StreamcNew] bootstrap HTTP $code1: ${body1?.take(120)}")
                if (code1 == 403) {
                    streamcCfBlockedUntil[cfKey(embedDomain)] = System.currentTimeMillis() + 10 * 60_000L
                }
                return@withLock false
            }
            val err = streamcJsonString(body1, "error")
            if (err != null) {
                nlog("[NguonC] [StreamcNew] bootstrap error: $err")
                return@withLock false
            }
            val bootstrapJwt = streamcJsonString(body1, "bootstrap")
            if (bootstrapJwt.isNullOrBlank()) {
                nlog("[NguonC] [StreamcNew] no bootstrap JWT in response")
                return@withLock false
            }
            val turnstile = Regex(""""turnstileEnabled"\s*:\s*true""").containsMatchIn(body1)
            nlog("[NguonC] [StreamcNew] bootstrap OK (turnstileEnabled=$turnstile)")
            if (turnstile) return@withLock false  // needs a real browser challenge

            // Turnstile disabled on this video -> request the playlist directly
            val (code2, body2) = streamcPostJson(
                embedUrl, embedDomain, embedUrl,
                """{"action":"issue","bootstrap":"$bootstrapJwt","turnstile_response":"","playlist_format":"hls","pretty_url":true,"path_chunks":true}"""
            )
            val playlist = body2?.let { streamcJsonString(it, "playlist") }
            if (code2 != 200 || playlist.isNullOrBlank()) {
                nlog("[NguonC] [StreamcNew] issue HTTP $code2: ${body2?.take(120)}")
                return@withLock false
            }
            nlog("[NguonC] [StreamcNew] issue OK: ${playlist.take(90)}")

            val content = streamcGet(playlist, embedDomain, embedUrl) ?: return@withLock false
            if (!content.contains("#EXTM3U")) {
                nlog("[NguonC] [StreamcNew] unexpected playlist content (${content.take(60)})")
                return@withLock false
            }
            // v5: server may answer "aesgcm" even for the mobile UA -> decrypt via the shared
            // handler (plain + encrypted) instead of rejecting #ENC-AESGCM.
            val directHash = Regex("""[?&]hash=([a-fA-F0-9]{8,})""").find(embedUrl)?.groupValues?.get(1)
            streamcRegisterCaptured(playlist, content, directHash, embedUrl, serverName, callback)
        } catch (e: Exception) {
            nlog("[NguonC] [StreamcNew] direct flow error: ${e.message}")
            false
        }
    }

    /**
     * STEP 0b (NEW v2): run the REAL embed page in a WebView and let it perform
     * bootstrap -> guard.js -> Cloudflare Turnstile -> issue -> playlist entirely
     * by itself. Key facts from the 08-09 incident log that shape this design:
     *  - EVERY plain-HTTP request from the user's device (okhttp/HttpURLConnection)
     *    to streamc.xyz gets 403-challenged by Cloudflare, so the playlist BODY can
     *    NOT be fetched outside the WebView.
     *  - With the default Android WebView UA the page asks for the "aesgcm"
     *    playlist format (player.js: r=/iphone|ipad|macintosh/ test on UA).
     *    The envelope is 4 lines: #EXTM3U / #ENC-AESGCM;iv=<24 hex> /
     *    #EXT-X-B65:0-138 / base64(ciphertext || 16-byte GCM tag).
     *  - Key derivation (player.js decryptM3U8): AES-256-GCM key =
     *    HMAC-SHA256(key="stream-derive-v1", msg=videoHash) where videoHash is
     *    the `hash` query parameter of the embed URL. IV = 12 bytes from hex.
     * A tiny JS hook is injected into the page BEFORE its scripts run; it
     * forwards every fetch/XHR response that looks like a playlist or an issue
     * JSON to the Kotlin bridge, so no second fetch (and no Cloudflare risk)
     * is ever needed.
     */
    private suspend fun tryStreamcWebViewFlow(
        targetUrl: String, embedDomain: String,
        serverName: String, callback: (ExtractorLink) -> Unit,
        captureOnly: Boolean = false   // v14: only fetch + cache the playlist (next-episode pre-capture)
    ): Boolean {
        val flowT0 = System.currentTimeMillis()
        var pageStartedLogs = 0
        var webView: WebView? = null
        var dialog: android.app.Dialog? = null
        var fetcher: WebSegFetcher? = null      // v7: keeps this WebView alive for segment fetching
        var keepWebView = false
        try {
            val videoHash = Regex("""[?&]hash=([a-fA-F0-9]{8,})""").find(targetUrl)?.groupValues?.get(1)
            nlog("[NguonC] [StreamcNew] 0b: WebView fetch-hook flow on ${targetUrl.take(70)}")

            val ctx = streamcAppContext()
            if (ctx == null) {
                nlog("[NguonC] [StreamcNew] no app context available for WebView")
                return false
            }

            val captures = Channel<Pair<String, String>>(Channel.UNLIMITED)
            val issuePlaylistUrl = java.util.concurrent.atomic.AtomicReference<String?>(null)

            withContext(Dispatchers.Main) {
                val wv = WebView(ctx)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.allowFileAccess = false
                wv.settings.allowContentAccess = false
                wv.settings.mediaPlaybackRequiresUserGesture = false
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                // v7: JS bridge used later (blank document) to download segments via Chromium
                val segF = WebSegFetcher(wv, Regex("""^(https?://[^/]+)""").find(targetUrl)?.groupValues?.get(1) ?: embedDomain)
                fetcher = segF
                wv.addJavascriptInterface(segF, "__ngSeg")
                wv.addJavascriptInterface(
                    StreamcCaptureBridge { url, body ->
                        // v3: always log the arrival — proves the JS->Kotlin path works
                        nlog("[NguonC] [StreamcNew] bridge capture: +${System.currentTimeMillis() - flowT0}ms url=${url.take(60)} len=${body.length} head=${body.take(12)}")
                        val t = body.trim()
                        if (t.startsWith("#EXTM3U")) {
                            captures.trySend(url to body)
                        } else if (t.contains("\"playlist\"")) {
                            streamcJsonString(t, "playlist")?.let { issuePlaylistUrl.compareAndSet(null, it) }
                        }
                    },
                    "__ngcBridge"
                )
                wv.webChromeClient = WebChromeClient()
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        if (pageStartedLogs++ < 2) nlog("[NguonC] [WV] pageStarted +${System.currentTimeMillis() - flowT0}ms ${url?.take(60)}")
                        view?.evaluateJavascript(streamcHookJs, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url != null && !url.startsWith("about:")) nlog("[NguonC] [WV] pageFinished +${System.currentTimeMillis() - flowT0}ms ${url.take(60)}")
                        view?.evaluateJavascript(streamcHookJs, null)
                    }
                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?
                    ) {
                        try {
                            val u = request?.url?.toString() ?: return
                            if (u.contains("streamaaa")) {
                                nlog("[NguonC] [Seg] WEBVIEW http ${errorResponse?.statusCode} srv=${errorResponse?.responseHeaders?.get("Server")} url=...${u.takeLast(40)}")
                            }
                        } catch (_: Throwable) {}
                    }
                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        try {
                            val u = request?.url?.toString() ?: return
                            if (u.contains("streamaaa")) {
                                nlog("[NguonC] [Seg] WEBVIEW net error ${error?.errorCode} ${error?.description} url=...${u.takeLast(40)}")
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // Show the page in a real dialog window when an Activity is
                // reachable: gives Turnstile a genuine window environment and,
                // in the rare interactive case, lets the user tap the checkbox.
                if (!captureOnly && ctx is android.app.Activity && !ctx.isFinishing) {
                    try {
                        val dlg = android.app.Dialog(ctx)
                        dlg.setContentView(
                            wv,
                            android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        dlg.window?.setLayout(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        dlg.setCanceledOnTouchOutside(false)
                        dlg.setOnDismissListener { captures.close() }  // user closed -> abort flow
                        dlg.show()
                        dialog = dlg
                    } catch (e: Exception) {
                        nlog("[NguonC] [StreamcNew] dialog unavailable (${e.message}), running headless")
                    }
                }

                wv.loadUrl(targetUrl, mapOf("Referer" to "$mainUrl/"))
            }

            var registered = withTimeoutOrNull(60_000L) {
                var ok = false
                var tries = 0
                while (!ok && tries < 8) {
                    tries++
                    val (capUrl, capBody) = captures.receive()
                    // v10: playlist in hand -> stop the embed page. Left running, jwplayer keeps
                    // downloading the 15 MB pre-roll ad and requests segments (seen as
                    // "WEBVIEW http 403 ... streamaaa0000.png" during preflight) while we start playback.
                    try {
                        withContext(Dispatchers.Main) {
                            webView?.stopLoading()
                            webView?.loadUrl("about:blank")
                        }
                    } catch (_: Throwable) {}
                    if (captureOnly) {
                        streamcCachePut(videoHash, capUrl, capBody)
                        ok = true
                    } else {
                        ok = streamcRegisterCaptured(capUrl, capBody, videoHash, targetUrl, serverName, callback)
                        if (!ok) {
                            // registration failed -> restart the page so another capture can arrive
                            try {
                                withContext(Dispatchers.Main) {
                                    webView?.loadUrl(targetUrl, mapOf("Referer" to "$mainUrl/"))
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                ok
            } ?: false

            if (!registered) {
                // The issue JSON told us the playlist URL but the in-page fetch
                // never came through the hook — try one manual GET (may hit CF).
                issuePlaylistUrl.get()?.let { pUrl ->
                    nlog("[NguonC] [StreamcNew] issue gave playlist, fetching manually: ${pUrl.take(70)}")
                    val manual = streamcGet(pUrl, embedDomain, targetUrl)
                    if (manual != null) {
                        registered = streamcRegisterCaptured(pUrl, manual, videoHash, targetUrl, serverName, callback)
                    }
                }
            }

            // v7: keep the Cloudflare-cleared WebView for segment downloads. Swap the embed
            // page (jwplayer would autoplay) for a blank document with the embed origin.
            val keepF = fetcher
            if (SEG_WEBVIEW_FALLBACK && registered && keepF != null) {
                putWebFetcher(targetUrl, keepF)
                withContext(Dispatchers.Main) {
                    dialog?.dismiss()
                    dialog = null
                    webView?.loadDataWithBaseURL(keepF.origin + "/", segFetchBlankHtml, "text/html", "UTF-8", null)
                }
                keepWebView = true
                nlog("[NguonC] [StreamcNew] WebView kept alive for segment fetching origin=${keepF.origin}")
            }

            println(
                if (registered) "[NguonC] [StreamcNew] WebView flow SUCCESS"
                else "[NguonC] [StreamcNew] WebView flow captured nothing (Turnstile likely interactive)"
            )
            return registered
        } catch (e: Exception) {
            nlog("[NguonC] [StreamcNew] WebView flow error: ${e.message}")
            return false
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.Main) {
                    dialog?.dismiss()
                    if (!keepWebView) {
                        webView?.stopLoading()
                        webView?.destroy()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * JS injected in onPageStarted/Finished (idempotent). Wraps window.fetch and
     * XMLHttpRequest so that every response body which looks like an HLS playlist
     * (#EXTM3U / #ENC-AESGCM) or an issue JSON ("playlist": "...") is forwarded to
     * the Kotlin bridge. Plain string concat only — no template literals.
     * v3 hardening:
     *  - URLs are normalized to ABSOLUTE before forwarding (XHR may hand us a
     *    relative path; Kotlin needs the absolute playlist URL as segment base).
     *  - XHR bodies are recovered even when responseType is arraybuffer/blob
     *    (responseText throws there) via TextDecoder.
     *  - When the issue JSON passes by, the hook itself fetches the playlist URL
     *    in-page (same origin -> no Cloudflare risk) and forwards its body, so a
     *    native-video playback path that bypasses fetch/XHR cannot hide it.
     *  - Seen-response dedupe keeps the bridge from receiving duplicates.
     */
    private val streamcHookJs = """
(function(){
  if (window.__ngcHooked) return; window.__ngcHooked = true;
  function abs(u){
    var s = String(u);
    if (s.indexOf('http') === 0) return s;
    if (s.charAt(0) === '/') return location.origin + s;
    return location.origin + '/' + s;
  }
  function pass(u, b){
    try {
      if (!b || b.length < 16) return;
      var sig = (String(u).length + ':' + b.length + ':' + b.substring(0, 24));
      window.__ngcSeen = window.__ngcSeen || {};
      if (window.__ngcSeen[sig]) return;
      if (Object.keys(window.__ngcSeen).length > 64) window.__ngcSeen = {};
      window.__ngcSeen[sig] = 1;
      var au = abs(u);
      if (b.indexOf('#EXTM3U') === 0 || b.indexOf('#ENC-AESGCM') >= 0) {
        window.__ngcBridge.onCapture(au, b); return;
      }
      if (b.indexOf('"playlist"') >= 0 && au.indexOf('streamc.xyz') >= 0) {
        window.__ngcBridge.onCapture(au, b);
        if (!window.__ngcFetched) {
          window.__ngcFetched = 1;
          try {
            var pu = JSON.parse(b).playlist;
            if (pu) {
              fetch(abs(pu), {credentials:'include'}).then(function(r){
                return r.text();
              }).then(function(t){ pass(pu, t); }, function(){});
            }
          } catch (e) {}
        }
      }
    } catch (e) {}
  }
  function xhrText(x){
    try { return x.responseText; } catch (e) {}
    try { return new TextDecoder('utf-8').decode(x.response); } catch (e) {}
    return null;
  }
  try {
    var of = window.fetch;
    if (of) {
      window.fetch = function(){
        var args = arguments;
        var u = (args[0] && args[0].url) ? String(args[0].url) : String(args[0]);
        return of.apply(this, args).then(function(res){
          try { res.clone().text().then(function(t){ pass(u, t); }, function(){}); } catch (e) {}
          return res;
        });
      };
    }
  } catch (e) {}
  try {
    var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u){ this.__ngcU = u; return oo.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function(){
      var self = this;
      this.addEventListener('load', function(){
        try { var t = xhrText(self); if (t) { pass(self.__ngcU, t); } } catch (e) {}
      });
      return os.apply(this, arguments);
    };
  } catch (e) {}
})();
"""

    /**
     * JS -> Kotlin bridge for the WebView capture flow.
     * v3 FIX: the constructor lambda MUST NOT be named `onCapture` — in v2 the
     * member function `fun onCapture(...)` shadowed the lambda property of the
     * same name, so the body called ITSELF (infinite recursion) and every
     * capture died with StackOverflowError before reaching Kotlin. Renamed to
     * `handler`, and Throwable (not just Exception) is caught so no Error can
     * ever kill the data path again.
     */
    private class StreamcCaptureBridge(
        private val handler: (String, String) -> Unit
    ) {
        @JavascriptInterface
        fun onCapture(url: String?, body: String?) {
            if (url.isNullOrBlank() || body.isNullOrBlank()) return
            try { handler(url, body) } catch (_: Throwable) {}
        }
    }

    /**
     * Best-effort Context for the WebView: prefer the foreground Activity that
     * CloudStream tracks (gives Turnstile a real window), fall back to the
     * Application context via ActivityThread reflection. No CloudStream API
     * version dependency.
     */
    private fun streamcAppContext(): android.content.Context? {
        try {
            val cls = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            for (name in listOf("activity", "currentActivity")) {
                try {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    (f.get(null) as? android.content.Context)?.let { return it }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val m = at.getDeclaredMethod("currentApplication")
            m.isAccessible = true
            m.invoke(null) as? android.content.Context
        } catch (_: Exception) { null }
    }

    /**
     * Register a captured playlist body. Handles both formats the server hands
     * out: plain "hls" (#EXTM3U ...) and encrypted "aesgcm" (4-line envelope,
     * decrypted with the hash-derived key — see streamcDecryptAesGcm).
     */
    private suspend fun streamcRegisterCaptured(
        capturedUrl: String, body: String, videoHash: String?,
        referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val trimmed = body.trim()
        val base = capturedUrl.substringBeforeLast("/") + "/"
        if (trimmed.startsWith("#EXTM3U") && !trimmed.contains("#ENC-AESGCM")) {
            nlog("[NguonC] [StreamcNew] captured PLAIN playlist: ${capturedUrl.take(80)}")
            val ok = registerM3U8Link(trimmed, referer, base, serverName, callback)
            if (ok) streamcCachePut(videoHash, capturedUrl, trimmed)
            return ok
        }
        if (trimmed.contains("#ENC-AESGCM")) {
            if (videoHash == null) {
                nlog("[NguonC] [StreamcNew] encrypted playlist but no hash param in URL")
                return false
            }
            val decrypted = streamcDecryptAesGcm(trimmed, videoHash)
            if (decrypted != null) {
                nlog("[NguonC] [StreamcNew] captured ENCRYPTED playlist + decrypted OK: ${capturedUrl.take(80)}")
                val ok = registerM3U8Link(decrypted, referer, base, serverName, callback)
                if (ok) streamcCachePut(videoHash, capturedUrl, trimmed)
                return ok
            }
            nlog("[NguonC] [StreamcNew] captured ENCRYPTED playlist but decrypt failed")
        }
        return false
    }

    /**
     * Decrypt the Stream Panel "aesgcm" playlist envelope (mirrors player.js):
     *   #EXTM3U
     *   #ENC-AESGCM<;|:>iv=<24 hex chars = 12-byte GCM IV>
     *   #EXT-X-B65:<range>
     *   base64(ciphertext || 16-byte GCM tag)   (tolerated when wrapped over several lines)
     * key = HMAC-SHA256(key="stream-derive-v1", msg=videoHash), AES-256-GCM, tag 128.
     * v5: verified 2026-10-12 against a captured envelope (hash e6435cc0...): this key
     * derivation yields "#EXTM3U / #EXT-X-VERSION:3 / #EXT-X-TARGETDURATION:2 /
     * #EXT-X-PLAYLIST-TYPE:VOD" with absolute segment URLs
     * https://indossNN.amassNN.top/<hash>/streamaaaNNNN.png
     */
    private fun streamcDecryptAesGcm(content: String, videoHash: String): String? {
        return try {
            val lines = content.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty() || !lines[0].startsWith("#EXTM3U")) {
                nlog("[NguonC] [StreamcNew] AESGCM envelope: no #EXTM3U header")
                return null
            }
            val ivRegex = Regex("""^#ENC-AESGCM\s*[;:,]\s*iv=([a-fA-F0-9]{24})""")
            var ivHex: String? = null
            for (l in lines) {
                val m = ivRegex.find(l)
                if (m != null) { ivHex = m.groupValues[1]; break }
            }
            if (ivHex == null) {
                nlog("[NguonC] [StreamcNew] AESGCM envelope: IV line not recognised: ${lines.getOrNull(1)?.take(60)}")
                return null
            }
            val b64 = lines.filter { !it.startsWith("#") }.joinToString("")
                .replace('-', '+').replace('_', '/').trimEnd('=')
            if (b64.isEmpty()) {
                nlog("[NguonC] [StreamcNew] AESGCM envelope: empty ciphertext")
                return null
            }
            val data = Base64.decode(padBase64(b64), Base64.DEFAULT)
            if (data.size <= 16) return null
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec("stream-derive-v1".toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val key = mac.doFinal(videoHash.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, hexToBytes(ivHex)))
            val plain = cipher.doFinal(data)
            val text = String(plain, Charsets.UTF_8)
            if (text.startsWith("#EXTM3U")) text else null
        } catch (e: Exception) {
            nlog("[NguonC] [StreamcNew] AESGCM decrypt failed: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Main link loading logic
    // ═══════════════════════════════════════════════════════════════════════════
    //
    //  STRATEGY (ordered by reliability):
    //  1. Get embed HTML, extract token from data-obf, fetch m3u8
    //  2. If token in HTML but not data-obf, use findTokenInHTML
    //  3. If no token found, parse JS files (player1.js etc.) for token
    //  4. WebView interception - let JS run and capture m3u8 request
    //  5. Broader WebView interception (broadM3u8Interceptor) on any domain
    //  6. Try URL hash-based token construction
    //  7. Last resort: broader cfInterceptor

    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        // v12: newEpisode(url) runs fixUrl() in CloudStream (MainAPI.kt:2631), which turns
        // "Vietsub::https://embed..." into "https://phim.nguonc.com/Vietsub::https://embed..." --
        // the first server's label became "https://phim.nguonc.com/Vietsub" (seen in every log).
        val rawData = if (data.startsWith("$mainUrl/") && data.contains("::")) data.removePrefix("$mainUrl/") else data
        val embedEntries = rawData.split("|").mapNotNull { entry ->
            val parts = entry.trim().split("::", limit = 2)
            if (parts.size == 2) Pair(cleanServerName(parts[0]), parts[1])
            else if (parts.size == 1 && parts[0].startsWith("http")) Pair("Vietsub", parts[0])
            else null
        }.distinctBy { it.second }  // dedupe by URL — same source shouldn't appear twice
        var linkFound = false
        nlog("[NguonC] loadLinks: entries=${embedEntries.size} urls=${embedEntries.joinToString(" | ") { it.first + "=" + it.second.take(90) }}")
        ntoastV("NguonC $NG_BUILD: ${embedEntries.size} nguồn, " + embedEntries.joinToString(",") {
            if (it.second.contains("hash=")) "embed+hash" else if (it.second.contains(".m3u")) "m3u8" else "khác"
        })

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

                    // Extract hash from URL for fallback use
                    val urlHash = Regex("""[?&]hash=([a-fA-F0-9]+)""").find(targetUrl)?.groupValues?.getOrNull(1)

                    try {
                        // ══════════════════════════════════════════════════════════════
                        // Streamc.xyz embed URL processing
                        // ══════════════════════════════════════════════════════════════
                        if (targetUrl.contains("streamc.xyz") || targetUrl.contains("phimmoi.net")) {
                            nlog("[NguonC] === Processing: ${targetUrl.take(80)} ===")

                            // ── STEP 0 (NEW 2026-09): Stream Panel "lite-15-cf-free"/"guard-7".
                            // The embed page no longer carries data-obf; the playlist URL is
                            // only issued AFTER a Cloudflare Turnstile check. Try the cheap
                            // HTTP handshake first, then let the real page run in a WebView
                            // (it solves Turnstile itself). Legacy fallbacks below untouched.
                            if (!linkFound) {
                                // v4 PERF: replay a recently-issued playlist for this
                                // hash (valid ~150s — the bootstrap JWT lifetime) and
                                // skip Turnstile entirely on re-loads.
                                val cached = streamcCacheGet(urlHash)
                                if (cached != null) {
                                    nlog("[NguonC] [StreamcNew] 0c: playlist cache HIT (${urlHash?.take(12)}...)")
                                    if (streamcRegisterCaptured(cached.first, cached.second, urlHash, targetUrl, serverName, callback)) {
                                        linkFound = true; return@async
                                    }
                                    nlog("[NguonC] [StreamcNew] 0c: cached playlist no longer playable — falling through")
                                }
                            }
                            if (!linkFound) {
                                if (tryStreamcDirectFlow(targetUrl, embedDomain, serverName, callback)) {
                                    linkFound = true; return@async
                                }
                            }
                            if (!linkFound) {
                                if (tryStreamcWebViewFlow(targetUrl, embedDomain, serverName, callback)) {
                                    linkFound = true; return@async
                                }
                            }

                            var token: String? = null
                            var kX: String? = null

                            // ── STEP 1: Get embed HTML and extract token ──
                            val html = fetchEmbedHTML(targetUrl, embedDomain)

                            if (html != null) {
                                // Extract from data-obf (primary method)
                                val obfData = extractObfData(html)
                                if (obfData != null) {
                                    token = obfData.first   // sUb
                                    kX = obfData.third      // kX
                                    // Also use hD as fallback token if sUb is null
                                    if (token == null && obfData.second != null) {
                                        nlog("[NguonC] sUb is null, trying hD as token")
                                    }
                                    nlog("[NguonC] From data-obf: token=${token?.take(20)}... kX=${kX?.take(20)}... hD=${obfData.second?.take(20)}...")
                                }

                                // Fallback: find token in HTML source
                                if (token == null) {
                                    token = findTokenInHTML(html)
                                    nlog("[NguonC] Fallback token from HTML: ${token?.take(20)}...")
                                }

                                // Fallback: try to find token from full URL in HTML
                                if (token == null) {
                                    val fullUrlMatch = Regex("""https?://embed\d+\.streamc\.xyz/([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)""").find(html)
                                    if (fullUrlMatch != null) {
                                        token = fullUrlMatch.groupValues[1]
                                        nlog("[NguonC] Found token from full URL in HTML: ${token.take(20)}...")
                                    }
                                }

                                // Fallback: try to find token from any path ending in .m3u8/.m3u9
                                if (token == null) {
                                    val pathMatch = Regex("""/([A-Za-z0-9_+/=\\-]{20,})\.(m3u8|m3u9)""").find(html)
                                    if (pathMatch != null) {
                                        token = pathMatch.groupValues[1]
                                        nlog("[NguonC] Found token from path in HTML: ${token.take(20)}...")
                                    }
                                }

                                // Get kX if not yet found
                                if (kX == null) {
                                    kX = findEncryptionKey(html, embedDomain, targetUrl)
                                }

                                // ── STEP 2: Parse JS files for token if not found in HTML ──
                                if (token == null) {
                                    nlog("[NguonC] Token not in HTML, trying JS files...")
                                    val jsResult = extractTokenFromJS(html, embedDomain, targetUrl, urlHash)
                                    if (jsResult != null) {
                                        if (jsResult.first != null) {
                                            token = jsResult.first
                                            nlog("[NguonC] Found token in JS: ${token?.take(20)}...")
                                        }
                                        if (jsResult.second != null && kX == null) {
                                            kX = jsResult.second
                                            nlog("[NguonC] Found kX in JS: ${kX?.take(20)}...")
                                        }
                                    }
                                }

                                // ── STEP 3: Try using hD from data-obf as token if sUb is null ──
                                if (token == null && obfData != null && obfData.second != null) {
                                    val hD = obfData.second
                                    nlog("[NguonC] Trying hD as token: ${hD?.take(20)}...")
                                    token = hD
                                }
                            }

                            // ── STEP 3.5 (NEW): Mobile flow — POST /{token} -> xat, then
                            // GET /{token}.m3u9?xat=<xat>.  This is the current working
                            // path as of 2026-06 and bypasses all the AES-GCM encryption
                            // grief because the .m3u9 endpoint returns a PLAIN playlist.
                            // Try this FIRST so the user actually gets a playable link.
                            if (token != null) {
                                nlog("[NguonC] Step 3.5: Mobile flow (POST + .m3u9?xat=)")
                                if (tryMobileM3U8Flow(targetUrl, embedDomain, token, serverName, callback)) {
                                    linkFound = true; return@async
                                }
                            }

                            // ── STEP 4: Try fetching with extracted token (legacy, rarely works) ──
                            if (token != null) {
                                if (tryFetchWithToken(token, embedDomain, targetUrl, kX, serverName, callback)) {
                                    linkFound = true; return@async
                                }
                            }

                            // ── FAST BAIL-OUT: if we couldn't even fetch the embed page
                            // (html == null), have no token, AND no urlHash to try alt
                            // domains with — the target URL is almost certainly unreachable.
                            // Skip everything and let the next server (Vietsub/Thuyết Minh)
                            // try. This prevents the "không tìm thấy trang web" toasts that
                            // pop up when WebView tries to load a dead URL.
                            if (html == null && token == null && urlHash == null) {
                                nlog("[NguonC] Embed page unreachable + no token + no urlHash — bailing out to avoid 'website not found'")
                                return@async
                            }
                            // If html is null but we DO have urlHash, skip the WebView
                            // fallbacks that retry the SAME dead URL (Steps 5, 6, 8) and
                            // go straight to Step 7 (which tries DIFFERENT embed domains).
                            val skipWebViewFallbacks = (html == null && token == null && urlHash != null)
                            if (skipWebViewFallbacks) {
                                nlog("[NguonC] Embed page unreachable but have urlHash — skipping WebView fallbacks, going to Step 7 (alt domains)")
                            }

                            // ── STEP 5: WebView interception - capture m3u8 directly ──
                            if (!linkFound && !skipWebViewFallbacks) {
                                nlog("[NguonC] Step 5: WebView interception for m3u8/m3u9")
                                try {
                                    val resp = app.get(targetUrl, interceptor = m3u8Interceptor, headers = mapOf(
                                        "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                                    ))
                                    val content = resp.text
                                    val capturedUrl = resp.url ?: ""

                                    // Plain m3u8
                                    if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                        nlog("[NguonC] WebView captured plain m3u8!")
                                        val m3u8Base = if (capturedUrl.isNotEmpty()) capturedUrl.substringBeforeLast("/") + "/" else ""
                                        if (registerM3U8Link(content, targetUrl, m3u8Base, serverName, callback)) {
                                            linkFound = true; return@async
                                        }
                                    }

                                    // Encrypted m3u8 captured via WebView
                                    if (content.contains("#ENC-AESGCM")) {
                                        nlog("[NguonC] WebView captured encrypted m3u8, trying decrypt...")
                                        val capToken = Regex("""/([A-Za-z0-9+/=_-]+)\.(m3u8|m3u9)""").find(capturedUrl)?.groupValues?.get(1)

                                        val encKX = kX ?: html?.let {
                                            extractObfData(it)?.third ?: findEncryptionKey(it, embedDomain, targetUrl)
                                        }

                                        if (encKX != null) {
                                            val decrypted = decryptStreamcM3u8(content, encKX, capToken)
                                            if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                nlog("[NguonC] Decrypted WebView m3u8 OK!")
                                                if (registerM3U8Link(decrypted, targetUrl, "", serverName, callback)) {
                                                    linkFound = true; return@async
                                                }
                                            }
                                        }

                                        // Try .m3u9 alternative (plain) from captured token
                                        if (capToken != null && !linkFound) {
                                            val altExt = if (capturedUrl.contains(".m3u8")) ".m3u9" else ".m3u8"
                                            val altUrl = "$embedDomain/$capToken$altExt"
                                            nlog("[NguonC] Trying alternative: ${altUrl.take(60)}")
                                            val altContent = fetchDirect(altUrl, targetUrl, embedDomain)
                                            if (altContent != null && altContent.contains("#EXTM3U") && !altContent.contains("#ENC-AESGCM")) {
                                                val m3u8Base = altUrl.substringBeforeLast("/") + "/"
                                                if (registerM3U8Link(altContent, targetUrl, m3u8Base, serverName, callback)) {
                                                    linkFound = true; return@async
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    nlog("[NguonC] WebView interception failed: ${e.message}")
                                }
                            }

                            // ── STEP 6: Broader WebView interception (any domain) ──
                            if (!linkFound && !skipWebViewFallbacks) {
                                nlog("[NguonC] Step 6: Broad WebView interception")
                                try {
                                    val resp = app.get(targetUrl, interceptor = broadM3u8Interceptor, headers = mapOf(
                                        "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                                    ))
                                    val content = resp.text
                                    val capturedUrl = resp.url ?: ""

                                    if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                        nlog("[NguonC] Broad WebView captured plain m3u8!")
                                        val m3u8Base = if (capturedUrl.isNotEmpty()) capturedUrl.substringBeforeLast("/") + "/" else ""
                                        if (registerM3U8Link(content, targetUrl, m3u8Base, serverName, callback)) {
                                            linkFound = true; return@async
                                        }
                                    }

                                    if (content.contains("#ENC-AESGCM")) {
                                        nlog("[NguonC] Broad WebView captured encrypted m3u8")
                                        val capToken = Regex("""/([A-Za-z0-9+/=_-]+)\.(m3u8|m3u9)""").find(capturedUrl)?.groupValues?.get(1)
                                        val encKX = kX ?: html?.let {
                                            extractObfData(it)?.third ?: findEncryptionKey(it, embedDomain, targetUrl)
                                        }
                                        if (encKX != null) {
                                            val decrypted = decryptStreamcM3u8(content, encKX, capToken)
                                            if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                if (registerM3U8Link(decrypted, targetUrl, "", serverName, callback)) {
                                                    linkFound = true; return@async
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    nlog("[NguonC] Broad WebView failed: ${e.message}")
                                }
                            }

                            // ── STEP 7: Try URL hash-based token construction ──
                            if (!linkFound && urlHash != null) {
                                nlog("[NguonC] Step 7: Trying URL hash as token: ${urlHash.take(20)}...")

                                // 7a: Try hash directly as m3u8 URL path
                                for (ext in listOf(".m3u9", ".m3u8")) {
                                    val m3u8Url = "$embedDomain/$urlHash$ext"
                                    val m3u8Content = fetchDirect(m3u8Url, targetUrl, embedDomain)
                                    if (m3u8Content != null) {
                                        if (m3u8Content.contains("#EXTM3U") && !m3u8Content.contains("#ENC-AESGCM")) {
                                            if (registerM3U8Link(m3u8Content, targetUrl, "", serverName, callback)) {
                                                linkFound = true; return@async
                                            }
                                        }
                                        if (m3u8Content.contains("#ENC-AESGCM") && kX != null) {
                                            val decrypted = decryptStreamcM3u8(m3u8Content, kX, urlHash)
                                            if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                if (registerM3U8Link(decrypted, targetUrl, "", serverName, callback)) {
                                                    linkFound = true; return@async
                                                }
                                            }
                                        }
                                    }
                                }

                                // 7b: Try constructing token from hash (base64 of JSON with h=hash)
                                if (!linkFound) {
                                    try {
                                        val tokenJson = """{"h":"$urlHash","t":"$urlHash"}"""
                                        val constructedToken = toUrlSafeBase64(
                                            String(Base64.encode(tokenJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP), Charsets.UTF_8)
                                        )
                                        nlog("[NguonC] Constructed token: ${constructedToken.take(20)}...")
                                        if (tryFetchWithToken(constructedToken, embedDomain, targetUrl, kX, serverName, callback)) {
                                            linkFound = true; return@async
                                        }
                                    } catch (_: Exception) {}
                                }

                                // 7c: Try other embed domain numbers IN PARALLEL via the
                                // mobile flow (the working path as of 2026-06). The old
                                // sequential loop with tryFetchWithToken would hit 401 on
                                // every domain and take 30+ seconds before giving up.
                                if (!linkFound) {
                                    nlog("[NguonC] Step 7c: Trying alt domains in parallel via mobile flow")
                                    val altResults = listOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
                                        .map { num -> "https://embed${num}.streamc.xyz" }
                                        .filter { it != embedDomain }
                                        .map { altDomain ->
                                            async {
                                                try {
                                                    val altUrl = "$altDomain/embed.php?hash=$urlHash"
                                                    val altHtml = fetchEmbedHTML(altUrl, altDomain)
                                                    if (altHtml == null) return@async null
                                                    val altObfData = extractObfData(altHtml)
                                                    val altToken = altObfData?.first ?: findTokenInHTML(altHtml)
                                                        ?: return@async null
                                                    nlog("[NguonC] [7c] Found token on $altDomain, trying mobile flow")
                                                    if (tryMobileM3U8Flow(altUrl, altDomain, altToken, serverName, callback)) {
                                                        return@async altDomain  // success — return domain as a truthy signal
                                                    }
                                                    null
                                                } catch (_: Exception) { null }
                                            }
                                        }.awaitAll()
                                    if (altResults.any { it != null }) {
                                        nlog("[NguonC] [7c] Alt domain succeeded: $altResults")
                                        linkFound = true; return@async
                                    }
                                }
                            }

                            // ── STEP 8: Last resort - broader cfInterceptor ──
                            if (!linkFound && !skipWebViewFallbacks) {
                                nlog("[NguonC] Step 8: Last resort cfInterceptor")
                                try {
                                    val resp = app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf(
                                        "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                                    ))
                                    val content = resp.text
                                    val capUrl = resp.url ?: ""

                                    if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                        val m3u8Base = if (capUrl.isNotEmpty()) capUrl.substringBeforeLast("/") + "/" else ""
                                        if (registerM3U8Link(content, targetUrl, m3u8Base, serverName, callback)) {
                                            linkFound = true
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            if (!linkFound) {
                                nlog("[NguonC] FAILED - all approaches exhausted for: $targetUrl")
                            }
                            return@async
                        }

                        // ══════════════════════════════════════════════════════════════
                        // Direct m3u8/m3u9 URL (from API m3u8 field)
                        // ══════════════════════════════════════════════════════════════
                        if ((targetUrl.contains(".m3u8") || targetUrl.contains(".m3u9")) && targetUrl.contains("streamc.xyz")) {
                            nlog("[NguonC] Processing direct m3u8/m3u9 URL: ${targetUrl.take(80)}")

                            // NEW: Extract the token from the URL and try the mobile flow first.
                            // This handles the case where the API returns a direct /{token}.m3u8
                            // URL — the same access-confirm handshake is required.
                            val urlToken = Regex("""/([A-Za-z0-9+/=_-]+)\.(m3u8|m3u9)""").find(targetUrl)?.groupValues?.get(1)
                            if (urlToken != null) {
                                // Determine the embed page URL for the Referer header.
                                // The segment CDN accepts any https://embedXX.streamc.xyz/* referer.
                                val embedPageUrl = Regex("""(https?://embed\d+\.streamc\.xyz)""").find(targetUrl)?.groupValues?.get(1)?.let { "$it/embed.php" }
                                    ?: "$embedDomain/embed.php"
                                nlog("[NguonC] Trying mobile flow on direct URL with token: ${urlToken.take(20)}...")
                                if (tryMobileM3U8Flow(embedPageUrl, embedDomain, urlToken, serverName, callback)) {
                                    linkFound = true; return@async
                                }
                            }

                            // Legacy fallback: try .m3u9 first (plain), then .m3u8
                            val urlsToTry = mutableListOf(targetUrl)
                            if (targetUrl.endsWith(".m3u8")) {
                                urlsToTry.add(targetUrl.replace(".m3u8", ".m3u9"))
                            } else if (targetUrl.endsWith(".m3u9")) {
                                urlsToTry.add(targetUrl.replace(".m3u9", ".m3u8"))
                            }
                            for (m3u8Url in urlsToTry) {
                                val content = fetchDirect(m3u8Url, "$mainUrl/", embedDomain)
                                if (content != null) {
                                    if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                        val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
                                        registerM3U8Link(content, targetUrl, m3u8Base, serverName, callback)
                                        linkFound = true; break
                                    }
                                    if (content.contains("#ENC-AESGCM")) {
                                        // Need kX - try to get from embed page
                                        var encKX: String? = null
                                        if (urlToken != null) {
                                            val decoded = decodeStreamcToken(urlToken)
                                            if (decoded != null) {
                                                val (_, h, _) = decoded
                                                // Try to find embed page using hash from token
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
                                                linkFound = true; break
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
                        nlog("[NguonC] Processing other embed: ${targetUrl.take(80)}")
                        val embedRes = app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf(
                            "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                        ))
                        val otherHtml = embedRes.text

                        // Check if cfInterceptor already captured m3u8
                        if (otherHtml.contains("#EXTM3U") && !otherHtml.contains("#ENC-AESGCM")) {
                            val capUrl = embedRes.url ?: ""
                            val m3u8Base = if (capUrl.isNotEmpty()) capUrl.substringBeforeLast("/") + "/" else ""
                            if (registerM3U8Link(otherHtml, targetUrl, m3u8Base, serverName, callback)) {
                                linkFound = true; return@async
                            }
                        }

                        // Try to find m3u8 URL in HTML
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
                        nlog("[NguonC] Error processing $targetUrl: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        nlog("[NguonC] loadLinks: done linkFound=$linkFound")
        if (linkFound) prewarmNext(rawData)
        if (!linkFound) ntoast("NguonC $NG_BUILD: loadLinks KHÔNG có link")
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

    /**
     * Response from `POST https://embedXX.streamc.xyz/{token}` (the access-confirm
     * endpoint called by player.js `_confirmAccess`).
     *
     * Example: `{"ok":true,"xat":"b536dd2e065857326edcb521114e9c46f3f2ad3d1af97f7af50a950c249db8ef"}`
     *
     * The `xat` (x-auth-token) is then attached to the m3u8 fetch as either a
     * `?xat=<xat>` query param (mobile / .m3u9) or a `hash: <xat>` header (desktop
     * / .m3u8).  We use the mobile path because it returns a plain playlist.
     */
    data class StreamcAccessResponse(
        @JsonProperty("ok")  val ok: Boolean? = null,
        @JsonProperty("xat") val xat: String? = null
    )
}
