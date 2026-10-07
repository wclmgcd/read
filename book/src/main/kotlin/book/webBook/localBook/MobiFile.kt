package book.webBook.localBook

import book.model.Book
import book.model.BookChapter
import book.model.TxtTocRule
import book.util.EncodingDetect
import book.util.MD5Utils
import book.util.help.DefaultData
import book.webBook.exception.TocEmptyException
import org.slf4j.LoggerFactory
import java.nio.charset.Charset
import java.util.regex.Pattern
import kotlin.math.min

private val logger = LoggerFactory.getLogger(MobiFile::class.java)

/**
 * MOBI / AZW / AZW3 / PRC 解析。
 *
 * 【容器结构】
 * mobi 是一个 PalmDB 容器：开头 78 字节是数据库头，紧接着是「记录信息表」，
 * 每条 8 字节（4 字节偏移 + 1 字节属性 + 3 字节唯一 ID）。
 * record 0 里放的是 PalmDOC 头（16 字节）+ MOBI 头，正文从 record 1 开始，
 * 按 record 分块存放，每块单独压缩。
 *
 * 【解压的三个关键点】（这三点都已用真实 mobi 文件验证，写错会静默产出错字）
 *
 * 1. 回引用必须走「全局输出缓冲」，不能每个 record 各解各的。
 *    PalmDOC 的 LZ77 距离可以跨 record 边界，指向上一个 record 的尾部数据。
 *    按 record 独立解压时 distance 会大于当前已输出长度，直接数组越界。
 *
 * 2. 每个 record 的解压输出上限是 header 里的 recordSize（通常 4096），
 *    不是把该 record 的压缩数据全部解完。压缩块尾部有填充字节，
 *    解完会多出 5-11 字节垃圾，且填充里的字节对可能解出 distance=0。
 *
 * 3. 0x01-0x08 是「把接下来的 N 个字节原样拷贝」，不是「把同一个字节重复 N 次」；
 *    0xC0-0xFF 也不是保留值，而是「空格 + (b and 0x7F)」的压缩对，
 *    直接跳过会让英文书里被压缩的空格全部丢失、单词粘连。
 *    （参考实现 KlinRead 在这两点上有偏差，此处按 PalmDOC 规范修正。）
 *
 * 【能力边界】
 * 只支持未加密、PalmDOC（compression = 1 或 2）的 mobi。
 * HUFF/CDIC（17480）压缩与 DRM 加密会抛出可读异常，由调用方转成友好提示。
 * 正文里的内嵌图片暂不提取（返回纯文本，不返回 HTML）。
 */
object MobiFile {

    private const val PALMDB_HEADER_SIZE = 78
    private const val RECORD_INFO_SIZE = 8
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val COMPRESSION_HUFF_CDIC = 17480
    private const val DEFAULT_RECORD_SIZE = 4096

    /** 解压后正文长度上限，用于防御损坏文件里离谱的 textLength 造成 OOM。 */
    private const val MAX_TEXT_LENGTH = 64 * 1024 * 1024

    /**
     * `<mbp:pagebreak>` 的替身。它既是 mobi 里唯一可靠的分页信号
     * （中文书有「第X章」正则可用，英文书没有，只能靠它），
     * 又要保证不干扰正文，所以用 Unicode 私有使用区字符。
     */
    private const val PAGE_BREAK_MARK = '\uE000'

    class UnsupportedMobiException(message: String) : Exception(message)

    /** 解析结果缓存。mobi 解压一次要遍历整本书，按 bookUrl 单槽缓存（与 EpubFile 同策略）。 */
    private class Parsed(val bookUrl: String, val text: String, val chapters: ArrayList<BookChapter>)

    private var cache: Parsed? = null

    @Synchronized
    private fun load(book: Book): Parsed {
        cache?.let { if (it.bookUrl == book.bookUrl) return it }
        val text = extractText(book)
        val chapters = buildChapters(text)
        chapters.forEachIndexed { index, chapter ->
            chapter.index = index
            chapter.bookUrl = book.bookUrl
            chapter.url = MD5Utils.md5Encode16(book.originName + index + chapter.title)
        }
        if (chapters.isNotEmpty()) {
            book.latestChapterTitle = chapters.last().title
        }
        book.totalChapterNum = chapters.size
        val parsed = Parsed(book.bookUrl, text, chapters)
        cache = parsed
        return parsed
    }

