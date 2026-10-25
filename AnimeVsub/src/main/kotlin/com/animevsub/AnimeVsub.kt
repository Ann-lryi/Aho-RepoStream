// AnimeVsub v7 (2026-10-25): CloudStream provider cho https://animevietsub.sale
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
//  v5 (25-10-2026, fix lần 3 theo log thật — site trả 520 + trang "Lỗi Server 5xx" 888KB):
//    * ROOT CAUSE MỚI: origin server animevietsub.sale TỰ LỖI theo đợt (HTTP 520,
//      trang lỗi ~888KB title "Lỗi Server 5xx - AnimeVietsub", textLen chỉ ~1.2KB)
//      — không phải chỉ CF gate. v4 nhận nhầm trang lỗi là DOM "sạch" (blocked=false)
//      -> CACHE 5 phút -> site hồi phục vẫn 0 items; loadLinks đốt 4 step ~120s
//      -> player báo "Timed out waiting for 120000 ms" (screenshot 25-10 11:34).
//    * looksServerError(): nhận diện trang lỗi qua <title> (lỗi server/5xx/bad
//      gateway/...). Mọi lớp fetch REJECT trang lỗi + KHÔNG BAO GIỜ cache;
//      cache-hit cũng kiểm tra lại trước khi dùng (cache-drop).
//    * SITE-DOWN breaker: 3 lỗi 5xx liên tục -> nghỉ 90s, mọi fetchDoc/loadLinks
//      fail TỨC THÌ + toast rõ ràng — hết cảnh treo 2 phút và đỡ đánh thêm request
//      vào server đang chết. Thành công bất kỳ (plain OK / WVDom có phim) -> gỡ ngay.
//    * WVDom: poll thấy trang lỗi -> dừng capture NGAY (~1s thay vì chờ 22s),
//      không trả DOM lỗi làm "DOM sạch cuối".
//    * Bỏ proxy.cors.sh (NXDOMAIN — domain đã chết), thêm api.codetabs.com.
//  v6 (25-10-2026, nâng cấp theo cấu trúc CMS thật — tham khảo Kodi addon
//  sonle998/kodi-animevietsub: site dùng CMS họ OPhim/TPost với AJAX API):
//    * STEP 0 MỚI trong loadLinks: POST ajax/player (id/play/link/hash,
//      backuplinks=1) — API chính chủ trả JSON/HTML nguồn phát. Metadata tập
//      (data-id/data-play/data-hash từ vùng #list-server của trang chi tiết)
//      được load() nhét vào fragment #avs=<url-encoded> của episode data;
//      loadLinks tách ra trước khi dựng watchUrl. Thành công -> bỏ qua 4 step nặng.
//    * search: thử POST ajax/suggest (ajaxSearch=1&keysearch=...) TRƯỚC các
//      biến thể GET /tim-kiem — 1 request duy nhất khi plain HTTP được qua.
//    * load(): parse #list-server a[data-id] (máy chủ OPhim) -> eps + meta;
//      ép các anchor xem-phim cũ chỉ lấp chỗ còn thiếu.
//    * parseCards: fallback title từ .Title trong card .TPost/.TPostMv.
//    * looksBlocked: thêm "xác minh người dùng việt nam" + "challenge-running"
//      (dấu hiệu gate theo Kodi addon).
//  v7 (25-10-2026, fix theo log v6 thật — user: "load được trang chủ NHƯNG KHÔNG
//  XEM ĐƯỢC PHIM + thiếu tập". Log: [Load] list-server eps=0 meta=0; [Step1] media
//  0/0 iframes=0; S2 hook TIMEOUT 60s captures=0; S3 bắt playlist stream.googleapiscdn
//  nhưng proxy referer=playlist URL; plain ck=10 OK 200 — cookie clearance ĐÃ CHẠY):
//    * ROOT CAUSE 1 — THIẾU TẬP: trang chi tiết KHÔNG chứa #list-server a[data-id]
//      trong HTML thô (site render tập ở TRANG XEM) -> eps=0 -> vòng a[href] nhặt
//      nhầm anchor nút "Xem phim" (text rỗng) -> 1 tập rác "Tập 0" -> Step0 KHÔNG
//      BAO GIỜ chạy. Fix: load() nạp thêm TRANG XEM (mode MODE_EPISODES mới — WVDom
//      chỉ nhận khi DOM có a[data-id]) rồi parse #list-server từ đó; anchor nút bấm
//      (text rỗng/"Xem phim"/"Xem ngay") bị loại khỏi vòng a[href].
//    * ROOT CAUSE 2 — KHÔNG XEM ĐƯỢC: player nạp qua AJAX (HTML trang xem không
//      chứa iframe/m3u8) nên Step1 luôn 0; S2 đốt 60s; S3 đăng ký proxy với
//      referer=playlist URL -> CDN từ chối segment.
//      Fix: Step0 (POST ajax/player id/play/link/hash + X-Requested-With +
//      Referer=trang chi tiết) chạy với metadata NHIỀU MÁY CHỦ từ fragment #avs=
//      ("khối;;khối") hoặc parse từ trang xem pre-fetch; fallback WebView
//      same-origin fetch khi POST OkHttp bị chặn (clearance gắn TLS fingerprint);
//      registerM3U8Link phát link TRỰC TIẾP (referer=trang xem) TRƯỚC link proxy
//      dự phòng; proxy thử chuỗi referer [site, playlist, origin].
//    * Thứ tự step mới: S0 -> S0f -> S0w -> S1 -> S3 -> S4 -> S2 (hook tương tác
//      DỜI XUỐNG CUỐI — hết cảnh chờ 60s trước khi có link).
//  v8 (25-10-2026, WebView-first — theo log 17:59-18:27: plain HTTP chết hẳn 403
//  len≈660, proxy 100% timeout, trong khi WebView app fork render OK 18:21):
//    * Xoá 5 điểm siteDown-throw trong fetchDoc — WebView LUÔN được phép chạy
//      (breaker cũ giết nhầm tầng duy nhất còn sống); CORS proxy HẠ CUỐI chuỗi.
//  v9 (25-10-2026, fix theo log 19:11-19:13 — 139 lỗi 5xx LIÊN TỤC/2 phút, trang
//  "Lỗi Server 5xx - AnimeVietsub" ~888KB do ORIGIN SITE tự sinh, cả WebView thật
//  cũng nhận; Telegram kênh chính thức vẫn đăng bài bình thường 22-25/10 -> site
//  lỗi theo đợt / rate-limit client dội quá nhiều request song song):
//    * STORM-GATE: chính WebView (WVDom) nhận >=2 trang 5xx trong 90s -> chặn
//      MỌI fetch mới 30s, fail tức thì kèm thông báo rõ ("server site lỗi 5xx —
//      đợi ~30s; mở site trong Chrome để kiểm tra"). Khác breaker cũ: chỉ đếm
//      lỗi nhìn thấy TỪ WEBVIEW — trường hợp plain 403 nhưng WebView vẫn vào
//      được thì KHÔNG BAO GIỜ trip (giữ nguyên tắc v8 "WebView luôn được phép").
//    * GATE TOÀN CỤC: tối đa 3 chuỗi plain song song + jitter 80-280ms — trước
//      đây CloudStream nạp 12 mục danh mục CÙNG LÚC ≈ 24 request/2s (log
//      19:11:06: 12 request trong 1 giây) — thứ rất có thể kích rate-limit origin.
//    * DEDUP in-flight: các fetch TRÙNG URL chạy song song được GỘP (log 19:11:
//      cùng 1 URL chạy lặp 4 lần full-chain).
//    * CORS proxy: chạy SONG SONG (race 2 proxy) + timeout cứng 9s/proxy (trước
//      đây tuần tự — server chết = 10-20s/proxy x 2 = đốt 20-40s/URL vô ích).
//    * Gate-fix: cooldown 10 phút chỉ tính khi dialog THẬT SỰ mở được (trước
//      đây lần attempt bị chặn vì "không có Activity" cũng consume cooldown ->
//      các lần sau bị "bỏ qua" oan — log 19:11:25 -> 19:13 toàn "bỏ qua").
//    * Thông báo phân biệt rõ SITE-5xx (server chết, đợi + kiểm tra Chrome) vs
//      bị chặn (cần tick gate); getMainPage/search break sớm khi storm bật.
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
    // Người dùng không chọn tính năng tải xuống cho v1 — bật lại bằng 1 dòng nếu muốn.
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ═══════════════════════════════════════════════════════════════════════════
    //  FLAGS / HẰNG SỐ
    // ═══════════════════════════════════════════════════════════════════════════
    private val AVS_BUILD = "v9"

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
    //  MODE_EPISODES (v7): chỉ nhận khi DOM có a[data-id] (vùng #list-server đã
    //                     render tập) hoặc sau ~10 poll — dùng cho trang xem khi
    //                     load() cần danh sách tập thật.
    private val MODE_LISTING = 0
    private val MODE_DETAIL = 1
    private val MODE_LOOSE = 2
    private val MODE_EPISODES = 3

    private val commonHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    // v5: bỏ proxy.cors.sh (log 25-10: NXDOMAIN — domain đã chết); thêm codetabs.
    private val corsProxies = listOf(
        "https://api.allorigins.win/raw?url=",
        "https://api.codetabs.com/v1/proxy?quest="
    )

    // Log thật 25-10: proxy.cors.sh chết (NXDOMAIN), allorigins timeout 10s/trang.
    // Circuit breaker: sau 4 lần fail liên tục thì tắt proxy 10 phút cho đỡ treo listing.
    private val proxyFailCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var proxyDisabledUntilMs = 0L
    private val PROXY_COOLDOWN_MS = 10 * 60 * 1000L

    // v9: GATE TOÀN CỤC (tối đa 3 chuỗi plain song song + jitter) + DEDUP
    // in-flight + STORM-GATE (khai báo ở khối breaker dưới) — chống dội request.
    private val fetchGate = java.util.concurrent.Semaphore(3)
    private val fetchRand = kotlin.random.Random
    private val inflightLock = Any()
    private val inflightDocs = HashMap<String, kotlinx.coroutines.CompletableDeferred<Document>>()

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
               (lower.contains("cloudflare") && lower.contains("challenge")) ||
               // v6: nhận diện thêm theo Kodi addon (sonle998) — văn bản + node id
               lower.contains("xác minh người dùng việt nam") ||
               lower.contains("challenge-running")
    }

    // ═══ v5: NHẬN DIỆN TRANG LỖI 5xx CỦA SITE + SITE-DOWN BREAKER ═══
    //  Log thật 25-10 11:32: site trả HTTP 520 kèm trang lỗi ~888KB, title
    //  "Lỗi Server 5xx - AnimeVietsub", textLen chỉ ~1.2KB — v4 coi là DOM sạch
    //  (blocked=false) rồi CACHE -> 0 items dai dẳng kể cả khi site hồi phục.
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

    // Breaker toàn cục: server 5xx liên tục -> NGHỈ 90s (mọi fetchDoc/loadLinks
    // fail tức thì với thông báo rõ) — hết cảnh treo 2 phút, đỡ đánh thêm request
    // vào server đang chết. Mọi thành công (plain OK / WVDom có dữ liệu) gỡ ngay.
    private val srvErrCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var siteDownUntilMs = 0L
    private val SITE_DOWN_THRESHOLD = 3
    private val SITE_DOWN_COOLDOWN_MS = 90_000L

    // v9: STORM-GATE — khi CHÍNH WebView (WVDom) liên tục nhận trang lỗi 5xx
    // (server chết thật với client này), chặn MỌI fetch mới 30s: fail tức thì
    // thay vì dội hàng trăm request (log 19:11: 139 lỗi/2 phút vì không có chốt).
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
        // v9: lỗi nhìn thấy TỪ WEBVIEW (trang thật của trình duyệt) mới là bằng
        // chứng "server chết với client này" -> bật STORM-GATE chặn fetch mới.
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

    /** v7: số anchor tập a[data-id] (vùng #list-server CMS OPhim) trong HTML. */
    private fun countEpisodeAnchors(html: String): Int {
        return try { Regex("""data-id=""").findAll(html).count() } catch (_: Throwable) { 0 }
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
                // v5: 520/5xx từ origin (storm 11:32 trong log) — đếm vào breaker.
                if (resp.code in 500..599) noteServerError("plain-http", url)
                nlog("[Fetch] plain $tag ${resp.code} REJECT len=${html.length} ms=${System.currentTimeMillis() - t0} $url")
                return null
            }
            val blocked = looksBlocked(html)
            if (!blocked) {
                // v5: trang lỗi 5xx có thể đến kèm HTTP 200 (qua proxy/CDN cache)
                // — chặn ở đây để KHÔNG BAO GIỜ trả/cache trang lỗi.
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

    /**
     * Fetch một trang HTML bất kỳ của site, nhanh nhất có thể.
     * mode: MODE_LISTING (mặc định — chỉ nhận trang đã render cards),
     *       MODE_DETAIL (trang chi tiết/xem), MODE_LOOSE (search, embed),
     *       MODE_EPISODES (v7 — trang xem: đợi vùng #list-server render tập).
     * Sau mỗi lần WebView DOM capture thành công, cookies (cf_clearance...) được
     * cache để các lần gọi plain HTTP sau có cơ hội không phải chạy WebView nữa.
     * v9: wrapper = cache -> STORM-GATE -> dedup in-flight -> fetchDocChain.
     */
    private suspend fun fetchDoc(url: String, useCache: Boolean = true, mode: Int = MODE_LISTING): Document {
        if (useCache) {
            docCache[url]?.let { cached ->
                if (System.currentTimeMillis() - cached.time < DOC_CACHE_TTL_MS) {
                    // v5: cache có thể chứa trang lỗi (session trước để lại) — kiểm tra lại.
                    if (docLooksServerError(cached.doc)) {
                        docCache.remove(url)
                        nlog("[Fetch] cache-drop (trang lỗi 5xx) $url")
                    } else {
                        nlog("[Fetch] cache-hit $url")
                        return cached.doc
                    }
                }
            }
        }
        // v9: STORM-GATE — WebView vừa xác nhận server trả 5xx -> fail TỨC THÌ,
        // đừng dội thêm request vào server đang chết (log 19:11: 139 lỗi/2 phút).
        if (srvStormActive()) {
            nlog("[Fetch] STORM-GATE chặn fetch mới (server 5xx) $url")
            throw srvStormError()
        }
        // v9: gộp các fetch TRÙNG URL đang chạy song song — CloudStream nạp 12
        // mục danh mục cùng lúc + retry; log 19:11: 1 URL chạy lặp 4 lần full-chain.
        var joinRetries = 0
        while (true) {
            val (isLeader, deferred) = joinOrCreateInflight(url)
            if (!isLeader && joinRetries < 2) {
                joinRetries++
                nlog("[Fetch] join in-flight #$joinRetries $url")
                try {
                    return deferred.await()
                } catch (e: Throwable) {
                    nlog("[Fetch] leader chết (${e.message?.take(60) ?: "?"}) — tự chạy lại $url")
                }
                continue
            }
            try {
                val doc = fetchDocChain(url, useCache, mode)
                deferred.complete(doc)
                return doc
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
                throw t
            } finally {
                clearInflight(url, deferred)
            }
        }
    }

    /** v9: join-or-lead cho dedup in-flight (lock nội bộ — API 1-safe). */
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

    /** Chuỗi fetch thật (v9 tách khỏi fetchDoc để wrapper lo storm-gate + dedup). */
    private suspend fun fetchDocChain(url: String, useCache: Boolean, mode: Int): Document {
        // v8: KHÔNG fail tức thì theo breaker nữa — log 25-10 17:59-18:27: site
        // flappy (plain 403-gate xen 520-storm) trong khi WebView VẪN VÀO ĐƯỢC
        // (screenshot user 18:21 render đầy đủ). Breaker chỉ còn ý nghĩa với tầng
        // plain/proxy; tầng WebView tự bảo vệ bằng bail nhanh khi thấy DOM 5xx.

        // ── 1. Plain HTTP với cookie clearance đã có (nhanh nhất) ──
        val cachedCookies = cfCookieCache[mainUrl] ?: cookiesFromCookieManager(mainUrl)
        // v9: GATE TOÀN CỤC — tối đa 3 chuỗi plain song song + jitter 80-280ms.
        // Trước đây 12 mục danh mục nạp CÙNG LÚC ≈ 24 request/2s dội vào site —
        // thứ rất có thể kích rate-limit origin -> trang "Lỗi Server 5xx" theo đợt.
        while (!fetchGate.tryAcquire()) kotlinx.coroutines.delay(60)
        try {
            kotlinx.coroutines.delay(fetchRand.nextLong(80, 280))
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
        } finally {
            fetchGate.release()
        }
        // ── 2. WebView-first (v8): plain đã chết (403-gate/520-storm) — vào WebView
        //    NGAY. Log 25-10: CORS proxy 100% timeout (đốt 10-20s/URL vô ích) nên
        //    bị HẠ xuống cuối chuỗi; breaker không còn quyền chặn tầng WebView. ──
        nlog("[Fetch] plain dead (403/520) -> webview-first mode=$mode $url")

        // ── 3. WebView DOM capture im lặng (serialized qua cfMutex) ──
        //    v4: WebViewResolver cũ trả RAW HTML TRƯỚC KHI JS render — log 25-10
        //    11:09: webview "OK" nhưng 0 link /phim/ -> 0 items mọi danh mục.
        //    Giờ: WebView tự quản, poll DOM mỗi 0.8s, chỉ nhận khi có dữ liệu phim.
        val silent = cfMutex.withLock {
            // Ai đó có thể đã fetch xong trong lúc ta chờ lock.
            if (useCache) {
                docCache[url]?.let { cached ->
                    if (System.currentTimeMillis() - cached.time < DOC_CACHE_TTL_MS) {
                        // v5: validate cache trong lock nữa (doc mới có thể vừa ghi lỗi).
                        if (docLooksServerError(cached.doc)) {
                            docCache.remove(url)
                            nlog("[Fetch] cache-drop (trang lỗi 5xx) $url")
                        } else {
                            return@withLock cached.doc
                        }
                    }
                }
            }
            // v8: không check breaker trong lock — nếu site thật sự chết,
            // webviewDocCapture tự bail ~2s khi poll thấy DOM 5xx.
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

        // v9: storm đang bật -> gate/webview/proxy đều vô ích, fail tức thì.
        if (srvStormActive()) {
            nlog("[Fetch] STORM-GATE giữa chuỗi — dừng trước gate $url")
            throw srvStormError()
        }

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

        // v9: storm bật -> proxy chỉ ăn timeout 10-20s, fail tức thì.
        if (srvStormActive()) {
            nlog("[Fetch] STORM-GATE — bỏ qua proxy $url")
            throw srvStormError()
        }

        // ── 5. CORS proxy — HẠ CUỐI (v8): log 25-10 18h: allorigins/codetabs 100%
        //    timeout 10-20s/URL, lần "200" duy nhất cũng là trang gate bị chặn
        //    (blocked=true). Chỉ còn là phương sách cuối; giữ circuit breaker.
        //    v9: chạy SONG SONG (race) + timeout cứng 9s/proxy (tryCorsProxy). ──
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

    /** v9: thử 1 CORS proxy với timeout cứng 9s — log 25-10: khi server site
     *  chết, allorigins/codetabs treo 10-20s/URL, đốt chuỗi fetch vô ích. */
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
                // v5: site-down -> các candidate kế cũng vậy, đừng lặp vô ích.
                // v9: storm-gate (WebView cũng 5xx) -> break luôn.
                if (siteDown() || srvStormActive()) break
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
                // v6: CMS site dạng OPhim/TPost — tên phim nằm ở .Title trong card
                if (title.isBlank()) title = a.closest(".TPost, .TPostMv, li, article")?.selectFirst(".Title")?.text()?.trim() ?: ""
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

        // v6: API AJAX chính chủ (theo Kodi addon sonle998/kodi-animevietsub):
        // POST ajax/suggest với ajaxSearch=1&keysearch=... — CMS OPhim trả HTML
        // <li> gợi ý (có <a> + <img>) hoặc JSON. Rẻ hơn nhiều so với 4 biến thể GET
        // qua WebView — thử TRƯỚC, thất bại thì rơi xuống biến thể GET cũ.
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
                // v9: storm/breaker bật -> dừng toàn bộ biến thể, đừng đốt chuỗi nữa.
                if (srvStormActive() || siteDown()) return emptyList()
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

    // ═══════════════════════════════════════════════════════════════════════
    //  v7: METADATA TẬP (CMS OPhim) + AJAX/PLAYER
    // ═══════════════════════════════════════════════════════════════════════

    /** 1 máy chủ phát của 1 tập: id/play/hash cho POST ajax/player. */
    private data class AvsSrv(val id: String, val play: String, val hash: String)

    /** Encode 1 máy chủ thành khối "id=..&play=..&hash=.." (giá trị url-encode). */
    private fun buildAvsMetaBlock(id: String, play: String, hash: String): String {
        fun enc(s: String) = try { URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }
        return "id=" + enc(id) + "&play=" + enc(play) + "&hash=" + enc(hash)
    }

    /** Parse chuỗi meta "khối;;khối" -> danh sách máy chủ (ngược buildAvsMetaBlock).
     *  v7b-fix: trả MutableList để call site (loadLinks Step1p) gọi addAll được. */
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

    /**
     * v7: parse vùng #list-server (CMS OPhim) — a[data-id][data-play][data-hash].
     * Dùng chung cho trang chi tiết + TRANG XEM (site render tập ở trang xem —
     * log 25-10: trang chi tiết eps=0 meta=0). Multi-server: mỗi tập gom NHIỀU
     * khối meta (tất cả máy chủ) — Step0 thử lần lượt tới khi có nguồn.
     * Trả về số tập MỚI thêm được.
     */
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
                        val href = absolutize(a.attr("href"))
                        epOrder[epName] = if (href != null && href.contains("xem-phim")) href
                                          else detail.trimEnd('/') + "/xem-phim.html"
                        added++
                    }
                } catch (e: Throwable) { nlogE("[Load] list-server", e) }
            }
        } catch (e: Throwable) { nlogE("[Load] list-server region", e) }
        return added
    }

    /**
     * v7: parse JSON trả về từ POST ajax/player (port api_sources Kodi addon):
     *  - link/sources là STRING: chứa <iframe> -> đào iframe; URL media -> nguồn
     *    trực tiếp; URL thường + playTech=iframe -> iframe; còn lại -> mã hóa.
     *  - link/sources là ARRAY: từng phần tử {file|src, label, type}.
     *  - Không phải JSON (HTML trần): harvest iframe/media URL như thường.
     * Trả về (media url->label, iframe embeds, có nguồn mã hóa không).
     */
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
            // Body KHÔNG phải JSON — nhiều bản CMS trả HTML trần chứa iframe player.
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

    /** v7: label "720p"/"1080P"/"FHD"/"HD"/"4K" -> quality CloudStream. */
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

    /**
     * v7: xử lý nguồn trả về từ ajax/player (dùng cho cả POST OkHttp lẫn WebView
     * fetch): m3u8 -> fetch playlist -> register (direct + proxy); mp4 -> link
     * thẳng; iframe -> bỏ vào hàng đợi embed. Trả về true nếu đã phát được link.
     */
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

    /** Bridge nhận body ajax/player từ WebView fetch (same-origin). */
    private class AvsAjaxBridge(private val cb: (String) -> Unit) {
        @JavascriptInterface
        fun onBody(body: String?) {
            if (body.isNullOrBlank()) return
            try { cb(body) } catch (_: Throwable) {}
        }
    }

    /**
     * v7: Fallback Step0 — POST ajax/player TỪ TRONG WebView (same-origin fetch).
     * Cloudflare gắn clearance vào TLS fingerprint của WebView nên POST OkHttp có
     * thể vẫn 403 dù GET được; fetch() chạy trong ngữ cảnh trang xem thì mang đầy
     * đủ cookie + TLS thật của WebView. Trả về body (JSON/HTML) hoặc null.
     */
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
                          fetch('$mainUrl/ajax/player', {
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
            val body = withTimeoutOrNull(30_000L) { done.await() }
            nlog("[Step0w] webview-ajax ${if (body == null) "TIMEOUT" else "len=" + body.length}")
            return body
        } catch (e: Throwable) {
            if (isCancellation(e)) throw e
            nlogE("[Step0w]", e)
            return null
        } finally {
            try { withContext(Dispatchers.Main) { webView?.destroy() } } catch (_: Throwable) {}
        }
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

        // ── Trích xuất tập (v7) ──
        val epOrder = LinkedHashMap<String, String>()             // epName -> watchUrl
        val epMeta  = LinkedHashMap<String, MutableSet<String>>() // v7: epName -> N máy chủ
        val seenUrls = HashSet<String>()

        // v7: CMS OPhim — vùng #list-server chứa a[data-id][data-hash] (máy chủ).
        // Metadata cho phép loadLinks gọi POST ajax/player TRỰC TIẾP (Step 0),
        // nhét vào fragment #avs= của episode data (CloudStream giữ nguyên fragment).
        val addedDetail = parseOphimEpisodes(doc, detail, epOrder, epMeta)
        nlog("[Load] list-server eps=+$addedDetail total=${epOrder.size} meta=${epMeta.size}")

        // Anchor xem-phim khác trên trang chi tiết — BỎ anchor nút bấm ("Xem phim"/
        // icon text rỗng): v6 biến nó thành tập rác "Tập 0" (bug user 25-10).
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

        // v7: trang chi tiết không có danh sách tập — site render tập ở TRANG XEM.
        // Nạp trang xem (MODE_EPISODES: WVDom đợi #list-server render tập) rồi parse.
        if (epOrder.isEmpty()) {
            val watchGuess = detail.trimEnd('/') + "/xem-phim.html"
            try {
                val wdoc = fetchDoc(watchGuess, useCache = false, mode = MODE_EPISODES)
                val addedWatch = parseOphimEpisodes(wdoc, detail, epOrder, epMeta)
                nlog("[Load] watch-page eps=+$addedWatch total=${epOrder.size} meta=${epMeta.size} $watchGuess")
            } catch (e: Throwable) {
                if (isCancellation(e)) throw e
                nlogE("[Load] watch-page", e, watchGuess)
            }
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
            // v7: episode có metadata ajax/player (N máy chủ) -> gắn fragment #avs=
            val metaRaw = epMeta[epName]?.joinToString(";;") { it } ?: ""
            val data = if (metaRaw.isNotBlank())
                watchUrl + "#avs=" + URLEncoder.encode(metaRaw, "UTF-8")
            else watchUrl
            newEpisode(data) {
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
    //  LOADLINKS (v7) — chuỗi chiến lược đa đường, NHANH TRƯỚC, NẶNG SAU:
    //   PRE   : plain nạp trang xem (metadata tập fallback + HTML cho Step1)
    //   STEP 0: POST ajax/player (N máy chủ) -> m3u8/mp4/iframe trực tiếp
    //   STEP 0f: đào iframe do ajax/player trả
    //   STEP 0w: WebView same-origin fetch ajax/player (khi POST OkHttp bị chặn)
    //   STEP 1: HTML trang xem -> harvest m3u8/mp4 + iframe embed (1 cấp lồng)
    //   STEP 3: WebViewResolver broad trên trang xem
    //   STEP 4: WebViewResolver broad trên từng embed
    //   STEP 2: WebView + JS hook tương tác — DỜI XUỐNG CUỐI (đắt: dialog + 60s)
    //  registerM3U8Link phát link TRỰC TIẾP (referer đúng) + link proxy dự phòng.
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

        // v7: tách metadata ajax/player từ fragment #avs= — NHIỀU MÁY CHỦ:
        // "id=..&play=..&hash=..;;id=..&play=..&hash=.." (mỗi khối = 1 máy chủ)
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

        if (!watchUrl.contains("xem-phim") && watchUrl.contains("/phim/")) {
            watchUrl = watchUrl.trimEnd('/') + "/xem-phim.html"
        }
        nlog("[loadLinks] watch=$watchUrl")
        ntoastV("AnimeVsub $AVS_BUILD: đang lấy link…")

        // v5: site đang chết (breaker bật) — đừng đốt 4 step ~120s khiến player
        // báo "Timed out waiting for 120000 ms"; báo rõ và thoát ngay.
        if (siteDown()) {
            nlog("[loadLinks] ABORT site-down watch=$watchUrl")
            ntoast("AnimeVsub: animevietsub.sale đang lỗi 5xx — thử lại sau ~1-2 phút")
            return false
        }

        coroutineScope {
            // ── v7 PRE-FETCH: nạp TRANG XEM bằng plain HTTP (nhanh, dùng cookies
            //  sẵn có — log 25-10: plain ck=10 OK 200) — 1 lần cho cả
            //  Step0-metadata-fallback lẫn Step1. KHÔNG dùng fetchDoc (full chain)
            //  ở đây để tránh treo trước Step0. ──
            var watchDoc: Document? = null
            if (!siteDown()) {
                try {
                    val ck = cfCookieCache[mainUrl] ?: cookiesFromCookieManager(mainUrl)
                    watchDoc = (if (ck != null) tryPlainDoc(watchUrl, ck) else null)
                        ?: tryPlainDoc(watchUrl, null)
                    if (watchDoc != null) {
                        noteSiteOk()
                        nlog("[Step1p] watch plain OK len=${watchDoc!!.outerHtml().length} $watchUrl")
                    }
                } catch (e: Throwable) {
                    if (isCancellation(e)) throw e
                    nlogE("[Step1p]", e)
                }
                // Fragment rỗng (load() không lấy được list-server) -> parse metadata
                // từ HTML trang xem vừa nạp.
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

            // ═══ STEP 0: POST ajax/player — API chính chủ CMS OPhim. Thử TỪNG MÁY
            //  CHỦ trong metadata (fragment #avs= hoặc trang xem pre-fetch); thành
            //  công -> bỏ qua các step nặng WebView. ═══
            val embedQueue = mutableListOf<String>()
            var step0Weak = false
            if (srvList.isNotEmpty() && !linkFound && !siteDown()) {
                var tried = 0
                for (srv in srvList) {
                    if (linkFound || siteDown() || tried >= 4) break
                    tried++
                    try {
                        val ref = detailBaseOf(watchUrl)
                        val resp = app.post(
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

            // ═══ STEP 0f: iframe do ajax/player trả (playTech=iframe / HTML trần) ═══
            if (!linkFound && embedQueue.isNotEmpty() && !siteDown()) {
                for (fr in embedQueue.distinct().take(MAX_EMBEDS)) {
                    if (linkFound || siteDown()) break
                    nlog("[Step0f] embed ${fr.take(70)}")
                    if (extractFromEmbed(fr, watchUrl, callback)) { linkFound = true; break }
                }
                stepNotes.add("S0f:${if (linkFound) "HIT" else "0"}")
            }

            // ═══ STEP 0w (v7): WebView same-origin fetch — POST OkHttp bị Cloudflare
            //  chặn (clearance gắn TLS fingerprint WebView) thì fetch() trong WebView
            //  vẫn mang cookie + TLS thật. Tối đa 2 máy chủ, mỗi lần 30s. ═══
            if (!linkFound && step0Weak && srvList.isNotEmpty() && !siteDown()) {
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

            // ═══ STEP 1: HTML trang xem (pre-fetch ở trên; hụt thì fetchDoc đầy
            //  đủ chain: proxy -> WVDom -> gate) ═══
            if (!linkFound) {
                try {
                    val doc = watchDoc ?: fetchDoc(watchUrl, useCache = false, mode = MODE_DETAIL).also { watchDoc = it }
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

            // ═══ STEP 3: WebViewResolver broad trên trang xem ═══
            if (!linkFound && !siteDown()) {   // v5
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
                        if (registerM3U8Link(content, m3u8Url, m3u8Url, "Vietsub", callback, siteReferer = watchUrl)) {
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

            // ═══ STEP 4: WebViewResolver broad trên từng embed (nếu còn đường) ═══
            if (!linkFound && !siteDown()) {   // v5
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

            // ═══ STEP 2 (v7 DỜI XUỐNG CUỐI): WebView + JS hook tương tác ═══
            //  Đắt nhất (Dialog + đợi capture tới 60s) — chỉ dùng khi MỌI đường
            //  nhanh đều hụt; log 25-10: S2 TIMEOUT 60s chặn S3 thêm 4s nữa.
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
                        if (registerM3U8Link(body, capUrl, base, "Vietsub", callback, siteReferer = targetUrl)) {
                            registered = true
                        }
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
     *  - MODE_EPISODES (v7): hoàn tất khi DOM có a[data-id] (#list-server đã render
     *    tập) hoặc sau ~10 poll (~8s) — dùng khi cần danh sách tập thật của trang xem.
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
        var sawSrvErr = false
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
                    val srvErr = looksServerError(html)
                    val cards = countPhimAnchors(html)
                    val xem = countXemPhim(html)
                    val eps = countEpisodeAnchors(html)
                    nlog("[WVDom] poll#$pollIdx len=${html.length} blocked=$blocked srvErr=$srvErr cards=$cards xem=$xem eps=$eps ${u.take(70)}")
                    // v5: trang lỗi 5xx — dừng capture NGAY, không bao giờ trả/cache
                    // DOM lỗi (trước đây: TIMEOUT "DOM sạch" len=888812 = trang lỗi -> cache).
                    if (srvErr) {
                        noteServerError("WVDom", u)
                        sawSrvErr = true
                        try { wv.stopLoading() } catch (_: Throwable) {}
                        if (!done.isCompleted) done.complete(null)
                        return@AvsGateBridge
                    }
                    if (cards > 0 || xem > 0) noteSiteOk()
                    lastHtml = html
                    lastBlocked = blocked
                    val ready = !blocked && when (mode) {
                        MODE_LISTING -> cards > 0
                        MODE_DETAIL  -> xem > 0 || cards > 0 || pollIdx >= 8
                        MODE_EPISODES -> eps > 0 || pollIdx >= 10
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
        // v9: storm -> server đang 5xx với cả WebView, mở gate vô ích.
        if (srvStormActive()) {
            nlog("[Gate] bỏ qua — server đang lỗi 5xx (STORM-GATE)")
            return@withLock null
        }
        if (System.currentTimeMillis() - lastGateAttemptMs < GATE_RETRY_MS) {
            // Có unlock khác đang chạy/vừa chạy — trả null để caller thử đường khác.
            nlog("[Gate] bỏ qua (đã có unlock trong ${GATE_RETRY_MS / 60000} phút gần đây)")
            return@withLock null
        }

        val ctx = appContext()
        if (ctx !is android.app.Activity || ctx.isFinishing) {
            nlog("[Gate] không có Activity foreground — không mở dialog xác minh")
            return@withLock null
        }

        // v9-fix: chỉ tính "đã thử unlock" khi dialog THẬT SỰ mở được — trước đây
        // lần attempt bị chặn (không Activity) cũng consume cooldown 10 phút ->
        // các lần sau bị "bỏ qua" oan (log 19:11:25 -> 19:13 toàn "bỏ qua").
        lastGateAttemptMs = System.currentTimeMillis()

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

    /** Tạo link phát cho 1 playlist (v7): link TRỰC TIẾP trước (ExoPlayer tự gửi
     *  UA+Referer cho cả playlist lẫn segment) + link proxy cục bộ DỰ PHÒNG với
     *  chuỗi referer [site, playlist]. Log 25-10: proxy cũ dùng referer=playlist
     *  URL -> CDN stream.googleapiscdn từ chối -> không phát được dù S3 "HIT". */
    private suspend fun registerM3U8Link(
        m3u8Content: String, m3u8Url: String, m3u8BaseUrl: String,
        serverName: String, callback: (ExtractorLink) -> Unit,
        siteReferer: String = ""
    ): Boolean {
        if (!m3u8Content.contains("#EXTM3U")) return false
        val ref = siteReferer.ifBlank { m3u8Url }

        val segUrls = m3u8Content.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if (it.startsWith("http") || m3u8BaseUrl.isEmpty()) it else m3u8BaseUrl.trimEnd('/') + "/" + it }
        nlog("[Register] '$serverName' segments=${segUrls.size} first=${segUrls.firstOrNull()?.take(100)} referer=${ref.take(60)}")

        val trim = preflightTrimCount(segUrls, ref)
        if (trim > 0) nlog("[Register] trim $trim segment đầu (probe chặn)")

        // ── Link TRỰC TIẾP: phát luôn URL gốc, ExoPlayer gửi UA+Referer ──
        try {
            callback(newExtractorLink(name, serverName, m3u8Url, ExtractorLinkType.M3U8) {
                quality = Qualities.P1080.value
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to ref)
            })
            nlog("[Register] direct '$serverName' ${m3u8Url.take(80)}")
        } catch (e: Throwable) {
            nlogE("[Register] direct", e)
        }

        // ── Link PROXY dự phòng: local server, chuỗi referer đầy đủ ──
        //  Giới hạn số proxy sống đồng thời (tránh cạn port trong session dài).
        //  v7b-fix: newExtractorLink là hàm suspend — KHÔNG được gọi bên trong
        //  synchronized (critical section). Lock CHỈ quản lý vòng đời server;
        //  phát link thực hiện sau khi đã thoát lock.
        var proxyBase: String? = null
        synchronized(activeServers) {
            while (activeServers.size >= 8) {
                try { activeServers.removeAt(0).stop() } catch (_: Throwable) {}
            }
            try {
                val server = AvsProxyServer(if (siteReferer.isNotBlank()) listOf(siteReferer, m3u8Url) else listOf(m3u8Url))
                server.label = serverName
                server.start()
                activeServers.add(server)
                proxyBase = "http://127.0.0.1:${server.port}"
                server.setM3U8(rewriteM3U8(trimLeadingSegments(m3u8Content, trim), "http://127.0.0.1:${server.port}", m3u8BaseUrl))
            } catch (e: Throwable) {
                proxyBase = null
                nlogE("[Register] bind", e, "không mở được port cục bộ — chỉ dùng link direct")
            }
        }
        val proxyEmitBase = proxyBase ?: return true
        try {
            callback(newExtractorLink(name, "$serverName • dự phòng", "$proxyEmitBase/stream.m3u8", ExtractorLinkType.M3U8) {
                quality = Qualities.P1080.value
                headers = mapOf("User-Agent" to USER_AGENT)
            })
            nlog("[Register] emitted $proxyEmitBase/stream.m3u8 segs=${segUrls.size}")
        } catch (e: Throwable) {
            nlogE("[Register] proxy-emit", e)
        }
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

    private inner class AvsProxyServer(private val referers: List<String>) {
        private fun primaryRef(): String = referers.firstOrNull() ?: ""

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
            nlog("[Proxy] started port=$port label=$label refs=${referers.size} referer=${primaryRef().take(50)}")
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
                            val origin = Regex("""^(https?://[^/]+)""").find(primaryRef())?.groupValues?.get(1) ?: ""
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
            // v7: thử TỪNG referer trong chuỗi (site -> playlist URL -> ...)
            for (ref in referers) {
                if (ref.isBlank()) continue
                val req = okhttp3.Request.Builder().url(segUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", ref)
                    .header("Accept", "*/*")
                    .build()
                val ok = try {
                    segClient.newCall(req).execute().use { resp ->
                        val ct = resp.header("Content-Type")
                        if (isBlockedResponse(resp.code, ct)) {
                            nlog("[Seg] OKHTTP ${resp.code} ct=$ct ref=${ref.take(30)} url=...${segUrl.takeLast(40)}")
                            return@use false
                        }
                        val body = resp.body ?: return@use false
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
                    nlog("[Seg] OKHTTP err ${e.message} ref=${ref.take(30)} url=...${segUrl.takeLast(40)}")
                    false
                }
                if (ok) return true
            }
            return false
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
                conn.setRequestProperty("Referer", primaryRef())
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
