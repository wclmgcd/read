package web.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import web.model.ReplaceRule

interface ReplaceRuleMapper: BaseMapper<ReplaceRule> {

    @Select("SELECT * FROM replace_rule WHERE id = #{id} and userid = #{userid} LIMIT 1")
    fun getrule(@Param("id") id: String,@Param("userid") userid: String): ReplaceRule?


    @Select("SELECT * FROM replace_rule  WHERE userid = #{userid} and name = #{name}")
    fun getrulebyname(@Param("userid") userid: String,@Param("name") name: String): List<ReplaceRule>

    // 【`exclude_scope is null or` 这一句不能省】
    //
    // 原来的 WHERE 结尾是 `and exclude_scope not like #{name}`。SQL 的三值逻辑下
    // `NULL NOT LIKE '%书名%'` 得到的是 **NULL 而不是 TRUE**，整行被滤掉 ——
    // 规则建得出来、在「替换净化」列表里也看得见，但**永远不会生效**，
    // 表现为「过滤/净化点了没反应」。
    //
    // `exclude_scope` 是可空列，只要写入方没显式发空串（老版本客户端、外部导入、
    // 直接改库）库里就是 NULL，于是这类规则会集体失效。语义上 NULL 就是
    // 「没有排除范围」，本来就该命中，所以补一句 `is null or` 兜底。
    //
    // 客户端侧另有一半：新写入的规则要显式发 `excludeScope: ""`
    // （见 Qread-flutter `lib/models/replace_rule.dart` 的 `toServerJson()`）。
    // 两边都做，才能既修好存量规则、又保证新规则干净。
    @Select(
        "SELECT * FROM replace_rule  WHERE is_enabled= true and userid = #{userid} " +
            "and (scope = #{url} or scope like #{name} or scope = '' or scope is null) " +
            "and (exclude_scope is null or exclude_scope not like #{name})"
    )
    fun getrulebybookname(@Param("userid") userid: String,@Param("name") name: String,@Param("url") url: String): List<ReplaceRule>

    @Select("SELECT * FROM replace_rule  WHERE userid = #{userid} order by ruleorder,name asc")
    fun getallrule(@Param("userid") userid: String): List<ReplaceRule>

    @Update("UPDATE replace_rule set ruleorder= #{ruleorder} WHERE id = #{id}")
    fun changeorder(@Param("id") id: String, @Param("ruleorder") ruleorder: Int):Int

    @Update("UPDATE replace_rule set is_enabled= #{enabled} WHERE id = #{id}")
    fun changeEnabled(@Param("id") id: String, @Param("enabled") enabled: Boolean):Int

    @Delete("Delete  FROM replace_rule WHERE userid = #{id}")
    fun delUserrule(@Param("id") id: String): Int
}