    @Throws(Exception::class)
    fun getChapterList(book: Book): ArrayList<BookChapter> {
        val chapters = load(book).chapters
        if (chapters.isEmpty()) {
            throw TocEmptyException("Chapterlist is empty  " + book.getLocalFile())
        }
        return chapters
    }

    fun getContent(book: Book, chapter: BookChapter): String? {
        val parsed = load(book)
        val start = chapter.start?.toInt() ?: return null
        val end = chapter.end?.toInt() ?: return null
        if (start < 0 || end > parsed.text.length || start >= end) {
            return null
        }
        return parsed.text.substring(start, end).replace(PAGE_BREAK_MARK.toString(), "").trim()
    }

    /**
     * 不额外补全书名/作者。
     * mobi 头里虽然有全名字段，但偏移随 MOBI 版本（v5/v6/KF8）变化，
     * 读错会得到乱码，因此沿用 [LocalBook.analyzeNameAuthor] 的文件名解析。
     * 也没有内嵌封面可提取（图片记录需要 recindex 映射，本版本不做）。
     */
    fun upBookInfo(book: Book, onlyCover: Boolean = false) {
    }

    // ---------------------------------------------------------------- 解压

    private fun extractText(book: Book): String {
        val bytes = book.getLocalFile().readBytes()
        val raw = readTextBytes(bytes)
        val charsetName = runCatching { EncodingDetect.getEncode(raw) }.getOrNull()
        val decoded = if (charsetName.isNullOrBlank()) {
            String(raw, Charsets.UTF_8)
        } else {
            runCatching { String(raw, Charset.forName(charsetName)) }.getOrElse { String(raw, Charsets.UTF_8) }
        }
        return htmlToPlainText(decoded)
    }

