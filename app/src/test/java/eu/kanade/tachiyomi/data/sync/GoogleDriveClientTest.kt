package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
class GoogleDriveClientTest {
    @Test
    fun `concurrent first syncs create independent files and relay later changes`(@TempDir cacheDir: File) =
        runBlocking<Unit> {
            val drive = FakeDrive()
            val client = GoogleDriveClient(cacheDir, ProtoBuf, OkHttpClient.Builder().addInterceptor(drive).build())
            val token = "test-token"
            val phone = client.open(token, "phone")
            val tablet = client.open(token, "tablet")
            phone.hasRemoteFiles shouldBe false
            tablet.hasRemoteFiles shouldBe false

            phone.merge(upload("/phone-title", page = 4, version = 100))
            tablet.merge(upload("/tablet-title", page = 2, version = 100))
            drive.files.size shouldBe 2

            val empty = SyncUpload(Backup(), emptyList())
            val tabletAfter = client.open(token, "tablet")
            val shared = tabletAfter.merge(empty).backup
            shared.backupManga.map { it.url }.toSet() shouldBe setOf("/phone-title", "/tablet-title")

            tabletAfter.merge(upload("/phone-title", page = 9, version = 200))
            drive.patchedNames shouldBe listOf("yuzu-sync-v1-tablet")
            val phoneAfter = client.open(token, "phone").merge(empty).backup
            phoneAfter.backupManga.single { it.url == "/phone-title" }.chapters.single().lastPageRead shouldBe 9L
        }

    private fun upload(url: String, page: Long, version: Long) = SyncUpload(
        Backup(
            listOf(
                BackupManga(
                    source = 123,
                    url = url,
                    version = version,
                    chapters = listOf(BackupChapter(url = "/chapter", lastPageRead = page, version = version)),
                ),
            ),
        ),
        emptyList(),
    )

    private class FakeDrive : Interceptor {
        val files = linkedMapOf<String, StoredFile>()
        val patchedNames = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            request.header("Authorization") shouldBe "Bearer test-token"
            val path = request.url.encodedPath
            val body = when {
                path == "/drive/v3/files" -> listFiles(request)
                path.startsWith("/drive/v3/files/") -> files.getValue(request.url.pathSegments.last())
                    .content.toResponseBody()
                request.method == "POST" -> create(request)
                request.method == "PATCH" -> update(request)
                else -> error("Unexpected Drive request: $path")
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }

        private fun listFiles(request: Request): ResponseBody {
            request.url.queryParameter("spaces") shouldBe "appDataFolder"
            val entries = files.map { (id, file) -> """{"id":"$id","name":"${file.name}"}""" }
            return """{"files":[${entries.joinToString(",")}]}""".toResponseBody()
        }

        private fun create(request: Request): ResponseBody {
            request.url.encodedPath shouldBe "/upload/drive/v3/files"
            request.url.queryParameter("uploadType") shouldBe "multipart"
            val body = request.body as MultipartBody
            body.contentType().subtype shouldBe "related"
            val metadata = Buffer().also { body.parts[0].body.writeTo(it) }.readUtf8()
            val fields = Json.parseToJsonElement(metadata).jsonObject
            fields.getValue("parents").toString() shouldBe """["appDataFolder"]"""
            val name = fields.getValue("name").jsonPrimitive.content
            val content = Buffer().also { body.parts[1].body.writeTo(it) }.readByteString()
            val id = (files.size + 1).toString()
            files[id] = StoredFile(name, content)
            return """{"id":"$id"}""".toResponseBody()
        }

        private fun update(request: Request): ResponseBody {
            request.url.queryParameter("uploadType") shouldBe "media"
            val id = request.url.pathSegments.last()
            val previous = files.getValue(id)
            patchedNames += previous.name
            val content = Buffer().also { requireNotNull(request.body).writeTo(it) }.readByteString()
            files[id] = previous.copy(content = content)
            return """{"id":"$id"}""".toResponseBody()
        }

        data class StoredFile(val name: String, val content: ByteString)
    }
}
