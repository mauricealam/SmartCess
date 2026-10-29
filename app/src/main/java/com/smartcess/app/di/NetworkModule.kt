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
import com.smartcess.app.core.session.BackendAuthApi
import com.smartcess.app.core.session.BackendChatApi
import com.smartcess.app.core.session.BackendSparkAdapter
import com.smartcess.app.core.session.EncryptedSessionStore
import com.smartcess.app.core.session.SessionAuthenticator
import com.smartcess.app.core.session.SessionTokenStore
import com.smartcess.app.core.session.SessionInterceptor
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
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = SmartCessJson.instance

    /**
     * Direct Meta client (developer override only). Adds the Meta key when present;
     * with no key every call throws [OfflineModeException]. No payload logging.
     */
    @Provides
    @Singleton
    fun provideMetaHttp(keys: ApiKeyProvider): OkHttpClient =
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

    /**
     * Keyless client for backend auth/health and the voice socket. Generous
     * timeouts absorb one Render free-tier cold start (~60s); the app's own
     * per-call timeouts still bound UX.
     */
    @Provides
    @Singleton
    @Named("backendPlain")
    fun provideBackendPlainHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .callTimeout(100, TimeUnit.SECONDS)
            .build()

    /** Backend client with session attach + 401 refresh-and-retry. */
    @Provides
    @Singleton
    @Named("backendAuthed")
    fun provideBackendAuthedHttp(
        interceptor: SessionInterceptor,
        authenticator: SessionAuthenticator,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .callTimeout(100, TimeUnit.SECONDS)
            .addInterceptor(interceptor)
            .authenticator(authenticator)
            .build()

    @Provides
    @Singleton
    fun provideMetaRetrofit(http: OkHttpClient, json: Json, config: AiConfig): Retrofit =
        Retrofit.Builder()
            .baseUrl(config.sparkBaseUrl)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    @Named("backendPlain")
    fun provideBackendPlainRetrofit(
        @Named("backendPlain") http: OkHttpClient,
        json: Json,
        config: AiConfig,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(config.backendBaseUrl)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    @Named("backendAuthed")
    fun provideBackendAuthedRetrofit(
        @Named("backendAuthed") http: OkHttpClient,
        json: Json,
        config: AiConfig,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(config.backendBaseUrl)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    @Named("metaDirect")
    fun provideMetaSparkApi(retrofit: Retrofit): SparkApi =
        retrofit.create(SparkApi::class.java)

    @Provides
    @Singleton
    fun provideBackendAuthApi(@Named("backendPlain") retrofit: Retrofit): BackendAuthApi =
        retrofit.create(BackendAuthApi::class.java)

    @Provides
    @Singleton
    fun provideBackendChatApi(@Named("backendAuthed") retrofit: Retrofit): BackendChatApi =
        retrofit.create(BackendChatApi::class.java)

    /**
     * Transport selection. Production always uses the backend adapter; the
     * direct Meta client activates only with a developer-override key.
     */
    @Provides
    @Singleton
    fun provideSparkApi(
        @Named("metaDirect") direct: SparkApi,
        adapter: BackendSparkAdapter,
        keys: ApiKeyProvider,
    ): SparkApi = if (keys.getStoredKey() != null) direct else adapter

    @Provides
    @Singleton
    fun provideSessionStore(impl: EncryptedSessionStore): SessionTokenStore = impl

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
