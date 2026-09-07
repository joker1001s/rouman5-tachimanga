package eu.kanade.tachiyomi.extension.zh.rouman5

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.runWebView
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale

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
    // Search
    // ============================================================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val encoded = URLEncoder.encode(
            query,
            Charsets.UTF_8,
        )

        val candidates = listOf(
            "$baseUrl/search?keyword=$encoded&page=${page - 1}",
            "$baseUrl/search?q=$encoded&page=${page - 1}",
            "$baseUrl/search?term=$encoded&page=${page - 1}",
        )

        for (url in candidates) {
            val result = getMangaList(url)

            if (
                result.mangas.isNotEmpty() ||
                url == candidates.last()
            ) {
                return result
            }
        }

        return MangasPage(
            emptyList(),
            false,
        )
    }

    // ============================================================
    // Manga URL
    // ============================================================

    override suspend fun getMangaByUrl(
        url: HttpUrl,
    ): SManga? {
        val host = baseUrl.toHttpUrl().host

        if (url.host != host) {
            return null
        }

        val id = extractMangaIdFromPath(
            url.encodedPath,
        )

        if (id.isBlank()) {
            return null
        }

        val mangaUrl = "$baseUrl/books/$id"

        // --------------------------------------------------------
        // 第一阶段：普通 HTTP
        // --------------------------------------------------------

        val httpDocument = runCatching {
            client
                .get(mangaUrl)
                .asJsoup()
        }.getOrNull()

        if (httpDocument != null) {
            val manga = parseManga(
                httpDocument,
                id,
            )

            if (
                manga.title.isNotBlank() &&
                parseChapters(
                    httpDocument,
                    id,
                ).isNotEmpty()
            ) {
                return manga
            }
        }

        // --------------------------------------------------------
        // 第二阶段：WebView
        //
        // 肉漫屋部分情况下普通 HTTP 得到的页面
        // 与真实浏览器页面不同，因此这里自动 fallback。
        // --------------------------------------------------------

        val webDocument = fetchDocumentWithWebView(
            mangaUrl,
            requireChapters = true,
        )

        if (webDocument != null) {
            return parseManga(
                webDocument,
                id,
            )
        }

        return null
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

        val id = extractMangaId(
            manga.url,
        )

        if (id.isBlank()) {
            throw IllegalStateException(
                "Missing manga id",
            )
        }

        val mangaUrl = "$baseUrl/books/$id"

        // --------------------------------------------------------
        // 普通 HTTP
        // --------------------------------------------------------

        var document = runCatching {
            client
                .get(mangaUrl)
                .asJsoup()
        }.getOrNull()

        var parsedManga: SManga? = null
        var parsedChapters: List<SChapter> = emptyList()

        if (document != null) {
            parsedManga = runCatching {
                parseManga(
                    document!!,
                    id,
                )
            }.getOrNull()

            parsedChapters = runCatching {
                parseChapters(
                    document!!,
                    id,
                )
            }.getOrDefault(
                emptyList(),
            )
        }

        // --------------------------------------------------------
        // 如果标题或者章节没有正确取得，
        // 自动切换到 WebView。
        // --------------------------------------------------------

        val needWebView =
            parsedManga == null ||
                parsedManga!!.title.isBlank() ||
                (
                    fetchChapters &&
                        parsedChapters.isEmpty()
                    )

        if (needWebView) {
            document = fetchDocumentWithWebView(
                mangaUrl,
                requireChapters = fetchChapters,
            )

            if (document != null) {
                parsedManga = runCatching {
                    parseManga(
                        document!!,
                        id,
                    )
                }.getOrNull()

                parsedChapters = runCatching {
                    parseChapters(
                        document!!,
                        id,
                    )
                }.getOrDefault(
                    emptyList(),
                )
            }
        }

        // --------------------------------------------------------
        // 强制检查标题
        // --------------------------------------------------------

        val details = parsedManga ?: manga

        if (fetchDetails && details.title.isBlank()) {
            throw IllegalStateException(
                "Missing manga title: $mangaUrl",
            )
        }

        // --------------------------------------------------------
        // 强制检查章节
        // --------------------------------------------------------

        val updatedChapters =
            if (fetchChapters) {
                if (parsedChapters.isEmpty()) {
                    throw IllegalStateException(
                        "No chapters found: $mangaUrl",
                    )
                }

                parsedChapters
            } else {
                chapters
            }

        return SMangaUpdate(
            details,
            updatedChapters,
        )
    }

    // ============================================================
    // Chapter pages
    // ============================================================

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {

        val chapterUrl = getChapterUrl(
            chapter,
        )

        val result = withTimeoutOrNull(
            45_000L,
        ) {
            runWebView<String> {

                javaScriptEnabled = true
                domStorageEnabled = true

                loadUrl(
                    chapterUrl,
                )

                onPageFinished {

                    poll(500) {

                        evaluateJs(
                            """
                            (() => {

                                // 自动点击 18+ 确认
                                const buttons =
                                    Array.from(
                                        document.querySelectorAll(
                                            "button, a"
                                        )
                                    );

                                const gate =
                                    buttons.find(
                                        el => {
                                            const text =
                                                (
                                                    el.innerText ||
                                                    el.textContent ||
                                                    ""
                                                ).trim();

                                            return (
                                                text.includes("我已滿18歲") ||
                                                text.includes("我已滿 18 歲") ||
                                                text.includes("我已满18岁") ||
                                                text.includes("我已满 18 岁") ||
                                                text.includes("進入") ||
                                                text.includes("进入")
                                            );
                                        }
                                    );

                                if (gate) {
                                    gate.click();
                                }

                                // 尝试滚动到底部，
                                // 触发 lazy-load 图片。
                                window.scrollTo(
                                    0,
                                    document.body.scrollHeight
                                );

                                const images =
                                    Array.from(
                                        document.images
                                    )
                                    .map(
                                        img =>
                                            img.currentSrc ||
                                            img.src ||
                                            img.dataset.src ||
                                            img.dataset.original ||
                                            ""
                                    )
                                    .filter(
                                        url =>
                                            url &&
                                            !url.startsWith("data:") &&
                                            !url.includes("loading.jpg") &&
                                            !url.includes("logo")
                                    );

                                if (images.length > 0) {
                                    return [
                                        ...new Set(images)
                                    ].join("\\n");
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

        val urls = result
            ?.replace(
                "\\n",
                "\n",
            )
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
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
                it.contains(
                    "loading.jpg",
                )
            }
            ?.filterNot {
                it.contains(
                    "logo",
                    ignoreCase = true,
                )
            }
            ?.distinct()
            .orEmpty()

        return urls.mapIndexed {
                index,
                imageUrl,
            ->
            Page(
                index,
                imageUrl = imageUrl,
                url = imageUrl,
            )
        }
    }

    // ============================================================
    // URL helpers
    // ============================================================

    override fun getMangaUrl(
        manga: SManga,
    ): String {
        val id = extractMangaId(
            manga.url,
        )

        return "$baseUrl/books/$id"
    }

    override fun getChapterUrl(
        chapter: SChapter,
    ): String {

        val url = chapter.url

        return when {
            url.startsWith(
                "http://",
            ) -> url

            url.startsWith(
                "https://",
            ) -> url

            url.startsWith("/") -> {
                "$baseUrl$url"
            }

            else -> {
                "$baseUrl/$url"
            }
        }
    }

    // ============================================================
    // Manga list
    // ============================================================

    private suspend fun getMangaList(
        url: String,
    ): MangasPage {

        val doc = client
            .get(url)
            .asJsoup()

        val mangas =
            doc.select(
                "a[href^=/books/]",
            )
            .mapNotNull { anchor ->

                val path =
                    anchor.attr(
                        "href",
                    )
                    .removePrefix(
                        "/books/",
                    )
                    .trim('/')

                // 列表页面只接受：
                // /books/{mangaId}
                //
                // 章节页面：
                // /books/{mangaId}/{chapterId}
                //
                // 直接排除。
                if (
                    path.isBlank() ||
                    path.contains('/')
                ) {
                    return@mapNotNull null
                }

                val title =
                    anchor
                        .select(
                            "h3,h4,span",
                        )
                        .firstOrNull()
                        ?.text()
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: anchor
                            .text()
                            .trim()

                if (title.isBlank()) {
                    return@mapNotNull null
                }

                val image =
                    anchor
                        .select(
                            "img",
                        )
                        .firstOrNull()
                        ?.let { img ->

                            img.absUrl(
                                "data-src",
                            ).ifBlank {

                                img.absUrl(
                                    "data-original",
                                ).ifBlank {

                                    img.absUrl(
                                        "src",
                                    )
                                }
                            }
                        }
                        .orEmpty()

                SManga.create().apply {

                    // 只保存 ID
                    url = path

                    this.title = title

                    thumbnail_url = image
                }
            }
            .distinctBy {
                it.url
            }

        val hasNext =
            doc.select(
                "a",
            ).any {

                val text =
                    it.text().trim()

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
        doc: Document,
        id: String,
    ): SManga {

        val manga = SManga.create()

        manga.url = id

        // --------------------------------------------------------
        // 标题解析
        //
        // 优先级：
        //
        // 1. h1
        // 2. og:title
        // 3. twitter:title
        // 4. title
        // --------------------------------------------------------

        val titleCandidates =
            listOf(
                doc.select(
                    "h1",
                ).firstOrNull()?.text(),

                doc.select(
                    "meta[property=og:title]",
                )
                    .firstOrNull()
                    ?.attr(
                        "content",
                    ),

                doc.select(
                    "meta[name=twitter:title]",
                )
                    .firstOrNull()
                    ?.attr(
                        "content",
                    ),

                doc.title(),
            )

        val title =
            titleCandidates
                .asSequence()
                .map {
                    it.orEmpty().trim()
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

        if (title.isBlank()) {
            throw IllegalStateException(
                "Missing manga title: $baseUrl/books/$id",
            )
        }

        manga.title = title

        // --------------------------------------------------------
        // Cover
        // --------------------------------------------------------

        manga.thumbnail_url =
            doc.select(
                "meta[property=og:image]",
            )
                .firstOrNull()
                ?.attr(
                    "content",
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: doc.select(
                    "img",
                )
                    .firstOrNull()
                    ?.let {

                        it.absUrl(
                            "src",
                        ).ifBlank {

                            it.absUrl(
                                "data-src",
                            ).ifBlank {

                                it.absUrl(
                                    "data-original",
                                )
                            }
                        }
                    }
                    .orEmpty()

        // --------------------------------------------------------
        // Info
        // --------------------------------------------------------

        val bodyText =
            doc.body()
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
                doc,
            )

        val status =
            extractInfo(
                bodyText,
                "狀態",
            )
                .lowercase(
                    Locale.ROOT,
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
        doc: Document,
        id: String,
    ): List<SChapter> {

        val selector =
            "a[href^=/books/$id/]"

        return doc
            .select(
                selector,
            )
            .mapNotNull { link ->

                val href =
                    link.absUrl(
                        "href",
                    )

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val chapterPath =
                    href
                        .substringAfter(
                            "/books/$id/",
                            "",
                        )
                        .trim('/')

                if (
                    chapterPath.isBlank() ||
                    chapterPath.contains('/')
                ) {
                    return@mapNotNull null
                }

                val name =
                    link.text().trim()

                if (name.isBlank()) {
                    return@mapNotNull null
                }

                SChapter.create().apply {

                    setUrlWithoutDomain(
                        href,
                    )

                    this.name = name
                }
            }
            .distinctBy {
                it.url
            }
            .reversed()
    }

    // ============================================================
    // WebView document
    // ============================================================

    private suspend fun fetchDocumentWithWebView(
        url: String,
        requireChapters: Boolean,
    ): Document? {

        val html =
            withTimeoutOrNull(
                30_000L,
            ) {

                runWebView<String> {

                    javaScriptEnabled = true
                    domStorageEnabled = true

                    loadUrl(
                        url,
                    )

                    onPageFinished {

                        poll(500) {

                            evaluateJs(
                                """
                                (() => {

                                    const buttons =
                                        Array.from(
                                            document.querySelectorAll(
                                                "button, a"
                                            )
                                        );

                                    const gate =
                                        buttons.find(
                                            el => {

                                                const text =
                                                    (
                                                        el.innerText ||
                                                        el.textContent ||
                                                        ""
                                                    )
                                                    .trim();

                                                return (
                                                    text.includes("我已滿18歲") ||
                                                    text.includes("我已滿 18 歲") ||
                                                    text.includes("我已满18岁") ||
                                                    text.includes("我已满 18 岁") ||
                                                    text === "進入" ||
                                                    text === "进入"
                                                );
                                            }
                                        );

                                    if (gate) {
                                        gate.click();

                                        return "";
                                    }

                                    const hasTitle =
                                        !!document.querySelector(
                                            "h1"
                                        );

                                    const hasChapter =
                                        !!document.querySelector(
                                            'a[href*="/books/"]'
                                        );

                                    if (
                                        hasTitle &&
                                        (
                                            !${requireChapters} ||
                                            hasChapter
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
            }

        if (
            html.isNullOrBlank()
        ) {
            return null
        }

        val decoded =
            decodeJsString(
                html,
            )

        return runCatching {
            Jsoup.parse(
                decoded,
                baseUrl,
            )
        }.getOrNull()
    }

    // ============================================================
    // Manga ID
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

    private fun extractMangaIdFromPath(
        path: String,
    ): String {

        return path
            .removePrefix(
                "/books/",
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
            .replace(
                Regex(
                    """\s*-\s*肉漫屋.*$""",
                ),
                "",
            )
            .trim()
    }

    // ============================================================
    // Extract info
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
            ?.trim()
            .orEmpty()
    }

    // ============================================================
    // Description
    // ============================================================

    private fun extractDescription(
        doc: Document,
    ): String {

        val text =
            doc.body()
                ?.text()
                .orEmpty()

        val markers =
            listOf(
                "簡介:",
                "簡介：",
                "敘述:",
                "敘述：",
            )

        return markers
            .firstNotNullOfOrNull { marker ->

                val index =
                    text.indexOf(
                        marker,
                    )

                if (index < 0) {
                    null
                } else {
                    text
                        .substring(
                            index + marker.length,
                        )
                        .substringBefore(
                            "放入書架",
                        )
                        .trim()
                }
            }
            .orEmpty()
    }

    // ============================================================
    // WebView JS result decoder
    // ============================================================

    private fun decodeJsString(
        value: String,
    ): String {

        var result =
            value.trim()

        if (
            result.startsWith("\"") &&
            result.endsWith("\"")
        ) {
            result =
                result.substring(
                    1,
                    result.length - 1,
                )
        }

        return result
            .replace(
                "\\n",
                "\n",
            )
            .replace(
                "\\r",
                "\r",
            )
            .replace(
                "\\t",
                "\t",
            )
            .replace(
                "\\\"",
                "\"",
            )
            .replace(
                "\\/",
                "/",
            )
            .replace(
                "\\u003C",
                "<",
                ignoreCase = true,
            )
            .replace(
                "\\u003E",
                ">",
                ignoreCase = true,
            )
            .replace(
                "\\u0026",
                "&",
                ignoreCase = true,
            )
    }
}
