package web.cron

import book.appCtx
import book.util.FileUtils
import kotlinx.coroutines.runBlocking
import org.noear.solon.annotation.Inject
import org.noear.solon.scheduling.annotation.Scheduled
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import web.util.mapper.mapper
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

@Scheduled(fixedRate = 1000 * 60*60*24)
class CleanBookCache : Runnable {
    @Inject(value = "\${admin.cron:true}", autoRefreshed=true)
    var cron:Boolean=true


    companion object {
        private var isdo = false
        private val cachefile = FileUtils.createFolderIfNotExist(appCtx.externalFiles, "cache","book")
        private val cache2file = FileUtils.createFolderIfNotExist(appCtx.externalFiles, "ruleData","book")
        val logger: Logger = LoggerFactory.getLogger(CleanBookCache::class.java)
    }
    override fun run() = runBlocking{
        if(!cron){
            return@runBlocking
        }
        if (isdo) {
            return@runBlocking
        }
       isdo =true
        kotlin.runCatching {
            cachefile.walk().maxDepth(1).forEach {
                if(it.isDirectory && it.name != "book"){
                    checkcahce(it)
                }
            }
        }

        kotlin.runCatching {
            cache2file.walk().maxDepth(1).forEach {
                if(it.isDirectory && it.name != "book"){
                    checkcahce2(it)
                }
            }
        }
        isdo =false
    }

    fun checkcahce2(file: File) {
        kotlin.runCatching {
            file.walk().maxDepth(1).forEach {
                if(it.isDirectory && it.name != file.name){
                    kotlin.runCatching {
                        val attributes = Files.readAttributes(it.toPath(), BasicFileAttributes::class.java)
                        val creationTime = attributes.creationTime()
                        val instant = creationTime.toInstant()
                        val time=(System.currentTimeMillis()-instant.toEpochMilli())/(60*60*24*1000)
                        if(time > 3){
                            checkbook2(file.name,it)
                        }
                    }
                }
            }
        }
    }

    fun checkbook2(userid:String,file: File) {
        val bookUrlFile = File(FileUtils.getPath(file, "bookUrl.txt"))
        if (bookUrlFile.exists()) {
            runCatching {
                val book=mapper.get().booklistMapper.getbook(userid,bookUrlFile.readText())
                if(book == null) {
                    val book=mapper.get().sgreadMapper.getbook(userid,bookUrlFile.readText())
                    if (book == null) {
                        logger.info("bookcache: Book not found: ${bookUrlFile.readText()} clean")
                        FileUtils.delete(file, true)
                    }
                }
            }
        }else{
            FileUtils.delete(file, true)
        }
    }

    fun checkcahce(file: File) {
        kotlin.runCatching {
            file.walk().maxDepth(1).forEach {
                if(it.isDirectory && it.name != file.name){
                    kotlin.runCatching {
                        val attributes = Files.readAttributes(it.toPath(), BasicFileAttributes::class.java)
                        val creationTime = attributes.creationTime()
                        val instant = creationTime.toInstant()
                        val time=(System.currentTimeMillis()-instant.toEpochMilli())/(60*60*24*1000)
                        if(time > 3){
                            checkbook(file.name,it)
                        }
                    }
                }
            }
        }
    }

    fun checkbook(userid:String,file: File) {
        val bookUrlFile = File(FileUtils.getPath(file, "bookUrl.txt"))
        if (bookUrlFile.exists()) {
            runCatching {
                val book=mapper.get().booklistMapper.getbook(userid,bookUrlFile.readText())
                if(book == null) {
                    logger.info("bookcache: Book not found: ${bookUrlFile.readText()} clean")
                    FileUtils.delete(file, true)
                }else{
                    val chapterFile = File(FileUtils.getPath(file, "chapter.txt"))
                    checkchapter(chapterFile)
                    val contentFile = File(FileUtils.getPath(file, "content"))
                    checkcontent(contentFile)
                }
            }
        }else{
            FileUtils.delete(file, true)
        }
    }

    fun  checkchapter(file: File){
        if(!file.exists())return
        runCatching {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            // 【必须用 lastModifiedTime，不能用 creationTime】
            // chapter.txt 是**覆盖写**（BigDataHelp.putChapterList 里 writeText），
            // 创建时间永远停在第一次落盘那一刻，之后每次刷新目录都不会变。
            // 用 creationTime 判「超过 1 天就删」，结果就是：只要文件存在满 1 天，
            // 无论期间被更新过多少次，每天都会被这个定时任务删掉 ——
            // 表现正是「每次打开书都要重新抓目录，很久才出内容」。
            val modified = attributes.lastModifiedTime()
            val instant = modified.toInstant()
            val time=(System.currentTimeMillis()-instant.toEpochMilli())/(60*60*24*1000)
            if(time > 1){
                logger.info("bookcache: chapterFile ${file.path} is timeout clean")
                file.delete()
            }
        }
    }

    fun  checkcontent(file: File){
        if(!file.exists()) return
        if(!file.isDirectory) {
            file.delete()
            return
        }
        file.walk().maxDepth(1).forEach {
            if(it.isFile){
                val attributes = Files.readAttributes(it.toPath(), BasicFileAttributes::class.java)
                // 同上：正文文件也是覆盖写，必须看修改时间而不是创建时间。
                val modified = attributes.lastModifiedTime()
                val instant = modified.toInstant()
                val time=(System.currentTimeMillis()-instant.toEpochMilli())/(60*60*24*1000)
                if(time > 30){
                    logger.info("bookcache: content ${it.path} is timeout clean")
                    it.delete()
                }
            }
        }
    }
}