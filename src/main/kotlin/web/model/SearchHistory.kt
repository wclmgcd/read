package web.model

import com.baomidou.mybatisplus.annotation.TableId
import org.dromara.autotable.annotation.AutoTable
import org.dromara.autotable.annotation.ColumnType
import org.dromara.autotable.annotation.Index
import org.dromara.autotable.annotation.PrimaryKey
import web.util.hash.Md5

/**
 * 书架右上角「搜索」页里的搜索历史。
 *
 * 原来是客户端 SharedPreferences 里的一个字符串数组，只在本机可见；
 * 落到服务端之后 iOS / 安卓 / 浏览器 / Windows 各端共享同一份。
 */
@AutoTable(value = "search_history")
class SearchHistory {

    @TableId
    @PrimaryKey
    var id: String? = null

    @Index
    var userid: String? = null

    @ColumnType(value = "TEXT")
    var keyword: String? = null

    var t: Long = 0

    /**
     * id 用 `md5(userid + keyword)`：同一个关键词只留一条，
     * 再次搜索时 `insertOrUpdate` 把时间刷新到最新（等于「置顶」）。
     */
    fun create(userid: String, keyword: String): SearchHistory {
        this.userid = userid
        this.keyword = keyword
        this.id = Md5(userid + keyword)
        this.t = System.currentTimeMillis()
        return this
    }
}
