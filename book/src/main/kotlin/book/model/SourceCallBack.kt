package book.model

import book.util.isTrue
import book.webBook.Debug
import book.webBook.analyzeRule.SourceLoginJsExtensions

/**
 * 书源事件回调(legado 兼容)
 *
 * 说明: 下列事件全部由客户端交互触发(点击作者名、加入书架、保存进度…),
 * 后端没有对应的触发点, 因此这里只提供工具方法: 前端(或 WebSocket 通道)
 * 把事件回传过来时, 由本对象执行书源的 callBackJs。
 *
 * 语义与 legado 一致: js 返回 true 表示「书源接管」, 调用方不应再执行默认逻辑。
 */
object SourceCallBack {
    const val CLICK_AUTHOR = "clickAuthor"
    const val LONG_CLICK_AUTHOR = "longClickAuthor"
    const val CLICK_BOOK_NAME = "clickBookName"
    const val LONG_CLICK_BOOK_NAME = "longClickBookName"
    const val CLICK_CUSTOM_BUTTON = "clickCustomButton"
    const val LONG_CLICK_CUSTOM_BUTTON = "longClickCustomButton"
    const val CLICK_SHARE_BOOK = "clickShareBook"
    const val CLICK_CLEAR_CACHE = "clickClearCache"
    const val CLICK_COPY_BOOK_URL = "clickCopyBookUrl"
    const val CLICK_COPY_TOC_URL = "clickCopyTocUrl"
    const val CLICK_COPY_PLAY_URL = "clickCopyPlayUrl"
    const val CLICK_BOOK_LABEL = "clickBookLabel"
    const val LONG_CLICK_BOOK_LABEL = "longClickBookLabel"

    const val ADD_BOOK_SHELF = "addBookShelf"
    const val DEL_BOOK_SHELF = "delBookShelf"
    const val SAVE_READ = "saveRead"
    const val START_READ = "startRead"
    const val END_READ = "endRead"
    const val START_SHELF_REFRESH = "startShelfRefresh"
    const val END_SHELF_REFRESH = "endShelfRefresh"

    /**
     * 执行书源回调规则
     *
     * @return true 表示书源接管了该事件, 调用方不再执行默认逻辑
     */
    fun callBack(
        event: String,
        source: BookSource?,
        book: Book? = null,
        chapter: BookChapter? = null,
        result: String? = null
    ): Boolean {
        // 没开事件监听、或没写回调 js —— 都算「书源不接管」
        if (source == null || !source.eventListener) return false
        val jsStr = source.getContentRule().callBackJs
        if (jsStr.isNullOrEmpty()) return false
        return kotlin.runCatching {
            source.evalJS(jsStr) {
                put("event", event)
                put("java", SourceLoginJsExtensions(source = source))
                put("result", result)
                put("book", book)
                put("chapter", chapter)
            }.toString().isTrue()
        }.onFailure {
            Debug.log(source.bookSourceUrl, "书源执行回调事件${event}出错\n${it.localizedMessage}")
        }.getOrElse { false }
    }

    /**
     * 同 [callBack], 但书源不接管时执行 [noCall]
     */
    fun callBackBtn(
        event: String,
        source: BookSource?,
        book: Book? = null,
        chapter: BookChapter? = null,
        result: String? = null,
        noCall: (() -> Unit)? = null
    ) {
        if (!callBack(event, source, book, chapter, result)) {
            noCall?.invoke()
        }
    }
}
