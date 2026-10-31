package com.ratatoskr.android.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import com.ratatoskr.shared.api.RatatoskrApiException
import com.ratatoskr.shared.api.RatatoskrConnectionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Ratatoskr as the phone's autofill service (chosen by the user in the
 * system's Passwords/Autofill settings). When another app or a browser
 * shows a login form, onFillRequest suggests the matching entries, or
 * "Unlock Ratatoskr" while the vault is locked; after the user signs in
 * with a password Ratatoskr didn't fill, onSaveRequest offers to save it.
 *
 * Everything that needs a screen (unlocking, choosing an entry, confirming
 * a save) happens in AutofillActivity. Nothing is ever filled without an
 * unlocked session on the server.
 */
class RatatoskrAutofillService : AutofillService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val form = FormParser.parse(request.fillContexts.last().structure)
        val vault = AutofillVault(this)
        // Not Ratatoskr's own screens (the master password field, in this
        // app or Ratatoskr's standalone one), and nothing until the app has
        // been connected to a server.
        if (form == null || isRatatoskrApp(form.target.packageName) || vault.serverUrl == null) {
            callback.onSuccess(null)
            return
        }
        val job = scope.launch {
            // The system gives up on a slow service after a few seconds,
            // so a server that's slow or unreachable gets the "Unlock"
            // suggestion; AutofillActivity then shows what's wrong.
            val entries = try {
                withTimeout(FILL_TIMEOUT_MS) { vault.entriesOrNull() }
            } catch (_: TimeoutCancellationException) {
                null
            } catch (_: RatatoskrConnectionException) {
                null
            } catch (_: RatatoskrApiException) {
                null
            }
            val response = if (entries == null) {
                Responses.locked(this@RatatoskrAutofillService, form)
            } else {
                Responses.unlocked(this@RatatoskrAutofillService, form, matchingEntries(entries, form.target, vault.pairedIds(form.target)))
            }
            callback.onSuccess(response)
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // The latest screen has the password; with a two-step sign-in the
        // username was typed on an earlier one.
        val forms = request.fillContexts.asReversed().mapNotNull { FormParser.parse(it.structure) }
        val form = forms.firstOrNull { !it.passwordValue.isNullOrEmpty() }
        if (form == null || isRatatoskrApp(form.target.packageName)) {
            callback.onSuccess()
            return
        }
        val username = form.usernameValue?.takeIf { it.isNotBlank() }
            ?: forms.firstNotNullOfOrNull { f -> f.usernameValue?.takeIf { it.isNotBlank() } }
        val key = PendingSaves.put(PendingSave(form.target, username.orEmpty().trim(), form.passwordValue!!))
        val intent = Intent(this, AutofillActivity::class.java)
            .putExtra(AutofillActivity.EXTRA_MODE, AutofillActivity.MODE_SAVE)
            .putExtra(AutofillActivity.EXTRA_SAVE_KEY, key)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pending = PendingIntent.getActivity(this, key.hashCode(), intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            callback.onSuccess(pending.intentSender)
        } else {
            // Before Android 9 the callback can't open a screen, but a
            // service could still start one itself.
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            callback.onSuccess()
        }
    }

    private companion object {
        const val FILL_TIMEOUT_MS = 4_000L
    }
}

/** A login waiting for the user to confirm saving it. */
class PendingSave(val target: AutofillTarget, val username: String, val password: String)

/**
 * Hands a pending save from the service to AutofillActivity by a random
 * key, so the password stays in this process's memory rather than riding
 * along in an Intent the system keeps hold of. If the process dies in
 * between, the save is simply lost and the user is told.
 */
object PendingSaves {
    private val pending = ConcurrentHashMap<String, PendingSave>()

    fun put(save: PendingSave): String = UUID.randomUUID().toString().also { pending[it] = save }

    fun take(key: String?): PendingSave? = key?.let { pending.remove(it) }
}
