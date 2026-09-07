package eu.kanade.tachiyomi.extension.zh.rouman5

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.network.get
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Source
abstract class Rouman5 : KeiSource() {

    override val supportsLatest = true

    // ========================================================================
    // 热门漫画
    // ========================================================================

    override suspend fun getPopularManga(
        page: Int,
    ): MangasPage {

        val url = "$baseUrl/home"

        val response = client.get(url)

        val document = Jsoup.parse(
            response.body.string(),
            url,
        )

        return parseHomePage(
            document,
            Regex("正熱門|今日最佳|本週熱門"),
        )
    }

    private fun parseHomePage(
        document: Document,
        sectionRegex: Regex,
    ): MangasPage {

        val entries = mutableListOf<SManga>()

        /*
         * 肉漫屋首页结构：
         *
         * div.px-1
         *   ├── section
         *   ├── section
         *   ├── section
         *
         * 根据 section 标题判断热门分类。
         */

        val container =
            document.selectFirst("div.px-1")

        if (container != null) {

            for (section in container.children()) {

                val text = section.text()

                if (!sectionRegex.containsMatchIn(text)) {
                    continue
                }

                entries += parseEntries(section)
            }
        }

        return MangasPage(
            entries.distinctBy { it.url },
            false,
        )
    }

    // ========================================================================
    // 最新更新
    // ========================================================================

    override suspend fun getLatestUpdates(
        page: Int,
    ): MangasPage {

        val url = "$baseUrl/home"

        val response = client.get(url)

        val document = Jsoup.parse(
            response.body.string(),
            url,
        )

        return parseHomePage(
            document,
            Regex("最近更新"),
        )
    }

    // ========================================================================
    // 首页漫画解析
    // ========================================================================

    private fun parseEntries(
        container: Element,
    ): List<SManga> {

        return container
            .select("a[href*=\"/books/\"]")
            .mapNotNull { element ->

                val href =
                    element.absUrl("href")

                if (
                    href.isBlank() ||
                    !href.contains("/books/")
                ) {
                    return@mapNotNull null
                }

                val title =
                    firstNonBlank(
                        element.selectFirst("div.truncate")?.text(),
                        element.selectFirst("h1")?.text(),
                        element.selectFirst("h2")?.text(),
                        element.selectFirst("h3")?.text(),
                        element.text(),
                    )

                if (title.isBlank()) {
                    return@mapNotNull null
                }

                val image =
                    firstNonBlank(
                        element
                            .selectFirst("img")
                            ?.absUrl("src"),

                        element
                            .selectFirst("img")
                            ?.absUrl("data-src"),

                        element
                            .selectFirst("img")
                            ?.absUrl("data-original"),
                    )

                SManga.create().apply {

                    setUrlWithoutDomain(href)

                    this.title =
                        cleanMangaTitle(title)

                    if (image.isNotBlank()) {
                        thumbnail_url = image
                    }
                }
            }
            .distinctBy {
                it.url
            }
    }

    // ========================================================================
    // 搜索
    // ========================================================================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {

        if (query.isBlank()) {
            return MangasPage(
                emptyList(),
                false,
            )
        }

        val encodedQuery =
            URLEncoder.encode(
                query,
                StandardCharsets.UTF_8.name(),
            )

        /*
         * Roumanwu 原实现：
         *
         * /search?term=xxx&page=0
         *
         * 注意这里是 page - 1。
         */

        val url =
            "$baseUrl/search?term=$encodedQuery&page=${page - 1}"

        val response =
            client.get(url)

        val document =
            Jsoup.parse(
                response.body.string(),
                url,
            )

        val entries =
            parseEntries(document)

        val hasNextPage =
            document.selectFirst(
                "div.justify-end > a:contains(下一頁)",
            ) != null

