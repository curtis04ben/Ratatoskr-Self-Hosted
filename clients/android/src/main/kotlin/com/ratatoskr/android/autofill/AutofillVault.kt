package com.ratatoskr.android.autofill

import android.content.Context
import com.ratatoskr.android.KeystoreSessionStore
import com.ratatoskr.shared.api.EntryIn
import com.ratatoskr.shared.api.EntryOut
import com.ratatoskr.shared.api.RatatoskrApiClient
import com.ratatoskr.shared.api.RatatoskrApiException
import com.ratatoskr.shared.platform.SavedSession

/**
 * The vault as autofill sees it: the same server and encrypted session
 * token the app saved (KeystoreSessionStore), so unlocking in either place
 * unlocks both, and locking the app stops autofill too. Entries are
 * fetched fresh for each request and only ever held in memory.
 *
 * Also remembers, on this device only, which entry the user picked for
 * an app or site that had no match, and the last username unlocked with
 * (so the unlock form only needs the master password). Kept in private
 * prefs that, like the session, are excluded from backups.
 */
class AutofillVault(context: Context) {
    private val sessionStore = KeystoreSessionStore(context)
    private val prefs = context.getSharedPreferences("autofill", Context.MODE_PRIVATE)

    /** The server the app is connected to, or null if it hasn't been set up. */
    val serverUrl: String? get() = sessionStore.load()?.serverUrl

    val lastUsername: String get() = prefs.getString(KEY_USERNAME, null).orEmpty()

    /**
     * The vault's entries, or null when it's locked: no token, or the
     * server no longer accepts it (idle timeout, server restart, locked
     * elsewhere), in which case the stale token is dropped. A server
     * that can't be reached throws RatatoskrConnectionException.
     */
    suspend fun entriesOrNull(): List<EntryOut>? {
        val saved = sessionStore.load() ?: return null
        val token = saved.token ?: return null
        return withClient(saved.serverUrl, token) { api ->
            try {
                api.listEntries().also {
                    // Unlocked in the app rather than here: learn the
                    // username once, so the unlock form can prefill it.
                    if (lastUsername.isEmpty()) prefs.edit().putString(KEY_USERNAME, api.me().username).apply()
                }
            } catch (e: RatatoskrApiException) {
                if (e.statusCode != 401) throw e
                sessionStore.save(SavedSession(saved.serverUrl, token = null))
                null
            }
        }
    }

    /** Unlocks with the master password, saving the new session for the
     * app as well. Throws the API/connection exceptions for the UI. */
    suspend fun unlock(username: String, masterPassword: String) {
        val server = checkNotNull(serverUrl) { "Open Ratatoskr and connect to your server first." }
        withClient(server, token = null) { api ->
            val response = api.unlock(username, masterPassword)
            sessionStore.save(SavedSession(server, response.token))
            prefs.edit().putString(KEY_USERNAME, response.username).apply()
        }
    }

    suspend fun createEntry(entry: EntryIn): EntryOut = withSession { it.createEntry(entry) }

    suspend fun updateEntry(id: String, entry: EntryIn): EntryOut = withSession { it.updateEntry(id, entry) }

    fun pairedIds(target: AutofillTarget): Set<String> =
        prefs.getStringSet(PAIR_PREFIX + target.pairingKey, null).orEmpty()

    fun pair(target: AutofillTarget, entryId: String) {
        prefs.edit().putStringSet(PAIR_PREFIX + target.pairingKey, pairedIds(target) + entryId).apply()
    }

    private suspend fun <T> withSession(block: suspend (RatatoskrApiClient) -> T): T {
        val saved = checkNotNull(sessionStore.load()?.takeIf { it.token != null }) { "Ratatoskr is locked." }
        return withClient(saved.serverUrl, saved.token, block)
    }

    private suspend fun <T> withClient(serverUrl: String, token: String?, block: suspend (RatatoskrApiClient) -> T): T {
        val api = RatatoskrApiClient(serverUrl)
        api.token = token
        return try {
            block(api)
        } finally {
            api.close()
        }
    }

    private companion object {
        const val KEY_USERNAME = "last_username"
        const val PAIR_PREFIX = "pair:"
    }
}
