package cn.listenly.app.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire types for docs/v3/sync-protocol.md. Field names are the protocol's, not ours.

@Serializable
data class AuthRequest(val username: String, val password: String, val deviceName: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class PasswordRequest(val password: String)

@Serializable
data class AuthResponse(
    val userId: String,
    val deviceId: String,
    val accessToken: String,
    val accessExpiresAt: String,
    val refreshToken: String,
    val refreshExpiresAt: String,
)

@Serializable
data class WireRecord(
    val collection: String,
    val key: String,
    val value: JsonElement? = null,
    val deleted: Boolean = false,
    val clock: Long,
    val device: String = "",
    val seq: Long? = null,
)

@Serializable
data class SyncRequest(val cursor: Long, val changes: List<WireRecord>)

@Serializable
data class Rejected(val index: Int, val reason: String)

@Serializable
data class SyncResponse(
    val accepted: Int = 0,
    val ignored: Int = 0,
    val rejected: List<Rejected> = emptyList(),
    val changes: List<WireRecord> = emptyList(),
    val cursor: Long,
    val hasMore: Boolean = false,
)

@Serializable
data class WireDevice(
    val id: String,
    val name: String,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val current: Boolean = false,
)

@Serializable
data class MeResponse(val userId: String, val username: String, val devices: List<WireDevice> = emptyList())

@Serializable
data class ErrorDetail(val code: String, val message: String = "")

@Serializable
data class ErrorBody(val error: ErrorDetail)

/** HTTP transport. Throws [SyncException] with the protocol error code on failure. */
interface SyncTransport {
    suspend fun register(server: String, body: AuthRequest): AuthResponse
    suspend fun login(server: String, body: AuthRequest): AuthResponse
    suspend fun refresh(server: String, refreshToken: String): AuthResponse
    suspend fun logout(server: String, accessToken: String)
    suspend fun me(server: String, accessToken: String): MeResponse
    suspend fun deleteMe(server: String, accessToken: String, password: String)
    suspend fun sync(server: String, accessToken: String, body: SyncRequest): SyncResponse
}

/** User-facing Chinese text for protocol error codes. */
fun syncErrorMessage(code: String): String = when (code) {
    "invalid_credentials" -> "用户名或密码错误"
    "username_taken" -> "这个用户名已被注册"
    "registration_closed" -> "该服务器已关闭注册"
    "weak_password" -> "密码至少 8 位"
    "bad_username" -> "用户名只能用 3–32 位小写字母、数字和 _ . -"
    "rate_limited" -> "操作太频繁，请稍后再试"
    "invalid_refresh_token", "unauthorized" -> "登录已失效，请重新登录"
    "network" -> "无法连接同步服务器"
    "bad_server" -> "服务器地址必须以 https:// 开头"
    "bad_response" -> "服务器返回了无法识别的内容"
    "other_owner" -> "已登录。本机原有的学习记录属于另一个账号，不会上传到当前账号"
    else -> "同步失败（$code）"
}