    /** 按 PalmDB 结构逐块解压，返回未解码的正文字节。 */
    private fun readTextBytes(bytes: ByteArray): ByteArray {
        if (bytes.size < PALMDB_HEADER_SIZE) {
            throw UnsupportedMobiException("文件太小，不是有效的 MOBI")
        }
        val type = readAscii(bytes, 60, 8)
        if (!type.startsWith("BOOKMOBI") && !type.startsWith("TEXtREAd")) {
            throw UnsupportedMobiException("不是有效的 MOBI / AZW 文件")
        }

        val recordCount = readU16(bytes, 76)
        if (recordCount <= 0) {
            throw UnsupportedMobiException("MOBI 记录表为空")
        }
        val recordOffsets = IntArray(recordCount) { readU32(bytes, PALMDB_HEADER_SIZE + it * RECORD_INFO_SIZE) }

        val headerOffset = recordOffsets[0]
        if (headerOffset <= 0 || headerOffset >= bytes.size) {
            throw UnsupportedMobiException("MOBI 头缺失")
        }

        val compression = readU16(bytes, headerOffset)
        val textLength = readU32(bytes, headerOffset + 4)
        val textRecordCount = readU16(bytes, headerOffset + 8)
        val recordSize = readU16(bytes, headerOffset + 10).takeIf { it > 0 } ?: DEFAULT_RECORD_SIZE
        val encryption = readU16(bytes, headerOffset + 12)

        if (encryption != 0) {
            throw UnsupportedMobiException("这本书有 DRM 保护，无法读取")
        }
        if (compression == COMPRESSION_HUFF_CDIC) {
            throw UnsupportedMobiException("暂不支持 HUFF/CDIC 压缩的 MOBI，请先转成 EPUB 或 TXT")
        }
        if (compression != COMPRESSION_NONE && compression != COMPRESSION_PALMDOC) {
            throw UnsupportedMobiException("不支持的 MOBI 压缩方式（$compression）")
        }

        // 全局输出缓冲：回引用可能跨 record，必须共用同一块内存
        val estimated = if (textLength in 1..MAX_TEXT_LENGTH) {
            textLength
        } else {
            min(recordSize.toLong() * textRecordCount, MAX_TEXT_LENGTH.toLong()).toInt()
        }
        var buf = ByteArray(estimated.coerceAtLeast(1024))
        var len = 0

        fun ensure(extra: Int) {
            if (len + extra <= buf.size) return
            var size = buf.size
            while (size < len + extra) size = size shl 1
            buf = buf.copyOf(size)
        }

        for (r in 1..textRecordCount) {
            val start = recordOffsets.getOrNull(r) ?: break
            val end = recordOffsets.getOrNull(r + 1) ?: bytes.size
            if (start <= 0 || start >= end || end > bytes.size) continue
            val base = len

            if (compression == COMPRESSION_NONE) {
                val take = min(end - start, recordSize)
                ensure(take)
                bytes.copyInto(buf, len, start, start + take)
                len += take
                continue
            }

            var i = start
            while (i < end && len - base < recordSize) {
                val b = bytes[i].toInt() and 0xFF
                when {
                    b == 0 -> {
                        ensure(1)
                        buf[len++] = 0
                        i++
                    }
                    b <= 0x08 -> {
                        // 原样拷贝接下来的 b 个字节
                        ensure(b)
                        var k = 0
                        while (k < b && i + 1 + k < end) {
                            buf[len++] = bytes[i + 1 + k]
                            k++
                        }
                        i += b + 1
                    }
                    b <= 0x7F -> {
                        ensure(1)
                        buf[len++] = b.toByte()
                        i++
                    }
                    b <= 0xBF -> {
                        if (i + 1 >= end) break
                        val pair = (b shl 8) or (bytes[i + 1].toInt() and 0xFF)
                        val distance = (pair shr 3) and 0x07FF
                        val length = (pair and 0x07) + 3
                        // distance 为 0 只会出现在 record 尾部的填充里，跳过而不是取 buf[0]
                        if (distance == 0) {
                            i += 2
                            continue
                        }
                        ensure(length)
                        var src = len - distance
                        var k = 0
                        while (k < length) {
                            if (src < 0) {
                                src++
                                k++
                                continue
                            }
                            buf[len++] = buf[src++]
                            k++
                        }
                        i += 2
                    }
                    else -> {
                        ensure(2)
                        buf[len++] = 0x20
                        buf[len++] = (b and 0x7F).toByte()
                        i++
                    }
                }
            }
            // 压缩块尾部的填充要去掉，否则会在正文里留下垃圾
            if (len - base > recordSize) {
                len = base + recordSize
            }
        }

        val raw = buf.copyOf(len)
        // textLength 是解压后的真实长度，最后一个 record 通常有填充，需要截断
        return if (textLength in 1..raw.size) raw.copyOfRange(0, textLength) else raw
    }

    // ---------------------------------------------------------------- 文本化

