// AnimeVsub v2 (2026-10-25): CloudStream provider cho https://animevietsub.sale
// Kiến trúc battle-hardened kế thừa NguonC (Aho-Repo):
//  - Lớp fetch Cloudflare-aware: doc cache -> plain HTTP + cookie clearance cache
//    -> CORS proxy -> WebViewResolver fallback (serialized qua mutex, cookie re-captured).
//    Site chặn TOÀN BỘ request bằng trang gate "Xác Minh An Toàn" (Turnstile) +
//    cf-mitigated: challenge (curl -> 403, ~895KB challenge page), không có API mở.
//  - loadLinks đa đường (heuristic vì player nằm sau gate, chưa verify runtime):
//      STEP 1 plain HTML harvest (m3u8/mp4/iframe) + 1 cấp iframe lồng
//      STEP 2 WebView + JS hook (patch fetch/XHR) — port từ NguonC streamcHookJs
//      STEP 3 WebViewResolver broad capture trên trang xem
//      STEP 4 WebViewResolver broad capture trên từng embed
//  - HLS proxy cục bộ: /stream.m3u8 rewrite + /seg/ relay (OkHttp -> HttpURLConnection),
//    streaming 64KB, kiểm tra HTTP status (không bao giờ serve trang Cloudflare làm video).
//  - Diagnostics v2 (chuẩn NguonC): nlog/nlogE -> logcat + file
//    <externalFilesDir>/animevsub_diag.txt; session banner model/OS/app-version;
//    timestamp .SSS + build tag mỗi dòng; nlogE ghi class+stack cho MỌI catch
//    (v1 nuốt exception im lặng ở ~15 chỗ); loadLinks kết thúc bằng dòng SUMMARY;
//    ntoast chạy main looper (v1 toast từ coroutine IO bị rơi im lặng).
//  v3 (25-10-2026, fix theo animevsub_diag.txt THẬT của user — PPG-AN00, Android 17):
//    * ROOT CAUSE 0-items: Cloudflare trả 403/520 kèm trang challenge ~888KB
//      KHÔNG chứa keyword nhận dạng nào -> v2 nhận nhầm là nội dung và CACHE 5 phút.
//      Fix: tryPlainDoc/fetchText CHỈ nhận HTTP 2xx; looksBlocked thêm marker
//      challenge-platform / challenges.cloudflare.com / turnstile; kết quả WebView
//      cũng kiểm tra blocked trước khi cache; rethrow CancellationException (bớt noise).
//    * Log thật: cả 2 CORS proxy đã chết (cors.sh NXDOMAIN, allorigins timeout)
//      -> circuit breaker tắt proxy 10 phút sau 4 lần fail liên tục.
//    * Gate "Xác Minh An Toàn" KHÔNG tự giải được bằng HTTP/cookie -> unlockGate():
//      Dialog WebView full-screen cho user tick "Verify you are human" MỘT LẦN,
//      bắt DOM qua JS bridge, lưu clearance cookie cho toàn bộ fetch sau đó
//      (gateMutex + tối đa 1 lần/10 phút, bỏ qua khi không có Activity foreground).
//  v4 (25-10-2026, fix lần 2 theo log thật — webview "OK" vẫn 0 items MỌI danh mục):
//    * ROOT CAUSE: WebViewResolver trả RAW HTML TRƯỚC KHI JS render, trong khi
//      site render danh sách phim bằng AJAX -> HTML thô KHÔNG có link /phim/
//      -> parseCards ra 0 dù trang "sạch".
//    * Fix cốt lõi: webviewDocCapture() — WebView TỰ QUẢN LÝ, poll DOM mỗi 0.8s
//      qua JS bridge, CHỈ nhận khi DOM CÓ LINK PHIM (hoặc đã render đủ lâu).
//      Silent pass trước (không UI); gate vẫn chặn -> unlockGate() mở Dialog
//      full-screen cho user tick "Xác Minh An Toàn" MỘT lần.
//    * UA đồng bộ WebView == plain HTTP (cf_clearance gắn với UA — lệch UA là 403).
//    * parseCards: fallback title từ img[alt] + chặn title rác; log [Parse] 0-cards.
//    * search: dừng sau 2 biến thể sạch 0 kết quả (tránh đốt 4 biến thể full-chain).
// URL patterns đã xác minh qua search index + Telegram kênh chính thức (24-10-2026):
//  - Chi tiết phim : /phim/{slug}-a{id}/          (vd /phim/dogulwang-a6014/)
//  - Trang xem     : /phim/{slug}-a{id}/xem-phim.html
//  - Thể loại      : /the-loai/{slug}/trang-{N}.html  (vd /the-loai/hanh-dong/trang-4.html)
//  - Nhân vật      : /nhan-vat/{slug}.html        Random: /random-anime
package com.animevsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

@CloudstreamPlugin
class AnimeVsubPlugin : Plugin() {
    override fun load() { registerMainAPI(AnimeVsubProvider()) }
}

class AnimeVsubProvider : MainAPI() {
    override var mainUrl = "https://animevietsub.sale"
    override var name = "AnimeVsub"
    override val hasMainPage = true
    override var lang = "vi"
    // Người dùng không chọn tính năng tải xuống cho v1 — bật lại bằng 1 dòng nếu muốn.
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ═══════════════════════════════════════════════════════════════════════════
    //  FLAGS / HẰNG SỐ
    // ═══════════════════════════════════════════════════════════════════════════
    private val AVS_BUILD = "v4"

    // Preflight probe segment đầu rồi trim playlist (kỹ thuật NguonC v9) — để OFF
    // vì chưa biết CDN của site có chặn segment đầu kiểu streamaaa0000.png không.
    // Khi bật: probe 3 segment đầu, nếu 0 OK và >0 OK từ segment sau -> trim.
    private val PREFLIGHT_TRIM = false

    // Số embed iframe tối đa được đào mỗi lần loadLinks (chống nổ request).
    private val MAX_EMBEDS = 3

    // Timeout cho WebView hook flow — trang gate Turnstile có thể cần user tương tác.
    private val HOOK_TIMEOUT_MS = 60_000L

    // v4: timeout WebView DOM capture (silent) — đợi JS render danh sách phim.
    private val WV_SILENT_TIMEOUT_MS = 22_000L

    // Chế độ hoàn tất của webviewDocCapture:
    //  MODE_LISTING: chỉ nhận khi DOM có link /phim/{slug}-a{id} (cards đã render)
    //  MODE_DETAIL : nhận khi có link xem-phim, hoặc sau ~8 poll (~6.4s)
    //  MODE_LOOSE  : nhận khi DOM sạch sau 4 poll (~3.2s) — search variant, embed
    private val MODE_LISTING = 0
    private val MODE_DETAIL = 1
    private val MODE_LOOSE = 2

    private val commonHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    private val corsProxies = listOf(
        "https://proxy.cors.sh/",
        "https://api.allorigins.win/raw?url="
    )

    // Log thật 25-10: proxy.cors.sh chết (NXDOMAIN), allorigins timeout 10s/trang.
    // Circuit breaker: sau 4 lần fail liên tục thì tắt proxy 10 phút cho đỡ treo listing.
    private val proxyFailCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var proxyDisabledUntilMs = 0L
    private val PROXY_COOLDOWN_MS = 10 * 60 * 1000L

    // Host rác không bao giờ là embed player.
    private val junkHosts = listOf(
        "googleads", "doubleclick", "googlesyndication", "google-analytics",
        "facebook.com", "t.me", "telegram", "twitter.com", "x.com", "disqus",
        "gravatar", "recaptcha", "cloudflareinsights", "hotjar", "pushnami"
    )

    // ═══════════════════════════════════════════════════════════════════════════
    //  DIAGNOSTICS v2 (chuẩn NguonC): mọi sự kiện ghi logcat + file
    //  <externalFilesDir>/animevsub_diag.txt. Mỗi dòng: HH:mm:ss.SSS + build tag.
    //  Mở phiên mới có banner thiết bị (model/Android/app version) để log gửi về
    //  luôn đủ ngữ cảnh phân tích. nlogE() ghi đầy đủ class + message + 3 khung
    //  stack của exception — bắt buộc gọi trong MỌI catch thay vì nuốt im lặng.
    // ═══════════════════════════════════════════════════════════════════════════
    private val diagLock = Any()
    @Volatile private var diagCtx: android.content.Context? = null
    @Volatile private var diagPathShown = false
    private val AVS_TOAST_VERBOSE = false

