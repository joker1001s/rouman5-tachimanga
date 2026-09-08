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
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Source
abstract class Rouman5 : KeiSource() {

    override val supportsLatest = true

    // ========================================================================
    // HTTP Client
    // ========================================================================

    /**
     * 肉漫屋漫画图片存在分块倒序。
     *
     * KeiSource 的 client 是 final，
     * 所以通过 configureClient() 添加拦截器。
     */
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        return addInterceptor(
            ScrambledImageInterceptor(),
        )
    }

    // ========================================================================
    // Filters
    // ========================================================================

    private class StatusFilter : Filter.Select<String>(
        "状态",
        arrayOf(
            "全部",
            "连载中",
            "已完结",
        ),
    )

    override fun getFilterList(
        data: JsonElement?,
    ): FilterList {
        return FilterList(
            StatusFilter(),
        )
    }

    // ========================================================================
    // 热门
    // ========================================================================

    override suspend fun getPopularManga(
        page: Int,
    ): MangasPage {

        val url =
            "$baseUrl/home"

        val document =
            client.get(url).use {
                Jsoup.parse(
                    it.body.string(),
                    url,
                )
            }

        return parseHomePage(
            document,
            Regex(
                "正熱門|今日最佳|本週熱門",
            ),
        )
    }

    // ========================================================================
    // 最新
    // ========================================================================

    override suspend fun getLatestUpdates(
        page: Int,
    ): MangasPage {

        val url =
            "$baseUrl/home"

        val document =
            client.get(url).use {
                Jsoup.parse(
                    it.body.string(),
                    url,
                )
            }

        return parseHomePage(
            document,
            Regex(
                "最近更新",
            ),
        )
    }

    // ========================================================================
    // 首页解析
    // ========================================================================

    private fun parseHomePage(
        document: Document,
        sectionRegex: Regex,
    ): MangasPage {

        val container =
            document.selectFirst(
                "div.px-1",
            )
                ?: return MangasPage(
                    emptyList(),
                    false,
                )

        val entries =
            container
                .children()
                .flatMap { section ->

                    if (
                        sectionRegex.containsMatchIn(
                            section.text(),
                        )
                    ) {
                        parseEntries(
                            section,
                        )
                    } else {
                        emptyList()
                    }
                }
                .distinctBy {
                    it.url
                }

        return MangasPage(
            entries,
            false,
        )
    }

    private fun parseEntries(
        container: Element,
    ): List<SManga> {

        return container
            .select(
                "a[href*=\"/books/\"]",
            )
            .mapNotNull { element ->

                val href =
                    element.absUrl(
                        "href",
                    )

                if (
                    href.isBlank()
                ) {
                    return@mapNotNull null
                }

                val title =
                    firstNonBlank(
                        element
                            .selectFirst(
                                "div.truncate",
                            )
                            ?.text(),

                        element
                            .selectFirst(
                                "h1",
                            )
                            ?.text(),

                        element
                            .selectFirst(
                                "h2",
                            )
                            ?.text(),

                        element
                            .selectFirst(
                                "h3",
                            )
                            ?.text(),

                        element.text(),
                    )

                if (
                    title.isBlank()
                ) {
                    return@mapNotNull null
                }

                val image =
                    element
                        .selectFirst(
                            "img",
                        )
                        ?.let {
                            firstNonBlank(
                                it.absUrl(
                                    "src",
                                ),
                                it.absUrl(
                                    "data-src",
                                ),
                                it.absUrl(
                                    "data-original",
                                ),
                                it.absUrl(
                                    "data-lazy-src",
                                ),
                            )
                        }
                        ?: ""

                SManga.create().apply {

                    setUrlWithoutDomain(
                        href,
                    )

                    this.title =
                        cleanMangaTitle(
                            title,
                        )

                    if (
                        image.isNotBlank()
                    ) {
                        thumbnail_url =
                            image
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

        val statusFilter =
            filters.find {
                it is StatusFilter
            } as? StatusFilter

        val continued =
            when (
                statusFilter?.state
            ) {
                1 -> "true"
                2 -> "false"
                else -> null
            }

        val pageIndex =
            page - 1

        val url =
            if (
                query.isBlank()
            ) {

                buildString {

                    append(
                        "$baseUrl/books?page=$pageIndex",
                    )

                    if (
                        continued != null
                    ) {
                        append(
                            "&continued=$continued",
                        )
                    }
                }

            } else {

                val encodedQuery =
                    URLEncoder.encode(
                        query,
                        StandardCharsets.UTF_8.name(),
                    )

                buildString {

                    append(
                        "$baseUrl/search?term=$encodedQuery&page=$pageIndex",
                    )

                    if (
                        continued != null
                    ) {
                        append(
                            "&continued=$continued",
                        )
                    }
                }
            }

        val document =
            client.get(url).use {
                Jsoup.parse(
                    it.body.string(),
                    url,
                )
            }

        val entries =
            parseEntries(
                document,
            )

        val hasNextPage =
            document.selectFirst(
                "a:contains(下一頁)",
            ) != null ||
                document.selectFirst(
                    "a:contains(下一頁 →)",
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
            extractMangaId(
                url,
            )

        if (
            mangaId.isBlank()
        ) {
            return null
        }

        val mangaUrl =
            "$baseUrl/books/$mangaId"

        val document =
            client.get(mangaUrl).use {
                Jsoup.parse(
                    it.body.string(),
                    mangaUrl,
                )
            }

        return parseManga(
            document,
            mangaId,
        )
    }

    private fun parseManga(
        document: Document,
        mangaId: String,
    ): SManga {

        val manga =
            SManga.create()

        manga.setUrlWithoutDomain(
            "$baseUrl/books/$mangaId",
        )

        // --------------------------------------------------------------------
        // 标题
        // --------------------------------------------------------------------

        manga.title =
            cleanMangaTitle(
                firstNonBlank(
                    document
                        .selectFirst(
                            "div.basis-3\\/5 > div.text-xl",
                        )
                        ?.text(),

                    document
                        .selectFirst(
                            "h1",
                        )
                        ?.text(),

                    document
                        .selectFirst(
                            "meta[property=og:title]",
                        )
                        ?.attr(
                            "content",
                        ),

                    document.title(),
                ),
            )

        // --------------------------------------------------------------------
        // 封面
        // --------------------------------------------------------------------

        val thumbnail =
            firstNonBlank(

                document
                    .selectFirst(
                        "div.basis-2\\/5 img",
                    )
                    ?.absUrl(
                        "src",
                    ),

                document
                    .selectFirst(
                        "meta[property=og:image]",
                    )
                    ?.attr(
                        "content",
                    ),

                document
                    .selectFirst(
                        "img",
                    )
                    ?.absUrl(
                        "src",
                    ),

                document
                    .selectFirst(
                        "img",
                    )
                    ?.absUrl(
                        "data-src",
                    ),
            )

        if (
            thumbnail.isNotBlank()
        ) {
            manga.thumbnail_url =
                thumbnail
        }

        // --------------------------------------------------------------------
        // 信息
        // --------------------------------------------------------------------

        val info =
            document.selectFirst(
                "div.basis-3\\/5",
            )

        if (
            info != null
        ) {

            val genres =
                mutableListOf<String>()

            info.children()
                .map {
                    it.text()
                }
                .filter {
                    it.isNotBlank()
                }
                .forEach { line ->

                    when {

                        line.startsWith(
                            "作者:",
                        ) -> {

                            manga.author =
                                line
                                    .removePrefix(
                                        "作者:",
                                    )
                                    .trim()
                        }

                        line.startsWith(
                            "狀態:",
                        ) -> {

                            val status =
                                line
                                    .removePrefix(
                                        "狀態:",
                                    )
                                    .trim()

                            manga.status =
                                when (
                                    status
                                ) {
                                    "連載中" ->
                                        SManga.ONGOING

                                    "已完結" ->
                                        SManga.COMPLETED

                                    else ->
                                        SManga.UNKNOWN
                                }
                        }

                        line.startsWith(
                            "地區:",
                        ) -> {

                            val value =
                                line
                                    .removePrefix(
                                        "地區:",
                                    )
                                    .trim()

                            if (
                                value.isNotBlank()
                            ) {
                                genres +=
                                    value
                            }
                        }

                        line.startsWith(
                            "標籤:",
                        ) -> {

                            val value =
                                line
                                    .removePrefix(
                                        "標籤:",
                                    )
                                    .trim()

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

            manga.genre =
                genres
                    .distinct()
                    .joinToString()
        }

        // --------------------------------------------------------------------
        // 简介
        // --------------------------------------------------------------------

        val description =
            firstNonBlank(

                document
                    .selectFirst(
                        "p:contains(簡介:)",
                    )
                    ?.text()
                    ?.removePrefix(
                        "簡介:",
                    )
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

        if (
            description.isNotBlank()
        ) {
            manga.description =
                description
        }

        return manga
    }

    // ========================================================================
    // 更新漫画
    // ========================================================================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {

        val mangaId =
            extractMangaId(
                manga.url,
            )

        if (
            mangaId.isBlank()
        ) {
            return SMangaUpdate(
                manga,
                chapters,
            )
        }

        val mangaUrl =
            "$baseUrl/books/$mangaId"

        val document =
            client.get(mangaUrl).use {
                Jsoup.parse(
                    it.body.string(),
                    mangaUrl,
                )
            }

        val updatedManga =
            parseManga(
                document,
                mangaId,
            )

        val updatedChapters =
            parseChapters(
                document,
                mangaId,
            )

        return SMangaUpdate(
            updatedManga,
            updatedChapters,
        )
    }

    // ========================================================================
    // 章节列表
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
            .mapNotNull { link ->

                val href =
                    link.absUrl(
                        "href",
                    )

                if (
                    href.isBlank()
                ) {
                    return@mapNotNull null
                }

                val chapterId =
                    extractChapterId(
                        href,
                        mangaId,
                    )

                if (
                    chapterId.isBlank()
                ) {
                    return@mapNotNull null
                }

                val name =
                    link.text()

                if (
                    name.isBlank()
                ) {
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
                }
            }
            .distinctBy {
                it.url
            }
            .asReversed()
    }

    // ========================================================================
    // ★ 章节页面
    // ★ 官方 Roumanwu RSC 方案
    // ========================================================================

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {

        val chapterUrl =
            getChapterUrl(
                chapter,
            )

        /*
         * 肉漫屋使用 Next.js。
         *
         * 普通 GET 得到的是普通 HTML，
         * 漫画阅读数据位于 Next.js RSC 响应中。
         *
         * 因此必须发送：
         *
         *     rsc: 1
         *
         * 官方 Roumanwu 实现就是这个方案。
         */

        val rscHeaders =
            headers.newBuilder()
                .add(
                    "rsc",
                    "1",
                )
                .build()

        val html =
            client.get(
                chapterUrl,
                rscHeaders,
            ).use {
                it.body.string()
            }

        if (
            html.isBlank()
        ) {
            return emptyList()
        }

        return parsePages(
            html,
        )
    }

    // ========================================================================
    // ★ RSC 页面图片解析
    // ========================================================================

    private fun parsePages(
        html: String,
    ): List<Page> {

        /*
         * 官方 Roumanwu：
         *
         * Regex(""""imageUrl":"([^"]+)"""")
         *
         * 这里不要：
         *
         * 1. 解析普通 <img>
         * 2. 判断 /sr:1/
         * 3. 判断图片扩展名
         * 4. 判断广告关键词
         *
         * /sr:1/ 是图片下载阶段由
         * ScrambledImageInterceptor 处理的。
         */

        return IMAGE_URL_REGEX
            .findAll(
                html,
            )
            .map {
                decodeJsonUrl(
                    it.groupValues[1],
                )
            }
            .filter {
                it.startsWith(
                    "http://",
                ) ||
                    it.startsWith(
                        "https://",
                    )
            }
            .distinct()
            .mapIndexed {
                    index,
                    url,
                ->

                Page(
                    index,
                    imageUrl = url,
                )
            }
            .toList()
    }

    // ========================================================================
    // JSON URL 解码
    // ========================================================================

    private fun decodeJsonUrl(
        url: String,
    ): String {

        return url
            .replace(
                "\\/",
                "/",
            )
            .replace(
                "\\u002F",
                "/",
            )
            .replace(
                "\\u002f",
                "/",
            )
            .replace(
                "\\u003A",
                ":",
            )
            .replace(
                "\\u003a",
                ":",
            )
            .replace(
                "\\u003F",
                "?",
            )
            .replace(
                "\\u003f",
                "?",
            )
            .replace(
                "\\u003D",
                "=",
            )
            .replace(
                "\\u003d",
                "=",
            )
            .replace(
                "\\u0026",
                "&",
            )
            .trim()
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

            url.startsWith(
                "http://",
            ) ||
                url.startsWith(
                    "https://",
                ) -> {
                url
            }

            url.startsWith(
                "books/",
            ) -> {
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

            url.startsWith(
                "http://",
            ) ||
                url.startsWith(
                    "https://",
                ) -> {
                url
            }

            url.startsWith(
                "books/",
            ) -> {
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

        val parts =
            url
                .substringBefore("?")
                .trim('/')
                .split('/')

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

        return ""
    }

    // ========================================================================
    // Chapter ID
    // ========================================================================

    private fun extractChapterId(
        url: String,
        mangaId: String,
    ): String {

        val parts =
            url
                .substringBefore("?")
                .trim('/')
                .split('/')

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

            val currentMangaId =
                parts[
                    booksIndex + 1
                ]

            if (
                currentMangaId != mangaId
            ) {
                return ""
            }

            return parts[
                booksIndex + 2
            ]
        }

        return ""
    }

    // ========================================================================
    // Chapter Number
    // ========================================================================

    private fun extractChapterNumber(
        text: String,
    ): Float {

        return Regex(
            """(?i)(?:chapter|chap|第)\s*([0-9]+(?:\.[0-9]+)?)""",
        )
            .find(
                text,
            )
            ?.groupValues
            ?.getOrNull(
                1,
            )
            ?.toFloatOrNull()
            ?: 0F
    }

    // ========================================================================
    // Clean Title
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
    // Utility
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

    // ========================================================================
    // Constants
    // ========================================================================

    companion object {

        /**
         * 肉漫屋 Next.js RSC 中的漫画图片字段。
         *
         * 官方 Roumanwu 使用同样的表达式。
         */
        private val IMAGE_URL_REGEX =
            Regex(
                """"imageUrl":"([^"]+)"""",
            )
    }
}
