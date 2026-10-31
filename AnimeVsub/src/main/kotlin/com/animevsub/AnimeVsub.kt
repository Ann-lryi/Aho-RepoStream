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
import java.net.URLDecoder
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

    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private val AVS_BUILD = "v33"

    private val AVS_FAST_REAL_PLAY = true
    private val REAL_STARTUP_GRACE_MS = 1_200L

    private val PLAIN_TIMEOUT_MS = 2_500L

    private val AVS_DIRECT_CANONICAL = true

    private val PREFLIGHT_TRIM = false

    private val MAX_EMBEDS = 3

    private val HOOK_TIMEOUT_MS = 60_000L

    private val WV_SILENT_TIMEOUT_MS = 22_000L

    private val MODE_LISTING = 0
    private val MODE_DETAIL = 1
    private val MODE_LOOSE = 2
    private val MODE_EPISODES = 3
    private val MODE_PLAY = 4

    private val commonHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    private val corsProxies = listOf(
        "https://api.allorigins.win/raw?url=",
        "https://api.codetabs.com/v1/proxy?quest="
    )

    private val proxyFailCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var proxyDisabledUntilMs = 0L
    private val PROXY_COOLDOWN_MS = 10 * 60 * 1000L

    private val fetchGate = java.util.concurrent.Semaphore(3)
    private val fetchRand = kotlin.random.Random

    private val plainDeadStreak = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var plainHtmlDeadUntilMs = 0L
    private val PLAIN_DEAD_THRESHOLD = 3

    private val PLAIN_DEAD_COOLDOWN_MS = 2 * 60 * 60 * 1000L

    private val stateRestored = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var canonicalHost: String? = null
    @Volatile private var canonicalConfirmed = false
    @Volatile private var canonicalOff = false

    private fun statePrefs(): android.content.SharedPreferences? = try {
        (diagContext() ?: appContext())
            ?.getSharedPreferences("avs_provider_state", android.content.Context.MODE_PRIVATE)
    } catch (_: Throwable) { null }

    private fun restoreStateOnce() {
        if (!stateRestored.compareAndSet(false, true)) return
        try {
            val p = statePrefs()
            if (p == null) { stateRestored.set(false); return }
            val now = System.currentTimeMillis()
            val until = p.getLong("plainDeadUntil", 0L)
            if (until > now && until > plainHtmlDeadUntilMs) {
                plainHtmlDeadUntilMs = until
                plainDeadStreak.set(PLAIN_DEAD_THRESHOLD)
                nlog("[Fetch] plain TẮT khôi phục từ phiên trước — còn ${(until - now) / 60000} phút (v30 C1)")
            }
            if (AVS_DIRECT_CANONICAL) {
                val ch = p.getString("canonicalHost", null)
                if (!ch.isNullOrBlank() && ch.contains("animevietsub") && ch != hostOf(mainUrl)) {
                    canonicalHost = ch
                    nlog("[Fetch] host chuẩn khôi phục: $ch (v30 C2)")
                }
            }
        } catch (_: Throwable) {}
    }

    private fun setPlainDead(untilMs: Long) {
        plainHtmlDeadUntilMs = untilMs
        try { statePrefs()?.edit()?.putLong("plainDeadUntil", untilMs)?.apply() } catch (_: Throwable) {}
    }

    private fun learnCanonical(pageUrl: String) {
        if (!AVS_DIRECT_CANONICAL || canonicalOff) return
        val h = hostOf(pageUrl)
        if (h.isEmpty() || h == hostOf(mainUrl) || !h.contains("animevietsub")) return
        if (canonicalHost != h) {
            canonicalHost = h
            nlog("[Fetch] học host chuẩn: $h (mainUrl=${hostOf(mainUrl)}) (v30 C2)")
            try { statePrefs()?.edit()?.putString("canonicalHost", h)?.apply() } catch (_: Throwable) {}
        }
        canonicalConfirmed = true
    }

    private fun toCanonical(url: String): String {
        val ch = canonicalHost ?: return url
        if (!AVS_DIRECT_CANONICAL || canonicalOff) return url
        val h = hostOf(url)
        if (h.isEmpty() || h != hostOf(mainUrl)) return url
        return url.replaceFirst(h, ch)
    }

    private val wvGate = java.util.concurrent.Semaphore(2)
    private val playWvGate = java.util.concurrent.Semaphore(1)
    private val inflightLock = Any()
    private val inflightDocs = HashMap<String, kotlinx.coroutines.CompletableDeferred<Document>>()

    private val junkHosts = listOf(
        "googleads", "doubleclick", "googlesyndication", "google-analytics",
        "facebook.com", "t.me", "telegram", "twitter.com", "x.com", "disqus",
        "gravatar", "recaptcha", "cloudflareinsights", "hotjar", "pushnami"
    )

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

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    android.widget.Toast.makeText(ctx, msg.take(160), android.widget.Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private val diagUrlQuery = Regex("""(?i)(https?://[^\s|"'<>?#]+)\?[^\s|"'<>]*""")
    private val diagHeaders = Regex("""(?i)\b(cookie|authorization|x-envelope|set-cookie)\s*[:=]\s*[^\r\n|]+""")
    private val diagFields = Regex("""(?i)(["']?(?:token|access_token|cf_clearance|__cf_bm)["']?\s*[:=]\s*["']?)[^"'\s,|}]+""")

    private fun redactDiag(msg: String): String {
        val noQueries = diagUrlQuery.replace(msg) { it.groupValues[1] + "?[redacted]" }
        val noHeaders = diagHeaders.replace(noQueries) { it.groupValues[1] + "=[redacted]" }
        return diagFields.replace(noHeaders) { it.groupValues[1] + "[redacted]" }
    }

    private fun nlog(msg: String) {
        val safeMessage = try { redactDiag(msg) } catch (_: Throwable) { "[redacted log]" }
        try { android.util.Log.d("AnimeVsub", safeMessage) } catch (_: Throwable) {}
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

                if (f.exists() && f.length() > 384 * 1024) {
                    f.writeText(f.readText().takeLast(128 * 1024))
                }
                val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                f.appendText("$ts $AVS_BUILD $safeMessage\n")
            }
        } catch (_: Throwable) {}
    }

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

    private fun isCancellation(e: Throwable?): Boolean =
        e is kotlinx.coroutines.CancellationException

    private fun pageTitleOf(html: String): String {
        return try {

            Regex("""(?is)<title[^>]*>(.*?)</title>""")
                .find(html)?.groupValues?.get(1)?.trim()?.take(60) ?: "?"
        } catch (_: Throwable) { "?" }
    }

    private data class CachedDoc(val doc: Document, val time: Long)

    private val docCache = ConcurrentHashMap<String, CachedDoc>()
    private val DOC_CACHE_TTL_MS = 5 * 60 * 1000L

    private fun cachedDocSupports(cached: CachedDoc, mode: Int): Boolean =
        mode != MODE_EPISODES || cached.doc.selectFirst("a[data-id]") != null

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

               lower.contains("cdn-cgi/challenge-platform") ||
               lower.contains("challenges.cloudflare.com") ||
               lower.contains("cf_chl_opt") ||
               lower.contains("_cf_chl") ||
               lower.contains("turnstile") ||
               (lower.contains("cloudflare") && lower.contains("challenge")) ||

               lower.contains("xác minh người dùng việt nam") ||
               lower.contains("challenge-running")
    }

    private fun titleIsServerError(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("lỗi server") || t.contains("server error") ||
               t.contains("lỗi 5") || t.contains("5xx") ||
               t.contains("bad gateway") || t.contains("service unavailable") ||
               t.contains("internal server") || t.contains("gateway time")
    }

    private fun looksServerError(html: String): Boolean =
        titleIsServerError(pageTitleOf(html))

    private fun docLooksServerError(doc: Document): Boolean =
        try { titleIsServerError(doc.title()) } catch (_: Throwable) { false }

    private val srvErrCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var siteDownUntilMs = 0L
    private val SITE_DOWN_THRESHOLD = 3
    private val SITE_DOWN_COOLDOWN_MS = 90_000L

    @Volatile private var srvStormUntilMs = 0L
    private val SRV_STORM_COOLDOWN_MS = 30_000L
    private val wvSrvErrTimes = ArrayDeque<Long>()

    private fun srvStormActive(): Boolean = System.currentTimeMillis() < srvStormUntilMs

    private fun srvStormError() = ErrorLoadingException(
        "SITE-5xx: server animevietsub đang trả lỗi 5xx (cả WebView đều thấy) — đợi ~${SRV_STORM_COOLDOWN_MS / 1000}s rồi thử lại; mở ${mainUrl} trong Chrome để kiểm tra (lỗi phía site, không phải plugin)"
    )

    private fun siteDown(): Boolean = System.currentTimeMillis() < siteDownUntilMs

    private fun siteDownError() = ErrorLoadingException(
        "SITE-DOWN: animevietsub.sale đang trả lỗi 5xx — đợi ${SITE_DOWN_COOLDOWN_MS / 1000}s rồi thử lại"
    )

    private fun noteServerError(where: String, url: String) {
        val n = srvErrCount.incrementAndGet()
        nlog("[Site] lỗi 5xx #$n ($where) ${url.take(70)}")

        if (where == "WVDom") {
            val now = System.currentTimeMillis()
            synchronized(wvSrvErrTimes) {
                wvSrvErrTimes.addLast(now)
                while (wvSrvErrTimes.size > 6) wvSrvErrTimes.removeFirst()
                val recent = wvSrvErrTimes.count { now - it <= 90_000 }
                if (recent >= 2 && now >= srvStormUntilMs) {
                    srvStormUntilMs = now + SRV_STORM_COOLDOWN_MS
                    nlog("[Site] STORM-GATE BẬT ${SRV_STORM_COOLDOWN_MS / 1000}s: WebView cũng nhận trang 5xx — ngừng dội request, đợi site hồi phục")
                    ntoast("AnimeVsub: server site đang lỗi 5xx — đợi ~30s rồi thử lại; mở animevietsub.sale trong Chrome để kiểm tra")
                }
            }
        }
        if (n >= SITE_DOWN_THRESHOLD && !siteDown()) {
            siteDownUntilMs = System.currentTimeMillis() + SITE_DOWN_COOLDOWN_MS
            srvErrCount.set(0)
            nlog("[Site] SITE-DOWN breaker: nghỉ ${SITE_DOWN_COOLDOWN_MS / 1000}s (server 5xx liên tục)")
            ntoast("AnimeVsub: animevietsub.sale đang lỗi 5xx — thử lại sau ~1-2 phút")
        }
    }

    private fun noteSiteOk() {
        srvErrCount.set(0)
        synchronized(wvSrvErrTimes) { wvSrvErrTimes.clear() }
        if (siteDown()) {
            siteDownUntilMs = 0L
            nlog("[Site] site hồi phục — gỡ breaker")
        }
        if (srvStormActive()) {
            srvStormUntilMs = 0L
            nlog("[Site] site hồi phục — gỡ STORM-GATE")
        }
    }

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

    private fun countPhimAnchors(html: String): Int {
        return try {
            Regex("""/phim/[^"'\s<>\\]*?-a\d+""").findAll(html)
                .map { it.value }.toSet().size
        } catch (_: Throwable) { 0 }
    }

    private fun countXemPhim(html: String): Int {
        return try {
            Regex("xem-phim", RegexOption.IGNORE_CASE).findAll(html).count()
        } catch (_: Throwable) { 0 }
    }

    private fun countEpisodeAnchors(html: String): Int {
        return try { Regex("""data-id=""").findAll(html).count() } catch (_: Throwable) { 0 }
    }

    private val playerIframeRegex = Regex(
        """<iframe[^>]+(?:src|data-src)\s*=\s*["']?[^"'>\s]*googleapiscdn""",
        RegexOption.IGNORE_CASE
    )
    private fun hasPlayerIframe(html: String): Boolean =
        html.contains("googleapiscdn") && playerIframeRegex.containsMatchIn(html)

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

    private fun isBackgroundPlayerUrl(url: String): Boolean = try {
        val uri = java.net.URI(url)
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: ""
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            (host == "googleapiscdn.com" || host.endsWith(".googleapiscdn.com")) &&
            (uri.path ?: "").startsWith("/player/")
    } catch (_: Throwable) { false }

    private fun isJunkUrl(url: String): Boolean {
        val h = hostOf(url)
        if (h.isBlank()) return true
        return junkHosts.any { h.contains(it) }
    }

    private suspend fun tryPlainDoc(url: String, cookies: Map<String, String>?): Document? {
        val t0 = System.currentTimeMillis()
        val tag = if (cookies != null) "ck=${cookies.size}" else "no-ck"
        try {

            val resp = withTimeoutOrNull(PLAIN_TIMEOUT_MS) {
                if (cookies != null) {
                    app.get(url, headers = commonHeaders, cookies = cookies)
                } else {
                    app.get(url, headers = commonHeaders)
                }
            }
            if (resp == null) {
                nlog("[Fetch] plain $tag TIMEOUT ${PLAIN_TIMEOUT_MS}ms (v29 F1) $url")

                if (hostOf(url) == hostOf(mainUrl) && System.currentTimeMillis() >= plainHtmlDeadUntilMs) {
                    plainDeadStreak.set(PLAIN_DEAD_THRESHOLD)
                    setPlainDead(System.currentTimeMillis() + PLAIN_DEAD_COOLDOWN_MS)
                    nlog("[Fetch] plain TẮT ${PLAIN_DEAD_COOLDOWN_MS / 60000} phút (TIMEOUT, v30 C1)")
                }
                return null
            }
            val html = resp.text

            if (resp.code !in 200..299) {

                if (resp.code in 500..599) noteServerError("plain-http", url)

                if (resp.code == 403 && html.length < 2000 &&
                    hostOf(url) == hostOf(mainUrl) &&
                    System.currentTimeMillis() >= plainHtmlDeadUntilMs) {
                    plainDeadStreak.set(PLAIN_DEAD_THRESHOLD)
                    setPlainDead(System.currentTimeMillis() + PLAIN_DEAD_COOLDOWN_MS)
                    nlog("[Fetch] plain 403 challenge-page (len=${html.length}) — tắt plain ${PLAIN_DEAD_COOLDOWN_MS / 60000} phút NGAY (v26 P5)")
                }
                nlog("[Fetch] plain $tag ${resp.code} REJECT len=${html.length} ms=${System.currentTimeMillis() - t0} $url")
                return null
            }
            val blocked = looksBlocked(html)
            if (!blocked) {

                if (looksServerError(html)) {
                    noteServerError("plain", url)
                    nlog("[Fetch] plain $tag ${resp.code} SRV-ERR title='${pageTitleOf(html)}' len=${html.length} ms=${System.currentTimeMillis() - t0} $url")
                    return null
                }
                val doc = resp.document
                if (doc.body()?.text()?.isNotBlank() == true) {
                    noteSiteOk()
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

    private suspend fun fetchDoc(url: String, useCache: Boolean = true, mode: Int = MODE_LISTING): Document {
        if (useCache) {
            docCache[url]?.let { cached ->
                if (System.currentTimeMillis() - cached.time < DOC_CACHE_TTL_MS) {

                    if (docLooksServerError(cached.doc)) {
                        docCache.remove(url)
                        nlog("[Fetch] cache-drop (trang lỗi 5xx) $url")
                    } else if (cachedDocSupports(cached, mode)) {
                        nlog("[Fetch] cache-hit $url")
                        return cached.doc
                    } else {
                        nlog("[Fetch] bỏ cache thiếu danh sách tập mode=$mode $url")
                    }
                }
            }
        }

        if (srvStormActive()) {
            nlog("[Fetch] STORM-GATE chặn fetch mới (server 5xx) $url")
            throw srvStormError()
        }

        val inflightKey = "$mode|$useCache|$url"
        var leaderRetries = 0
        while (true) {
            val (isLeader, deferred) = joinOrCreateInflight(inflightKey)
            if (!isLeader) {
                nlog("[Fetch] join in-flight mode=$mode $url")
                try {
                    return deferred.await()
                } catch (e: Throwable) {
                    if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == false) throw e
                    if (!isCancellation(e) || leaderRetries++ >= 2) throw e
                    clearInflight(inflightKey, deferred)
                    nlog("[Fetch] leader bị hủy — follower còn sống thử lại $url")
                    continue
                }
            }
            try {
                val doc = fetchDocChain(url, useCache, mode)
                deferred.complete(doc)
                return doc
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
                throw t
            } finally {
                clearInflight(inflightKey, deferred)
            }
        }
    }

    private fun joinOrCreateInflight(url: String): Pair<Boolean, kotlinx.coroutines.CompletableDeferred<Document>> {
        synchronized(inflightLock) {
            val ex = inflightDocs[url]
            if (ex != null) return Pair(false, ex)
            val mine = kotlinx.coroutines.CompletableDeferred<Document>()
            inflightDocs[url] = mine
            return Pair(true, mine)
        }
    }

    private fun clearInflight(url: String, mine: kotlinx.coroutines.CompletableDeferred<Document>) {
        synchronized(inflightLock) {
            if (inflightDocs[url] === mine) inflightDocs.remove(url)
        }
    }

    private suspend fun fetchDocChain(url: String, useCache: Boolean, mode: Int): Document {

        restoreStateOnce()
        val cachedCookies = cfCookieCache[mainUrl] ?: cookiesFromCookieManager(mainUrl)
        if (System.currentTimeMillis() < plainHtmlDeadUntilMs) {
            nlog("[Fetch] plain TẮT (breaker 403 liên tục) — vào WebView luôn $url")
        } else {

            while (!fetchGate.tryAcquire()) kotlinx.coroutines.delay(60)
            try {

                val deadNow = System.currentTimeMillis() < plainHtmlDeadUntilMs
                if (!deadNow) kotlinx.coroutines.delay(fetchRand.nextLong(150, 450))
                val viaCk = if (!deadNow && cachedCookies != null) tryPlainDoc(url, cachedCookies) else null

                val doc = viaCk ?: if (deadNow || System.currentTimeMillis() < plainHtmlDeadUntilMs) null
                                   else tryPlainDoc(url, null)
                if (doc != null) {
                    if (hostOf(url) == hostOf(mainUrl)) {
                        plainDeadStreak.set(0)
                        setPlainDead(0L)
                    }
                    if (useCache) docCache[url] = CachedDoc(doc, System.currentTimeMillis())
                    return doc
                }
                if (!deadNow && hostOf(url) == hostOf(mainUrl)) {
                    val n = plainDeadStreak.incrementAndGet()
                    val nowMs = System.currentTimeMillis()
                    if (n >= PLAIN_DEAD_THRESHOLD && nowMs >= plainHtmlDeadUntilMs) {
                        setPlainDead(nowMs + PLAIN_DEAD_COOLDOWN_MS)
                        nlog("[Fetch] plain TẮT ${PLAIN_DEAD_COOLDOWN_MS / 60000} phút (streak=$n lần 403/challenge liên tục) — vào WebView ngay")
                    }
                }
            } finally {
                fetchGate.release()
            }
        }

        nlog("[Fetch] plain dead (403/520) -> webview-first mode=$mode $url")

        val silent = run {

            if (useCache) {
                docCache[url]?.let { cached ->
                    if (System.currentTimeMillis() - cached.time < DOC_CACHE_TTL_MS) {

                        if (docLooksServerError(cached.doc)) {
                            docCache.remove(url)
                            nlog("[Fetch] cache-drop (trang lỗi 5xx) $url")
                        } else if (cachedDocSupports(cached, mode)) {
                            return@run cached.doc
                        }
                    }
                }
            }

            val fresh = cfCookieCache[mainUrl] ?: cookiesFromCookieManager(mainUrl)

            if (System.currentTimeMillis() >= plainHtmlDeadUntilMs &&
                fresh != null && fresh != cachedCookies) {
                tryPlainDoc(url, fresh)?.let {
                    if (useCache) docCache[url] = CachedDoc(it, System.currentTimeMillis())
                    return@run it
                }
            }

            nlog("[Fetch] webview-dom fallback mode=$mode $url")

            val viaCanon = toCanonical(url) != url
            var doc = webviewDocCapture(
                url, mode,
                if (viaCanon && !canonicalConfirmed) 9_000L else WV_SILENT_TIMEOUT_MS,
                interactive = false
            )
            if (doc == null && viaCanon) {
                nlog("[Fetch] canonical-direct thất bại -> tắt, thử lại URL gốc (v30 C2) $url")
                canonicalOff = true
                canonicalHost = null
                try { statePrefs()?.edit()?.remove("canonicalHost")?.apply() } catch (_: Throwable) {}
                doc = webviewDocCapture(url, mode, WV_SILENT_TIMEOUT_MS, interactive = false)
            }
            if (doc != null && useCache) docCache[url] = CachedDoc(doc, System.currentTimeMillis())
            doc
        }
        if (silent != null) return silent

        if (srvStormActive()) {
            nlog("[Fetch] STORM-GATE giữa chuỗi — dừng trước gate $url")
            throw srvStormError()
        }

        unlockGate(url, mode)?.let {
            if (useCache) docCache[url] = CachedDoc(it, System.currentTimeMillis())
            return it
        }

        tryPlainDoc(url, cfCookieCache[mainUrl] ?: cookiesFromCookieManager(mainUrl))?.let {
            if (useCache) docCache[url] = CachedDoc(it, System.currentTimeMillis())
            return it
        }

        webviewDocCapture(url, mode, WV_SILENT_TIMEOUT_MS, interactive = false)?.let {
            if (useCache) docCache[url] = CachedDoc(it, System.currentTimeMillis())
            return it
        }

        if (srvStormActive()) {
            nlog("[Fetch] STORM-GATE — bỏ qua proxy $url")
            throw srvStormError()
        }

        if (System.currentTimeMillis() >= proxyDisabledUntilMs) {
            val found = coroutineScope {
                corsProxies.map { proxy -> async { tryCorsProxy(proxy, url) } }
            }.awaitAll().filterNotNull().firstOrNull()
            if (found != null) {
                if (useCache) docCache[url] = CachedDoc(found, System.currentTimeMillis())
                return found
            }
            if (proxyFailCount.get() >= 4) {
                proxyDisabledUntilMs = System.currentTimeMillis() + PROXY_COOLDOWN_MS
                proxyFailCount.set(0)
                nlog("[Fetch] TẮT CORS proxy ${PROXY_COOLDOWN_MS / 60000} phút (fail liên tục)")
            }
        }

        throw ErrorLoadingException("AnimeVsub: site vẫn chặn sau mọi đường (plain 403/520 + WebView + gate + proxy) — thử lại sau vài phút; mở animevietsub.sale trong Chrome để kiểm tra: Chrome cũng lỗi -> site đang sự cố 5xx, Chrome OK -> đợi dialog xác minh hiện ra và tick")
    }

    private suspend fun tryCorsProxy(proxy: String, url: String): Document? {
        val pt0 = System.currentTimeMillis()
        try {
            val proxiedUrl = if (proxy.endsWith("?url=") || proxy.endsWith("?quest=")) {
                proxy + URLEncoder.encode(url, "UTF-8")
            } else proxy + url
            val resp = withTimeoutOrNull(9_000L) { app.get(proxiedUrl, headers = commonHeaders) }
            if (resp == null) {
                proxyFailCount.incrementAndGet()
                nlog("[Fetch] proxy ${hostOf(proxy)} TIMEOUT>9s $url")
                return null
            }
            val html = resp.text
            if (resp.code in 200..299 && !looksBlocked(html) && !looksServerError(html)) {
                val doc = resp.document
                if (doc.body()?.text()?.isNotBlank() == true) {
                    nlog("[Fetch] proxy ${hostOf(proxy)} OK ${resp.code} len=${html.length} ms=${System.currentTimeMillis() - pt0} $url")
                    proxyFailCount.set(0)
                    noteSiteOk()
                    return doc
                }
            }
            if (looksServerError(html)) noteServerError("proxy", url)
            nlog("[Fetch] proxy ${hostOf(proxy)} FAIL ${resp.code} blocked=${looksBlocked(html)} srvErr=${looksServerError(html)} len=${html.length} ms=${System.currentTimeMillis() - pt0} $url")
            proxyFailCount.incrementAndGet()
        } catch (e: Throwable) {
            if (isCancellation(e)) throw e
            nlogE("[Fetch] proxy ${hostOf(proxy)}", e, url)
            proxyFailCount.incrementAndGet()
        }
        return null
    }

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

    @Volatile private var homePageStyle = 0
    @Volatile private var genrePageStyle = 0

    override val mainPage = mainPageOf(
        "HOME" to "Mới Cập Nhật",
        "GENRE::hanh-dong"  to "Hành Động",
        "GENRE::hai-huoc"   to "Hài Hước",
        "GENRE::tinh-cam"   to "Tình Cảm",
        "GENRE::kinh-di"    to "Kinh Dị"
    )

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

                if (siteDown() || srvStormActive()) break
            }
        }
        if (usedIdx >= 0) rememberPaginationStyle(request.data, page, usedIdx)
        nlog("[List] ${request.name} p$page -> ${items.size} items")
        ntoastV("AnimeVsub $AVS_BUILD: ${request.name} p$page — ${items.size} phim")
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private val slugIdRegex = Regex("""/phim/([^/?#]+-a\d+)""")

    private fun detailUrl(href: String): String? {
        val m = slugIdRegex.find(href) ?: return null
        return "$mainUrl/phim/${m.groupValues[1]}/"
    }

    private fun pickImgUrl(img: Element): String? {
        for (attr in listOf("data-src", "data-original", "data-lazy-src", "src")) {
            val v = img.attr(attr).trim()
            if (v.isNotBlank() && !v.startsWith("data:")) return v
        }

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

                if (title.isBlank()) title = a.closest(".TPost, .TPostMv, li, article")?.selectFirst(".Title")?.text()?.trim() ?: ""
                if (title.isBlank()) title = a.text().trim()

                if (title.isBlank()) title = a.selectFirst("img")?.attr("alt")?.trim() ?: ""
                if (title.isBlank()) {
                    title = a.closest("li, .item, .card, .movie-item, .film-item, article")
                        ?.selectFirst("h3, h2, .title, .name")?.text()?.trim() ?: ""
                }
                if (title.isBlank()) continue

                val tl = title.lowercase()
                if (tl == "xem phim" || tl == "xem ngay" || tl.startsWith("tập ")) continue

                if (title.length > 120) continue

                val img: Element? = a.selectFirst("img")
                    ?: a.closest("li, .item, .card, .movie-item, .film-item, article, div")
                        ?.selectFirst("img")
                val poster: String? = if (img == null) null else absolutize(pickImgUrl(img))

                out[slugId] = newAnimeSearchResponse(title, detail, TvType.Anime) {
                    this.posterUrl = poster
                }
            } catch (e: Throwable) {
                nlogE("[Card]", e, a.attr("href").take(60))
            }
        }
        if (out.isEmpty()) {

            nlog("[Parse] 0 cards | title='${doc.title().take(50)}' aTotal=${doc.select("a").size} aPhim=${doc.select("a[href*=\"/phim/\"]").size} img=${doc.select("img").size} iframe=${doc.select("iframe").size} textLen=${doc.text().length}")
        }
        return out.values.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = URLEncoder.encode(query.trim(), "UTF-8")

        try {
            val res = app.post(
                "$mainUrl/ajax/suggest",
                headers = commonHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin"  to mainUrl.trimEnd('/'),
                    "Referer" to mainUrl
                ),
                data = mapOf("ajaxSearch" to "1", "keysearch" to query.trim())
            )
            val body = res.text
            if (res.code in 200..299 && body.isNotBlank() && !looksBlocked(body) && !looksServerError(body)) {
                val items = parseCards(Jsoup.parse(body, mainUrl))
                nlog("[Search] ajax/suggest ${res.code} -> ${items.size} len=${body.length}")
                if (items.isNotEmpty()) return items
            } else {
                nlog("[Search] ajax/suggest ${res.code} skip len=${body.length} title='${pageTitleOf(body)}'")
            }
        } catch (e: Throwable) {
            if (isCancellation(e)) throw e
            nlogE("[Search] ajax/suggest", e)
        }

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

                cleanMiss++
                if (cleanMiss >= 2) {
                    nlog("[Search] dừng sau $cleanMiss biến thể sạch 0 kết quả")
                    return emptyList()
                }
            } catch (e: Throwable) {
                if (isCancellation(e)) throw e
                nlog("[Search] candidate fail $url: ${e.message}")

                if (srvStormActive() || siteDown()) return emptyList()
            }
        }
        nlog("[Search] '$query' -> 0 kết quả sau ${candidates.size} biến thể")
        return emptyList()
    }

    private fun normalizeEpName(raw: String?): String {
        if (raw.isNullOrBlank()) return "0"
        val s = raw.trim()
        val numMatch = Regex("""(\d+)""").find(s)
        if (numMatch != null) return numMatch.groupValues[1].trimStart('0').ifBlank { "0" }
        return s
    }

    private data class AvsSrv(val id: String, val play: String, val hash: String)

    private fun buildAvsMetaBlock(id: String, play: String, hash: String): String {
        fun enc(s: String) = try { URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }
        return "id=" + enc(id) + "&play=" + enc(play) + "&hash=" + enc(hash)
    }

    private fun parseAvsMeta(meta: String): MutableList<AvsSrv> {
        val out = ArrayList<AvsSrv>()
        for (block in meta.split(";;")) {
            if (block.isBlank()) continue
            var id = ""; var play = ""; var hash = ""
            for (kv in block.split('&')) {
                val i = kv.indexOf('=')
                if (i <= 0) continue
                val k = kv.substring(0, i).trim()
                val v = try { URLDecoder.decode(kv.substring(i + 1), "UTF-8") } catch (_: Throwable) { kv.substring(i + 1) }
                when (k) {
                    "id"   -> id = v
                    "play" -> play = v
                    "hash" -> hash = v
                }
            }
            if (id.isNotBlank()) out.add(AvsSrv(id, play, hash))
        }
        return out
    }

    private fun parseOphimEpisodes(
        doc: Document, detail: String,
        epOrder: LinkedHashMap<String, String>,
        epMeta: LinkedHashMap<String, MutableSet<String>>
    ): Int {
        var added = 0
        try {
            val region = doc.selectFirst("#list-server, #list-server-1, #list-server-2, .list-server, .list-episode")
                ?: doc.body()
            for (a in region?.select("a[data-id]") ?: emptyList()) {
                try {
                    val id = a.attr("data-id").trim()
                    if (id.isBlank()) continue
                    val play = a.attr("data-play").trim()
                    val hash = a.attr("data-hash").ifBlank { a.attr("data-href") }.trim()
                    var rawName = a.text().trim()
                    if (rawName.isBlank() || rawName.length > 40) rawName = id
                    val epName = normalizeEpName(rawName)
                    epMeta.getOrPut(epName) { LinkedHashSet() }.add(buildAvsMetaBlock(id, play, hash))
                    if (!epOrder.containsKey(epName)) {

                        val rawHref = a.attr("href").trim()
                        val href = if (rawHref.isBlank() || rawHref.startsWith("#") ||
                            rawHref.startsWith("javascript:", ignoreCase = true)) null
                        else a.absUrl("href").takeIf { it.isNotBlank() } ?: absolutize(rawHref)
                        epOrder[epName] = if (href != null && isWatchPageUrl(href) &&
                            detailUrl(href) == detailUrl(detail)) href
                        else detail.trimEnd('/') + "/xem-phim.html"
                        added++
                    }
                } catch (e: Throwable) { nlogE("[Load] list-server", e) }
            }
        } catch (e: Throwable) { nlogE("[Load] list-server region", e) }
        return added
    }

    private fun parseAjaxPlayerPayload(body: String): Triple<List<Pair<String, String>>, List<String>, Boolean> {
        val media = mutableListOf<Pair<String, String>>()
        val frames = mutableListOf<String>()
        var encrypted = false
        try {
            val json = org.json.JSONObject(body)
            val links = json.opt("link") ?: json.opt("sources")
            if (links is String) {
                val s = links.replace("\\/", "/").trim()
                if (s.contains("<iframe", ignoreCase = true)) {
                    frames.addAll(harvestIframes(Jsoup.parse(s)))
                } else {
                    val u = absolutize(s)
                    when {
                        u == null || isJunkUrl(u)              -> encrypted = true
                        m3u8UrlRegex.containsMatchIn(u) ||
                        mp4UrlRegex.containsMatchIn(u)         -> media.add(u to "")
                        json.optString("playTech") == "iframe" -> frames.add(u)
                        else                                   -> encrypted = true
                    }
                }
            } else if (links is org.json.JSONArray) {
                for (i in 0 until links.length()) {
                    val item = links.optJSONObject(i) ?: continue
                    val raw = item.optString("file").ifBlank { item.optString("src") }
                        .replace("\\/", "/").trim()
                    if (raw.isBlank()) { encrypted = true; continue }
                    val u = absolutize(raw)
                    if (u == null || isJunkUrl(u)) { encrypted = true; continue }
                    val label = item.optString("label")
                    if (m3u8UrlRegex.containsMatchIn(u) || mp4UrlRegex.containsMatchIn(u)) {
                        media.add(u to label)
                    } else if (item.optString("type").contains("iframe", true)) {
                        frames.add(u)
                    } else {
                        encrypted = true
                    }
                }
            }
        } catch (e: Throwable) {

            try {
                val ifr = harvestIframes(Jsoup.parse(body, mainUrl))
                if (ifr.isNotEmpty()) {
                    frames.addAll(ifr)
                } else {
                    val (m3u8s, mp4s) = harvestMediaUrls(body)
                    for (m in m3u8s) media.add(m to "")
                    for (m in mp4s) media.add(m to "")
                    if (m3u8s.isEmpty() && mp4s.isEmpty()) encrypted = true
                }
            } catch (_: Throwable) { encrypted = true }
        }
        return Triple(media, frames, encrypted)
    }

    private fun qualityFromLabel(label: String): Int {
        Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (it in 144..2160) return it
        }
        val up = label.uppercase()
        return when {
            up.contains("4K") || up.contains("2160")  -> 2160
            up.contains("FHD") || up.contains("1080") -> Qualities.P1080.value
            up.contains("HD") || up.contains("720")   -> Qualities.P720.value
            else -> Qualities.P720.value
        }
    }

    private suspend fun processAjaxMedia(
        media: List<Pair<String, String>>,
        frames: List<String>,
        watchUrl: String,
        embedQueue: MutableList<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = detailBaseOf(watchUrl)
        for (f in frames) if (!embedQueue.contains(f)) embedQueue.add(f)
        for (src in media) {
            val url = src.first
            val label = src.second
            if (m3u8UrlRegex.containsMatchIn(url)) {
                val content = fetchText(url, referer = ref) ?: continue
                if (content.contains("#EXTM3U") &&
                    registerM3U8Link(content, url, url, label.ifBlank { "Vietsub" }, callback, siteReferer = ref)) {
                    return true
                }
            } else {
                nlog("[Step0] direct ${label.ifBlank { "MP4" }} ${url.take(90)}")
                callback(newExtractorLink(name, label.ifBlank { "MP4" }, url, ExtractorLinkType.VIDEO) {
                    quality = qualityFromLabel(label)
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to ref)
                })
                return true
            }
        }
        return false
    }

    private class AvsAjaxBridge(private val cb: (String) -> Unit) {
        @JavascriptInterface
        fun onBody(body: String?) {
            if (body.isNullOrBlank()) return
            try { cb(body) } catch (_: Throwable) {}
        }
    }

    private suspend fun ajaxPlayerViaWebView(watchUrl: String, srv: AvsSrv): String? {
        val ctx = appContext() ?: return null
        val done = kotlinx.coroutines.CompletableDeferred<String?>()
        var webView: WebView? = null
        fun jsq(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", "").replace("\r", "").replace("</", "<\\/")
        try {
            withContext(Dispatchers.Main) {
                val wv = WebView(ctx)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = USER_AGENT
                try { wv.settings.blockNetworkImage = true } catch (_: Throwable) {}
                try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Throwable) {}
                val js = """
                    (function(){
                      function go(){
                        try {
                          var p = new URLSearchParams();
                          p.append('id', '${jsq(srv.id)}');
                          p.append('play', '${jsq(srv.play)}');
                          p.append('link', '${jsq(srv.hash)}');
                          p.append('backuplinks', '1');
                          fetch(location.origin + '/ajax/player', {
                            method: 'POST',
                            credentials: 'include',
                            headers: {'X-Requested-With': 'XMLHttpRequest'},
                            body: p.toString()
                          }).then(function(r){ return r.text(); })
                            .then(function(t){ try { AvsAjaxBridge.onBody(String(t || '')); } catch (e) {} })
                            .catch(function(e){ try { AvsAjaxBridge.onBody('ERR ' + e); } catch (e2) {} });
                        } catch (e) { try { AvsAjaxBridge.onBody('ERR ' + e); } catch (e2) {} }
                      }
                      setTimeout(go, 600);
                    })();
                """.trimIndent()
                wv.addJavascriptInterface(AvsAjaxBridge { body ->
                    if (!done.isCompleted) done.complete(body)
                }, "AvsAjaxBridge")
                wv.webChromeClient = WebChromeClient()
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) {
                        try { view?.evaluateJavascript(js, null) } catch (_: Throwable) {}
                    }
                }
                wv.loadUrl(watchUrl, mapOf("Referer" to "$mainUrl/"))
            }

            val body = withTimeoutOrNull(6_000L) { done.await() }
            nlog("[Step0w] webview-ajax ${if (body == null) "TIMEOUT" else "len=" + body.length}")
            return body
        } catch (e: Throwable) {
            if (isCancellation(e)) throw e
            nlogE("[Step0w]", e)
            return null
        } finally {
            try { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { webView?.destroy() } } catch (_: Throwable) {}
        }
    }

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val detail = detailUrl(url) ?: url

        val watchGuess = detail.trimEnd('/') + "/xem-phim.html"
        val watchJob = async {
            try {

                fetchDoc(watchGuess, useCache = true, mode = MODE_EPISODES)
            } catch (e: Throwable) {
                if (isCancellation(e)) throw e
                nlogE("[Load] watch-page", e, watchGuess)
                null
            }
        }
        val doc = try {
            fetchDoc(detail, mode = MODE_DETAIL)
        } catch (e: Throwable) {
            nlogE("[Load] fetchDoc", e, detail)
            throw e
        }

        var title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        if (title.isBlank()) title = doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.trim() ?: ""
        if (title.isBlank()) title = doc.title().trim().substringBefore("|").trim()
        if (title.isBlank()) throw ErrorLoadingException("Không đọc được tên phim")

        var poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")?.trim() ?: ""
        if (poster.isBlank()) {
            val posterImg: Element? = doc.selectFirst(".poster img, .image img, .thumb img, .film-poster img, img[src*=\"upload\"], img[data-src*=\"upload\"]")
            poster = if (posterImg == null) "" else pickImgUrl(posterImg) ?: ""
        }

        var plot = doc.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim() ?: ""
        for (sel in listOf(".description", ".film-description", "#bio", ".story", "[class*=descript]", ".content-detail")) {
            if (plot.isNotBlank()) break
            plot = doc.selectFirst(sel)?.text()?.trim() ?: ""
        }
        if (plot.isBlank()) plot = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim() ?: ""

        val tags = doc.select("a[href*=\"/the-loai/\"]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()
            .take(12)

        val infoText = doc.select("li, dt, dd, .info, [class*=meta], p")
            .joinToString(" ") { it.text() }
        val year = Regex("""(?:Năm|Phát hành|Năm phát hành)\D{0,12}((?:19|20)\d{2})""", RegexOption.IGNORE_CASE)
            .find(infoText)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b((?:19|20)\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val totalEps = Regex("""(\d+)\s*(?:tập|episodes?)""", RegexOption.IGNORE_CASE)
            .find(infoText + " " + title)?.groupValues?.get(1)?.toIntOrNull()

        val epOrder = LinkedHashMap<String, String>()
        val epMeta  = LinkedHashMap<String, MutableSet<String>>()
        val seenUrls = HashSet<String>()

        val addedDetail = parseOphimEpisodes(doc, detail, epOrder, epMeta)
        nlog("[Load] list-server eps=+$addedDetail total=${epOrder.size} meta=${epMeta.size}")

        for (a in doc.select("a[href]")) {
            val href = absolutize(a.attr("href")) ?: continue
            if (!href.contains("xem-phim")) continue
            if (seenUrls.contains(href)) continue

            val label = a.text().trim()
            val numName = Regex("""(?:tap|ep|tap-phim)[=\-/]?(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)?.groupValues?.get(1)
            val isButton = (label.isBlank() || label.length > 40 ||
                            Regex("""(?i)xem\s*(phim|ngay)""").containsMatchIn(label))
            if (isButton && numName == null) continue

            val rawName = if (isButton) (numName ?: "") else label
            val epName = normalizeEpName(rawName)
            seenUrls.add(href)
            if (!epOrder.containsKey(epName)) epOrder[epName] = href
        }

        if (epOrder.isEmpty()) {
            try {
                val wdoc = watchJob.await()
                if (wdoc != null) {
                    val addedWatch = parseOphimEpisodes(wdoc, detail, epOrder, epMeta)
                    nlog("[Load] watch-page eps=+$addedWatch total=${epOrder.size} meta=${epMeta.size} $watchGuess")
                }
            } catch (e: Throwable) {
                if (isCancellation(e)) throw e
                nlogE("[Load] watch-page", e, watchGuess)
            }
        } else {
            watchJob.cancel()
        }

        nlog("[Load] $detail title='$title' tags=${tags.size} eps=${epOrder.size} totalEps=$totalEps")
        ntoastV("AnimeVsub $AVS_BUILD: $title — ${epOrder.size} tập")

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

            val metaRaw = epMeta[epName]?.joinToString(";;") { it } ?: ""
            val data = if (metaRaw.isNotBlank())
                watchUrl + "#avs=" + URLEncoder.encode(metaRaw, "UTF-8")
            else watchUrl
            newEpisode(data, initializer = {
                this.name    = "Tập $epName"
                this.episode = epName.toIntOrNull()
            })
        }.sortedBy { it.episode ?: Int.MAX_VALUE }

        newTvSeriesLoadResponse(title, detail, TvType.Anime, episodes) {
            this.posterUrl = poster.takeIf { it.isNotBlank() }
            this.plot      = plot.takeIf { it.isNotBlank() }
            this.tags      = tags
            this.year      = year
        }
    }

    private val m3u8UrlRegex =
        Regex("""https?://[^"'\s<>\\]+?\.m3u[89](?:\?[^"'\s<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val mp4UrlRegex =
        Regex("""https?://[^"'\s<>\\]+?\.(?:mp4|m4v)(?:\?[^"'\s<>\\]*)?""", RegexOption.IGNORE_CASE)

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

    private fun watchDocumentHasMedia(doc: Document): Boolean = try {
        if (harvestIframes(doc).any { isBackgroundPlayerUrl(it) }) true else {
            val media = harvestMediaUrls(doc.outerHtml())
            media.first.isNotEmpty() || media.second.isNotEmpty()
        }
    } catch (_: Throwable) { false }

    private suspend fun preferredWatchDocument(
        current: Document?, pending: kotlinx.coroutines.Deferred<Document?>?
    ): Document? {
        val candidate = current ?: if (pending != null) {
            try { withTimeoutOrNull(1_200L) { pending.await() } }
            catch (e: Throwable) {
                if (isCancellation(e)) throw e
                null
            }
        } else null
        return candidate?.takeIf { watchDocumentHasMedia(it) }
    }

    private fun isWatchPageUrl(url: String): Boolean =
        url.contains("xem-phim", ignoreCase = true) ||
            Regex("""(?i)/tap-[^/?#]+""").containsMatchIn(url)

    private fun detailBaseOf(watchUrl: String): String =
        Regex("""(?i)^(https?://[^/]+/phim/[^/?#]+-a\d+)(?:[/?#]|$)""")
            .find(watchUrl)?.groupValues?.get(1) ?: mainUrl

    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        var linkFound = false
        val runT0 = System.currentTimeMillis()
        val stepNotes = mutableListOf<String>()

        var watchUrl = data.trim()

        var avsMetaRaw = ""
        val avsIdx = watchUrl.indexOf("#avs=")
        if (avsIdx >= 0) {
            avsMetaRaw = try { URLDecoder.decode(watchUrl.substring(avsIdx + 5), "UTF-8") } catch (_: Throwable) { "" }
            watchUrl = watchUrl.substring(0, avsIdx)
        }
        val srvList = parseAvsMeta(avsMetaRaw)
        if (srvList.isNotEmpty()) {
            nlog("[loadLinks] avs-meta servers=${srvList.size} id1=${srvList.first().id} play1=${srvList.first().play} hashLen1=${srvList.first().hash.length}")
        }

        if (!isWatchPageUrl(watchUrl) && watchUrl.contains("/phim/")) {
            watchUrl = watchUrl.trimEnd('/') + "/xem-phim.html"
        }
        nlog("[loadLinks] watch=$watchUrl")
        ntoastV("AnimeVsub $AVS_BUILD: đang lấy link…")

        if (siteDown()) {
            nlog("[loadLinks] ABORT site-down watch=$watchUrl")
            ntoast("AnimeVsub: animevietsub.sale đang lỗi 5xx — thử lại sau ~1-2 phút")
            return false
        }

        coroutineScope {

            var watchDoc: Document? = null
            if (!siteDown()) {
                try {
                    restoreStateOnce()
                    val cached = docCache[watchUrl]
                    if (cached != null && System.currentTimeMillis() - cached.time < DOC_CACHE_TTL_MS &&
                        !docLooksServerError(cached.doc)) {
                        watchDoc = cached.doc
                        nlog("[Step1p] dùng cache trang xem $watchUrl")
                    }

                    if (watchDoc == null && System.currentTimeMillis() >= plainHtmlDeadUntilMs) {
                        val ck = cfCookieCache[mainUrl] ?: cookiesFromCookieManager(mainUrl)
                        watchDoc = (if (ck != null) tryPlainDoc(watchUrl, ck) else null)
                            ?: tryPlainDoc(watchUrl, null)
                    }
                    if (watchDoc != null) {
                        noteSiteOk()
                        nlog("[Step1p] watch DOM OK len=${watchDoc!!.outerHtml().length} $watchUrl")
                    }
                } catch (e: Throwable) {
                    if (isCancellation(e)) throw e
                    nlogE("[Step1p]", e)
                }

                if (srvList.isEmpty() && watchDoc != null) {
                    val tmpOrder = LinkedHashMap<String, String>()
                    val tmpMeta  = LinkedHashMap<String, MutableSet<String>>()
                    parseOphimEpisodes(watchDoc!!, detailBaseOf(watchUrl), tmpOrder, tmpMeta)
                    for (set in tmpMeta.values) {
                        for (block in set) srvList.addAll(parseAvsMeta(block))
                    }
                    if (srvList.isNotEmpty()) nlog("[Step1p] watch-page meta servers=${srvList.size} eps=${tmpOrder.size}")
                }
            }

            val watchPrefetch: kotlinx.coroutines.Deferred<Document?>? =
                if (watchDoc == null && !siteDown()) async {
                    try {
                        fetchDoc(watchUrl, useCache = true, mode = MODE_PLAY)
                    } catch (e: Throwable) {
                        if (isCancellation(e)) throw e
                        nlogE("[Step1pre]", e, watchUrl)
                        null
                    }
                } else null

            val embedQueue = mutableListOf<String>()
            var step0Weak = false
            if (srvList.isNotEmpty() && !linkFound && !siteDown()) {
                var tried = 0
                for (srv in srvList) {
                    if (linkFound || siteDown() || tried >= 4) break
                    tried++
                    try {
                        val ref = detailBaseOf(watchUrl)
                        val resp = withTimeoutOrNull(2_000L) {
                            app.post(
                                "$mainUrl/ajax/player",
                                headers = commonHeaders + mapOf(
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Origin"  to mainUrl.trimEnd('/'),
                                    "Referer" to ref
                                ),
                                data = mapOf(
                                    "id" to srv.id,
                                    "play" to srv.play,
                                    "link" to srv.hash,
                                    "backuplinks" to "1"
                                )
                            )
                        }
                        if (resp == null) {
                            nlog("[Step0] ajax/player timeout 2000ms — chuyển fallback")
                            step0Weak = true
                            break
                        }
                        val body = resp.text
                        nlog("[Step0] ajax/player #$tried ${resp.code} len=${body.length} title='${pageTitleOf(body)}'")
                        if (resp.code in 200..299 && body.isNotBlank() &&
                            !looksBlocked(body) && !looksServerError(body)) {
                            noteSiteOk()
                            val (media, frames, encrypted) = parseAjaxPlayerPayload(body)
                            nlog("[Step0] payload media=${media.size} frames=${frames.size} encrypted=$encrypted")
                            if (processAjaxMedia(media, frames, watchUrl, embedQueue, callback)) {
                                linkFound = true
                                break
                            }
                            if (media.isEmpty() && frames.isEmpty()) step0Weak = true
                        } else if (resp.code in 200..299 && body.isBlank()) {

                        } else {
                            step0Weak = true
                        }
                    } catch (e: Throwable) {
                        if (isCancellation(e)) throw e
                        nlogE("[Step0]", e)
                        step0Weak = true
                    }
                }
            }
            stepNotes.add("S0:${if (linkFound) "HIT" else "0"}")

            if (!linkFound && embedQueue.isNotEmpty() && !siteDown()) {
                for (fr in embedQueue.distinct().take(MAX_EMBEDS)) {
                    if (linkFound || siteDown()) break
                    nlog("[Step0f] embed ${fr.take(70)}")
                    if (extractFromEmbed(fr, watchUrl, callback)) { linkFound = true; break }
                }
                stepNotes.add("S0f:${if (linkFound) "HIT" else "0"}")
            }

            suspend fun attemptWebViewAjax() {
                for (srv in srvList.take(2)) {
                    if (linkFound || siteDown()) break
                    val body2 = ajaxPlayerViaWebView(watchUrl, srv) ?: break
                    if (body2.startsWith("ERR") || looksBlocked(body2) || looksServerError(body2)) continue
                    val (media2, frames2, _) = parseAjaxPlayerPayload(body2)
                    nlog("[Step0w] payload media=${media2.size} frames=${frames2.size}")
                    if (processAjaxMedia(media2, frames2, watchUrl, embedQueue, callback)) {
                        linkFound = true
                        break
                    }
                    if (frames2.isNotEmpty()) {
                        for (fr in frames2.distinct().take(MAX_EMBEDS)) {
                            if (linkFound) break
                            if (extractFromEmbed(fr, watchUrl, callback)) { linkFound = true; break }
                        }
                    }
                }
                stepNotes.add("S0w:${if (linkFound) "HIT" else "0"}")
            }

            var deferredWebViewAjax = false
            if (!linkFound && step0Weak && srvList.isNotEmpty() && !siteDown()) {
                val readyWatch = preferredWatchDocument(watchDoc, watchPrefetch)
                if (readyWatch != null) {
                    watchDoc = readyWatch
                    deferredWebViewAjax = true
                    nlog("[Step0w] ưu tiên iframe/nguồn có sẵn; giữ AJAX WebView làm dự phòng")
                } else {
                    attemptWebViewAjax()
                }
            }

            if (linkFound) watchPrefetch?.cancel()

            if (!linkFound) {
                try {

                    val doc = watchDoc
                        ?: (if (watchPrefetch != null) watchPrefetch.await()
                            else fetchDoc(watchUrl, useCache = false, mode = MODE_PLAY))
                            ?.also { watchDoc = it }
                        ?: throw ErrorLoadingException("AnimeVsub: không nạp được trang xem")
                    val html = doc.outerHtml()

                    val (m3u8s, mp4s) = harvestMediaUrls(html)
                    nlog("[Step1] media m3u8=${m3u8s.size} mp4=${mp4s.size}")
                    for (m3u8 in m3u8s) {
                        val content = fetchText(m3u8, referer = detailBaseOf(watchUrl)) ?: continue
                        if (content.contains("#EXTM3U")) {
                            if (registerM3U8Link(content, m3u8, m3u8, "Vietsub", callback, siteReferer = detailBaseOf(watchUrl))) {
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

                    if (!linkFound) {
                        var embeds = harvestIframes(doc).sortedByDescending { isBackgroundPlayerUrl(it) }.take(MAX_EMBEDS)

                        if (embeds.isEmpty() && isWatchPageUrl(watchUrl)) {
                            nlog("[Step1] iframes=0 — nạp lại trang xem (WVDom đợi iframe, v28 R5)")
                            docCache.remove(watchUrl)
                            try {
                                val doc2 = fetchDoc(watchUrl, useCache = false, mode = MODE_EPISODES)
                                val embeds2 = harvestIframes(doc2)
                                if (embeds2.isNotEmpty()) {
                                    watchDoc = doc2
                                    embeds = embeds2.sortedByDescending { isBackgroundPlayerUrl(it) }.take(MAX_EMBEDS)
                                }
                            } catch (e: Throwable) {
                                if (isCancellation(e)) throw e
                                nlogE("[Step1] R5", e)
                            }
                            nlog("[Step1] iframes(retry R5)=${embeds.size}")
                        }
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

            if (!linkFound && deferredWebViewAjax && !siteDown()) {
                attemptWebViewAjax()
            }

            if (!linkFound && !siteDown()) {
                try {
                    val t3 = System.currentTimeMillis()
                    val resp = app.get(watchUrl, interceptor = WebViewResolver(
                        Regex(""".*\.(m3u8|m3u9|mp4)(\?|$)""")
                    ), headers = commonHeaders)
                    val content = resp.text
                    val capturedUrl = resp.url ?: ""
                    nlog("[Step3] wvr captured=${capturedUrl.take(90)} len=${content.length} ms=${System.currentTimeMillis() - t3}")
                    if (content.contains("#EXTM3U")) {
                        val m3u8Url = capturedUrl.ifBlank { watchUrl }
                        var reg = registerM3U8Link(content, m3u8Url, m3u8Url, "Vietsub", callback, siteReferer = watchUrl)

                        if (!reg && content.contains("_t=")) {
                            val pid = Regex("""/playlist/([0-9a-f]{16,})""").find(m3u8Url)?.groupValues?.get(1)
                            if (pid != null) {
                                val playerUrl = "https://stream.googleapiscdn.com/player/$pid"
                                nlog("[Step3] raw playlist (_t=) — hook player thay thế $playerUrl")
                                reg = tryHookFlow(playerUrl, callback)
                            }
                        }
                        if (reg) {
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
                                registerM3U8Link(c, firstM3u8, firstM3u8, "Vietsub", callback, siteReferer = watchUrl)) {
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

            if (!linkFound && !siteDown()) {
                try {
                    val embeds = harvestIframes(fetchDoc(watchUrl, useCache = false, mode = MODE_PLAY)).take(MAX_EMBEDS)
                    nlog("[Step4] embeds=${embeds.size} ${embeds.joinToString(" | ") { it.take(60) }}")
                    for (embed in embeds) {
                        try {
                            val resp = app.get(embed, interceptor = WebViewResolver(
                                Regex(""".*\.(m3u8|m3u9|mp4)(\?|$)""")
                            ), headers = commonHeaders + mapOf("Referer" to watchUrl))
                            val content = resp.text
                            nlog("[Step4] ${embed.take(60)} -> ${resp.code} len=${content.length}")
                            if (content.contains("#EXTM3U")) {
                                if (registerM3U8Link(content, resp.url ?: embed, resp.url ?: embed, "Vietsub", callback, siteReferer = watchUrl)) {
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

            if (!linkFound && !siteDown()) {
                try {
                    if (tryHookFlow(watchUrl, callback)) linkFound = true
                    stepNotes.add("S2:${if (linkFound) "HIT" else "0"}")
                } catch (e: Throwable) {
                    if (isCancellation(e)) throw e
                    nlogE("[Step2]", e)
                    stepNotes.add("S2:ERR")
                }
            }
        }

        nlog("[loadLinks] SUMMARY ${stepNotes.joinToString(" | ")} -> result=$linkFound ms=${System.currentTimeMillis() - runT0} watch=$watchUrl")
        if (!linkFound) {
            ntoast("AnimeVsub $AVS_BUILD: không lấy được link — xem animevsub_diag.txt")
        }
        return linkFound
    }

    private suspend fun extractFromEmbed(
        embed: String, watchUrl: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {

            if (embed.contains("googleapiscdn") && embed.contains("/player/")) {
                nlog("[Embed] player CDN — bỏ DOM chết, hook runtime luôn ${embed.take(60)}")
                return tryHookFlow(embed, callback)
            }

            val doc: Document? = try {
                fetchDoc(embed, useCache = false, mode = MODE_LOOSE)
            } catch (e: Throwable) {
                if (isCancellation(e)) throw e
                nlogE("[Embed] fetchDoc", e, embed.take(60))
                null
            }
            if (doc != null) run {
            val html = doc.outerHtml()
            val embedOrigin = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: mainUrl

            val (m3u8s, mp4s) = harvestMediaUrls(html)
            nlog("[Embed] $embed -> m3u8=${m3u8s.size} mp4=${mp4s.size}")

            for (m3u8 in m3u8s) {
                val content = fetchText(m3u8, referer = embedOrigin) ?: continue
                if (content.contains("#EXTM3U") &&
                    registerM3U8Link(content, m3u8, m3u8, "Vietsub", callback, siteReferer = embedOrigin)) {
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

            val nested = harvestIframes(doc).filter { it != embed }.firstOrNull()
            if (nested != null) {
                val nestedDoc = fetchDoc(nested, useCache = false, mode = MODE_LOOSE)
                val nestedHtml = nestedDoc.outerHtml()
                val nestedOrigin = Regex("""^(https?://[^/]+)""").find(nested)?.groupValues?.get(1) ?: embedOrigin
                val (nm3u8, nmp4) = harvestMediaUrls(nestedHtml)
                for (m3u8 in nm3u8) {
                    val content = fetchText(m3u8, referer = nestedOrigin) ?: continue
                    if (content.contains("#EXTM3U") &&
                        registerM3U8Link(content, m3u8, m3u8, "Vietsub", callback, siteReferer = nestedOrigin)) {
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
            }

            nlog("[Embed] DOM không có media — mở hook WebView runtime ${embed.take(60)}")
            if (tryHookFlow(embed, callback)) return true
        } catch (e: Throwable) {
            if (isCancellation(e)) throw e
            nlogE("[Embed]", e, embed.take(70))
        }
        return false
    }

    private val avsHookJs = """
(function(){
  if (window.__avsHooked) return; window.__avsHooked = true;
  function abs(u){
    var s = String(u);
    if (s.indexOf('http') === 0) return s;
    if (s.charAt(0) === '/') return location.origin + s;
    return location.origin + '/' + s;
  }
  function isVideo(u){
    var s = String(u || '').split('?')[0].toLowerCase();
    var hostMatch = abs(s).match(/^https?:\/\/([^\/?:#]+)(?::\d+)?/);
    var host = hostMatch ? hostMatch[1] : '';
    if (host !== 'googleapiscdn.com' && !/\.googleapiscdn\.com$/.test(host)) return false;
    if (s.indexOf('/playlist/') >= 0 || /\.m3u8?$|\.m3u9$/.test(s)) return false;
    return s.indexOf('/hls/') >= 0 || s.indexOf('/chunks/') >= 0;
  }
  function isBinary(u){ return isVideo(u) || String(u || '').indexOf('/seg-key/') >= 0; }
  function removeActive(list, item){
    var i = list.indexOf(item); if (i >= 0) list.splice(i, 1);
  }
  window.__avsActiveVideoXHR = window.__avsActiveVideoXHR || [];
  window.__avsActiveVideoFetch = window.__avsActiveVideoFetch || [];
  function maybeDecrypt(u, b, headers){
    try {
      if (window.__avsRealSent || window.__avsDecrypting || String(u).indexOf('/playlist/') < 0 ||
          b.indexOf('#EXTM3U') !== 0 || b.indexOf('_t=') < 0) return;
      var dec = window._avsDecryptM3u8;
      if (typeof dec !== 'function') return;
      window.__avsDecrypting = Date.now();
      Promise.resolve().then(function(){ return dec.call(window, b, headers || {}); })
        .then(function(out){
          window.__avsDecrypting = 0;
          if (!window.__avsRealSent && typeof out === 'string' &&
              out.indexOf('#EXTM3U') === 0 && out.indexOf('_t=') < 0) {
            window.__avsRealSent = true;
            try { window.__avsBridge.onPlaylistReal(out); } catch (e) {}
          }
        }, function(){ window.__avsDecrypting = 0; });
    } catch (e) { window.__avsDecrypting = 0; }
  }
  function pass(u, b, headers){
    try {
      if (!b || b.length < 8 || b.length > 2000000) return;
      maybeDecrypt(u, b, headers);
      var sig = (String(u).length + ':' + b.length + ':' + b.substring(0, 24));
      window.__avsSeen = window.__avsSeen || {};
      if (window.__avsSeen[sig]) return;
      if (Object.keys(window.__avsSeen).length > 64) window.__avsSeen = {};
      window.__avsSeen[sig] = 1;
      var s = String(u);
      var isPlaylist = b.indexOf('#EXTM3U') >= 0;
      var isMediaUrl = s.indexOf('.m3u8') >= 0 || s.indexOf('.m3u9') >= 0 || s.indexOf('.mp4') >= 0;
      var bodyHasMedia = b.indexOf('.m3u8') >= 0 || b.indexOf('.m3u9') >= 0 || b.indexOf('.mp4') >= 0;
      if (isPlaylist || isMediaUrl || bodyHasMedia) {
        window.__avsBridge.onCapture(abs(u), b);
      }
    } catch (e) {}
  }
  function rec(u, hdrs, m, cred){
    try {
      var s = String(u);
      if (s.indexOf('/chunks/') < 0 && s.indexOf('/playlist/') < 0 && s.indexOf('/api/') < 0) return;
      var parts = [];
      try {
        if (hdrs) {
          if (typeof hdrs.forEach === 'function') hdrs.forEach(function(v, k){ parts.push(k + '=' + v); });
          else if (hdrs.length) { for (var i = 0; i < hdrs.length; i++) { parts.push(hdrs[i][0] + '=' + hdrs[i][1]); } }
          else { for (var k in hdrs) { parts.push(k + '=' + hdrs[k]); } }
        }
      } catch (e2) {}
      window.__avsBridge.onReq(abs(s), parts.join('|'), String(m || 'GET'), String(cred || ''));
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
      window.__avsBaseFetch = window.__avsBaseFetch || of;
      window.fetch = function(){
        var args = arguments;
        var u = (args[0] && args[0].url) ? String(args[0].url) : String(args[0]);
        var init = args[1], controller = null;
        if (isVideo(u)) {
          if (window.__avsPausePlayerMedia) return Promise.reject(new Error('AVS player suspended'));
          if (typeof AbortController !== 'undefined') {
            try {
              controller = new AbortController();
              var nextInit = Object.assign({}, init || {});
              var oldSignal = nextInit.signal || (args[0] && args[0].signal);
              if (oldSignal) {
                if (oldSignal.aborted) controller.abort();
                else if (oldSignal.addEventListener) oldSignal.addEventListener('abort', function(){ controller.abort(); }, {once:true});
              }
              nextInit.signal = controller.signal;
              args = [args[0], nextInit];
              var active = window.__avsActiveVideoFetch;
              active.push(controller);
              if (active.length > 32) active.shift();
              (function(c){ setTimeout(function(){ removeActive(active, c); }, 60000); })(controller);
            } catch (e) { controller = null; }
          }
        }
        try { rec(u, init ? init.headers : null, init ? init.method : 'GET', init ? init.credentials : ''); } catch (e0) {}
        return of.apply(this, args).then(function(res){
          var headerMap = {};
          try {
            var su = String(u);
            if (su.indexOf('/playlist/') >= 0) {
              var hs = [];
              res.headers.forEach(function(v, k){ hs.push(k + '=' + v); headerMap[k] = v; });
              window.__avsBridge.onPlRes(abs(u), hs.join('|'));
            }
            if (su.indexOf('/chunks/') >= 0) {
              window.__avsBridge.onSegRes(abs(su), String(res.status));
            }
          } catch (e1) {}
          if (!isBinary(u)) try {
            var ct = String(res.headers.get('content-type') || '').toLowerCase();
            var len = parseInt(res.headers.get('content-length') || '0', 10) || 0;
            if ((!/^(image|video|audio)\//.test(ct) || /mpegurl|m3u8/.test(ct)) && len <= 2000000)
              res.clone().text().then(function(t){ pass(u, t, headerMap); }, function(){});
          } catch (e) {}
          return res;
        }, function(err){
          if (controller) removeActive(window.__avsActiveVideoFetch, controller);
          throw err;
        });
      };
    }
  } catch (e) {}
  try {
    var oo = XMLHttpRequest.prototype.open, osh = XMLHttpRequest.prototype.setRequestHeader, os = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u){ this.__avsU = u; this.__avsM = m; this.__avsH = {}; return oo.apply(this, arguments); };
    XMLHttpRequest.prototype.setRequestHeader = function(k, v){ try { if (this.__avsH) { this.__avsH[k] = v; } } catch (e) {} return osh.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function(){
      var self = this;
      if (isVideo(self.__avsU)) {
        if (window.__avsPausePlayerMedia) { try { self.abort(); } catch (e) {} return; }
        var active = window.__avsActiveVideoXHR;
        active.push(self);
        if (active.length > 32) active.shift();
        this.addEventListener('loadend', function(){ removeActive(active, self); });
      }
      try { rec(self.__avsU, self.__avsH, self.__avsM, String(self.withCredentials)); } catch (e0) {}
      this.addEventListener('load', function(){
        var headerMap = {};
        try {
          var su = String(self.__avsU || '');
          if (su.indexOf('/playlist/') >= 0) {
            var rawHeaders = self.getAllResponseHeaders() || '';
            window.__avsBridge.onPlRes(abs(su), rawHeaders);
            rawHeaders.split(/\r?\n/).forEach(function(line){
              var p = line.indexOf(':');
              if (p > 0) headerMap[line.substring(0, p).trim().toLowerCase()] = line.substring(p + 1).trim();
            });
          }
          if (su.indexOf('/chunks/') >= 0) { window.__avsBridge.onSegRes(abs(su), String(self.status)); }
        } catch (e1) {}
        if (!isBinary(self.__avsU)) try {
          var ct = String(self.getResponseHeader('content-type') || '').toLowerCase();
          var len = parseInt(self.getResponseHeader('content-length') || '0', 10) || 0;
          if ((!/^(image|video|audio)\//.test(ct) || /mpegurl|m3u8/.test(ct)) && len <= 2000000) {
            var t = xhrText(self); if (t) { pass(self.__avsU, t, headerMap); }
          }
        } catch (e) {}
      });
      return os.apply(this, arguments);
    };
  } catch (e) {}
  try {
    var pmN = 0;
    window.addEventListener('message', function(ev){
      try {
        if (pmN++ > 8) return;
        var d = '';
        try { d = JSON.stringify(ev.data).substring(0, 240); } catch (e2) { d = String(ev.data).substring(0, 240); }
        window.__avsBridge.onReq('PM:' + String(ev.origin || '?'), d, 'MSG', '');
      } catch (e) {}
    });
  } catch (e) {}
})();
"""

    private class AvsCaptureBridge(
        private val handler: (String, String) -> Unit,
        private val reqHandler: (String, String, String, String) -> Unit = { _, _, _, _ -> },
        private val plResHandler: (String, String) -> Unit = { _, _ -> },
        private val segResHandler: (String, String) -> Unit = { _, _ -> },
        private val relayMetaHandler: (String, Int, String?, String?) -> Unit = { _, _, _, _ -> },
        private val relayHandler: (String, String?, Int, String?) -> Unit = { _, _, _, _ -> }
    ) {
        @JavascriptInterface
        fun onCapture(url: String?, body: String?) {
            if (url.isNullOrBlank() || body.isNullOrBlank()) return
            try { handler(url, body) } catch (_: Throwable) {}
        }

        @JavascriptInterface
        fun onReq(url: String?, headers: String?, method: String?, cred: String?) {
            if (url.isNullOrBlank()) return
            try { reqHandler(url, headers ?: "", method ?: "GET", cred ?: "") } catch (_: Throwable) {}
        }

        @JavascriptInterface
        fun onPlRes(url: String?, headers: String?) {
            if (url.isNullOrBlank()) return
            try { plResHandler(url, headers ?: "") } catch (_: Throwable) {}
        }

        @JavascriptInterface
        fun onSegRes(url: String?, status: String?) {
            if (url.isNullOrBlank()) return
            try { segResHandler(url, status ?: "?") } catch (_: Throwable) {}
        }

        @JavascriptInterface
        fun onRelayMeta(id: String?, status: Int, contentRange: String?, contentType: String?) {
            if (id.isNullOrBlank()) return
            try { relayMetaHandler(id, status, contentRange, contentType) } catch (_: Throwable) {}
        }

        @JavascriptInterface
        fun onRelayChunk(id: String?, b64: String?, last: Int, err: String?) {
            if (id.isNullOrBlank()) return
            try { relayHandler(id, b64, last, err) } catch (_: Throwable) {}
        }

        @JavascriptInterface
        fun onPlaylistReal(body: String?) {
            if (body.isNullOrBlank()) return
            try { handler("avs-playlist-real", body) } catch (_: Throwable) {}
        }
    }

    private fun appContext(): android.content.Context? {
        currentActivity()?.let { return it }
        return diagContext()
    }

    private fun currentActivity(): android.app.Activity? {
        try {
            val cls = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            fun fromObj(o: Any?): android.app.Activity? = when (o) {
                is android.app.Activity -> o
                is java.lang.ref.WeakReference<*> -> o.get() as? android.app.Activity
                else -> null
            }
            for (f in cls.declaredFields) {
                try {
                    f.isAccessible = true
                    fromObj(f.get(null))?.let { if (!it.isFinishing) return it }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun isFastRealPlaylist(url: String, body: String): Boolean {
        if (!AVS_FAST_REAL_PLAY || !url.startsWith("avs-playlist-real")) return false
        if (!body.trimStart().startsWith("#EXTM3U") || body.contains("_t=") || body.contains("/chunks/")) return false
        val segments = body.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        return segments.isNotEmpty() && segments.all {
            val host = hostOf(it)
            (it.startsWith("http://") || it.startsWith("https://")) && it.contains("/hls/") &&
                (host == "googleapiscdn.com" || host.endsWith(".googleapiscdn.com"))
        }
    }

    private fun isCdnVideoRequest(url: String, targetUrl: String): Boolean = try {
        val uri = java.net.URI(url)
        val target = java.net.URI(targetUrl)
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: ""
        val targetHost = target.host?.lowercase(java.util.Locale.ROOT) ?: ""
        val path = (uri.path ?: "").lowercase(java.util.Locale.ROOT)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            host == targetHost && (host == "googleapiscdn.com" || host.endsWith(".googleapiscdn.com")) &&
            (path.contains("/hls/") || path.contains("/chunks/")) &&
            !path.endsWith(".m3u8") && !path.endsWith(".m3u9") &&
            !path.endsWith(".key") && !path.endsWith("/key") &&
            !path.contains("/key/") && !path.contains("/seg-key/")
    } catch (_: Throwable) { false }

    private class AvsMediaGate {
        @Volatile private var paused = false
        @Volatile private var recovering = false
        private val relayUses = java.util.concurrent.atomic.AtomicInteger(0)
        val blockedCount = java.util.concurrent.atomic.AtomicInteger(0)
        @Synchronized fun pauseUnlessRecovering(): Boolean {
            if (!recovering) paused = true
            return paused
        }
        @Synchronized fun beginRecovery() { recovering = true; paused = false }
        @Synchronized fun endRecovery() { recovering = false; paused = true }
        fun beginRelay() { relayUses.incrementAndGet() }
        fun endRelay() { relayUses.decrementAndGet() }
        fun shouldBlock(): Boolean = paused && relayUses.get() == 0
    }

    private suspend fun tryHookFlow(targetUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val flowT0 = System.currentTimeMillis()
        val mediaGate = AvsMediaGate()
        var webView: WebView? = null
        var dialog: android.app.Dialog? = null

        var adoptedRelay = false
        val captures = Channel<Pair<String, String>>(Channel.UNLIMITED)
        val cdnReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            val ctx = appContext()
            if (ctx == null) {
                nlog("[Hook] không có app context cho WebView")
                return false
            }

            val captureCount = java.util.concurrent.atomic.AtomicInteger(0)

            withContext(Dispatchers.Main) {
                val wv = WebView(ctx)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true

                wv.settings.userAgentString = USER_AGENT
                wv.settings.allowFileAccess = false
                wv.settings.allowContentAccess = false
                wv.settings.mediaPlaybackRequiresUserGesture = false
                try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Throwable) {}
                wv.addJavascriptInterface(
                    AvsCaptureBridge(
                        handler = { url, body ->
                            if (captures.trySend(url to body).isSuccess) {
                                captureCount.incrementAndGet()
                                nlog("[Hook] bridge +${System.currentTimeMillis() - flowT0}ms url=${url.take(60)} len=${body.length} head=${body.take(12)}")
                            }
                        },
                        reqHandler = { url, headers, method, _ ->

                            if (url.contains("/chunks/")) {
                                val isFirst = segObservedUrl == null
                                segObservedUrl = url
                                segObservedHeaders = parseHdrPairs(headers)
                                segRecipeUpdateAt = System.currentTimeMillis()
                                buildSegRecipe()
                                val n = segReqCount.incrementAndGet()
                                if (isFirst) {

                                    nlog("[Hook] SEG-REQ +${System.currentTimeMillis() - flowT0}ms $method url=$url hdrs=$headers cred=hit")
                                } else if (n <= 4 || n % 15 == 0) {
                                    nlog("[Hook] SEG-REQ#$n $method url=...${url.takeLast(70)} hdrs=${headers.take(80)}")
                                }
                            } else if (url.startsWith("PM:")) {

                                if (pmLogCount.incrementAndGet() <= 6) {
                                    nlog("[Hook] PM ${url.take(30)} ${headers.take(220)}")
                                }
                            } else if (url.contains("/playlist/")) {
                                nlog("[Hook] PL-REQ +${System.currentTimeMillis() - flowT0}ms $method url=${url.take(80)}")
                            } else if (url.contains("/api/")) {

                                if (apiLogCount.incrementAndGet() <= 6) {
                                    nlog("[Hook] API-REQ $method $url hdrs=${headers.take(120)}")
                                }
                            }
                        },
                        plResHandler = { url, headers ->

                            nlog("[Hook] PL-RES ${url.take(60)} hdrs=${headers.take(1500)}")
                        },
                        segResHandler = { url, status ->

                            if (url.contains("/chunks/")) {
                                if (status.toIntOrNull() in 200..299) playerSegOkSeen = true
                                val n = segResCount.incrementAndGet()
                                if (n <= 4 || n % 15 == 0) {
                                    nlog("[Hook] SEG-RES#$n st=$status url=...${url.takeLast(70)}")
                                }
                            } else if (url.contains("/playlist/")) {
                                nlog("[Hook] PL-RES-ST st=$status ${url.take(50)}")
                            }
                        },
                        relayMetaHandler = { id, status, range, ct ->
                            handleRelayMeta(id, status, range, ct)
                        },
                        relayHandler = { id, b64, last, err ->
                            handleRelayChunk(id, b64, last, err)
                        }
                    ),
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

                        if (url?.contains("googleapiscdn") == true || url?.contains("/player/") == true) {
                            view?.evaluateJavascript(AVS_REAL_PL_JS, null)
                        }
                    }
                    override fun onReceivedError(
                        view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?
                    ) {
                        try { nlog("[Hook] WV error ${error?.errorCode} ${error?.description} ${request?.url}") } catch (_: Throwable) {}
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?, request: android.webkit.WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val u = request?.url?.toString() ?: return null
                        if (request.method.equals("GET", true) && mediaGate.shouldBlock() &&
                            isCdnVideoRequest(u, targetUrl)) {
                            if (mediaGate.blockedCount.incrementAndGet() <= 2)
                                nlog("[Fast] ngắt tải video nền ở tầng WebView")
                            return android.webkit.WebResourceResponse(
                                "application/octet-stream", "UTF-8", 204, "No Content",
                                mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"),
                                java.io.ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        val isRealMedia = hostOf(u) == hostOf(targetUrl) &&
                            (u.contains("/hls/") || u.contains("/seg-key/"))
                        if (!u.contains("/chunks/") && !u.contains("/playlist/") && !isRealMedia) return null
                        if (isRealMedia && cdnReady.complete(Unit)) {
                            nlog("[Hook] player đã yêu cầu key/segment thật +${System.currentTimeMillis() - flowT0}ms")
                        }
                        val hdrs = try {
                            request.requestHeaders?.entries?.joinToString("|") { "${it.key}=${it.value}" } ?: ""
                        } catch (_: Throwable) { "" }
                        if (u.contains("/chunks/")) {
                            segObservedUrl = u
                            segObservedHeaders = parseHdrPairs(hdrs)
                            segRecipeUpdateAt = System.currentTimeMillis()
                            buildSegRecipe()
                        }
                        val n = siReqCount.incrementAndGet()
                        if (n <= 2 || n % 25 == 0) {

                            nlog("[SI] #$n ${request.method} ${u.take(80)} hdrs=${hdrs.take(700)}")
                        }
                        return null
                    }
                }

                val isSiteTarget = targetUrl.startsWith(mainUrl)
                if (ctx is android.app.Activity && !ctx.isFinishing && isSiteTarget) {
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
                var pendingPl: Pair<String, String>? = null
                while (!registered && tries < 6) {
                    tries++
                    val recv = try { captures.receive() } catch (e: Throwable) {
                        if (isCancellation(e)) throw e
                        nlogE("[Hook] receive", e); null
                    }
                    if (recv == null) break
                    val (capUrl, capBody) = recv

                    val body = capBody.trim()
                    if (body.startsWith("#EXTM3U")) {
                        if (capUrl.startsWith("avs-playlist-real")) {
                            pendingPl = recv
                            realPlaylistSeen = true
                            break
                        }
                        if (!body.contains("_t=")) {

                            pendingPl = recv
                            break
                        }

                        var chosen: Pair<String, String>? = null
                        withTimeoutOrNull(6_000L) {
                            while (chosen == null) {
                                val next = captures.receiveCatching().getOrNull() ?: break
                                if (next.first.startsWith("avs-playlist-real") &&
                                    next.second.trimStart().startsWith("#EXTM3U") && !next.second.contains("_t=")) {
                                    chosen = next
                                }
                            }
                        }
                        if (chosen != null) {
                            nlog("[Hook] dùng manifest THẬT (decrypt) thay placeholder")
                            realPlaylistSeen = true
                            pendingPl = chosen
                        } else {
                            nlog("[Hook] raw playlist chưa được giải mã — không đăng ký")
                        }
                        break
                    } else {
                        val (m3u8s, mp4s) = harvestMediaUrls(body)
                        val m3u8 = m3u8s.firstOrNull()
                        when {
                            m3u8 != null -> {
                                val origin = Regex("""^(https?://[^/]+)""").find(m3u8)?.groupValues?.get(1) ?: ""
                                val m3u8Dir = Regex("""^(https?://[^/]+/.*/)""").find(m3u8)?.groupValues?.get(1) ?: m3u8
                                val content = fetchText(m3u8, referer = origin.ifBlank { targetUrl })
                                if (content != null && content.contains("#EXTM3U") &&
                                    registerM3U8Link(content, m3u8, m3u8Dir, "Vietsub", callback, siteReferer = targetUrl)) {
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

                if (registered) {
                    try {
                        withContext(Dispatchers.Main) {
                            webView?.stopLoading()
                            webView?.loadUrl("about:blank")
                        }
                    } catch (_: Throwable) {}
                }

                if (!registered && pendingPl != null) {
                    val (capUrlP, capBodyP) = pendingPl

                    lastPlaylistLines = capBodyP.trim().lines().map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .map { resolvePlaylistUri(it, capUrlP) ?: it }
                    segObservedUrl = null
                    segObservedHeaders = emptyMap()
                    segReqCount.set(0)
                    segResCount.set(0)

                    try {
                        lastKickAtMs = System.currentTimeMillis()
                        withContext(Dispatchers.Main) { webView?.evaluateJavascript(AVS_KICK_JS, null) }
                    } catch (_: Throwable) {}

                    val fastReal = isFastRealPlaylist(capUrlP, capBodyP)
                    if (fastReal && !cdnReady.isCompleted) {
                        withTimeoutOrNull(REAL_STARTUP_GRACE_MS) { cdnReady.await() }
                        nlog("[Hook] fast-real: đợi player tối đa ${REAL_STARTUP_GRACE_MS}ms; cdnReady=${cdnReady.isCompleted}")
                    }
                    val obsMaxMs = if (fastReal) 0L else 8_000L
                    val obsT0 = System.currentTimeMillis()
                    var kicks = 1
                    while (segObservedUrl == null && System.currentTimeMillis() - obsT0 < obsMaxMs) {
                        kotlinx.coroutines.delay(400)

                        if (kicks < 4 && System.currentTimeMillis() - obsT0 > 3_000L * kicks) {
                            kicks++
                            try {
                                withContext(Dispatchers.Main) { webView?.evaluateJavascript(AVS_KICK_JS, null) }
                            } catch (_: Throwable) {}
                        }
                    }
                    if (segObservedUrl != null) buildSegRecipe()
                    else if (obsMaxMs == 0L) {
                        nlog("[Hook] fast-real: bỏ chờ recipe 8s; giữ fallback khi CDN không sẵn sàng (v31)")
                    } else {
                        nlog("[Hook] player chưa fetch segment trong 8s — proxy sẽ ĐỢI recipe (F6), không dội method mù")
                        try {
                            withContext(Dispatchers.Main) {
                                webView?.evaluateJavascript(AVS_VIDEO_STATE_JS) { r ->
                                    nlog("[Hook] video-state ${r?.take(180)}")
                                }
                            }
                        } catch (_: Throwable) {}
                    }

                    val baseP = Regex("""^(https?://[^/]+/.*/)""").find(capUrlP)?.groupValues?.get(1) ?: ""
                    if (registerM3U8Link(capBodyP, capUrlP, baseP, "Vietsub", callback,
                            siteReferer = targetUrl, mediaGate = mediaGate)) {
                        registered = true
                        if (!targetUrl.startsWith(mainUrl)) {

                            adoptRelayWebView(webView, dialog, fastReal, mediaGate)
                            adoptedRelay = true
                        } else {
                            try {
                                withContext(Dispatchers.Main) {
                                    webView?.stopLoading()
                                    webView?.loadUrl("about:blank")
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                registered
            }

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

            captures.close()

            if (!adoptedRelay) {

                try { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { dialog?.dismiss() } } catch (_: Throwable) {}
                try { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { webView?.destroy() } } catch (_: Throwable) {}
            }
        }
    }

    private val gateMutex = Mutex()
    @Volatile private var lastGateAttemptMs = 0L
    private val GATE_RETRY_MS = 10 * 60 * 1000L
    private val GATE_OPEN_TIMEOUT_MS = 90_000L

    private val domPollJs = """
(function(){
  if (window.__avsDomLoop) return; window.__avsDomLoop = true;
  var pending = 0, lastSent = 0;
  function send(){
    pending = 0;
    try {
      var h = document.documentElement && document.documentElement.outerHTML;
      if (h && h.length > 64) {
        lastSent = Date.now();
        window.__avsGate.onDom(String(location.href), h);
      }
    } catch (e) {}
  }
  function schedule(){
    if (pending) return;
    pending = setTimeout(send, Math.max(100, 250 - (Date.now() - lastSent)));
  }
  function observe(){
    try {
      if (document.documentElement && window.MutationObserver) {
        new MutationObserver(schedule).observe(document.documentElement, {
          childList: true, subtree: true, attributes: true,
          attributeFilter: ['src', 'data-src', 'href', 'data-id', 'class']
        });
      }
    } catch (e) {}
  }
  if (document.documentElement) observe();
  else document.addEventListener('DOMContentLoaded', observe, {once: true});
  send();
  setInterval(function(){ if (!pending) send(); }, 800);
})();
"""

    private class AvsGateBridge(private val handler: (String, String) -> Unit) {
        @JavascriptInterface
        fun onDom(url: String?, html: String?) {
            if (url.isNullOrBlank() || html.isNullOrBlank()) return
            try { handler(url, html) } catch (_: Throwable) {}
        }
    }

    private suspend fun webviewDocCapture(
        url: String, mode: Int, timeoutMs: Long, interactive: Boolean
    ): Document? {
        val ctx = appContext()
        if (ctx == null) {
            nlog("[WVDom] không có context — bỏ qua webview $url")
            return null
        }
        if (interactive && (ctx !is android.app.Activity || ctx.isFinishing)) return null

        val gate = if (mode == MODE_PLAY) playWvGate else wvGate
        while (!gate.tryAcquire()) kotlinx.coroutines.delay(40)

        val done = kotlinx.coroutines.CompletableDeferred<Document?>()
        var webView: WebView? = null
        var dialog: android.app.Dialog? = null
        var lastHtml: String? = null
        var lastBlocked = true
        var sawSrvErr = false
        try {
            withContext(Dispatchers.Main) {
                val wv = WebView(ctx)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true

                wv.settings.userAgentString = USER_AGENT
                wv.settings.mediaPlaybackRequiresUserGesture = false

                try { wv.settings.blockNetworkImage = true } catch (_: Throwable) {}
                try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Throwable) {}

                var lastSig = ""
                var pollIdx = 0
                var lastCards = 0
                var lastXem = 0
                var lastEps = 0
                var lastPageUrl = ""
                var firstPagePollMs = 0L
                val blockedPlayerFrames = java.util.concurrent.atomic.AtomicInteger(0)

                fun domReady(u: String, html: String, blocked: Boolean, cards: Int, xem: Int, eps: Int, ageMs: Long): Boolean {
                    val isWatchPage = isWatchPageUrl(u)
                    val hasPlayerFrame = hasPlayerIframe(html)

                    return !blocked && when (mode) {
                        MODE_LISTING -> cards > 0
                        MODE_DETAIL, MODE_PLAY -> if (isWatchPage) {
                            hasPlayerFrame || (xem > 0 && ageMs >= 2_400L) || ageMs >= 7_200L
                        } else xem > 0 || cards > 0 || ageMs >= 5_600L
                        MODE_EPISODES -> (eps > 0 && (hasPlayerFrame || ageMs >= 2_400L)) || ageMs >= 7_200L
                        else         -> ageMs >= 2_400L || cards > 0 || xem > 0
                    }
                }
                wv.addJavascriptInterface(AvsGateBridge { u, html ->
                    if (done.isCompleted) return@AvsGateBridge
                    pollIdx++
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (u != lastPageUrl) {
                        lastPageUrl = u
                        firstPagePollMs = nowMs
                        lastSig = ""
                        lastHtml = null
                    }
                    val pageAgeMs = nowMs - firstPagePollMs
                    val sig = html.length.toString() + ":" + html.hashCode()
                    if (sig == lastSig) {

                        val lh = lastHtml
                        if (lh != null && domReady(u, lh, lastBlocked, lastCards, lastXem, lastEps, pageAgeMs)) {
                            if (!lastBlocked && (lastCards > 0 || lastXem > 0 || lastEps > 0 || hasPlayerIframe(lh))) {
                                noteSiteOk()
                                learnCanonical(u)
                            }
                            saveCfCookiesFromManager("WVDom")
                            try { wv.post { try { wv.stopLoading() } catch (_: Throwable) {} } } catch (_: Throwable) {}
                            done.complete(Jsoup.parse(lh, u))
                        }
                        return@AvsGateBridge
                    }
                    lastSig = sig
                    val blocked = looksBlocked(html)
                    val srvErr = looksServerError(html)
                    val cards = countPhimAnchors(html)
                    val xem = countXemPhim(html)
                    val eps = countEpisodeAnchors(html)
                    nlog("[WVDom] poll#$pollIdx len=${html.length} blocked=$blocked srvErr=$srvErr cards=$cards xem=$xem eps=$eps ${u.take(70)}")

                    if (srvErr) {
                        noteServerError("WVDom", u)
                        sawSrvErr = true
                        try { wv.post { try { wv.stopLoading() } catch (_: Throwable) {} } } catch (_: Throwable) {}
                        if (!done.isCompleted) done.complete(null)
                        return@AvsGateBridge
                    }

                    if (u.endsWith("/404") || html.contains("Tên miền tạm thời bị chặn")) {
                        nlog("[WVDom] 404/tên-miền-bị-chặn — trả null ngay $u")
                        try { wv.post { try { wv.stopLoading() } catch (_: Throwable) {} } } catch (_: Throwable) {}
                        if (!done.isCompleted) done.complete(null)
                        return@AvsGateBridge
                    }
                    lastHtml = html
                    lastBlocked = blocked
                    lastCards = cards
                    lastXem = xem
                    lastEps = eps
                    val ready = domReady(u, html, blocked, cards, xem, eps, pageAgeMs)
                    if (ready) {
                        if (!blocked && (cards > 0 || xem > 0 || eps > 0 || hasPlayerIframe(html))) {
                            noteSiteOk()
                            learnCanonical(u)
                        }
                        saveCfCookiesFromManager("WVDom")
                        try { wv.post { try { wv.stopLoading() } catch (_: Throwable) {} } } catch (_: Throwable) {}
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
                    override fun shouldInterceptRequest(
                        view: WebView?, request: android.webkit.WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val req = request ?: return null
                        if ((mode == MODE_PLAY || mode == MODE_EPISODES) && !req.isForMainFrame &&
                            isBackgroundPlayerUrl(req.url.toString())) {
                            if (blockedPlayerFrames.incrementAndGet() == 1)
                                nlog("[WVDom] giữ iframe, tắt player phụ trong trang xem")
                            val body = "<!doctype html><html><body></body></html>".toByteArray(Charsets.UTF_8)
                            return android.webkit.WebResourceResponse("text/html", "UTF-8", java.io.ByteArrayInputStream(body))
                        }
                        return null
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
                        dlg.setCancelable(true)
                        dlg.setOnDismissListener {
                            try { if (!done.isCompleted) done.complete(null) } catch (_: Throwable) {}
                        }
                        dlg.show()
                        dialog = dlg
                    } catch (e: Throwable) {

                        nlogE("[WVDom] dialog", e, "chạy headless")
                    }
                }

                wv.loadUrl(toCanonical(url), mapOf("Referer" to "$mainUrl/"))
            }

            val t0 = System.currentTimeMillis()
            var doc = withTimeoutOrNull(timeoutMs) { done.await() }
            if (doc != null) {
                nlog("[WVDom] OK interactive=$interactive ms=${System.currentTimeMillis() - t0} $url")
            } else {

                if (sawSrvErr) {
                    nlog("[WVDom] DỪNG: trang lỗi 5xx (site-down) interactive=$interactive $url")
                } else {
                    val lh = lastHtml
                    if (lh != null && !lastBlocked) {
                        nlog("[WVDom] TIMEOUT nhưng DOM sạch (len=${lh.length}) — trả DOM cuối $url")
                        doc = Jsoup.parse(lh, url)
                    } else {
                        nlog("[WVDom] TIMEOUT/BỊ CHẶN ${timeoutMs / 1000}s interactive=$interactive $url")
                    }
                }
            }
            return doc
        } finally {

            try { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { dialog?.dismiss() } } catch (_: Throwable) {}
            try { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { webView?.destroy() } } catch (_: Throwable) {}
            gate.release()
        }
    }

    private suspend fun unlockGate(url: String, mode: Int = MODE_LISTING): Document? = gateMutex.withLock {

        if (srvStormActive()) {
            nlog("[Gate] bỏ qua — server đang lỗi 5xx (STORM-GATE)")
            return@withLock null
        }
        if (System.currentTimeMillis() - lastGateAttemptMs < GATE_RETRY_MS) {

            nlog("[Gate] bỏ qua (đã có unlock trong ${GATE_RETRY_MS / 60000} phút gần đây)")
            return@withLock null
        }

        val ctx = appContext()
        if (ctx !is android.app.Activity || ctx.isFinishing) {
            nlog("[Gate] không có Activity foreground — không mở dialog xác minh")
            return@withLock null
        }

        lastGateAttemptMs = System.currentTimeMillis()

        nlog("[Gate] mở dialog xác minh: $url")
        ntoast("AnimeVsub: site yêu cầu xác minh — hãy hoàn tất trong cửa sổ vừa mở")
        val doc = webviewDocCapture(url, mode, GATE_OPEN_TIMEOUT_MS, interactive = true)
        if (doc != null) nlog("[Gate] OK") else nlog("[Gate] TIMEOUT/HỦY sau ${GATE_OPEN_TIMEOUT_MS / 1000}s")
        doc
    }

    private data class SegRecipe(
        val extraParams: String,
        val headers: Map<String, String>,
        val sampleUrl: String,
        val at: Long
    )

    @Volatile private var segRecipe: SegRecipe? = null

    @Volatile private var realPlaylistSeen = false
    @Volatile private var segObservedUrl: String? = null
    @Volatile private var segObservedHeaders: Map<String, String> = emptyMap()
    @Volatile private var lastPlaylistLines: List<String>? = null

    @Volatile private var segRecipeUpdateAt = 0L
    @Volatile private var playerSegOkSeen = false
    @Volatile private var lastKickAtMs = 0L

    @Volatile private var segAuthBackoffGlobalUntil = 0L

    @Volatile private var relayGateUntilGlobalMs = 0L

    @Volatile private var dnsCooldownUntil = 0L
    @Volatile private var lastDnsLogMs = 0L

    @Volatile private var segStormToastShown = false

    private val allMethodFails = java.util.concurrent.atomic.AtomicInteger(0)
    private val segReqCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val segResCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val pmLogCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val apiLogCount = java.util.concurrent.atomic.AtomicInteger(0)

    private val siReqCount = java.util.concurrent.atomic.AtomicInteger(0)

    private class RelayBuf(val maxBytes: Int) {
        val latch = java.util.concurrent.CountDownLatch(1)
        val buf = java.io.ByteArrayOutputStream()
        @Volatile var done = false
        @Volatile var err: String? = null
        @Volatile var status = 0
        @Volatile var contentRange: String? = null
        @Volatile var contentType: String? = null
    }

    private data class AvsRelayResult(
        val bytes: ByteArray?, val error: String?, val status: Int,
        val contentRange: String?, val contentType: String?
    )

    @Volatile private var relayWv: WebView? = null
    @Volatile private var relayDialog: android.app.Dialog? = null
    @Volatile private var relayMediaGate: AvsMediaGate? = null
    private val relayLock = Any()
    private val relayPending = java.util.concurrent.ConcurrentHashMap<String, RelayBuf>()
    private val relaySeq = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var relayLastUseMs = 0L

    private val AVS_KICK_JS = "(function(){try{window.__avsPausePlayerMedia=false;var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){vs[i].muted=true;var p=vs[i].play();if(p&&p.catch)p.catch(function(){});}}catch(e){}})();"

    private val AVS_REAL_PL_JS = """
(function(){
  if (window.__avsRealPlRunning) return; window.__avsRealPlRunning = true;
  var tries = 0;
  function go() {
    if (window.__avsRealSent) return;
    if (window.__avsDecrypting && Date.now() - window.__avsDecrypting < 1800) { setTimeout(go, 120); return; }
    tries++;
    if (tries > 60) { try{ window.__avsBridge.onPlaylistReal("AVS-REALPL-FAIL max-tries"); }catch(e){} return; }
    try {
      var dec = window._avsDecryptM3u8;
      if (typeof dec !== "function") { setTimeout(go, 300); return; }
      var pid = (location.pathname.match(/\/player\/([0-9a-f]{16,})/) || [])[1] || (typeof id !== "undefined" ? id : "");
      var tok = window._avsSk || (typeof avsToken !== "undefined" ? avsToken : "");
      if (!pid || !tok) { setTimeout(go, 300); return; }
      var playlistUrl = location.origin + "/playlist/" + pid + "/playlist.m3u8?token=" + tok;
      try { window.__avsBridge.onReq(playlistUrl, "", "GET", "include"); } catch (e1) {}
      (window.__avsBaseFetch || window.fetch || fetch).call(window, playlistUrl, { credentials: "include" })
        .then(function(r) {
          var hs = {}, parts = [];
          try {
            r.headers.forEach(function(v, k) { hs[k] = v; parts.push(k + "=" + v); });
            window.__avsBridge.onPlRes(playlistUrl, parts.join("|"));
          } catch (e2) {}
          return r.text().then(function(t) {
            try { return Promise.resolve(dec.call(window, t, hs)); }
            catch (e3) { return "AVS-REALPL-ERR " + e3; }
          });
        })
        .then(function(out) {
          if (typeof out === "string" && out.indexOf("#EXTM3U") === 0 && out.indexOf("_t=") < 0) {
            if (!window.__avsRealSent) {
              window.__avsRealSent = true;
              try { window.__avsBridge.onPlaylistReal(out); } catch (e4) {}
            }
          } else if (!window.__avsRealSent) { setTimeout(go, 900); }
        })
        .catch(function() { setTimeout(go, 900); });
    } catch (e) { setTimeout(go, 900); }
  }
  setTimeout(go, 350);
})();
""";

    private val AVS_CALM_JS = "(function(){try{var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){try{vs[i].pause();}catch(e){}}}catch(e){}})();"

    private val AVS_CALM_FAST_JS = """
(function(){
  try {
    window.__avsPausePlayerMedia = true;
    var xs = (window.__avsActiveVideoXHR || []).slice(), fs = (window.__avsActiveVideoFetch || []).slice();
    window.__avsActiveVideoXHR = []; window.__avsActiveVideoFetch = [];
    for (var i = 0; i < xs.length; i++) { try { xs[i].abort(); } catch (e) {} }
    for (var j = 0; j < fs.length; j++) { try { fs[j].abort(); } catch (e) {} }
    var vs = document.querySelectorAll('video');
    for (var k = 0; k < vs.length; k++) { try { vs[k].pause(); } catch (e) {} }
    return JSON.stringify({xhr:xs.length, fetch:fs.length, videos:vs.length});
  } catch (e) { return 'error'; }
})();
"""

    private val AVS_VIDEO_STATE_JS = "(function(){try{var v=document.querySelector('video');var h=false;try{h=!!(window.hls||window.Hls);}catch(e0){}if(!v)return 'novideo hls='+h;return 'rs='+v.readyState+' paused='+v.paused+' t='+Math.round(v.currentTime||0)+' net='+v.networkState+' hls='+h+' src='+String(v.currentSrc||v.src||'').substring(0,50);}catch(e){return 'err '+(e&&e.message?e.message:'?')}})();"

    private val avsRelayJs = """
(function(){
  if (window.__avsRelayInstalled) return; window.__avsRelayInstalled = true;
  window.__avsRelay = function(id, url, headers, maxBytes, timeoutMs){
    var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var finished = false;
    var timer = setTimeout(function(){ if (controller) controller.abort(); else finish('timeout'); }, timeoutMs);
    function finish(err){
      if (finished) return; finished = true; clearTimeout(timer);
      try { window.__avsBridge.onRelayChunk(id, '', 1, err || null); } catch(e) {}
    }
    function send(u8){
      var CH = 98304;
      for (var i = 0; i < u8.length; i += CH) {
        var end = Math.min(i + CH, u8.length), bin = '';
        for (var j = i; j < end; j += 8192) {
          bin += String.fromCharCode.apply(null, u8.subarray(j, Math.min(j + 8192, end)));
        }
        window.__avsBridge.onRelayChunk(id, btoa(bin), 0, null);
      }
    }
    try {
      var h = {}, hasRange = false;
      if (headers) for (var k in headers) {
        h[k] = headers[k]; if (String(k).toLowerCase() === 'range') hasRange = true;
      }
      var opt = {credentials: 'include', headers: h};
      if (controller) opt.signal = controller.signal;
      (window.__avsBaseFetch || window.fetch || fetch).call(window, url, opt).then(function(r){
        if (finished) { if (r.body && r.body.cancel) r.body.cancel(); return; }
        if (!r.ok) { finish('HTTP' + r.status); return; }
        var cr = r.headers.get('content-range') || '';
        var ct = r.headers.get('content-type') || '';
        var len = parseInt(r.headers.get('content-length') || '0', 10) || 0;
        if (hasRange && (r.status !== 206 || !cr)) {
          if (r.body && r.body.cancel) r.body.cancel();
          finish('BAD-RANGE ' + r.status); return;
        }
        if (len > maxBytes) {
          if (r.body && r.body.cancel) r.body.cancel();
          finish('TOO-LARGE'); return;
        }
        window.__avsBridge.onRelayMeta(id, r.status, cr, ct);
        if (r.body && r.body.getReader) {
          var reader = r.body.getReader(), total = 0;
          function pump(){
            reader.read().then(function(part){
              if (finished) { reader.cancel(); return; }
              if (part.done) { finish(null); return; }
              total += part.value.length;
              if (total > maxBytes) { reader.cancel(); finish('TOO-LARGE'); return; }
              send(part.value); pump();
            }).catch(function(e){ finish('' + e); });
          }
          pump();
        } else {
          if (!len || len > maxBytes) { finish('NO-BOUNDED-READER'); return; }
          r.arrayBuffer().then(function(ab){
            var u8 = new Uint8Array(ab);
            if (u8.length > maxBytes) { finish('TOO-LARGE'); return; }
            send(u8); finish(null);
          }).catch(function(e){ finish('' + e); });
        }
      }).catch(function(e){ finish('' + e); });
    } catch (e) { finish('' + e); }
  };
})();
"""

    private fun parseHdrPairs(s: String): Map<String, String> =
        s.split('|').mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i).trim() to p.substring(i + 1).trim()
        }.toMap()

    @Volatile private var lastRecipeSig: String = ""

    private val AVS_HDR_DROP = setOf("accept-encoding", "range", "cookie", "host", "content-length", "connection")
    private fun buildSegRecipe() {
        val obs = segObservedUrl ?: return
        try {
            val obsPath = obs.substringBefore('?')
            val obsQ = obs.substringAfter('?', "")
            val line = lastPlaylistLines?.firstOrNull { it.substringBefore('?') == obsPath }
            val lineNames = (line?.substringAfter('?', "") ?: "")
                .split('&').map { it.substringBefore('=') }.toSet()
            val extra = obsQ.split('&').filter { it.isNotBlank() && it.substringBefore('=') !in lineNames }

            val hdrs = segObservedHeaders.filterKeys { it.lowercase() !in AVS_HDR_DROP }
            val sig = extra.joinToString("&") + "#" + hdrs.keys.joinToString(",") + "#" + obsPath
            if (sig == lastRecipeSig) return
            lastRecipeSig = sig
            segRecipe = SegRecipe(extra.joinToString("&"), hdrs, obs, System.currentTimeMillis())
            nlog("[Hook] SEG-RECIPE pathMatch=${line != null} extra=[${extra.joinToString("&").take(90)}] hdrs=[${hdrs.keys.joinToString(",").take(90)}]")
        } catch (e: Throwable) {
            nlogE("[Hook] recipe", e)
        }
    }

    private fun recipeUrlFor(segUrl: String): String {
        val rec = segRecipe ?: return segUrl
        return try {
            if (rec.sampleUrl.substringBefore('?') == segUrl.substringBefore('?')) {
                rec.sampleUrl
            } else if (rec.extraParams.isNotBlank()) {
                val missp = rec.extraParams.split('&').filter { p ->
                    val n = p.substringBefore('=')
                    n.isNotBlank() && !segUrl.contains(n + "=")
                }
                if (missp.isNotEmpty()) {
                    segUrl + (if (segUrl.contains('?')) "&" else "?") + missp.joinToString("&")
                } else segUrl
            } else segUrl
        } catch (_: Throwable) { segUrl }
    }

    private fun handleRelayMeta(id: String, status: Int, contentRange: String?, contentType: String?) {
        val rb = relayPending[id] ?: return
        rb.status = status
        rb.contentRange = contentRange?.takeIf { it.isNotBlank() }
        rb.contentType = contentType?.takeIf { it.isNotBlank() }
    }

    private fun handleRelayChunk(id: String, b64: String?, last: Int, err: String?) {
        val rb = relayPending[id] ?: return
        if (rb.done) return
        if (!err.isNullOrBlank()) {
            rb.err = err
            rb.done = true
        } else {
            if (!b64.isNullOrEmpty()) {
                try {
                    val chunk = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    if (chunk.size > rb.maxBytes - rb.buf.size()) {
                        rb.err = "too-large"
                        rb.done = true
                    } else rb.buf.write(chunk)
                } catch (_: Throwable) { rb.err = "b64"; rb.done = true }
            }
            if (last == 1) rb.done = true
        }
        if (rb.done) rb.latch.countDown()
    }

    private fun adoptRelayWebView(wv: WebView?, dlg: android.app.Dialog?, fastReal: Boolean, mediaGate: AvsMediaGate) {
        if (wv == null) return
        try {
            relayLastUseMs = System.currentTimeMillis()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    synchronized(relayLock) {
                        val old = relayWv
                        if (old != null && old !== wv) { try { old.destroy() } catch (_: Throwable) {} }
                        val oldDlg = relayDialog
                        if (oldDlg != null && oldDlg !== dlg) { try { oldDlg.dismiss() } catch (_: Throwable) {} }
                        relayWv = wv
                        relayDialog = dlg
                        relayMediaGate = if (fastReal) mediaGate else null
                    }
                    dlg?.window?.let { win ->
                        try {
                            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                            win.setDimAmount(0f)
                            win.setBackgroundDrawableResource(android.R.color.transparent)
                            win.setLayout(1, 1)
                        } catch (_: Throwable) {}
                    }
                    try {
                        val recovered = fastReal && synchronized(activeServers) {
                            activeServers.any { it.playerMediaGate === mediaGate && it.isRecoveryComplete() }
                        }
                        val pauseMedia = if (recovered) {
                            mediaGate.endRecovery()
                            true
                        } else fastReal && mediaGate.pauseUnlessRecovering()
                        val js = if (pauseMedia) AVS_CALM_FAST_JS else if (fastReal) AVS_KICK_JS else AVS_CALM_JS
                        wv.evaluateJavascript(js) { result ->
                            if (fastReal) nlog("[Fast] player nền pause=$pauseMedia ${result?.take(100)}")
                        }
                    } catch (_: Throwable) {}
                    try { wv.evaluateJavascript(avsRelayJs, null) } catch (_: Throwable) {}
                    nlog("[Relay] WebView nhận nuôi — giữ ngữ cảnh player cho segment")
                } catch (_: Throwable) {}
            }
            scheduleRelayIdleCheck()
        } catch (_: Throwable) {}
    }

    private fun scheduleRelayIdleCheck() {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val idle = System.currentTimeMillis() - relayLastUseMs
                    if (idle > 14 * 60_000L) {
                        synchronized(relayLock) {
                            try { relayWv?.destroy() } catch (_: Throwable) {}
                            try { relayDialog?.dismiss() } catch (_: Throwable) {}
                            relayWv = null
                            relayDialog = null
                            relayMediaGate = null
                        }
                        nlog("[Relay] nhàn rỗi >14 phút — giải phóng WebView")
                    } else {
                        scheduleRelayIdleCheck()
                    }
                } catch (_: Throwable) {}
            }, 60_000L)
        } catch (_: Throwable) {}
    }

    private fun relayFetchSegment(
        segUrl: String, extraHeaders: Map<String, String>, timeoutMs: Long, maxBytes: Int,
        expectedGate: AvsMediaGate? = null
    ): AvsRelayResult {
        val snapshot = synchronized(relayLock) {
            val w = relayWv
            if (w != null && (expectedGate == null || relayMediaGate === expectedGate)) w to relayMediaGate else null
        } ?: return AvsRelayResult(null, "no-webview", 0, null, null)
        val wv = snapshot.first
        val mediaGate = snapshot.second
        mediaGate?.beginRelay()
        relayLastUseMs = System.currentTimeMillis()
        val id = "r" + relaySeq.incrementAndGet()
        val rb = RelayBuf(maxBytes)
        relayPending[id] = rb
        try {
            val headersJson = org.json.JSONObject()
            extraHeaders.forEach { (k, v) -> headersJson.put(k, v) }
            val idJson = org.json.JSONObject.quote(id)
            val urlJson = org.json.JSONObject.quote(segUrl)
            val js = "try{window.__avsRelay($idJson,$urlJson,$headersJson,$maxBytes,${timeoutMs - 250});}" +
                "catch(e){try{window.__avsBridge.onRelayChunk($idJson,'',1,''+e);}catch(e2){}}"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val w = synchronized(relayLock) { relayWv }
                    if (w != null && w === wv) w.evaluateJavascript(js, null)
                } catch (_: Throwable) {}
            }
            val done = rb.latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val error = when {
                !done -> "timeout"
                rb.err != null -> rb.err
                rb.buf.size() == 0 -> "empty"
                else -> null
            }
            return AvsRelayResult(if (error == null) rb.buf.toByteArray() else null,
                error, rb.status, rb.contentRange, rb.contentType)
        } finally {
            relayPending.remove(id)
            mediaGate?.endRelay()
        }
    }

    private val activeServers = mutableListOf<AvsProxyServer>()

    private fun resolvePlaylistUri(uri: String, base: String): String? = try {
        val value = uri.trim()
        val resolved = when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            base.startsWith("http://") || base.startsWith("https://") -> java.net.URI(base).resolve(value).toString()
            else -> ""
        }
        resolved.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    } catch (_: Throwable) { null }

    private fun rewriteM3U8(m3u8: String, proxyBase: String, m3u8BaseUrl: String = ""): String {
        return m3u8.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-KEY:", ignoreCase = true) ||
                trimmed.startsWith("#EXT-X-SESSION-KEY:", ignoreCase = true) ||
                trimmed.startsWith("#EXT-X-MAP:", ignoreCase = true)) {
                val m = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(trimmed)
                val abs = m?.groupValues?.get(1)?.let { resolvePlaylistUri(it, m3u8BaseUrl) }
                if (m != null && abs != null) {
                    trimmed.replaceRange(m.range, "URI=\"$proxyBase/seg/${URLEncoder.encode(abs, "UTF-8")}\"")
                } else line
            } else if (trimmed.startsWith("#") || trimmed.isBlank()) {
                line
            } else {
                val abs = resolvePlaylistUri(trimmed, m3u8BaseUrl)
                if (abs != null) "$proxyBase/seg/${URLEncoder.encode(abs, "UTF-8")}" else line
            }
        }
    }

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

    private suspend fun registerM3U8Link(
        m3u8Content: String, m3u8Url: String, m3u8BaseUrl: String,
        serverName: String, callback: (ExtractorLink) -> Unit,
        siteReferer: String = "", mediaGate: AvsMediaGate? = null
    ): Boolean {
        if (!m3u8Content.contains("#EXTM3U")) return false

        if (m3u8Content.contains("_t=")) {
            nlog("[Register] BỎ raw playlist (_t= — chưa giải mã, CDN 403 Missing segment token) url=${m3u8Url.take(70)}")
            return false
        }
        val ref = siteReferer.ifBlank { m3u8Url }
        val fastReal = isFastRealPlaylist(m3u8Url, m3u8Content)
        var emitted = false

        val segUrls = m3u8Content.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolvePlaylistUri(it, m3u8BaseUrl) ?: it }
        nlog("[Register] '$serverName' segments=${segUrls.size} first=${segUrls.firstOrNull()?.take(100)} referer=${ref.take(60)}")

        lastPlaylistLines = segUrls

        val trim = preflightTrimCount(segUrls, ref)
        if (trim > 0) nlog("[Register] trim $trim segment đầu (probe chặn)")

        val directOk = m3u8Url.startsWith("http://") || m3u8Url.startsWith("https://")
        if (directOk) {
            val ckDirect = try { android.webkit.CookieManager.getInstance().getCookie(m3u8Url) } catch (_: Throwable) { null }
            val ckMap = if (ckDirect.isNullOrBlank()) emptyMap() else mapOf("Cookie" to ckDirect)
            try {
                callback(newExtractorLink(name, serverName, m3u8Url, ExtractorLinkType.M3U8) {
                    quality = Qualities.P1080.value
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to ref) + ckMap + (segRecipe?.headers ?: emptyMap())
                })
                emitted = true
                nlog("[Register] direct '$serverName' ${m3u8Url.take(80)}")
            } catch (e: Throwable) {
                nlogE("[Register] direct", e)
            }
        } else {
            nlog("[Register] bỏ link direct (manifest nội bộ '${m3u8Url.take(40)}') — chỉ phát link proxy (v26 P0, hết 2001)")
        }

        var proxyBase: String? = null
        synchronized(activeServers) {
            for (old in activeServers) old.cancelUnclaimedWarm()
            while (activeServers.size >= 8) {
                try { activeServers.removeAt(0).stop() } catch (_: Throwable) {}
            }
            try {
                val server = AvsProxyServer(
                    if (siteReferer.isNotBlank()) listOf(siteReferer, m3u8Url) else listOf(m3u8Url),
                    fastReal
                )
                server.label = serverName
                server.playerMediaGate = if (fastReal) mediaGate else null
                server.start()
                activeServers.add(server)
                proxyBase = "http://127.0.0.1:${server.port}"
                server.setM3U8(rewriteM3U8(trimLeadingSegments(m3u8Content, trim), "http://127.0.0.1:${server.port}", m3u8BaseUrl))
            } catch (e: Throwable) {
                proxyBase = null
                nlogE("[Register] bind", e, "không mở được port cục bộ — chỉ dùng link direct")
            }
        }
        val proxyEmitBase = proxyBase ?: return emitted
        try {
            callback(newExtractorLink(name, if (directOk) "$serverName • dự phòng" else "$serverName", "$proxyEmitBase/stream.m3u8", ExtractorLinkType.M3U8) {
                quality = Qualities.P1080.value
                headers = mapOf("User-Agent" to USER_AGENT)
            })
            emitted = true
            nlog("[Register] emitted $proxyEmitBase/stream.m3u8 segs=${segUrls.size} fastReal=$fastReal")
        } catch (e: Throwable) {
            nlogE("[Register] proxy-emit", e)
        }
        return emitted
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

    private object AvsDnsCache : okhttp3.Dns {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<java.net.InetAddress>>>()
        private val ttlMs = 10 * 60 * 1000L
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val now = System.currentTimeMillis()
            cache[hostname]?.let { (ts, ips) -> if (now - ts < ttlMs && ips.isNotEmpty()) return ips }
            return try {
                val ips = okhttp3.Dns.SYSTEM.lookup(hostname)
                if (ips.isNotEmpty()) cache[hostname] = now to ips
                ips
            } catch (e: Throwable) {
                val stale = cache[hostname]?.second
                if (!stale.isNullOrEmpty()) {
                    android.util.Log.w("AnimeVsub", "[Seg] DNS SYSTEM fail (${e.message?.take(40)}) — dùng cache cũ $hostname")
                    stale
                } else throw e
            }
        }
    }
    private val segClient: okhttp3.OkHttpClient by lazy {
        try {
            app.baseClient.newBuilder().dns(AvsDnsCache).build()
        } catch (_: Throwable) {
            app.baseClient
        }
    }

    private class AvsPrefetchTask {
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var call: okhttp3.Call? = null
        @Volatile var cancelled = false
    }

    private data class AvsContentRange(val start: Long, val end: Long, val total: Long?)

    private class AvsWarmConnection {
        val ready = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        @Volatile var call: okhttp3.Call? = null
        @Volatile var cancelled = false
        val slotFreed = java.util.concurrent.atomic.AtomicBoolean(false)
        var claimed = false
        var response: okhttp3.Response? = null
    }

    private class AvsResponseOutput(private val target: java.io.OutputStream) : java.io.OutputStream() {
        @Volatile var started = false
            private set
        override fun write(b: Int) { started = true; target.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > 0) started = true
            target.write(b, off, len)
        }
        override fun flush() = target.flush()
    }

    private inner class AvsProxyServer(private val referers: List<String>, private val fastReal: Boolean) {
        private fun primaryRef(): String = referers.firstOrNull() ?: ""

        @Volatile var label: String = ""
        @Volatile var playerMediaGate: AvsMediaGate? = null
        @Volatile var port: Int = -1
            private set

        private var serverSocket: java.net.ServerSocket? = null
        private val threadPool = java.util.concurrent.Executors.newCachedThreadPool()
        @Volatile private var _m3u8: String = ""
        @Volatile private var recipeNeeded = true
        @Volatile private var servedBig = false
        private val bornAt = System.currentTimeMillis()
        private val segReqLog = java.util.concurrent.atomic.AtomicInteger(0)
        private val okLogCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val prefetchLogCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val backoffSkips = java.util.concurrent.atomic.AtomicInteger(0)

        private val segOrder = java.util.Collections.synchronizedList(ArrayList<Pair<String, String?>>())
        private val segmentDurations = java.util.concurrent.ConcurrentHashMap<String, Double>()
        private val recentSegmentRequests = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val latestSegmentIndex = java.util.concurrent.atomic.AtomicInteger(-1)
        private val keyUrls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        private val segCache = LinkedHashMap<String, Triple<Int, String?, ByteArray>>(16, 0.75f, true)
        private val segCacheLock = Any()
        private var segCacheBytes = 0L
        private val maxCacheBytes = 16L * 1024 * 1024
        private val maxPrefetchBytes = 8 * 1024 * 1024
        private val segPrefetchInflight = java.util.concurrent.ConcurrentHashMap<String, AvsPrefetchTask>()
        private val prefetchSlots = java.util.concurrent.Semaphore(3)
        private val warmConnections = java.util.concurrent.ConcurrentHashMap<String, AvsWarmConnection>()
        private val warmSlots = java.util.concurrent.Semaphore(1)
        @Volatile private var startupRecoveryRequired = false
        @Volatile private var startupRecoveryDone = false
        @Volatile private var recoverySegmentOk = false
        private val failedWarmKeys = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        private val recoveryCalmed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val startupRecoveryLock = Any()
        private val prefetchClient: okhttp3.OkHttpClient by lazy {
            segClient.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        }

        fun getM3U8(): String = _m3u8

        fun isRecoveryComplete(): Boolean = recoverySegmentOk

        private fun decodeProxyUrl(value: String): String {
            if (!value.contains("/seg/")) return value
            val enc = value.substringAfter("/seg/", "")
            return try { java.net.URLDecoder.decode(enc, "UTF-8") } catch (_: Throwable) { enc }
        }

        fun setM3U8(v: String) {
            _m3u8 = v
            try {
                val entries = ArrayList<Pair<String, String?>>()
                keyUrls.clear()
                segmentDurations.clear()
                var firstKey: String? = null
                var pendingRange: String? = null
                var pendingDuration: Double? = null
                fun addSegment(url: String, range: String?) {
                    entries.add(url to range)
                    pendingDuration?.let { segmentDurations[cacheKey(url, range)] = it }
                }
                var previousRangeUrl: String? = null
                var previousRangeEnd: Long? = null
                for (raw in v.lines()) {
                    val t = raw.trim()
                    if (t.startsWith("#EXT-X-KEY:", ignoreCase = true)) {
                        val method = Regex("""(?i)(?:^|,)METHOD\s*=\s*([^,]+)""")
                            .find(t.substringAfter(':'))?.groupValues?.get(1)?.trim()
                        val uri = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                            .find(t)?.groupValues?.get(1)
                        if (method.equals("AES-128", ignoreCase = true) && uri != null) {
                            val keyUrl = decodeProxyUrl(uri)
                            if (keyUrl.startsWith("http://") || keyUrl.startsWith("https://")) {
                                keyUrls.add(keyUrl)
                                if (firstKey == null) firstKey = keyUrl
                            }
                        }
                    } else if (t.startsWith("#EXTINF:", ignoreCase = true)) {
                        pendingDuration = t.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it > 0.0 && it <= 600.0 }
                    } else if (t.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true)) {
                        pendingRange = t.substringAfter(':').trim()
                    } else if (t.startsWith("#") || t.isEmpty()) {

                    } else {
                        val u = decodeProxyUrl(t)
                        if (u.startsWith("http://") || u.startsWith("https://")) {
                            val spec = pendingRange
                            if (spec == null) {
                                addSegment(u, null)
                                previousRangeUrl = null
                                previousRangeEnd = null
                            } else {
                                val len = spec.substringBefore('@').toLongOrNull()
                                val explicit = if (spec.contains('@')) spec.substringAfter('@').toLongOrNull() else null
                                val start = if (spec.contains('@')) explicit else
                                    if (u == previousRangeUrl && previousRangeEnd != null && previousRangeEnd < Long.MAX_VALUE)
                                        previousRangeEnd + 1 else null
                                if (len != null && len > 0 && start != null && start >= 0 && len - 1 <= Long.MAX_VALUE - start) {
                                    val end = start + len - 1
                                    addSegment(u, "bytes=$start-$end")
                                    previousRangeUrl = u
                                    previousRangeEnd = end
                                } else {

                                    previousRangeUrl = null
                                    previousRangeEnd = null
                                    nlog("[Prefetch] bỏ BYTERANGE không hợp lệ (port=$port)")
                                }
                            }
                        }
                        pendingRange = null
                        pendingDuration = null
                    }
                }
                synchronized(segOrder) { segOrder.clear(); segOrder.addAll(entries) }
                recipeNeeded = !fastReal || entries.isEmpty() || entries.any { it.first.contains("/chunks/") }
                nlog("[Proxy] prefetch-map ${entries.size} segment (port=$port) recipeNeeded=$recipeNeeded fastReal=$fastReal")
                if (!recipeNeeded && entries.isNotEmpty()) {

                    firstKey?.let { schedulePrefetch(it, null, true, "warm-key") }
                    val first = entries.first()
                    openWarmConnection(first.first, first.second, true)
                }
            } catch (e: Throwable) {
                nlogE("[Proxy] prefetch-map", e)
            }
        }

        private fun orderIndexOf(segUrl: String, reqRange: String?): Int =
            synchronized(segOrder) {
                segOrder.indexOfFirst { it.first == segUrl && (it.second ?: "") == (reqRange ?: "") }
            }

        private fun cacheKey(url: String, range: String?): String = url + "|" + (range ?: "")

        private fun cacheGet(key: String): Triple<Int, String?, ByteArray>? =
            synchronized(segCacheLock) { segCache[key] }

        private fun cachePut(key: String, value: Triple<Int, String?, ByteArray>) {
            if (value.third.isEmpty() || value.third.size > maxPrefetchBytes) return
            synchronized(segCacheLock) {
                segCache.remove(key)?.let { segCacheBytes -= it.third.size.toLong() }
                segCache[key] = value
                segCacheBytes += value.third.size.toLong()
                while (segCache.size > 6 || segCacheBytes > maxCacheBytes) {
                    val eldest = segCache.keys.firstOrNull() ?: break
                    segCache.remove(eldest)?.let { segCacheBytes -= it.third.size.toLong() }
                }
            }
        }

        private fun rangeResponseValid(range: String?, code: Int, contentRange: String?, contentLength: Long): Boolean {
            if (range == null) return true
            if (code != 206 || contentRange == null) return false
            val req = Regex("""(?i)^bytes\s*=\s*(\d*)-(\d*)\s*$""").matchEntire(range.trim()) ?: return false
            val res = Regex("""(?i)^bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""").matchEntire(contentRange.trim()) ?: return false
            val start = res.groupValues[1].toLongOrNull() ?: return false
            val end = res.groupValues[2].toLongOrNull() ?: return false
            val total = res.groupValues[3].toLongOrNull()
            if (end < start || (total != null && (total <= 0 || end >= total))) return false
            val actualLen = end - start + 1
            if (actualLen <= 0 || (contentLength >= 0 && contentLength != actualLen)) return false
            val reqStart = req.groupValues[1].toLongOrNull()
            val reqEnd = req.groupValues[2].toLongOrNull()
            if (reqStart == null) {

                if (reqEnd == null || reqEnd <= 0 || total == null) return false
                return start == (total - reqEnd).coerceAtLeast(0L) && end == total - 1
            }
            if (start != reqStart || (reqEnd != null && reqEnd < reqStart)) return false
            if (reqEnd != null && end != reqEnd) {

                if (total == null || reqEnd < total || end != total - 1) return false
            }
            return true
        }

        private fun looksLikeErrorBytes(b: ByteArray): Boolean {
            if (b.isEmpty()) return true
            val head = try { String(b, 0, minOf(b.size, 256), Charsets.UTF_8).trimStart().lowercase() }
                       catch (_: Throwable) { return false }
            return head.startsWith("<!doctype html") || head.startsWith("<html") ||
                (head.startsWith("{") && (head.contains("\"error\"") || head.contains("\"message\"")))
        }

        private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
            val buf = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (n > limit - total) return null
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }

        private fun schedulePrefetch(u: String, rg: String?, isKey: Boolean, reason: String) {
            val key = cacheKey(u, rg)
            if (cacheGet(key) != null || !prefetchSlots.tryAcquire()) return
            val task = AvsPrefetchTask()
            if (segPrefetchInflight.putIfAbsent(key, task) != null) {
                prefetchSlots.release()
                return
            }
            try {
                threadPool.execute {
                    val startMs = System.currentTimeMillis()
                    try {
                        if (task.cancelled || System.currentTimeMillis() < segAuthBackoffGlobalUntil ||
                            System.currentTimeMillis() < dnsCooldownUntil) return@execute
                        val limit = if (isKey) 16 * 1024 else maxPrefetchBytes

                        if (rg != null) {
                            val m = Regex("""(?i)^bytes=(\d+)-(\d+)$""").matchEntire(rg)
                            val lo = m?.groupValues?.get(1)?.toLongOrNull()
                            val hi = m?.groupValues?.get(2)?.toLongOrNull()
                            if (lo == null || hi == null || hi < lo || hi - lo + 1 > limit) return@execute
                        }
                        val rb = okhttp3.Request.Builder().url(u)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", primaryRef())
                            .header("Accept", "*/*")
                            .header("Sec-Fetch-Site", "same-origin")
                            .header("Sec-Fetch-Mode", "cors")
                            .header("Sec-Fetch-Dest", "empty")
                        rg?.let { rb.header("Range", it) }
                        cookieHeaderFor(u)?.let { rb.header("Cookie", it) }
                        val call = prefetchClient.newCall(rb.build())
                        task.call = call
                        if (task.cancelled) { call.cancel(); return@execute }
                        call.execute().use { resp ->
                            val ct = resp.header("Content-Type")
                            val cr = resp.header("Content-Range")
                            val body = resp.body ?: return@use
                            val length = body.contentLength()
                            if (isBlockedResponse(resp.code, ct) ||
                                !rangeResponseValid(rg, resp.code, cr, length) || length > limit) {

                                if (reason.startsWith("warm-") && isBlockedResponse(resp.code, ct)) {
                                    failedWarmKeys.add(key)
                                    startupRecoveryRequired = true
                                }
                                if (prefetchLogCount.incrementAndGet() <= 12)
                                    nlog("[Prefetch] $reason bỏ response code=${resp.code} range=${rg ?: "-"} ms=${System.currentTimeMillis() - startMs}")
                                return@use
                            }
                            val bytes = readBounded(body.byteStream(), limit) ?: return@use
                            if (task.cancelled || bytes.isEmpty() || looksLikeErrorBytes(bytes) ||
                                (isKey && bytes.size != 16) ||
                                !rangeResponseValid(rg, resp.code, cr, bytes.size.toLong())) {
                                if (reason.startsWith("warm-") && (bytes.isEmpty() || looksLikeErrorBytes(bytes))) {
                                    failedWarmKeys.add(key)
                                    startupRecoveryRequired = true
                                }
                                return@use
                            }
                            cachePut(key, Triple(resp.code, cr, bytes))
                            if (prefetchLogCount.incrementAndGet() <= 12)
                                nlog("[Prefetch] $reason OK bytes=${bytes.size} range=${rg ?: "-"} totalMs=${System.currentTimeMillis() - startMs}")
                        }
                    } catch (e: Throwable) {
                        if (reason.startsWith("warm-") && !task.cancelled) {
                            failedWarmKeys.add(key)
                            startupRecoveryRequired = true
                        }
                        if (prefetchLogCount.incrementAndGet() <= 12)
                            nlog("[Prefetch] $reason lỗi ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                    } finally {
                        task.done.countDown()
                        segPrefetchInflight.remove(key, task)
                        prefetchSlots.release()
                    }
                }
            } catch (e: Throwable) {
                segPrefetchInflight.remove(key, task)
                task.done.countDown()
                prefetchSlots.release()
                nlogE("[Prefetch] scheduling", e)
            }
        }

        private fun openWarmConnection(u: String, rg: String?, startup: Boolean): AvsWarmConnection? {
            val key = cacheKey(u, rg)
            if (cacheGet(key) != null || !warmSlots.tryAcquire()) return null
            val task = AvsWarmConnection()
            if (warmConnections.putIfAbsent(key, task) != null) {
                warmSlots.release()
                return null
            }
            try {
                threadPool.execute {
                    var published = false
                    val startMs = System.currentTimeMillis()
                    try {
                        try {
                            if (task.cancelled || System.currentTimeMillis() < segAuthBackoffGlobalUntil ||
                                System.currentTimeMillis() < dnsCooldownUntil) return@execute
                            val rb = okhttp3.Request.Builder().url(u)
                                .header("User-Agent", USER_AGENT)
                                .header("Referer", primaryRef())
                                .header("Accept", "*/*")
                                .header("Sec-Fetch-Site", "same-origin")
                                .header("Sec-Fetch-Mode", "cors")
                                .header("Sec-Fetch-Dest", "empty")
                            rg?.let { rb.header("Range", it) }
                            cookieHeaderFor(u)?.let { rb.header("Cookie", it) }
                            val call = segClient.newCall(rb.build())
                            task.call = call
                            if (task.cancelled) { call.cancel(); return@execute }
                            val resp = call.execute()
                            val ct = resp.header("Content-Type")
                            val len = resp.body?.contentLength() ?: -1L
                            val valid = !isBlockedResponse(resp.code, ct) && len != 0L &&
                                rangeResponseValid(rg, resp.code, resp.header("Content-Range"), len)
                            if (!valid) {
                                if (startup && !task.cancelled && isBlockedResponse(resp.code, ct)) {
                                    failedWarmKeys.add(key)
                                    startupRecoveryRequired = true
                                }
                                if (prefetchLogCount.incrementAndGet() <= 20)
                                    nlog("[Warm] không dùng status=${resp.code} range=${rg ?: "-"} headerMs=${System.currentTimeMillis() - startMs}")
                                resp.close()
                            } else {
                                synchronized(task) {
                                    if (!task.cancelled) {
                                        task.response = resp
                                        published = true
                                    }
                                }
                                if (!published) resp.close()
                                else if (prefetchLogCount.incrementAndGet() <= 20)
                                    nlog("[Warm] headers sẵn status=${resp.code} bytes=$len headerMs=${System.currentTimeMillis() - startMs}")
                            }
                        } catch (e: Throwable) {
                            if (!task.cancelled) {
                                if (startup) {
                                    failedWarmKeys.add(key)
                                    startupRecoveryRequired = true
                                }
                                if (prefetchLogCount.incrementAndGet() <= 20)
                                    nlog("[Warm] lỗi ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                            }
                        } finally {
                            task.ready.countDown()
                        }
                        if (published) {
                            try { task.released.await(8, java.util.concurrent.TimeUnit.SECONDS) }
                            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        }
                    } finally {
                        task.ready.countDown()
                        synchronized(task) {
                            try { task.response?.close() } catch (_: Throwable) {}
                            task.response = null
                        }
                        warmConnections.remove(key, task)
                        releaseWarmSlot(task)
                    }
                }
            } catch (e: Throwable) {
                warmConnections.remove(key, task)
                task.ready.countDown()
                task.released.countDown()
                releaseWarmSlot(task)
                nlogE("[Warm] scheduling", e)
                return null
            }
            return task
        }

        private fun releaseWarmSlot(task: AvsWarmConnection) {
            if (task.slotFreed.compareAndSet(false, true)) warmSlots.release()
        }

        private fun cancelWarmConnection(task: AvsWarmConnection, force: Boolean = false) {
            val shouldCancel = synchronized(task) {
                if (task.claimed && !force) false else {
                    task.cancelled = true
                    try { task.response?.close() } catch (_: Throwable) {}
                    task.response = null
                    true
                }
            }
            if (shouldCancel) {
                try { task.call?.cancel() } catch (_: Throwable) {}
                task.ready.countDown()
                task.released.countDown()
            }
        }

        fun cancelUnclaimedWarm() {
            for (task in warmConnections.values) cancelWarmConnection(task)
        }

        private fun takeWarmConnection(key: String): okhttp3.Response? {
            val task = warmConnections[key] ?: run {
                cancelUnclaimedWarm()
                return null
            }
            val start = System.currentTimeMillis()
            val ready = try { task.ready.await(4_000L, java.util.concurrent.TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            if (!ready) {
                cancelWarmConnection(task)
                nlog("[Warm] chờ header quá 4000ms — dùng đường thường")
                return null
            }
            val response = synchronized(task) {
                if (task.cancelled) null else task.response?.also {
                    task.response = null
                    task.claimed = true
                }
            }
            if (response != null) {
                warmConnections.remove(key, task)
                releaseWarmSlot(task)
            }
            task.released.countDown()
            if (response != null && prefetchLogCount.incrementAndGet() <= 20)
                nlog("[Warm] chuyển stream trực tiếp waitMs=${System.currentTimeMillis() - start}")
            return response
        }

        private fun rangeLength(range: String?): Long? {
            if (range == null) return null
            val m = Regex("""(?i)^bytes\s*=\s*(\d+)-(\d+)\s*$""").matchEntire(range.trim()) ?: return null
            val start = m.groupValues[1].toLongOrNull() ?: return null
            val end = m.groupValues[2].toLongOrNull() ?: return null
            if (end < start || end - start == Long.MAX_VALUE) return null
            return end - start + 1
        }

        private fun maybePrefetchSegment(fromIdx: Int, early: Boolean = false): AvsWarmConnection? {
            if (!fastReal || fromIdx < 0 || latestSegmentIndex.get() != fromIdx) return null
            val entry = synchronized(segOrder) { segOrder.getOrNull(fromIdx + 1) } ?: return null
            val key = cacheKey(entry.first, entry.second)
            val lastRequest = recentSegmentRequests[key] ?: 0L
            if (System.currentTimeMillis() - lastRequest < 3_000L) return null
            val size = rangeLength(entry.second)
            if (early && (size == null || size !in 1L..3_145_728L)) return null
            val task = openWarmConnection(entry.first, entry.second, false) ?: return null
            if (latestSegmentIndex.get() != fromIdx) {
                cancelWarmConnection(task)
                return null
            }
            if (early) nlog("[Ahead] mở sớm segment kế bytes=$size")
            return task
        }

        private fun awaitPrefetch(key: String): Triple<Int, String?, ByteArray>? {
            cacheGet(key)?.let { return it }
            val task = segPrefetchInflight[key]
            if (task != null) {
                val start = System.currentTimeMillis()
                val finished = try { task.done.await(10_500L, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
                if (!finished) {
                    task.cancelled = true
                    task.call?.cancel()
                    try { task.done.await(250L, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }
                if (prefetchLogCount.incrementAndGet() <= 12)
                    nlog("[Prefetch] join finished=$finished waitMs=${System.currentTimeMillis() - start}")
            }

            return cacheGet(key)
        }

        private fun recoverFastStartupIfNeeded() {
            if (!fastReal || !startupRecoveryRequired || startupRecoveryDone) return
            synchronized(startupRecoveryLock) {
                if (startupRecoveryDone || !startupRecoveryRequired) return
                val delayMs = (bornAt + 8_000L - System.currentTimeMillis()).coerceIn(0L, 8_000L)
                nlog("[Fast] warm lỗi — khôi phục nhịp player kiểu v30, chờ ${delayMs}ms trước retry")
                val gate = playerMediaGate
                gate?.beginRecovery()
                val wv = synchronized(relayLock) {
                    if (gate == null || relayMediaGate === gate) relayWv else null
                }
                if (wv != null) {
                    try { android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try { wv.evaluateJavascript(AVS_KICK_JS, null) } catch (_: Throwable) {}
                    } } catch (_: Throwable) {}
                }
                if (delayMs > 0) try { Thread.sleep(delayMs) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                startupRecoveryDone = true
            }
        }

        private fun calmRecoveredPlayer() {
            if (!startupRecoveryDone || !servedBig) return
            val gate = playerMediaGate
            val wv = synchronized(relayLock) {
                if (gate == null || relayMediaGate === gate) relayWv else null
            } ?: return
            if (!recoveryCalmed.compareAndSet(false, true)) return
            gate?.endRecovery()
            try { android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { wv.evaluateJavascript(AVS_CALM_FAST_JS, null) } catch (_: Throwable) {}
            } } catch (_: Throwable) {}
        }

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
            nlog("[Proxy] started port=$port label=$label refs=${referers.size} referer=${primaryRef().take(50)}")
        }

        fun stop() {
            try { serverSocket?.close() } catch (_: Throwable) {}
            for (task in segPrefetchInflight.values) {
                task.cancelled = true
                try { task.call?.cancel() } catch (_: Throwable) {}
            }
            for (task in warmConnections.values) cancelWarmConnection(task, true)
            synchronized(segCacheLock) { segCache.clear(); segCacheBytes = 0L }
            try { threadPool.shutdownNow() } catch (_: Throwable) {}
            nlog("[Proxy] stopped port=$port")
        }

        private fun handleClient(client: java.net.Socket) {
            try {
                client.soTimeout = 15_000
                val input  = client.getInputStream().bufferedReader()
                val output = AvsResponseOutput(client.getOutputStream())
                val requestLine = input.readLine() ?: return
                var reqRange: String? = null
                while (true) {
                    val h = input.readLine() ?: ""
                    if (h.isBlank()) break

                    if (h.length > 6 && h.substring(0, 6).equals("Range:", ignoreCase = true)) {
                        reqRange = h.substringAfter(':').trim()
                    }
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
                        if (segReqLog.incrementAndGet() <= 8) {
                            nlog("[Seg] REQ sinceReg=${t0 - bornAt}ms range=${reqRange ?: "-"} servedBig=$servedBig url=...${segUrl.takeLast(26)}")
                        }

                        val key = cacheKey(segUrl, reqRange)
                        val isKey = keyUrls.contains(segUrl)
                        if (!isKey) {
                            val now = System.currentTimeMillis()
                            latestSegmentIndex.set(orderIndexOf(segUrl, reqRange))
                            recentSegmentRequests[key] = now
                            if (recentSegmentRequests.size > 128) {
                                for ((k, t) in recentSegmentRequests) {
                                    if (now - t > 30_000L) recentSegmentRequests.remove(k, t)
                                }
                            }
                        }
                        var cacheHit = if (isKey) awaitPrefetch(key) else cacheGet(key)
                        val warmResponse = if (!isKey && cacheHit == null) takeWarmConnection(key) else null
                        try {
                            if (cacheHit == null && warmResponse == null && fastReal && failedWarmKeys.contains(key)) {
                                recoverFastStartupIfNeeded()
                                if (isKey) {
                                    schedulePrefetch(segUrl, reqRange, true, "recovery")
                                    cacheHit = awaitPrefetch(key)
                                }
                                failedWarmKeys.remove(key)
                            }
                            if (cacheHit != null && cacheHit.third.isNotEmpty()) {
                                if (cacheHit.third.size >= 4096) servedBig = true
                                val head = StringBuilder(
                                    if (cacheHit.first == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
                                ).append(crlf)
                                    .append("Content-Type: ").append(if (keyUrls.contains(segUrl)) "application/octet-stream" else "video/mp2t").append(crlf)
                                    .append("Access-Control-Allow-Origin: *").append(crlf)
                                cacheHit.second?.let { head.append("Content-Range: ").append(it).append(crlf) }
                                head.append("Content-Length: ").append(cacheHit.third.size).append(crlf).append(crlf)
                                output.write(head.toString().toByteArray())
                                output.write(cacheHit.third)
                                if (okLogCount.incrementAndGet() <= 40) {
                                    nlog("[Seg] CACHE-HIT bytes=${cacheHit.third.size} url=...${segUrl.takeLast(26)}")
                                }
                                ok = true
                            }

                            val nowMs = System.currentTimeMillis()
                            if (ok) {

                            } else if (warmResponse == null && (nowMs < segAuthBackoffGlobalUntil || nowMs < dnsCooldownUntil)) {

                                val skipN = backoffSkips.incrementAndGet()
                                if (skipN <= 2 || skipN % 20 == 0) {
                                    val waitS = ((maxOf(segAuthBackoffGlobalUntil, dnsCooldownUntil) - nowMs) / 1000L).coerceAtLeast(1)
                                    nlog("[Seg] backoff toàn cục ${waitS}s — 502 tạm url=...${segUrl.takeLast(26)}")
                                }
                            } else {

                                if (segRecipe == null && recipeNeeded && !servedBig) waitForRecipe()

                                if (!ok && warmResponse != null) ok = viaWarmResponse(warmResponse, segUrl, reqRange, output, crlf, t0)
                                if (!ok && !output.started && recipeNeeded) ok = viaRecipe(segUrl, reqRange, output, crlf, t0)
                                if (!ok && !output.started) ok = viaOkHttp(segUrl, reqRange, output, crlf, t0)
                                if (!ok && !output.started) ok = viaRelay(segUrl, reqRange, output, crlf, t0)
                                if (!ok && !output.started) {
                                    val origin = Regex("""^(https?://[^/]+)""").find(primaryRef())?.groupValues?.get(1) ?: ""
                                    if (origin.isNotEmpty()) ok = viaOkHttpOrigin(segUrl, reqRange, "$origin/", output, crlf, t0)
                                }
                                if (!ok && !output.started) ok = viaUrlConnection(segUrl, reqRange, output, crlf, t0)
                            }

                            if (ok) {

                                allMethodFails.set(0)
                                backoffSkips.set(0)
                                segStormToastShown = false
                                if (startupRecoveryDone && servedBig) recoverySegmentOk = true
                                calmRecoveredPlayer()

                                try {
                                    val idx = orderIndexOf(segUrl, reqRange)
                                    if (idx >= 0 && latestSegmentIndex.get() == idx) maybePrefetchSegment(idx)
                                } catch (_: Throwable) {}
                            } else if (output.started) {

                                nlog("[Seg] stream bị ngắt sau header — đóng socket để player retry")
                            } else {
                                val body = "502 upstream blocked".toByteArray(Charsets.UTF_8)
                                output.write(("HTTP/1.1 502 Bad Gateway${crlf}Content-Length: ${body.size}${crlf}$crlf").toByteArray())
                                output.write(body)
                                val n = allMethodFails.incrementAndGet()
                                if (n <= 3 || n % 10 == 0) nlog("[Seg] ALL-METHODS-FAILED #$n url=...${segUrl.takeLast(40)}")

                                if (n >= 5 && System.currentTimeMillis() > segAuthBackoffGlobalUntil) {
                                    segAuthBackoffGlobalUntil = System.currentTimeMillis() + 30_000L
                                    if (!segStormToastShown) {
                                        segStormToastShown = true
                                        ntoast("AnimeVsub: CDN chặn segment — nghỉ 30s rồi thử lại")
                                    }
                                }
                            }
                        } finally {
                            try { warmResponse?.close() } catch (_: Throwable) {}
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

        private fun isBlockedResponse(code: Int, ct: String?): Boolean {
            val contentType = (ct ?: "").lowercase()
            return code !in 200..299 || contentType.contains("text/html") ||
                contentType.contains("application/json") || contentType.contains("application/problem+json")
        }

        private fun cookieHeaderFor(url: String): String? = try {
            android.webkit.CookieManager.getInstance().getCookie(url)
        } catch (_: Throwable) { null }

        private val blockedDetailLog = java.util.concurrent.atomic.AtomicInteger(0)
        private val fullUrlLogged = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun noteBlocked(): Boolean = blockedDetailLog.incrementAndGet() <= 3

        private fun logFullUrlOnce(segUrl: String) {
            if (fullUrlLogged.compareAndSet(false, true)) nlog("[Seg] URL-ĐẦY-ĐỦ $segUrl")
        }

        private fun noteAuthFail(ct: String?) {
            val html = (ct ?: "").contains("text/html", ignoreCase = true)
            segAuthBackoffGlobalUntil = System.currentTimeMillis() + if (html) 30_000L else 8_000L
        }

        private fun noteDnsFail(e: Throwable?) {
            if (e !is java.net.UnknownHostException) return
            dnsCooldownUntil = System.currentTimeMillis() + 20_000L
            val now = System.currentTimeMillis()
            if (now - lastDnsLogMs > 30_000L) {
                lastDnsLogMs = now
                nlog("[Seg] DNS NXDOMAIN tạm thời — nghỉ 20s (chống bão), khi nào mạng ổn tự chạy lại")
            }
        }

        private fun waitForRecipe(maxMs: Long = 5_000L) {
            val t0 = System.currentTimeMillis()
            var kicked = false
            while (segRecipe == null && System.currentTimeMillis() - t0 < maxMs) {
                if (!kicked && System.currentTimeMillis() - t0 > 2_500L) {
                    kicked = true
                    try {
                        val wv = synchronized(relayLock) { relayWv }
                        if (wv != null && System.currentTimeMillis() - lastKickAtMs > 2_500L) {
                            lastKickAtMs = System.currentTimeMillis()
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try { wv.evaluateJavascript(AVS_KICK_JS, null) } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
                }
                try { Thread.sleep(250) } catch (_: Throwable) { break }
            }
            if (segRecipe != null && System.currentTimeMillis() - segRecipeUpdateAt < 60_000L) {
                nlog("[Seg] recipe sẵn sàng sau ${System.currentTimeMillis() - t0}ms chờ playerSegOk=$playerSegOkSeen")
            }
        }

        private fun callWithDnsRetry(req: okhttp3.Request): okhttp3.Response {
            return try {
                segClient.newCall(req).execute()
            } catch (e: java.net.UnknownHostException) {
                noteDnsFail(e)
                nlog("[Seg] DNS fail (${e.message?.take(50)}) — retry sau 1.2s")
                Thread.sleep(1_200)
                segClient.newCall(req).execute()
            }
        }

        private fun parseContentRange(value: String?): AvsContentRange? {
            val m = value?.trim()?.let {
                Regex("""(?i)^bytes\s+(\d+)-(\d+)/(\d+|\*)$""").matchEntire(it)
            } ?: return null
            val start = m.groupValues[1].toLongOrNull() ?: return null
            val end = m.groupValues[2].toLongOrNull() ?: return null
            val total = m.groupValues[3].toLongOrNull()
            if (end < start || (total != null && (total <= 0 || end >= total))) return null
            if (end - start == Long.MAX_VALUE) return null
            return AvsContentRange(start, end, total)
        }

        private fun lastBytes(previous: ByteArray, bytes: ByteArray, length: Int): ByteArray {
            if (length >= 64) return bytes.copyOfRange(length - 64, length)
            val keep = minOf(previous.size, 64 - length)
            val result = ByteArray(keep + length)
            if (keep > 0) System.arraycopy(previous, previous.size - keep, result, 0, keep)
            if (length > 0) System.arraycopy(bytes, 0, result, keep, length)
            return result
        }

        private fun resumeRange(
            original: okhttp3.Response, range: AvsContentRange, sent: Long, tail: ByteArray
        ): Pair<okhttp3.Response, java.io.InputStream>? {
            if (sent <= 0 || sent > range.end - range.start || tail.size > sent) return null
            val first = range.start + sent - tail.size
            val wanted = "bytes=$first-${range.end}"
            val etag = original.header("ETag")?.takeIf { it.isNotBlank() && !it.startsWith("W/", true) }
            val modified = original.header("Last-Modified")?.takeIf { it.isNotBlank() }
            if (etag == null && modified == null) {
                nlog("[Seg] RESUME thiếu validator — để player retry")
                return null
            }
            val builder = original.request.newBuilder().header("Range", wanted)
            (etag ?: modified)?.let { builder.header("If-Range", it) }
            var response: okhttp3.Response? = null
            try {
                val next = segClient.newCall(builder.build()).execute()
                response = next
                if (!validOkHttpResponse(next, wanted, original.request.url.toString(), "RESUME")) return null
                val nextRange = parseContentRange(next.header("Content-Range")) ?: return null
                if (nextRange.start != first || nextRange.end != range.end || nextRange.total != range.total) return null
                if (etag != null && next.header("ETag") != etag) return null
                if (etag == null && modified != null && next.header("Last-Modified") != modified) return null
                val stream = next.body?.byteStream() ?: return null
                val verify = ByteArray(tail.size)
                var pos = 0
                while (pos < verify.size) {
                    val n = stream.read(verify, pos, verify.size - pos)
                    if (n < 0) return null
                    if (n > 0) pos += n
                }
                if (!java.util.Arrays.equals(tail, verify)) {
                    nlog("[Seg] RESUME dữ liệu đối chiếu không khớp — dừng")
                    return null
                }
                response = null
                return next to stream
            } catch (e: java.io.IOException) {
                nlog("[Seg] RESUME lỗi upstream ${e.javaClass.simpleName}: ${e.message?.take(70)}")
                return null
            } finally {
                try { response?.close() } catch (_: Throwable) {}
            }
        }

        private fun streamOkHttpBody(
            resp: okhttp3.Response, output: AvsResponseOutput, crlf: String, t0: Long, method: String,
            segUrl: String, reqRange: String?
        ): Long {
            val body = resp.body ?: return -1
            val originalRange = if (resp.code == 206) parseContentRange(resp.header("Content-Range")) else null
            val length = originalRange?.let { it.end - it.start + 1 } ?: body.contentLength()
            if (length == 0L) return -1
            val statusLine = if (resp.code == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
            val head = StringBuilder(statusLine).append(crlf)
                .append("Content-Type: ").append(resp.header("Content-Type") ?: "application/octet-stream").append(crlf)
                .append("Access-Control-Allow-Origin: *").append(crlf)
                .append("Connection: close").append(crlf)
            resp.header("Content-Range")?.let { head.append("Content-Range: ").append(it).append(crlf) }
            resp.header("Accept-Ranges")?.let { head.append("Accept-Ranges: ").append(it).append(crlf) }
            if (length >= 0) head.append("Content-Length: ").append(length).append(crlf)
            head.append(crlf)
            output.write(head.toString().toByteArray())
            val segmentKey = cacheKey(segUrl, reqRange)
            val segmentDuration = segmentDurations[segmentKey]
            val segmentIndex = if (fastReal) orderIndexOf(segUrl, reqRange) else -1
            val validator = when {
                resp.header("ETag")?.let { it.isNotBlank() && !it.startsWith("W/", true) } == true -> "etag"
                !resp.header("Last-Modified").isNullOrBlank() -> "date"
                else -> "none"
            }
            if (okLogCount.get() < 40)
                nlog("[Seg] READY $method status=${resp.code} bytes=$length headerMs=${System.currentTimeMillis() - t0} proto=${resp.protocol} validator=$validator duration=${segmentDuration ?: 0}")
            val buf = ByteArray(65536)
            var sent = 0L
            var tail = ByteArray(0)
            var retries = 0
            var current = resp
            var stream = body.byteStream()
            var lastProgress = System.currentTimeMillis()
            var firstByteAt = 0L
            var earlyStarted = false
            var earlyTask: AvsWarmConnection? = null
            var progressLogs = 0
            try {
                while (length < 0 || sent < length) {
                    var problem: java.io.IOException? = null
                    val wanted = if (length >= 0) minOf(buf.size.toLong(), length - sent).toInt() else buf.size
                    val n = try { stream.read(buf, 0, wanted) } catch (e: java.io.IOException) { problem = e; -1 }
                    if (n < 0) {
                        if (problem == null && length < 0) break
                        if (length >= 0 && sent == length) break
                        nlog("[Seg] upstream ngắt $method bytes=$sent/$length ${problem?.javaClass?.simpleName ?: "EOF"}")
                        if (originalRange == null || retries >= 2 || sent <= 0) return -1
                        retries++
                        try { current.close() } catch (_: Throwable) {}
                        val next = resumeRange(resp, originalRange, sent, tail) ?: return -1
                        current = next.first
                        stream = next.second
                        nlog("[Seg] RESUME #$retries tiếp từ byte=$sent")
                        continue
                    }
                    if (n == 0) continue
                    try {
                        output.write(buf, 0, n)
                    } catch (e: java.io.IOException) {
                        nlog("[Seg] player đóng $method bytes=$sent/$length ${e.javaClass.simpleName}")
                        return -1
                    }
                    val before = sent
                    sent += n
                    tail = lastBytes(tail, buf, n)
                    if (before == 0L) {
                        firstByteAt = System.currentTimeMillis()
                        if (okLogCount.get() < 40)
                            nlog("[Seg] FIRST $method firstMs=${firstByteAt - t0} bytes=$n")
                    }
                    if (sent >= 4096L && !servedBig) {
                        servedBig = true
                        calmRecoveredPlayer()
                    }
                    val now = System.currentTimeMillis()
                    if (!earlyStarted && segmentIndex >= 0 && latestSegmentIndex.get() == segmentIndex &&
                        length >= 524_288L && sent >= 262_144L && sent < length && now - firstByteAt >= 1_000L) {
                        val downloadRate = sent.toDouble() * 1_000.0 / (now - firstByteAt).coerceAtLeast(1L)
                        val mediaRate = segmentDuration?.let { length.toDouble() / it }
                        val threshold = when {
                            mediaRate == null || downloadRate < mediaRate -> 0.86
                            downloadRate < mediaRate * 1.5 -> 0.78
                            else -> 0.68
                        }
                        if (sent.toDouble() / length >= threshold) {
                            earlyTask = maybePrefetchSegment(segmentIndex, true)
                            earlyStarted = earlyTask != null
                        }
                    }
                    if (now - lastProgress >= 5_000L && progressLogs++ < 6) {
                        lastProgress = now
                        nlog("[Seg] PROGRESS $method bytes=$sent/$length totalMs=${now - t0}")
                    }
                }
                if (sent > 0 && segmentIndex >= 0 && okLogCount.get() < 40) {
                    val totalMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    val rateKbps = sent.toDouble() * 8.0 / totalMs
                    val mediaKbps = segmentDuration?.let { length.toDouble() * 8.0 / it / 1000.0 } ?: 0.0
                    nlog("[Seg] RATE fetchKbps=${rateKbps.toInt()} mediaKbps=${mediaKbps.toInt()} ahead=$earlyStarted")
                }
                return sent
            } finally {
                if (earlyStarted && length >= 0 && sent < length) earlyTask?.let { cancelWarmConnection(it) }
                if (current !== resp) try { current.close() } catch (_: Throwable) {}
            }
        }

        private fun validOkHttpResponse(
            resp: okhttp3.Response, range: String?, url: String, method: String
        ): Boolean {
            val ct = resp.header("Content-Type")
            if (isBlockedResponse(resp.code, ct)) {
                logFullUrlOnce(url)
                if (noteBlocked()) {
                    nlog("[Seg] $method ${resp.code} ct=$ct url=...${url.takeLast(40)}")
                    val peek = try { resp.peekBody(200).string().replace("\n", " ").take(140) } catch (_: Throwable) { "?" }
                    nlog("[Seg] $method blocked-body $peek")
                }
                noteAuthFail(ct)
                return false
            }
            val body = resp.body ?: return false
            val len = body.contentLength()
            if (len == 0L || !rangeResponseValid(range, resp.code, resp.header("Content-Range"), len)) {
                if (noteBlocked()) nlog("[Seg] $method sai Range status=${resp.code} range=${range ?: "-"} cr=${resp.header("Content-Range") ?: "-"}")
                return false
            }

            if (len in 1..1024) {
                val peek = try { resp.peekBody(minOf(len, 256L)).bytes() } catch (_: Throwable) { ByteArray(0) }
                if (peek.isNotEmpty() && looksLikeErrorBytes(peek)) return false
            }
            return true
        }

        private fun viaRecipe(segUrl: String, reqRange: String?, output: AvsResponseOutput, crlf: String, t0: Long): Boolean {
            val rec = segRecipe ?: return false
            val url = recipeUrlFor(segUrl)
            return try {
                val rb = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Referer", primaryRef())
                reqRange?.let { rb.header("Range", it) }
                rec.headers.forEach { (k, v) -> try { rb.header(k, v) } catch (_: Throwable) {} }
                cookieHeaderFor(url)?.let { rb.header("Cookie", it) }
                callWithDnsRetry(rb.build()).use { resp ->
                    val headerMs = System.currentTimeMillis() - t0
                    if (!validOkHttpResponse(resp, reqRange, url, "RECIPE")) return@use false
                    val sent = streamOkHttpBody(resp, output, crlf, t0, "RECIPE", segUrl, reqRange)
                    if (sent >= 4096L) servedBig = true
                    if (sent > 0 && okLogCount.incrementAndGet() <= 40) {
                        nlog("[Seg] RECIPE OK bytes=$sent headerMs=${headerMs} totalMs=${System.currentTimeMillis() - t0} url=...${url.takeLast(26)}")
                    }
                    sent > 0
                }
            } catch (e: Throwable) {
                noteDnsFail(e)
                nlog("[Seg] RECIPE err ${e.message?.take(60)} url=...${segUrl.takeLast(30)}")
                false
            }
        }

        private fun viaRelay(segUrl: String, reqRange: String?, output: AvsResponseOutput, crlf: String, t0: Long): Boolean {
            val hasWv = synchronized(relayLock) { relayWv != null }
            if (!hasWv || System.currentTimeMillis() < relayGateUntilGlobalMs ||
                System.currentTimeMillis() < dnsCooldownUntil) return false
            val headers = (if (recipeNeeded) segRecipe?.headers ?: emptyMap() else emptyMap()).toMutableMap()
            reqRange?.let { headers["Range"] = it }
            val relayUrl = if (recipeNeeded) recipeUrlFor(segUrl) else segUrl
            val isKey = keyUrls.contains(segUrl)
            val limit = if (isKey) 16 * 1024 else maxPrefetchBytes
            val result = try {
                relayFetchSegment(relayUrl, headers, 9_000L, limit, if (fastReal) playerMediaGate else null)
            } catch (e: Throwable) {
                AvsRelayResult(null, "exc " + (e.message?.take(50) ?: "?"), 0, null, null)
            }
            val bytes = result.bytes
            if (bytes == null || bytes.isEmpty() || isBlockedResponse(result.status, result.contentType) ||
                !rangeResponseValid(reqRange, result.status, result.contentRange, bytes.size.toLong()) ||
                looksLikeErrorBytes(bytes) || (isKey && bytes.size != 16) || (!isKey && bytes.size < 1024)) {
                relayGateUntilGlobalMs = System.currentTimeMillis() + 10_000
                nlog("[Seg] RELAY fail err=${result.error ?: "bad-response"} st=${result.status} range=${reqRange ?: "-"} — nghỉ 10s url=...${segUrl.takeLast(30)}")
                return false
            }
            val head = StringBuilder(if (result.status == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK").append(crlf)
                .append("Content-Type: ").append(result.contentType ?: if (isKey) "application/octet-stream" else "video/mp2t").append(crlf)
                .append("Content-Length: ").append(bytes.size).append(crlf)
                .append("Connection: close").append(crlf)
                .append("Access-Control-Allow-Origin: *").append(crlf)
            result.contentRange?.let { head.append("Content-Range: ").append(it).append(crlf) }
            head.append(crlf)
            output.write(head.toString().toByteArray())
            output.write(bytes)
            if (bytes.size >= 4096) servedBig = true
            if (okLogCount.incrementAndGet() <= 40) {
                nlog("[Seg] RELAY OK bytes=${bytes.size} totalMs=${System.currentTimeMillis() - t0} url=...${segUrl.takeLast(26)}")
            }
            return true
        }

        private fun viaWarmResponse(
            resp: okhttp3.Response, segUrl: String, reqRange: String?,
            output: AvsResponseOutput, crlf: String, t0: Long
        ): Boolean {
            return try {
                if (!validOkHttpResponse(resp, reqRange, segUrl, "WARM")) return false
                val sent = streamOkHttpBody(resp, output, crlf, t0, "WARM", segUrl, reqRange)
                if (sent >= 4096L) servedBig = true
                if (sent > 0 && okLogCount.incrementAndGet() <= 40)
                    nlog("[Seg] WARM OK bytes=$sent totalMs=${System.currentTimeMillis() - t0}")
                sent > 0
            } catch (e: Throwable) {
                nlog("[Seg] WARM lỗi ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                false
            }
        }

        private fun viaOkHttp(segUrl: String, reqRange: String?, output: AvsResponseOutput, crlf: String, t0: Long): Boolean {
            val ck = cookieHeaderFor(segUrl)
            for (ref in referers) {
                if (ref.isBlank()) continue
                val rb = okhttp3.Request.Builder().url(segUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", ref)
                    .header("Accept", "*/*")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                if (!ck.isNullOrBlank()) rb.header("Cookie", ck)
                reqRange?.let { rb.header("Range", it) }
                val ok = try {
                    segClient.newCall(rb.build()).execute().use { resp ->
                        val headerMs = System.currentTimeMillis() - t0
                        if (!validOkHttpResponse(resp, reqRange, segUrl, "OKHTTP")) return@use false
                        val sent = streamOkHttpBody(resp, output, crlf, t0, "OKHTTP", segUrl, reqRange)
                        if (sent >= 4096L) servedBig = true
                        if (sent > 0 && okLogCount.incrementAndGet() <= 40) {
                            nlog("[Seg] OKHTTP OK bytes=$sent headerMs=${headerMs} totalMs=${System.currentTimeMillis() - t0} sinceReg=${System.currentTimeMillis() - bornAt}ms url=...${segUrl.takeLast(26)}")
                        }
                        sent > 0
                    }
                } catch (e: Throwable) {
                    noteDnsFail(e)
                    nlog("[Seg] OKHTTP err ${e.message?.take(80)} ref=${ref.take(30)} url=...${segUrl.takeLast(40)}")
                    false
                }
                if (ok) return true
                if (output.started) return false
            }
            return false
        }

        private fun viaOkHttpOrigin(segUrl: String, reqRange: String?, originRef: String, output: AvsResponseOutput, crlf: String, t0: Long): Boolean {
            val rb = okhttp3.Request.Builder().url(segUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", originRef)
                .header("Accept", "*/*")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
            reqRange?.let { rb.header("Range", it) }
            cookieHeaderFor(segUrl)?.let { rb.header("Cookie", it) }
            return try {
                segClient.newCall(rb.build()).execute().use { resp ->
                    val headerMs = System.currentTimeMillis() - t0
                    if (!validOkHttpResponse(resp, reqRange, segUrl, "OKHTTP-ORIGIN")) return@use false
                    val sent = streamOkHttpBody(resp, output, crlf, t0, "OKHTTP-ORIGIN", segUrl, reqRange)
                    if (sent >= 4096L) servedBig = true
                    if (sent > 0 && okLogCount.incrementAndGet() <= 40)
                        nlog("[Seg] OKHTTP-ORIGIN OK bytes=$sent headerMs=$headerMs totalMs=${System.currentTimeMillis() - t0}")
                    sent > 0
                }
            } catch (e: Throwable) {
                noteDnsFail(e)
                nlogE("[Seg] OKHTTP-ORIGIN", e, "...${segUrl.takeLast(40)}")
                false
            }
        }

        private fun viaUrlConnection(segUrl: String, reqRange: String?, output: AvsResponseOutput, crlf: String, t0: Long): Boolean {
            var conn: java.net.HttpURLConnection? = null
            return try {
                val c = java.net.URL(segUrl).openConnection() as java.net.HttpURLConnection
                conn = c
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Referer", primaryRef())
                c.setRequestProperty("Accept", "*/*")
                c.setRequestProperty("Sec-Fetch-Site", "same-origin")
                c.setRequestProperty("Sec-Fetch-Mode", "cors")
                c.setRequestProperty("Sec-Fetch-Dest", "empty")
                reqRange?.let { c.setRequestProperty("Range", it) }
                cookieHeaderFor(segUrl)?.let { c.setRequestProperty("Cookie", it) }
                val code = c.responseCode
                val headerMs = System.currentTimeMillis() - t0
                val ct = c.contentType
                if (isBlockedResponse(code, ct)) {
                    logFullUrlOnce(segUrl)
                    if (noteBlocked()) {
                        val peek = try { c.errorStream?.bufferedReader()?.readLine() ?: "?" } catch (_: Throwable) { "?" }
                        nlog("[Seg] URLCONN $code ct=$ct body=${peek.take(140)} url=...${segUrl.takeLast(40)}")
                    }
                    noteAuthFail(ct)
                    return false
                }
                val len = c.contentLengthLong
                val contentRange = c.getHeaderField("Content-Range")
                if (len == 0L || !rangeResponseValid(reqRange, code, contentRange, len)) {
                    if (noteBlocked()) nlog("[Seg] URLCONN sai Range status=$code range=${reqRange ?: "-"} cr=${contentRange ?: "-"}")
                    return false
                }
                val head = StringBuilder(if (code == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK").append(crlf)
                    .append("Content-Type: ").append(ct ?: "application/octet-stream").append(crlf)
                    .append("Access-Control-Allow-Origin: *").append(crlf)
                    .append("Connection: close").append(crlf)
                if (contentRange != null) head.append("Content-Range: ").append(contentRange).append(crlf)
                if (len >= 0) head.append("Content-Length: ").append(len).append(crlf)
                head.append(crlf)
                output.write(head.toString().toByteArray())
                var sent = 0L
                c.inputStream.use { stream ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        output.write(buf, 0, n)
                        sent += n
                    }
                }
                if (sent >= 4096L) servedBig = true
                if (sent > 0 && okLogCount.incrementAndGet() <= 40)
                    nlog("[Seg] URLCONN OK bytes=$sent headerMs=$headerMs totalMs=${System.currentTimeMillis() - t0}")
                sent > 0
            } catch (e: Throwable) {
                noteDnsFail(e)
                nlogE("[Seg] URLCONN", e, "...${segUrl.takeLast(40)}")
                false
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
    }
}
