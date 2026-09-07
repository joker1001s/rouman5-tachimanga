package eu.kanade.tachiyomi.extension.zh.rouman5

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Rouman5 : KeiSource() {

    override val supportsLatest = true

    // -------------------------------------------------------------------------
    // 搜索
    // -------------------------------------------------------------------------

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isBlank()) {
            return MangasPage(emptyList(), false)
        }

        val searchUrl = "$baseUrl/search?term=${encode(query)}"

        val body = client.get(searchUrl).use { response ->
            response.body.string()
        }

        val document = Jsoup.parse(body, searchUrl)

        val mangas = document
            .select("a[href*=\"/books/\"]")
            .mapNotNull { element ->
                parseSearchManga(element)
            }
            .distinctBy { it.url }

        return MangasPage(
            mangas,
            false,
        )
    }

    private fun parseSearchManga(element: Element): SManga? {
        val href = element.absUrl("href")

        if (href.isBlank() || !href.contains("/books/")) {
            return null
        }

        val title = element.selectFirst("h1, h2, h3, h4, .title")
            ?.text()
            ?.trim()
            ?: element.text().trim()

        if (title.isBlank()) {
            return null
        }

        return SManga.create().apply {
            setUrlWithoutDomain(href)
            this.title = cleanMangaTitle(title)

            val image = element.selectFirst("img")
                ?.let {
                    it.absUrl("src")
                        .ifBlank {
                            it.absUrl("data-src")
                        }
                        .ifBlank {
                            it.absUrl("data-original")
                        }
                }

            if (image.isNotBlank()) {
                thumbnail_url = image
            }
        }
    }

    // -------------------------------------------------------------------------
    // 热门
    // -------------------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage {
        val body = client.get(baseUrl).use { response ->
            response.body.string()
        }

        val document = Jsoup.parse(body, baseUrl)

        val mangas = document
            .select("a[href*=\"/books/\"]")
            .mapNotNull { parseSearchManga(it) }
            .distinctBy { it.url }

        return MangasPage(
            mangas,
            false,
        )
    }

    // -------------------------------------------------------------------------
    // 最新
    // -------------------------------------------------------------------------

    override suspend fun getLatestManga(page: Int): MangasPage {
        val body = client.get(baseUrl).use { response ->
            response.body.string()
        }

        val document = Jsoup.parse(body, baseUrl)

        val mangas = document
            .select("a[href*=\"/books/\"]")
            .mapNotNull { parseSearchManga(it) }
            .distinctBy { it.url }

        return MangasPage(
            mangas,
            false,
        )
    }

    // -------------------------------------------------------------------------
    // 漫画详情
    // -------------------------------------------------------------------------

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val mangaId = extractMangaId(url)

        if (mangaId.isBlank()) {
            return null
        }

        val mangaUrl = "$baseUrl/books/$mangaId"

        val body = client.get(mangaUrl).use { response ->
            response.body.string()
        }

        val document = Jsoup.parse(body, mangaUrl)

        return parseManga(document, mangaId)
    }

    // -------------------------------------------------------------------------
    // 更新漫画
    // -------------------------------------------------------------------------

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaId = extractMangaId(manga.url)

        if (mangaId.isBlank()) {
            return SMangaUpdate(manga, chapters)
        }

        val mangaUrl = "$baseUrl/books/$mangaId"

        val body = client.get(mangaUrl).use { response ->
            response.body.string()
        }

        val document = Jsoup.parse(body, mangaUrl)

        val updatedManga = if (fetchDetails) {
            parseManga(document, mangaId)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            parseChapters(document, mangaId)
        } else {
            chapters
        }

        return SMangaUpdate(
            updatedManga,
            updatedChapters,
        )
    }

    // -------------------------------------------------------------------------
    // 漫画详情解析
    // -------------------------------------------------------------------------

    private fun parseManga(
        document: Document,
        mangaId: String,
    ): SManga {
        val manga = SManga.create()

        manga.setUrlWithoutDomain("$baseUrl/books/$mangaId")

        val title = firstNonBlank(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=twitter:title]")?.attr("content"),
            document.title(),
        )

        if (title.isBlank()) {
            throw IllegalStateException(
                "Missing manga title: $baseUrl/books/$mangaId",
            )
        }

        manga.title = cleanMangaTitle(title)

        val thumbnail = firstNonBlank(
            document.selectFirst("meta[property=og:image]")
                ?.attr("content"),

            document.selectFirst("meta[name=twitter:image]")
                ?.attr("content"),

            document.selectFirst("img")
                ?.absUrl("src"),

            document.selectFirst("img")
                ?.absUrl("data-src"),

            document.selectFirst("img")
                ?.absUrl("data-original"),
        )

        if (thumbnail.isNotBlank()) {
            manga.thumbnail_url = thumbnail
        }

        val author = document.selectFirst(
            "a[href*=\"/authors/\"], .author, [class*=author]",
        )?.text()?.trim()

        if (!author.isNullOrBlank()) {
            manga.author = author
        }

        manga.status = SManga.COMPLETED

        manga.description = document
            .selectFirst(
                ".description, .summary, [class*=description], [class*=summary]",
            )
            ?.text()
            ?.trim()

        val tags = document
            .select(
                "a[href*=\"/tags/\"], .tag, [class*=tag]",
            )
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (tags.isNotEmpty()) {
            manga.genre = tags.joinToString(", ")
        }

        return manga
    }

    // -------------------------------------------------------------------------
    // 章节列表
    // -------------------------------------------------------------------------

    private fun parseChapters(
        document: Document,
        mangaId: String,
    ): List<SChapter> {
        val prefix = "/books/$mangaId/"

        return document
            .select("a[href*=\"$prefix\"]")
            .mapNotNull { link ->
                val href = link.absUrl("href")

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val chapterId = extractChapterId(
                    href,
                    mangaId,
                )

                if (chapterId.isBlank()) {
                    return@mapNotNull null
                }

                val name = link.text().trim()

                if (name.isBlank()) {
                    return@mapNotNull null
                }

                SChapter.create().apply {
                    setUrlWithoutDomain(
                        "$baseUrl/books/$mangaId/$chapterId",
                    )

                    this.name = name

                    chapter_number = extractChapterNumber(name)

                    date_upload = 0L
                }
            }
            .distinctBy { it.url }
            .reversed()
    }

    // -------------------------------------------------------------------------
    // ★★★ 阅读页 ★★★
    //
    // 不再打开网页抓 <img>
    //
    // 直接请求：
    //
    // /api/books/{漫画ID}/{章节ID}
    //
    // 然后读取：
    //
    // chapter.images[*].src
    //
    // 这样网页广告不会进入 Tachimanga。
    // -------------------------------------------------------------------------

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {
        val chapterUrl = chapter.url.trim('/')

        val parts = chapterUrl.split('/')

        if (parts.size < 2) {
            throw IllegalStateException(
                "Invalid chapter URL: ${chapter.url}",
            )
        }

        val mangaId = parts[0]
        val chapterId = parts[1]

        if (mangaId.isBlank() || chapterId.isBlank()) {
            throw IllegalStateException(
                "Invalid manga/chapter ID: ${chapter.url}",
            )
        }

        /*
         * 肉漫屋章节 API
         *
         * /api/books/{mangaId}/{chapterId}
         */
        val apiUrl =
            "$baseUrl/api/books/$mangaId/$chapterId"

        val body = client.get(apiUrl).use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Chapter API failed: HTTP ${response.code}",
                )
            }

            response.body.string()
        }

        if (body.isBlank()) {
            throw IllegalStateException(
                "Empty chapter API response: $apiUrl",
            )
        }

        val imageUrls = parseChapterImages(body)

        if (imageUrls.isEmpty()) {
            throw IllegalStateException(
                "No manga images found: $apiUrl",
            )
        }

        return imageUrls.mapIndexed { index, imageUrl ->
            Page(
                index = index,
                imageUrl = imageUrl,
            )
        }
    }

    // -------------------------------------------------------------------------
    // API 图片解析
    // -------------------------------------------------------------------------

    private fun parseChapterImages(
        body: String,
    ): List<String> {
        val result = mutableListOf<String>()

        /*
         * 第一种方式：
         *
         * {
         *   "chapter": {
         *     "images": [
         *       {
         *         "src": "https://..."
         *       }
         *     ]
         *   }
         * }
         */

        try {
            val root = org.json.JSONObject(body)

            val chapterObject =
                root.optJSONObject("chapter")

            if (chapterObject != null) {
                val images =
                    chapterObject.optJSONArray("images")

                if (images != null) {
                    for (index in 0 until images.length()) {
                        val image =
                            images.optJSONObject(index)
                                ?: continue

                        val src =
                            image.optString("src")
                                .trim()

                        if (isValidImageUrl(src)) {
                            result += src
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // JSON 结构发生变化时使用备用解析
        }

        /*
         * 第二种方式：
         *
         * 有些情况下 API 返回的数据结构可能变化。
         *
         * 使用正则作为备用解析。
         */
        if (result.isEmpty()) {
            val regex = Regex(
                """["']src["']\s*:\s*["'](https?://[^"']+)["']""",
            )

            regex
                .findAll(body)
                .map {
                    it.groupValues[1]
                }
                .map {
                    it
                        .replace("\\/", "/")
                        .replace("\\u002F", "/")
                }
                .filter {
                    isValidImageUrl(it)
                }
                .distinct()
                .forEach {
                    result += it
                }
        }

        /*
         * 最终清理：
         *
         * 1. 去重
         * 2. 去掉 loading 图片
         * 3. 去掉明显广告图片
         */
        return result
            .map { it.trim() }
            .filter { isValidImageUrl(it) }
            .filterNot {
                it.contains(
                    "loading",
                    ignoreCase = true,
                )
            }
            .filterNot {
                it.contains(
                    "placeholder",
                    ignoreCase = true,
                )
            }
            .filterNot {
                it.contains(
                    "avatar",
                    ignoreCase = true,
                )
            }
            .distinct()
    }

    // -------------------------------------------------------------------------
    // 图片 URL 判断
    // -------------------------------------------------------------------------

    private fun isValidImageUrl(
        url: String,
    ): Boolean {
        if (url.isBlank()) {
            return false
        }

        if (
            !url.startsWith("https://") &&
            !url.startsWith("http://")
        ) {
            return false
        }

        val lower = url.lowercase()

        /*
         * 排除明显不是漫画正文的资源。
         */
        val blockedKeywords = listOf(
            "logo",
            "favicon",
            "banner",
            "avatar",
            "icon",
            "loading",
            "placeholder",
            "advert",
            "ads",
            "doubleclick",
            "googlead",
        )

        return blockedKeywords.none {
            lower.contains(it)
        }
    }

    // -------------------------------------------------------------------------
    // URL
    // -------------------------------------------------------------------------

    override fun getMangaUrl(
        manga: SManga,
    ): String {
        val url = manga.url.trim('/')

        return if (url.startsWith("http://") ||
            url.startsWith("https://")
        ) {
            url
        } else if (url.startsWith("books/")) {
            "$baseUrl/$url"
        } else {
            "$baseUrl/books/$url"
        }
    }

    override fun getChapterUrl(
        chapter: SChapter,
    ): String {
        val url = chapter.url.trim('/')

        return if (url.startsWith("http://") ||
            url.startsWith("https://")
        ) {
            url
        } else if (url.startsWith("books/")) {
            "$baseUrl/$url"
        } else {
            "$baseUrl/books/$url"
        }
    }

    // -------------------------------------------------------------------------
    // Filter
    // -------------------------------------------------------------------------

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("肉漫屋不提供额外搜索筛选"),
        )
    }

    // -------------------------------------------------------------------------
    // 工具：提取漫画 ID
    // -------------------------------------------------------------------------

    private fun extractMangaId(
        url: HttpUrl,
    ): String {
        return extractMangaId(
            url.encodedPath,
        )
    }

    private fun extractMangaId(
        url: String,
    ): String {
        val path = url
            .substringBefore("?")
            .trim('/')

        val parts = path.split('/')

        val booksIndex =
            parts.indexOfFirst {
                it.equals(
                    "books",
                    ignoreCase = true,
                )
            }

        if (booksIndex >= 0 &&
            booksIndex + 1 < parts.size
        ) {
            return parts[booksIndex + 1]
        }

        return path
            .removePrefix("books/")
            .substringBefore('/')
            .trim()
    }

    // -------------------------------------------------------------------------
    // 工具：提取章节 ID
    // -------------------------------------------------------------------------

    private fun extractChapterId(
        url: String,
        mangaId: String,
    ): String {
        val path = url
            .substringBefore("?")
            .trim('/')

        val prefix = "books/$mangaId/"

        if (path.startsWith(prefix)) {
            return path
                .removePrefix(prefix)
                .substringBefore('/')
                .trim()
        }

        val parts = path.split('/')

        val booksIndex =
            parts.indexOfFirst {
                it.equals(
                    "books",
                    ignoreCase = true,
                )
            }

        if (booksIndex >= 0 &&
            booksIndex + 2 < parts.size
        ) {
            return parts[booksIndex + 2]
        }

        return ""
    }

    // -------------------------------------------------------------------------
    // 工具：章节号
    // -------------------------------------------------------------------------

    private fun extractChapterNumber(
        text: String,
    ): Float {
        val match = Regex(
            """(?i)(?:chapter|chap|第)\s*([0-9]+(?:\.[0-9]+)?)""",
        ).find(text)

        return match
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?: 0F
    }

    // -------------------------------------------------------------------------
    // 工具：标题清理
    // -------------------------------------------------------------------------

    private fun cleanMangaTitle(
        title: String,
    ): String {
        return title
            .trim()
            .replace(
                Regex(
                    """\s*[-|｜]\s*肉漫屋.*$""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .trim()
    }

    // -------------------------------------------------------------------------
    // 工具：第一个非空字符串
    // -------------------------------------------------------------------------

    private fun firstNonBlank(
        vararg values: String?,
    ): String {
        return values
            .firstOrNull {
                !it.isNullOrBlank()
            }
            ?.trim()
            ?: ""
    }

    // -------------------------------------------------------------------------
    // 工具：URL 编码
    // -------------------------------------------------------------------------

    private fun encode(
        value: String,
    ): String {
        return java.net.URLEncoder
            .encode(
                value,
                Charsets.UTF_8.name(),
            )
    }
}
