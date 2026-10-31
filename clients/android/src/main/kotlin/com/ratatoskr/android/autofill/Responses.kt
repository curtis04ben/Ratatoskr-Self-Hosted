package com.ratatoskr.android.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.ratatoskr.android.R
import com.ratatoskr.shared.api.EntryOut
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds what the autofill dropdown shows. Uses the original (pre-API 33)
 * Dataset/SaveInfo builders, which still work everywhere, rather than
 * duplicating each one for the newer Presentations API.
 */
@Suppress("DEPRECATION")
object Responses {
    private val requestCodes = AtomicInteger()

    /** Unlocked: one suggestion per matching entry, then "Search
     * Ratatoskr…" for choosing any other entry. */
    fun unlocked(context: Context, form: LoginForm, matches: List<EntryOut>): FillResponse {
        val builder = FillResponse.Builder()
        matches.take(MAX_SUGGESTIONS).mapNotNull { dataset(context, form, it) }.forEach(builder::addDataset)
        val search = Dataset.Builder(presentation(context, "Search Ratatoskr…", if (matches.isEmpty()) "No saved login for this ${form.target.kind}" else null))
        for (id in form.ids) search.setValue(id, null)
        search.setAuthentication(activitySender(context, AutofillActivity.MODE_PICK))
        builder.addDataset(search.build())
        saveInfo(form)?.let(builder::setSaveInfo)
        return builder.build()
    }

    /** Locked: a single "Unlock Ratatoskr" suggestion. Unlocking replaces
     * this response with unlocked()'s. */
    fun locked(context: Context, form: LoginForm): FillResponse {
        val builder = FillResponse.Builder()
            .setAuthentication(form.ids, activitySender(context, AutofillActivity.MODE_UNLOCK), presentation(context, "Unlock Ratatoskr", "to fill a saved login"))
        saveInfo(form)?.let(builder::setSaveInfo)
        return builder.build()
    }

    /** Fills the entry's username and password into whichever of the two
     * fields the form has. Null if the entry has nothing for them. */
    fun dataset(context: Context, form: LoginForm, entry: EntryOut): Dataset? {
        val builder = Dataset.Builder(presentation(context, entry.site, entry.username.ifBlank { "No username" }))
        var any = false
        if (form.usernameId != null && entry.username.isNotEmpty()) {
            builder.setValue(form.usernameId, AutofillValue.forText(entry.username)); any = true
        }
        if (form.passwordId != null && entry.password.isNotEmpty()) {
            builder.setValue(form.passwordId, AutofillValue.forText(entry.password)); any = true
        }
        return if (any) builder.build() else null
    }

    /** Asks the system to offer "Save to Ratatoskr?" once the user submits
     * a password we didn't fill (or changed what we filled). Only for forms
     * with a password field: a username on its own isn't worth saving. */
    private fun saveInfo(form: LoginForm): SaveInfo? {
        val passwordId = form.passwordId ?: return null
        val type = if (form.usernameId != null) SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME
        else SaveInfo.SAVE_DATA_TYPE_PASSWORD
        return SaveInfo.Builder(type, arrayOf(passwordId))
            .apply { form.usernameId?.let { setOptionalIds(arrayOf(it)) } }
            // Many sign-ins don't end the activity (single-activity apps,
            // web pages), so also offer to save once the fields disappear.
            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            .build()
    }

    fun presentation(context: Context, title: String, subtitle: String?): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle.orEmpty())
            setViewVisibility(R.id.autofill_subtitle, if (subtitle.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
        }

    /** The system adds the screen's structure to this intent when it fires
     * (hence FLAG_MUTABLE), and AutofillActivity re-reads the form from it. */
    private fun activitySender(context: Context, mode: String): IntentSender {
        val intent = Intent(context, AutofillActivity::class.java).putExtra(AutofillActivity.EXTRA_MODE, mode)
        return PendingIntent.getActivity(
            context,
            requestCodes.incrementAndGet(),
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender
    }

    private val AutofillTarget.kind: String get() = if (webDomain != null) "site" else "app"

    private const val MAX_SUGGESTIONS = 5
}
