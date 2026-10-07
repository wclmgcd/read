package web.controller.api

import book.util.GSON
import org.noear.solon.annotation.Body
import org.noear.solon.annotation.Controller
import org.noear.solon.annotation.Inject
import org.noear.solon.annotation.Mapping
import org.noear.solon.core.util.DataThrowable
import org.noear.solon.web.cors.annotation.CrossOrigin
import web.mapper.BrowsingHistoryMapper
import web.mapper.SearchHistoryMapper
import web.model.BrowsingHistory
import web.model.SearchHistory
import web.response.*

/**
 * 浏览历史 / 搜索历史的跨端同步。
 *
 * 【为什么单独开一个 Controller】
 * 这两份数据原来都只存在客户端本地（SharedPreferences），换设备就没了。
 * 落到服务端之后，iOS / 安卓 / 浏览器 / Windows 各端读的是同一份。
 *
 * 【同步模型：服务端为准】
 * 不做复杂的增量合并 —— 每个写操作（新增 / 删除 / 清空）都直接打到服务端，
 * 读的时候从服务端拉。同一本书 / 同一个关键词在表里只有一条（id 是
 * md5(userid+key)），所以「重复添加」自然退化成「更新时间戳 = 置顶」。
 *
 * 客户端仍保留一份本地缓存做离线兜底（见 BrowsingHistoryService）。
 */
@Controller
@Mapping(routepath)
@CrossOrigin(origins = "*")
open class HistoryController : BaseController() {

    @Inject
    lateinit var browsingHistoryMapper: BrowsingHistoryMapper

    @Inject
    lateinit var searchHistoryMapper: SearchHistoryMapper

    /** 浏览历史最多返回多少条。前端本地也按 30 条裁剪，这里给足冗余。 */
    private val maxBrowsing = 100

    /** 搜索历史最多返回多少条（和前端搜索页显示的 20 条对齐）。 */
    private val maxSearch = 20

    // ------------------------------------------------------------ 浏览历史

    @Mapping("/getBrowsingHistory")
    fun getBrowsingHistory(accessToken: String?) = run {
        val user = getuserbytocken(accessToken)
        JsonResponse(true).Data(
            browsingHistoryMapper.listByUser(user.id!!, maxBrowsing)
                .mapNotNull { it.bookJson?.takeIf { json -> json.isNotBlank() } }
        )
    }

    /**
     * 新增 / 更新一条浏览历史。
     *
     * body 直接就是前端 Book 的 JSON 原文 —— 服务端只从里面取 `bookUrl` 当键，
     * 其余原样存着，避免两边字段不同步。
     */
    @Mapping("/addBrowsingHistory")
    fun addBrowsingHistory(accessToken: String?, @Body content: String) = run {
        val user = getuserbytocken(accessToken)
        if (content.isBlank()) {
            throw DataThrowable().data(JsonResponse(false, "内容为空"))
        }
        val bookUrl = runCatching {
            val map = GSON.fromJson(content, Map::class.java)
            map?.get("bookUrl")?.toString()
        }.getOrNull()
        if (bookUrl.isNullOrBlank()) {
            throw DataThrowable().data(JsonResponse(false, "bookUrl 为空"))
        }
        browsingHistoryMapper.insertOrUpdate(
            BrowsingHistory().create(user.id!!, bookUrl, content)
        )
        JsonResponse(true)
    }

    @Mapping("/delBrowsingHistory")
    fun delBrowsingHistory(accessToken: String?, bookUrl: String?) = run {
        val user = getuserbytocken(accessToken)
        if (!bookUrl.isNullOrBlank()) {
            browsingHistoryMapper.deleteOne(user.id!!, bookUrl)
        }
        JsonResponse(true)
    }

    @Mapping("/clearBrowsingHistory")
    fun clearBrowsingHistory(accessToken: String?) = run {
        val user = getuserbytocken(accessToken)
        browsingHistoryMapper.clearByUser(user.id!!)
        JsonResponse(true)
    }

    /**
     * 批量上传本地历史 —— 给「老版本客户端首次升级」用。
     *
     * 本地攒了几十条历史但服务端还是空的，逐条 add 也行，但客户端要发几十个
     * 请求；这里一次收完。同 bookUrl 只保留最后一条（客户端传来的顺序即
     * 「由新到旧」，所以先到的那条时间戳更新）。
     */
    @Mapping("/pushBrowsingHistory")
    fun pushBrowsingHistory(accessToken: String?, @Body content: String) = run {
        val user = getuserbytocken(accessToken)
        if (content.isBlank()) return@run JsonResponse(true)
        val list = runCatching {
            GSON.fromJson(content, Array<Map<String, Any>>::class.java)?.toList() ?: emptyList()
        }.getOrElse { emptyList() }
        val now = System.currentTimeMillis()
        list.forEachIndexed { index, item ->
            val bookUrl = item["bookUrl"]?.toString()
            if (!bookUrl.isNullOrBlank()) {
                val entity = BrowsingHistory().create(user.id!!, bookUrl, GSON.toJson(item))
                // 列表按「由新到旧」传上来，序号越小越新 —— 时间戳照此递减，
                // 保证入库后的倒序和客户端看到的一致。
                entity.t = now - index
                browsingHistoryMapper.insertOrUpdate(entity)
            }
        }
        JsonResponse(true).Data(list.size)
    }

    // ------------------------------------------------------------ 搜索历史

    @Mapping("/getSearchHistory")
    fun getSearchHistory(accessToken: String?) = run {
        val user = getuserbytocken(accessToken)
        JsonResponse(true).Data(
            searchHistoryMapper.listByUser(user.id!!, maxSearch)
                .mapNotNull { it.keyword?.takeIf { k -> k.isNotBlank() } }
        )
    }

    @Mapping("/addSearchHistory")
    fun addSearchHistory(accessToken: String?, keyword: String?) = run {
        val user = getuserbytocken(accessToken)
        if (!keyword.isNullOrBlank()) {
            searchHistoryMapper.insertOrUpdate(SearchHistory().create(user.id!!, keyword))
        }
        JsonResponse(true)
    }

    @Mapping("/delSearchHistory")
    fun delSearchHistory(accessToken: String?, keyword: String?) = run {
        val user = getuserbytocken(accessToken)
        if (!keyword.isNullOrBlank()) {
            searchHistoryMapper.deleteOne(user.id!!, keyword)
        }
        JsonResponse(true)
    }

    @Mapping("/clearSearchHistory")
    fun clearSearchHistory(accessToken: String?) = run {
        val user = getuserbytocken(accessToken)
        searchHistoryMapper.clearByUser(user.id!!)
        JsonResponse(true)
    }
}
