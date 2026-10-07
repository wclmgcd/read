package web.model

import com.baomidou.mybatisplus.annotation.TableId
import org.dromara.autotable.annotation.AutoTable
import org.dromara.autotable.annotation.ColumnType
import org.dromara.autotable.annotation.Index
import org.dromara.autotable.annotation.PrimaryKey
import web.util.hash.Md5

/**
 * 「我的 → 浏览历史」的一条记录。
 *
 * 和书架的区别：书架是用户**主动收藏**的书；浏览历史是**打开过阅读页**的书
 * ——包括从搜索 / 发现里点进去、读完就走的那些（不在书架里）。
 *
 * 【为什么存 JSON 而不是逐个建列】
 * 前端 `Book` 有二十几个字段且会随客户端演进增删（customCoverUrl、
 * latestChapterTitle、durChapterIndex…）。逐个建列意味着每次客户端加字段都要
 * 动一次服务端；存 Book 的 JSON 原文则前后端解耦，客户端要什么自己解析。
 * 代价是没法按内部字段做 SQL 查询 —— 但浏览历史只需要「按时间倒序取前 N 条」，
 * 用不到。
 */
@AutoTable(value = "browsing_history")
class BrowsingHistory {

    @TableId
    @PrimaryKey
    var id: String? = null

    @Index
    var userid: String? = null

    @Index
    var bookUrl: String? = null

    /** 前端 Book 的 JSON 原文。 */
    @ColumnType(value = "MEDIUMTEXT")
    var bookJson: String? = null

    /** 最近一次打开的时间（毫秒时间戳），列表按它倒序。 */
    var t: Long = 0

    /**
     * id 用 `md5(userid + bookUrl)`：同一本书在表里只留一条，
     * 重读时 `insertOrUpdate` 覆盖而不是越堆越多。
     */
    fun create(userid: String, bookUrl: String, bookJson: String): BrowsingHistory {
        this.userid = userid
        this.bookUrl = bookUrl
        this.bookJson = bookJson
        this.id = Md5(userid + bookUrl)
        this.t = System.currentTimeMillis()
        return this
    }
}
