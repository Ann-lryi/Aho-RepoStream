package com.vlxx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@CloudstreamPlugin
class VLXXPlugin : Plugin() {
    override fun load() {
        registerMainAPI(VLXXProvider())
    }
}

class VLXXProvider : MainAPI() {
    override var mainUrl = "https://vlxx.moi"
    override var name = "VLXX"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    val nsfw = true

    private val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/"
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  Home page — 12 sections covering popular tags + sort orders
    // ═══════════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "/"                  to "Mới Cập Nhật 🔥",
        "/xxx/#day/"         to "Hay Hàng Ngày ⭐",
        "/jav/"              to "JAV 🇯🇵",
        "/vietsub/"          to "Vietsub 🌐",
        "/khong-che/"        to "Không Che 🥵",
        "/vung-trom/"        to "Vụng Trộm 💔",
        "/chau-au/"          to "Châu Âu 🇪🇺",
        "/xvideos/"          to "XVideos 🎬",
        "/xnxx/"             to "XNXX 📺",
        "/phim-sex-hay/"     to "Phim Sex Hay 👍",
        "/hoc-sinh/"         to "Học Sinh 🎓",
        "/cap-3/"            to "Cap 3 👯"
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
     * Normalize a server label into a clean Vietnamese picker name.
     *   "VIP 1" / "1" / null  →  "Server VIP 1"
     *   "Backup" / "Backup 2" →  "Server Backup 2"
     * (matches polish level of NguonC / HentaiZ cleanServerName)
     */
    private fun cleanServerName(raw: String?, idx: Int = 0): String {
        if (raw.isNullOrBlank()) return "Server ${idx + 1}"
        val lower = raw.lowercase().trim()
        return when {
            lower.startsWith("vip") || lower == "1" || lower.matches(Regex("""\d+""")) -> {
                val num = Regex("""\d+""").find(lower)?.value?.toIntOrNull() ?: (idx + 1)
                "Server VIP $num"
            }
            lower.startsWith("backup") -> {
                val num = Regex("""\d+""").find(lower)?.value?.toIntOrNull() ?: (idx + 1)
                "Server Backup $num"
            }
            else -> "Server ${raw.trim().replaceFirstChar { it.uppercaseChar() }}"
        }
    }

    /**
     * Build a beautiful HTML-formatted description matching NguonC/HentaiZ polish.
     * Pulls og:description, video code (e.g. EBWH-195), and source ID from the
     * detail page and formats them with emoji icons + colored values.
     */
    private fun buildBeautifulDescription(
        title: String,
        description: String?,
        videoCode: String?,
        sourceId: String?,
        tagLabels: List<String>
    ): String {
        return buildString {
            fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank())
                    append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
            }

            addInfo("🔖", "Code", videoCode?.trim(), "#E91E63")
            addInfo("🔗", "ID", sourceId?.trim(), "#03A9F4")
            if (tagLabels.isNotEmpty()) {
                addInfo("🎭", "Thể loại", tagLabels.joinToString(", "), "#4CAF50")
            }

