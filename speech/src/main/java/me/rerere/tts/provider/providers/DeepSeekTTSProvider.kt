package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "DeepSeekTTSProvider"

/**
 * DeepSeek 语音合成。
 *
 * 一次请求最多 10000 个计费字符（汉字算 2 个），超过会返回 400；
 * 音频边合成边返回，这里整体读完再交给播放器，和别的提供商保持一致。
 */
class DeepSeekTTSProvider : TTSProvider<TTSProviderSetting.DeepSeek> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.DeepSeek,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("text", request.text)
            put("voice_id", providerSetting.voiceId)
            put("format", providerSetting.format)
            put("filter_markdown", providerSetting.filterMarkdown)
        }

        Log.i(TAG, "generateSpeech: voice=${providerSetting.voiceId} chars=${request.text.length}")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/tools/tts")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val detail = runCatching { response.body?.string() }.getOrNull().orEmpty()
            Log.e(TAG, "generateSpeech: ${response.code} $detail")
            throw TTSProviderException(
                message = "DeepSeek TTS request failed: ${response.code} $detail",
                statusCode = response.code
            )
        }

        val audioData = response.body.bytes()

        emit(
            AudioChunk(
                // 只有 wav 带容器；pcm 交给播放器按裸数据播
                format = if (providerSetting.format == "wav") AudioFormat.WAV else AudioFormat.PCM,
                data = audioData,
                isLast = true,
                metadata = mapOf(
                    "provider" to "deepseek",
                    "voice" to providerSetting.voiceId,
                    "format" to providerSetting.format,
                )
            )
        )
    }
}
