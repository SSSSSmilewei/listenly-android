package cn.listenly.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OkHttp transport for docs/v3/sync-protocol.md. Never logs bodies: they hold passwords and tokens. */
class HttpSyncTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : SyncTransport {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun register(server: String, body: AuthRequest) =
        call(server, "POST", "/v1/auth/register", null, AuthRequest.serializer(), body, AuthResponse.serializer())!!

    override suspend fun login(server: String, body: AuthRequest) =
        call(server, "POST", "/v1/auth/login", null, AuthRequest.serializer(), body, AuthResponse.serializer())!!

    override suspend fun refresh(server: String, refreshToken: String) =
        call(server, "POST", "/v1/auth/refresh", null, RefreshRequest.serializer(), RefreshRequest(refreshToken), AuthResponse.serializer())!!

    override suspend fun logout(server: String, accessToken: String) {
        call<Unit, Unit>(server, "POST", "/v1/auth/logout", accessToken, null, null, null)
    }

    override suspend fun me(server: String, accessToken: String) =
        call<Unit, MeResponse>(server, "GET", "/v1/me", accessToken, null, null, MeResponse.serializer())!!

    override suspend fun deleteMe(server: String, accessToken: String, password: String) {
        call<PasswordRequest, Unit>(server, "DELETE", "/v1/me", accessToken, PasswordRequest.serializer(), PasswordRequest(password), null)
    }

    override suspend fun sync(server: String, accessToken: String, body: SyncRequest) =
        call(server, "POST", "/v1/sync", accessToken, SyncRequest.serializer(), body, SyncResponse.serializer())!!

    private suspend fun <B, R> call(
        server: String,
        method: String,
        path: String,
        token: String?,
        bodySerializer: KSerializer<B>?,
        body: B?,
        responseSerializer: KSerializer<R>?,
    ): R? = withContext(Dispatchers.IO) {
        val requestBody = if (bodySerializer != null && body != null) {
            json.encodeToString(bodySerializer, body).toRequestBody(mediaType)
        } else if (method == "POST") {
            ByteArray(0).toRequestBody(mediaType)
        } else {
            null
        }
        val request = Request.Builder()
            .url(server + path)
            .method(method, requestBody)
            .header("Accept", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw SyncException("network", syncErrorMessage("network"))
        }
        response.use {
            val text = try {
                it.body.string()
            } catch (error: IOException) {
                throw SyncException("network", syncErrorMessage("network"))
            }
            if (!it.isSuccessful) {
                val code = runCatching { json.decodeFromString(ErrorBody.serializer(), text).error.code }
                    .getOrDefault(if (it.code == 401) "unauthorized" else "http_${it.code}")
                throw SyncException(code, syncErrorMessage(code))
            }
            if (responseSerializer == null || text.isBlank()) {
                null
            } else {
                try {
                    json.decodeFromString(responseSerializer, text)
                } catch (error: SerializationException) {
                    throw SyncException("bad_response", syncErrorMessage("bad_response"))
                } catch (error: IllegalArgumentException) {
                    throw SyncException("bad_response", syncErrorMessage("bad_response"))
                }
            }
        }
    }
}
