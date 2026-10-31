package com.ratatoskr.shared.backend

import com.ratatoskr.shared.api.AuthResponse
import com.ratatoskr.shared.api.EntryIn
import com.ratatoskr.shared.api.EntryOut
import com.ratatoskr.shared.api.GenerateRequest
import com.ratatoskr.shared.api.ImportResult
import com.ratatoskr.shared.api.RatatoskrApiClient

/**
 * The vault, as the screens and AppState see it: everything they ask of
 * it in one interface, so they don't depend on the HTTP client directly
 * (and a fake can stand in for tests). RemoteVaultBackend is the server.
 *
 * Failures: RatatoskrApiException for a refusal (the `detail` is the
 * message to show; 401 means the session is gone, or a wrong password
 * when unlocking) and RatatoskrConnectionException when the server can't
 * be reached.
 */
interface VaultBackend {
    suspend fun isInitialized(): Boolean
    suspend fun setup(username: String, masterPassword: String): AuthResponse
    suspend fun unlock(username: String, masterPassword: String): AuthResponse
    suspend fun lock()
    suspend fun changePassword(currentPassword: String, newPassword: String): AuthResponse

    suspend fun listEntries(): List<EntryOut>
    suspend fun createEntry(entry: EntryIn): EntryOut
    suspend fun updateEntry(entryId: String, entry: EntryIn): EntryOut
    suspend fun deleteEntry(entryId: String)

    suspend fun generatePassword(request: GenerateRequest): String
    suspend fun exportCsv(): String
    suspend fun importCsv(fileName: String, csvBytes: ByteArray): ImportResult
}

/** Every call goes to the server. Keeps the client's token in step with
 * the server's responses. */
class RemoteVaultBackend(val api: RatatoskrApiClient) : VaultBackend {
    override suspend fun isInitialized() = api.status().initialized

    override suspend fun setup(username: String, masterPassword: String) =
        api.setup(username, masterPassword).also { api.token = it.token }

    override suspend fun unlock(username: String, masterPassword: String) =
        api.unlock(username, masterPassword).also { api.token = it.token }

    suspend fun acceptInvite(username: String, inviteToken: String, masterPassword: String) =
        api.acceptInvite(username, inviteToken, masterPassword).also { api.token = it.token }

    override suspend fun lock() {
        try {
            api.lock()
        } finally {
            api.token = null
        }
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) =
        api.changePassword(currentPassword, newPassword).also { api.token = it.token }

    override suspend fun listEntries() = api.listEntries()
    override suspend fun createEntry(entry: EntryIn) = api.createEntry(entry)
    override suspend fun updateEntry(entryId: String, entry: EntryIn) = api.updateEntry(entryId, entry)
    override suspend fun deleteEntry(entryId: String) = api.deleteEntry(entryId)

    override suspend fun generatePassword(request: GenerateRequest) = api.generatePassword(request).password
    override suspend fun exportCsv() = api.exportCsv()
    override suspend fun importCsv(fileName: String, csvBytes: ByteArray) = api.importCsv(fileName, csvBytes)
}

