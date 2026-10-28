package com.ultron.ai

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object UltronVoicePlayer {
    private val client = OkHttpClient()
    private var mediaPlayer: MediaPlayer? = null

    /**
     * Plays text using your custom Ultron Voice ID via ElevenLabs API.
     * Streams the resulting .mp3 directly to Android's MediaPlayer.
     */
    suspend fun speakWithVoiceId(
        context: Context,
        text: String,
        voiceId: String,
        apiKey: String,
        onFinished: (() -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (voiceId.isBlank() || apiKey.isBlank()) return@withContext false

        try {
            val jsonPayload = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.45)        // Cold robotic consistency
                    put("similarity_boost", 0.85) // Matches the exact Ultron timbre
                    put("style", 0.35)
                })
            }

            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                .addHeader("xi-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false

                val audioBytes = response.body?.bytes() ?: return@withContext false
                val tempAudioFile = File.createTempFile("ultron_voice_", ".mp3", context.cacheDir)
                FileOutputStream(tempAudioFile).use { fos ->
                    fos.write(audioBytes)
                }

                withContext(Dispatchers.Main) {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(tempAudioFile.absolutePath)
                        prepare()
                        setOnCompletionListener {
                            tempAudioFile.delete()
                            onFinished?.invoke()
                        }
                        start()
                    }
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