    private fun diagContext(): android.content.Context? {
        diagCtx?.let { return it }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val m = at.getDeclaredMethod("currentApplication")
            m.isAccessible = true
            (m.invoke(null) as? android.content.Context)?.let {
                diagCtx = it.applicationContext ?: it; diagCtx
            }
        } catch (_: Throwable) { null }
    }

    private fun ntoastV(msg: String) { if (AVS_TOAST_VERBOSE) ntoast(msg) }

    private fun ntoast(msg: String) {
        try {
            val ctx = diagContext() ?: return
            // Toast phải chạy trên main looper (bài học NguonC v11: gọi thẳng từ
            // coroutine IO thì toast bị rơi im lặng hoặc crash).
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    android.widget.Toast.makeText(ctx, msg.take(160), android.widget.Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun nlog(msg: String) {
        try { android.util.Log.d("AnimeVsub", msg) } catch (_: Throwable) {}
        try {
            val ctx = diagContext() ?: return
            synchronized(diagLock) {
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                val f = java.io.File(dir, "animevsub_diag.txt")
                if (!diagPathShown) {
                    diagPathShown = true
                    val appVer = try {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
                    } catch (_: Throwable) { "?" }
                    val dev = try { android.os.Build.MODEL } catch (_: Throwable) { "?" }
                    val os  = try { android.os.Build.VERSION.RELEASE } catch (_: Throwable) { "?" }
                    f.appendText("═══ AnimeVsub $AVS_BUILD session ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} | $dev | Android $os | app $appVer ═══\n")
                    f.appendText("log file: ${f.absolutePath}\n")
                }
                // Giữ file dưới ~384KB (cắt bớt đầu, giữ 128KB gần nhất).
                if (f.exists() && f.length() > 384 * 1024) {
                    f.writeText(f.readText().takeLast(128 * 1024))
                }
                val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                f.appendText("$ts $AVS_BUILD $msg\n")
            }
        } catch (_: Throwable) {}
    }

    /** Log exception đầy đủ: class + message + 3 khung stack đầu — dùng trong mọi catch. */
    private fun nlogE(tag: String, e: Throwable?, extra: String = "") {
        val cls  = e?.javaClass?.simpleName ?: "?"
        val emsg = (e?.message ?: "").take(200)
        val frames = try {
            e?.stackTrace?.take(3)?.joinToString("<-") {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            } ?: ""
        } catch (_: Throwable) { "" }
        nlog("ERROR $tag $cls: $emsg${if (extra.isNotBlank()) " | $extra" else ""}${if (frames.isNotBlank()) " @ $frames" else ""}")
    }

    /** Job bị huỷ (user rời màn hình) — phải ném lại để giữ cấu trúc coroutine, đừng nuốt. */
    private fun isCancellation(e: Throwable?): Boolean =
        e is kotlinx.coroutines.CancellationException

    /** <title> đầu tiên của HTML — nhận diện trang gate/challenge trong log. */
    private fun pageTitleOf(html: String): String {
        return try {
            // (?i)=case-insensitive, (?s)=dot-all — inline flags, tránh RegexOption.or
            // (stdlib Kotlin không có infix 'or' cho RegexOption -> lỗi CI "Unresolved 'or'").
            Regex("""(?is)<title[^>]*>(.*?)</title>""")
                .find(html)?.groupValues?.get(1)?.trim()?.take(60) ?: "?"
        } catch (_: Throwable) { "?" }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  LỚP FETCH CLOUDFLARE-AWARE (port từ NguonC fetchListingDoc + cfCookieCache)
    //  Thứ tự: cache -> plain HTTP + cookie clearance -> plain không cookie
    //          -> CORS proxy (circuit breaker) -> WebView DOM capture (serialized)
    //          -> Dialog xác minh tương tác (unlockGate).
    //  Site dùng gate "Xác Minh An Toàn" riêng TRƯỚC challenge Cloudflare chuẩn,
    //  nên looksBlocked() nhận thêm các cụm tiếng Việt của trang gate.
    // ═══════════════════════════════════════════════════════════════════════════

    private data class CachedDoc(val doc: Document, val time: Long)

    private val docCache = ConcurrentHashMap<String, CachedDoc>()
    private val DOC_CACHE_TTL_MS = 5 * 60 * 1000L // 5 phút

    private val cfCookieCache = ConcurrentHashMap<String, Map<String, String>>()
    private val cfMutex = Mutex()

    private fun looksBlocked(html: String): Boolean {
        if (html.isBlank()) return true
        val lower = html.lowercase()
        return lower.contains("just a moment") ||
               lower.contains("checking your browser") ||
               lower.contains("cf-browser-verification") ||
               lower.contains("attention required") ||
               lower.contains("verify you are human") ||
               lower.contains("xác minh an toàn") ||
               lower.contains("xác minh bảo mật") ||
               // v3: log thật 25-10 — trang challenge ~888KB không nói lời nào,
               // chỉ nhận ra qua script/markup của Turnstile + challenge platform.
               lower.contains("cdn-cgi/challenge-platform") ||
               lower.contains("challenges.cloudflare.com") ||
               lower.contains("cf_chl_opt") ||
               lower.contains("_cf_chl") ||
               lower.contains("turnstile") ||
               (lower.contains("cloudflare") && lower.contains("challenge"))
    }

    /** Lấy cookie cf_clearance... mà bất kỳ WebView nào trong app đã thu được. */
    private fun cookiesFromCookieManager(url: String): Map<String, String>? {
        return try {
            val raw = CookieManager.getInstance().getCookie(url) ?: return null
            if (raw.isBlank()) null
            else raw.split(";").mapNotNull {
                val idx = it.indexOf('=')
                if (idx <= 0) null else it.substring(0, idx).trim() to it.substring(idx + 1).trim()
            }.toMap().ifEmpty { null }
        } catch (e: Throwable) { nlogE("[CookieMgr]", e); null }
    }

    /** Số link phim distinct (/phim/{slug}-a{id}) trong HTML — tiêu chí "trang đã render xong". */
    private fun countPhimAnchors(html: String): Int {
        return try {
            Regex("""/phim/[^"'\s<>\\]*?-a\d+""").findAll(html)
                .map { it.value }.toSet().size
        } catch (_: Throwable) { 0 }
    }

    /** Số lần xuất hiện "xem-phim" (link tập) trong HTML. */
    private fun countXemPhim(html: String): Int {
        return try {
            Regex("xem-phim", RegexOption.IGNORE_CASE).findAll(html).count()
        } catch (_: Throwable) { 0 }
    }

    /** Lưu cookie từ CookieManager (clearance của WebView) vào cfCookieCache
     *  để plain HTTP dùng lại. LƯU Ý: cf_clearance gắn với UA của WebView —
     *  webviewDocCapture đặt UA trùng USER_AGENT để plain HTTP tái sử dụng được. */
    private fun saveCfCookiesFromManager(tag: String) {
        try {
            val ck = CookieManager.getInstance().getCookie(mainUrl)
            if (!ck.isNullOrBlank()) {
                val map = ck.split(";").mapNotNull {
                    val idx = it.indexOf('=')
                    if (idx <= 0) null else it.substring(0, idx).trim() to it.substring(idx + 1).trim()
                }.toMap()
                cfCookieCache[mainUrl] = map
                nlog("[$tag] cookie-cache +${map.size} cookie")
            }
        } catch (e: Throwable) { nlogE("[$tag] cookie-save", e) }
    }

    private fun absolutize(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val h = href.trim().replace("\\/", "/")
        return when {
            h.startsWith("http://") || h.startsWith("https://") -> h
            h.startsWith("//") -> "https:$h"
            h.startsWith("/")  -> mainUrl + h
            h.startsWith("about:") || h.startsWith("javascript:") || h.startsWith("data:") -> null
            else -> "$mainUrl/$h"
        }
    }

    private fun hostOf(url: String): String =
        Regex("""^https?://([^/?#]+)""").find(url)?.groupValues?.get(1)?.lowercase() ?: ""

    private fun isJunkUrl(url: String): Boolean {
        val h = hostOf(url)
        if (h.isBlank()) return true
        return junkHosts.any { h.contains(it) }
    }

    private suspend fun tryPlainDoc(url: String, cookies: Map<String, String>?): Document? {
        val t0 = System.currentTimeMillis()
        val tag = if (cookies != null) "ck=${cookies.size}" else "no-ck"
        try {
            val resp = if (cookies != null) {
                app.get(url, headers = commonHeaders, cookies = cookies)
            } else {
                app.get(url, headers = commonHeaders)
            }
            val html = resp.text
            // v3: CHỈ nhận 2xx — log thật: Cloudflare trả 403/520 kèm trang challenge
            // ~888KB không có keyword nhận dạng -> v2 dùng nhầm làm content + cache.
            if (resp.code !in 200..299) {
                nlog("[Fetch] plain $tag ${resp.code} REJECT len=${html.length} ms=${System.currentTimeMillis() - t0} $url")
                return null
            }
            val blocked = looksBlocked(html)
            if (!blocked) {
                val doc = resp.document
                if (doc.body()?.text()?.isNotBlank() == true) {
                    nlog("[Fetch] plain $tag OK ${resp.code} len=${html.length} ms=${System.currentTimeMillis() - t0} $url")
                    return doc
                }
                nlog("[Fetch] plain $tag ${resp.code} EMPTY-BODY len=${html.length} ms=${System.currentTimeMillis() - t0} $url")
            } else {
                nlog("[Fetch] plain $tag ${resp.code} BLOCKED title='${pageTitleOf(html)}' len=${html.length} ms=${System.currentTimeMillis() - t0} $url")
            }
        } catch (e: Throwable) {
            if (isCancellation(e)) throw e
            nlogE("[Fetch] plain $tag", e, url)
        }
        return null
    }

    /**
     * Fetch một trang HTML bất kỳ của site, nhanh nhất có thể.
     * mode: MODE_LISTING (mặc định — chỉ nhận trang đã render cards),
     *       MODE_DETAIL (trang chi tiết/xem), MODE_LOOSE (search, embed).
     * Sau mỗi lần WebView DOM capture thành công, cookies (cf_clearance...) được
     * cache để các lần gọi plain HTTP sau có cơ hội không phải chạy WebView nữa.
     */
    private suspend fun fetchDoc(url: String, useCache: Boolean = true, mode: Int = MODE_LISTING): Document {
        if (useCache) {
            docCache[url]?.let { cached ->
                if (System.currentTimeMillis() - cached.time < DOC_CACHE_TTL_MS) {
                    nlog("[Fetch] cache-hit $url")
                    return cached.doc
                }
            }
        }

        // ── 1. Plain HTTP với cookie clearance đã có (nhanh nhất) ──
        val cachedCookies = cfCookieCache[mainUrl] ?: cookiesFromCookieManager(mainUrl)
        if (cachedCookies != null) {
            tryPlainDoc(url, cachedCookies)?.let {
                if (useCache) docCache[url] = CachedDoc(it, System.currentTimeMillis())
                return it
            }
        }
        tryPlainDoc(url, null)?.let {
            if (useCache) docCache[url] = CachedDoc(it, System.currentTimeMillis())
            return it
        }

        // ── 2. CORS proxy + circuit breaker (log thật 25-10: cors.sh NXDOMAIN,
        //    allorigins timeout 10s/trang -> tắt 10 phút sau 4 lần fail liên tục) ──
        if (System.currentTimeMillis() >= proxyDisabledUntilMs) {
            for (proxy in corsProxies) {
                val pt0 = System.currentTimeMillis()
                try {
                    val proxiedUrl = if (proxy.endsWith("?url=")) proxy + URLEncoder.encode(url, "UTF-8") else proxy + url
                    val resp = app.get(proxiedUrl, headers = commonHeaders)
                    val html = resp.text
                    if (resp.code in 200..299 && !looksBlocked(html)) {
                        val doc = resp.document
                        if (doc.body()?.text()?.isNotBlank() == true) {
                            nlog("[Fetch] proxy ${hostOf(proxy)} OK ${resp.code} len=${html.length} ms=${System.currentTimeMillis() - pt0} $url")
                            proxyFailCount.set(0)
                            if (useCache) docCache[url] = CachedDoc(doc, System.currentTimeMillis())
                            return doc
                        }
                    }
                    nlog("[Fetch] proxy ${hostOf(proxy)} FAIL ${resp.code} blocked=${looksBlocked(html)} len=${html.length} ms=${System.currentTimeMillis() - pt0} $url")
                } catch (e: Throwable) {
                    if (isCancellation(e)) throw e
                    nlogE("[Fetch] proxy ${hostOf(proxy)}", e, url)
                }
                if (proxyFailCount.incrementAndGet() >= 4) {
                    proxyDisabledUntilMs = System.currentTimeMillis() + PROXY_COOLDOWN_MS
                    proxyFailCount.set(0)
                    nlog("[Fetch] TẮT CORS proxy ${PROXY_COOLDOWN_MS / 60000} phút (fail liên tục)")
                    break
                }
            }
        }

        // ── 3. WebView DOM capture im lặng (serialized qua cfMutex) ──
        //    v4: WebViewResolver cũ trả RAW HTML TRƯỚC KHI JS render — log 25-10
        //    11:09: webview "OK" nhưng 0 link /phim/ -> 0 items mọi danh mục.
        //    Giờ: WebView tự quản, poll DOM mỗi 0.8s, chỉ nhận khi có dữ liệu phim.
        val silent = cfMutex.withLock {
            // Ai đó có thể đã fetch xong trong lúc ta chờ lock.
            if (useCache) {
                docCache[url]?.let { cached ->
                    if (System.currentTimeMillis() - cached.time < DOC_CACHE_TTL_MS) return@withLock cached.doc
                }
            }
            val fresh = cfCookieCache[mainUrl] ?: cookiesFromCookieManager(mainUrl)
            if (fresh != null && fresh != cachedCookies) {
                tryPlainDoc(url, fresh)?.let {
                    if (useCache) docCache[url] = CachedDoc(it, System.currentTimeMillis())
                    return@withLock it
                }
            }

            nlog("[Fetch] webview-dom fallback mode=$mode $url")
            val doc = webviewDocCapture(url, mode, WV_SILENT_TIMEOUT_MS, interactive = false)
            if (doc != null && useCache) docCache[url] = CachedDoc(doc, System.currentTimeMillis())
            doc
        }
        if (silent != null) return silent

        // ── 4. Unlock tương tác: Dialog WebView cho user tick "Xác Minh An Toàn" ──
        unlockGate(url, mode)?.let {
            if (useCache) docCache[url] = CachedDoc(it, System.currentTimeMillis())
            return it
        }
        // Nếu một unlock khác vừa chạy xong -> clearance cookie mới có thể đã có.
        tryPlainDoc(url, cfCookieCache[mainUrl] ?: cookiesFromCookieManager(mainUrl))?.let {
            if (useCache) docCache[url] = CachedDoc(it, System.currentTimeMillis())
            return it
        }
        // Lần chót: silent webview với clearance mới (unlock khác vừa mở gate).
        webviewDocCapture(url, mode, WV_SILENT_TIMEOUT_MS, interactive = false)?.let {
            if (useCache) docCache[url] = CachedDoc(it, System.currentTimeMillis())
            return it
        }

        throw ErrorLoadingException("AnimeVsub: site vẫn chặn sau khi thử mọi đường — hãy thử lại và hoàn tất xác minh trong cửa sổ plugin mở ra (xem animevsub_diag.txt)")
    }

    /** GET trả về text thô (dùng cho m3u8), có kiểm tra block-page. */
    private suspend fun fetchText(url: String, referer: String?): String? {
        val headers = if (referer != null) commonHeaders + mapOf("Referer" to referer) else commonHeaders
        val t0 = System.currentTimeMillis()
        val short = "...${url.takeLast(60)}"
        return try {
            val resp = app.get(url, headers = headers)
            val t = resp.text
            if (resp.code !in 200..299) {
                nlog("[Text] ${resp.code} REJECT len=${t.length} ms=${System.currentTimeMillis() - t0} $short")
                null
            } else if (looksBlocked(t)) {
                nlog("[Text] ${resp.code} BLOCKED title='${pageTitleOf(t)}' len=${t.length} ms=${System.currentTimeMillis() - t0} $short")
                null
            } else {
                nlog("[Text] ${resp.code} OK len=${t.length} ms=${System.currentTimeMillis() - t0} $short")
                t
            }
        } catch (e: Throwable) {
            if (isCancellation(e)) throw e
            nlogE("[Text]", e, short)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  TRANG CHỦ ĐA MỤC + LỌC THỂ LOẠI
    //  Pattern phân trang thể loại đã xác minh: /the-loai/{slug}/trang-{N}.html
    //  Phân trang trang chủ chưa xác minh -> thử 2 kiểu, nhớ kiểu thắng cuộc.
    // ═══════════════════════════════════════════════════════════════════════════

    @Volatile private var homePageStyle = 0   // 0: /trang-N.html, 1: ?page=N
    @Volatile private var genrePageStyle = 0  // 0: trang-N.html, 1: ?page=N

    override val mainPage = mainPageOf(
        "HOME" to "Mới Cập Nhật",
        "GENRE::hanh-dong"  to "Hành Động",
        "GENRE::phieu-luu"  to "Phiêu Lưu",
        "GENRE::hai-huoc"   to "Hài Hước",
        "GENRE::ky-ao"      to "Huyền Ảo",
        "GENRE::sieu-nhien" to "Siêu Nhiên",
        "GENRE::bi-an"      to "Bí Ẩn",
        "GENRE::tinh-cam"   to "Tình Cảm",
        "GENRE::hoc-duong"  to "Học Đường",
        "GENRE::kinh-di"    to "Kinh Dị",
        "GENRE::the-thao"   to "Thể Thao",
        "GENRE::am-nhac"    to "Âm Nhạc"
    )

    /** Danh sách URL ứng viên cho 1 trang listing — cái nào parse ra card thì dùng. */
    private fun listingCandidates(data: String, page: Int): List<String> {
        return if (data == "HOME") {
            if (page == 1) listOf("$mainUrl/")
            else when (homePageStyle) {
                0 -> listOf("$mainUrl/trang-$page.html", "$mainUrl/?page=$page")
                else -> listOf("$mainUrl/?page=$page", "$mainUrl/trang-$page.html")
            }
        } else {
            val slug = data.removePrefix("GENRE::")
            if (page == 1) {
                listOf("$mainUrl/the-loai/$slug/", "$mainUrl/the-loai/$slug/trang-1.html")
            } else when (genrePageStyle) {
                0 -> listOf("$mainUrl/the-loai/$slug/trang-$page.html", "$mainUrl/the-loai/$slug/?page=$page")
                else -> listOf("$mainUrl/the-loai/$slug/?page=$page", "$mainUrl/the-loai/$slug/trang-$page.html")
            }
        }
    }

    private fun rememberPaginationStyle(data: String, page: Int, idx: Int) {
        if (page == 1) return
        if (data == "HOME") {
            if (idx != homePageStyle) homePageStyle = idx
        } else {
            if (idx != genrePageStyle) genrePageStyle = idx
        }
        nlog("[List] pagination style ${data.take(12)} p$page -> idx=$idx")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val candidates = listingCandidates(request.data, page)
        var items: List<SearchResponse> = emptyList()
        var usedIdx = -1
        for ((idx, url) in candidates.withIndex()) {
            try {
                val doc = fetchDoc(url)
                items = parseCards(doc)
                if (items.isNotEmpty()) { usedIdx = idx; break }
            } catch (e: Throwable) {
                if (isCancellation(e)) throw e
                nlog("[List] candidate fail $url: ${e.message}")
            }
        }
        if (usedIdx >= 0) rememberPaginationStyle(request.data, page, usedIdx)
        nlog("[List] ${request.name} p$page -> ${items.size} items")
        ntoastV("AnimeVsub $AVS_BUILD: ${request.name} p$page — ${items.size} phim")
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PARSE CARD — "anchor harvest" chịu lỗi với markup chưa biết chính xác:
    //  mọi <a href="/phim/{slug}-a{id}..."> có kèm ảnh/tiêu đề đều là một card.
    // ═══════════════════════════════════════════════════════════════════════════

    private val slugIdRegex = Regex("""/phim/([^/?#]+-a\d+)""")

    /** Chuẩn hoá về URL chi tiết dạng /phim/{slug}-a{id}/ (có dấu / cuối). */
    private fun detailUrl(href: String): String? {
        val m = slugIdRegex.find(href) ?: return null
        return "$mainUrl/phim/${m.groupValues[1]}/"
    }

    private fun pickImgUrl(img: Element): String? {
        for (attr in listOf("data-src", "data-original", "data-lazy-src", "src")) {
            val v = img.attr(attr).trim()
            if (v.isNotBlank() && !v.startsWith("data:")) return v
        }
        // srcset: lấy URL đầu tiên.
        val srcset = img.attr("srcset").trim()
        if (srcset.isNotBlank()) return srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
        return null
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        for (a in doc.select("a[href*=\"/phim/\"]")) {
            try {
                val href = absolutize(a.attr("href")) ?: continue
                val detail = detailUrl(href) ?: continue
                val slugId = slugIdRegex.find(detail)!!.groupValues[1]
                if (out.containsKey(slugId)) continue

                var title = a.attr("title").trim()
                if (title.isBlank()) title = a.selectFirst("h3, h2, .title, .name, .film-title")?.text()?.trim() ?: ""
                if (title.isBlank()) title = a.text().trim()
                // v4: nhiều CMS đặt tên phim ở alt của ảnh card
                if (title.isBlank()) title = a.selectFirst("img")?.attr("alt")?.trim() ?: ""
                if (title.isBlank()) {
                    title = a.closest("li, .item, .card, .movie-item, .film-item, article")
                        ?.selectFirst("h3, h2, .title, .name")?.text()?.trim() ?: ""
                }
                if (title.isBlank()) continue
                // v4: chặn title rác (anchor "Xem phim"/"Tập N" lọt vào card)
                val tl = title.lowercase()
                if (tl == "xem phim" || tl == "xem ngay" || tl.startsWith("tập ")) continue
                // Tiêu đề rác kiểu "Xem phim ..." quá dài thường là link nội dung, bỏ.
                if (title.length > 120) continue

                val img = a.selectFirst("img")
                    ?: a.closest("li, .item, .card, .movie-item, .film-item, article, div")
                        ?.selectFirst("img")
                val poster = img?.let { absolutize(pickImgUrl(it)) }

                out[slugId] = newAnimeSearchResponse(title, detail, TvType.Anime) {
                    this.posterUrl = poster
                }
            } catch (e: Throwable) {
                nlogE("[Card]", e, a.attr("href").take(60))
            }
        }
        if (out.isEmpty()) {
            // v4: chẩn đoán trang nhận được khi 0 cards — lần log sau sẽ xác định
            // NGAY trang là gate/shell/trang thật nhưng selector lệch.
            nlog("[Parse] 0 cards | title='${doc.title().take(50)}' aTotal=${doc.select("a").size} aPhim=${doc.select("a[href*=\"/phim/\"]").size} img=${doc.select("img").size} iframe=${doc.select("iframe").size} textLen=${doc.text().length}")
        }
        return out.values.toList()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  TÌM KIẾM — site không có JSON API mở (mọi đường đều 403), dùng trang
    //  /tim-kiem với nhiều biến thể URL; biến thể nào parse ra card thì thắng.
    // ═══════════════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = URLEncoder.encode(query.trim(), "UTF-8")
        val candidates = listOf(
            "$mainUrl/tim-kiem?keyword=$keyword",
            "$mainUrl/tim-kiem/$keyword",
            "$mainUrl/tim-kiem.html?keyword=$keyword",
            "$mainUrl/?s=$keyword"
        )
        var cleanMiss = 0
        for (url in candidates) {
            try {
                val doc = fetchDoc(url, mode = MODE_LOOSE)
                val items = parseCards(doc)
                nlog("[Search] '$query' -> ${items.size} @ $url")
                if (items.isNotEmpty()) return items
                // v4: trang sạch mà 0 kết quả — đừng đốt thêm biến thể
                // (mỗi biến thể chạy trọn fetch chain, rất chậm khi bị gate).
                cleanMiss++
                if (cleanMiss >= 2) {
                    nlog("[Search] dừng sau $cleanMiss biến thể sạch 0 kết quả")
                    return emptyList()
                }
            } catch (e: Throwable) {
                if (isCancellation(e)) throw e
                nlog("[Search] candidate fail $url: ${e.message}")
            }
        }
        nlog("[Search] '$query' -> 0 kết quả sau ${candidates.size} biến thể")
        return emptyList()
    }

    /** "Tập 1" / "Ep 1" / "Episode 1" / "1" / "01" -> "1"; "OVA" giữ nguyên. */
    private fun normalizeEpName(raw: String?): String {
        if (raw.isNullOrBlank()) return "0"
        val s = raw.trim()
        val numMatch = Regex("""(\d+)""").find(s)
        if (numMatch != null) return numMatch.groupValues[1].trimStart('0').ifBlank { "0" }
        return s
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  LOAD — chi tiết phim
    //  Tập phim: gom mọi <a href="...xem-phim..."> trên trang chi tiết, gộp theo
    //  số tập (multi-server sẽ hiện ở mức link — loadLinks trả nhiều link/tập).
    //  Nếu trang chi tiết không có link tập, fallback dựng từ "Số tập" info.
    // ═══════════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val detail = detailUrl(url) ?: url
        val doc = try {
            fetchDoc(detail, mode = MODE_DETAIL)
        } catch (e: Throwable) {
            nlogE("[Load] fetchDoc", e, detail)
            throw e
        }

        // ── Tiêu đề ──
        var title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        if (title.isBlank()) title = doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.trim() ?: ""
        if (title.isBlank()) title = doc.title().trim().substringBefore("|").trim()
        if (title.isBlank()) throw ErrorLoadingException("Không đọc được tên phim")

        // ── Poster ──
        var poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")?.trim() ?: ""
        if (poster.isBlank()) {
            poster = doc.selectFirst(".poster img, .image img, .thumb img, .film-poster img, img[src*=\"upload\"], img[data-src*=\"upload\"]")
                ?.let { pickImgUrl(it) } ?: ""
        }

        // ── Nội dung ──
        var plot = doc.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim() ?: ""
        for (sel in listOf(".description", ".film-description", "#bio", ".story", "[class*=descript]", ".content-detail")) {
            if (plot.isNotBlank()) break
            plot = doc.selectFirst(sel)?.text()?.trim() ?: ""
        }
        if (plot.isBlank()) plot = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim() ?: ""

        // ── Tags: mọi link /the-loai/ trên trang đều là thể loại (chịu lỗi layout) ──
        val tags = doc.select("a[href*=\"/the-loai/\"]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()
            .take(12)

        // ── Năm phát hành: ưu tiên vùng info, fallback og:title / text trang ──
        val infoText = doc.select("li, dt, dd, .info, [class*=meta], p")
            .joinToString(" ") { it.text() }
        val year = Regex("""(?:Năm|Phát hành|Năm phát hành)\D{0,12}((?:19|20)\d{2})""", RegexOption.IGNORE_CASE)
            .find(infoText)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b((?:19|20)\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        // ── Số tập khai báo (dùng cho fallback) ──
        val totalEps = Regex("""(\d+)\s*(?:tập|episodes?)""", RegexOption.IGNORE_CASE)
            .find(infoText + " " + title)?.groupValues?.get(1)?.toIntOrNull()

        // ── Trích xuất tập: mọi anchor dẫn tới trang xem ──
        val epOrder = LinkedHashMap<String, String>()   // epName(normalized) -> watchUrl đầu tiên gặp
        val seenUrls = HashSet<String>()

        for (a in doc.select("a[href]")) {
            val href = absolutize(a.attr("href")) ?: continue
            if (!href.contains("xem-phim")) continue
            if (seenUrls.contains(href)) continue

            // Tên tập: text anchor, hoặc từ href (tap-3.html / ep=3 / tap-3)
            var rawName = a.text().trim()
            if (rawName.isBlank() || rawName.length > 40) {
                rawName = Regex("""(?:tap|ep|tap-phim)[=\-/]?(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.get(1) ?: ""
            }
            val epName = normalizeEpName(rawName)
            seenUrls.add(href)
            if (!epOrder.containsKey(epName)) epOrder[epName] = href
        }

        nlog("[Load] $detail title='$title' tags=${tags.size} eps=${epOrder.size} totalEps=$totalEps")
        ntoastV("AnimeVsub $AVS_BUILD: $title — ${epOrder.size} tập")

        // ── Fallback: không có anchor tập -> dựng URL xem dạng chuẩn CMS ──
        if (epOrder.isEmpty()) {
            val n = totalEps ?: throw ErrorLoadingException("Không tìm thấy tập phim nào")
            if (n in 1..2000) {
                val base = detail.trimEnd('/')
                for (i in 1..n) epOrder[i.toString()] = "$base/xem-phim.html?ep=$i"
                nlog("[Load] fallback dựng $n tập từ info")
            }
        }

        if (epOrder.isEmpty()) throw ErrorLoadingException("Không tìm thấy tập phim nào")

        val episodes = epOrder.map { (epName, watchUrl) ->
            newEpisode(watchUrl) {
                this.name    = "Tập $epName"
                this.episode = epName.toIntOrNull()
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }

        return newTvSeriesLoadResponse(title, detail, TvType.Anime, episodes) {
            this.posterUrl = poster.takeIf { it.isNotBlank() }
            this.plot      = plot.takeIf { it.isNotBlank() }
            this.tags      = tags
            this.year      = year
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  LOADLINKS — chuỗi chiến lược đa đường (player nằm sau gate, v1 dùng heuristic)
    //   STEP 1: HTML trang xem -> harvest m3u8/mp4 + iframe embed (1 cấp lồng)
    //   STEP 2: WebView + JS hook (fetch/XHR) — bắt playlist ngay khi player nạp
    //   STEP 3: WebViewResolver broad trên trang xem
    //   STEP 4: WebViewResolver broad trên từng embed
    //  Mỗi đường đăng ký qua registerM3U8Link (proxy cục bộ) hoặc link VIDEO thẳng.
    // ═══════════════════════════════════════════════════════════════════════════

    private val m3u8UrlRegex =
        Regex("""https?://[^"'\s<>\\]+?\.m3u[89](?:\?[^"'\s<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val mp4UrlRegex =
        Regex("""https?://[^"'\s<>\\]+?\.(?:mp4|m4v)(?:\?[^"'\s<>\\]*)?""", RegexOption.IGNORE_CASE)

    /** Unescape "\/" từ JSON-embedded URL rồi harvest m3u8/mp4. */
    private fun harvestMediaUrls(html: String): Pair<List<String>, List<String>> {
        val unescaped = html.replace("\\/", "/")
        val m3u8 = m3u8UrlRegex.findAll(unescaped)
            .map { it.value }.distinct().toList()
        val mp4 = mp4UrlRegex.findAll(unescaped)
            .map { it.value }.distinct().toList()
        return m3u8 to mp4
    }

    private fun harvestIframes(doc: Document): List<String> {
        return doc.select("iframe, source, embed").mapNotNull { el ->
            val raw = el.attr("src").ifBlank { el.attr("data-src") }
            val src = absolutize(raw)
            src?.takeIf { !isJunkUrl(it) && !it.startsWith("data:") }
        }.distinct()
    }

    /** Bỏ đuôi xem-phim để lấy base chi tiết, dùng làm Referer hợp lệ. */
    private fun detailBaseOf(watchUrl: String): String {
        val base = watchUrl.substringBefore("xem-phim").trimEnd('/')
        return if (base.contains("/phim/")) base else mainUrl
    }

    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        var linkFound = false
        val runT0 = System.currentTimeMillis()
        val stepNotes = mutableListOf<String>()

        // CloudStream chạy fixUrl() trên data của newEpisode — nếu data đã là URL
        // tuyệt đối thì giữ nguyên; nếu là URL chi tiết thì dựng trang xem chuẩn.
        var watchUrl = data.trim()
        if (!watchUrl.contains("xem-phim") && watchUrl.contains("/phim/")) {
            watchUrl = watchUrl.trimEnd('/') + "/xem-phim.html"
        }
        nlog("[loadLinks] watch=$watchUrl")
        ntoastV("AnimeVsub $AVS_BUILD: đang lấy link…")

        coroutineScope {
            // ═══ STEP 1: HTML trang xem (plain HTTP + cookie clearance) ═══
            if (!linkFound) {
                try {
                    val doc = fetchDoc(watchUrl, useCache = false, mode = MODE_DETAIL)
                    val html = doc.outerHtml()

                    val (m3u8s, mp4s) = harvestMediaUrls(html)
                    nlog("[Step1] media m3u8=${m3u8s.size} mp4=${mp4s.size}")
                    for (m3u8 in m3u8s) {
                        val content = fetchText(m3u8, referer = detailBaseOf(watchUrl)) ?: continue
                        if (content.contains("#EXTM3U")) {
                            if (registerM3U8Link(content, m3u8, detailBaseOf(watchUrl), "Vietsub", callback)) {
                                linkFound = true
                            }
                        }
                        if (linkFound) break
                    }
                    if (!linkFound) {
                        for (mp4 in mp4s) {
                            nlog("[Step1] MP4 direct ${mp4.take(90)}")
                            callback(newExtractorLink(name, "MP4", mp4, ExtractorLinkType.VIDEO) {
                                quality = Qualities.P720.value
                                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to detailBaseOf(watchUrl))
                            })
                            linkFound = true
                            break
                        }
                    }

                    // ── Nếu chưa có: đào iframe embed (tối đa MAX_EMBEDS, song song) ──
                    if (!linkFound) {
                        val embeds = harvestIframes(doc).take(MAX_EMBEDS)
                        nlog("[Step1] iframes=${embeds.size} ${embeds.joinToString(" | ") { it.take(70) }}")
                        val results = embeds.map { embed ->
                            async { extractFromEmbed(embed, watchUrl, callback) }
                        }.awaitAll()
                        linkFound = results.any { it }
                    }
                    stepNotes.add("S1:${if (linkFound) "HIT" else "0"}")
                } catch (e: Throwable) {
                    if (isCancellation(e)) throw e
                    nlogE("[Step1]", e)
                    stepNotes.add("S1:ERR")
                }
            }

            // ═══ STEP 2: WebView + JS hook (giải gate Turnstile + bắt playlist) ═══
            if (!linkFound) {
                try {
                    if (tryHookFlow(watchUrl, callback)) linkFound = true
                    stepNotes.add("S2:${if (linkFound) "HIT" else "0"}")
                } catch (e: Throwable) {
                    if (isCancellation(e)) throw e
                    nlogE("[Step2]", e)
                    stepNotes.add("S2:ERR")
                }
            }

            // ═══ STEP 3: WebViewResolver broad trên trang xem ═══
            if (!linkFound) {
                try {
                    val t3 = System.currentTimeMillis()
                    val resp = app.get(watchUrl, interceptor = WebViewResolver(
                        Regex(""".*\.(m3u8|m3u9|mp4)(\?|$)""")
                    ), headers = commonHeaders)
                    val content = resp.text
                    val capturedUrl = resp.url ?: ""
                    nlog("[Step3] wvr captured=${capturedUrl.take(90)} len=${content.length} ms=${System.currentTimeMillis() - t3}")
                    if (content.contains("#EXTM3U")) {
                        if (registerM3U8Link(content, capturedUrl.ifBlank { watchUrl }, "", "Vietsub", callback)) {
                            linkFound = true
                        }
                        if (!linkFound) nlog("[Step3] có EXTM3U nhưng register thất bại")
                    } else {
                        val (m3u8s, mp4s) = harvestMediaUrls(content)
                        nlog("[Step3] harvest m3u8=${m3u8s.size} mp4=${mp4s.size}")
                        val firstM3u8 = m3u8s.firstOrNull()
                        val firstMp4 = mp4s.firstOrNull()
                        if (firstM3u8 != null) {
                            val c = fetchText(firstM3u8, referer = watchUrl)
                            if (c != null && c.contains("#EXTM3U") &&
                                registerM3U8Link(c, firstM3u8, detailBaseOf(watchUrl), "Vietsub", callback)) {
                                linkFound = true
                            }
                        } else if (firstMp4 != null) {
                            nlog("[Step3] MP4 direct ${firstMp4.take(90)}")
                            callback(newExtractorLink(name, "MP4", firstMp4, ExtractorLinkType.VIDEO) {
                                quality = Qualities.P720.value
                                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)
                            })
                            linkFound = true
                        }
                    }
                    stepNotes.add("S3:${if (linkFound) "HIT" else "0"}")
                } catch (e: Throwable) {
                    if (isCancellation(e)) throw e
                    nlogE("[Step3]", e)
                    stepNotes.add("S3:ERR")
                }
            }

            // ═══ STEP 4: WebViewResolver broad trên từng embed (nếu còn đường) ═══
            if (!linkFound) {
                try {
                    val embeds = harvestIframes(fetchDoc(watchUrl, useCache = false, mode = MODE_DETAIL)).take(MAX_EMBEDS)
                    nlog("[Step4] embeds=${embeds.size} ${embeds.joinToString(" | ") { it.take(60) }}")
                    for (embed in embeds) {
                        try {
                            val resp = app.get(embed, interceptor = WebViewResolver(
                                Regex(""".*\.(m3u8|m3u9|mp4)(\?|$)""")
                            ), headers = commonHeaders + mapOf("Referer" to watchUrl))
                            val content = resp.text
                            nlog("[Step4] ${embed.take(60)} -> ${resp.code} len=${content.length}")
                            if (content.contains("#EXTM3U")) {
                                if (registerM3U8Link(content, resp.url ?: embed, "", "Vietsub", callback)) {
                                    linkFound = true; break
                                }
                            }
                        } catch (e: Throwable) {
                            if (isCancellation(e)) throw e
                            nlogE("[Step4] embed", e, embed.take(60))
                        }
                    }
                    stepNotes.add("S4:${if (linkFound) "HIT" else "0"}")
                } catch (e: Throwable) {
                    if (isCancellation(e)) throw e
                    nlogE("[Step4]", e)
                    stepNotes.add("S4:ERR")
                }
            }
        }

        nlog("[loadLinks] SUMMARY ${stepNotes.joinToString(" | ")} -> result=$linkFound ms=${System.currentTimeMillis() - runT0} watch=$watchUrl")
        if (!linkFound) {
            ntoast("AnimeVsub $AVS_BUILD: không lấy được link — xem animevsub_diag.txt")
        }
        return linkFound
    }

    /** Đào 1 embed: HTML -> m3u8/mp4; nếu không thấy, đào iframe lồng thêm 1 cấp. */
    private suspend fun extractFromEmbed(
        embed: String, watchUrl: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = fetchDoc(embed, useCache = false, mode = MODE_LOOSE)
            val html = doc.outerHtml()
            val embedOrigin = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: mainUrl

            val (m3u8s, mp4s) = harvestMediaUrls(html)
            nlog("[Embed] $embed -> m3u8=${m3u8s.size} mp4=${mp4s.size}")

            for (m3u8 in m3u8s) {
                val content = fetchText(m3u8, referer = embedOrigin) ?: continue
                if (content.contains("#EXTM3U") &&
                    registerM3U8Link(content, m3u8, embedOrigin, "Vietsub", callback)) {
                    return true
                }
            }
            mp4s.firstOrNull()?.let {
                callback(newExtractorLink(name, "MP4", it, ExtractorLinkType.VIDEO) {
                    quality = Qualities.P720.value
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embedOrigin)
                })
                return true
            }

            // 1 cấp lồng: embed lại chứa iframe con.
            val nested = harvestIframes(doc).filter { it != embed }.firstOrNull()
            if (nested != null) {
                val nestedDoc = fetchDoc(nested, useCache = false, mode = MODE_LOOSE)
                val nestedHtml = nestedDoc.outerHtml()
                val nestedOrigin = Regex("""^(https?://[^/]+)""").find(nested)?.groupValues?.get(1) ?: embedOrigin
                val (nm3u8, nmp4) = harvestMediaUrls(nestedHtml)
                for (m3u8 in nm3u8) {
                    val content = fetchText(m3u8, referer = nestedOrigin) ?: continue
                    if (content.contains("#EXTM3U") &&
                        registerM3U8Link(content, m3u8, nestedOrigin, "Vietsub", callback)) {
                        return true
                    }
                }
                nmp4.firstOrNull()?.let {
                    callback(newExtractorLink(name, "MP4", it, ExtractorLinkType.VIDEO) {
                        quality = Qualities.P720.value
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to nestedOrigin)
                    })
                    return true
                }
            }
        } catch (e: Throwable) {
            if (isCancellation(e)) throw e
            nlogE("[Embed]", e, embed.take(70))
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  WEBVIEW + JS HOOK FLOW (port từ NguonC tryStreamcWebViewFlow v5/v14)
    //  WebView nạp trang xem; hook JS monkey-patch window.fetch + XMLHttpRequest
    //  để bắt mọi response chứa playlist (#EXTM3U hoặc URL .m3u8). Trang gate
    //  "Xác Minh An Toàn"/Turnstile tự giải trong WebView thật của thiết bị; khi
    //  có Activity, WebView được đặt trong Dialog full-screen để người dùng có
    //  thể tick "Verify you are human" nếu cần.
    // ═══════════════════════════════════════════════════════════════════════════

    private val avsHookJs = """
(function(){
  if (window.__avsHooked) return; window.__avsHooked = true;
  function abs(u){
    var s = String(u);
    if (s.indexOf('http') === 0) return s;
    if (s.charAt(0) === '/') return location.origin + s;
    return location.origin + '/' + s;
  }
  function pass(u, b){
    try {
      if (!b || b.length < 8) return;
      var sig = (String(u).length + ':' + b.length + ':' + b.substring(0, 24));
      window.__avsSeen = window.__avsSeen || {};
      if (window.__avsSeen[sig]) return;
      if (Object.keys(window.__avsSeen).length > 64) window.__avsSeen = {};
      window.__avsSeen[sig] = 1;
      var s = String(u);
      var isPlaylist = b.indexOf('#EXTM3U') >= 0;
      var isMediaUrl = s.indexOf('.m3u8') >= 0 || s.indexOf('.m3u9') >= 0 || s.indexOf('.mp4') >= 0;
      if (isPlaylist || (isMediaUrl && s.indexOf('http') === 0)) {
        window.__avsBridge.onCapture(abs(u), b);
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
    XMLHttpRequest.prototype.open = function(m, u){ this.__avsU = u; return oo.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function(){
      var self = this;
      this.addEventListener('load', function(){
        try { var t = xhrText(self); if (t) { pass(self.__avsU, t); } } catch (e) {}
      });
      return os.apply(this, arguments);
    };
  } catch (e) {}
})();
"""

    /** JS -> Kotlin bridge. Kotlin handler không được trùng tên @JavascriptInterface
     *  (bài học NguonC v3: trùng tên -> StackOverflowError giết cả data path). */
    private class AvsCaptureBridge(
        private val handler: (String, String) -> Unit
    ) {
        @JavascriptInterface
        fun onCapture(url: String?, body: String?) {
            if (url.isNullOrBlank() || body.isNullOrBlank()) return
            try { handler(url, body) } catch (_: Throwable) {}
        }
    }

    /** Context cho WebView: Activity foreground của CloudStream -> Application. */
    private fun appContext(): android.content.Context? {
        try {
            val cls = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            for (name in listOf("activity", "currentActivity")) {
                try {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    (f.get(null) as? android.content.Context)?.let { return it }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return diagContext()
    }

    private suspend fun tryHookFlow(targetUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val flowT0 = System.currentTimeMillis()
        var webView: WebView? = null
        var dialog: android.app.Dialog? = null
        try {
            val ctx = appContext()
            if (ctx == null) {
                nlog("[Hook] không có app context cho WebView")
                return false
            }

            val captures = Channel<Pair<String, String>>(Channel.UNLIMITED)
            val captureCount = java.util.concurrent.atomic.AtomicInteger(0)

            withContext(Dispatchers.Main) {
                val wv = WebView(ctx)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.allowFileAccess = false
                wv.settings.allowContentAccess = false
                wv.settings.mediaPlaybackRequiresUserGesture = false
                try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Throwable) {}
                wv.addJavascriptInterface(
                    AvsCaptureBridge { url, body ->
                        captureCount.incrementAndGet()
                        nlog("[Hook] bridge +${System.currentTimeMillis() - flowT0}ms url=${url.take(60)} len=${body.length} head=${body.take(12)}")
                        captures.trySend(url to body)
                    },
                    "__avsBridge"
                )
                wv.webChromeClient = WebChromeClient()
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        try { nlog("[Hook] page-start ${url?.take(90)}") } catch (_: Throwable) {}
                        view?.evaluateJavascript(avsHookJs, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        try { nlog("[Hook] page-finish ${url?.take(90)}") } catch (_: Throwable) {}
                        view?.evaluateJavascript(avsHookJs, null)
                    }
                    override fun onReceivedError(
                        view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?
                    ) {
                        try { nlog("[Hook] WV error ${error?.errorCode} ${error?.description} ${request?.url}") } catch (_: Throwable) {}
                    }
                }

                // Dialog full-screen khi có Activity: gate Turnstile có người dùng hỗ trợ tick.
                if (ctx is android.app.Activity && !ctx.isFinishing) {
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
                        dlg.setOnDismissListener { captures.close() }
                        dlg.show()
                        dialog = dlg
                    } catch (e: Throwable) {
                        nlogE("[Hook] dialog", e, "chạy headless")
                    }
                }

                wv.loadUrl(targetUrl, mapOf("Referer" to "$mainUrl/"))
            }

            var registered = false
            val hookResult = withTimeoutOrNull(HOOK_TIMEOUT_MS) {
                var tries = 0
                while (!registered && tries < 6) {
                    tries++
                    val recv = try { captures.receive() } catch (e: Throwable) { nlogE("[Hook] receive", e); null }
                    if (recv == null) break
                    val (capUrl, capBody) = recv
                    // Có playlist -> dừng trang để player không tải tiếp quảng cáo/segment.
                    try {
                        withContext(Dispatchers.Main) {
                            webView?.stopLoading()
                            webView?.loadUrl("about:blank")
                        }
                    } catch (_: Throwable) {}

                    val body = capBody.trim()
                    if (body.startsWith("#EXTM3U")) {
                        val base = Regex("""^(https?://[^/]+/.*/)""").find(capUrl)?.groupValues?.get(1) ?: ""
                        if (registerM3U8Link(body, capUrl, base, "Vietsub", callback)) {
                            registered = true
                        }
                    } else {
                        val (m3u8s, mp4s) = harvestMediaUrls(body)
                        val m3u8 = m3u8s.firstOrNull()
                        when {
                            m3u8 != null -> {
                                val origin = Regex("""^(https?://[^/]+)""").find(m3u8)?.groupValues?.get(1) ?: ""
                                val content = fetchText(m3u8, referer = origin.ifBlank { targetUrl })
                                if (content != null && content.contains("#EXTM3U") &&
                                    registerM3U8Link(content, m3u8, origin, "Vietsub", callback)) {
                                    registered = true
                                }
                            }
                            mp4s.firstOrNull() != null -> {
                                callback(newExtractorLink(name, "MP4", mp4s.first(), ExtractorLinkType.VIDEO) {
                                    quality = Qualities.P720.value
                                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to targetUrl)
                                })
                                registered = true
                            }
                        }
                    }
                }
                registered
            }

            // Cookie clearance từ WebView dùng lại cho plain HTTP các lần sau.
            try {
                val cookies = CookieManager.getInstance().getCookie(mainUrl)
                if (!cookies.isNullOrBlank()) {
                    cfCookieCache[mainUrl] = cookies.split(";").mapNotNull {
                        val idx = it.indexOf('=')
                        if (idx <= 0) null else it.substring(0, idx).trim() to it.substring(idx + 1).trim()
                    }.toMap()
                    nlog("[Hook] cookie-cache +${cfCookieCache[mainUrl]?.size ?: 0} cookie")
                }
            } catch (e: Throwable) {
                nlogE("[Hook] cookie-save", e)
            }

            if (registered) {
                nlog("[Hook] OK trong +${System.currentTimeMillis() - flowT0}ms")
            } else if (hookResult == null) {
                nlog("[Hook] TIMEOUT ${HOOK_TIMEOUT_MS / 1000}s captures=${captureCount.get()} — có thể gate cần người dùng tick")
            } else {
                nlog("[Hook] ${captureCount.get()} capture, không có playlist khả dụng")
            }
            return registered
        } finally {
            try { withContext(Dispatchers.Main) { dialog?.dismiss() } } catch (_: Throwable) {}
            try { withContext(Dispatchers.Main) { webView?.destroy() } } catch (_: Throwable) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  WEBVIEW DOM CAPTURE + GATE UNLOCK (v4 — log 25-10 11:09: webview "OK" vẫn
    //  0 items mọi danh mục. Nguyên nhân: WebViewResolver trả RAW HTML TRƯỚC KHI
    //  JS render — site render danh sách phim bằng AJAX nên HTML thô không có
    //  link /phim/; hoặc gate chưa xong. Fix: WebView tự quản lý, poll DOM mỗi
    //  0.8s qua JS bridge, CHỈ nhận khi DOM có dữ liệu phim theo mode. Silent
    //  trước (không UI); nếu gate vẫn chặn -> unlockGate() mở Dialog full-screen
    //  cho user tick "Xác Minh An Toàn" MỘT lần, clearance cookie dùng lại sau.)
    // ═══════════════════════════════════════════════════════════════════════════

    private val gateMutex = Mutex()
    @Volatile private var lastGateAttemptMs = 0L
    private val GATE_RETRY_MS = 10 * 60 * 1000L
    private val GATE_OPEN_TIMEOUT_MS = 90_000L

    // Poll DOM mỗi 0.8s gửi về Kotlin qua bridge __avsGate (tên KHÁC hook bridge
    // __avsBridge — bài học NguonC v3: trùng tên @JavascriptInterface -> crash).
    private val domPollJs = """
(function(){
  if (window.__avsDomLoop) return; window.__avsDomLoop = true;
  function send(){
    try {
      var h = document.documentElement.outerHTML;
      if (h && h.length > 64) window.__avsGate.onDom(String(location.href), h);
    } catch (e) {}
  }
  send();
  setInterval(send, 800);
})();
"""

    /** JS -> Kotlin bridge cho DOM poll. */
    private class AvsGateBridge(private val handler: (String, String) -> Unit) {
        @JavascriptInterface
        fun onDom(url: String?, html: String?) {
            if (url.isNullOrBlank() || html.isNullOrBlank()) return
            try { handler(url, html) } catch (_: Throwable) {}
        }
    }

    /**
     * Nạp url trong WebView TỰ QUẢN LÝ, poll DOM mỗi 0.8s:
     *  - MODE_LISTING: hoàn tất khi DOM có link /phim/{slug}-a{id} (cards đã render)
     *  - MODE_DETAIL : hoàn tất khi có link xem-phim, hoặc sau ~8 poll (~6.4s)
     *  - MODE_LOOSE  : hoàn tất khi DOM sạch sau 4 poll (~3.2s)
     * Nếu mọi poll đều blocked (gate chưa giải) -> trả null để caller escalate
     * sang unlockGate() (interactive). interactive=true: WebView nằm trong Dialog
     * full-screen để người dùng tick xác minh. Timeout: trả DOM sạch cuối cùng
     * (nếu có) thay vì null — trang trúng nhưng ít dữ liệu vẫn được dùng.
     */
    private suspend fun webviewDocCapture(
        url: String, mode: Int, timeoutMs: Long, interactive: Boolean
    ): Document? {
        val ctx = appContext()
        if (ctx == null) {
            nlog("[WVDom] không có context — bỏ qua webview $url")
            return null
        }
        if (interactive && (ctx !is android.app.Activity || ctx.isFinishing)) return null

        val done = kotlinx.coroutines.CompletableDeferred<Document?>()
        var webView: WebView? = null
        var dialog: android.app.Dialog? = null
        var lastHtml: String? = null
        var lastBlocked = true
        try {
            withContext(Dispatchers.Main) {
                val wv = WebView(ctx)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                // v4: UA ĐỒNG BỘ với plain HTTP — cf_clearance gắn với UA, lệch UA là 403.
                wv.settings.userAgentString = USER_AGENT
                wv.settings.mediaPlaybackRequiresUserGesture = false
                // Chặn tải ảnh: DOM sẵn sàng nhanh hơn hẳn, tiết kiệm băng thông.
                try { wv.settings.blockNetworkImage = true } catch (_: Throwable) {}
                try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Throwable) {}

                var lastSig = ""
                var pollIdx = 0
                wv.addJavascriptInterface(AvsGateBridge { u, html ->
                    if (done.isCompleted) return@AvsGateBridge
                    pollIdx++
                    val sig = html.length.toString() + ":" + html.take(120).hashCode()
                    if (sig == lastSig) return@AvsGateBridge   // DOM không đổi — bỏ qua poll
                    lastSig = sig
                    val blocked = looksBlocked(html)
                    val cards = countPhimAnchors(html)
                    val xem = countXemPhim(html)
                    nlog("[WVDom] poll#$pollIdx len=${html.length} blocked=$blocked cards=$cards xem=$xem ${u.take(70)}")
                    lastHtml = html
                    lastBlocked = blocked
                    val ready = !blocked && when (mode) {
                        MODE_LISTING -> cards > 0
                        MODE_DETAIL  -> xem > 0 || cards > 0 || pollIdx >= 8
                        else         -> pollIdx >= 4 || cards > 0 || xem > 0
                    }
                    if (ready) {
                        saveCfCookiesFromManager("WVDom")
                        try { wv.stopLoading() } catch (_: Throwable) {}
                        done.complete(Jsoup.parse(html, u))
                    }
                }, "__avsGate")

                wv.webChromeClient = WebChromeClient()
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                        try { nlog("[WVDom] page-start ${u?.take(90)}") } catch (_: Throwable) {}
                        view?.evaluateJavascript(domPollJs, null)
                    }
                    override fun onPageFinished(view: WebView?, u: String?) {
                        try { nlog("[WVDom] page-finish ${u?.take(90)}") } catch (_: Throwable) {}
                        view?.evaluateJavascript(domPollJs, null)
                    }
                }

                if (interactive && ctx is android.app.Activity && !ctx.isFinishing) {
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
                        dlg.setCancelable(true)   // Back = huỷ unlock
                        dlg.setOnDismissListener {
                            try { if (!done.isCompleted) done.complete(null) } catch (_: Throwable) {}
                        }
                        dlg.show()
                        dialog = dlg
                    } catch (e: Throwable) {
                        // Vẫn chạy headless — challenge đôi khi tự giải mà không cần user.
                        nlogE("[WVDom] dialog", e, "chạy headless")
                    }
                }

                wv.loadUrl(url, mapOf("Referer" to "$mainUrl/"))
            }

            val t0 = System.currentTimeMillis()
            var doc = withTimeoutOrNull(timeoutMs) { done.await() }
            if (doc != null) {
                nlog("[WVDom] OK interactive=$interactive ms=${System.currentTimeMillis() - t0} $url")
            } else {
                // Timeout/huỷ: nếu đã có DOM sạch (chưa "ready" vì thiếu cards/xem)
                // thì vẫn trả — caller tự quyết (getMainPage sẽ thử candidate kế).
                val lh = lastHtml
                if (lh != null && !lastBlocked) {
                    nlog("[WVDom] TIMEOUT nhưng DOM sạch (len=${lh.length}) — trả DOM cuối $url")
                    doc = Jsoup.parse(lh, url)
                } else {
                    nlog("[WVDom] TIMEOUT/BỊ CHẶN ${timeoutMs / 1000}s interactive=$interactive $url")
                }
            }
            return doc
        } finally {
            try { withContext(Dispatchers.Main) { dialog?.dismiss() } } catch (_: Throwable) {}
            try { withContext(Dispatchers.Main) { webView?.destroy() } } catch (_: Throwable) {}
        }
    }

    /**
     * Unlock tương tác (v4): Dialog full-screen nạp url; thành công khi DOM có
     * dữ liệu theo mode (đúng tiêu chí webviewDocCapture). gateMutex bảo đảm
     * 1 dialog duy nhất; tối đa 1 lần chủ động / 10 phút (GATE_RETRY_MS).
     */
    private suspend fun unlockGate(url: String, mode: Int = MODE_LISTING): Document? = gateMutex.withLock {
        if (System.currentTimeMillis() - lastGateAttemptMs < GATE_RETRY_MS) {
            // Có unlock khác đang chạy/vừa chạy — trả null để caller thử đường khác.
            nlog("[Gate] bỏ qua (đã có unlock trong ${GATE_RETRY_MS / 60000} phút gần đây)")
            return@withLock null
        }
        lastGateAttemptMs = System.currentTimeMillis()

        val ctx = appContext()
        if (ctx !is android.app.Activity || ctx.isFinishing) {
            nlog("[Gate] không có Activity foreground — không mở dialog xác minh")
            return@withLock null
        }

        nlog("[Gate] mở dialog xác minh: $url")
        ntoast("AnimeVsub: site yêu cầu xác minh — hãy hoàn tất trong cửa sổ vừa mở")
        val doc = webviewDocCapture(url, mode, GATE_OPEN_TIMEOUT_MS, interactive = true)
        if (doc != null) nlog("[Gate] OK") else nlog("[Gate] TIMEOUT/HỦY sau ${GATE_OPEN_TIMEOUT_MS / 1000}s")
        doc
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HLS PROXY CỤC BỘ (port gọn từ NguonCProxyServer)
    //  Serve /stream.m3u8 (playlist đã rewrite) + /seg/{url-encoded} (relay segment).
    //  Mỗi segment thử OkHttp (app.baseClient) rồi HttpURLConnection; stream 64KB;
    //  KHÔNG BAO GIỜ serve trang Cloudflare/HTML làm video (check status + content-type).
    // ═══════════════════════════════════════════════════════════════════════════

    private val activeServers = mutableListOf<AvsProxyServer>()

    private fun rewriteM3U8(m3u8: String, proxyBase: String, m3u8BaseUrl: String = ""): String {
        return m3u8.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isBlank()) {
                line
            } else if (trimmed.startsWith("http")) {
                "$proxyBase/seg/${URLEncoder.encode(trimmed, "UTF-8")}"
            } else if (trimmed.isNotEmpty()) {
                val resolvedUrl = if (m3u8BaseUrl.isNotEmpty()) {
                    "${m3u8BaseUrl.trimEnd('/')}/$trimmed"
                } else {
                    trimmed
                }
                if (resolvedUrl.startsWith("http")) {
                    "$proxyBase/seg/${URLEncoder.encode(resolvedUrl, "UTF-8")}"
                } else {
                    line
                }
            } else {
                line
            }
        }
    }

    /** Probe 3 segment đầu; trả về số segment đầu cần cắt (0 nếu không cần). */
    private suspend fun preflightTrimCount(segUrls: List<String>, referer: String): Int {
        if (!PREFLIGHT_TRIM || segUrls.isEmpty()) return 0
        val probe = segUrls.take(3)
        var firstOk = -1
        probe.forEachIndexed { idx, segUrl ->
            val ok = try {
                val req = okhttp3.Request.Builder().url(segUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", referer)
                    .header("Range", "bytes=0-1024")
                    .build()
                segClient.newCall(req).execute().use { resp ->
                    resp.code in 200..299 &&
                        !(resp.header("Content-Type") ?: "").contains("text/html", ignoreCase = true)
                }
            } catch (_: Throwable) { false }
            if (ok && firstOk < 0) firstOk = idx
        }
        return if (firstOk > 0) firstOk else 0
    }

    /** Tạo proxy server cho 1 playlist và phát link ExtractorLink về CloudStream. */
    private suspend fun registerM3U8Link(
        m3u8Content: String, referer: String, m3u8BaseUrl: String,
        serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!m3u8Content.contains("#EXTM3U")) return false

        val segUrls = m3u8Content.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if (it.startsWith("http") || m3u8BaseUrl.isEmpty()) it else m3u8BaseUrl.trimEnd('/') + "/" + it }
        nlog("[Register] '$serverName' segments=${segUrls.size} first=${segUrls.firstOrNull()?.take(100)} referer=${referer.take(60)}")

        val trim = preflightTrimCount(segUrls, referer)
        if (trim > 0) nlog("[Register] trim $trim segment đầu (probe chặn)")

        // Giới hạn số proxy server sống đồng thời (tránh cạn port trong session dài).
        synchronized(activeServers) {
            while (activeServers.size >= 8) {
                try { activeServers.removeAt(0).stop() } catch (_: Throwable) {}
            }
        }

        val server = AvsProxyServer(referer)
        server.label = serverName
        try {
            server.start()
        } catch (e: Throwable) {
            nlogE("[Register] bind", e, "không mở được port cục bộ — bỏ qua server này")
            return false
        }
        synchronized(activeServers) { activeServers.add(server) }

        val proxyBase = "http://127.0.0.1:${server.port}"
        server.setM3U8(rewriteM3U8(trimLeadingSegments(m3u8Content, trim), proxyBase, m3u8BaseUrl))
        callback(newExtractorLink(name, serverName, "$proxyBase/stream.m3u8", ExtractorLinkType.M3U8) {
            quality = Qualities.P1080.value
            headers = mapOf("User-Agent" to USER_AGENT)
        })
        nlog("[Register] emitted $proxyBase/stream.m3u8 segs=${segUrls.size}")
        return true
    }

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

    private val segClient: okhttp3.OkHttpClient by lazy { app.baseClient }

    private inner class AvsProxyServer(private val referer: String) {
        @Volatile var label: String = ""
        @Volatile var port: Int = -1
            private set

        private var serverSocket: java.net.ServerSocket? = null
        private val threadPool = java.util.concurrent.Executors.newCachedThreadPool()
        @Volatile private var _m3u8: String = ""
        private val okLogCount = java.util.concurrent.atomic.AtomicInteger(0)

        fun getM3U8(): String = _m3u8
        fun setM3U8(v: String) { _m3u8 = v }

        fun start() {
            val ss = try {
                java.net.ServerSocket(0, 64, java.net.InetAddress.getByName("127.0.0.1"))
            } catch (e: Throwable) {
                nlogE("[Proxy] bind", e)
                throw e
            }
            serverSocket = ss
            port = ss.localPort
            threadPool.execute {
                try {
                    while (!ss.isClosed) {
                        val client = ss.accept()
                        threadPool.execute { handleClient(client) }
                    }
                } catch (_: Throwable) {}
            }
            nlog("[Proxy] started port=$port label=$label referer=${referer.take(50)}")
        }

        fun stop() {
            try { serverSocket?.close() } catch (_: Throwable) {}
            try { threadPool.shutdownNow() } catch (_: Throwable) {}
            nlog("[Proxy] stopped port=$port")
        }

        private fun handleClient(client: java.net.Socket) {
            try {
                client.soTimeout = 15_000
                val input  = client.getInputStream().bufferedReader()
                val output = client.getOutputStream()
                val requestLine = input.readLine() ?: return
                while (true) {
                    val h = input.readLine() ?: ""
                    if (h.isBlank()) break
                }

                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val crlf = "\r\n"

                when {
                    path == "/stream.m3u8" -> {
                        val body = getM3U8().toByteArray(Charsets.UTF_8)
                        if (body.isEmpty()) nlog("[Proxy] stream.m3u8 rỗng khi phát (port=$port label=$label)")
                        output.write(("HTTP/1.1 200 OK${crlf}Content-Type: application/vnd.apple.mpegurl${crlf}Content-Length: ${body.size}${crlf}Access-Control-Allow-Origin: *${crlf}${crlf}").toByteArray())
                        output.write(body)
                    }
                    path.startsWith("/seg/") -> {
                        val segUrl = try {
                            java.net.URLDecoder.decode(path.removePrefix("/seg/"), "UTF-8")
                        } catch (_: Throwable) { return }
                        val t0 = System.currentTimeMillis()
                        var ok = false

                        // ── Method 0: OkHttp (CloudStream baseClient) ──
                        if (!ok) ok = viaOkHttp(segUrl, output, crlf, t0)
                        // ── Method 1: OkHttp với Referer = origin/ ──
                        if (!ok) {
                            val origin = Regex("""^(https?://[^/]+)""").find(referer)?.groupValues?.get(1) ?: ""
                            if (origin.isNotEmpty()) ok = viaOkHttpOrigin(segUrl, "$origin/", output, crlf, t0)
                        }
                        // ── Method 2: HttpURLConnection (không chunked, UA riêng) ──
                        if (!ok) ok = viaUrlConnection(segUrl, output, crlf, t0)

                        if (!ok) {
                            val body = "502 upstream blocked".toByteArray(Charsets.UTF_8)
                            output.write(("HTTP/1.1 502 Bad Gateway${crlf}Content-Length: ${body.size}${crlf}$crlf").toByteArray())
                            output.write(body)
                            nlog("[Seg] ALL-METHODS-FAILED url=...${segUrl.takeLast(40)}")
                        }
                    }
                    else -> {
                        val body = "404".toByteArray()
                        output.write(("HTTP/1.1 404 Not Found${crlf}Content-Length: ${body.size}${crlf}$crlf").toByteArray())
                        output.write(body)
                    }
                }
                output.flush()
            } catch (e: Throwable) {
                nlogE("[ProxySrv]", e, "port=$port")
            } finally {
                try { client.close() } catch (_: Throwable) {}
            }
        }

        private fun isBlockedResponse(code: Int, ct: String?): Boolean =
            code !in 200..299 || (ct ?: "").contains("text/html", ignoreCase = true)

        private fun viaOkHttp(segUrl: String, output: java.io.OutputStream, crlf: String, t0: Long): Boolean {
            val req = okhttp3.Request.Builder().url(segUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "*/*")
                .build()
            return try {
                segClient.newCall(req).execute().use { resp ->
                    val ct = resp.header("Content-Type")
                    if (isBlockedResponse(resp.code, ct)) {
                        nlog("[Seg] OKHTTP ${resp.code} ct=$ct url=...${segUrl.takeLast(40)}")
                        return false
                    }
                    val body = resp.body ?: return false
                    val head = StringBuilder("HTTP/1.1 200 OK").append(crlf)
                        .append("Content-Type: video/mp2t").append(crlf)
                        .append("Access-Control-Allow-Origin: *").append(crlf)
                    val len = body.contentLength()
                    if (len > 0) head.append("Content-Length: ").append(len).append(crlf)
                    else head.append("Connection: close").append(crlf)
                    head.append(crlf)
                    output.write(head.toString().toByteArray())
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
                    if (okLogCount.incrementAndGet() <= 40) {
                        nlog("[Seg] OKHTTP OK bytes=$sent ttfb=${System.currentTimeMillis() - t0}ms url=...${segUrl.takeLast(26)}")
                    }
                    true
                }
            } catch (e: Throwable) {
                nlog("[Seg] OKHTTP err ${e.message} url=...${segUrl.takeLast(40)}")
                false
            }
        }

        private fun viaOkHttpOrigin(segUrl: String, originRef: String, output: java.io.OutputStream, crlf: String, t0: Long): Boolean {
            val req = okhttp3.Request.Builder().url(segUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", originRef)
                .header("Accept", "*/*")
                .build()
            return try {
                segClient.newCall(req).execute().use { resp ->
                    val ct = resp.header("Content-Type")
                    if (isBlockedResponse(resp.code, ct)) {
                        nlog("[Seg] OKHTTP-ORIGIN ${resp.code} ct=$ct url=...${segUrl.takeLast(40)}")
                        return false
                    }
                    val body = resp.body ?: return false
                    val head = StringBuilder("HTTP/1.1 200 OK").append(crlf)
                        .append("Content-Type: video/mp2t").append(crlf)
                        .append("Access-Control-Allow-Origin: *").append(crlf)
                    val len = body.contentLength()
                    if (len > 0) head.append("Content-Length: ").append(len).append(crlf)
                    else head.append("Connection: close").append(crlf)
                    head.append(crlf)
                    output.write(head.toString().toByteArray())
                    val buf = ByteArray(65536)
                    body.byteStream().use { stream ->
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            output.flush()
                        }
                    }
                    true
                }
            } catch (e: Throwable) {
                nlogE("[Seg] OKHTTP-ORIGIN", e, "...${segUrl.takeLast(40)}")
                false
            }
        }

        private fun viaUrlConnection(segUrl: String, output: java.io.OutputStream, crlf: String, t0: Long): Boolean {
            return try {
                val conn = java.net.URL(segUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Referer", referer)
                conn.setRequestProperty("Accept", "*/*")
                val code = conn.responseCode
                val ct = conn.contentType
                if (isBlockedResponse(code, ct)) {
                    nlog("[Seg] URLCONN $code ct=$ct url=...${segUrl.takeLast(40)}")
                    try { conn.disconnect() } catch (_: Throwable) {}
                    return false
                }
                val head = StringBuilder("HTTP/1.1 200 OK").append(crlf)
                    .append("Content-Type: video/mp2t").append(crlf)
                    .append("Access-Control-Allow-Origin: *").append(crlf)
                val len = conn.contentLengthLong
                if (len > 0) head.append("Content-Length: ").append(len).append(crlf)
                else head.append("Connection: close").append(crlf)
                head.append(crlf)
                output.write(head.toString().toByteArray())
                conn.inputStream.use { stream ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        output.flush()
                    }
                }
                true
            } catch (e: Throwable) {
                nlogE("[Seg] URLCONN", e, "...${segUrl.takeLast(40)}")
                false
            }
        }
    }
}
