package com.javtiful

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
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
 *  - mainPage: Latest / Trending / Most Viewed / Most Liked / For You
 *              + Censored / Uncensored / Reducing-mosaic collections
 *              + 12 popular categories (Beautiful Girl, Big Tits, Married Woman ...)
 *              + 12 popular actresses (Hatano Yui, Julia, Tachibana Mary ...)
 *              + 12 popular channels (S-Cute, Idea-Pocket, Faleno ...)
 *  - search  : /vn/search?q={query}&page={page}
 *  - load    : metadata + parallel recommendations from actress + first category page
 *  - loadLinks : direct MP4 from `playerSources` array (refreshed each call)
 *
 * Card filter: skip `<article class="front-video-card front-partner-card">` (ad cards).
 */
class Javtiful : MainAPI() {
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

    // ────────────────────────────────────────────────────────────────────
    //  mainPage
    // ────────────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        // Sort-based listings (single endpoint, just ?sort=…)
        "$mainUrl$VN/videos"                                                    to "Mới nhất",
        "$mainUrl$VN/videos?sort=added_today"                                   to "Hôm nay",
        "$mainUrl$VN/videos?sort=added_week"                                    to "Tuần này",
        "$mainUrl$VN/videos?sort=added_month"                                   to "Tháng này",
        "$mainUrl$VN/trending"                                                  to "Đang thịnh hành",
        "$mainUrl$VN/videos?sort=most_viewed"                                   to "Xem nhiều nhất",
        "$mainUrl$VN/videos?sort=most_liked"                                    to "Thích nhiều nhất",
        "$mainUrl$VN/videos?sort=popular_today"                                 to "Hot hôm nay",
        "$mainUrl$VN/videos?sort=popular_month"                                 to "Hot tháng này",

        // Collections
        "$mainUrl$VN/censored"                                                   to "Có che",
        "$mainUrl$VN/uncensored"                                                 to "Không che",
        "$mainUrl$VN/reducing-mosaic"                                            to "Giảm mosaics",

        // Popular categories
        "$mainUrl$VN/category/beautiful-girl"                                    to "Biệt chủng: Beautiful Girl",
        "$mainUrl$VN/category/big-tits"                                          to "Biệt chủng: Big Tits",
        "$mainUrl$VN/category/married-woman"                                     to "Biệt chủng: Married Woman",
        "$mainUrl$VN/category/milf"                                              to "Biệt chủng: MILF",
        "$mainUrl$VN/category/mature-woman"                                      to "Biệt chủng: Mature Woman",
        "$mainUrl$VN/category/female-teacher"                                    to "Biệt chủng: Female Teacher",
        "$mainUrl$VN/category/female-student"                                    to "Biệt chủng: Female Student",
        "$mainUrl$VN/category/office-lady"                                       to "Biệt chủng: Office Lady",
        "$mainUrl$VN/category/nurse"                                             to "Biệt chủng: Nurse",
        "$mainUrl$VN/category/housekeeper"                                       to "Biệt chủng: Housekeeper",
        "$mainUrl$VN/category/cosplay"                                           to "Biệt chủng: Cosplay",
        "$mainUrl$VN/category/drama"                                             to "Biệt chủng: Drama",
        "$mainUrl$VN/category/amateur"                                           to "Biệt chủng: Amateur",

        // Popular actresses
        "$mainUrl$VN/actress/hatano-yui"                                         to "Diễn viên: Hatano Yui",
        "$mainUrl$VN/actress/julia"                                              to "Diễn viên: Julia",
        "$mainUrl$VN/actress/tachibana-mary"                                     to "Diễn viên: Tachibana Mary",
        "$mainUrl$VN/actress/shinoda-yuu"                                        to "Diễn viên: Shinoda Yuu",
        "$mainUrl$VN/actress/iioka-kanako"                                       to "Diễn viên: Iioka Kanako",
        "$mainUrl$VN/actress/hanasaki-himari"                                    to "Diễn viên: Hanazawa Himari",
        "$mainUrl$VN/actress/matsumoto-ichika"                                   to "Diễn viên: Matsumoto Ichika",
        "$mainUrl$VN/actress/tanaka-nene"                                        to "Diễn viên: Tanaka Nene",
        "$mainUrl$VN/actress/hamasaki-mao"                                       to "Diễn viên: Hamasaki Mao",
        "$mainUrl$VN/actress/kitano-mina"                                        to "Diễn viên: Kitano Mina",
        "$mainUrl$VN/actress/misono-waka"                                        to "Diễn viên: Misono Waka",
        "$mainUrl$VN/actress/suehiro-jun"                                        to "Diễn viên: Suehiro Jun",

