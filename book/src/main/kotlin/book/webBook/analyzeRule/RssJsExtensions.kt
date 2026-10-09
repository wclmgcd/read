package book.webBook.analyzeRule

import book.app.App
import book.model.BaseSource
import book.webBook.DebugLog
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URL

open class RssJsExtensions( private val source: BaseSource? = null,): JsExtensions {

    override  var debugLog: DebugLog? =null
    override fun getSource(): BaseSource? {
        return source
    }

    override val logger: Logger
        get() =  LoggerFactory.getLogger(RssJsExtensions::class.java)

    fun put(key: String, value: String): String {
        getSource()?.put(key, value)
        return value
    }

    fun get(key: String): String {
        return getSource()?.get(key) ?: ""
    }

    fun showPhoto(src: String) {
        App.showPhoto(src, getSource()?.usertocken ?: "")
    }

    /** AnalyzeRule 实现 **/
    val analyzeRule: AnalyzeRule by lazy {
        AnalyzeRule(debugLog = debugLog, source = getSource())
    }

    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRule {
        return analyzeRule.setContent(content, baseUrl)
    }

    fun setBaseUrl(baseUrl: String?): AnalyzeRule {
        return analyzeRule.setBaseUrl(baseUrl)
    }

    fun setRedirectUrl(url: String): URL? {
        return analyzeRule.setRedirectUrl(url)
    }

    @JvmOverloads
    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        return analyzeRule.getString(ruleStr, mContent, isUrl)
    }

    fun getString(ruleStr: String?, unescape: Boolean): String {
        return analyzeRule.getString(ruleStr, unescape)
    }

    @JvmOverloads
    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? {
        return analyzeRule.getStringList(rule, mContent, isUrl)
    }

    fun getElement(ruleStr: String): Any? {
        return analyzeRule.getElement(ruleStr)
    }

    fun getElements(ruleStr: String): List<Any> {
        return analyzeRule.getElements(ruleStr)
    }


    fun searchBook(key: String) {
        //SearchActivity.start(activity, key)
        App.searchBook(key,"",getSource()?.usertocken?:"")
    }

    fun searchBook(key: String,url: String) {
        //SearchActivity.start(activity, key)
        App.searchBook(key,url,getSource()?.usertocken?:"")
    }


    fun addBook(bookUrl: String) {
        App.addBook(bookUrl,getSource()?.usertocken?:"")
        //activity.showDialogFragment(AddToBookshelfDialog(bookUrl))
    }


    @JvmOverloads
    fun open(name: String, url: String? = null, title: String? = null, origin: String? = null) {
        println("open $name $url $title, $origin")
        App.toast("暂不支持open函数",getSource()?.usertocken?:"")
    }


}