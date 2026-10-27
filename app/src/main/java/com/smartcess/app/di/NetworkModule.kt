package com.smartcess.app.di

import com.smartcess.app.core.ai.AiEvalLogger
import com.smartcess.app.core.ai.AndroidSpeaker
import com.smartcess.app.core.ai.InMemoryAiEvalLogger
import com.smartcess.app.core.ai.MlKitOcrEngine
import com.smartcess.app.core.ai.OcrEngine
import com.smartcess.app.core.ai.OfflineModeException
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.ai.SparkApi
import com.smartcess.app.core.config.AiConfig
import com.smartcess.app.core.config.ApiKeyProvider
import com.smartcess.app.core.serialization.SmartCessJson
import com.smartcess.app.core.voice.MuseVoiceTranscriber
import com.smartcess.app.core.voice.MuseVoiceTranscriberImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = SmartCessJson.instance

    /**
     * HTTPS client. Adds the Meta key when present; with no key every call throws
     * [OfflineModeException] so features degrade to ML Kit + TTS. No payload logging.
     */
    @Provides
    @Singleton
    fun provideOkHttp(keys: ApiKeyProvider, config: AiConfig): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(50, TimeUnit.SECONDS)
            .callTimeout(50, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val key = keys.getMetaApiKey() ?: throw OfflineModeException()
                chain.proceed(
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $key")
                        .build(),
                )
            }
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(http: OkHttpClient, json: Json, config: AiConfig): Retrofit =
        Retrofit.Builder()
            .baseUrl(config.sparkBaseUrl)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideSparkApi(retrofit: Retrofit): SparkApi = retrofit.create(SparkApi::class.java)

    @Provides
    @Singleton
    fun provideTranscriber(impl: MuseVoiceTranscriberImpl): MuseVoiceTranscriber = impl

    @Provides
    @Singleton
    fun provideOcrEngine(impl: MlKitOcrEngine): OcrEngine = impl

    @Provides
    @Singleton
    fun provideSpeaker(impl: AndroidSpeaker): Speaker = impl

    @Provides
    @Singleton
    fun provideEvalLogger(impl: InMemoryAiEvalLogger): AiEvalLogger = impl
}
