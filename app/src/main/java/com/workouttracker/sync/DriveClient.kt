package com.workouttracker.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal Drive v3 client over the REST API — just the three calls we need
 * (find / download / upload) against the hidden `appDataFolder` space. Using
 * OkHttp directly keeps the Google API client library (and several MB of
 * transitive dependencies) out of the app.
 */
class DriveClient(
    private val http: OkHttpClient = defaultClient(),
) {
    companion object {
        const val BACKUP_FILE_NAME = "workout-tracker-backup.json"
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Drive file id of an existing backup, or null on first ever sync. */
    suspend fun findBackupId(token: String): String? = withContext(Dispatchers.IO) {
        val url = "$FILES?spaces=appDataFolder&fields=files(id)" +
            "&q=" + urlEncode("name = '$BACKUP_FILE_NAME' and trashed = false")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(describe("list", response.code, body))
            val files = JSONObject(body).optJSONArray("files")
            if (files == null || files.length() == 0) null else files.getJSONObject(0).getString("id")
        }
    }

    suspend fun download(token: String, fileId: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$FILES/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(describe("download", response.code, body))
            body
        }
    }

    /** Creates the backup file, or overwrites it when [fileId] is known. Returns the file id. */
    suspend fun upload(token: String, fileId: String?, json: String): String = withContext(Dispatchers.IO) {
        val request = if (fileId == null) {
            val metadata = JSONObject()
                .put("name", BACKUP_FILE_NAME)
                .put("parents", listOf("appDataFolder").let { org.json.JSONArray(it) })
                .toString()
            val multipart = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadata.toRequestBody(JSON))
                .addPart(json.toRequestBody(JSON))
                .build()
            Request.Builder()
                .url("$UPLOAD?uploadType=multipart&fields=id")
                .header("Authorization", "Bearer $token")
                .post(multipart)
                .build()
        } else {
            Request.Builder()
                .url("$UPLOAD/$fileId?uploadType=media&fields=id")
                .header("Authorization", "Bearer $token")
                .patch(json.toRequestBody(JSON))
                .build()
        }
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(describe("upload", response.code, body))
            JSONObject(body).optString("id").ifEmpty { fileId.orEmpty() }
        }
    }

    private fun describe(op: String, code: Int, body: String): String {
        val reason = runCatching {
            JSONObject(body).getJSONObject("error").getString("message")
        }.getOrNull() ?: body.take(160)
        return "Drive $op failed (HTTP $code): $reason"
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
