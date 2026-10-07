package web.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import web.model.BrowsingHistory

interface BrowsingHistoryMapper : BaseMapper<BrowsingHistory> {

    @Select("SELECT * FROM browsing_history WHERE userid = #{id} ORDER BY t DESC LIMIT #{limit}")
    fun listByUser(@Param("id") id: String, @Param("limit") limit: Int): List<BrowsingHistory>

    @Delete("DELETE FROM browsing_history WHERE userid = #{id} AND book_url = #{url}")
    fun deleteOne(@Param("id") id: String, @Param("url") url: String): Int

    @Delete("DELETE FROM browsing_history WHERE userid = #{id}")
    fun clearByUser(@Param("id") id: String): Int

    /** 给定时任务用：清掉太久以前的记录，避免表无限增长。 */
    @Delete("DELETE FROM browsing_history WHERE t < #{t}")
    fun deltimeout(@Param("t") t: Long): Int
}
