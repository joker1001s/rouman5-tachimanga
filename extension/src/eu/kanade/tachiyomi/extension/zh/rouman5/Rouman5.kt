```kotlin
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
import keiyoushi.utils.runWebView
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Rouman5 : KeiSource() {

    override val supportsLatest = true

    // ============================================================
    // Popular
    // ============================================================

    override suspend fun getPopularManga(page: Int): MangasPage {
        return getMangaList(
            "$baseUrl/books?continued=&page=${page - 1}",
        )
    }

    // ============================================================
    // Latest
    // ============================================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return getMangaList(
            "$baseUrl/books?continued=&page=${page - 1}",
        )
    }

    // ============================================================
    // Search + Filters
    // ============================================================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {

        /*
         * 网站目前的搜索：
         *
         * /search?term=xxx
         *
         * 网站的全部漫画筛选：
         *
         * /books?continued=true
         * /books?continued=false
         * /books?continued=
         *
         * 搜索时网站本身不会同时处理状态筛选，
         * 所以：
         *
         * 1. 有关键词 -> /search
         * 2. 无关键词 -> /books + filters
         */

        if (query.isNotBlank()) {
            val encoded = URLEncoder.encode(
                query,
                Charsets.UTF_8,
            )

            return getMangaList(
                "$baseUrl/search?term=$encoded&page=${page - 1}",
            )
        }

        val statusFilter =
            filters.filterIsInstance<StatusFilter>().firstOrNull()

        val continued =
            statusFilter?.toUriPart()
                ?: ""

        val url =
            buildString {
                append(baseUrl)
                append("/books?continued=")
                append(continued)
                append("&page=")
                append(page - 1)
            }

        return getMangaList(url)
    }

    // ============================================================
    // Filters
    // ============================================================

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header(
                "提示：篩選僅適用於「全部漫畫」",
            ),
            StatusFilter(),
        )
    }

    private class StatusFilter :
        Filter.Select<String>(
            "狀態",
            arrayOf(
                "全部",
                "連載中",
                "已完結",
            ),
        ) {

        fun toUriPart(): String {
            return when (state) {
                1 -> "true"
                2 -> "false"
                else -> ""
            }
        }
    }

    // ============================================================
    // Manga details
    // ============================================================

    override suspend fun getMangaByUrl(
        url: HttpUrl,
    ): SManga? {

        val id =
            extractMangaId(
                url.encodedPath,
            )

        if (id.isBlank()) {
            return null
        }

        val mangaUrl =
            "$baseUrl/books/$id"

        /*
         * 先使用普通 HTTP。
         *
         * 只有页面不完整的时候才进入 WebView。
         */
        val document =
            fetchDocument(
                mangaUrl,
            )

        if (document != null) {

            val manga =
                runCatching {
                    parseManga(
                        document,
                        id,
                    )
                }.getOrNull()

            val chapters =
                runCatching {
                    parseChapters(
                        document,
                        id,
                    )
                }.getOrDefault(
                    emptyList(),
                )

            if (
                manga != null &&
                    manga.title.isNotBlank() &&
                    chapters.isNotEmpty()
            ) {
                return manga
            }
        }

        /*
         * 普通 HTTP 无法取得完整页面时，
         * 使用 WebView 正常完成网站的年龄确认流程。
         */
        val webDocument =
            fetchDocumentWithWebView(
                mangaUrl,
                requireChapters = true,
            )

        return if (webDocument != null) {
            runCatching {
                parseManga(
                    webDocument,
                    id,
                )
            }.getOrNull()
        } else {
            null
        }
    }

    // ============================================================
    // Manga update
    // ============================================================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {

        val id =
            extractMangaId(
                manga.url,
            )

        if (id.isBlank()) {
            throw IllegalStateException(
                "Missing manga id",
            )
        }

        val mangaUrl =
            "$baseUrl/books/$id"

        var document =
            fetchDocument(
                mangaUrl,
            )

        var details: SManga? =
            null

        var updatedChapters:
            List<SChapter> =
            emptyList()

        if (document != null) {

            details =
                runCatching {
                    parseManga(
                        document,
                        id,
                    )
                }.getOrNull()

            updatedChapters =
                runCatching {
                    parseChapters(
                        document,
                        id,
                    )
                }.getOrDefault(
                    emptyList(),
                )
        }

        /*
         * 如果 HTTP 页面没有正常返回章节，
         * 使用 WebView。
         */
        val needWebView =
            details == null ||
                details.title.isBlank() ||
                (
                    fetchChapters &&
                        updatedChapters.isEmpty()
                    )

        if (needWebView) {

            document =
                fetchDocumentWithWebView(
                    mangaUrl,
                    requireChapters = fetchChapters,
                )

            if (document != null) {

                details =
                    runCatching {
                        parseManga(
                            document,
                            id,
                        )
                    }.getOrNull()

                updatedChapters =
                    runCatching {
                        parseChapters(
                            document,
                            id,
                        )
                    }.getOrDefault(
                        emptyList(),
                    )
            }
        }

        val finalManga =
            if (
                fetchDetails &&
                    details != null
            ) {
                details
            } else {
                manga
            }

        if (finalManga.title.isBlank()) {
            throw IllegalStateException(
                "Missing manga title: $mangaUrl",
            )
        }

        val finalChapters =
            if (fetchChapters) {

                if (updatedChapters.isEmpty()) {
                    throw IllegalStateException(
                        "No chapters found: $mangaUrl",
                    )
                }

                updatedChapters
            } else {
                chapters
            }

        return SMangaUpdate(
            finalManga,
            finalChapters,
        )
    }

    // ============================================================
    // Chapter pages
    // ============================================================

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {

        val chapterUrl =
            getChapterUrl(
                chapter,
            )

        /*
         * 使用 WebView：
         *
         * 1. 正常打开章节
         * 2. 如果出现 18+ 页面，点击网站自己的确认按钮
         * 3. 等待真正漫画图片出现
         * 4. 滚动触发懒加载
         * 5. 反复读取图片
         * 6. 最终返回稳定的图片列表
         */

        val result =
            withTimeoutOrNull(
                60.seconds,
            ) {

                runWebView<String> {

                    javaScriptEnabled = true
                    domStorageEnabled = true

                    loadUrl(
                        chapterUrl,
                    )

                    onPageFinished {

                        poll(
                            500.milliseconds,
                        ) {

                            evaluateJs(
                                """
                                (() => {

                                    // ------------------------------------------------
                                    // 1. 正常处理网站年龄确认
                                    // ------------------------------------------------

                                    const elements =
                                        Array.from(
                                            document.querySelectorAll(
                                                "button, a"
                                            )
                                        );

                                    const gate =
                                        elements.find(
                                            el => {

                                                const text =
                                                    (
                                                        el.innerText ||
                                                        el.textContent ||
                                                        ""
                                                    )
                                                    .replace(
                                                        /\s+/g,
                                                        ""
                                                    )
                                                    .trim();

                                                return (
                                                    text.includes(
                                                        "我已滿18歲"
                                                    ) ||
                                                    text.includes(
                                                        "我已滿18"
                                                    ) ||
                                                    text.includes(
                                                        "我已满18岁"
                                                    ) ||
                                                    text.includes(
                                                        "我已满18"
                                                    )
                                                );
                                            }
                                        );

                                    if (gate) {

                                        gate.click();

                                        return "";
                                    }

                                    // ------------------------------------------------
                                    // 2. 触发懒加载
                                    // ------------------------------------------------

                                    const height =
                                        document.body
                                            ? document.body.scrollHeight
                                            : 0;

                                    window.scrollTo(
                                        0,
                                        height
                                    );

                                    // ------------------------------------------------
                                    // 3. 找到真正的漫画图片
                                    // ------------------------------------------------

                                    const images =
                                        Array.from(
                                            document.querySelectorAll(
                                                "img"
                                            )
                                        )
                                        .map(
                                            img => {

                                                const src =
                                                    img.currentSrc ||
                                                    img.src ||
                                                    img.getAttribute(
                                                        "data-src"
                                                    ) ||
                                                    img.getAttribute(
                                                        "data-original"
                                                    ) ||
                                                    img.getAttribute(
                                                        "data-lazy-src"
                                                    ) ||
                                                    "";

                                                return {
                                                    img: img,
                                                    src: src
                                                };
                                            }
                                        )
                                        .filter(
                                            item => {

                                                const img =
                                                    item.img;

                                                const src =
                                                    item.src;

                                                if (!src) {
                                                    return false;
                                                }

                                                // data URI
                                                if (
                                                    src.startsWith(
                                                        "data:"
                                                    )
                                                ) {
                                                    return false;
                                                }

                                                const lower =
                                                    src.toLowerCase();

                                                // 网站 UI / 占位图片
                                                if (
                                                    lower.includes(
                                                        "loading"
                                                    ) ||
                                                    lower.includes(
                                                        "placeholder"
                                                    ) ||
                                                    lower.includes(
                                                        "logo"
                                                    ) ||
                                                    lower.includes(
                                                        "favicon"
                                                    ) ||
                                                    lower.includes(
                                                        "avatar"
                                                    ) ||
                                                    lower.includes(
                                                        "icon"
                                                    )
                                                ) {
                                                    return false;
                                                }

                                                // 必须是图片 URL
                                                if (
                                                    !(
                                                        lower.includes(
                                                            ".jpg"
                                                        ) ||
                                                        lower.includes(
                                                            ".jpeg"
                                                        ) ||
                                                        lower.includes(
                                                            ".png"
                                                        ) ||
                                                        lower.includes(
                                                            ".webp"
                                                        )
                                                    )
                                                ) {
                                                    return false;
                                                }

                                                // 排除明显的小型 UI 图片
                                                const width =
                                                    img.naturalWidth ||
                                                    img.width ||
                                                    0;

                                                const height =
                                                    img.naturalHeight ||
                                                    img.height ||
                                                    0;

                                                if (
                                                    width > 0 &&
                                                    height > 0 &&
                                                    (
                                                        width < 200 ||
                                                        height < 200
                                                    )
                                                ) {
                                                    return false;
                                                }

                                                return true;
                                            }
                                        );

                                    // ------------------------------------------------
                                    // 4. 按 DOM 顺序去重
                                    // ------------------------------------------------

                                    const urls = [];

                                    const seen =
                                        new Set();

                                    for (
                                        const item
                                        of images
                                    ) {

                                        const url =
                                            item.src
                                                .trim();

                                        if (
                                            !url ||
                                            seen.has(url)
                                        ) {
                                            continue;
                                        }

                                        seen.add(url);
                                        urls.push(url);
                                    }

                                    // ------------------------------------------------
                                    // 5. 只有真正找到漫画图片后才返回
                                    // ------------------------------------------------

                                    if (
                                        urls.length > 0
                                    ) {

                                        return urls.join(
                                            "\\n"
                                        );
                                    }

                                    return "";

                                })();
                                """.trimIndent(),
                            ) { value ->

                                if (
                                    value.isNotBlank()
                                ) {
                                    resolve(
                                        value,
                                    )
                                }
                            }
                        }
                    }
                }
            }

        val urls =
            result
                ?.trim()
                ?.removePrefix("\"")
                ?.removeSuffix("\"")
                ?.replace(
                    "\\n",
                    "\n",
                )
                ?.replace(
                    "\\/",
                    "/",
                )
                ?.split('\n')
                ?.map {
                    it.trim()
                }
                ?.filter {
                    it.startsWith(
                        "https://",
                    ) ||
                        it.startsWith(
                            "http://",
                        )
                }
                ?.filterNot {
                    isInvalidImageUrl(
                        it,
                    )
                }
                ?.distinct()
                .orEmpty()

        /*
         * 最终再做一次 Kotlin 层去重。
         *
         * 这样即使 WebView 返回重复图片，
         * Tachimanga 也不会出现重复 Page。
         */
        return urls
            .distinct()
            .mapIndexed {
                    index,
                    imageUrl,
                ->
                Page(
                    index,
                    imageUrl = imageUrl,
                )
            }
    }

    // ============================================================
    // Manga URL
    // ============================================================

    override fun getMangaUrl(
        manga: SManga,
    ): String {

        val id =
            extractMangaId(
                manga.url,
            )

        return "$baseUrl/books/$id"
    }

    // ============================================================
    // Chapter URL
    // ============================================================

    override fun getChapterUrl(
        chapter: SChapter,
    ): String {

        val url =
            chapter.url

        return when {

            url.startsWith(
                "http://",
            ) ->
                url

            url.startsWith(
                "https://",
            ) ->
                url

            url.startsWith(
                "/",
            ) ->
                "$baseUrl$url"

            else ->
                "$baseUrl/$url"
        }
    }

    // ============================================================
    // Manga list
    // ============================================================

    private suspend fun getMangaList(
        url: String,
    ): MangasPage {

        val document =
            fetchDocument(
                url,
            )
                ?: return MangasPage(
                    emptyList(),
                    false,
                )

        val mangas =
            document
                .select(
                    "a[href^=/books/]",
                )
                .mapNotNull { anchor ->

                    val href =
                        anchor.absUrl(
                            "href",
                        )

                    if (
                        href.isBlank()
                    ) {
                        return@mapNotNull null
                    }

                    val path =
                        href
                            .substringAfter(
                                "/books/",
                                "",
                            )
                            .trim('/')

                    /*
                     * 排除章节 URL：
                     *
                     * /books/mangaId/chapterId
                     */
                    if (
                        path.isBlank() ||
                            path.contains("/")
                    ) {
                        return@mapNotNull null
                    }

                    val title =
                        anchor
                            .select(
                                "h1, h2, h3, h4, span",
                            )
                            .firstOrNull()
                            ?.text()
                            ?.trim()
                            ?.takeIf {
                                it.isNotEmpty()
                            }
                            ?: anchor.text()
                                .trim()

                    if (
                        title.isBlank()
                    ) {
                        return@mapNotNull null
                    }

                    val image =
                        anchor
                            .select(
                                "img",
                            )
                            .firstOrNull()
                            ?.let { image ->

                                image
                                    .absUrl(
                                        "src",
                                    )
                                    .ifBlank {

                                        image.absUrl(
                                            "data-src",
                                        )
                                            .ifBlank {

                                                image.absUrl(
                                                    "data-original",
                                                )
                                            }
                                    }
                            }
                            .orEmpty()

                    SManga.create().apply {

                        setUrlWithoutDomain(
                            href,
                        )

                        this.title =
                            cleanMangaTitle(
                                title,
                            )

                        thumbnail_url =
                            image
                    }
                }
                .distinctBy {
                    it.url
                }

        val hasNext =
            document
                .select(
                    "a",
                )
                .any { link ->

                    val text =
                        link.text()

                    text.contains(
                        "下一頁",
                    ) ||
                        text.contains(
                            "下一页",
                        ) ||
                        text.contains(
                            "Next",
                            ignoreCase = true,
                        )
                }

        return MangasPage(
            mangas,
            hasNext,
        )
    }

    // ============================================================
    // Parse manga
    // ============================================================

    private fun parseManga(
        document: Document,
        id: String,
    ): SManga {

        val manga =
            SManga.create()

        manga.setUrlWithoutDomain(
            "$baseUrl/books/$id",
        )

        val title =
            listOf(
                document
                    .select("h1")
                    .firstOrNull()
                    ?.text(),

                document
                    .select(
                        "meta[property=og:title]",
                    )
                    .firstOrNull()
                    ?.attr(
                        "content",
                    ),

                document
                    .select(
                        "meta[name=twitter:title]",
                    )
                    .firstOrNull()
                    ?.attr(
                        "content",
                    ),

                document.title(),
            )
                .asSequence()
                .map {
                    it.orEmpty()
                }
                .map {
                    cleanMangaTitle(
                        it,
                    )
                }
                .firstOrNull {
                    it.isNotBlank() &&
                        !it.equals(
                            "肉漫屋",
                            ignoreCase = true,
                        )
                }
                .orEmpty()

        if (
            title.isBlank()
        ) {
            throw IllegalStateException(
                "Missing manga title: $baseUrl/books/$id",
            )
        }

        manga.title =
            title

        manga.thumbnail_url =
            document
                .select(
                    "meta[property=og:image]",
                )
                .firstOrNull()
                ?.attr(
                    "content",
                )
                .orEmpty()
                .ifBlank {

                    document
                        .select(
                            "img",
                        )
                        .firstOrNull()
                        ?.let { image ->

                            image
                                .absUrl(
                                    "src",
                                )
                                .ifBlank {

                                    image.absUrl(
                                        "data-src",
                                    )
                                        .ifBlank {

                                            image.absUrl(
                                                "data-original",
                                            )
                                        }
                                }
                        }
                        .orEmpty()
                }

        val bodyText =
            document
                .body()
                ?.text()
                .orEmpty()

        manga.author =
            extractInfo(
                bodyText,
                "作者",
            )

        manga.genre =
            extractInfo(
                bodyText,
                "標籤",
            )

        manga.description =
            extractDescription(
                document,
            )

        val status =
            extractInfo(
                bodyText,
                "狀態",
            )

        manga.status =
            if (
                status.contains(
                    "完結",
                ) ||
                    status.contains(
                        "完结",
                    )
            ) {
                SManga.COMPLETED
            } else {
                SManga.ONGOING
            }

        return manga
    }

    // ============================================================
    // Parse chapters
    // ============================================================

    private fun parseChapters(
        document: Document,
        id: String,
    ): List<SChapter> {

        return document
            .select(
                "a[href*=\"/books/$id/\"]",
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
                    href
                        .substringAfter(
                            "/books/$id/",
                            "",
                        )
                        .trim('/')

                if (
                    chapterId.isBlank() ||
                        chapterId.contains("/")
                ) {
                    return@mapNotNull null
                }

                val name =
                    link.text()
                        .trim()

                if (
                    name.isBlank()
                ) {
                    return@mapNotNull null
                }

                SChapter.create().apply {

                    setUrlWithoutDomain(
                        href,
                    )

                    this.name =
                        name
                }
            }
            .distinctBy {
                it.url
            }
            .reversed()
    }

    // ============================================================
    // HTTP document
    // ============================================================

    private suspend fun fetchDocument(
        url: String,
    ): Document? {

        return runCatching {

            client
                .get(
                    url,
                )
                .use { response ->

                    Jsoup.parse(
                        response.body.string(),
                        url,
                    )
                }

        }.getOrNull()
    }

    // ============================================================
    // WebView document
    // ============================================================

    private suspend fun fetchDocumentWithWebView(
        url: String,
        requireChapters: Boolean,
    ): Document? {

        val html =
            runCatching {

                runWebView<String>(
                    timeout = 30.seconds,
                ) {

                    javaScriptEnabled =
                        true

                    domStorageEnabled =
                        true

                    loadUrl(
                        url,
                    )

                    onPageFinished {

                        poll(
                            500.milliseconds,
                        ) {

                            evaluateJs(
                                """
                                (() => {

                                    // ============================================
                                    // 正常处理网站年龄确认
                                    // ============================================

                                    const elements =
                                        Array.from(
                                            document.querySelectorAll(
                                                "button, a"
                                            )
                                        );

                                    const gate =
                                        elements.find(
                                            el => {

                                                const text =
                                                    (
                                                        el.innerText ||
                                                        el.textContent ||
                                                        ""
                                                    )
                                                    .replace(
                                                        /\s+/g,
                                                        ""
                                                    )
                                                    .trim();

                                                return (
                                                    text.includes(
                                                        "我已滿18歲"
                                                    ) ||
                                                    text.includes(
                                                        "我已滿18"
                                                    ) ||
                                                    text.includes(
                                                        "我已满18岁"
                                                    ) ||
                                                    text.includes(
                                                        "我已满18"
                                                    )
                                                );
                                            }
                                        );

                                    if (gate) {

                                        gate.click();

                                        return "";
                                    }

                                    // ============================================
                                    // 页面已经进入正常内容
                                    // ============================================

                                    const title =
                                        document.querySelector(
                                            "h1"
                                        );

                                    const chapters =
                                        document.querySelector(
                                            'a[href*="/books/"]'
                                        );

                                    if (
                                        title &&
                                        title.textContent.trim() &&
                                        (
                                            !${requireChapters} ||
                                            chapters
                                        )
                                    ) {

                                        return document
                                            .documentElement
                                            .outerHTML;
                                    }

                                    return "";

                                })();
                                """.trimIndent(),
                            ) { value ->

                                if (
                                    value.isNotBlank()
                                ) {
                                    resolve(
                                        value,
                                    )
                                }
                            }
                        }
                    }
                }

            }.getOrNull()

        if (
            html.isNullOrBlank()
        ) {
            return null
        }

        return runCatching {

            Jsoup.parse(
                html,
                url,
            )

        }.getOrNull()
    }

    // ============================================================
    // Image URL validation
    // ============================================================

    private fun isInvalidImageUrl(
        url: String,
    ): Boolean {

        val lower =
            url.lowercase()

        if (
            lower.contains(
                "loading",
            )
        ) {
            return true
        }

        if (
            lower.contains(
                "placeholder",
            )
        ) {
            return true
        }

        if (
            lower.contains(
                "logo",
            )
        ) {
            return true
        }

        if (
            lower.contains(
                "favicon",
            )
        ) {
            return true
        }

        if (
            lower.contains(
                "avatar",
            )
        ) {
            return true
        }

        if (
            lower.contains(
                "icon",
            )
        ) {
            return true
        }

        if (
            lower.startsWith(
                "data:",
            )
        ) {
            return true
        }

        return false
    }

    // ============================================================
    // ID
    // ============================================================

    private fun extractMangaId(
        value: String,
    ): String {

        return value
            .substringAfter(
                "/books/",
                value,
            )
            .trim('/')
            .substringBefore('/')
            .trim()
    }

    // ============================================================
    // Title cleanup
    // ============================================================

    private fun cleanMangaTitle(
        value: String,
    ): String {

        return value
            .replace(
                Regex(
                    """\s*[-|｜]\s*肉漫屋.*$""",
                ),
                "",
            )
            .trim()
    }

    // ============================================================
    // Info
    // ============================================================

    private fun extractInfo(
        text: String,
        label: String,
    ): String {

        val regex =
            Regex(
                """$label\s+(.+?)(?=\s+(作者|狀態|地區|更新|標籤)\s+|$)""",
            )

        return regex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    // ============================================================
    // Description
    // ============================================================

    private fun extractDescription(
        document: Document,
    ): String {

        val text =
            document
                .body()
                ?.text()
                .orEmpty()

        val markers =
            listOf(
                "簡介:",
                "簡介：",
                "敘述:",
                "敘述：",
            )

        for (
            marker in markers
        ) {

            val index =
                text.indexOf(
                    marker,
                )

            if (
                index >= 0
            ) {

                return text
                    .substring(
                        index +
                            marker.length,
                    )
                    .substringBefore(
                        "放入書架",
                    )
                    .trim()
            }
        }

        return ""
    }
}
```
