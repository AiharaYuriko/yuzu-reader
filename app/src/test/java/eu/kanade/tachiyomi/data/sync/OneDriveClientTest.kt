package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

@OptIn(ExperimentalSerializationApi::class)
class OneDriveClientTest {
    @Test
    fun `independent device files merge progress across paginated Graph results`(@TempDir cacheDir: File) =
        runBlocking<Unit> {
            val graph = FakeGraph()
            val drive = client(cacheDir, graph)
            val phone = drive.open(TOKEN, "phone")
            val tablet = drive.open(TOKEN, "tablet")
            phone.hasRemoteFiles shouldBe false
            tablet.hasRemoteFiles shouldBe false

            phone.merge(upload("/phone-title", page = 4, version = 100))
            tablet.merge(upload("/tablet-title", page = 2, version = 100))
            graph.files.values.map { it.name } shouldBe listOf("yuzu-sync-v1-phone", "yuzu-sync-v1-tablet")

            val empty = SyncUpload(Backup(), emptyList())
            val nextTablet = drive.open(TOKEN, "tablet")
            nextTablet.hasRemoteFiles shouldBe true
            nextTablet.merge(empty).backup.backupManga.map { it.url }.toSet() shouldBe
                setOf("/phone-title", "/tablet-title")
            graph.updates shouldBe emptyList<String>()

            nextTablet.merge(upload("/phone-title", page = 9, version = 200))
            graph.updates shouldBe listOf("yuzu-sync-v1-tablet")
            val synced = drive.open(TOKEN, "phone").merge(empty).backup
            synced.backupManga.single { it.url == "/phone-title" }.chapters.single().lastPageRead shouldBe 9L
        }

    @Test
    fun `pagination does not send tokens outside Graph`(@TempDir cacheDir: File) {
        val graph = FakeGraph(nextPageHost = "example.net")
        assertThrows<IOException> {
            runBlocking { client(cacheDir, graph).open(TOKEN, "phone") }
        }.message shouldBe "Invalid Microsoft Graph URL"
    }

    private fun client(cacheDir: File, graph: FakeGraph) = OneDriveClient(
        cacheDir,
        ProtoBuf,
        OkHttpClient.Builder().followRedirects(false).addInterceptor(graph).build(),
    )

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

    private class FakeGraph(private val nextPageHost: String = "graph.microsoft.com") : Interceptor {
        val files = linkedMapOf<String, StoredFile>()
        val updates = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            if (url.host == "download.example.net") {
                request.header("Authorization") shouldBe null
                return response(request, files.getValue(url.pathSegments.last()).content.toResponseBody())
            }
            url.host shouldBe "graph.microsoft.com"
            request.header("Authorization") shouldBe "Bearer $TOKEN"
            val body = when {
                url.encodedPath == "/v1.0/me/drive/special/approot" -> """{"id":"app!folder"}""".toResponseBody()
                url.pathSegments.last() == "children" -> children(request)
                request.method == "PUT" -> save(request)
                url.pathSegments.last() == "content" -> {
                    val id = url.pathSegments[url.pathSize - 2]
                    return response(request, "".toResponseBody()).newBuilder()
                        .code(302)
                        .header("Location", "https://download.example.net/$id")
                        .build()
                }
                else -> error("Unexpected Graph request: $url")
            }
            return response(request, body)
        }

        private fun children(request: Request): ResponseBody {
            val url = request.url
            url.pathSegments[url.pathSize - 2] shouldBe "app!folder"
            val entries = files.map { (id, file) -> """{"id":"$id","name":"${file.name}","file":{}}""" }
            if (url.queryParameter("\$skiptoken") == "next") {
                return """{"value":[${entries.drop(1).joinToString()}]}""".toResponseBody()
            }
            val folder = """{"id":"ignored","name":"yuzu-sync-v1-folder","folder":{}}"""
            val unrelated = """{"id":"ignored","name":"other.txt","file":{}}"""
            val listing = (listOf(folder, unrelated) + entries.take(1)).joinToString()
            val nextLink = "https://$nextPageHost/v1.0/me/drive/items/app!folder/children?%24skiptoken=next"
            return """{"value":[$listing],"@odata.nextLink":"$nextLink"}""".toResponseBody()
        }

        private fun save(request: Request): ResponseBody {
            val path = request.url.pathSegments
            path.last() shouldBe "content"
            val content = Buffer().also { requireNotNull(request.body).writeTo(it) }.readByteString()
            val id = if (path[path.size - 3] == "app!folder:") {
                val name = path[path.size - 2].removeSuffix(":")
                (files.size + 1).toString().also { files[it] = StoredFile(name, content) }
            } else {
                path[path.size - 2].also {
                    val previous = files.getValue(it)
                    updates += previous.name
                    files[it] = previous.copy(content = content)
                }
            }
            return """{"id":"$id"}""".toResponseBody()
        }

        private fun response(request: Request, body: ResponseBody) = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()

        data class StoredFile(val name: String, val content: ByteString)
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}
