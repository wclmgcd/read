package book.webBook.localBook

import book.model.Book
import book.model.BookChapter
import book.util.FileUtils
import book.util.help.BookHelp
import book.webBook.exception.TocEmptyException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.script.SimpleBindings

object LocalBook {

    /** 本地书支持的全部后缀。新增格式时只改这一处。 */
    private val supportedExtensions = arrayOf(".txt", ".epub", ".mobi", ".azw", ".azw3", ".prc")

    /** mobi 家族后缀，见 [Book.isMobi]。 */
    private val mobiExtensions = arrayOf(".mobi", ".azw", ".azw3", ".prc")

    /** 文件名是否属于 mobi 家族。 */
    fun isMobiFileName(name: String): Boolean = mobiExtensions.any { name.endsWith(it, true) }

    /** 文件名是否是后端能解析的本地书。 */
    fun isSupportedFileName(name: String): Boolean = supportedExtensions.any { name.endsWith(it, true) }

    private val nameAuthorPatterns = arrayOf(
        Pattern.compile("(.*?)《([^《》]+)》.*?作者：(.*)"),
        Pattern.compile("(.*?)《([^《》]+)》(.*)"),
        Pattern.compile("(^)(.+) 作者：(.+)$"),
        Pattern.compile("(^)(.+) by (.+)$")
    )

    @Throws(FileNotFoundException::class, SecurityException::class)
    fun getBookInputStream(book: Book): InputStream {
        val file = book.getLocalFile()
        if (file.exists()) {
            return FileInputStream(file)
        }
        throw FileNotFoundException(book.name + " 文件不存在")
    }

    @Throws(Exception::class)
    fun getChapterList(book: Book): ArrayList<BookChapter> {
        val chapters = when {
            book.isEpub() -> {
                EpubFile.getChapterList(book)
            }
            book.isUmd() -> {
                UmdFile.getChapterList(book)
            }
            book.isCbz() -> {
                CbzFile.getChapterList(book)
            }
            book.isMobi() -> {
                MobiFile.getChapterList(book)
            }
            else -> {
                TextFile.getChapterList(book)
            }
        }
        if (chapters.isEmpty()) {
            throw TocEmptyException("Chapterlist is empty  " + book.getLocalFile())
        }
        return chapters
    }

    fun getContent(book: Book, chapter: BookChapter): String? {
        return when {
            book.isEpub() -> {
                EpubFile.getContent(book, chapter)
            }
            book.isUmd() -> {
                UmdFile.getContent(book, chapter)
            }
            book.isCbz() -> {
                CbzFile.getContent(book, chapter)
            }
            book.isMobi() -> {
                MobiFile.getContent(book, chapter)
            }
            else -> {
                TextFile.getContent(book, chapter)
            }
        }
    }

    fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        val tempFileName = fileName.substringBeforeLast(".")
        var name: String
        var author: String

        for (pattern in nameAuthorPatterns) {
            pattern.matcher(tempFileName).takeIf { it.find() }?.run {
                name = group(2)!!
                val group1 = group(1) ?: ""
                val group3 = group(3) ?: ""
                author = BookHelp.formatBookAuthor(group1 + group3)
                return Pair(name, author)
            }
        }

        name = BookHelp.formatBookName(tempFileName)
        author = BookHelp.formatBookAuthor(tempFileName.replace(name, ""))
            .takeIf { it.length != tempFileName.length } ?: ""

        return Pair(name, author)
    }

    fun deleteBook(book: Book) {
        kotlin.runCatching {
            var bookFile = book.getLocalFile();
            if (book.isLocalTxt() || book.isUmd()) {
                if (bookFile.exists()) {
                    bookFile.delete()
                }
            }
            if (book.isEpub()) {
                bookFile = bookFile.parentFile
                if (bookFile != null && bookFile.exists()) {
                    FileUtils.delete(bookFile, true)
                }
            }
        }
    }
}
