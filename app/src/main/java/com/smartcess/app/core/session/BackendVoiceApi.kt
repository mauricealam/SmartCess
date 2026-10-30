package com.smartcess.app.core.session

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

@Serializable
data class TranscribeResponse(
    val transcript: String = "",
    val audio_duration_ms: Int = 0,
    val turns: Int = 0,
)

/** Push-to-talk upload. Real WAV bytes in, transcript out; key stays server-side. */
interface BackendVoiceApi {
    @Multipart
    @POST("api/voice/transcribe")
    suspend fun transcribe(@Part audio: MultipartBody.Part): TranscribeResponse
}
