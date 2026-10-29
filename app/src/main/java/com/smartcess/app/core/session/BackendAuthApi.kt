package com.smartcess.app.core.session

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

@Serializable
data class GoogleExchangeRequest(val id_token: String)

@Serializable
data class RefreshRequest(val refresh_token: String)

@Serializable
data class SessionPair(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long,
)

@Serializable
data class SignoutResponse(val ok: Boolean = true)

@Serializable
data class HealthResponse(val status: String = "", val version: String = "")

/** Unaauthenticated backend surface: auth exchange/refresh/signout + health. */
interface BackendAuthApi {
    @POST("api/auth/google")
    suspend fun exchange(@Body body: GoogleExchangeRequest): SessionPair

    @POST("api/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): SessionPair

    @POST("api/auth/signout")
    suspend fun signout(): SignoutResponse

    @GET("health")
    suspend fun health(): HealthResponse
}