        return MangasPage(
            entries,
            hasNextPage,
        )
    }

    // ========================================================================
    // 漫画详情
    // ========================================================================

    override suspend fun getMangaByUrl(
        url: HttpUrl,
    ): SManga? {

        val mangaId =
            extractMangaId(url)

        if (mangaId.isBlank()) {
            return null
        }

        val mangaUrl =
            "$baseUrl/books/$mangaId"

        val response =
            client.get(mangaUrl)

        val document =
            Jsoup.parse(
                response.body.string(),
                mangaUrl,
            )

        return parseManga(
            document,
            mangaId,
        )
    }

    // ========================================================================
    // 漫画详情 + 章节
    // ========================================================================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {

        val mangaId =
            extractMangaId(manga.url)

        if (mangaId.isBlank()) {
            return SMangaUpdate(
                manga,
                chapters,
            )
        }

        val mangaUrl =
            "$baseUrl/books/$mangaId"

        val response =
            client.get(mangaUrl)

        val document =
            Jsoup.parse(
                response.body.string(),
                mangaUrl,
            )

        val updatedManga =
            if (fetchDetails) {
                parseManga(
                    document,
                    mangaId,
                )
            } else {
                manga
            }

        val updatedChapters =
            if (fetchChapters) {
                parseChapters(
                    document,
                    mangaId,
                )
            } else {
                chapters
            }

        return SMangaUpdate(
            updatedManga,
            updatedChapters,
        )
    }

    // ========================================================================
    // 解析漫画详情
    // ========================================================================

    private fun parseManga(
        document: Document,
        mangaId: String,
    ): SManga {

        val manga =
            SManga.create()

        manga.setUrlWithoutDomain(
            "$baseUrl/books/$mangaId",
        )

        /*
         * 优先按照 Roumanwu 的实际页面结构解析。
         */

        val title =
            firstNonBlank(
                document.selectFirst(
                    "div.basis-3\\/5 > div.text-xl",
                )?.text(),

                document.selectFirst("h1")?.text(),

                document.selectFirst(
                    "meta[property=og:title]",
                )?.attr("content"),

                document.title(),
            )

        manga.title =
            cleanMangaTitle(title)

        /*
         * 封面
         */

        val thumbnail =
            firstNonBlank(

                document
                    .selectFirst(
                        "div.basis-2\\/5 img",
                    )
                    ?.absUrl("src"),

                document
                    .selectFirst(
                        "meta[property=og:image]",
                    )
                    ?.attr("content"),

                document
                    .selectFirst("img")
                    ?.absUrl("src"),

                document
                    .selectFirst("img")
                    ?.absUrl("data-src"),
            )

        if (thumbnail.isNotBlank()) {
            manga.thumbnail_url =
                thumbnail
        }

        /*
         * 尝试读取信息框。
         */

        val info =
            document.selectFirst(
                "div.basis-3\\/5",
            )

        if (info != null) {

            val lines =
                info.children()
                    .map {
                        it.text().trim()
                    }
                    .filter {
                        it.isNotBlank()
                    }

            val genres =
                mutableListOf<String>()

            for (line in lines) {

                when {

                    line.startsWith("作者:") -> {
                        manga.author =
                            line.removePrefix("作者:")
                                .trim()
                    }

                    line.startsWith("狀態:") -> {

                        val value =
                            line.removePrefix("狀態:")
                                .trim()

                        manga.status =
                            when (value) {
                                "連載中" ->
                                    SManga.ONGOING

                                "已完結" ->
                                    SManga.COMPLETED

                                else ->
                                    SManga.UNKNOWN
                            }
                    }

                    line.startsWith("地區:") -> {

                        val value =
                            line.removePrefix("地區:")
                                .trim()

                        if (value.isNotBlank()) {
                            genres += value
                        }
                    }

                    line.startsWith("標籤:") -> {

                        val value =
                            line.removePrefix("標籤:")
                                .trim()

                        if (value.isNotBlank()) {

                            genres +=
                                value
                                    .split(",")
                                    .map {
                                        it.trim()
                                    }
                                    .filter {
                                        it.isNotBlank()
                                    }
                        }
                    }
                }
            }

            if (genres.isNotEmpty()) {
                manga.genre =
                    genres.distinct()
                        .joinToString(", ")
            }
        }

        /*
         * 简介
         */

        val description =
            firstNonBlank(

                document
                    .selectFirst(
                        "p:contains(簡介:)",
                    )
                    ?.text()
                    ?.removePrefix("簡介:")
                    ?.trim(),

                document
                    .selectFirst(
                        ".description",
                    )
                    ?.text(),

                document
                    .selectFirst(
                        ".summary",
                    )
                    ?.text(),
            )

        if (description.isNotBlank()) {
            manga.description =
                description
        }

        if (
            manga.status == 0
        ) {
            manga.status =
                SManga.UNKNOWN
        }

        return manga
    }

    // ========================================================================
    // 章节
    // ========================================================================

    private fun parseChapters(
        document: Document,
        mangaId: String,
    ): List<SChapter> {

        val prefix =
            "/books/$mangaId/"

        return document
            .select(
                "a[href*=\"$prefix\"]",
            )
            .filter { element ->

                /*
                 * 必须是：
                 *
                 * /books/{mangaId}/{chapterId}
                 *
                 * 而不是：
                 *
                 * /books/{mangaId}
                 */

                val href =
                    element.attr("href")

                val path =
                    href
                        .substringBefore("?")
                        .trim('/')

                val parts =
                    path.split('/')

                parts.size >= 3 &&
                    parts[0] == "books" &&
                    parts[1] == mangaId
            }
            .mapNotNull { link ->

                val href =
                    link.absUrl("href")

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val chapterId =
                    extractChapterId(
                        href,
                        mangaId,
                    )

                if (chapterId.isBlank()) {
                    return@mapNotNull null
                }

                val name =
                    link.text().trim()

                if (name.isBlank()) {
                    return@mapNotNull null
                }

                SChapter.create().apply {

                    setUrlWithoutDomain(
                        "$baseUrl/books/$mangaId/$chapterId",
                    )

                    this.name =
                        name

                    chapter_number =
                        extractChapterNumber(
                            name,
                        )

                    date_upload = 0L
                }
            }
            .distinctBy {
                it.url
            }
            .asReversed()
    }

    // ========================================================================
    // ★★★ 阅读章节 ★★★
    //
    // 这里是本次最重要的修改。
    //
    // 不再请求：
    //
    // /api/books/...
    //
    // 而是请求：
    //
    // /books/{mangaId}/{chapterId}
    //
    // 并添加：
    //
    // rsc: 1
    //
    // 与你提供的 Roumanwu 源保持一致。
    // ========================================================================

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {

        val chapterUrl =
            getChapterUrl(chapter)

        val request =
            Request.Builder()
                .url(chapterUrl)
                .headers(headers)
                .addHeader(
                    "rsc",
                    "1",
                )
                .get()
                .build()

        val response =
            client.newCall(request)
                .execute()

        if (!response.isSuccessful) {

            throw IllegalStateException(
                "Chapter page failed: HTTP ${response.code}",
            )
        }

        val html =
            response.body.string()

        if (html.isBlank()) {

            throw IllegalStateException(
                "Empty chapter response: $chapterUrl",
            )
        }

        return parsePages(
            html,
        )
    }

    // ========================================================================
    // 解析章节图片
    // ========================================================================

    private fun parsePages(
        html: String,
    ): List<Page> {

        /*
         * Roumanwu 的关键：
         *
         * "imageUrl":"https://..."
         *
         * 所以 Rouman5 也优先使用 imageUrl。
         */

        val regex =
            Regex(
                """"imageUrl":"([^"]+)"""",
            )

        val urls =
            regex
                .findAll(html)
                .map {
                    it.groupValues[1]
                }
                .map {
                    decodeJsonUrl(it)
                }
                .filter {
                    isValidImageUrl(it)
                }
                .distinct()
                .toList()

        if (urls.isEmpty()) {

            /*
             * 备用：
             *
             * 如果 Next.js 返回的数据中字段变成 src，
             * 再尝试 src。
             */

            val srcRegex =
                Regex(
                    """"src":"(https?://[^"]+)"""",
                )

            val fallback =
                srcRegex
                    .findAll(html)
                    .map {
                        it.groupValues[1]
                    }
                    .map {
                        decodeJsonUrl(it)
                    }
                    .filter {
                        isValidImageUrl(it)
                    }
                    .distinct()
                    .toList()

            if (fallback.isNotEmpty()) {

                return fallback.mapIndexed { index, url ->

                    Page(
                        index = index,
                        imageUrl = url,
                    )
                }
            }

            throw IllegalStateException(
                "No manga images found in chapter response",
            )
        }

        return urls.mapIndexed { index, url ->

            Page(
                index = index,
                imageUrl = url,
            )
        }
    }

    // ========================================================================
    // 图片 URL
    // ========================================================================

    private fun isValidImageUrl(
        url: String,
    ): Boolean {

        if (url.isBlank()) {
            return false
        }

        if (
            !url.startsWith("http://") &&
            !url.startsWith("https://")
        ) {
            return false
        }

        val lower =
            url.lowercase()

        val blocked =
            listOf(
                "favicon",
                "banner",
                "avatar",
                "loading",
                "placeholder",
                "doubleclick",
                "googlead",
                "ads.",
                "/ads/",
            )

        return blocked.none {
            lower.contains(it)
        }
    }

    private fun decodeJsonUrl(
        url: String,
    ): String {

        return url
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u003A", ":")
            .replace("\\u003a", ":")
    }

    // ========================================================================
    // Manga URL
    // ========================================================================

    override fun getMangaUrl(
        manga: SManga,
    ): String {

        val url =
            manga.url.trim('/')

        return when {

            url.startsWith("http://") ||
                url.startsWith("https://") -> {
                url
            }

            url.startsWith("books/") -> {
                "$baseUrl/$url"
            }

            else -> {
                "$baseUrl/books/$url"
            }
        }
    }

    // ========================================================================
    // Chapter URL
    // ========================================================================

    override fun getChapterUrl(
        chapter: SChapter,
    ): String {

        val url =
            chapter.url.trim('/')

        return when {

            url.startsWith("http://") ||
                url.startsWith("https://") -> {
                url
            }

            url.startsWith("books/") -> {
                "$baseUrl/$url"
            }

            else -> {
                "$baseUrl/books/$url"
            }
        }
    }

    // ========================================================================
    // Manga ID
    // ========================================================================

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

        val path =
            url
                .substringBefore("?")
                .trim('/')

        val parts =
            path.split('/')

        val booksIndex =
            parts.indexOfFirst {
                it.equals(
                    "books",
                    ignoreCase = true,
                )
            }

        if (
            booksIndex >= 0 &&
            booksIndex + 1 < parts.size
        ) {
            return parts[
                booksIndex + 1
            ]
        }

        return path
            .removePrefix("books/")
            .substringBefore('/')
            .trim()
    }

    // ========================================================================
    // Chapter ID
    // ========================================================================

    private fun extractChapterId(
        url: String,
        mangaId: String,
    ): String {

        val path =
            url
                .substringBefore("?")
                .trim('/')

        val parts =
            path.split('/')

        val booksIndex =
            parts.indexOfFirst {
                it.equals(
                    "books",
                    ignoreCase = true,
                )
            }

        if (
            booksIndex >= 0 &&
            booksIndex + 2 < parts.size
        ) {

            val id =
                parts[
                    booksIndex + 2
                ]

            if (id.isNotBlank()) {
                return id
            }
        }

        return ""
    }

    // ========================================================================
    // Chapter number
    // ========================================================================

    private fun extractChapterNumber(
        text: String,
    ): Float {

        val match =
            Regex(
                """(?i)(?:chapter|chap|第)\s*([0-9]+(?:\.[0-9]+)?)""",
            ).find(text)

        return match
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?: 0F
    }

    // ========================================================================
    // 清理标题
    // ========================================================================

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

    // ========================================================================
    // 第一个非空字符串
    // ========================================================================

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
}