    private val scriptStyle = Regex("""<(script|style)\b.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val pageBreakTag = Regex("""<mbp:pagebreak\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val blockBreak = Regex(
        """</?(?:p|div|br|h[1-6]|li|tr|blockquote|section|article|mbp:pagebreak)\b[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val anyTag = Regex("""<[^>]+>""")

    /**
     * 把 mobi 的 HTML 正文转成纯文本。
     *
     * 刻意不返回 HTML：mobi 里的图片是 `recindex:00001` 形式的记录引用，
     * 需要额外的图片记录映射才能显示，本版本不做，因此直接返回纯文本，
     * 前端按 txt 的方式渲染即可，不会出现裂图。
     */
    private fun htmlToPlainText(html: String): String {
        var text = scriptStyle.replace(html, " ")
        // 分页标记必须先于 blockBreak 处理，否则会被当成普通块级标签吃掉
        text = pageBreakTag.replace(text, "\n$PAGE_BREAK_MARK\n")
        text = blockBreak.replace(text, "\n")
        text = anyTag.replace(text, "")
        text = decodeEntities(text)
        return text.lineSequence()
            .map { it.replace('\u00A0', ' ').trim() }
            .joinToString("\n")
    }

    private fun decodeEntities(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        .replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–")
        .replace("&hellip;", "…").replace("&ldquo;", "“").replace("&rdquo;", "”")
        .replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("&amp;", "&")

    // ---------------------------------------------------------------- 分章

    /** 内置兜底规则，用于 DefaultData 规则缺失或全部不匹配时。 */
    private val builtinPatterns = listOf(
        Pattern.compile("^\\s*第[0-9零一二三四五六七八九十百千万两]{1,12}[章节回卷篇集部][^\\n]{0,40}$", Pattern.MULTILINE),
        Pattern.compile("^\\s*(?:序章|序言|楔子|引子|尾声|后记|番外)[^\\n]{0,40}$", Pattern.MULTILINE)
    )

    private fun buildChapters(text: String): ArrayList<BookChapter> {
        if (text.isBlank()) return arrayListOf()

        // 优先按章节标题切：中文书最准
        pickPattern(text)?.let { return chaptersByPattern(text, it) }

        // 退而求其次：按 mobi 自带的分页标记切（英文书走这条）
        if (text.contains(PAGE_BREAK_MARK)) {
            val chapters = chaptersByPageBreak(text)
            if (chapters.isNotEmpty()) return chapters
        }

        return arrayListOf(chapterOf("全文", 0, text.length))
    }

    /** 选「命中次数最多」的规则，与 TextFile 的取法一致；都不命中时退回内置规则。 */
    private fun pickPattern(text: String): Pattern? {
        var best: Pattern? = null
        var bestCount = 1
        for (rule in safeTocRules().reversed()) {
            val pattern = runCatching { rule.rule.toPattern(Pattern.MULTILINE) }.getOrNull() ?: continue
            val count = countMatches(pattern, text)
            if (count >= bestCount) {
                bestCount = count
                best = pattern
            }
        }
        if (best != null) return best
        for (pattern in builtinPatterns) {
            if (countMatches(pattern, text) >= 2) return pattern
        }
        return null
    }

    private fun countMatches(pattern: Pattern, text: String): Int {
        val matcher = pattern.matcher(text)
        var count = 0
        while (matcher.find()) {
            count++
            if (count > 5000) break
        }
        return count
    }

    private fun safeTocRules(): List<TxtTocRule> = runCatching {
        DefaultData.txtTocRules.filter { it.enable && it.rule.isNotBlank() }
    }.getOrElse {
        logger.warn("读取 txt 分章规则失败，mobi 将使用内置规则：${it.message}")
        emptyList()
    }

    private fun chaptersByPattern(text: String, pattern: Pattern): ArrayList<BookChapter> {
        val chapters = arrayListOf<BookChapter>()
        val matcher = pattern.matcher(text)
        var lastStart = -1
        var lastTitle = ""
        while (matcher.find()) {
            val start = matcher.start()
            val title = matcher.group().trim()
            if (lastStart < 0) {
                // 第一个标题之前的内容当作前言
                if (start > 0 && text.substring(0, start).isNotBlank()) {
                    chapters.add(chapterOf("前言", 0, start))
                }
            } else {
                chapters.add(chapterOf(lastTitle, lastStart, start))
            }
            lastStart = start
            lastTitle = title
        }
        if (lastStart >= 0) {
            chapters.add(chapterOf(lastTitle, lastStart, text.length))
        }
        return chapters
    }

    private fun chaptersByPageBreak(text: String): ArrayList<BookChapter> {
        val chapters = arrayListOf<BookChapter>()
        var segStart = 0
        while (true) {
            val pos = text.indexOf(PAGE_BREAK_MARK, segStart)
            val segEnd = if (pos < 0) text.length else pos
            addSegment(text, segStart, segEnd, chapters)
            if (pos < 0) break
            segStart = pos + 1
        }
        return chapters
    }

    private fun addSegment(text: String, start: Int, end: Int, out: MutableList<BookChapter>) {
        if (end <= start) return
        val segment = text.substring(start, end)
        if (segment.isBlank()) return
        val title = segment.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != PAGE_BREAK_MARK.toString() }
            ?.take(40)
            ?: "第${out.size + 1}节"
        out.add(chapterOf(title, start, end))
    }

    private fun chapterOf(title: String, start: Int, end: Int) = BookChapter().apply {
        this.title = title
        this.start = start.toLong()
        this.end = end.toLong()
    }

    // ---------------------------------------------------------------- 字节读取

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > bytes.size) return ""
        return String(bytes, offset, length, Charsets.US_ASCII)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
