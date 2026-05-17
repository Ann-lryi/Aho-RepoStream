package com.vlxx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

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
    override val supportedTypes = setOf(TvType.NSFW)

    private val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/" to "Mới",
        "/xxx/#day/" to "Hay Hàng Ngày"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        val cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl/new/$page/"
        }

        val doc = app.get(url, headers = headers).document
        val items = doc.select("div.video-item").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst(".video-name a")?.text() }?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-original").ifBlank { img.attr("src") }
            }

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }

        val hasNext = items.isNotEmpty() && doc.select("a:contains(→), .pagenavi a:last-child").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${java.net.URLEncoder.encode(query, "UTF-8")}/"
        val doc = app.get(url, headers = headers).document

        return doc.select("div.video-item").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst(".video-name a")?.text() }?.trim() ?: return@mapNotNull null
            val imgEl = el.selectFirst("img")
            val poster = imgEl?.attr("data-original")?.ifBlank { imgEl.attr("src") }

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1, .video-name, title")?.text()?.trim() ?: "VLXX Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img.video-image")?.attr("src")

        // Extract video ID from URL: /video/xxx/3099/
        val videoId = Regex("""/(\d+)/?$""").find(url)?.groupValues?.get(1)

        return newMovieLoadResponse(title, url, TvType.NSFW, videoId ?: url) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data là video ID
        val videoId = if (data.startsWith("http")) {
            Regex("""/(\d+)/?$""").find(data)?.groupValues?.get(1)
        } else {
            data
        }

        if (videoId == null) return false

        // Format video ID thành 5 chữ số (3099 → 03099)
        val formattedId = videoId.padStart(5, '0')

        // URL m3u8 trực tiếp
        val m3u8Url = "https://rr3---sn-8pxuuxa-i5ozr.qooglevideo.com/manifest-s1/$formattedId.vl"

        val m3u8Headers = mapOf(
            "User-Agent" to UA,
            "Referer" to "https://play.vlstream.net/",
            "Origin" to "https://play.vlstream.net"
        )

        try {
            val checkRes = app.get(m3u8Url, headers = m3u8Headers)
            
            if (checkRes.code == 200 && checkRes.text.contains("#EXTM3U")) {
                callback(
                    newExtractorLink(name, "Server VIP", m3u8Url, type = ExtractorLinkType.M3U8) {
                        this.referer = "https://play.vlstream.net/"
                        this.headers = m3u8Headers
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }
        } catch (e: Exception) {
            // Try alternative method
        }

        // Fallback: Gọi AJAX để lấy iframe
        return loadLinksFromAjax(videoId, callback)
    }

    private suspend fun loadLinksFromAjax(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ajaxUrl = "$mainUrl/ajax.php"
        
        val ajaxHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to "$mainUrl/",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept" to "*/*"
        )

        try {
            // Sử dụng data map thay vì requestBody string
            val postData = mapOf(
                "vlxx_server" to "1",
                "id" to videoId,
                "server" to "1"
            )
            
            val response = app.post(ajaxUrl, data = postData, headers = ajaxHeaders).text
            
            // Tìm embed ID từ iframe URL
            val embedMatch = Regex("""/embed/([a-zA-Z0-9]+)""").find(response)
            
            if (embedMatch != null) {
                val embedId = embedMatch.groupValues[1]
                
                // Thử các server khác
                val servers = listOf(
                    "https://stream.vlstream.net/videos/$embedId/master.m3u8",
                    "https://play.vlstream.net/videos/$embedId/master.m3u8"
                )

                for (m3u8Url in servers) {
                    try {
                        val checkHeaders = mapOf(
                            "User-Agent" to UA,
                            "Referer" to "https://play.vlstream.net/"
                        )
                        val checkRes = app.get(m3u8Url, headers = checkHeaders)
                        
                        if (checkRes.code == 200 && checkRes.text.contains("#EXTM3U")) {
                            callback(
                                newExtractorLink(name, "Server VIP", m3u8Url, type = ExtractorLinkType.M3U8) {
                                    this.referer = "https://play.vlstream.net/"
                                    this.headers = checkHeaders
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            return true
                        }
                    } catch (e: Exception) {
                        // Try next
                    }
                }
            }
        } catch (e: Exception) {
            // Failed
        }

        return false
    }
}
