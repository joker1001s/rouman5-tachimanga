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

    /**
     * 肉漫屋漫画图片需要经过分块还原。
     *
     * KeiSource 的 client 是 final，
     * 因此不能 override val client。
     *
     * 必须通过 configureClient() 添加拦截器。
     */
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        return addInterceptor(
            ScrambledImageInterceptor(),
        )
    }

    // ========================================================================
    // Filters
    // ========================================================================

    /**
     * Rouman5 currently provides three status filters:
     *
     * 全部
     * 连载中
     * 已完结
     */
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
    // 热门漫画
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

    private fun parseHomePage(
        document: Document,
        sectionRegex: Regex,
    ): MangasPage {

        val entries =
            mutableListOf<SManga>()

        /*
         * Rouman5 home page structure:
         *
         * div.px-1
         *   ├── section
         *   ├── section
         *   └── section
         */

        val container =
            document.selectFirst(
                "div.px-1",
            )

        if (container != null) {

            for (section in container.children()) {

                val text =
                    section.text()

                if (
                    !sectionRegex.containsMatchIn(
                        text,
                    )
                ) {
                    continue
                }

                entries +=
                    parseEntries(
                        section,
                    )
            }
        }

        return MangasPage(
            entries.distinctBy {
                it.url
            },
            false,
        )
    }

    // ========================================================================
    // 最新更新
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
    // 首页漫画解析
    // ========================================================================

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
                    href.isBlank() ||
                    !href.contains(
                        "/books/",
                    )
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
                            .selectFirst("h1")
                            ?.text(),

                        element
                            .selectFirst("h2")
                            ?.text(),

                        element
                            .selectFirst("h3")
                            ?.text(),

                        element.text(),
                    )

                if (title.isBlank()) {
                    return@mapNotNull null
                }

                val imageElement =
                    element.selectFirst(
                        "img",
                    )

                val image =
                    firstNonBlank(

                        imageElement
                            ?.absUrl(
                                "src",
                            ),

                        imageElement
                            ?.absUrl(
                                "data-src",
                            ),

                        imageElement
                            ?.absUrl(
                                "data-original",
                            ),

                        imageElement
                            ?.absUrl(
                                "data-lazy-src",
                            ),
                    )

                SManga.create().apply {

                    setUrlWithoutDomain(
                        href,
                    )

                    this.title =
                        cleanMangaTitle(
                            title,
                        )

                    if (image.isNotBlank()) {
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
    // 搜索 + 筛选
    // ========================================================================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {

        /*
         * StatusFilter.state:
         *
         * 0 = 全部
         * 1 = 连载中
         * 2 = 已完结
         */

        val statusFilter =
            filters.find {
                it is StatusFilter
            } as? StatusFilter

        val continued =
            when (statusFilter?.state) {

                1 -> "true"

                2 -> "false"

                else -> null
            }

        val pageIndex =
            page - 1

        val url =
            if (query.isBlank()) {

                /*
                 * 没有搜索关键词时：
                 *
                 * 全部：
                 * /books?page=0
                 *
                 * 连载中：
                 * /books?continued=true&page=0
                 *
                 * 已完结：
                 * /books?continued=false&page=0
                 */

                buildString {

                    append(
                        "$baseUrl/books",
                    )

                    append(
                        "?page=$pageIndex",
                    )

                    if (continued != null) {

                        append(
                            "&continued=$continued",
                        )
                    }
                }

            } else {

                /*
                 * 搜索：
                 *
                 * /search?term=xxx&page=0
                 *
                 * 如果存在状态筛选，则追加：
                 *
                 * &continued=true
                 */

                val encodedQuery =
                    URLEncoder.encode(
                        query,
                        StandardCharsets.UTF_8.name(),
                    )

                buildString {

                    append(
                        "$baseUrl/search",
                    )

                    append(
                        "?term=$encodedQuery",
                    )

                    append(
                        "&page=$pageIndex",
                    )

                    if (continued != null) {

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

        /*
         * Rouman5 currently uses "下一頁" for pagination.
         */
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

        if (mangaId.isBlank()) {
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
            extractMangaId(
                manga.url,
            )

        if (mangaId.isBlank()) {
            return SMangaUpdate(
                manga,
                chapters,
            )
        }

        val mangaUrl =
            "$baseUrl/books/$mangaId"

        /*
         * Details and chapters are on the same page,
         * so one request is enough.
         */

        val document =
            client.get(mangaUrl).use {
                Jsoup.parse(
                    it.body.string(),
                    mangaUrl,
                )
            }

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

        // --------------------------------------------------------------------
        // 标题
        // --------------------------------------------------------------------

        val title =
            firstNonBlank(

                document.selectFirst(
                    "div.basis-3\\/5 > div.text-xl",
                )?.text(),

                document.selectFirst(
                    "h1",
                )?.text(),

                document.selectFirst(
                    "meta[property=og:title]",
                )?.attr(
                    "content",
                ),

                document.title(),
            )

        manga.title =
            cleanMangaTitle(
                title,
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

        if (thumbnail.isNotBlank()) {

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

                        val value =
                            line
                                .removePrefix(
                                    "狀態:",
                                )
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

                        if (
                            value.isNotBlank()
                        ) {

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
                    genres
                        .distinct()
                        .joinToString(
                            ", ",
                        )
            }
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

        if (manga.status == 0) {

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
                 * Must be:
                 *
                 * /books/{mangaId}/{chapterId}
                 *
                 * rather than:
                 *
                 * /books/{mangaId}
                 */

                val href =
                    element.attr(
                        "href",
                    )

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
                    link.absUrl(
                        "href",
                    )

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

                    date_upload =
                        0L
                }
            }
            .distinctBy {
                it.url
            }
            .asReversed()
    }

    // ========================================================================
    // 阅读章节
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
         * 章节页面中的漫画图片信息可能存在于：
         *
         * 1. Next.js RSC 数据
         * 2. imageUrl 字段
         * 3. images 数组
         *
         * 因此这里直接获取普通章节 HTML/RSC 页面，
         * 后续统一由 parsePages() 提取。
         *
         * 不再直接使用 client.newCall().execute()，
         * 因为 KeiSource 1.6 应使用 suspend client.get()。
         */
        val html =
            client.get(
                chapterUrl,
            ).use {
                it.body.string()
            }
    
        if (html.isBlank()) {
            return emptyList()
        }
    
        return parsePages(
            html,
            chapterUrl,
        )
    }

    // ========================================================================
    // 解析章节图片
    // ========================================================================
    
    private fun parsePages(
        html: String,
        pageUrl: String,
    ): List<Page> {
    
        /*
         * 肉漫屋当前漫画图片有非常明确的特征：
         *
         *     /sr:1/
         *
         * 上游图片拦截器也是通过这个字段判断漫画图片。
         *
         * 因此这里不再把普通 img、广告图片、
         * banner、loading 图片等直接当作漫画页面。
         */
    
        val imageUrlRegex =
            Regex(
                """"imageUrl":"([^"]+)"""",
            )
    
        /*
         * 第一优先级：
         *
         * Next.js RSC 中的 imageUrl
         *
         * 只保留真正漫画图片。
         */
        val imageUrls =
            imageUrlRegex
                .findAll(
                    html,
                )
                .map {
                    it.groupValues[1]
                }
                .map {
                    decodeJsonUrl(
                        it,
                    )
                }
                .filter {
                    isMangaImageUrl(
                        it,
                    )
                }
                .distinct()
                .toList()
    
        if (
            imageUrls.isNotEmpty()
        ) {
    
            return imageUrls.mapIndexed {
                    index,
                    url,
                ->
    
                Page(
                    index = index,
                    imageUrl = url,
                )
            }
        }
    
        /*
         * 第二优先级：
         *
         * 某些版本可能使用：
         *
         * "images":[...]
         *
         * 继续从 Next.js 数据中寻找 URL。
         */
        val imagesBlock =
            Regex(
                """"images":\[(.*?)]""",
                setOf(
                    RegexOption.DOT_MATCHES_ALL,
                ),
            )
                .find(
                    html,
                )
                ?.groupValues
                ?.getOrNull(
                    1,
                )
    
        if (
            !imagesBlock.isNullOrBlank()
        ) {
    
            val dataUrls =
                Regex(
                    """"(https?:\\/\\/[^"]+)"""",
                )
                    .findAll(
                        imagesBlock,
                    )
                    .map {
                        it.groupValues[1]
                    }
                    .map {
                        decodeJsonUrl(
                            it,
                        )
                    }
                    .filter {
                        isMangaImageUrl(
                            it,
                        )
                    }
                    .distinct()
                    .toList()
    
            if (
                dataUrls.isNotEmpty()
            ) {
    
                return dataUrls.mapIndexed {
                        index,
                        url,
                    ->
    
                    Page(
                        index = index,
                        imageUrl = url,
                    )
                }
            }
        }
    
        /*
         * 第三优先级：
         *
         * 部分页面可能真的把漫画图片放到了 <img>
         * 中。
         *
         * 但是这里仍然必须经过 isMangaImageUrl()
         * 检查 /sr:1/。
         */
        val document =
            Jsoup.parse(
                html,
                pageUrl,
            )
    
        val htmlImageUrls =
            document
                .select(
                    "img",
                )
                .mapNotNull { image ->
    
                    firstNonBlank(
    
                        image.absUrl(
                            "data-src",
                        ),
    
                        image.absUrl(
                            "data-original",
                        ),
    
                        image.absUrl(
                            "data-lazy-src",
                        ),
    
                        image.absUrl(
                            "src",
                        ),
                    )
                }
                .map {
                    decodeJsonUrl(
                        it,
                    )
                }
                .filter {
                    isMangaImageUrl(
                        it,
                    )
                }
                .distinct()
                .toList()
    
        if (
            htmlImageUrls.isNotEmpty()
        ) {
    
            return htmlImageUrls.mapIndexed {
                    index,
                    url,
                ->
    
                Page(
                    index = index,
                    imageUrl = url,
                )
            }
        }
    
        return emptyList()
    }

    // ========================================================================
    // 判断是否为真正的漫画图片
    // ========================================================================
    
    private fun isMangaImageUrl(
        url: String,
    ): Boolean {
    
        if (
            url.isBlank()
        ) {
            return false
        }
    
        /*
         * 必须是 HTTP/HTTPS 图片。
         */
        if (
            !url.startsWith(
                "http://",
            ) &&
            !url.startsWith(
                "https://",
            )
        ) {
            return false
        }
    
        val lower =
            url.lowercase()
    
        /*
         * ================================================================
         * 核心判断
         * ================================================================
         *
         * 肉漫屋真正的漫画图片 URL 使用：
         *
         *     /sr:1/
         *
         * 例如：
         *
         *     https://xxx/.../sr:1/xxxx.jpg
         *
         * 广告、logo、banner 等不会满足这个条件。
         *
         * 这是这次修复最重要的一条。
         */
        if (
            !lower.contains(
                "/sr:1/",
            )
        ) {
            return false
        }
    
        /*
         * ================================================================
         * 二次排除
         * ================================================================
         */
    
        val blocked =
            listOf(
                "favicon",
                "logo",
                "banner",
                "avatar",
                "loading",
                "placeholder",
                "thumbnail",
                "thumb",
                "doubleclick",
                "googlead",
                "googlesyndication",
                "adservice",
                "advert",
                "advertising",
                "promotion",
                "/ads/",
                "ads.",
            )
    
        if (
            blocked.any {
                lower.contains(
                    it,
                )
            }
        ) {
            return false
        }
    
        /*
         * ================================================================
         * 图片扩展名检查
         * ================================================================
         */
    
        val extension =
            lower
                .substringBefore(
                    "?",
                )
                .substringAfterLast(
                    ".",
                    "",
                )
    
        if (
            extension.isNotBlank() &&
            extension !in setOf(
                "jpg",
                "jpeg",
                "png",
                "webp",
                "gif",
            )
        ) {
            return false
        }
    
        return true
    }

    // ========================================================================
    // Decode JSON URL
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
            .removePrefix(
                "books/",
            )
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
            ).find(
                text,
            )

        return match
            ?.groupValues
            ?.getOrNull(
                1,
            )
            ?.toFloatOrNull()
            ?: 0F
    }

    // ========================================================================
    // Clean manga title
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
    // First non-blank string
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
