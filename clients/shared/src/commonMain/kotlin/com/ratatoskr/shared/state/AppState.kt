package com.ratatoskr.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ratatoskr.shared.api.AuthResponse
import com.ratatoskr.shared.api.EntryIn
import com.ratatoskr.shared.api.EntryOut
import com.ratatoskr.shared.api.GenerateRequest
import com.ratatoskr.shared.api.ImportResult
import com.ratatoskr.shared.api.RatatoskrApiClient
import com.ratatoskr.shared.api.RatatoskrApiException
import com.ratatoskr.shared.api.RatatoskrConnectionException
import com.ratatoskr.shared.backend.RemoteVaultBackend
import com.ratatoskr.shared.backend.VaultBackend
import com.ratatoskr.shared.platform.SavedSession
import com.ratatoskr.shared.platform.SessionStore

/** Which top-level screen is showing. Mirrors the web UI's lock-screen
 * forms (setup/unlock/invite) and the main app screen. See
 * static/index.html for the equivalent web markup this maps to. */
sealed class Screen {
    data object ServerConnect : Screen()
    data object CheckingServer : Screen()
    data object Setup : Screen()
    data object Unlock : Screen()
    data object AcceptInvite : Screen()
    data object Vault : Screen()
}

/**
 * Single state holder for the whole app, analogous to the web UI's `state`
 * object in app.js plus the screen-switching logic in boot(). Plain
 * Compose `mutableStateOf` properties rather than a platform-specific
 * ViewModel base class, deliberately. This class lives in commonMain and
 * needs to work unmodified on desktop, Android, and iOS. A platform's own
 * ViewModel wrapper (e.g. androidx.lifecycle.ViewModel on Android) can
 * hold and scope an instance of this later without this class needing to
 * change.
 *
 * The vault is on a server (RemoteVaultBackend). `sessionStore`, when the
 * platform supplies one, remembers the server and session token across
 * launches (see restoreSession()).
 */
class AppState(private val sessionStore: SessionStore? = null) {
    // With a saved server, start on the "connecting" screen rather than
    // flashing the server-address form while restoreSession() runs.
    var screen by mutableStateOf<Screen>(
        if (sessionStore?.load() != null) Screen.CheckingServer else Screen.ServerConnect
    )
        private set

    var serverUrl by mutableStateOf("")
    var username by mutableStateOf("")
    var role by mutableStateOf("")
    var entries by mutableStateOf<List<EntryOut>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    /** Short-lived confirmation message, the web UI's toast(). Shown
     * and auto-cleared by RatatoskrApp. */
    var notice by mutableStateOf<String?>(null)

    private var backend: VaultBackend? = null

    private fun requireBackend(): VaultBackend =
        backend ?: throw IllegalStateException("Not connected to a server yet")

    private fun remote(): RemoteVaultBackend =
        requireBackend() as? RemoteVaultBackend ?: throw IllegalStateException("Not connected to a server")

