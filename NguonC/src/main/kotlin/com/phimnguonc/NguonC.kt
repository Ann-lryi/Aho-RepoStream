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

    private data class CachedDoc(val doc: Document, val time: Long)

    private val docCache = ConcurrentHashMap<String, CachedDoc>()
    private val DOC_CACHE_TTL_MS = 5 * 60 * 1000L

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

    private suspend inline fun <reified T : Any> fetchApi(url: String): T? {
        try {
            val res = app.get(url, headers = commonHeaders).parsedSafe<T>()
            if (res != null) return res
        } catch (_: Exception) {}
        for (proxy in corsProxies) {
            if (proxy.isEmpty()) continue
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

        val cachedCookies = cfCookieCache[mainUrl]
        if (cachedCookies != null) {
            tryPlain(cachedCookies)?.let { return it }
        } else {
            tryPlain(null)?.let { return it }
        }

        for (proxy in corsProxies) {
            if (proxy.isEmpty()) continue
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

        return cfMutex.withLock {
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
        "${API_PREFIX}api/films/the-loai/hoat-hinh"       to "Anime~H.hình",
        "${API_PREFIX}api/films/danh-sach/phim-le"        to "Phim L\u1EBB",
        "${API_PREFIX}api/films/danh-sach/phim-bo"        to "Phim B\u1ED9"
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
            val doc   = fetchListingDoc(url)
            val items = doc.select("table tbody tr").mapNotNull { parseCard(it) }
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = URLEncoder.encode(query, "utf-8")

        val res = fetchApi<NguonCApiResponse>("$mainUrl/api/films/search?keyword=$keyword")

        if (!res?.items.isNullOrEmpty())
            return res!!.items!!.mapNotNull { parseApiItem(it) }

        val legacyRes = fetchApi<NguonCApiResponse>("$mainUrl/api/films?keyword=$keyword")
        return legacyRes?.items?.mapNotNull { parseApiItem(it) } ?: emptyList()
    }

    private fun cleanServerName(raw: String?, fallbackIdx: Int = 0): String {
        val fallback = when (fallbackIdx) {
            0    -> "Vietsub"
            1    -> "Thuyết Minh"
            else -> "Server ${fallbackIdx + 1}"
        }
        if (raw.isNullOrBlank()) return fallback

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

        var name = raw.trim()
        name = name.replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("""^[a-z0-9.-]+\.[a-z]{2,}/+""", RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("""\.(m3u8|m3u9|php|html?)(\?[^ ]*)?""", RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("""\s*#\d+\s*$"""), "")
        name = name.replace(Regex("""\s+(1080p|720p|480p|2160p|4k|hd|fhd|uhd|bluray|webrip)\s*$""", RegexOption.IGNORE_CASE), "")
        name = name.trim(' ', '-', '_', '|', '#', ':', '/', '\\', '.', ',', '(', ')', '[', ']')
        name = name.replace(Regex("\\s+"), " ").trim()

        if (name.isBlank()) return fallback
        return name.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    private fun normalizeEpName(raw: String?): String {
        if (raw.isNullOrBlank()) return "0"
        val s = raw.trim()
        val numMatch = Regex("""(\d+)""").find(s)
        if (numMatch != null) return numMatch.groupValues[1].trimStart('0').ifBlank { "0" }
        return s
    }

    override suspend fun load(url: String): LoadResponse {
        val slug  = url.trim().trimEnd('/').substringAfterLast("/")
        val res   = fetchApi<NguonCDetailResponse>("$mainUrl/api/film/$slug")
        val movie = res?.movie ?: throw ErrorLoadingException("Kh\u00F4ng th\u1EC3 t\u1EA3i d\u1EEF li\u1EC7u phim")

        val epMap = linkedMapOf<String, MutableList<Pair<String, String>>>()
        movie.episodes?.forEachIndexed { idx, server ->
            val serverName = cleanServerName(server.server_name ?: server.name, idx)
            val items = server.items ?: server.list
            items?.forEach { ep ->
                val epName = normalizeEpName(ep.name)
                val embed = ep.embed?.replace("\\/", "/")?.trim() ?: ""
                val directM3u8 = ep.m3u8?.replace("\\/", "/")?.trim() ?: ""

                val url2 = when {
                    embed.isNotBlank() && embed.contains("hash=") -> embed
                    directM3u8.isNotBlank()                       -> directM3u8
                    embed.isNotBlank()                            -> embed
                    else                                          -> return@forEach
                }

                val list = epMap.getOrPut(epName) { mutableListOf() }
                if (list.none { it.second == url2 }) {
                    list.add(serverName to url2)
                }
            }
        }

        if (epMap.isEmpty()) throw ErrorLoadingException("Kh\u00F4ng t\u00ECm th\u1EA5y t\u1EADp phim")

        val episodes = epMap.map { (epName, embeds) ->
            newEpisode(embeds.joinToString("|") { (sn, u) -> "$sn::$u" }) {
                this.name    = "T\u1EADp $epName"
                this.episode = epName.toIntOrNull()
            }
        }.sortedBy { it.episode ?: 0 }

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

    private val activeServers = mutableListOf<NguonCProxyServer>()

    private val segProfileByHost = ConcurrentHashMap<String, Int>()

    private val NG_BUILD = "v14"
    private val SEG_WEBVIEW_FALLBACK = false
    private val PREFETCH_AHEAD = 3
    private val EMIT_DIRECT_LINK = false
    private val STREAMC_DIRECT_FLOW = false
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
                if (c > 160 && c % 50 != 0) return
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

    private class SegJob {
        val out = java.io.ByteArrayOutputStream()
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var status = 0
        @Volatile var err: String? = null
    }

    private val segWebFetchers = LinkedHashMap<String, WebSegFetcher>()
    private val segHostWeb = ConcurrentHashMap<String, Boolean>()
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

            val urls = content.lines().map { it.trim() }
                .filter { !it.startsWith("#") && it.contains("/seg/") }
                .map { java.net.URLDecoder.decode(it.substringAfter("/seg/"), "UTF-8") }
            segOrder = urls
            segIndex = urls.withIndex().associate { it.value to it.index }
        }
        private fun getM3U8(): String = _m3u8

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

                        fun viaOkHttp(variant: Int): Boolean {
                            val ref = if (variant == 1) segReferer else "$segOrigin/"
                            val rb = okhttp3.Request.Builder().url(segUrl)
                                .header("User-Agent", USER_AGENT)
                                .header("Referer", ref)
                                .header("Accept", "*/*")
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
                                    if (headersSent) return true
                                }
                            }
                            return false
                        }

                        fun viaWeb(): Boolean {
                            val f = wf ?: return false
                            val b = f.fetch(segUrl, 30_000L)
                            if (b == null || b.isEmpty()) return false
                            serveBytes(b)
                            return true
                        }

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
            else -> {
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

    private val segClient: okhttp3.OkHttpClient by lazy { app.baseClient.newBuilder().dns(segDns).build() }

    private val segTrimByHost = ConcurrentHashMap<String, Pair<Int, Long>>()

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

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun padBase64(s: String): String {
        val padNeeded = (4 - s.length % 4) % 4
        return s + "=".repeat(padNeeded)
    }

    private fun toUrlSafeBase64(token: String): String {
        return token.replace('+', '-').replace('/', '_').trimEnd('=')
    }

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

    private fun extractObfData(html: String): Triple<String?, String?, String?>? {
        val obfBase64 = Regex("""data-obf="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""data-obf='([^']+)'""").find(html)?.groupValues?.getOrNull(1)
            ?: return null

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

                if (sUb == null && hD == null && kX == null) {
                    val allValues = Regex(""""(\w+)"\s*:\s*"([A-Za-z0-9_+/=-]{20,})"""").findAll(obfJson).toList()
                    for (match in allValues) {
                        val key = match.groupValues[1]
                        val value = match.groupValues[2]
                        nlog("[NguonC] data-obf found field: $key = ${value.take(20)}...")
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

    private fun findTokenInHTML(html: String): String? {
        val patterns = listOf(
            Regex(""""file"\s*:\s*"([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)""""),
            Regex("""file\s*[:=]\s*["']([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)["']"""),
            Regex("""["']([A-Za-z0-9_+/=\\-]{20,})\.(m3u8|m3u9)["']"""),
            Regex("""(?:var|let|const)\s+\w+\s*=\s*["']([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)["']"""),
            Regex("""(?:src|href)\s*=\s*["']([^"']*?(m3u8|m3u9))["']"""),
            Regex(""""file"\s*:\s*"([^"]+\.(m3u8|m3u9))""""),
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

    private suspend fun findEncryptionKey(html: String, embedDomain: String, referer: String): String? {
        val obfData = extractObfData(html)
        if (obfData != null && obfData.third != null) {
            nlog("[NguonC] Found kX from data-obf: ${obfData.third}")
            return obfData.third
        }

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

        for (jsName in listOf("player1", "debug", "player", "config", "app")) {
            val jsMatch = Regex("""src=["']([^"']*${jsName}[^"']*\.js[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
            if (jsMatch != null) {
                val jsPath = jsMatch.groupValues[1]
                val jsUrl = if (jsPath.startsWith("http")) jsPath else "$embedDomain/$jsPath"
                try {
                    val jsContent = app.get(jsUrl, headers = mapOf(
                        "Referer" to referer, "User-Agent" to USER_AGENT
                    )).text
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

    private suspend fun extractTokenFromJS(html: String, embedDomain: String, referer: String, urlHash: String?): Pair<String?, String?>? {
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

                val b64Matches = Regex("""["']([A-Za-z0-9_+/=-]{40,})["']""").findAll(jsContent)
                for (match in b64Matches) {
                    val candidate = match.groupValues[1]
                    try {
                        val decoded = String(Base64.decode(padBase64(candidate.replace('-', '+').replace('_', '/')), Base64.DEFAULT), Charsets.UTF_8)
                        if (decoded.contains("\"h\"") && decoded.contains("\"t\"")) {
                            nlog("[NguonC] Found token in JS: ${candidate.take(20)}... (decoded: ${decoded.take(50)})")
                            val kXMatch = Regex("""(?:kX|key|aesKey)\s*[:=]\s*["']([a-fA-F0-9]{32,})["']""").find(jsContent)
                            return Pair(candidate, kXMatch?.groupValues?.get(1))
                        }
                    } catch (_: Exception) {}
                }

                val urlPattern = Regex("""["']/([A-Za-z0-9_+/=-]+)\.(m3u8|m3u9)["']""").find(jsContent)
                if (urlPattern != null) {
                    val tokenStr = urlPattern.groupValues[1]
                    if (tokenStr.length > 20) {
                        nlog("[NguonC] Found token-like URL in JS: ${tokenStr.take(20)}...")
                        val kXMatch = Regex("""(?:kX|key|aesKey)\s*[:=]\s*["']([a-fA-F0-9]{32,})["']""").find(jsContent)
                        return Pair(tokenStr, kXMatch?.groupValues?.get(1))
                    }
                }

                val tokenConstruct = Regex("""(?:btoa|atob|Base64)\s*\(\s*(?:JSON\.stringify\s*\(\s*\{[^}]*"h"[^}]*\})""").find(jsContent)
                if (tokenConstruct != null) {
                    nlog("[NguonC] Found token construction in JS: ${tokenConstruct.value.take(60)}...")
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun decryptStreamcM3u8(content: String, kX: String, token: String? = null): String? {
        try {
            val ivMatch = Regex("""#ENC-AESGCM;iv=([a-f0-9A-F]+)""").find(content) ?: return null
            val ivHex = ivMatch.groupValues[1]
            val iv = hexToBytes(ivHex)
            nlog("[NguonC] Decrypt: iv=${ivHex.take(16)}... (${ivHex.length}hex), kX=$kX")

            val b65Match = Regex("""#EXT-X-B65:(\d+)(?:-(\d+))?""").find(content)
            val offset = b65Match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val length = b65Match?.groupValues?.get(2)?.toIntOrNull() ?: 0

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

            val keyCandidates = mutableListOf<Pair<String, ByteArray>>()

            val kxUtf8 = kX.toByteArray(Charsets.UTF_8)
            if (kxUtf8.size == 16 || kxUtf8.size == 32) keyCandidates.add(Pair("kX-utf8", kxUtf8))
            if (kxUtf8.size > 32) keyCandidates.add(Pair("kX-utf8-first32", kxUtf8.copyOf(32)))
            if (kxUtf8.size > 16) keyCandidates.add(Pair("kX-utf8-first16", kxUtf8.copyOf(16)))

            if (kX.length >= 32 && kX.length % 2 == 0 && kX.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                try {
                    val hexBytes = hexToBytes(kX)
                    if (hexBytes.size == 16 || hexBytes.size == 32) keyCandidates.add(Pair("kX-hexBytes", hexBytes))
                } catch (_: Exception) {}
            }

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
                val tokenUtf8 = token.toByteArray(Charsets.UTF_8)
                if (tokenUtf8.size == 16 || tokenUtf8.size == 32) keyCandidates.add(Pair("token-utf8", tokenUtf8))
                if (tokenUtf8.size > 16) keyCandidates.add(Pair("token-utf8-first16", tokenUtf8.copyOf(16)))
                if (tokenUtf8.size > 32) keyCandidates.add(Pair("token-utf8-first32", tokenUtf8.copyOf(32)))
            }

            if (kX.length in 17..31) {
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

            if (iv.size != 12) {
                nlog("[NguonC] IV size is ${iv.size} bytes (expected 12), trying standard size...")
                for ((desc, keyBytes) in keyCandidates) {
                    if (keyBytes.size != 16 && keyBytes.size != 32) continue
                    try {
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

    private suspend fun fetchEmbedHTML(embedUrl: String, embedDomain: String): String? {
        try {
            val resp = app.get(embedUrl, headers = mapOf(
                "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ))
            val html = resp.text
            if (html.contains("data-obf") || html.contains("jwplayer") || html.contains("player1") ||
                html.contains("streamc") || html.contains("embed") || html.contains("<video") ||
                html.contains("m3u8") || html.contains("m3u9")) {
                nlog("[NguonC] Direct HTML OK: ${html.length} chars")
                return html
            }
            if (html.length > 500 && html.contains("<")) {
                nlog("[NguonC] Direct HTML (no markers but substantial): ${html.take(200)}")
                return html
            }
            nlog("[NguonC] Direct HTML not embed page: ${html.take(100)}")
        } catch (e: Exception) {
            nlog("[NguonC] Direct HTML failed: ${e.message}")
        }

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

    private suspend fun fetchDirect(url: String, referer: String, origin: String): String? {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "*/*"
        )
        try {
            val resp = app.get(url, headers = headers)
            val content = resp.text
            if (content.contains("#EXTM3U") || content.contains("#ENC-AESGCM")) {
                nlog("[NguonC] Direct fetch OK: ${url.take(60)}")
                return content
            }
            nlog("[NguonC] Direct fetch response: ${content.take(80)}")
        } catch (_: Exception) {}
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

    private suspend fun tryFetchWithToken(
        token: String, embedDomain: String, referer: String,
        kX: String?, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urlVariations = mutableListOf<Pair<String, String>>()

        val urlSafeToken = toUrlSafeBase64(token)
        urlVariations.add(Pair(urlSafeToken, "urlSafe"))

        if (token != urlSafeToken) {
            urlVariations.add(Pair(URLEncoder.encode(token, "UTF-8"), "urlEncoded"))
        }

        if (token != urlSafeToken) {
            urlVariations.add(Pair(token, "original"))
        }

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
                    if (m3u8Content.contains("#EXTM3U") && !m3u8Content.contains("#ENC-AESGCM")) {
                        nlog("[NguonC] Got plain m3u8 with $desc + $ext!")
                        val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
                        if (registerM3U8Link(m3u8Content, referer, m3u8Base, serverName, callback)) {
                            return true
                        }
                    }
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

    private suspend fun tryMobileM3U8Flow(
        embedUrl: String,
        embedDomain: String,
        token: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val accessTokenPath = token

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
            val altUrl = "$embedDomain/$accessTokenPath.m3u8?xat=$xat"
            nlog("[NguonC] [MobileFlow] Trying .m3u8: $altUrl")
            val altContent = try { app.get(altUrl, headers = getHeaders).text } catch (_: Exception) { null }
            if (altContent.isNullOrBlank() || !altContent.contains("#EXTM3U")) {
                nlog("[NguonC] [MobileFlow] .m3u8 also failed")
                return false
            }
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
        return registerM3U8Link(m3u8Content, embedUrl, "", serverName, callback)
    }

    private val streamcMobileUA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    private val streamcHttpMutex = Mutex()

    private val streamcPlaylistCache =
        java.util.concurrent.ConcurrentHashMap<String, Triple<Long, String, String>>()

    private val streamcCfBlockedUntil =
        java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun cfKey(domain: String): String {
        val host = domain.removePrefix("https://").removePrefix("http://").substringBefore("/")
        val parts = host.split(".")
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    private val nextEpisodeRaw = ConcurrentHashMap<String, String>()
    private val prewarmRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun rawEpisodeData(d: String): String =
        if (d.startsWith("$mainUrl/") && d.contains("::")) d.removePrefix("$mainUrl/") else d

    private fun prewarmNext(rawData: String) {
        val next = nextEpisodeRaw[rawData] ?: return
        if (!prewarmRunning.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
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
        streamcPlaylistCache[hash] =
            Triple(System.currentTimeMillis() + 6L * 3_600_000L, url, rawBody)
    }

    private fun streamcJsonString(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json) ?: return null
        return m.groupValues[1].replace("\\/", "/")
    }

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

    private suspend fun tryStreamcDirectFlow(
        embedUrl: String, embedDomain: String,
        serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean = streamcHttpMutex.withLock {
        try {
            if (!STREAMC_DIRECT_FLOW) return@withLock false
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
            if (turnstile) return@withLock false

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
            val directHash = Regex("""[?&]hash=([a-fA-F0-9]{8,})""").find(embedUrl)?.groupValues?.get(1)
            streamcRegisterCaptured(playlist, content, directHash, embedUrl, serverName, callback)
        } catch (e: Exception) {
            nlog("[NguonC] [StreamcNew] direct flow error: ${e.message}")
            false
        }
    }

    private suspend fun tryStreamcWebViewFlow(
        targetUrl: String, embedDomain: String,
        serverName: String, callback: (ExtractorLink) -> Unit,
        captureOnly: Boolean = false
    ): Boolean {
        val flowT0 = System.currentTimeMillis()
        var pageStartedLogs = 0
        var webView: WebView? = null
        var dialog: android.app.Dialog? = null
        var fetcher: WebSegFetcher? = null
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
                val segF = WebSegFetcher(wv, Regex("""^(https?://[^/]+)""").find(targetUrl)?.groupValues?.get(1) ?: embedDomain)
                fetcher = segF
                wv.addJavascriptInterface(segF, "__ngSeg")
                wv.addJavascriptInterface(
                    StreamcCaptureBridge { url, body ->
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
                        dlg.setOnDismissListener { captures.close() }
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
                issuePlaylistUrl.get()?.let { pUrl ->
                    nlog("[NguonC] [StreamcNew] issue gave playlist, fetching manually: ${pUrl.take(70)}")
                    val manual = streamcGet(pUrl, embedDomain, targetUrl)
                    if (manual != null) {
                        registered = streamcRegisterCaptured(pUrl, manual, videoHash, targetUrl, serverName, callback)
                    }
                }
            }

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

    private class StreamcCaptureBridge(
        private val handler: (String, String) -> Unit
    ) {
        @JavascriptInterface
        fun onCapture(url: String?, body: String?) {
            if (url.isNullOrBlank() || body.isNullOrBlank()) return
            try { handler(url, body) } catch (_: Throwable) {}
        }
    }

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

    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        val rawData = if (data.startsWith("$mainUrl/") && data.contains("::")) data.removePrefix("$mainUrl/") else data
        val embedEntries = rawData.split("|").mapNotNull { entry ->
            val parts = entry.trim().split("::", limit = 2)
            if (parts.size == 2) Pair(cleanServerName(parts[0]), parts[1])
            else if (parts.size == 1 && parts[0].startsWith("http")) Pair("Vietsub", parts[0])
            else null
        }.distinctBy { it.second }
        var linkFound = false
        nlog("[NguonC] loadLinks: entries=${embedEntries.size} urls=${embedEntries.joinToString(" | ") { it.first + "=" + it.second.take(90) }}")
        ntoastV("NguonC $NG_BUILD: ${embedEntries.size} nguồn, " + embedEntries.joinToString(",") {
            if (it.second.contains("hash=")) "embed+hash" else if (it.second.contains(".m3u")) "m3u8" else "khác"
        })

        coroutineScope {
            embedEntries.map { (serverName, url) ->
                async {
                    var targetUrl = url

                    if (url.contains("sing.phimmoi.net")) {
                        val hash = Regex("""/([^/]+)/hls\.m3u8""").find(url)?.groupValues?.get(1)
                        if (hash != null) {
                            targetUrl = "https://embed15.streamc.xyz/embed.php?hash=$hash"
                        } else return@async
                    }

                    val embedDomain: String = Regex("""(https?://embed\d+\.streamc\.xyz)""").find(targetUrl)?.groupValues?.getOrNull(1)
                        ?: Regex("""(https?://[^/]+)""").find(targetUrl)?.groupValues?.getOrNull(1)
                        ?: "https://embed15.streamc.xyz"

                    val urlHash = Regex("""[?&]hash=([a-fA-F0-9]+)""").find(targetUrl)?.groupValues?.getOrNull(1)

                    try {

                        if (targetUrl.contains("streamc.xyz") || targetUrl.contains("phimmoi.net")) {
                            nlog("[NguonC] === Processing: ${targetUrl.take(80)} ===")

                            if (!linkFound) {
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

                            val html = fetchEmbedHTML(targetUrl, embedDomain)

                            if (html != null) {
                                val obfData = extractObfData(html)
                                if (obfData != null) {
                                    token = obfData.first
                                    kX = obfData.third
                                    if (token == null && obfData.second != null) {
                                        nlog("[NguonC] sUb is null, trying hD as token")
                                    }
                                    nlog("[NguonC] From data-obf: token=${token?.take(20)}... kX=${kX?.take(20)}... hD=${obfData.second?.take(20)}...")
                                }

                                if (token == null) {
                                    token = findTokenInHTML(html)
                                    nlog("[NguonC] Fallback token from HTML: ${token?.take(20)}...")
                                }

                                if (token == null) {
                                    val fullUrlMatch = Regex("""https?://embed\d+\.streamc\.xyz/([A-Za-z0-9_+/=\\-]+)\.(m3u8|m3u9)""").find(html)
                                    if (fullUrlMatch != null) {
                                        token = fullUrlMatch.groupValues[1]
                                        nlog("[NguonC] Found token from full URL in HTML: ${token.take(20)}...")
                                    }
                                }

                                if (token == null) {
                                    val pathMatch = Regex("""/([A-Za-z0-9_+/=\\-]{20,})\.(m3u8|m3u9)""").find(html)
                                    if (pathMatch != null) {
                                        token = pathMatch.groupValues[1]
                                        nlog("[NguonC] Found token from path in HTML: ${token.take(20)}...")
                                    }
                                }

                                if (kX == null) {
                                    kX = findEncryptionKey(html, embedDomain, targetUrl)
                                }

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

                                if (token == null && obfData != null && obfData.second != null) {
                                    val hD = obfData.second
                                    nlog("[NguonC] Trying hD as token: ${hD?.take(20)}...")
                                    token = hD
                                }
                            }

                            if (token != null) {
                                nlog("[NguonC] Step 3.5: Mobile flow (POST + .m3u9?xat=)")
                                if (tryMobileM3U8Flow(targetUrl, embedDomain, token, serverName, callback)) {
                                    linkFound = true; return@async
                                }
                            }

                            if (token != null) {
                                if (tryFetchWithToken(token, embedDomain, targetUrl, kX, serverName, callback)) {
                                    linkFound = true; return@async
                                }
                            }

                            if (html == null && token == null && urlHash == null) {
                                nlog("[NguonC] Embed page unreachable + no token + no urlHash — bailing out to avoid 'website not found'")
                                return@async
                            }
                            val skipWebViewFallbacks = (html == null && token == null && urlHash != null)
                            if (skipWebViewFallbacks) {
                                nlog("[NguonC] Embed page unreachable but have urlHash — skipping WebView fallbacks, going to Step 7 (alt domains)")
                            }

                            if (!linkFound && !skipWebViewFallbacks) {
                                nlog("[NguonC] Step 5: WebView interception for m3u8/m3u9")
                                try {
                                    val resp = app.get(targetUrl, interceptor = m3u8Interceptor, headers = mapOf(
                                        "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                                    ))
                                    val content = resp.text
                                    val capturedUrl = resp.url ?: ""

                                    if (content.contains("#EXTM3U") && !content.contains("#ENC-AESGCM")) {
                                        nlog("[NguonC] WebView captured plain m3u8!")
                                        val m3u8Base = if (capturedUrl.isNotEmpty()) capturedUrl.substringBeforeLast("/") + "/" else ""
                                        if (registerM3U8Link(content, targetUrl, m3u8Base, serverName, callback)) {
                                            linkFound = true; return@async
                                        }
                                    }

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

                            if (!linkFound && urlHash != null) {
                                nlog("[NguonC] Step 7: Trying URL hash as token: ${urlHash.take(20)}...")

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
                                                        return@async altDomain
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

                        if ((targetUrl.contains(".m3u8") || targetUrl.contains(".m3u9")) && targetUrl.contains("streamc.xyz")) {
                            nlog("[NguonC] Processing direct m3u8/m3u9 URL: ${targetUrl.take(80)}")

                            val urlToken = Regex("""/([A-Za-z0-9+/=_-]+)\.(m3u8|m3u9)""").find(targetUrl)?.groupValues?.get(1)
                            if (urlToken != null) {
                                val embedPageUrl = Regex("""(https?://embed\d+\.streamc\.xyz)""").find(targetUrl)?.groupValues?.get(1)?.let { "$it/embed.php" }
                                    ?: "$embedDomain/embed.php"
                                nlog("[NguonC] Trying mobile flow on direct URL with token: ${urlToken.take(20)}...")
                                if (tryMobileM3U8Flow(embedPageUrl, embedDomain, urlToken, serverName, callback)) {
                                    linkFound = true; return@async
                                }
                            }

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
                                        var encKX: String? = null
                                        if (urlToken != null) {
                                            val decoded = decodeStreamcToken(urlToken)
                                            if (decoded != null) {
                                                val (_, h, _) = decoded
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

                        nlog("[NguonC] Processing other embed: ${targetUrl.take(80)}")
                        val embedRes = app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf(
                            "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT
                        ))
                        val otherHtml = embedRes.text

                        if (otherHtml.contains("#EXTM3U") && !otherHtml.contains("#ENC-AESGCM")) {
                            val capUrl = embedRes.url ?: ""
                            val m3u8Base = if (capUrl.isNotEmpty()) capUrl.substringBeforeLast("/") + "/" else ""
                            if (registerM3U8Link(otherHtml, targetUrl, m3u8Base, serverName, callback)) {
                                linkFound = true; return@async
                            }
                        }

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

    data class StreamcAccessResponse(
        @JsonProperty("ok")  val ok: Boolean? = null,
        @JsonProperty("xat") val xat: String? = null
    )
}
