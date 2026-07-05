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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        "${API_PREFIX}api/films/phim-moi-cap-nhat"    to "Phim M\u1EDBi C\u1EADp Nh\u1EADt",
        "danh-sach/phim-le"                           to "Phim L\u1EBB",
        "danh-sach/phim-bo"                           to "Phim B\u1ED9",
        "the-loai/phim-18"                            to "18+"
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
        val res = fetchApi<NguonCApiResponse>("$mainUrl/api/films?keyword=${URLEncoder.encode(query, "utf-8")}")

        if (!res?.items.isNullOrEmpty())
            return res!!.items!!.mapNotNull { parseApiItem(it) }

        val searchUrl = "$mainUrl/tim-kiem?keyword=${URLEncoder.encode(query, "utf-8")}"
        // PERF: same fast-path-first fetch as getMainPage.
        val doc = fetchListingDoc(searchUrl)
        return doc.select("table tbody tr").mapNotNull { parseCard(it) }
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
                val url2 = when {
                    directM3u8.isNotBlank() -> directM3u8
                    embed.isNotBlank()      -> embed
                    else                    -> return@forEach
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
            .maxByOrNull { it.list?.size ?: 0 }?.list ?: genreItems.take(5)

        fun nameToSlug(name: String): String {
            val map = mapOf(
                '\u00E0' to "a", '\u00E1' to "a", '\u00E2' to "a", '\u00E3' to "a", '\u00E4' to "a", '\u00E5' to "a",
                '\u0103' to "a", '\u1EAF' to "a", '\u1EB7' to "a", '\u1EB1' to "a", '\u1EB3' to "a", '\u1EB5' to "a",
                '\u1EA5' to "a", '\u1EA7' to "a", '\u1EA9' to "a", '\u1EAB' to "a", '\u1EAD' to "a", '\u1EA3' to "a", '\u1EA1' to "a",
                '\u00E8' to "e", '\u00E9' to "e", '\u00EA' to "e", '\u00EB' to "e",
                '\u1EC1' to "e", '\u1EBF' to "e", '\u1EC7' to "e", '\u1EC3' to "e", '\u1EC5' to "e", '\u1EB9' to "e", '\u1EBB' to "e", '\u1EBD' to "e",
                '\u00EC' to "i", '\u00ED' to "i", '\u00EE' to "i", '\u00EF' to "i", '\u1ECB' to "i", '\u1EC9' to "i", '\u0129' to "i",
                '\u00F2' to "o", '\u00F3' to "o", '\u00F4' to "o", '\u00F5' to "o", '\u00F6' to "o",
                '\u1ED3' to "o", '\u1ED1' to "o", '\u1ED9' to "o", '\u1ED5' to "o", '\u1ED7' to "o",
                '\u1EDD' to "o", '\u1EDB' to "o", '\u1EE3' to "o", '\u1EDF' to "o", '\u1EE1' to "o", '\u1ECD' to "o", '\u1ECF' to "o",
                '\u00F9' to "u", '\u00FA' to "u", '\u00FB' to "u", '\u00FC' to "u",
                '\u1EEB' to "u", '\u1EE9' to "u", '\u1EF1' to "u", '\u1EED' to "u", '\u1EEF' to "u", '\u1EE5' to "u", '\u1EE7' to "u", '\u0169' to "u",
                '\u1EF3' to "y", '\u00FD' to "y", '\u1EF5' to "y", '\u1EF7' to "y", '\u1EF9' to "y",
                '\u0111' to "d", '\u0110' to "d", '\u01B0' to "u", '\u01A1' to "o"
            )
            return name.lowercase().trim().map { c ->
                map[c] ?: if (c in 'a'..'z' || c in '0'..'9') c.toString() else if (c == ' ') "-" else ""
            }.joinToString("").replace(Regex("-{2,}"), "-").trim('-')
        }

        // ── Recommendations: HTML-scrape the genre listing pages ──
        //
        // The old code called `GET /api/films/{genre-slug}` which 404s for genre
        // slugs (that endpoint only accepts film slugs). As a result the genre
        // branch always returned empty and the code fell through to
        // `phim-moi-cap-nhat` (site-wide newest) — which is why an anime like
        // "Dũng Sĩ Căn Bà" was getting Chinese costume dramas as "similar films".
        //
        // The CORRECT endpoint is the HTML genre browse page:
        //   GET /the-loai/{slug}?page={n}   →  HTML <table><tbody><tr>…</tr></tbody></table>
        // which `getMainPage` already uses via `parseCard()`. We re-use the same
        // parser here.
        //
        // PERF: this used to fetch up to 3 PAGES per genre (up to 9 separate
        // WebView loads total, since every HTML fetch used a brand-new
        // WebViewResolver) which is why opening a movie could take 10-30+
        // seconds. Now:
        //   - fetchListingDoc() reuses cached CF cookies for a fast plain HTTP
        //     fetch whenever possible (WebView only runs once per app session,
        //     in the common case).
        //   - Capped at 2 pages per genre and stops as soon as we have enough
        //     items, cutting the number of requests needed.
        //
        // Genres are sorted by specificity (most specific first) so an anime's
        // "Hoạt Hình" genre is tried before its "Hành Động" genre — otherwise a
        // generic action match would drown out the much-more-relevant anime match.
        val genrePriority = listOf(
            "hoạt hình", "khoa học viễn tưởng", "giả tưởng", "phiêu lưu",
            "kinh dị", "bí ẩn", "hình sự", "chính kịch", "lịch sử", "cổ trang",
            "chiến tranh", "tâm lý", "tình cảm", "lãng mạn", "hài", "gia đình",
            "hành động", "phim 18", "tài liệu", "nhạc", "miền tây"
        )
        val sortedGenres = theLoaiItems.mapNotNull { it.name }.sortedBy { gname ->
            val lower = gname.lowercase()
            val idx = genrePriority.indexOfFirst { lower.contains(it) }
            if (idx == -1) Int.MAX_VALUE else idx
        }

        val recommendations: List<SearchResponse> = try {
            coroutineScope {
                // Try top 3 genres IN PARALLEL — first non-empty wins.
                // (If we tried them sequentially, a dead/slow genre would block
                //  the more relevant one behind it.) The cfMutex inside
                //  fetchListingDoc() makes sure a cold-start Cloudflare
                //  challenge is only solved once even though these run in
                //  parallel — the other calls simply reuse the resulting cookie.
                val genreResults = sortedGenres.take(3).map { genreName ->
                    async {
                        val slug2 = nameToSlug(genreName)
                        if (slug2.isBlank()) return@async emptyList<SearchResponse>()

                        val items = mutableListOf<SearchResponse>()
                        // Scrape up to 2 pages of this genre (20 films/page → up to 40),
                        // stopping early once we have enough for the grid.
                        for (p in 1..2) {
                            try {
                                val url = if (p == 1) "$mainUrl/the-loai/$slug2"
                                          else "$mainUrl/the-loai/$slug2?page=$p"
                                val doc = fetchListingDoc(url)
                                val rows = doc.select("table tbody tr").mapNotNull { parseCard(it) }
                                    .filter { (it.url ?: "").trimEnd('/').substringAfterLast("/") != movie.slug }
                                if (rows.isEmpty()) break  // no more pages
                                items += rows
                                if (items.size >= 24) break
                            } catch (_: Exception) { break }
                        }
                        items
                    }
                }.awaitAll()

                // Pick the genre with the most results — this naturally favors
                // the most specific genre that has many films (e.g. "Hoạt Hình"
                // for anime) over a generic one with the same count.
                val bestResult = genreResults.maxByOrNull { it.size } ?: emptyList()
                if (bestResult.isNotEmpty()) {
                    bestResult.distinctBy { it.url }.take(30)
                } else {
                    // Last-resort fallback — site-wide newest films (fast, plain API).
                    try {
                        app.get("$mainUrl/api/films/phim-moi-cap-nhat?page=1", headers = commonHeaders)
                            .parsedSafe<NguonCApiResponse>()?.items
                            ?.filter { it.slug != movie.slug }
                            ?.take(20)
                            ?.mapNotNull { parseApiItem(it) }
                            ?: emptyList()
                    } catch (_: Exception) { emptyList() }
                }
            }
        } catch (_: Exception) { emptyList() }

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

    inner class NguonCProxyServer(
        private val segReferer: String
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        val port: Int get() = serverSocket?.localPort ?: 0
        private val threadPool = java.util.concurrent.Executors.newCachedThreadPool()
        @Volatile private var _m3u8: String = ""

        fun setM3U8(content: String) { _m3u8 = content }
        private fun getM3U8(): String = _m3u8

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

        fun stop() {
            try { serverSocket?.close() } catch (_: Exception) {}
            try { threadPool.shutdownNow() } catch (_: Exception) {}
        }
    }

    /** Rewrite m3u8 segment URLs to go through our local proxy */
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

    /** Create proxy server and register m3u8 link */
    private suspend fun registerM3U8Link(
        m3u8Content: String, referer: String, m3u8BaseUrl: String,
        serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!m3u8Content.contains("#EXTM3U")) return false
        val server = NguonCProxyServer(referer)
        server.start(); activeServers.add(server)
        val proxyBase = "http://127.0.0.1:${server.port}"
        server.setM3U8(rewriteM3U8(m3u8Content, proxyBase, m3u8BaseUrl))
        callback(newExtractorLink("NguonC", serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
            quality = Qualities.P1080.value
            headers = mapOf("User-Agent" to USER_AGENT)
        })
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
                    println("[NguonC] Decoded token ($method): h=${h.length}hex, t=${t.length}hex")
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
                println("[NguonC] data-obf decoded ($methodName): ${obfJson.take(200)}")

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
                    println("[NguonC] data-obf ($methodName): sUb=${sUb?.take(20)}... hD=${hD?.take(20)}... kX=${kX?.take(20)}...")
                    return Triple(sUb, hD, kX)
                }

                // If no named fields found, try to extract any JSON value that looks like a token
                if (sUb == null && hD == null && kX == null) {
                    // Look for any base64-like string that could be a token
                    val allValues = Regex(""""(\w+)"\s*:\s*"([A-Za-z0-9_+/=-]{20,})"""").findAll(obfJson).toList()
                    for (match in allValues) {
                        val key = match.groupValues[1]
                        val value = match.groupValues[2]
                        println("[NguonC] data-obf found field: $key = ${value.take(20)}...")
                        // Heuristic: if value looks like base64 JSON token, use it as sUb
                        if (value.length > 30 && (value.contains("=") || value.any { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/" })) {
                            if (sUb == null) {
                                sUb = value
                                println("[NguonC] data-obf heuristic: using $key as sUb")
                            }
                        }
                    }
                    if (sUb != null) return Triple(sUb, hD, kX)
                }
            } catch (e: Exception) {
                println("[NguonC] data-obf decode error ($methodName): ${e.message}")
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
                    println("[NguonC] Found token in HTML: ${token.take(20)}... (len=${token.length}, pattern=${pattern.pattern.take(40)})")
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
            println("[NguonC] Found kX from data-obf: ${obfData.third}")
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
                    println("[NguonC] Found kX in inline script: ${key.take(20)}...")
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
                            println("[NguonC] Found kX in $jsName.js: ${match.groupValues[1].take(20)}...")
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

        println("[NguonC] Found ${scriptSrcs.size} script sources in HTML")

        for (jsPath in scriptSrcs) {
            val jsUrl = if (jsPath.startsWith("http")) jsPath else "$embedDomain/$jsPath"
            try {
                val jsContent = app.get(jsUrl, headers = mapOf(
                    "Referer" to referer, "User-Agent" to USER_AGENT
                )).text
                println("[NguonC] Downloaded JS: ${jsPath.take(40)} (${jsContent.length} chars)")

                // Look for token-like patterns: base64 strings that end up in URLs
                // Pattern 1: variable = "BASE64STRING" where BASE64 is long enough to be a token
                val b64Matches = Regex("""["']([A-Za-z0-9_+/=-]{40,})["']""").findAll(jsContent)
                for (match in b64Matches) {
                    val candidate = match.groupValues[1]
                    // Check if this could be a base64 JSON token
                    try {
                        val decoded = String(Base64.decode(padBase64(candidate.replace('-', '+').replace('_', '/')), Base64.DEFAULT), Charsets.UTF_8)
                        if (decoded.contains("\"h\"") && decoded.contains("\"t\"")) {
                            println("[NguonC] Found token in JS: ${candidate.take(20)}... (decoded: ${decoded.take(50)})")
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
                        println("[NguonC] Found token-like URL in JS: ${tokenStr.take(20)}...")
                        val kXMatch = Regex("""(?:kX|key|aesKey)\s*[:=]\s*["']([a-fA-F0-9]{32,})["']""").find(jsContent)
                        return Pair(tokenStr, kXMatch?.groupValues?.get(1))
                    }
                }

                // Pattern 3: Look for btoa/atob usage with token construction
                val tokenConstruct = Regex("""(?:btoa|atob|Base64)\s*\(\s*(?:JSON\.stringify\s*\(\s*\{[^}]*"h"[^}]*\})""").find(jsContent)
                if (tokenConstruct != null) {
                    println("[NguonC] Found token construction in JS: ${tokenConstruct.value.take(60)}...")
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
            println("[NguonC] Decrypt: iv=${ivHex.take(16)}... (${ivHex.length}hex), kX=$kX")

            // Parse B65 header - supports both #EXT-X-B65:offset-length and #EXT-X-B65:offset
            val b65Match = Regex("""#EXT-X-B65:(\d+)(?:-(\d+))?""").find(content)
            val offset = b65Match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val length = b65Match?.groupValues?.get(2)?.toIntOrNull() ?: 0

            // Extract base64 ciphertext
            val dataLines = content.lines().dropWhile { it.startsWith("#") || it.isBlank() }
            val b64Data = dataLines.joinToString("").trim()
            if (b64Data.isEmpty()) {
                println("[NguonC] Decrypt: no data after headers")
                return null
            }

            var encData = Base64.decode(b64Data, Base64.DEFAULT)
            if (offset > 0 || length > 0) {
                encData = if (length > 0) encData.copyOfRange(offset, minOf(offset + length, encData.size))
                          else if (offset > 0) encData.copyOfRange(offset, encData.size)
                          else encData
            }

            println("[NguonC] Decrypt: encData size=${encData.size}, offset=$offset, length=$length")

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

            println("[NguonC] Trying ${keyCandidates.size} key candidates...")

            for ((desc, keyBytes) in keyCandidates) {
                if (keyBytes.size != 16 && keyBytes.size != 32) continue
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
                    val result = String(cipher.doFinal(encData), Charsets.UTF_8)
                    if (result.contains("#EXTM3U")) {
                        println("[NguonC] Decryption OK with $desc!")
                        return result
                    }
                } catch (_: Exception) {}
            }

            // 5. Try with different IV sizes (96 bits = 12 bytes is standard for GCM)
            // But some implementations might use different IV sizes
            if (iv.size != 12) {
                println("[NguonC] IV size is ${iv.size} bytes (expected 12), trying standard size...")
                for ((desc, keyBytes) in keyCandidates) {
                    if (keyBytes.size != 16 && keyBytes.size != 32) continue
                    try {
                        // Use first 12 bytes of IV
                        val truncatedIv = iv.copyOf(12)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, truncatedIv))
                        val result = String(cipher.doFinal(encData), Charsets.UTF_8)
                        if (result.contains("#EXTM3U")) {
                            println("[NguonC] Decryption OK with $desc (truncated IV)!")
                            return result
                        }
                    } catch (_: Exception) {}
                }
            }

            println("[NguonC] All decryption attempts failed")
        } catch (e: Exception) {
            println("[NguonC] Decryption error: ${e.message}")
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
                println("[NguonC] Direct HTML OK: ${html.length} chars")
                return html
            }
            // Even if no known markers, return it if it's a substantial HTML page
            if (html.length > 500 && html.contains("<")) {
                println("[NguonC] Direct HTML (no markers but substantial): ${html.take(200)}")
                return html
            }
            println("[NguonC] Direct HTML not embed page: ${html.take(100)}")
        } catch (e: Exception) {
            println("[NguonC] Direct HTML failed: ${e.message}")
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
                println("[NguonC] WebView embed HTML OK: ${html.length} chars")
                return html
            }
        } catch (e: Exception) {
            println("[NguonC] WebView embed HTML failed: ${e.message}")
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
                println("[NguonC] cfInterceptor HTML OK: ${html.length} chars")
                return html
            }
        } catch (e: Exception) {
            println("[NguonC] cfInterceptor HTML failed: ${e.message}")
        }

        println("[NguonC] All HTML fetch methods failed for: $embedUrl")
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
                println("[NguonC] Direct fetch OK: ${url.take(60)}")
                return content
            }
            println("[NguonC] Direct fetch response: ${content.take(80)}")
        } catch (_: Exception) {}
        // Try with cfInterceptor
        try {
            val resp = app.get(url, headers = headers, interceptor = cfInterceptor)
            val content = resp.text
            if (content.contains("#EXTM3U") || content.contains("#ENC-AESGCM")) {
                println("[NguonC] cfInterceptor fetch OK: ${url.take(60)}")
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
                println("[NguonC] Trying $desc token: ${m3u8Url.take(80)}")
                val m3u8Content = fetchDirect(m3u8Url, referer, embedDomain)

                if (m3u8Content != null) {
                    // Plain m3u8
                    if (m3u8Content.contains("#EXTM3U") && !m3u8Content.contains("#ENC-AESGCM")) {
                        println("[NguonC] Got plain m3u8 with $desc + $ext!")
                        val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
                        if (registerM3U8Link(m3u8Content, referer, m3u8Base, serverName, callback)) {
                            return true
                        }
                    }
                    // Encrypted m3u8
                    if (m3u8Content.contains("#ENC-AESGCM")) {
                        println("[NguonC] Got encrypted m3u8 with $desc + $ext, trying decrypt...")
                        if (kX != null) {
                            val decrypted = decryptStreamcM3u8(m3u8Content, kX, tokenVariant)
                            if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                println("[NguonC] Decrypted m3u8 OK!")
                                if (registerM3U8Link(decrypted, referer, "", serverName, callback)) {
                                    return true
                                }
                            }
                        } else {
                            println("[NguonC] No kX for decryption")
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
        println("[NguonC] [MobileFlow] POST $postUrl")
        val accessResp = try {
            app.post(postUrl, headers = postHeaders).parsedSafe<StreamcAccessResponse>()
        } catch (e: Exception) {
            println("[NguonC] [MobileFlow] POST failed: ${e.message}")
            null
        }
        val xat = accessResp?.xat
        if (xat.isNullOrBlank()) {
            println("[NguonC] [MobileFlow] No xat in response (ok=${accessResp?.ok})")
            return false
        }
        println("[NguonC] [MobileFlow] Got xat: ${xat.take(20)}... (len=${xat.length})")

        // ── Step 2: GET /{token}.m3u9?xat=<xat> to fetch the PLAIN m3u8 ──
        val m3u8Url = "$embedDomain/$accessTokenPath.m3u9?xat=$xat"
        val getHeaders = mapOf(
            "Referer"    to embedUrl,
            "Origin"     to embedDomain,
            "Accept"     to "*/*",
            "User-Agent" to USER_AGENT
        )
        println("[NguonC] [MobileFlow] GET $m3u8Url")
        val m3u8Content = try {
            app.get(m3u8Url, headers = getHeaders).text
        } catch (e: Exception) {
            println("[NguonC] [MobileFlow] GET failed: ${e.message}")
            null
        }
        if (m3u8Content.isNullOrBlank() || !m3u8Content.contains("#EXTM3U")) {
            println("[NguonC] [MobileFlow] Response is not a valid m3u8 (len=${m3u8Content?.length ?: 0})")
            // Try .m3u8 extension as a fallback (desktop endpoint, may be encrypted)
            val altUrl = "$embedDomain/$accessTokenPath.m3u8?xat=$xat"
            println("[NguonC] [MobileFlow] Trying .m3u8: $altUrl")
            val altContent = try { app.get(altUrl, headers = getHeaders).text } catch (_: Exception) { null }
            if (altContent.isNullOrBlank() || !altContent.contains("#EXTM3U")) {
                println("[NguonC] [MobileFlow] .m3u8 also failed")
                return false
            }
            // If the .m3u8 endpoint returned a PLAIN m3u8, register it.
            // (If it's encrypted #ENC-AESGCM, we can't decrypt it here — let the
            // legacy fallbacks try.)
            if (altContent.contains("#ENC-AESGCM")) {
                println("[NguonC] [MobileFlow] .m3u8 is encrypted, deferring to legacy fallbacks")
                return false
            }
            return registerM3U8Link(altContent, embedUrl, "", serverName, callback)
        }

        if (m3u8Content.contains("#ENC-AESGCM")) {
            println("[NguonC] [MobileFlow] .m3u9 unexpectedly encrypted, deferring to legacy fallbacks")
            return false
        }

        println("[NguonC] [MobileFlow] Got plain m3u8 (${m3u8Content.length} chars, ${m3u8Content.split("\n").count { it.startsWith("#EXTINF") }} segments)")
        // Segments in this playlist are absolute https URLs (e.g. on hihihoho*.top),
        // so pass empty m3u8BaseUrl. The proxy will rewrite them and fetch with
        // Referer = embedUrl, which is exactly what the segment CDN requires.
        return registerM3U8Link(m3u8Content, embedUrl, "", serverName, callback)
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
        val embedEntries = data.split("|").mapNotNull { entry ->
            val parts = entry.trim().split("::", limit = 2)
            if (parts.size == 2) Pair(parts[0], parts[1])
            else if (parts.size == 1 && parts[0].startsWith("http")) Pair("Vietsub", parts[0])
            else null
        }.distinctBy { it.second }  // dedupe by URL — same source shouldn't appear twice
        var linkFound = false

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
                            println("[NguonC] === Processing: ${targetUrl.take(80)} ===")

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
                                        println("[NguonC] sUb is null, trying hD as token")
                                    }
                                    println("[NguonC] From data-obf: token=${token?.take(20)}... kX=${kX?.take(20)}... hD=${obfData.second?.take(20)}...")
                                }

                                // Fallback: find token in HTML source
                                if (token == null) {
                                    token = findTokenInHTML(html)
                                    println("[NguonC] Fallback token from HTML: ${token?.take(20)}...")
                                }

                                // Fallback: try to find token from full URL in HTML
                                if (token == null) {
                                    val fullUrlMatch = Regex("""https?://embed\d+\.streamc\.xyz/([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)""").find(html)
                                    if (fullUrlMatch != null) {
                                        token = fullUrlMatch.groupValues[1]
                                        println("[NguonC] Found token from full URL in HTML: ${token.take(20)}...")
                                    }
                                }

                                // Fallback: try to find token from any path ending in .m3u8/.m3u9
                                if (token == null) {
                                    val pathMatch = Regex("""/([A-Za-z0-9_+/=\\-]{20,})\.(m3u8|m3u9)""").find(html)
                                    if (pathMatch != null) {
                                        token = pathMatch.groupValues[1]
                                        println("[NguonC] Found token from path in HTML: ${token.take(20)}...")
                                    }
                                }

                                // Get kX if not yet found
                                if (kX == null) {
                                    kX = findEncryptionKey(html, embedDomain, targetUrl)
                                }

                                // ── STEP 2: Parse JS files for token if not found in HTML ──
                                if (token == null) {
                                    println("[NguonC] Token not in HTML, trying JS files...")
                                    val jsResult = extractTokenFromJS(html, embedDomain, targetUrl, urlHash)
                                    if (jsResult != null) {
                                        if (jsResult.first != null) {
                                            token = jsResult.first
                                            println("[NguonC] Found token in JS: ${token?.take(20)}...")
                                        }
                                        if (jsResult.second != null && kX == null) {
                                            kX = jsResult.second
                                            println("[NguonC] Found kX in JS: ${kX?.take(20)}...")
                                        }
                                    }
                                }

                                // ── STEP 3: Try using hD from data-obf as token if sUb is null ──
                                if (token == null && obfData != null && obfData.second != null) {
                                    val hD = obfData.second
                                    println("[NguonC] Trying hD as token: ${hD?.take(20)}...")
                                    token = hD
                                }
                            }

                            // ── STEP 3.5 (NEW): Mobile flow — POST /{token} -> xat, then
                            // GET /{token}.m3u9?xat=<xat>.  This is the current working
                            // path as of 2026-06 and bypasses all the AES-GCM encryption
                            // grief because the .m3u9 endpoint returns a PLAIN playlist.
                            // Try this FIRST so the user actually gets a playable link.
                            if (token != null) {
                                println("[NguonC] Step 3.5: Mobile flow (POST + .m3u9?xat=)")
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
                                println("[NguonC] Embed page unreachable + no token + no urlHash — bailing out to avoid 'website not found'")
                                return@async
                            }
                            // If html is null but we DO have urlHash, skip the WebView
                            // fallbacks that retry the SAME dead URL (Steps 5, 6, 8) and
                            // go straight to Step 7 (which tries DIFFERENT embed domains).
                            val skipWebViewFallbacks = (html == null && token == null && urlHash != null)
                            if (skipWebViewFallbacks) {
                                println("[NguonC] Embed page unreachable but have urlHash — skipping WebView fallbacks, going to Step 7 (alt domains)")
                            }

                            // ── STEP 5: WebView interception - capture m3u8 directly ──
                            if (!linkFound && !skipWebViewFallbacks) {
                                println("[NguonC] Step 5: WebView interception for m3u8/m3u9")
                                try {
                                    val resp = app.get(targetUrl, interceptor = m3u8Interceptor, headers = mapOf(
                                        "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                                    ))
                                    val content = resp.text
                                    val capturedUrl = resp.url ?: ""

                                    // Plain m3u8
                                    if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                        println("[NguonC] WebView captured plain m3u8!")
                                        val m3u8Base = if (capturedUrl.isNotEmpty()) capturedUrl.substringBeforeLast("/") + "/" else ""
                                        if (registerM3U8Link(content, targetUrl, m3u8Base, serverName, callback)) {
                                            linkFound = true; return@async
                                        }
                                    }

                                    // Encrypted m3u8 captured via WebView
                                    if (content.contains("#ENC-AESGCM")) {
                                        println("[NguonC] WebView captured encrypted m3u8, trying decrypt...")
                                        val capToken = Regex("""/([A-Za-z0-9+/=_-]+)\.(m3u8|m3u9)""").find(capturedUrl)?.groupValues?.get(1)

                                        val encKX = kX ?: html?.let {
                                            extractObfData(it)?.third ?: findEncryptionKey(it, embedDomain, targetUrl)
                                        }

                                        if (encKX != null) {
                                            val decrypted = decryptStreamcM3u8(content, encKX, capToken)
                                            if (!decrypted.isNullOrEmpty() && decrypted.contains("#EXTM3U")) {
                                                println("[NguonC] Decrypted WebView m3u8 OK!")
                                                if (registerM3U8Link(decrypted, targetUrl, "", serverName, callback)) {
                                                    linkFound = true; return@async
                                                }
                                            }
                                        }

                                        // Try .m3u9 alternative (plain) from captured token
                                        if (capToken != null && !linkFound) {
                                            val altExt = if (capturedUrl.contains(".m3u8")) ".m3u9" else ".m3u8"
                                            val altUrl = "$embedDomain/$capToken$altExt"
                                            println("[NguonC] Trying alternative: ${altUrl.take(60)}")
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
                                    println("[NguonC] WebView interception failed: ${e.message}")
                                }
                            }

                            // ── STEP 6: Broader WebView interception (any domain) ──
                            if (!linkFound && !skipWebViewFallbacks) {
                                println("[NguonC] Step 6: Broad WebView interception")
                                try {
                                    val resp = app.get(targetUrl, interceptor = broadM3u8Interceptor, headers = mapOf(
                                        "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                                    ))
                                    val content = resp.text
                                    val capturedUrl = resp.url ?: ""

                                    if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                        println("[NguonC] Broad WebView captured plain m3u8!")
                                        val m3u8Base = if (capturedUrl.isNotEmpty()) capturedUrl.substringBeforeLast("/") + "/" else ""
                                        if (registerM3U8Link(content, targetUrl, m3u8Base, serverName, callback)) {
                                            linkFound = true; return@async
                                        }
                                    }

                                    if (content.contains("#ENC-AESGCM")) {
                                        println("[NguonC] Broad WebView captured encrypted m3u8")
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
                                    println("[NguonC] Broad WebView failed: ${e.message}")
                                }
                            }

                            // ── STEP 7: Try URL hash-based token construction ──
                            if (!linkFound && urlHash != null) {
                                println("[NguonC] Step 7: Trying URL hash as token: ${urlHash.take(20)}...")

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
                                        println("[NguonC] Constructed token: ${constructedToken.take(20)}...")
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
                                    println("[NguonC] Step 7c: Trying alt domains in parallel via mobile flow")
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
                                                    println("[NguonC] [7c] Found token on $altDomain, trying mobile flow")
                                                    if (tryMobileM3U8Flow(altUrl, altDomain, altToken, serverName, callback)) {
                                                        return@async altDomain  // success — return domain as a truthy signal
                                                    }
                                                    null
                                                } catch (_: Exception) { null }
                                            }
                                        }.awaitAll()
                                    if (altResults.any { it != null }) {
                                        println("[NguonC] [7c] Alt domain succeeded: $altResults")
                                        linkFound = true; return@async
                                    }
                                }
                            }

                            // ── STEP 8: Last resort - broader cfInterceptor ──
                            if (!linkFound && !skipWebViewFallbacks) {
                                println("[NguonC] Step 8: Last resort cfInterceptor")
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
                                println("[NguonC] FAILED - all approaches exhausted for: $targetUrl")
                            }
                            return@async
                        }

                        // ══════════════════════════════════════════════════════════════
                        // Direct m3u8/m3u9 URL (from API m3u8 field)
                        // ══════════════════════════════════════════════════════════════
                        if ((targetUrl.contains(".m3u8") || targetUrl.contains(".m3u9")) && targetUrl.contains("streamc.xyz")) {
                            println("[NguonC] Processing direct m3u8/m3u9 URL: ${targetUrl.take(80)}")

                            // NEW: Extract the token from the URL and try the mobile flow first.
                            // This handles the case where the API returns a direct /{token}.m3u8
                            // URL — the same access-confirm handshake is required.
                            val urlToken = Regex("""/([A-Za-z0-9+/=_-]+)\.(m3u8|m3u9)""").find(targetUrl)?.groupValues?.get(1)
                            if (urlToken != null) {
                                // Determine the embed page URL for the Referer header.
                                // The segment CDN accepts any https://embedXX.streamc.xyz/* referer.
                                val embedPageUrl = Regex("""(https?://embed\d+\.streamc\.xyz)""").find(targetUrl)?.groupValues?.get(1)?.let { "$it/embed.php" }
                                    ?: "$embedDomain/embed.php"
                                println("[NguonC] Trying mobile flow on direct URL with token: ${urlToken.take(20)}...")
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
                        println("[NguonC] Processing other embed: ${targetUrl.take(80)}")
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
                        println("[NguonC] Error processing $targetUrl: ${e.message}")
                    }
                }
            }.awaitAll()
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
