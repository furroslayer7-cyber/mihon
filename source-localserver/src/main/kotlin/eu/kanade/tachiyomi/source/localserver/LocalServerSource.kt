package eu.kanade.tachiyomi.source.localserver

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tachiyomi.domain.chapter.service.ChapterRecognition

/**
 * Fuente que lee series desde un servidor HTTP local en la PC del usuario.
 *
 * Reemplaza el LocalSource clásico (que lee del almacenamiento del teléfono) por una
 * fuente que se conecta por HTTP a un servidor que sirve la carpeta de manga en la PC.
 *
 * Funciona con cualquier servidor HTTP estático que liste directorios como HTML
 * (por ejemplo `python3 -m http.server 8080`, `npx serve`, Apache, Nginx con autoindex).
 *
 * Convención de carpetas esperada en el servidor:
 *
 *   /Berserk/                <- serie (carpeta)
 *     /Capitulo 01/          <- capítulo (carpeta con imágenes sueltas)
 *       001.jpg
 *       002.jpg
 *     /Capitulo 02.cbz       <- capítulo (archivo .cbz/.zip/.epub)
 *   /One Piece/
 *     ...
 *
 * El lector maneja .cbz/.zip/.epub automáticamente al descargarlos; este source solo
 * necesita devolverles la URL del archivo.
 */
@Inject
@SingleIn(AppScope::class)
class LocalServerSource(
    private val context: Context,
) : HttpSource(), Source, UnmeteredSource {

    /**
     * URL base del servidor HTTP que corre en la PC.
     *
     * IMPORTANTE: cambia esto por la IP de tu PC en la red local.
     * Ejemplos:
     *   - "http://192.168.1.104:8080"
     *   - "http://10.0.0.5:8000"
     *
     * Sin barra final.
     */
    override val baseUrl: String = "http://192.168.1.104:8080"

    override val name: String = "Servidor Local PC"

    override val lang: String = "es"

    override val supportsLatest: Boolean = true

    /**
     * ID fijo y positivo. Debe ser único entre todas las fuentes instaladas.
     * Lo fijamos manualmente para poder registrarlo en AndroidSourceManager.
     */
    override val id: Long = 9876543210L

    companion object {
        const val ID = 9876543210L

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        private val ARCHIVE_EXTENSIONS = setOf("cbz", "zip", "cbr", "rar", "epub")
    }

    // ------------------------------------------------------------------
    // Listado de series (carpetas en la raíz del servidor)
    // ------------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage =
        getSearchManga(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getSearchManga(page, "", FilterList())

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val doc = fetchHtml(baseUrl)

        val entries = doc.select("a[href]")
            .mapNotNull { a -> toMangaEntry(a.attr("href"), a.text()) }
            // Solo carpetas (las carpetas vienen con / final en el href)
            .filter { it.url.endsWith("/") }
            .distinctBy { it.name }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }

        // Convertimos MangaEntry -> SManga (lo que espera MangasPage)
        val mangas = entries.map { entry ->
            SManga.create().apply {
                title = entry.name
                url = entry.url
            }
        }

        return MangasPage(mangas, false)
    }

    // ------------------------------------------------------------------
    // Detalles + lista de capítulos
    // ------------------------------------------------------------------

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val newChapters = if (fetchChapters) fetchChapterList(manga) else chapters
        // Marcamos el manga como inicializado para que la UI no intente refrescar metadata infinitamente
        if (fetchDetails) manga.initialized = true
        return SMangaUpdate(manga, newChapters)
    }

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val doc = fetchHtml(baseUrl + "/" + manga.url)

        val chapters = doc.select("a[href]")
            .mapNotNull { a -> toChapterEntry(a.attr("href"), a.text()) }
            .filter { entry ->
                entry.url.endsWith("/") ||
                    ARCHIVE_EXTENSIONS.any { ext -> entry.url.endsWith(".$ext", ignoreCase = true) }
            }
            .map { entry ->
                SChapter.create().apply {
                    url = manga.url + entry.url
                    name = entry.name
                    date_upload = 0L
                    chapter_number = ChapterRecognition
                        .parseChapterNumber(manga.title, name, null)
                        .toFloat()
                }
            }
            // Invertimos para que el capítulo más reciente quede arriba
            .reversed()

        return chapters
    }

    // ------------------------------------------------------------------
    // Páginas de un capítulo
    // ------------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = baseUrl + "/" + chapter.url
        val isArchive = ARCHIVE_EXTENSIONS.any { ext -> chapter.url.endsWith(".$ext", ignoreCase = true) }

        // Si es un .cbz/.zip/.epub, devolvemos una sola "página" con la URL directa.
        // El HttpPageLoader de Mihon descargará el archivo y el lector lo abrirá como
        // un archive usando DownloadPageLoader tras la descarga.
        if (isArchive) {
            return listOf(Page(0, chapter.url, chapterUrl))
        }

        // Si es carpeta, listamos las imágenes que contiene
        val doc = fetchHtml(chapterUrl)
        return doc.select("a[href]")
            .mapNotNull { a -> a.attr("href") to a.text() }
            .filter { (href, _) ->
                val ext = href.substringAfterLast('.', "").lowercase()
                href.substringBeforeLast('.', "").isNotBlank() && ext in IMAGE_EXTENSIONS
            }
            .sortedBy { (href, _) -> href }
            .mapIndexed { index, (href, _) ->
                Page(index, chapter.url, absoluteUrl(chapterUrl, href))
            }
    }

    override fun getFilterList(): FilterList = FilterList()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun fetchHtml(url: String): Document {
        val response: Response = client.newCall(GET(url, headers)).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("Error HTTP ${response.code} al obtener $url")
        }
        val body = response.body?.string() ?: throw Exception("Respuesta vacía de $url")
        return Jsoup.parse(body, url)
    }

    private fun absoluteUrl(base: String, href: String): String {
        return if (href.startsWith("http")) {
            href
        } else if (href.startsWith("/")) {
            baseUrl + href
        } else {
            base.trimEnd('/') + "/" + href.trimStart('/')
        }
    }

    private fun toMangaEntry(href: String, text: String): MangaEntry? {
        val name = text.trim().removeSuffix("/")
        if (name.isEmpty() || name == ".." || name == "../") return null
        if (href.isEmpty() || href == "/" || href == "../") return null
        return MangaEntry(name = name, url = href)
    }

    private fun toChapterEntry(href: String, text: String): MangaEntry? {
        val name = text.trim().removeSuffix("/")
        if (name.isEmpty() || name == ".." || name == "../") return null
        if (href.isEmpty() || href == "/" || href == "../") return null
        return MangaEntry(name = name, url = href)
    }

    private data class MangaEntry(val name: String, val url: String)
}
