package eu.kanade.tachiyomi.data.sync

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okio.buffer
import okio.gzip
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@SingleIn(AppScope::class)
internal class OneDriveClient(
    private val cacheDir: File,
    private val parser: ProtoBuf,
    private val client: OkHttpClient,
) {
    @Inject
    constructor(context: Context, parser: ProtoBuf) : this(
        context.cacheDir,
        parser,
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(90, TimeUnit.SECONDS)
            .build(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun appFolder(token: String): String {
        val url = drive.newBuilder().addPathSegments("special/approot").addQueryParameter("\$select", "id").build()
        return request(url, token).use { json.decodeFromString<ItemId>(it.body.string()).id }
    }

    suspend fun open(token: String, deviceId: String): CloudSyncSession {
        val folderId = appFolder(token)
        val listing = itemUrl(folderId).newBuilder()
            .addPathSegment("children")
            .addQueryParameter("\$select", "id,name,file")
            .build()
        val files = listFiles(token, listing).sortedWith(compareBy({ it.name }, { it.id }))
        val snapshots = files.map { download(token, it.id) }
        val name = "$CLOUD_SYNC_FILE_PREFIX$deviceId"
        val ownFile = files.firstOrNull { it.name == name }
        val upload = (
            ownFile?.let { itemUrl(it.id).newBuilder() }
                ?: itemUrl("$folderId:").newBuilder().addPathSegment("$name:")
            )
            .addPathSegment("content").build()
        return CloudSyncSession(mergeDriveSnapshots(snapshots), files.isNotEmpty()) { snapshot ->
            save(token, upload, snapshot)
        }
    }

    private suspend fun listFiles(token: String, firstPage: HttpUrl): List<Item> {
        val files = mutableListOf<Item>()
        val seen = mutableSetOf<HttpUrl>()
        var url: HttpUrl? = firstPage
        while (url != null) {
            if (!seen.add(url)) throw IOException("OneDrive returned a repeated page")
            val page = request(url, token).use { json.decodeFromString<ItemPage>(it.body.string()) }
            files += page.items.filter { it.file != null && it.name.startsWith(CLOUD_SYNC_FILE_PREFIX) }
            url = page.nextLink?.let {
                it.toHttpUrlOrNull() ?: throw IOException("Invalid OneDrive next page URL")
            }
        }
        return files
    }

    private suspend fun download(token: String, id: String): DriveSnapshot {
        val url = itemUrl(id).newBuilder().addPathSegment("content").build()
        return request(url, token, allowDownloadRedirect = true).use { response ->
            response.body.source().gzip().buffer().use { readDriveSnapshot(it, parser) }
        }
    }

    private suspend fun save(token: String, url: HttpUrl, snapshot: DriveSnapshot) {
        val file = File.createTempFile("yuzu-onedrive-", ".bin", cacheDir)
        try {
            file.sink().gzip().buffer().use { writeDriveSnapshot(it, parser, snapshot) }
            val upload = Request.Builder().url(url).put(file.asRequestBody(binaryType)).build()
            request(upload, token).close()
        } finally {
            file.delete()
        }
    }

    private suspend fun request(
        url: HttpUrl,
        token: String,
        allowDownloadRedirect: Boolean = false,
    ): Response = request(Request.Builder().url(url).build(), token, allowDownloadRedirect)

    private suspend fun request(
        request: Request,
        token: String,
        allowDownloadRedirect: Boolean = false,
    ): Response {
        val url = request.url
        if (url.scheme != drive.scheme || url.host != drive.host || url.port != drive.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || !url.encodedPath.startsWith("/v1.0/")
        ) {
            throw IOException("Invalid Microsoft Graph URL")
        }
        val response = client.newCall(request.newBuilder().header("Authorization", "Bearer $token").build()).await()
        if (response.isSuccessful) return response
        if (allowDownloadRedirect && response.code == 302) {
            val redirect = response.use { it.header("Location")?.let(url::resolve) }
            if (redirect == null || !redirect.isHttps || redirect.username.isNotEmpty() ||
                redirect.password.isNotEmpty()
            ) {
                throw IOException("Invalid OneDrive download URL")
            }
            val content = client.newCall(Request.Builder().url(redirect).build()).await()
            if (content.isSuccessful) return content
            content.use { throw IOException("OneDrive download HTTP ${it.code}") }
        }
        response.use { throw OneDriveHttpException(it.code) }
    }

    @Serializable
    private data class ItemId(val id: String)

    @Serializable
    private data class Item(val id: String, val name: String, val file: JsonObject? = null)

    @Serializable
    private data class ItemPage(
        @SerialName("value") val items: List<Item>,
        @SerialName("@odata.nextLink") val nextLink: String? = null,
    )

    private companion object {
        val drive = "https://graph.microsoft.com/v1.0/me/drive".toHttpUrl()
        val binaryType = "application/octet-stream".toMediaType()

        fun itemUrl(id: String) = drive.newBuilder().addPathSegment("items").addPathSegment(id).build()
    }
}

internal class OneDriveHttpException(val status: Int) : IOException("Microsoft Graph HTTP $status")
