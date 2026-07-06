package com.animevietsub

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.EnumSet
import kotlin.coroutines.resume

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        val provider = AnimeVietSubProvider()
        registerMainAPI(provider)
        kotlinx.coroutines.GlobalScope.launch {
            provider.prefetchAvsJs()
        }
    }
}

class AnimeVietSubProvider : MainAPI() {
    override var mainUrl = "https://animevietsub.love"
    override var name = "AnimeVietSub"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    private val cfInterceptor = com.lagradost.cloudstream3.network.WebViewResolver(
        Regex("""https://animevietsub\.love.*""")
    )

    private val baseHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Referer" to "$mainUrl/"
    )

    private fun pageUrl(base: String, page: Int) =
        if (page == 1) "$mainUrl/$base/"
        else "$mainUrl/$base/trang-$page.html"

    private var cachedAvsJs: String? = null

    // Full browser headers
    private val browserHeaders = mapOf(
        "User-Agent"      to UA,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer"         to "$mainUrl/",
        "Sec-Fetch-Dest"  to "document",
        "Sec-Fetch-Mode"  to "navigate",
        "Sec-Fetch-Site"  to "same-origin",
        "Upgrade-Insecure-Requests" to "1"
    )


    private fun parseCard(el: Element): SearchResponse? {
        // Structure: li.TPostMv > article.TPost.C... > a[href=/phim/...] > img, h2.Title
        val a = el.selectFirst("a[href*='/phim/']") ?: return null
        val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title = el.selectFirst("h2.Title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: a.attr("title").trim().ifBlank { null }
            ?: el.selectFirst(".Title")?.text()?.trim()?.ifBlank { null }
            ?: return null
        val poster = el.selectFirst("img")
            ?.let { img -> img.attr("src").ifBlank { img.attr("data-src") } }
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val epiNum = el.selectFirst("span.mli-eps i")?.text()?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.quality = SearchQuality.HD
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            if (epiNum != null) this.episodes = mutableMapOf(DubStatus.Subbed to epiNum)
        }
    }

    override val mainPage = mainPageOf(
        "/"                 to "Mới Cập Nhật",
        "/anime-bo"         to "Anime Bộ",
        "/anime-le"         to "Anime Lẻ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = app.get(url, headers = browserHeaders, interceptor = cfInterceptor).document
        val items = doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/",
            headers = browserHeaders
        ).document
        return doc.select("ul.MovieList li.TPostMv, li.TPostMv").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val base = url.trimEnd('/')
        val doc = app.get(base, headers = browserHeaders, interceptor = cfInterceptor).document

        val title    = doc.selectFirst("h1.Title")?.text()?.trim() ?: doc.title()
        val altTitle = doc.selectFirst("h2.SubTitle")?.text()?.trim()
        val poster   = doc.selectFirst("div.Image figure img, .Image img")?.attr("src")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val plot     = doc.selectFirst("div.Description")?.text()?.trim()
        val year     = doc.selectFirst("span.Date a, .Date a")?.text()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val tags     = doc.select("p.Genre a, li:contains(Thể loại) a")
            .map { it.text().trim() }.filter { it.isNotBlank() }

        val description = buildBeautifulDescription(altTitle, null, null,
            doc.selectFirst("span.Qlty")?.text()?.trim() ?: "HD",
            null, year?.toString(), null, null,
            doc.selectFirst("span.View")?.text()?.trim(), null,
            tags.joinToString(", "), plot)

        // Episodes: #list-server .list-episode a.episode-link
        val seen = mutableSetOf<String>()
        val episodes = doc.select("#list-server .list-episode a.episode-link, .list-episode a[data-id]")
            .mapNotNull { a ->
                val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
                if (href.isBlank() || !seen.add(href)) return@mapNotNull null
                val epNum = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
                val epTitle = a.attr("title").ifBlank { "Tập ${a.text().trim()}" }
                newEpisode(href) { this.name = epTitle; this.episode = epNum }
            }.distinctBy { it.episode ?: it.data }
            .sortedBy { it.episode ?: 0 }

        return if (episodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie,
                episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster; this.plot = description; this.tags = tags; this.year = year
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime, true) {
                this.posterUrl = poster; this.plot = description; this.tags = tags; this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    private fun buildBeautifulDescription(
        altTitle: String?,
        status: String?,
        duration: String?,
        quality: String?,
        country: String?,
        year: String?,
        studio: String?,
        followers: String?,
        views: String?,
        latestEps: String?,
        genre: String?,
        description: String?
    ): String {
        return buildString {
            altTitle?.takeIf { it.isNotBlank() }?.let {
                append("<font color='#AAAAAA'><i>$it</i></font><br><br>")
            }

            fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank()) {
                    append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
                }
            }

            val statusColor = when {
                status?.contains("đang chiếu", ignoreCase = true) == true -> "#4CAF50"
                status?.contains("hoàn thành", ignoreCase = true) == true -> "#2196F3"
                status?.contains("sắp chiếu", ignoreCase = true) == true -> "#FF9800"
                else -> "#2196F3"
            }

            addInfo("📺", "Trạng thái", status, statusColor)
            addInfo("⏱", "Thời lượng", duration)
            addInfo("🎬", "Chất lượng", quality?.ifBlank { null }, "#E91E63")
            addInfo("🌍", "Quốc gia", country)
            addInfo("📅", "Năm", year?.ifBlank { null })
            addInfo("🎥", "Studio", studio)
            addInfo("👥", "Theo dõi", followers?.ifBlank { null })
            addInfo("👁", "Lượt xem", views)
            addInfo("🎞", "Tập mới", latestEps)
            addInfo("🏷", "Thể loại", genre?.ifBlank { null })

            description?.takeIf { it.isNotBlank() }?.let {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(it.trim())
            }
        }
    }

    private val blobInterceptor = """
;(function(){
var _oc=URL.createObjectURL;
URL.createObjectURL=function(b){
var u=_oc.apply(this,arguments);
try{if(b&&b.type&&b.type.indexOf('mpegurl')!==-1){
var r=new FileReader();
r.onload=function(e){try{Android.onM3U8(e.target.result);}catch(x){}};
r.readAsText(b);}}catch(x){}
return u;};
})();
""".trimIndent()

    private val fakeAds = """
window.adsbygoogle=window.adsbygoogle||[];
window.adsbygoogle.loaded=true;
window.adsbygoogle.push=function(){};
""".trimIndent()

    inner class M3U8Bridge {
        @Volatile var result: String? = null
        @Volatile var m3u8Url: String? = null

        @JavascriptInterface
        fun onM3U8(content: String) {
            if (content.contains("#EXTM3U")) result = content
        }

        // Called from JS XHR override when player fetches playlist
        @JavascriptInterface
        fun onPlaylistUrl(url: String) {
            if (url.contains("playlist.m3u8")) m3u8Url = url
        }
    }

    suspend fun prefetchAvsJs() {
        if (cachedAvsJs != null) return
        try {
            // Auto-detect player JS from page HTML first
            val pageHtml = try { app.get("$mainUrl/", headers = baseHeaders).text } catch(_: Exception) { "" }
            val detectedPath = Regex("""statics/default/js/((?:pl\.watchbk\d+|avs\.watch)\.js\?v=[0-9.]+)""")
                .find(pageHtml)?.groupValues?.get(1)
            val jsUrl = if (!detectedPath.isNullOrBlank())
                "$mainUrl/statics/default/js/$detectedPath"
            else
                "$mainUrl/statics/default/js/pl.watchbk2.js?v=6.1.9"
            val js = app.get(
                jsUrl,
                headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/", "Accept" to "*/*")
            ).text
            if (js.length > 500) cachedAvsJs = js
        } catch (_: Exception) {}
    }

    private suspend fun fetchJs(url: String, cookie: String): String? {
        // Try provided URL first
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to UA, "Referer" to "$mainUrl/",
                "Accept" to "*/*", "Cookie" to cookie
            ))
            if (resp.text.length > 500) return resp.text
        } catch (_: Exception) {}
        // Auto-detect from page HTML
        return try {
            val html = app.get("$mainUrl/", headers = baseHeaders).text
            val path = Regex("""statics/default/js/((?:pl\.watchbk\d+|avs\.watch)\.js\?v=[0-9.]+)""")
                .find(html)?.groupValues?.get(1) ?: return null
            val js = app.get("$mainUrl/statics/default/js/$path", headers = mapOf(
                "User-Agent" to UA, "Referer" to "$mainUrl/", "Cookie" to cookie
            )).text
            if (js.length > 500) js else null
        } catch (_: Exception) { null }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getM3U8(epUrl: String, targetUrl: String, cookie: String, avsJs: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context }
                    catch (_: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val bridge = M3U8Bridge()

                    android.webkit.CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        cookie.split(";").forEach { kv ->
                            val t = kv.trim()
                            if (t.isNotBlank()) setCookie(mainUrl, t)
                        }
                        flush()
                    }

                    val wv = WebView(ctx)
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = UA
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    android.webkit.CookieManager.getInstance()
                        .setAcceptThirdPartyCookies(wv, true)
                    wv.addJavascriptInterface(bridge, "Android")

                    val patchedAvsJs = blobInterceptor + "\n" + avsJs
                    val avsJsBytes = patchedAvsJs.toByteArray(Charsets.UTF_8)
                    val fakeAdsBytes = fakeAds.toByteArray(Charsets.UTF_8)

                    // JS to inject into player page to capture playlist URL
                    val xhrOverrideJs = """
(function(){
var origOpen = XMLHttpRequest.prototype.open;
XMLHttpRequest.prototype.open = function(method, url) {
  try {
    if (url && typeof url === 'string' && url.indexOf('playlist.m3u8') !== -1) {
      Android.onPlaylistUrl(url);
    }
  } catch(e) {}
  return origOpen.apply(this, arguments);
};
var origFetch = window.fetch;
if (origFetch) {
  window.fetch = function(url, opts) {
    try {
      if (url && typeof url === 'string' && url.indexOf('playlist.m3u8') !== -1) {
        Android.onPlaylistUrl(url);
      }
    } catch(e) {}
    return origFetch.apply(this, arguments);
  };
}
})();
""".trimIndent()

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            view.evaluateJavascript(xhrOverrideJs, null)
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(xhrOverrideJs, null)
                        }
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            return when {
                                url.contains("watchbk") || url.contains("avs.watch") -> WebResourceResponse(
                                    "application/javascript", "utf-8",
                                    ByteArrayInputStream(avsJsBytes)
                                )
                                // Also capture via shouldInterceptRequest as backup
                                url.contains("googleapiscdn.com/playlist/") && url.contains(".m3u8") -> {
                                    bridge.m3u8Url = url
                                    null
                                }
                                url.contains("adsbygoogle") ||
                                        url.contains("googlesyndication") -> WebResourceResponse(
                                    "application/javascript", "utf-8",
                                    ByteArrayInputStream(fakeAdsBytes)
                                )
                                url.contains("google-analytics") ||
                                        url.contains("doubleclick") ||
                                        url.contains("googletagmanager") ||
                                        url.contains("facebook.com") ||
                                        url.contains("hotjar") ||
                                        url.contains("disqus") -> WebResourceResponse(
                                    "application/javascript", "utf-8",
                                    ByteArrayInputStream("".toByteArray())
                                )
                                url.endsWith(".woff") || url.endsWith(".woff2") ||
                                        url.endsWith(".ttf") || url.endsWith(".eot") ||
                                        (url.endsWith(".css") && !url.contains(mainUrl)) -> WebResourceResponse(
                                    "text/css", "utf-8",
                                    ByteArrayInputStream("".toByteArray())
                                )
                                url.endsWith(".png") || url.endsWith(".jpg") ||
                                        url.endsWith(".jpeg") || url.endsWith(".gif") ||
                                        url.endsWith(".webp") || url.endsWith(".svg") -> WebResourceResponse(
                                    "image/png", "utf-8",
                                    ByteArrayInputStream("".toByteArray())
                                )
                                else -> null
                            }
                        }
                    }

                    // Load target: iframe URL directly (or episode page as fallback)
                    // Set Referer to episode page so player thinks it's inside the episode
                    val loadHeaders = if (targetUrl != epUrl) {
                        mapOf("Accept-Language" to "vi-VN,vi;q=0.9", "Referer" to epUrl)
                    } else {
                        mapOf("Accept-Language" to "vi-VN,vi;q=0.9", "Referer" to "$mainUrl/")
                    }
                    wv.loadUrl(targetUrl, loadHeaders)

                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    var elapsed = 0
                    val checker = object : Runnable {
                        override fun run() {
                            // Priority: direct URL > blob content
                            val directUrl = bridge.m3u8Url
                            val blobContent = bridge.result
                            when {
                                directUrl != null -> {
                                    wv.stopLoading(); wv.destroy()
                                    // Return special marker so caller knows it's a URL
                                    if (cont.isActive) cont.resume("DIRECT_URL::$directUrl")
                                }
                                blobContent != null -> {
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume(blobContent)
                                }
                                elapsed >= 25_000 -> {
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume(null)
                                }
                                else -> {
                                    elapsed += 200
                                    handler.postDelayed(this, 200)
                                }
                            }
                        }
                    }
                    handler.postDelayed(checker, 800)
                    cont.invokeOnCancellation {
                        handler.removeCallbacks(checker)
                        wv.stopLoading(); wv.destroy()
                    }
                }
            }
        }
    }

    private var localServer: PlaylistProxyServer? = null

    inner class PlaylistProxyServer(
        private val playlistContent: String,
        private val playlistUrl: String,   // full playlist URL to resolve relative segs
        private val playerUrl: String,     // player URL used as Referer
        private val cookie: String         // cf_clearance cookie
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        val port: Int get() = serverSocket?.localPort ?: 0
        private val pool = java.util.concurrent.Executors.newCachedThreadPool()

        // Base URL for resolving relative segment URIs
        private val playlistBase: String = playlistUrl.substringBeforeLast("/") + "/"

        fun start() {
            serverSocket = java.net.ServerSocket(0)
            Thread {
                val ss = serverSocket ?: return@Thread
                while (!ss.isClosed) {
                    try { val c = ss.accept(); pool.execute { handle(c) } }
                    catch (_: Exception) { break }
                }
            }.also { it.isDaemon = true }.start()
        }

        private fun resolveSegUrl(line: String): String {
            val l = line.trim()
            return when {
                l.startsWith("http://") || l.startsWith("https://") -> l
                else -> playlistBase + l  // relative URI
            }
        }

        private fun handle(client: java.net.Socket) {
            try {
                val reader = client.getInputStream().bufferedReader()
                val firstLine = reader.readLine() ?: ""
                // drain headers
                var h = reader.readLine()
                while (!h.isNullOrBlank()) { h = reader.readLine() }

                val path = firstLine.split(" ").getOrNull(1) ?: "/"
                val crlf = "\r\n"
                val out = client.getOutputStream()

                if (path == "/playlist.m3u8") {
                    val base = "http://127.0.0.1:$port"
                    val rewritten = playlistContent.lines().joinToString("\n") { l ->
                        val trimmed = l.trim()
                        when {
                            // Skip comments and directives
                            trimmed.startsWith("#") || trimmed.isEmpty() -> l
                            // All non-comment lines after #EXTINF are segment URIs
                            else -> {
                                val absUrl = resolveSegUrl(trimmed)
                                "$base/seg?url=${java.net.URLEncoder.encode(absUrl, "UTF-8")}"
                            }
                        }
                    }.toByteArray(Charsets.UTF_8)
                    out.write("HTTP/1.1 200 OK${crlf}Content-Type: application/vnd.apple.mpegurl${crlf}Content-Length: ${rewritten.size}${crlf}Access-Control-Allow-Origin: *${crlf}${crlf}".toByteArray())
                    out.write(rewritten)
                } else if (path.startsWith("/seg?url=")) {
                    val segUrl = java.net.URLDecoder.decode(path.removePrefix("/seg?url="), "UTF-8")
                    try {
                        val conn = java.net.URL(segUrl).openConnection() as java.net.HttpURLConnection
                        conn.instanceFollowRedirects = true
                        conn.connectTimeout = 15000
                        conn.readTimeout = 30000
                        conn.setRequestProperty("User-Agent", UA)
                        // Correct Referer: player URL (matching what browser sends)
                        conn.setRequestProperty("Referer", playerUrl)
                        conn.setRequestProperty("Origin", "https://stream.googleapiscdn.com")
                        if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
                        conn.connect()
                        val bytes = conn.inputStream.readBytes()
                        conn.disconnect()
                        out.write("HTTP/1.1 200 OK${crlf}Content-Type: video/mp2t${crlf}Content-Length: ${bytes.size}${crlf}Access-Control-Allow-Origin: *${crlf}${crlf}".toByteArray())
                        out.write(bytes)
                    } catch (_: Exception) {
                        out.write("HTTP/1.1 502 Bad Gateway${crlf}${crlf}".toByteArray())
                    }
                } else {
                    out.write("HTTP/1.1 404 Not Found${crlf}${crlf}".toByteArray())
                }
                out.flush()
                client.close()
            } catch (_: Exception) { try { client.close() } catch (_: Exception) {} }
        }

        fun stop() {
            try { serverSocket?.close() } catch (_: Exception) {}
            try { pool.shutdownNow() } catch (_: Exception) {}
        }
    }

    data class PlaylistResult(val url: String, val cookie: String)

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private suspend fun capturePlaylist(playerUrl: String, referer: String): PlaylistResult? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (_: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)

                    val wv = android.webkit.WebView(ctx)
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = UA
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        mediaPlaybackRequiresUserGesture = false
                    }
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                    var done = false
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())

                    fun finish(result: PlaylistResult?) {
                        if (done) return
                        done = true
                        handler.removeCallbacksAndMessages(null)
                        handler.post { try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {} }
                        if (cont.isActive) cont.resume(result)
                    }

                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: android.webkit.WebView,
                            request: android.webkit.WebResourceRequest
                        ): android.webkit.WebResourceResponse? {
                            val url = request.url.toString()
                            // Catch ANY request to googleapiscdn.com that has m3u8 and token
                            if (!done && url.contains("googleapiscdn.com") &&
                                url.contains(".m3u8") && url.contains("token=")) {
                                // Get cookies while still on WebView thread
                                val cookie = android.webkit.CookieManager.getInstance()
                                    .getCookie(request.url.host) ?: ""
                                finish(PlaylistResult(url, cookie))
                            }
                            return null
                        }
                    }

                    wv.loadUrl(playerUrl, mapOf(
                        "Referer" to referer,
                        "Accept-Language" to "vi-VN,vi;q=0.9"
                    ))

                    handler.postDelayed({ finish(null) }, 28_000)

                    cont.invokeOnCancellation { finish(null) }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("|")

        val epHtml = try { app.get(epUrl, headers = baseHeaders, interceptor = cfInterceptor).text }
                     catch (_: Exception) { return true }

        // iframe is on either stream. or storage. googleapiscdn.com
        val playerUrl =
            Regex("""src=["'](https://(?:stream|storage)\.googleapiscdn\.com/player/[a-fA-F0-9]{20,}[^"'<>]*)["']""")
                .find(epHtml)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?: run {
                val hash = Regex("""var\s+_epHash\s*=\s*['"]([A-Za-z0-9_\-]{20,})['"]""")
                    .find(epHtml)?.groupValues?.get(1) ?: return true
                try {
                    val resp = app.post("$mainUrl/ajax/player",
                        headers = mapOf("User-Agent" to UA, "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "Referer" to epUrl, "Origin" to mainUrl),
                        data = mapOf("link" to hash, "play" to "api", "id" to "0", "backuplinks" to "1")
                    ).text
                    Regex(""""link"\s*:\s*"([^"]+)"""").find(resp)
                        ?.groupValues?.get(1)
                } catch (_: Exception) { null }
            } ?: return true

        // Extract base domain from player URL (stream. or storage.)
        val playerDomain = Regex("""(https://(?:stream|storage)\.googleapiscdn\.com)""")
            .find(playerUrl)?.groupValues?.get(1) ?: "https://storage.googleapiscdn.com"

        // Hash in player URL = playlist ID
        val videoHash = Regex("""/player/([a-fA-F0-9]{20,})""")
            .find(playerUrl)?.groupValues?.get(1) ?: return true

        // GET player page via cfInterceptor — bypasses Cloudflare, returns HTML with var avsToken
        val cfInt = com.lagradost.cloudstream3.network.WebViewResolver(
            Regex("""googleapiscdn\.com""")
        )
        val playerHtml = try {
            app.get(playerUrl, headers = mapOf(
                "User-Agent" to UA,
                "Referer"    to epUrl,
                "Accept"     to "text/html,*/*",
                "Accept-Language" to "vi-VN,vi;q=0.9"
            ), interceptor = cfInt).text
        } catch (_: Exception) { return true }

        val avsToken = Regex("""avsToken\s*=\s*["']([A-Za-z0-9._\-]{20,})["']""")
            .find(playerHtml)?.groupValues?.get(1) ?: return true

        // Playlist URL uses same domain as player
        val playlistUrl = "$playerDomain/playlist/$videoHash/playlist.m3u8?token=$avsToken"

        // Pass directly to ExoPlayer with correct headers
        // Segments are plain URLs (no redirect, no .html token) — ExoPlayer handles natively
        callback(newExtractorLink(
            source = name,
            name   = "$name - DU",
            url    = playlistUrl,
            type   = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf(
                "User-Agent" to UA,
                "Referer"    to playerUrl,
                "Origin"     to playerDomain
            )
        })
        return true
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private suspend fun capturePlaylistUrl(iframeUrl: String, referer: String, cookie: String): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (_: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    // Sync cookie
                    android.webkit.CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        cookie.split(";").forEach { kv ->
                            val t = kv.trim()
                            if (t.isNotBlank()) {
                                setCookie("https://storage.googleapiscdn.com", t)
                                setCookie(mainUrl, t)
                            }
                        }
                        flush()
                    }

                    val wv = android.webkit.WebView(ctx)
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = UA
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: android.webkit.WebView,
                            request: android.webkit.WebResourceRequest
                        ): android.webkit.WebResourceResponse? {
                            val url = request.url.toString()
                            // Capture the playlist.m3u8 request with token
                            if (url.contains("storage.googleapiscdn.com") &&
                                url.contains("playlist.m3u8") &&
                                url.contains("token=")) {
                                if (cont.isActive) cont.resume(url)
                            }
                            return null
                        }
                    }

                    wv.loadUrl(iframeUrl, mapOf(
                        "Referer" to referer,
                        "Accept-Language" to "vi-VN,vi;q=0.9"
                    ))

                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    var elapsed = 0
                    val checker = object : Runnable {
                        override fun run() {
                            if (elapsed >= 28_000) {
                                wv.stopLoading(); wv.destroy()
                                if (cont.isActive) cont.resume(null)
                                return
                            }
                            elapsed += 300
                            handler.postDelayed(this, 300)
                        }
                    }
                    handler.postDelayed(checker, 500)
                    cont.invokeOnCancellation {
                        handler.removeCallbacks(checker)
                        wv.stopLoading(); wv.destroy()
                    }
                }
            }
        }
    }

    private suspend fun servePlaylistViaProxy(
        playlistText: String,
        playlistUrl: String,
        playerUrl: String,
        cookie: String,
        callback: (ExtractorLink) -> Unit
    ) {
        localServer?.stop()
        val server = PlaylistProxyServer(playlistText, playlistUrl, playerUrl, cookie)
        server.start()
        localServer = server
        callback(newExtractorLink(
            source = name, name = "$name - DU",
            url = "http://127.0.0.1:${server.port}/playlist.m3u8",
            type = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf("User-Agent" to UA)
        })
    }
}