    /** Step 1: point the app at a server and see what it needs next.
     * Mirrors the web UI's boot() calling /auth/status before deciding
     * whether to show setup or unlock. */
    suspend fun connectToServer(url: String) {
        errorMessage = null
        isLoading = true
        val trimmed = url.trim()
        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed" // most homelab deployments are plain HTTP over Tailscale, same assumption static/totp.js makes
        }
        try {
            val candidate = RemoteVaultBackend(RatatoskrApiClient(normalized))
            val initialized = candidate.isInitialized()
            backend = candidate
            serverUrl = normalized
            sessionStore?.save(SavedSession(normalized, token = null))
            screen = if (initialized) Screen.Unlock else Screen.Setup
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
        } catch (e: RatatoskrApiException) {
            errorMessage = e.detail
        } finally {
            isLoading = false
        }
    }

    /** Called once at launch: reconnects to the saved server and, if a
     * saved token is still accepted, goes straight to the vault.
     * Otherwise it lands on Unlock for the same server, including when the
     * server can't be reached right now, so the user only ever re-enters
     * their credentials (unlocking retries the connection). The
     * server-address screen only appears via "Change server". */
    suspend fun restoreSession() {
        val store = sessionStore ?: return
        val saved = store.load() ?: return
        screen = Screen.CheckingServer
        serverUrl = saved.serverUrl // for the "Connecting to ..." screen
        connectToServer(saved.serverUrl)
        if (backend == null) {
            // Unreachable: keep connectToServer's error message showing.
            backend = RemoteVaultBackend(RatatoskrApiClient(saved.serverUrl))
            serverUrl = saved.serverUrl
            screen = Screen.Unlock
            return
        }
        // connectToServer saved the server with no token; put the saved
        // token back, so it's only dropped if the server rejects it below
        // (not, say, on a network blip while checking it).
        store.save(saved)
        val token = saved.token
        if (token == null || screen != Screen.Unlock) return

        val api = remote().api
        api.token = token
        try {
            val me = api.me()
            username = me.username
            role = me.role
            loadEntries()
            screen = Screen.Vault
        } catch (e: RatatoskrApiException) {
            api.token = null
            store.save(SavedSession(serverUrl, token = null))
        } catch (e: RatatoskrConnectionException) {
            api.token = null
            errorMessage = e.message
        }
    }

    fun showAcceptInvite() {
        screen = Screen.AcceptInvite
    }

    fun showUnlock() {
        screen = Screen.Unlock
    }

    fun changeServer() {
        (backend as? RemoteVaultBackend)?.api?.close()
        backend = null
        serverUrl = ""
        errorMessage = null
        sessionStore?.clear()
        screen = Screen.ServerConnect
    }

    suspend fun setup(username: String, masterPassword: String) {
        runAuthAction { requireBackend().setup(username, masterPassword) }
    }

    suspend fun unlock(username: String, masterPassword: String) {
        runAuthAction { requireBackend().unlock(username, masterPassword) }
    }

    suspend fun acceptInvite(username: String, inviteToken: String, masterPassword: String) {
        runAuthAction { remote().acceptInvite(username, inviteToken, masterPassword) }
    }

    private suspend fun runAuthAction(action: suspend () -> AuthResponse) {
        errorMessage = null
        isLoading = true
        try {
            val response = action()
            sessionStore?.save(SavedSession(serverUrl, response.token))
            username = response.username
            role = response.role
            loadEntries()
            screen = Screen.Vault
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
        } catch (e: RatatoskrApiException) {
            errorMessage = e.detail
        } finally {
            isLoading = false
        }
    }

    /** Changes the signed-in account's master password. The server signs
     * out every other session for this account and returns a new token for
     * this one, which replaces the old (now revoked) token here and in the
     * SessionStore. Returns null on success, or the error to show. */
    suspend fun changePassword(currentPassword: String, newPassword: String): String? = try {
        val response = requireBackend().changePassword(currentPassword, newPassword)
        sessionStore?.save(SavedSession(serverUrl, response.token))
        notice = "Master password changed"
        null
    } catch (e: RatatoskrApiException) {
        e.detail
    } catch (e: RatatoskrConnectionException) {
        e.message
    }

    suspend fun generatePassword(request: GenerateRequest): String = requireBackend().generatePassword(request)

    suspend fun loadEntries() {
        try {
            entries = requireBackend().listEntries()
        } catch (e: RatatoskrApiException) {
            fail(e)
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
        }
    }

    suspend fun saveEntry(existingId: String?, entry: EntryIn): Boolean {
        errorMessage = null
        return try {
            if (existingId != null) requireBackend().updateEntry(existingId, entry)
            else requireBackend().createEntry(entry)
            loadEntries()
            true
        } catch (e: RatatoskrApiException) {
            fail(e)
            false
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
            false
        }
    }

    suspend fun deleteEntry(id: String): Boolean {
        errorMessage = null
        return try {
            requireBackend().deleteEntry(id)
            loadEntries()
            true
        } catch (e: RatatoskrApiException) {
            fail(e)
            false
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
            false
        }
    }

    /** Fetches the unencrypted CSV export. Mirrors the web UI's
     * export-confirm handler. Returns null (with errorMessage set) on
     * failure. */
    suspend fun exportCsv(): String? {
        errorMessage = null
        return try {
            requireBackend().exportCsv()
        } catch (e: RatatoskrApiException) {
            fail(e)
            null
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
            null
        }
    }

    /** Uploads a CSV and refreshes the list, setting the same summary
     * notice the web UI toasts. Returns the server's result so the caller
     * can show per-row errors, or null on failure. */
    suspend fun importCsv(fileName: String, bytes: ByteArray): ImportResult? {
        errorMessage = null
        return try {
            val result = requireBackend().importCsv(fileName, bytes)
            var msg = "Imported ${result.imported} entr${if (result.imported == 1) "y" else "ies"}"
            if (result.skipped > 0) msg += ", skipped ${result.skipped} blank row${if (result.skipped == 1) "" else "s"}"
            if (result.errors.isNotEmpty()) msg += ", ${result.errors.size} error(s)"
            notice = msg
            loadEntries()
            result
        } catch (e: RatatoskrApiException) {
            fail(e)
            null
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
            null
        }
    }

    /** A 401 on a vault call means the session is gone (server idle
     * timeout or restart): back to Unlock, not an error banner on a vault
     * that no longer works. */
    private fun fail(e: RatatoskrApiException) {
        if (e.statusCode == 401) lockedOut(e.detail) else errorMessage = e.detail
    }

    private fun lockedOut(message: String) {
        (backend as? RemoteVaultBackend)?.api?.token = null
        sessionStore?.save(SavedSession(serverUrl, token = null))
        clearVault()
        errorMessage = message
    }

    suspend fun lock() {
        try {
            backend?.lock()
        } catch (_: Exception) {
            // best-effort, same as the web UI's lock button. Still clear
            // local state below even if the network call fails
        }
        sessionStore?.save(SavedSession(serverUrl, token = null))
        clearVault()
    }

    private fun clearVault() {
        entries = emptyList()
        username = ""
        role = ""
        screen = Screen.Unlock
    }
}
