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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

@Source
abstract class Rouman5 : KeiSource() {

    override val supportsLatest = true

    override suspend fun getPopularManga(page: Int): MangasPage =
        getMangaList("$baseUrl/books?continued=&page=${page - 1}")

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getMangaList("$baseUrl/books?continued=true&page=${page - 1}")

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8)
        return getMangaList("$baseUrl/search?term=$encoded&page=${page - 1}")
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val segments = url.pathSegments
        if (segments.firstOrNull() != "books") return null

        val id = segments.getOrNull(1) ?: return null
        if (id.isBlank()) return null

        return fetchDocument(url.toString()).let { parseManga(it, id) }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.trim('/').substringBefore('/')
        val doc = fetchDocument("$baseUrl/books/$id")
        return SMangaUpdate(
            parseManga(doc, id),
            parseChapters(doc, id),
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)

        val result = runWebView<String> {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true

            loadUrl(chapterUrl)

            onPageFinished {
                evaluateJs(
                    """
                    (() => {
                        const buttons = Array.from(document.querySelectorAll('button,a'));
                        const ageButton = buttons.find(el =>
                            (el.textContent || '').includes('我已滿18歲') ||
                            (el.textContent || '').includes('我已满18岁')
                        );
                        if (ageButton) ageButton.click();
                    })();
                    """.trimIndent(),
                )

                poll(500) {
                    evaluateJs(
                        """
                        (() => {
                            window.__rouman5 = window.__rouman5 || { lastHeight: -1, stable: 0 };
                            const state = window.__rouman5;
                            window.scrollTo(0, document.body.scrollHeight);

                            const urls = Array.from(document.images)
                                .map(img =>
                                    img.currentSrc ||
                                    img.src ||
                                    img.dataset.src ||
                                    img.dataset.original ||
                                    ''
                                )
                                .filter(url =>
                                    /^https?:\/\//.test(url) &&
                                    !url.includes('/loading.jpg') &&
                                    !url.includes('logo') &&
                                    !url.startsWith('data:')
                                );

                            const height = document.documentElement.scrollHeight;
                            if (height === state.lastHeight) {
                                state.stable += 1;
                            } else {
                                state.lastHeight = height;
                                state.stable = 0;
                            }

                            return JSON.stringify({
                                stable: state.stable,
                                urls: [...new Set(urls)]
                            });
                        })();
                        """.trimIndent(),
                    ) { value ->
                        val json = value.removeSurrounding("\"")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")

                        if (json.contains("\"stable\":3") ||
                            json.contains("\"stable\":4") ||
                            json.contains("\"stable\":5")
                        ) {
                            val urls = Regex("https?://[^\\\"\\s]+")
                                .findAll(json)
                                .map { it.value }
                                .filterNot { it.contains("/loading.jpg") }
                                .distinct()
                                .toList()

                            if (urls.isNotEmpty()) {
                                resolve(urls.joinToString("\n"))
                            }
                        }
                    }
                }
            }
        }

        return result
            .removeSurrounding("\"")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .split('\n')
            .map(String::trim)
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .filterNot { it.contains("/loading.jpg") }
            .distinct()
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl/books/${manga.url.trim('/')}"

    override fun getChapterUrl(chapter: SChapter): String =
        "$baseUrl/books/${chapter.url.trim('/')}"

    private suspend fun getMangaList(url: String): MangasPage {
        val doc = fetchDocument(url)

        val mangas = doc.select("a[href^=/books/]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                val path = href.substringAfter("/books/").trim('/')

                if (path.isBlank() || path.contains('/')) return@mapNotNull null

                val title = anchor.selectFirst("h1,h2,h3,h4,h5,span")
                    ?.text()
                    ?.takeIf { it.isNotEmpty() }
                    ?: anchor.ownText().takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                val image = anchor.selectFirst("img")?.let { img ->
                    img.absUrl("src")
                        .ifBlank { img.absUrl("data-src") }
                        .ifBlank { img.absUrl("data-original") }
                }.orEmpty()

                SManga.create().apply {
                    url = path
                    this.title = title
                    thumbnail_url = image
                }
            }
            .distinctBy { it.url }

        val hasNextPage = doc.select("a").any {
            val text = it.text().lowercase()
            text.contains("下一頁") ||
                text.contains("下一页") ||
                text.contains("next")
        }

        return MangasPage(mangas, hasNextPage)
    }

    private fun parseManga(doc: Document, id: String): SManga =
        SManga.create().apply {
            url = id
            title = doc.selectFirst("h1")
                ?.text()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Missing manga title")

            thumbnail_url = doc.selectFirst("main img, article img, img")?.let { image ->
                image.absUrl("src")
                    .ifBlank { image.absUrl("data-src") }
                    .ifBlank { image.absUrl("data-original") }
            }.orEmpty()

            val body = doc.body().text()
            author = extractInfo(body, "作者")
            genre = extractInfo(body, "標籤")
            description = extractDescription(doc)

            status = when {
                body.contains("完結") -> SManga.COMPLETED
                body.contains("連載中") -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }

    private fun parseChapters(doc: Document, id: String): List<SChapter> =
        doc.select("a[href^=/books/$id/]")
            .mapNotNull { link ->
                val href = link.attr("href")
                val chapterId = href.substringAfter("/books/$id/").trim('/')
                val name = link.text()

                if (chapterId.isBlank() || chapterId.contains('/') || name.isEmpty()) {
                    null
                } else {
                    SChapter.create().apply {
                        url = "$id/$chapterId"
                        this.name = name
                    }
                }
            }
            .distinctBy { it.url }
            .reversed()

    private suspend fun fetchDocument(url: String): Document {
        val response = client.get(url, ensureSuccess = false)
        val document = response.use { it.asJsoup() }

        if (needsBrowser(document)) {
            return fetchDocumentWithWebView(url)
        }

        return document
    }

    private suspend fun fetchDocumentWithWebView(url: String): Document {
        val html = runWebView<String> {
            javaScriptEnabled = true
            domStorageEnabled = true

            loadUrl(url)

            onPageFinished {
                evaluateJs(
                    """
                    (() => {
                        const buttons = Array.from(document.querySelectorAll('button,a'));
                        const ageButton = buttons.find(el =>
                            (el.textContent || '').includes('我已滿18歲') ||
                            (el.textContent || '').includes('我已满18岁')
                        );
                        if (ageButton) ageButton.click();
                    })();
                    """.trimIndent(),
                )

                poll(500) {
                    evaluateJs(
                        """
                        (() => {
                            const text = document.body ? document.body.innerText : '';
                            return JSON.stringify({
                                ready: text.includes('全部漫畫') ||
                                    text.includes('全部漫画') ||
                                    text.includes('搜尋漫畫') ||
                                    text.includes('搜索漫画') ||
                                    text.includes('章節目錄') ||
                                    text.includes('章節目录') ||
                                    document.querySelectorAll('a[href^="/books/"]').length > 0
                            });
                        })();
                        """.trimIndent(),
                    ) { value ->
                        if (value.contains(""ready":true")) {
                            evaluateJs("document.documentElement.outerHTML") { htmlValue ->
                                resolve(htmlValue)
                            }
                        }
                    }
                }
            }
        }

        return Jsoup.parse(
            html.removeSurrounding(""").replace("\"", """),
            url,
        )
    }

    private fun needsBrowser(doc: Document): Boolean {
        val text = doc.body().text()
        return (
            text.contains("閱讀前，請確認年齡") ||
                text.contains("阅读前，请确认年龄") ||
                doc.select("a[href^=/books/]").isEmpty() 
            ) && text.contains("18+")
    }

    private fun extractInfo(text: String, label: String): String {
        val regex = Regex(
            """$label\s+(.+?)(?=\s+(作者|狀態|地區|更新|標籤)\s+|$)""",
        )
        return regex.find(text)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractDescription(doc: Document): String {
        val text = doc.body().text()
        return listOf("敘述：", "敘述:", "簡介：", "簡介:")
            .firstNotNullOfOrNull { marker ->
                val index = text.indexOf(marker)
                if (index < 0) {
                    null
                } else {
                    text.substring(index + marker.length)
                        .substringBefore("放入書架")
                        .trim()
                }
            }
            .orEmpty()
    }
}
