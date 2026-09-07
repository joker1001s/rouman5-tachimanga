package eu.kanade.tachiyomi.extension.zh.rouman5

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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import kotlin.time.Duration.Companion.milliseconds

@Source
abstract class Rouman5 : KeiSource() {

    override val supportsLatest = true

    override suspend fun getPopularManga(page: Int): MangasPage {
        return getMangaList(
            "$baseUrl/books?continued=&page=${page - 1}",
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return getMangaList(
            "$baseUrl/books?continued=true&page=${page - 1}",
        )
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val encoded = URLEncoder.encode(
            query,
            Charsets.UTF_8,
        )

        return getMangaList(
            "$baseUrl/search?term=$encoded&page=${page - 1}",
        )
    }

    override suspend fun getMangaByUrl(
        url: HttpUrl,
    ): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        val segments = url.pathSegments

        if (segments.firstOrNull() != "books") {
            return null
        }

        val mangaId = segments.getOrNull(1)
            ?: return null

        if (mangaId.isBlank()) {
            return null
        }

        val document = fetchDocument(
            url.toString(),
        )

        return parseManga(
            document,
            mangaId,
        )
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaId = manga.url
            .trim('/')
            .substringBefore('/')

        val document = fetchDocument(
            "$baseUrl/books/$mangaId",
        )

        return SMangaUpdate(
            parseManga(
                document,
                mangaId,
            ),
            parseChapters(
                document,
                mangaId,
            ),
        )
    }

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {
        val chapterUrl = getChapterUrl(
            chapter,
        )

        val rawUrls = runWebView<String> {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true

            loadUrl(chapterUrl)

            onPageFinished {
                evaluateJs(
                    """
                    (() => {
                        const buttons =
                            Array.from(
                                document.querySelectorAll(
                                    'button,a'
                                )
                            );

                        const ageButton =
                            buttons.find(
                                element => {
                                    const text =
                                        element.textContent || '';

                                    return (
                                        text.includes(
                                            '我已滿18歲'
                                        ) ||
                                        text.includes(
                                            '我已满18岁'
                                        )
                                    );
                                }
                            );

                        if (ageButton) {
                            ageButton.click();
                        }
                    })();
                    """.trimIndent(),
                )

                poll(500.milliseconds) {
                    evaluateJs(
                        """
                        (() => {
                            window.__rouman5 =
                                window.__rouman5 || {
                                    lastHeight: -1,
                                    stableCount: 0
                                };

                            const state =
                                window.__rouman5;

                            window.scrollTo(
                                0,
                                document.body.scrollHeight
                            );

                            const images =
                                Array.from(
                                    document.images
                                );

                            const urls =
                                images
                                    .map(
                                        image =>
                                            image.currentSrc ||
                                            image.src ||
                                            image.dataset.src ||
                                            image.dataset.original ||
                                            ''
                                    )
                                    .filter(
                                        url =>
                                            /^https?:\/\//.test(
                                                url
                                            )
                                    )
                                    .filter(
                                        url =>
                                            !url.includes(
                                                '/loading.jpg'
                                            )
                                    )
                                    .filter(
                                        url =>
                                            !url.includes(
                                                'logo'
                                            )
                                    );

                            const height =
                                document.documentElement
                                    .scrollHeight;

                            if (
                                height ===
                                state.lastHeight
                            ) {
                                state.stableCount++;
                            } else {
                                state.lastHeight =
                                    height;

                                state.stableCount = 0;
                            }

                            return JSON.stringify(
                                {
                                    stable:
                                        state.stableCount,
                                    urls: [
                                        ...new Set(
                                            urls
                                        )
                                    ]
                                }
                            );
                        })();
                        """.trimIndent(),
                    ) { value ->
                        val json = value
                            .removeSurrounding("\"")
                            .replace(
                                "\\\"",
                                "\"",
                            )
                            .replace(
                                "\\\\",
                                "\\",
                            )

                        val stable =
                            Regex(
                                """"stable":(\d+)""",
                            )
                                .find(json)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
                                ?: 0

                        if (stable >= 3) {
                            val urls =
                                Regex(
                                    """https?://[^"\s]+""",
                                )
                                    .findAll(json)
                                    .map {
                                        it.value
                                    }
                                    .filterNot {
                                        it.contains(
                                            "/loading.jpg"
                                        )
                                    }
                                    .distinct()
                                    .toList()

                            if (urls.isNotEmpty()) {
                                resolve(
                                    urls.joinToString(
                                        "\n",
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        return rawUrls
            .removeSurrounding("\"")
            .replace(
                "\\n",
                "\n",
            )
            .replace(
                "\\\"",
                "\"",
            )
            .replace(
                "\\\\",
                "\\",
            )
            .split('\n')
            .map(String::trim)
            .filter {
                it.startsWith("https://") ||
                    it.startsWith("http://")
            }
            .filterNot {
                it.contains(
                    "/loading.jpg",
                )
            }
            .distinct()
            .mapIndexed { index, imageUrl ->
                Page(
                    index,
                    imageUrl = imageUrl,
                )
            }
    }

    override fun getMangaUrl(
        manga: SManga,
    ): String {
        return "$baseUrl/books/${manga.url.trim('/')}"
    }

    override fun getChapterUrl(
        chapter: SChapter,
    ): String {
        return "$baseUrl/books/${chapter.url.trim('/')}"
    }

    private suspend fun getMangaList(
        url: String,
    ): MangasPage {
        val document = fetchDocument(
            url,
        )

        val mangas =
            document
                .select(
                    "a[href^=/books/]",
                )
                .mapNotNull { anchor ->

                    val href =
                        anchor.attr("href")

                    val mangaId =
                        href
                            .substringAfter(
                                "/books/",
                            )
                            .trim('/')

                    if (
                        mangaId.isBlank() ||
                        mangaId.contains('/')
                    ) {
                        return@mapNotNull null
                    }

                    val title =
                        anchor
                            .selectFirst(
                                "h1,h2,h3,h4,h5,span",
                            )
                            ?.text()
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: anchor
                                .ownText()
                                .takeIf {
                                    it.isNotBlank()
                                }
                            ?: return@mapNotNull null

                    val thumbnail =
                        anchor
                            .selectFirst("img")
                            ?.let { image ->

                                image.absUrl(
                                    "src",
                                )
                                    .ifBlank {
                                        image.absUrl(
                                            "data-src",
                                        )
                                    }
                                    .ifBlank {
                                        image.absUrl(
                                            "data-original",
                                        )
                                    }
                            }
                            .orEmpty()

                    SManga.create().apply {
                        setUrlWithoutDomain(
                            "/books/$mangaId",
                        )

                        this.title = title

                        thumbnail_url =
                            thumbnail
                    }
                }
                .distinctBy {
                    it.url
                }

        val hasNextPage =
            document
                .select("a")
                .any { element ->

                    val text =
                        element
                            .text()
                            .lowercase()

                    text.contains("下一頁") ||
                        text.contains("下一页") ||
                        text.contains("next")
                }

        return MangasPage(
            mangas,
            hasNextPage,
        )
    }

    private fun parseManga(
        document: Document,
        mangaId: String,
    ): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(
                "/books/$mangaId",
            )

            title =
                document
                    .selectFirst("h1")
                    ?.text()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: throw IllegalStateException(
                        "Missing manga title",
                    )

            thumbnail_url =
                document
                    .selectFirst(
                        "main img, article img, img",
                    )
                    ?.let { image ->

                        image.absUrl(
                            "src",
                        )
                            .ifBlank {
                                image.absUrl(
                                    "data-src",
                                )
                            }
                            .ifBlank {
                                image.absUrl(
                                    "data-original",
                                )
                            }
                    }
                    .orEmpty()

            val body =
                document.body().text()

            author =
                extractInfo(
                    body,
                    "作者",
                )

            genre =
                extractInfo(
                    body,
                    "標籤",
                )

            description =
                extractDescription(
                    document,
                )

            status =
                when {
                    body.contains("完結") ->
                        SManga.COMPLETED

                    body.contains("連載中") ->
                        SManga.ONGOING

                    else ->
                        SManga.UNKNOWN
                }
        }
    }

    private fun parseChapters(
        document: Document,
        mangaId: String,
    ): List<SChapter> {
        return document
            .select(
                "a[href^=/books/$mangaId/]",
            )
            .mapNotNull { link ->

                val href =
                    link.attr("href")

                val chapterId =
                    href
                        .substringAfter(
                            "/books/$mangaId/",
                        )
                        .trim('/')

                val chapterName =
                    link.text().trim()

                if (
                    chapterId.isBlank() ||
                    chapterId.contains('/') ||
                    chapterName.isBlank()
                ) {
                    null
                } else {
                    SChapter.create().apply {
                        setUrlWithoutDomain(
                            "/books/$mangaId/$chapterId",
                        )

                        name = chapterName
                    }
                }
            }
            .distinctBy {
                it.url
            }
            .reversed()
    }

    private suspend fun fetchDocument(
        url: String,
    ): Document {
        val response =
            client.get(
                url,
                ensureSuccess = false,
            )

        response.use {
            val html =
                it.body.string()

            val document =
                Jsoup.parse(
                    html,
                    url,
                )

            return if (
                needsBrowser(document)
            ) {
                fetchDocumentWithWebView(
                    url,
                )
            } else {
                document
            }
        }
    }

    private suspend fun fetchDocumentWithWebView(
        url: String,
    ): Document {
        val html =
            runWebView<String> {
                javaScriptEnabled = true
                domStorageEnabled = true

                loadUrl(url)

                onPageFinished {
                    evaluateJs(
                        """
                        (() => {
                            const buttons =
                                Array.from(
                                    document.querySelectorAll(
                                        'button,a'
                                    )
                                );

                            const ageButton =
                                buttons.find(
                                    element => {
                                        const text =
                                            element.textContent ||
                                            '';

                                        return (
                                            text.includes(
                                                '我已滿18歲'
                                            ) ||
                                            text.includes(
                                                '我已满18岁'
                                            )
                                        );
                                    }
                                );

                            if (ageButton) {
                                ageButton.click();
                            }
                        })();
                        """.trimIndent(),
                    )

                    poll(500.milliseconds) {
                        evaluateJs(
                            """
                            (() => {
                                const text =
                                    document.body
                                        ? document.body.innerText
                                        : '';

                                const ready =
                                    text.includes(
                                        '全部漫畫'
                                    ) ||
                                    text.includes(
                                        '全部漫画'
                                    ) ||
                                    text.includes(
                                        '搜尋漫畫'
                                    ) ||
                                    text.includes(
                                        '搜索漫画'
                                    ) ||
                                    text.includes(
                                        '章節目錄'
                                    ) ||
                                    text.includes(
                                        '章節目录'
                                    ) ||
                                    document.querySelectorAll(
                                        'a[href^="/books/"]'
                                    ).length > 0;

                                return JSON.stringify({
                                    ready: ready
                                });
                            })();
                            """.trimIndent(),
                        ) { value ->

                            if (
                                value.contains(
                                    "\"ready\":true",
                                )
                            ) {
                                evaluateJs(
                                    "document.documentElement.outerHTML",
                                ) { pageHtml ->

                                    resolve(
                                        pageHtml,
                                    )
                                }
                            }
                        }
                    }
                }
            }

        val cleanHtml =
            html
                .removeSurrounding("\"")
                .replace(
                    "\\\"",
                    "\"",
                )
                .replace(
                    "\\\\",
                    "\\",
                )

        return Jsoup.parse(
            cleanHtml,
            url,
        )
    }

    private fun needsBrowser(
        document: Document,
    ): Boolean {
        val text =
            document.body().text()

        return (
            text.contains(
                "閱讀前，請確認年齡",
            ) ||
                text.contains(
                    "阅读前，请确认年龄",
                ) ||
                (
                    document
                        .select(
                            "a[href^=/books/]",
                        )
                        .isEmpty() &&
                        text.contains("18+")
                    )
            )
    }

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

    private fun extractDescription(
        document: Document,
    ): String {
        val text =
            document.body().text()

        val markers =
            listOf(
                "敘述：",
                "敘述:",
                "簡介：",
                "簡介:",
            )

        for (marker in markers) {
            val index =
                text.indexOf(marker)

            if (index >= 0) {
                return text
                    .substring(
                        index + marker.length,
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