        // Popular channels
        "$mainUrl$VN/channel/s-cute"                                             to "Kênh: S-Cute",
        "$mainUrl$VN/channel/idea-pocket"                                        to "Kênh: Idea-Pocket",
        "$mainUrl$VN/channel/faleno"                                             to "Kênh: Faleno",
        "$mainUrl$VN/channel/kawaii"                                             to "Kênh: Kawaii",
        "$mainUrl$VN/channel/fitch"                                              to "Kênh: Fitch",
        "$mainUrl$VN/channel/honnaka"                                            to "Kênh: Honnaka",
        "$mainUrl$VN/channel/attackers"                                          to "Kênh: Attackers",
        "$mainUrl$VN/channel/e-body"                                             to "Kênh: E-Body",
        "$mainUrl$VN/channel/hunter"                                             to "Kênh: Hunter",
        "$mainUrl$VN/channel/fc2ppv"                                             to "Kênh: FC2PPV",
        "$mainUrl$VN/channel/1pondo"                                             to "Kênh: 1Pondo",
        "$mainUrl$VN/channel/caribbean"                                          to "Kênh: Caribbean"
    )

    // ────────────────────────────────────────────────────────────────────
    //  HTTP helper
    // ────────────────────────────────────────────────────────────────────

    private fun httpGet(url: String): String {
        return try {
            app.get(url, headers = mapOf("User-Agent" to UA)).text
        } catch (t: Throwable) {
            Log.w(TAG, "httpGet failed: $url :: ${t.message}")
            ""
        }
    }

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

        // Optional badges
        val qualityTag  = selectFirst(".front-quality-tag")?.text()?.trim().orEmpty()
        val durationTag = selectFirst(".front-duration-tag")?.text()?.trim().orEmpty()

        // Views / time-ago (best-effort — used as a numeric hint)
        val statText = selectFirst(".front-video-stat")?.text()?.trim().orEmpty()

        return newMovieSearchResponse(
            title  = title,
            url    = if (href.startsWith("http")) href else mainUrl + href,
            type   = TvType.NSFW
        ) {
            this.posterUrl = if (poster.startsWith("http")) poster
                             else if (poster.startsWith("/")) mainUrl + poster
                             else ""
            // CloudStream's quality string is free-form; map "FHD"→1080p, "HD"→720p etc.
            this.quality = when (qualityTag.uppercase()) {
                "4K", "UHD"   -> getQualityValue("4K")
                "FHD", "1080" -> getQualityValue("FHD")
                "HD",  "720"  -> getQualityValue("HD")
                "SD",  "480"  -> getQualityValue("SD")
                else          -> null
            }
        }.also { sr ->
            // Stash duration & views inside the SearchResponse's tags so we can show
            // them without an extra round-trip later.
            if (durationTag.isNotBlank() || statText.isNotBlank()) {
                sr.tags = listOfNotNull(
                    durationTag.takeIf { it.isNotBlank() },
                    statText.takeIf    { it.isNotBlank() }
                )
            }
        }
    }

    /** Map a friendly quality tag to a SearchQualities integer. */
    private fun getQualityValue(tag: String): Int = when (tag.uppercase()) {
        "4K", "UHD"   -> SearchQualities.P4K.value
        "FHD", "1080" -> SearchQualities.P1080.value
        "HD",  "720"  -> SearchQualities.P720.value
        "SD",  "480"  -> SearchQualities.P480.value
        else          -> SearchQualities.Unknown.value
    }

    /**
     * Fetch a list page and convert every `<article class="front-video-card">`
     * (excluding partner cards) into a SearchResponse.
     */
    private fun parseListPage(url: String): List<SearchResponse> {
        val html = httpGet(url)
        if (html.isBlank()) return emptyList()
        val doc = app.newDocument(html)
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
        // Trending / collections don't really paginate via ?page — they accept it on the same path.
        val sep   = if (base.contains("?")) "&" else "?"
        val url   = if (page <= 1) base else "$base${sep}page=$page"
        val items = parseListPage(url)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ────────────────────────────────────────────────────────────────────
    //  Search
    // ────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return parseListPage("$mainUrl$VN/search?q=$encoded")
    }

    // ────────────────────────────────────────────────────────────────────
    //  load (detail page)
    // ────────────────────────────────────────────────────────────────────

    /** JSON shape inside `#frontWatchConfig` — only the fields we actually use. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class WatchConfig(
        @JsonProperty("playerSources") val playerSources: List<PlayerSource>? = null,
        @JsonProperty("videoTitle")    val videoTitle: String?    = null,
        @JsonProperty("videoPoster")   val videoPoster: String?   = null,
        @JsonProperty("defaultQuality") val defaultQuality: Int? = null,
        @JsonProperty("qualityOptions") val qualityOptions: List<Int>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class PlayerSource(
        @JsonProperty("src")  val src: String?  = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("size") val size: Int?    = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val html = httpGet(url) ?: ""
        if (html.isBlank()) return null
        val doc = app.newDocument(html)

        // 1) Pull the player config JSON out of the page.
        val configJson = doc.selectFirst("script#frontWatchConfig")?.data()
            ?: run {
                Log.w(TAG, "load: no #frontWatchConfig on $url")
                return null
            }

        val config: WatchConfig = tryParseJson(configJson) ?: run {
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
        val views  = doc.selectFirst("[data-front-views-count]")?.text()?.trim()
        val likes  = doc.selectFirst("[data-front-likes-count]")?.text()?.trim()

        // 6) Recommendations — pull the in-page "More like this" grid first,
        //    then enhance with parallel fetches from the actress + first category page.
        val pageRecs = doc.select("article.front-video-card")
            .mapNotNull { runCatching { it.toSearchResponse() }.getOrNull() }
            .distinctBy { it.url }

        // Parallel enhancement — only fetch if we have somewhere to fetch from.
        val enhancedRecs = withContext(Dispatchers.IO) {
            val endpoints = mutableListOf<String>()
            actressSlugs.firstOrNull()?.let { endpoints.add(if (it.startsWith("http")) it else mainUrl + it) }
            categorySlugs.firstOrNull()?.let { endpoints.add(if (it.startsWith("http")) it else mainUrl + it) }

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
            actress ?.let { append("<li><b>Diễn viên:</b> ").append(it).append("</li>") }
            channel ?.let { append("<li><b>Kênh:</b> ").append(it).append("</li>") }
            if (censoredChip.isNotBlank())
                append("<li><b>Loại:</b> ").append(censoredChip).append("</li>")
            if (categories.isNotEmpty())
                append("<li><b>Danh mục:</b> ").append(categories.joinToString(", ")).append("</li>")
            if (tags.isNotEmpty())
                append("<li><b>Thẻ:</b> ").append(tags.joinToString(", ")).append("</li>")
            addedDate?.let {
                runCatching {
                    val out = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        .format(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(it))
                    append("<li><b>Đăng ngày:</b> ").append(out).append("</li>")
                }.onFailure {
                    append("<li><b>Đăng ngày:</b> ").append(it).append("</li>")
                }
            }
            views?.let { append("<li><b>Lượt xem:</b> ").append(it).append("</li>") }
            likes?.let { append("<li><b>Lượt thích:</b> ").append(it).append("</li>") }
            append("</ul>")
        }

        return newMovieLoadResponse(
            name       = title,
            url        = url,
            type       = TvType.NSFW,
            dataUrl    = url     // we re-fetch the page in loadLinks to refresh signed MP4 URLs
        ) {
            this.posterUrl      = poster
            this.plot           = description
            this.tags           = (categories + tags).distinct().ifEmpty { null }
            this.recommendations = enhancedRecs.ifEmpty { null }
            this.actors         = actress?.split(", ")
                ?.map { Actor(it.trim(), null) }
                ?.ifEmpty { null }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  loadLinks — direct MP4 from Cloudflare R2 (signed URLs)
    // ────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleData) -> Unit,
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

        val config: WatchConfig = tryParseJson(configJson) ?: run {
            Log.w(TAG, "loadLinks: WatchConfig parse failed")
            return false
        }

        val sources = config.playerSources.orEmpty().filter { !it.src.isNullOrBlank() }
        if (sources.isEmpty()) {
            Log.w(TAG, "loadLinks: no playerSources on $data")
            return false
        }

        sources.forEach { s ->
            val src      = s.src ?: return@forEach
            val srcAbs   = if (src.startsWith("http")) src else mainUrl + src
            val height   = s.size ?: 0
            val quality  = when {
                height >= 2160 -> Qualities.P4K.value
                height >= 1080 -> Qualities.P1080.value
                height >= 720  -> Qualities.P720.value
                height >= 480  -> Qualities.P480.value
                height >= 360  -> Qualities.P360.value
                else           -> Qualities.Unknown.value
            }
            // Quality label that displays nicely in the CloudStream server picker
            val qLabel = if (height > 0) "${height}p" else "Default"

            callback(
                newExtractorLink(
                    source  = "Javtiful",
                    name    = "Javtiful · $qLabel",
                    url     = srcAbs,
                    type    = MP4_TYPE
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

        // The site doesn't ship subtitles — but if a future revision adds them,
        // we'd extract them here. Returning true means "we found at least one link".
        return true
    }

    // ────────────────────────────────────────────────────────────────────
    //  Link type — CloudStream's VIDEO type is for direct MP4/WebM file URLs.
    //  (M3U8 / DASH have their own dedicated types; this site only ships MP4.)
    // ────────────────────────────────────────────────────────────────────
    private val MP4_TYPE = ExtractorLinkType.VIDEO
}
