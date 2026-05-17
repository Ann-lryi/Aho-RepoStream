package com.watchhentai

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

@CloudstreamPlugin
class WatchHentaiPlugin : Plugin() {
    override fun load() {
        registerMainAPI(WatchHentaiProvider())
    }
}

class WatchHentaiProvider : MainAPI() {
    override var mainUrl = "https://watchhentai.net"
    override var name = "WatchHentai"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/"
    )

    // Đã thêm sẵn một số thể loại phổ biến để bạn dễ lướt
    override val mainPage = mainPageOf(
        "/videos/" to " Mới Up",
        "/genre/incest/" to "Loạn Luân",
        "/trending/" to "Trending"
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
        // Xử lý URL chuẩn xác cho mọi chuyên mục (xóa dấu / ở cuối để tránh bị lỗi //page/2)
        val data = request.data.removeSuffix("/")
        val url = if (page == 1) {
            "$mainUrl$data/"
        } else {
            "$mainUrl$data/page/$page/"
        }

        val doc = app.get(url, headers = headers).document
        
        val items = doc.select("article.item, div.item, article.post").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            if (href.isBlank() || href == mainUrl) return@mapNotNull null

            val title = el.selectFirst("h3, .title, .name")?.text()?.trim() ?: return@mapNotNull null
            val imgEl = el.selectFirst("img")
            val poster = imgEl?.attr("data-src")?.ifBlank { imgEl.attr("src") }?.let { fixUrl(it) }

            newAnimeSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        // FIX: Thuật toán "bất tử" - Quét toàn bộ thẻ <a> xem có link nào chứa "page/2", "page/3"... không
        val hasNext = items.isNotEmpty() && doc.select("a[href*=page/${page + 1}]").isNotEmpty()
        
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = headers).document

        return doc.select("article.item, div.item, article.post").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            if (href.isBlank() || href == mainUrl) return@mapNotNull null

            val title = el.selectFirst("h3, .title, .name")?.text()?.trim() ?: return@mapNotNull null
            val imgEl = el.selectFirst("img")
            val poster = imgEl?.attr("data-src")?.ifBlank { imgEl.attr("src") }?.let { fixUrl(it) }

            newAnimeSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "WatchHentai Video"
        
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".poster img, .video-poster img")?.attr("src")?.let { fixUrl(it) }
            
        val description = doc.selectFirst(".wp-content p, .description, meta[property=og:description]")?.text()?.trim()

        val tags = doc.select(".sgeneros a, .genres a, a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }

        val episodes = mutableListOf<Episode>()
        val epElements = doc.select(".se-c ul.episodios li, ul.episodios li")
        
        if (epElements.isNotEmpty()) {
            epElements.forEach { el ->
                val aTag = el.selectFirst(".episodiotitle a") ?: el.selectFirst("a")
                val epLink = aTag?.attr("href")?.let { fixUrl(it) } ?: return@forEach
                val epTitle = aTag.text().trim()
                
                episodes.add(newEpisode(epLink) {
                    this.name = epTitle
                    this.posterUrl = poster
                })
            }
        } else {
            val fallbackLinks = doc.select("a[href*=/videos/]").filter { it.text().isNotBlank() }
            if (fallbackLinks.isNotEmpty() && url.contains("/series/")) {
                fallbackLinks.forEach { a ->
                    val epLink = fixUrl(a.attr("href"))
                    episodes.add(newEpisode(epLink) {
                        this.name = a.text().trim()
                        this.posterUrl = poster
                    })
                }
            } else {
                episodes.add(newEpisode(url) {
                    this.name = title
                    this.posterUrl = poster
                })
            }
        }

        val recommendations = doc.select(
            ".srelacionados article.item, " +
            "#single_relacionados article.item, " +
            ".related article.item, " +
            ".owl-item article.item, " +
            "div[id*=related] article"
        ).mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            
            if (href.isBlank() || href == mainUrl || href == url) return@mapNotNull null

            val recTitle = el.selectFirst("h3, .title, .name")?.text()?.trim() ?: return@mapNotNull null
            val imgEl = el.selectFirst("img")
            val recPoster = imgEl?.attr("data-src")?.ifBlank { imgEl.attr("src") }?.let { fixUrl(it) }

            newAnimeSearchResponse(recTitle, href, TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }.distinctBy { it.url }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            
            addEpisodes(DubStatus.Subbed, episodes.reversed().distinctBy { it.data }) 
        }
    }

    data class DooPlayerResponse(
        @JsonProperty("embed_url") val embedUrl: String?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document
        val html = doc.html()
        var iframeUrl: String? = null

        iframeUrl = doc.selectFirst("iframe[src*=/jwplayer/]")?.attr("src")

        if (iframeUrl == null) {
            val regex = Regex("""(https?://[^"']+/jwplayer/\?source=[^"']+)""")
            val match = regex.find(html)
            if (match != null) {
                iframeUrl = match.groupValues[1]
            }
        }

        if (iframeUrl == null) {
            val playerOptions = doc.select(".dooplay_player_option, ul#playeroptionsul li, .options ul li")
            for (option in playerOptions) {
                val post = option.attr("data-post")
                val nume = option.attr("data-nume")
                val type = option.attr("data-type")

                if (post.isNotBlank() && nume.isNotBlank()) {
                    val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                    val postData = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    )
                    
                    try {
                        val response = app.post(ajaxUrl, data = postData, headers = headers).parsedSafe<DooPlayerResponse>()
                        if (response?.embedUrl?.contains("/jwplayer/") == true) {
                            iframeUrl = response.embedUrl
                            break
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        if (iframeUrl != null) {
            val fixedIframeUrl = fixUrl(iframeUrl).replace("&amp;", "&")
            
            val sourceMatch = Regex("""source=([^&]+)""").find(fixedIframeUrl)
            val qualityMatch = Regex("""quality=([^&]+)""").find(fixedIframeUrl)

            if (sourceMatch != null) {
                val encodedSource = sourceMatch.groupValues[1]
                val source = URLDecoder.decode(encodedSource, "UTF-8")
                val qualities = qualityMatch?.groupValues?.get(1)?.split(",")?.map { it.trim() } ?: emptyList()

                val extension = source.substringAfterLast(".", "mp4")
                val baseUrl = source.substringBeforeLast(".")

                if (qualities.isNotEmpty()) {
                    qualities.forEach { q ->
                        val directUrl = "${baseUrl}_$q.$extension"
                        val qualityValue = when(q) {
                            "1080p" -> Qualities.P1080.value
                            "720p" -> Qualities.P720.value
                            "480p" -> Qualities.P480.value
                            else -> Qualities.Unknown.value
                        }
                        
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "HStorage $q",
                                url = directUrl,
                                type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = qualityValue
                            }
                        )
                    }
                } else {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "HStorage",
                            url = source,
                            type = if (source.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                return true
            }
        }

        doc.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotBlank() && !src.contains("youtube") && !src.contains("/jwplayer/")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
