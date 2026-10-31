package com.ratatoskr.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Thrown for any non-2xx response. `detail` is the server's own error
 * message (FastAPI's standard `{"detail": "..."}` error body) when one
 * could be parsed, so the UI can show the exact same wording the web UI
 * would, never a generic "something went wrong". */
class RatatoskrApiException(val statusCode: Int, val detail: String) : Exception(detail)

/** Thrown when the server can't be reached at all (wrong address, server
 * down, no network), distinct from RatatoskrApiException so the UI can
 * tell "your credentials are wrong" apart from "the server didn't answer". */
class RatatoskrConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun HttpStatusCode.isSuccess() = value in 200..299

private val errorJson = Json { ignoreUnknownKeys = true }

/** FastAPI's `detail` is a plain string for HTTPException, but a list of
 * `{loc, msg, ...}` objects for request-validation (422) errors, e.g. a
 * master password under the 8-character minimum. Handle both, rather than
 * only the string form. */
internal fun parseErrorDetail(body: String): String? {
    val detail = (errorJson.parseToJsonElement(body) as? JsonObject)?.get("detail") ?: return null
    return when (detail) {
        is JsonPrimitive -> detail.content
        is JsonArray -> detail.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val msg = obj["msg"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val field = (obj["loc"] as? JsonArray)?.lastOrNull()?.jsonPrimitive?.content
            if (field != null) "$field: $msg" else msg
        }.joinToString("\n").ifEmpty { null }
        else -> null
    }
}

/**
 * One instance per configured server. `baseUrl` is whatever the user typed
 * on the server-connect screen (e.g. "http://192.168.1.50:8000"),
 * normalized to strip a trailing slash. `token` is set after a successful
 * unlock/setup/accept-invite and cleared on lock. It's held in memory here;
 * AppState persists it (encrypted) only where the platform supplies a
 * SessionStore, and the server's idle timeout bounds it either way.
 */
class RatatoskrApiClient(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val apiBase = "$base/api/v1"

    var token: String? = null

    private val http = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true // forward-compatible with server fields a client hasn't caught up to yet
                encodeDefaults = true
            })
        }
    }

    private fun authHeader(builder: HttpRequestBuilder) {
        token?.let { builder.header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /** Wraps every call: turns a non-2xx response into RatatoskrApiException
     * with the server's real detail message, and turns any network-level
     * failure (host unreachable, timeout, DNS failure) into
     * RatatoskrConnectionException instead of leaking a raw platform
     * exception type up into the UI layer. */
    private suspend inline fun <reified T> call(block: () -> HttpResponse): T {
        val response = try {
            block()
        } catch (e: Exception) {
            throw RatatoskrConnectionException(
                "Couldn't reach the server. Check the address and that it's running.", e
            )
        }
        if (!response.status.isSuccess()) {
            val detail = try {
                parseErrorDetail(response.bodyAsText())
            } catch (_: Exception) {
                null
            }
            throw RatatoskrApiException(
                response.status.value,
                detail ?: "Request failed (${response.status.value})"
            )
        }
        @Suppress("UNCHECKED_CAST")
        if (T::class == Unit::class) return Unit as T
        return response.body()
    }

    // ---------- auth ----------

    suspend fun status(): StatusResponse = call { http.get("$apiBase/auth/status") }

    suspend fun me(): MeResponse = call {
        http.get("$apiBase/auth/me") { authHeader(this) }
    }

    suspend fun setup(username: String, masterPassword: String): AuthResponse = call {
        http.post("$apiBase/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(SetupRequest(username, masterPassword))
        }
    }

    suspend fun unlock(username: String, masterPassword: String): AuthResponse = call {
        http.post("$apiBase/auth/unlock") {
            contentType(ContentType.Application.Json)
            setBody(UnlockRequest(username, masterPassword))
        }
    }

    suspend fun acceptInvite(username: String, inviteToken: String, masterPassword: String): AuthResponse = call {
        http.post("$apiBase/auth/accept-invite") {
            contentType(ContentType.Application.Json)
            setBody(AcceptInviteRequest(username, inviteToken, masterPassword))
        }
    }

    suspend fun lock() {
        call<Unit> { http.post("$apiBase/auth/lock") { authHeader(this) } }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String): AuthResponse = call {
        http.post("$apiBase/auth/change-password") {
            authHeader(this)
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest(currentPassword, newPassword))
        }
    }

    suspend fun factoryReset() {
        call<Unit> {
            http.post("$apiBase/auth/factory-reset") {
                contentType(ContentType.Application.Json)
                setBody(FactoryResetRequest(confirm_wipe = true))
            }
        }
    }

    // ---------- users (admin) ----------

    suspend fun listUsers(): List<UserOut> = call {
        http.get("$apiBase/users") { authHeader(this) }
    }

    suspend fun inviteUser(username: String, role: String): InviteResponse = call {
        http.post("$apiBase/users/invite") {
            authHeader(this)
            contentType(ContentType.Application.Json)
            setBody(InviteRequest(username, role))
        }
    }

    suspend fun updateUserRole(userId: String, role: String): UserOut = call {
        http.patch("$apiBase/users/$userId/role") {
            authHeader(this)
            contentType(ContentType.Application.Json)
            setBody(RoleUpdateRequest(role))
        }
    }

    suspend fun deleteUser(userId: String) {
        call<Unit> { http.delete("$apiBase/users/$userId") { authHeader(this) } }
    }

    // ---------- vault ----------

    suspend fun listEntries(): List<EntryOut> = call {
        http.get("$apiBase/vault") { authHeader(this) }
    }

    suspend fun createEntry(entry: EntryIn): EntryOut = call {
        http.post("$apiBase/vault") {
            authHeader(this)
            contentType(ContentType.Application.Json)
            setBody(entry)
        }
    }

    suspend fun updateEntry(entryId: String, entry: EntryIn): EntryOut = call {
        http.put("$apiBase/vault/$entryId") {
            authHeader(this)
            contentType(ContentType.Application.Json)
            setBody(entry)
        }
    }

    suspend fun deleteEntry(entryId: String) {
        call<Unit> { http.delete("$apiBase/vault/$entryId") { authHeader(this) } }
    }

    suspend fun getTotpCode(entryId: String): TotpCodeOut = call {
        http.get("$apiBase/vault/$entryId/totp") { authHeader(this) }
    }

    suspend fun listShares(entryId: String): List<ShareEntryOut> = call {
        http.get("$apiBase/vault/$entryId/shares") { authHeader(this) }
    }

    suspend fun shareEntry(entryId: String, username: String, canWrite: Boolean): ShareEntryOut = call {
        http.post("$apiBase/vault/$entryId/shares") {
            authHeader(this)
            contentType(ContentType.Application.Json)
            setBody(ShareRequest(username, canWrite))
        }
    }

    suspend fun unshareEntry(entryId: String, username: String) {
        call<Unit> { http.delete("$apiBase/vault/$entryId/shares/$username") { authHeader(this) } }
    }

    /** Raw CSV text, not a JSON model. The export endpoint returns
     * text/csv directly, matching the web UI's download behavior. */
    suspend fun exportCsv(): String {
        val response = try {
            http.get("$apiBase/vault/export") { authHeader(this) }
        } catch (e: Exception) {
            throw RatatoskrConnectionException("Couldn't reach the server to export.", e)
        }
        if (!response.status.isSuccess()) {
            throw RatatoskrApiException(response.status.value, "Export failed (${response.status.value})")
        }
        return response.bodyAsText()
    }

    /** Uploads a CSV file's raw bytes as multipart/form-data, matching the
     * server's `UploadFile` field name ("file"). */
    suspend fun importCsv(fileName: String, csvBytes: ByteArray): ImportResult = call {
        http.post("$apiBase/vault/import") {
            authHeader(this)
            setBody(MultiPartFormDataContent(formData {
                append("file", csvBytes, Headers.build {
                    append(HttpHeaders.ContentType, "text/csv")
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            }))
        }
    }

    // ---------- generator ----------

    suspend fun generatePassword(request: GenerateRequest): GenerateResponse = call {
        http.post("$apiBase/generate") {
            authHeader(this)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    fun close() = http.close()
}
