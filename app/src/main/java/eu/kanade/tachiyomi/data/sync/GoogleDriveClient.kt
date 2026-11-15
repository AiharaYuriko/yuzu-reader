package eu.kanade.tachiyomi.data.sync

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.gzip
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@SingleIn(AppScope::class)
class GoogleDriveClient internal constructor(
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

    internal suspend fun account(accessToken: String): DriveAccount {
        val url = api.newBuilder().addPathSegment("about")
            .addQueryParameter("fields", "user(emailAddress,permissionId)")
            .build()
        return send(Request.Builder().url(url), accessToken).use { response ->
            val user = json.decodeFromString<DriveAbout>(response.body.string()).user
            DriveAccount(user.emailAddress, user.permissionId)
        }
    }

    internal suspend fun open(accessToken: String, deviceId: String): CloudSyncSession {
        val files = listFiles(accessToken).sortedWith(compareBy({ it.name }, { it.id }))
        val snapshots = files.map { file ->
            val url = api.newBuilder().addPathSegment("files").addPathSegment(file.id)
                .addQueryParameter("alt", "media").build()
            send(Request.Builder().url(url), accessToken).use { response ->
                response.body.source().gzip().buffer().use { readDriveSnapshot(it, parser) }
            }
        }
        val name = "$CLOUD_SYNC_FILE_PREFIX$deviceId"
        var id = files.firstOrNull { it.name == name }?.id
        return CloudSyncSession(mergeDriveSnapshots(snapshots), files.isNotEmpty()) { snapshot ->
            id = saveFile(accessToken, name, id, snapshot)
        }
    }

    private suspend fun listFiles(accessToken: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = api.newBuilder().addPathSegment("files")
                .addQueryParameter("spaces", APP_FOLDER)
                .addQueryParameter("q", "name contains '$CLOUD_SYNC_FILE_PREFIX'")
                .addQueryParameter("fields", "nextPageToken,incompleteSearch,files(id,name)")
                .addQueryParameter("pageSize", "100")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = send(Request.Builder().url(url), accessToken).use { response ->
                json.decodeFromString<DriveFiles>(response.body.string())
            }
            if (page.incompleteSearch) throw IOException("Google Drive returned an incomplete sync listing")
            files += page.files.filter { it.name.startsWith(CLOUD_SYNC_FILE_PREFIX) }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return files
    }

    private suspend fun saveFile(accessToken: String, name: String, id: String?, snapshot: DriveSnapshot): String {
        val file = File.createTempFile("yuzu-drive-", ".bin", cacheDir)
        try {
            file.sink().gzip().buffer().use { writeDriveSnapshot(it, parser, snapshot) }
            val media = file.asRequestBody(binaryType)
            val url = uploads.newBuilder().addPathSegment("files").addQueryParameter("fields", "id")
            val request = if (id == null) {
                val metadata = json.encodeToString(DriveFileMetadata(name, listOf(APP_FOLDER)))
                val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                    .addPart(metadata.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .addPart(media)
                    .build()
                Request.Builder().url(url.addQueryParameter("uploadType", "multipart").build()).post(body)
            } else {
                Request.Builder()
                    .url(url.addPathSegment(id).addQueryParameter("uploadType", "media").build())
                    .patch(media)
            }
            return send(request, accessToken).use { response ->
                json.decodeFromString<UploadedFile>(response.body.string()).id
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun send(request: Request.Builder, accessToken: String): Response {
        val response = client.newCall(request.header("Authorization", "Bearer $accessToken").build()).await()
        if (!response.isSuccessful) {
            val error = DriveHttpException(response.code)
            response.close()
            throw error
        }
        return response
    }

    @Serializable
    private data class DriveFile(val id: String, val name: String)

    @Serializable
    private data class DriveFiles(
        val files: List<DriveFile> = emptyList(),
        val nextPageToken: String? = null,
        val incompleteSearch: Boolean = false,
    )

    @Serializable
    private data class DriveFileMetadata(val name: String, val parents: List<String>)

    @Serializable
    private data class UploadedFile(val id: String)

    @Serializable
    private data class DriveAbout(val user: DriveUser)

    @Serializable
    private data class DriveUser(val emailAddress: String, val permissionId: String)

    private companion object {
        const val APP_FOLDER = "appDataFolder"
        val api = "https://www.googleapis.com/drive/v3".toHttpUrl()
        val uploads = "https://www.googleapis.com/upload/drive/v3".toHttpUrl()
        val binaryType = "application/octet-stream".toMediaType()
    }
}

internal class DriveHttpException(val status: Int) : IOException("Google Drive HTTP $status")
