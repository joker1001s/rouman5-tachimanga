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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale
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

        val url =
            "$baseUrl/search?term=$encoded"

        return getMangaList(url)
    }

    // ============================================================
    // Manga details
    // ============================================================

    override suspend fun getMangaByUrl(
        url: HttpUrl,
    ): SManga? {

        val id = extractMangaId(
            url.encodedPath,
        )

        if (id.isBlank()) {
            return null
        }

        val mangaUrl = "$baseUrl/books/$id"

        // First try normal HTTP.
        val document = fetchDocument(
            mangaUrl,
        )

        if (document != null) {
            val manga = runCatching {
                parseManga(
                    document,
                    id,
                )
            }.getOrNull()

            val chapters = runCatching {
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

        // Fallback to WebView.
        val webDocument =
            fetchDocumentWithWebView(
                mangaUrl,
                requireChapters = true,
            )

        if (webDocument != null) {
            return runCatching {
                parseManga(
                    webDocument,
                    id,
                )
            }.getOrNull()
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

        var document = fetchDocument(
            mangaUrl,
        )

        var details: SManga? = null
        var updatedChapters: List<SChapter> =
            emptyList()

        if (document != null) {
            details = runCatching {
                parseManga(
                    document,
                    id,
                )
            }.getOrNull()

            updatedChapters = runCatching {
                parseChapters(
                    document,
                    id,
                )
            }.getOrDefault(
                emptyList(),
            )
        }

        // If the normal HTTP response is incomplete,
        // use a real WebView.
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
                details = runCatching {
                    parseManga(
                        document,
                        id,
                    )
                }.getOrNull()

                updatedChapters = runCatching {
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
            getChapterUrl(chapter)

        val result =
            withTimeoutOrNull(
                45.seconds,
            ) {
                runWebView<String> {

                    javaScriptEnabled = true
                    domStorageEnabled = true

                    loadUrl(chapterUrl)

                    onPageFinished {

                        poll(
                            500.milliseconds,
                        ) {

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
                                                    ).trim();

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
                                    }

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
                                                img.getAttribute("data-src") ||
                                                img.getAttribute("data-original") ||
                                                ""
                                        )
                                        .filter(
                                            url =>
                                                url &&
                                                !url.startsWith("data:") &&
                                                !url.includes("loading") &&
                                                !url.includes("logo")
                                        );

                                    return [
                                        ...new Set(images)
                                    ].join("\\n");
                                })();
                                """.trimIndent(),
                            ) { value ->

                                if (
                                    value.isNotBlank()
                                ) {
                                    resolve(value)
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
                ?.replace("\\n", "\n")
                ?.split('\n')
                ?.map {
                    it.trim()
                }
                ?.filter {
                    it.startsWith("https://") ||
                        it.startsWith("http://")
                }
                ?.filterNot {
                    it.contains(
                        "loading",
                        ignoreCase = true,
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

        val url = chapter.url

        return when {
            url.startsWith("http://") ->
                url

            url.startsWith("https://") ->
                url

            url.startsWith("/") ->
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
            fetchDocument(url)
                ?: return MangasPage(
                    emptyList(),
                    false,
                )

        val mangas =
            document
                .select("a[href^=/books/]")
                .mapNotNull { anchor ->

                    val href =
                        anchor.absUrl("href")

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

                    // Exclude chapter URLs.
                    if (
                        path.isBlank() ||
                            path.contains("/")
                    ) {
                        return@mapNotNull null
                    }

                    val title =
                        anchor
                            .select(
                                "h3, h4, span",
                            )
                            .firstOrNull()
                            ?.text()
                            ?.takeIf {
                                it.isNotEmpty()
                            }
                            ?: anchor.text()

                    if (
                        title.isBlank()
                    ) {
                        return@mapNotNull null
                    }

                    val image =
                        anchor
                            .select("img")
                            .firstOrNull()
                            ?.let { image ->

                                image.absUrl(
                                    "src",
                                ).ifBlank {

                                    image.absUrl(
                                        "data-src",
                                    ).ifBlank {

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
                .select("a")
                .any { link ->

                    val text =
                        link.text()

                    text.contains("下一頁") ||
                        text.contains("下一页") ||
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
                    ?.attr("content"),

                document
                    .select(
                        "meta[name=twitter:title]",
                    )
                    .firstOrNull()
                    ?.attr("content"),

                document.title(),
            )
                .asSequence()
                .map {
                    it.orEmpty()
                }
                .map {
                    cleanMangaTitle(it)
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

        manga.thumbnail_url =
            document
                .select(
                    "meta[property=og:image]",
                )
                .firstOrNull()
                ?.attr("content")
                .orEmpty()
                .ifBlank {

                    document
                        .select("img")
                        .firstOrNull()
                        ?.let { image ->

                            image.absUrl(
                                "src",
                            ).ifBlank {

                                image.absUrl(
                                    "data-src",
                                ).ifBlank {

                                    image.absUrl(
                                        "data-original",
                                    )
                                }
                            }
                        }
                        .orEmpty()
                }

        val bodyText =
            document.body()?.text().orEmpty()

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
            extractDescription(document)

        val status =
            extractInfo(
                bodyText,
                "狀態",
            )

        manga.status =
            if (
                status.contains("完結") ||
                    status.contains("完结")
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
                    link.absUrl("href")

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

                if (
                    name.isBlank()
                ) {
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
    // HTTP document
    // ============================================================

    private suspend fun fetchDocument(
        url: String,
    ): Document? {

        return runCatching {

            client
                .get(url)
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

                    javaScriptEnabled = true
                    domStorageEnabled = true

                    loadUrl(url)

                    onPageFinished {

                        poll(
                            500.milliseconds,
                        ) {

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
                                                    ).trim();

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

                                    const title =
                                        document.querySelector("h1");

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
                                    resolve(value)
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
            document.body()
                ?.text()
                .orEmpty()

        val markers =
            listOf(
                "簡介:",
                "簡介：",
                "敘述:",
                "敘述：",
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
