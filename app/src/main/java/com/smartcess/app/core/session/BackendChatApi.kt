package com.smartcess.app.core.session

import com.smartcess.app.core.ai.SparkApi
import com.smartcess.app.core.ai.SparkRequest
import com.smartcess.app.core.ai.SparkResponse
import retrofit2.http.Body
import retrofit2.http.POST
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Backend chat completions. The backend returns an OpenAI-compatible subset,
 * so [SparkResponse] parsing is unchanged from the direct Meta path.
 */
interface BackendChatApi {
    @POST("api/ai/chat")
    suspend fun chat(@Body request: SparkRequest): SparkResponse
}

/**
 * Routes [SparkApi] callers through the SmartCess backend with zero
 * [com.smartcess.app.core.ai.SparkClient] changes. Selected by DI unless a
 * developer key override is present (see NetworkModule).
 */
@Singleton
class BackendSparkAdapter @Inject constructor(
    @Named("backendAuthed") private val chat: BackendChatApi,
) : SparkApi {
    override suspend fun complete(request: SparkRequest): SparkResponse = chat.chat(request)
}
