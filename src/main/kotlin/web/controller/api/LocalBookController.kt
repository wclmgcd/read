package web.controller.api

import book.appCtx
import book.model.Book
import book.util.FileUtils
import book.webBook.localBook.LocalBook
import book.webBook.localBook.MobiFile
import org.noear.solon.annotation.Controller
import org.noear.solon.annotation.Inject
import org.noear.solon.annotation.Mapping
import org.noear.solon.core.handle.UploadedFile
import org.noear.solon.core.util.DataThrowable
import org.noear.solon.web.cors.annotation.CrossOrigin
import web.mapper.BooklistMapper
import web.model.Booklist
import web.response.*
import web.util.cache.getlocalpath
import web.util.hash.Md5
import java.io.File
import java.net.URLDecoder
import kotlin.concurrent.thread

@Controller
@Mapping(routepath)
@CrossOrigin(origins = "*")
open class LocalBookController:BaseController() {

    private val imagesDir = FileUtils.createFolderIfNotExist(appCtx.externalFiles, "assets","images")

    @Inject
    lateinit var booklistMapper: BooklistMapper


    @Mapping("/importBookPreview")
    open fun importBookPreview(accessToken:String?, file: UploadedFile?)=run{
        if(file == null) throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        if (file.isEmpty) {
            throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        }
        val user=getuserbytocken(accessToken)
        if(user.AllowUpTxt != true) {
            throw DataThrowable().data(JsonResponse(false,NOT_ALLOW_TXT))
        }
        if(!LocalBook.isSupportedFileName(file.name)){
            throw DataThrowable().data(JsonResponse(false,NOT_TXT))
        }
        var f1=file.name
        kotlin.runCatching {
            f1= URLDecoder.decode( f1, "UTF-8" )
        }
        val unifiedPath = f1.replace("\\", "/")
        f1= unifiedPath.substringAfterLast('/')
        val  uploadDir = getlocalpath(user.username?:"")
        val ufile= File(uploadDir)
        if(!ufile.exists()){ufile.mkdirs()}
        val localpath= "$uploadDir/$f1"
        val  uploadedFile =  File(localpath)
        uploadedFile.writeBytes(file.contentAsBytes)
        val book = Book.initLocalBook(localpath, localpath, "")
        // 解析失败（DRM / HUFF-CDIC / 结构损坏 / 目录为空）时删掉刚写入的文件，
        // 否则会在用户目录里留下一个永远打不开的残留。
        val chapters = try {
            LocalBook.getChapterList(book)
        } catch (e: MobiFile.UnsupportedMobiException) {
            uploadedFile.delete()
            throw DataThrowable().data(JsonResponse(false, e.message ?: NOT_TXT))
        } catch (e: Exception) {
            uploadedFile.delete()
            throw DataThrowable().data(JsonResponse(false, "解析失败：" + (e.message ?: "文件结构异常")))
        }
        val booklist= Booklist().create().bookto(book)
        booklistMapper.getbook(user.id!!,book.bookUrl)?.let {
           // booklist.durChapterTime=it.durChapterTime
            booklist.durChapterTitle=it.durChapterTitle
            booklist.durChapterPos=it.durChapterPos
            booklist.durChapterIndex=it.durChapterIndex
            booklistMapper.deleteById(it.id)
        }
        booklist.originName="本地"
        booklist.userid=user.id
        booklist.lastCheckTime=System.currentTimeMillis()
        booklist.lastCheckCount=chapters.size
        booklist.totalChapterNum=chapters.size
        booklist.latestChapterTitle=chapters[chapters.size-1].title
        booklist.latestChapterTime=System.currentTimeMillis()
        booklistMapper.insert(booklist)
        web.notification.Book.sendNotification(user)
        thread {
            ReadController.removeChapterListbycache(book.bookUrl,user.id!!)
            ReadController.removeallBookContentbycache(book.bookUrl,user.id!!)
            ReadController.setChapterListbycache(book.bookUrl,chapters,user.id!!)
        }
        return@run JsonResponse(true,SUCCESS).Data(mapOf("books" to book,"chapters" to chapters))
    }

    @Mapping("/uploadimage")
    open fun  uploadimage(accessToken:String?, file: UploadedFile?)= run{
        if(file == null) throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        if (file.isEmpty) {
            throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        }
        val fb=file.contentAsBytes;
        val f1="${Md5(fb)}.png"
        val valueFile = FileUtils.createFileIfNotExist(imagesDir,f1)
        valueFile.writeBytes(fb)
        JsonResponse(true).Data("http//assets/images/"+f1)
    }

}