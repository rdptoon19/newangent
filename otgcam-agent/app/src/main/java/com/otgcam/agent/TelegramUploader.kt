package com.otgcam.agent

import android.content.Context
import android.media.MediaMetadataRetriever
import com.otgcam.agent.model.AppConfig
import com.otgcam.agent.model.CallSignal
import com.otgcam.agent.model.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Handles all communication with the Telegram Bot API:
 * photo and video uploads, text messages, call signal delivery, and long-polling.
 *
 * All functions are suspend and run on [Dispatchers.IO].
 */
class TelegramUploader(
    private val config: AppConfig,
    private val context: Context
) {

    companion object {
        private const val RETRY_DELAY_MS = 3_000L
        private const val POLL_TIMEOUT_SECONDS = 30
        private const val PREFS_NAME = "telegram_poller_prefs"
        private const val PREF_LAST_UPDATE_ID = "last_update_id"
    }

    private val baseUrl = "https://api.telegram.org/bot${config.botToken}"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ---- Upload helpers --------------------------------------------------

    /**
     * Uploads a JPEG photo file to the configured Telegram chat.
     * Retries once on [java.io.IOException] after a 3-second delay.
     */
    suspend fun uploadPhoto(file: File): UploadResult = withContext(Dispatchers.IO) {
        val caption = "Photo captured at ${timestampFmt.format(Date())}"
        return@withContext attemptWithRetry {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", config.chatId)
                .addFormDataPart(
                    "photo", file.name,
                    file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .addFormDataPart("caption", caption)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/sendPhoto")
                .post(requestBody)
                .build()

            executeUpload(request, file)
        }
    }

    /**
     * Uploads an MP4 video file to the configured Telegram chat.
     * Includes the video duration extracted via [MediaMetadataRetriever].
     * Retries once on [java.io.IOException] after a 3-second delay.
     */
    suspend fun uploadVideo(file: File): UploadResult = withContext(Dispatchers.IO) {
        val caption = "Video captured at ${timestampFmt.format(Date())}"
        val durationSeconds = getVideoDurationSeconds(file)

        return@withContext attemptWithRetry {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", config.chatId)
                .addFormDataPart(
                    "video", file.name,
                    file.asRequestBody("video/mp4".toMediaTypeOrNull())
                )
                .addFormDataPart("caption", caption)
                .addFormDataPart("duration", durationSeconds.toString())
                .build()

            val request = Request.Builder()
                .url("$baseUrl/sendVideo")
                .post(requestBody)
                .build()

            executeUpload(request, file)
        }
    }

    /**
     * Sends a plain text message to the configured Telegram chat.
     * Returns true if the API returned ok=true.
     */
    suspend fun sendTextMessage(text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("chat_id", config.chatId)
                put("text", text)
            }
            val requestBody = json.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$baseUrl/sendMessage")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext false
                return@withContext JSONObject(body).optBoolean("ok", false)
            }
        } catch (e: Exception) {
            return@withContext false
        }
    }

    /**
     * Serialises a [CallSignal] to JSON and sends it as a text message.
     */
    suspend fun sendSignal(signal: CallSignal): Boolean {
        val json = JSONObject().apply {
            put("event", signal.event)
            signal.sdp?.let { put("sdp", it) }
            signal.candidate?.let { put("candidate", it) }
            put("videoEnabled", signal.videoEnabled)
            put("agentId", signal.agentId)
        }
        return sendTextMessage(json.toString())
    }

    /**
     * Long-polls [/getUpdates] in a loop and delivers parsed [CallSignal] objects
     * to [onSignal]. Exits cleanly when the coroutine is cancelled.
     *
     * Updates that contain unknown or mismatched agentIds are silently discarded.
     */
    suspend fun pollForSignals(onSignal: (CallSignal) -> Unit): Unit = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var lastUpdateId = prefs.getLong(PREF_LAST_UPDATE_ID, 0L)

        while (true) {
            kotlinx.coroutines.ensureActive()
            try {
                val url = "$baseUrl/getUpdates" +
                        "?offset=${lastUpdateId + 1}" +
                        "&timeout=$POLL_TIMEOUT_SECONDS" +
                        "&allowed_updates=%5B%22message%22%5D"

                val request = Request.Builder().url(url).get().build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use
                    val root = JSONObject(body)
                    if (!root.optBoolean("ok", false)) return@use

                    val updates = root.getJSONArray("result")
                    for (i in 0 until updates.length()) {
                        val update = updates.getJSONObject(i)
                        val updateId = update.getLong("update_id")
                        if (updateId > lastUpdateId) {
                            lastUpdateId = updateId
                            prefs.edit().putLong(PREF_LAST_UPDATE_ID, lastUpdateId).apply()
                        }
                        val message = update.optJSONObject("message") ?: continue
                        val text = message.optString("text", "")
                        if (text.startsWith("{")) {
                            parseSignal(text)?.let { signal ->
                                if (signal.agentId == config.agentId) {
                                    onSignal(signal)
                                }
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(5_000L)
            }
        }
    }

    // ---- Private helpers -------------------------------------------------

    private fun parseSignal(json: String): CallSignal? {
        return try {
            val obj = JSONObject(json)
            CallSignal(
                event = obj.optString("event", ""),
                sdp = obj.optString("sdp", null),
                candidate = obj.optString("candidate", null),
                videoEnabled = obj.optBoolean("videoEnabled", false),
                agentId = obj.optString("agentId", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun executeUpload(request: okhttp3.Request, file: File): UploadResult {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: return UploadResult.Failure("Empty response", file)
            val root = JSONObject(body)
            return if (root.optBoolean("ok", false)) {
                val result = root.getJSONObject("result")
                // For photos the file_id is in the last photo size element
                val fileId = try {
                    result.getJSONArray("photo")
                        .getJSONObject(result.getJSONArray("photo").length() - 1)
                        .getString("file_id")
                } catch (e: Exception) {
                    try {
                        result.getJSONObject("video").getString("file_id")
                    } catch (e2: Exception) {
                        "unknown"
                    }
                }
                UploadResult.Success(fileId, file.name)
            } else {
                val desc = root.optString("description", "Unknown Telegram error")
                UploadResult.Failure(desc, file)
            }
        }
    }

    /**
     * Executes [block] and retries exactly once on [java.io.IOException]
     * after a [RETRY_DELAY_MS] ms delay.
     */
    private suspend fun attemptWithRetry(block: () -> UploadResult): UploadResult {
        return try {
            block()
        } catch (e: java.io.IOException) {
            delay(RETRY_DELAY_MS)
            try {
                block()
            } catch (e2: java.io.IOException) {
                UploadResult.Failure("IOException after retry: ${e2.message}", File(""))
            }
        }
    }

    private fun getVideoDurationSeconds(file: File): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLong() ?: 0L
            retriever.release()
            durationMs / 1000L
        } catch (e: Exception) {
            0L
        }
    }
}
