package com.hanime1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Hanime1 plugin for CloudStream 3.
 *
 * Site: https://hanime1.me
 * Type: NSFW (Hentai anime)
 * Language: Chinese (Traditional) / Japanese
 *
 * Site structure (verified from live HTML):
 *   • Home page: sections with .home-rows-videos-wrapper > .horizontal-card
 *   • Search: /search?query=<q> or /search?genre=<genre>&sort=<sort>&page=<N>
 *   • Watch page: /watch?v=<videoId>
 *   • Video player: <video id="player"> with <source size="720/480/1080" src="...mp4">
 *
 * Video extraction is SIMPLE — direct MP4 URLs in <source> tags with quality
 * labels (480p, 720p, 1080p). No m3u8, no encryption, no AJAX needed.
 *
 * Cloudflare: Basic JS challenge only (NO Turnstile). WebViewResolver handles
 * it transparently, same as NguonC.
 */
@CloudstreamPlugin
class Hanime1Plugin : Plugin() {
    override fun load() {
        registerMainAPI(Hanime1Provider())
    }
}

class Hanime1Provider : MainAPI() {
    override var mainUrl = "https://hanime1.me"
    override var name = "Hanime1"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    val nsfw = true

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-TW,zh;q=0.9,en;q=0.8",
        "Referer"         to "$mainUrl/"
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  Home page — sections from verified home page HTML
    // ═══════════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "search?genre=裏番"                                      to "Home 🏠",
        "search?query=&type=&genre=裏番&sort=觀看次數&date=&duration=" to "Lượt xem Cao 🔥",
        "search?query=&type=&genre=裏番&tags%5B%5D=母&sort=&date=&duration=" to "Loạn Luân 🥵"
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        val cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }

    /**
     * Build a beautiful HTML-formatted description (NguonC polish).
     */
    private fun buildBeautifulDescription(
        title: String,
        description: String?,
        duration: String?,
        views: String?,
        rating: String?,
        tags: List<String>
    ): String {
        return buildString {
            fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank())
                    append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
            }

            addInfo("⏱", "時長", duration)
            addInfo("👀", "觀看次數", views)
            addInfo("👍", "評分", rating, "#4CAF50")
            if (tags.isNotEmpty()) {
                addInfo("🎭", "標籤", tags.joinToString(", "), "#E91E63")
            }

            if (!description.isNullOrBlank()) {
                append("<br><b><font color='#FFEB3B'>✦ 內容簡介</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(description.trim())
            }
        }
    }

    /**
     * Parse a video card from .search-videos structure (verified from search pages):
     *
     *   <a href="https://hanime1.me/watch?v=406899">
     *     <div class="home-rows-videos-div search-videos hover-lighter">
     *       <div class="video-card-inner">
     *         <img src="https://vdownload.hembed.com/image/cover/406899.jpg?secure=..." />
     *         <div class="home-rows-videos-title">不雅之舉 2</div>
     *       </div>
     *     </div>
     *   </a>
     */
    private fun parseSearchCard(el: Element): SearchResponse? {
        // .search-videos is the inner div — the <a> tag is its parent
        val a = if (el.tagName() == "a") el else el.parent()
        a ?: return null
        val href = a.attr("href")
        if (!href.contains("/watch")) return null

        val title = el.selectFirst(".home-rows-videos-title")?.text()?.trim()
            ?: a.attr("title").ifBlank { return null }

        val poster = el.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.let { if (it.startsWith("http")) it else fixUrl(it) }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
            this.quality = SearchQuality.HD
        }
    }

    /**
     * Parse a video card from .horizontal-card structure (verified from home.html):
     *
     *   <div class="horizontal-card">
     *     <a class="video-link" href="https://hanime1.me/watch?v=406918">
     *       <div class="thumb-container">
     *         <img class="main-thumb" src="https://vdownload.hembed.com/image/thumbnail/..." />
     *         <div class="duration">15:09</div>
     *         <div class="stats-container">
     *           <div class="stat-item"><i class="material-icons">thumb_up</i> 100%</div>
     *           <div class="stat-item">6137次</div>
     *         </div>
     *       </div>
     *       <div class="title">Block Party Sex 🔞💕</div>
     *     </a>
     *     <div class="subtitle"><a href="/search?query=Mr. No Face">Mr. No Face • 53分鐘前</a></div>
     *   </div>
     */
    private fun parseVideoCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a.video-link") ?: el.selectFirst("a[href*='/watch']") ?: return null
        val href = fixUrl(a.attr("href"))
        if (!href.contains("/watch")) return null

        val title = el.selectFirst(".title")?.text()?.trim()
            ?: a.attr("title").ifBlank { a.text().trim() }.ifBlank { return null }

        val poster = el.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-original").ifBlank { img.attr("src") } }
        }?.let { if (it.startsWith("http")) it else fixUrl(it) }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.quality = SearchQuality.HD
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main page
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        // URL-encode Chinese characters in the data string
        // CloudStream passes the raw string to app.get which may not encode properly
        val encodedData = URLEncoder.encode(data, "UTF-8")
            .replace("%3F", "?").replace("%3D", "=").replace("%26", "&")
            .replace("%5B", "%5B").replace("%5D", "%5D")  // keep [ and ] encoded

        val url = if (page == 1) {
            "$mainUrl/$encodedData"
        } else {
            "$mainUrl/$encodedData&page=$page"
        }

        println("[H1] getMainPage: $url")
        val doc = try {
            app.get(url, headers = commonHeaders).document
        } catch (e: Exception) {
            println("[H1] getMainPage failed: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        // Search pages use .search-videos (verified from live HTML)
        // Home page uses .horizontal-card
        // Try both selectors
        val items = doc.select(".search-videos").mapNotNull { parseSearchCard(it) }
        val items2 = if (items.isEmpty()) {
            doc.select(".horizontal-card").mapNotNull { parseVideoCard(it) }
        } else items

        // hasNext: look for pagination link to next page
        val hasNext = items2.isNotEmpty() && run {
            doc.selectFirst("a.page-link[href*='page=${page + 1}']") != null ||
            doc.selectFirst("a[rel='next']") != null
        }

        println("[H1]   items=${items2.size}, hasNext=$hasNext")
        return newHomePageResponse(request.name, items2, hasNext = hasNext)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?query=${URLEncoder.encode(query, "UTF-8")}"
        println("[H1] search: $url")
        val doc = try {
            app.get(url, headers = commonHeaders).document
        } catch (_: Exception) { return emptyList() }

        return doc.select(".search-videos, .horizontal-card").mapNotNull {
            parseSearchCard(it) ?: parseVideoCard(it)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Load detail
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        println("[H1] load: $url")
        val doc = app.get(url, headers = commonHeaders).document

        // Title: og:title has the cleanest format
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - Hanime1")?.trim()
            ?: doc.selectFirst("h3")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: "Hanime1 Video"

        // Poster: poster attr on <video> tag, or og:image
        val poster = doc.selectFirst("video#player")?.attr("poster")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        // Description: og:description has title/brand/release info
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // Parse description for metadata (it contains: Title, Brand, Release, File size)
        var brand: String? = null
        var releaseDate: String? = null
        var fileSize: String? = null
        description?.let { desc ->
            Regex("""Brand\s*/\s*[^:]+:\s*(.+)""").find(desc)?.let { brand = it.groupValues[1].trim() }
            Regex("""Release\s*/\s*[^:]+:\s*(.+)""").find(desc)?.let { releaseDate = it.groupValues[1].trim() }
            Regex("""File size\s*/\s*[^:]+:\s*(.+)""").find(desc)?.let { fileSize = it.groupValues[1].trim() }
        }

        // Duration from .duration div
        val duration = doc.selectFirst(".duration")?.text()?.trim()

        // Views and rating from stats
        val statsItems = doc.select(".stat-item")
        var views: String? = null
        var rating: String? = null
        for (stat in statsItems) {
            val text = stat.text().trim()
            if (text.contains("thumb_up") || text.contains("%")) rating = text.replace("thumb_up", "").trim()
            else if (text.contains("次") || text.contains("view")) views = text
        }

        // Tags: look for tag links
        val tags = doc.select("a[href*='/search?query=']").mapNotNull { a ->
            val text = a.text().trim()
            if (text.isNotBlank() && text.length < 30 && !text.contains("查看更多") && !text.contains("arrow")) text else null
        }.take(10)

        val plot = buildBeautifulDescription(title, description, duration, views, rating, tags)

        // ── Video sources (for current episode) ──
        val sources = mutableListOf<Pair<String, String>>()
        for (source in doc.select("video#player source")) {
            val src = source.attr("src")
            val size = source.attr("size")
            if (src.isNotBlank()) {
                sources.add(src to (if (size.isNotBlank()) "${size}p" else "Unknown"))
            }
        }
        val currentEpisodeData = if (sources.isNotEmpty()) {
            sources.joinToString(";") { (u, q) -> "$u|$q" }
        } else url

        // ── Parse playlist (same artist/series videos) ──
        // Structure (verified from watch page HTML):
        //   .hover-video-playlist > .related-watch-wrap (×30)
        //     > a.overlay[href="/watch?v=..."]
        //     > .card-mobile-panel > .card-mobile-title + .card-mobile-duration
        val playlistEntries = doc.select(".hover-video-playlist .related-watch-wrap, .related-watch-wrap")
            .mapNotNull { el ->
                val linkEl = el.selectFirst("a.overlay") ?: el.selectFirst("a[href*='/watch']") ?: return@mapNotNull null
                val href = fixUrl(linkEl.attr("href"))
                if (!href.contains("/watch")) return@mapNotNull null
                val epTitle = el.selectFirst(".card-mobile-title")?.text()?.trim() ?: return@mapNotNull null
                val epDuration = el.selectFirst(".card-mobile-duration")?.text()?.trim() ?: ""
                val isPlaying = el.get_text().contains("現正播放")
                Triple(href, epTitle, isPlaying)
            }
            .distinctBy { it.first }

        // ── Build episodes ──
        val episodes = if (playlistEntries.size > 1) {
            // Multiple videos in playlist → return as TV Series
            playlistEntries.mapIndexed { idx, (epUrl, epTitle, isPlaying) ->
                // For the currently-playing video, use pre-extracted sources
                // For others, pass the watch URL (loadLinks will fetch sources)
                val data = if (isPlaying) currentEpisodeData else epUrl
                newEpisode(data) {
                    this.name = epTitle
                    this.episode = idx + 1
                }
            }
        } else {
            // Single video → just 1 episode
            listOf(newEpisode(currentEpisodeData) {
                this.name = title
                this.episode = 1
            })
        }

        // ── Recommendations (exclude playlist entries) ──
        val playlistUrls = playlistEntries.map { it.first }.toSet()
        val recommendations = doc.select(".related-watch-wrap .horizontal-card, .related-watch-wrap .video-item-container")
            .mapNotNull { parseVideoCard(it) }
            .filter { it.url != url && it.url !in playlistUrls }
            .take(20)

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.tags = tags.ifEmpty { null }
                this.recommendations = recommendations.ifEmpty { null }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, currentEpisodeData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.tags = tags.ifEmpty { null }
                this.recommendations = recommendations.ifEmpty { null }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  loadLinks — extract MP4 sources from episode data
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[H1] loadLinks: ${data.take(100)}")

        // data format: "url1|quality1;url2|quality2;..." OR just a URL
        // Use ";" as separator (NOT ",") because MP4 URLs contain commas:
        //   secure=NtgShAeRSlHUIlnqcbQp8A==,1782777629
        if (data.contains("|")) {
            // Multiple sources encoded in load()
            val sources = data.split(";").mapNotNull { src ->
                val parts = src.split("|", limit = 2)
                if (parts.size == 2) Pair(parts[0], parts[1]) else null
            }

            for ((mp4Url, qualityLabel) in sources) {
                val quality = when {
                    qualityLabel.contains("1080") -> Qualities.P1080.value
                    qualityLabel.contains("720")  -> Qualities.P720.value
                    qualityLabel.contains("480")  -> Qualities.P480.value
                    qualityLabel.contains("360")  -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                println("[H1]   source: $qualityLabel -> ${mp4Url.take(80)}")
                callback(newExtractorLink(
                    name,
                    "Hanime1 $qualityLabel",
                    mp4Url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer"    to "$mainUrl/"
                    )
                })
            }
            return sources.isNotEmpty()
        }

        // Fallback: data is a URL (watch page) — fetch and extract sources
        val watchUrl = if (data.startsWith("http")) data else fixUrl(data)
        val doc = try {
            app.get(watchUrl, headers = commonHeaders).document
        } catch (e: Exception) {
            println("[H1] loadLinks fetch failed: ${e.message}")
            return false
        }

        var found = false
        for (source in doc.select("video#player source")) {
            val src = source.attr("src")
            val size = source.attr("size")
            if (src.isNotBlank()) {
                val qualityLabel = if (size.isNotBlank()) "${size}p" else "Unknown"
                val quality = when {
                    size == "1080" -> Qualities.P1080.value
                    size == "720"  -> Qualities.P720.value
                    size == "480"  -> Qualities.P480.value
                    size == "360"  -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                println("[H1]   fallback source: $qualityLabel -> ${src.take(80)}")
                callback(newExtractorLink(
                    name,
                    "Hanime1 $qualityLabel",
                    src,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer"    to "$mainUrl/"
                    )
                })
                found = true
            }
        }

        return found
    }
}
