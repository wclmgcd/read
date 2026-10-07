package web.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import web.model.SearchHistory

interface SearchHistoryMapper : BaseMapper<SearchHistory> {

    @Select("SELECT * FROM search_history WHERE userid = #{id} ORDER BY t DESC LIMIT #{limit}")
    fun listByUser(@Param("id") id: String, @Param("limit") limit: Int): List<SearchHistory>

    @Delete("DELETE FROM search_history WHERE userid = #{id} AND keyword = #{keyword}")
    fun deleteOne(@Param("id") id: String, @Param("keyword") keyword: String): Int

    @Delete("DELETE FROM search_history WHERE userid = #{id}")
    fun clearByUser(@Param("id") id: String): Int

    @Delete("DELETE FROM search_history WHERE t < #{t}")
    fun deltimeout(@Param("t") t: Long): Int
}
