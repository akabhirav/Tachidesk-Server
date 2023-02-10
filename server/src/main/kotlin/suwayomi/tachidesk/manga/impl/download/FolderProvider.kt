package suwayomi.tachidesk.manga.impl.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.Page
import suwayomi.tachidesk.manga.impl.Page.getPageName
import suwayomi.tachidesk.manga.impl.download.model.DownloadChapter
import suwayomi.tachidesk.manga.impl.util.getChapterDirPath
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse
import suwayomi.tachidesk.manga.model.table.PageTable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.reflect.KSuspendFunction2

/*
* Provides downloaded files when pages were downloaded into folders
* */
class FolderProvider(mangaId: Int, chapterId: Int) : DownloadedFilesProvider(mangaId, chapterId) {
    override fun getImage(index: Int): Pair<InputStream, String> {
        val chapterDir = getChapterDirPath(mangaId, chapterId)
        val folder = File(chapterDir)
        folder.mkdirs()
        val file = folder.listFiles()?.get(index)
        val fileType = file!!.name.substringAfterLast(".")
        return Pair(FileInputStream(file).buffered(), "image/$fileType")
    }

    @OptIn(FlowPreview::class)
    override suspend fun download(
        download: DownloadChapter,
        scope: CoroutineScope,
        step: KSuspendFunction2<DownloadChapter?, Boolean, Unit>
    ): Boolean {
        val pageCount = download.chapter.pageCount
        val chapterDir = getChapterDirPath(mangaId, chapterId)
        val folder = File(chapterDir)
        folder.mkdirs()

        for (pageNum in 0 until pageCount) {
            var pageProgressJob: Job? = null

            val pageEntry = transaction {
                PageTable.select { (PageTable.chapter eq chapterId) }
                    .orderBy(PageTable.index to SortOrder.ASC)
                    .limit(1, pageNum.toLong()).first()
            }

            val fileName = getPageName(pageEntry[PageTable.index]) // might have to change this to index stored in database
            if (isExistingFile(folder, fileName)) continue
            try {
                val image = Page.getPageImage(
                    mangaId = download.mangaId,
                    chapterIndex = download.chapterIndex,
                    index = pageNum
                ) { flow ->
                    pageProgressJob = flow
                        .sample(100)
                        .distinctUntilChanged()
                        .onEach {
                            download.progress = (pageNum.toFloat() + (it.toFloat() * 0.01f)) / pageCount
                            step(null, false) // don't throw on canceled download here since we can't do anything
                        }
                        .launchIn(scope)
                }.first

                val filePath = "$chapterDir/$fileName"
                ImageResponse.saveImage(filePath, image)
                withContext(Dispatchers.IO) {
                    image.close()
                }
            } finally {
                // always cancel the page progress job even if it throws an exception to avoid memory leaks
                pageProgressJob?.cancel()
            }
            // TODO: retry on error with 2,4,8 seconds of wait
            download.progress = ((pageNum + 1).toFloat()) / pageCount
            step(download, false)
        }
        return true
    }

    override fun delete(): Boolean {
        val chapterDir = getChapterDirPath(mangaId, chapterId)
        return File(chapterDir).deleteRecursively()
    }

    private fun isExistingFile(folder: File, fileName: String): Boolean {
        val existingFile = folder.listFiles { file ->
            file.isFile && file.name.startsWith(fileName)
        }?.firstOrNull()
        return existingFile?.exists() == true
    }
}