            if (!description.isNullOrBlank()) {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(description.trim())
            }
        }
    }

    /**
     * Parse a master m3u8 playlist and return its quality variants.
     * Returns empty list for non-master playlists (single-variant).
     * (Same logic as HentaiZ.parseM3U8Variants — kept here for self-contained plugin.)
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
                        bandwidth != null && bandwidth >= 4_000_000 -> "720p"  to Qualities.P720.value
                        bandwidth != null && bandwidth >= 1_500_000 -> "480p"  to Qualities.P480.value
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

    /** Parse a single video-item Element into a SearchResponse. */
    private fun parseVideoItem(el: org.jsoup.nodes.Element): SearchResponse? {
        val linkEl = el.selectFirst("a") ?: return null
        val href   = fixUrl(linkEl.attr("href"))
        val title  = linkEl.attr("title").ifBlank { el.selectFirst(".video-name a")?.text() }?.trim()
            ?: return null
        val poster = el.selectFirst("img")?.let { img ->
            img.attr("data-original").ifBlank { img.attr("data-src").ifBlank { img.attr("src") } }
        }?.let { if (it.startsWith("http")) it else null }
        // Ribbon badge (Vietsub / Không che / HD) → quality hint
        val ribbon = el.selectFirst(".ribbon")?.text()?.trim().orEmpty()
        val quality = when {
            ribbon.contains("HD", ignoreCase = true)          -> SearchQuality.HD
            ribbon.contains("4K", ignoreCase = true)          -> SearchQuality.P2160
            else                                              -> null
        }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            if (quality != null) this.quality = quality
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main page — proper per-section pagination
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Build the correct URL per-section per-page.
        // The previous code always used /new/$page/ regardless of section — broken
        // for the genre/tag sections.
        //
        // Patterns observed on vlxx.moi:
        //   • "/" or "/xxx/#day/"  →  page 2 = /new/2/, page 3 = /new/3/ ...
        //   • "/jav/"               →  page 2 = /jav/2/, page 3 = /jav/3/ ...
        //   • "/khong-che/"         →  page 2 = /khong-che/2/ ...
        val sectionPath = request.data.removeSuffix("/").substringBeforeLast("#")
        val url = when {
            // "/" or "" → root
            sectionPath.isEmpty() && page == 1 -> "$mainUrl/"
            // Special hash-anchored sections ("Hay Hàng Ngày")
            request.data.contains("#") && page == 1 -> "$mainUrl${request.data.substringBefore("#")}/"
            // Genre/tag sections with pagination
            sectionPath.isNotEmpty() && page > 1 -> "$mainUrl$sectionPath/$page/"
            // Genre/tag section, page 1
            sectionPath.isNotEmpty() -> "$mainUrl$sectionPath/"
            // Root, page > 1
            else -> "$mainUrl/new/$page/"
        }

        val doc = try {
            app.get(url, headers = headers).document
        } catch (e: Exception) {
            println("[VLXX] getMainPage fetch failed: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val items = doc.select("div.video-item").mapNotNull { parseVideoItem(it) }

        // hasNext: look for next-page link. vlxx uses /N/ pagination links.
        val hasNext = items.isNotEmpty() && run {
            val nextLink = doc.selectFirst("a:contains(→), .pagenavi a:last-child, a.next, li.next a")
            nextLink != null || doc.select("a[href*='/${page + 1}/']").isNotEmpty()
        }
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${java.net.URLEncoder.encode(query, "UTF-8")}/"
        val doc = try { app.get(url, headers = headers).document }
        catch (_: Exception) { return emptyList() }
        return doc.select("div.video-item").mapNotNull { parseVideoItem(it) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Load detail + recommendations (parse 10 related videos on same page)
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        // Title: prefer h1 (page-title), fall back to og:title then <title>
        val title = doc.selectFirst("h1.page-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - VLXX")?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - VLXX")?.trim()
            ?: "VLXX Video"

        // Poster: og:image, then img.video-image, then any img in main content
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img.video-image")?.attr("src")
            ?: doc.selectFirst("img[data-original]")?.attr("data-original")

        // Description from og:description / meta description
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        // Video code (e.g. EBWH-195) + source ID (e.g. vlxx.moi/3173)
        val videoCode = doc.selectFirst(".video-code")?.text()?.trim()
        val sourceId  = doc.selectFirst(".video-link")?.text()?.trim()

        // Extract video ID from URL: /video/xxx/3099/
        val videoId = Regex("""/(\d+)/?$""").find(url)?.groupValues?.get(1)

        // Tags: from breadcrumb or .video-info context (no explicit tags list on page,
        // but we can derive category from URL or breadcrumb items)
        val tagLabels = mutableListOf<String>()
        // The ribbon badge on the listing usually indicates Vietsub/Không Che
        // (we don't have it here, so we leave tags empty unless we find genre links)
        doc.select(".breadcrumb a, .tags a, .tag a, .category a").forEach { a ->
            val txt = a.text().trim()
            if (txt.isNotBlank() && txt.length < 30 && !txt.equals("VLXX", ignoreCase = true)) {
                tagLabels.add(txt)
            }
        }

        val plot = buildBeautifulDescription(title, description, videoCode, sourceId, tagLabels)

        // Recommendations: detail page already has 10 related videos in div.video-item.
        // Filter out the current video to avoid self-reference.
        // URL pattern is /video/{slug}/{id}/  — extract the trailing numeric id.
        val recommendations: List<SearchResponse> = doc.select("div.video-item")
            .mapNotNull { parseVideoItem(it) }
            .filter { rec ->
                val recId = (rec.url ?: "").trimEnd('/').substringAfterLast("/")
                recId != videoId
            }
            .take(20)

        // Data passed to loadLinks: videoId|url (we keep url for fallback re-fetch)
        val episodeData = if (videoId != null) "$videoId|$url" else url

        return newMovieLoadResponse(title, url, TvType.NSFW, episodeData) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = poster
            this.plot                = plot
            this.tags                = tagLabels.ifEmpty { null }
            this.recommendations     = recommendations.ifEmpty { null }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  loadLinks — try direct CDN URL first, then multi-server AJAX fallback
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[VLXX] loadLinks: ${data.take(100)}")

        // data format: "<videoId>|<detailUrl>"  OR  "<videoId>"  OR  "<url>"
        val (rawVideoId, detailUrl) = if (data.contains("|")) {
            val parts = data.split("|", limit = 2)
            Pair(parts[0], parts.getOrNull(1) ?: "")
        } else {
            Pair(data, "")
        }

        // If data is a URL (not a number), extract the ID from it
        val videoId = if (rawVideoId.startsWith("http")) {
            Regex("""/(\d+)/?$""").find(rawVideoId)?.groupValues?.get(1)
        } else {
            rawVideoId
        } ?: run {
            println("[VLXX] No video ID found in: $data")
            return false
        }

        // Format video ID to 5 digits (3099 → 03099)
        val formattedId = videoId.padStart(5, '0')

        // Headers for CDN access
        val cdnHeaders = mapOf(
            "User-Agent" to UA,
            "Referer"    to "https://play.vlstream.net/",
            "Origin"     to "https://play.vlstream.net"
        )
        val cdnHeadersBasic = mapOf(
            "User-Agent" to UA,
            "Referer"    to "https://play.vlstream.net/"
        )

        var linkFound = false

        // ── Strategy 1: Direct CDN URL (fastest path) ──
        // The hardcoded qooglevideo.com host may change over time. To be robust,
        // we try a small list of candidate host prefixes in parallel.
        val directHosts = listOf(
            "rr3---sn-8pxuuxa-i5ozr.qooglevideo.com",
            "rr1---sn-8pxuuxa-i5ozr.qooglevideo.com",
            "rr2---sn-8pxuuxa-i5ozr.qooglevideo.com",
            "rr4---sn-8pxuuxa-i5ozr.qooglevideo.com"
        )
        println("[VLXX] S1: trying direct CDN URLs in parallel (formattedId=$formattedId)")

        try {
            val directFound = coroutineScope {
                directHosts.mapIndexed { idx, host ->
                    async {
                        try {
                            val tryUrl = "https://$host/manifest-s1/$formattedId.vl"
                            val resp = app.get(tryUrl, headers = cdnHeaders)
                            if (resp.code == 200 && resp.text.contains("#EXTM3U")) {
                                Triple(host, tryUrl, resp.text)
                            } else null
                        } catch (_: Exception) { null }
                    }
                }.awaitAll().firstOrNull { it != null }
            }

            if (directFound != null) {
                val (_, m3u8Url, m3u8Content) = directFound
                println("[VLXX] S1 SUCCESS: $m3u8Url")

                // Parse master playlist for multi-quality variants
                val variants = parseM3U8Variants(m3u8Content, m3u8Url)
                if (variants.isNotEmpty()) {
                    variants.forEach { (label, variantUrl, quality) ->
                        callback(newExtractorLink(name, "VIP $label", variantUrl, ExtractorLinkType.M3U8) {
                            this.quality = quality
                            this.headers = cdnHeadersBasic
                            this.referer = "https://play.vlstream.net/"
                        })
                    }
                    linkFound = true
                } else {
                    // Single-variant playlist
                    callback(newExtractorLink(name, "Server VIP 1", m3u8Url, ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                        this.headers = cdnHeaders
                        this.referer = "https://play.vlstream.net/"
                    })
                    linkFound = true
                }
            }
        } catch (e: Exception) {
            println("[VLXX] S1 parallel error: ${e.message}")
        }

        // ── Strategy 2: AJAX → embed URL → master.m3u8 ──
        // AJAX returns iframe embed URL like:
        //   https://play.vlstream.net/embed/tYt3RH8VCdK/s1
        // We extract the embedId and try BOTH /s1 and /s2 endpoints.
        // Each server (s1, s2) returns a different master.m3u8 — offer both
        // in the picker so the user can switch if one fails.
        println("[VLXX] S2: trying AJAX embed for servers 1 & 2 in parallel")

        try {
            val ajaxUrl = "$mainUrl/ajax.php"
            val ajaxHeaders = mapOf(
                "User-Agent"      to UA,
                "Referer"         to "$mainUrl/",
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type"    to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept"          to "*/*"
            )

            // Try server 1 and server 2 IN PARALLEL
            val serverResults = coroutineScope {
                listOf(1, 2).map { srvNum ->
                    async {
                        try {
                            val postData = mapOf(
                                "vlxx_server" to srvNum.toString(),
                                "id"          to videoId,
                                "server"      to srvNum.toString()
                            )
                            val response = app.post(ajaxUrl, data = postData, headers = ajaxHeaders).text
                            val embedMatch = Regex("""/embed/([a-zA-Z0-9]+)""").find(response)
                            if (embedMatch != null) {
                                Pair(srvNum, embedMatch.groupValues[1])
                            } else null
                        } catch (_: Exception) { null }
                    }
                }.awaitAll()
            }

            // For each successful embed, fetch master.m3u8 from play.vlstream.net
            val embedHosts = listOf(
                "https://stream.vlstream.net",
                "https://play.vlstream.net"
            )

            for (result in serverResults) {
                if (result == null) continue
                val (srvNum, embedId) = result
                println("[VLXX] S2: server $srvNum embedId=$embedId — fetching m3u8")

                // Try both embed hosts in parallel
                val m3u8Found = coroutineScope {
                    embedHosts.map { host ->
                        async {
                            try {
                                val tryUrl = "$host/videos/$embedId/master.m3u8"
                                val resp = app.get(tryUrl, headers = cdnHeadersBasic)
                                if (resp.code == 200 && resp.text.contains("#EXTM3U")) {
                                    Triple(host, tryUrl, resp.text)
                                } else null
                            } catch (_: Exception) { null }
                        }
                    }.awaitAll().firstOrNull { it != null }
                }

                if (m3u8Found != null) {
                    val (_, m3u8Url, m3u8Content) = m3u8Found
                    println("[VLXX] S2 SUCCESS server $srvNum: $m3u8Url")

                    val variants = parseM3U8Variants(m3u8Content, m3u8Url)
                    if (variants.isNotEmpty()) {
                        variants.forEach { (label, variantUrl, quality) ->
                            callback(newExtractorLink(name, "VIP $srvNum $label", variantUrl, ExtractorLinkType.M3U8) {
                                this.quality = quality
                                this.headers = cdnHeadersBasic
                                this.referer = "https://play.vlstream.net/"
                            })
                        }
                    } else {
                        callback(newExtractorLink(name, "Server VIP $srvNum", m3u8Url, ExtractorLinkType.M3U8) {
                            this.quality = Qualities.P1080.value
                            this.headers = cdnHeaders
                            this.referer = "https://play.vlstream.net/"
                        })
                    }
                    linkFound = true
                }
            }
        } catch (e: Exception) {
            println("[VLXX] S2 error: ${e.message}")
        }

        if (!linkFound) {
            println("[VLXX] All strategies failed for videoId=$videoId")
        }
        return linkFound
    }
}
