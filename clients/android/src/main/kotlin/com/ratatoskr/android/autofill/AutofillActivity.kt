package com.ratatoskr.android.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ratatoskr.android.R
import com.ratatoskr.shared.api.EntryIn
import com.ratatoskr.shared.api.EntryOut
import com.ratatoskr.shared.api.RatatoskrApiException
import com.ratatoskr.shared.api.RatatoskrConnectionException
import com.ratatoskr.shared.ui.ErrorText
import com.ratatoskr.shared.ui.GhostButton
import com.ratatoskr.shared.ui.PrimaryButton
import com.ratatoskr.shared.ui.RatatoskrColors
import com.ratatoskr.shared.ui.RatatoskrTextField
import com.ratatoskr.shared.ui.RatatoskrTheme
import kotlinx.coroutines.launch

/**
 * The screens autofill opens over another app, in three modes:
 * - MODE_UNLOCK: the vault was locked when a login form appeared. Unlock,
 *   then hand the system the suggestions for that form.
 * - MODE_PICK: "Search Ratatoskr…". Choose any entry to fill, and
 *   remember the choice for that app or site.
 * - MODE_SAVE: the user signed in with a password Ratatoskr doesn't have.
 *   Save it as a new entry, or update the entry it replaces.
 * Each starts with the unlock form if the vault is locked.
 */
class AutofillActivity : ComponentActivity() {
    private lateinit var vault: AutofillVault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        vault = AutofillVault(applicationContext)

        val mode = intent.getStringExtra(EXTRA_MODE)
        val save = if (mode == MODE_SAVE) PendingSaves.take(intent.getStringExtra(EXTRA_SAVE_KEY)) else null
        val form = if (mode == MODE_SAVE) null else assistStructure()?.let(FormParser::parse)
        if (mode == MODE_SAVE && save == null) {
            Toast.makeText(this, "Ratatoskr couldn't save that login. Please sign in again.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (mode != MODE_SAVE && form == null) {
            finish()
            return
        }
        val target = save?.target ?: form!!.target

        setContent {
            RatatoskrTheme {
                Box(modifier = Modifier.fillMaxSize().background(RatatoskrColors.Bg).safeDrawingPadding()) {
                    AutofillFlow(
                        mode = mode.orEmpty(),
                        target = target,
                        targetLabel = targetLabel(target),
                        onUnlocked = { entries ->
                            if (mode == MODE_UNLOCK) {
                                val matches = matchingEntries(entries, target, vault.pairedIds(target))
                                finishWith(Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, Responses.unlocked(this@AutofillActivity, form!!, matches)))
                            }
                        },
                        pick = form?.let { f -> { entry -> pick(f, entry) } },
                        save = save,
                    )
                }
            }
        }
    }

    private fun pick(form: LoginForm, entry: EntryOut) {
        val dataset = Responses.dataset(this, form, entry)
        if (dataset == null) {
            Toast.makeText(this, "That entry has no username or password to fill.", Toast.LENGTH_SHORT).show()
            return
        }
        vault.pair(form.target, entry.id)
        finishWith(Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
    }

    private fun finishWith(result: Intent) {
        setResult(RESULT_OK, result)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun assistStructure(): AssistStructure? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        } else {
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        }

    /** "GitHub"-style name for what's asking: the site's domain, or the
     * app's name. Android 11+ hides other apps' details from us unless
     * declared, so fall back to tidying up the package name. */
    private fun targetLabel(target: AutofillTarget): String {
        target.webDomain?.let { return it }
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(target.packageName, 0)).toString()
        } catch (_: Exception) {
            target.packageName.split('.').lastOrNull { it.length > 2 && it !in setOf("android", "app", "mobile") }
                ?.replaceFirstChar { it.uppercase() } ?: target.packageName
        }
    }

    @Composable
    private fun AutofillFlow(
        mode: String,
        target: AutofillTarget,
        targetLabel: String,
        onUnlocked: (List<EntryOut>) -> Unit,
        pick: ((EntryOut) -> Unit)?,
        save: PendingSave?,
    ) {
        var entries by remember { mutableStateOf<List<EntryOut>?>(null) }
        var checking by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }

        // An unlocked session needs no form at all.
        LaunchedEffect(Unit) {
            try {
                vault.entriesOrNull()?.let { entries = it; onUnlocked(it) }
            } catch (e: RatatoskrConnectionException) {
                error = e.message
            } catch (e: RatatoskrApiException) {
                error = e.detail
            }
            checking = false
        }

        val loaded = entries
        when {
            checking -> Centered { CircularProgressIndicator(color = RatatoskrColors.Brass) }
            loaded == null -> UnlockForm(
                reason = when (mode) {
                    MODE_SAVE -> "Unlock to save your login for $targetLabel."
                    else -> "Unlock to fill your login for $targetLabel."
                },
                initialError = error,
                onUnlocked = { list -> entries = list; onUnlocked(list) },
            )
            mode == MODE_UNLOCK -> Centered { CircularProgressIndicator(color = RatatoskrColors.Brass) }
            mode == MODE_PICK && pick != null -> PickList(loaded, target, targetLabel, pick)
            save != null -> SaveForm(loaded, save, targetLabel)
        }
    }

    @Composable
    private fun UnlockForm(reason: String, initialError: String?, onUnlocked: (List<EntryOut>) -> Unit) {
        var username by remember { mutableStateOf(vault.lastUsername) }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf(initialError) }
        val scope = rememberCoroutineScope()
        val passwordFocus = remember { FocusRequester() }
        val usernameFocus = remember { FocusRequester() }

        LaunchedEffect(Unit) { (if (username.isEmpty()) usernameFocus else passwordFocus).requestFocus() }

        Card(title = "Unlock ${getString(R.string.app_name)}", lede = reason) {
            RatatoskrTextField(username, { username = it }, "Username", Modifier.fillMaxWidth().focusRequester(usernameFocus))
            RatatoskrTextField(
                password, { password = it }, "Master password",
                Modifier.fillMaxWidth().padding(top = 10.dp).focusRequester(passwordFocus), isPassword = true,
            )
            if (busy) {
                CircularProgressIndicator(color = RatatoskrColors.Brass, modifier = Modifier.padding(16.dp))
            } else {
                PrimaryButton(
                    "Unlock",
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                vault.unlock(username.trim(), password)
                                password = ""
                                onUnlocked(vault.entriesOrNull().orEmpty())
                            } catch (e: RatatoskrApiException) {
                                error = e.detail
                            } catch (e: RatatoskrConnectionException) {
                                error = e.message
                            } catch (e: IllegalStateException) {
                                error = e.message
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    enabled = username.isNotBlank() && password.isNotBlank(),
                )
            }
            ErrorText(error, modifier = Modifier.fillMaxWidth())
            GhostButton("Cancel", onClick = { finish() }, modifier = Modifier.padding(top = 8.dp))
        }
    }

    @Composable
    private fun PickList(entries: List<EntryOut>, target: AutofillTarget, targetLabel: String, onPick: (EntryOut) -> Unit) {
        var query by remember { mutableStateOf("") }
        val sorted = remember(entries) {
            entries.sortedWith(compareBy({ !looksRelated(it, target) }, { it.site.lowercase() }))
        }
        val shown = sorted.filter {
            query.isBlank() || it.site.contains(query, ignoreCase = true) ||
                it.username.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Header("Choose a login for $targetLabel", "Ratatoskr will suggest it there next time.")
            RatatoskrTextField(query, { query = it }, "Search", Modifier.fillMaxWidth().padding(bottom = 8.dp))
            if (shown.isEmpty()) {
                Text(
                    if (entries.isEmpty()) "Your vault is empty." else "No entries match.",
                    color = RatatoskrColors.TextMuted,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(shown, key = { it.id }) { entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(entry) }
                            .border(width = 1.dp, color = RatatoskrColors.Hairline)
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                    ) {
                        Text(entry.site, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = RatatoskrColors.Text)
                        if (entry.username.isNotEmpty()) {
                            Text(entry.username, style = MaterialTheme.typography.bodySmall, color = RatatoskrColors.TextMuted)
                        }
                    }
                }
            }
            GhostButton("Cancel", onClick = { finish() }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
        }
    }

    @Composable
    private fun SaveForm(entries: List<EntryOut>, save: PendingSave, targetLabel: String) {
        // An entry for the same app/site and username means a changed
        // password: offer to update it rather than add a duplicate.
        val existing = remember(entries) {
            matchingEntries(entries, save.target, vault.pairedIds(save.target))
                .firstOrNull { it.can_write && it.username.equals(save.username, ignoreCase = true) }
        }
        var site by remember { mutableStateOf(targetLabel) }
        var username by remember { mutableStateOf(save.username) }
        var password by remember { mutableStateOf(save.password) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var asNew by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        if (existing != null && existing.password == save.password) {
            LaunchedEffect(Unit) {
                Toast.makeText(this@AutofillActivity, "That login is already in Ratatoskr.", Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }

        fun run(action: suspend () -> EntryOut, done: String) {
            busy = true
            error = null
            scope.launch {
                try {
                    vault.pair(save.target, action().id)
                    Toast.makeText(this@AutofillActivity, done, Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: RatatoskrApiException) {
                    error = e.detail
                } catch (e: RatatoskrConnectionException) {
                    error = e.message
                } catch (e: IllegalStateException) {
                    error = e.message
                }
                busy = false
            }
        }

        if (existing != null && !asNew) {
            Card(
                title = "Update password?",
                lede = "Ratatoskr has a different password for ${existing.username} on ${existing.site}. Replace it with the one you just used?",
            ) {
                if (busy) {
                    CircularProgressIndicator(color = RatatoskrColors.Brass, modifier = Modifier.padding(16.dp))
                } else {
                    PrimaryButton(
                        "Update password",
                        onClick = {
                            run({
                                vault.updateEntry(
                                    existing.id,
                                    EntryIn(existing.site, existing.username, save.password, existing.url, existing.notes, existing.totp_secret),
                                )
                            }, "Password updated in Ratatoskr")
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    GhostButton("Save as a new entry", onClick = { asNew = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                ErrorText(error, modifier = Modifier.fillMaxWidth())
                GhostButton("Not now", onClick = { finish() }, modifier = Modifier.padding(top = 8.dp))
            }
            return
        }

        Card(title = "Save to Ratatoskr", lede = "Save this login so Ratatoskr can fill it next time.") {
            RatatoskrTextField(site, { site = it }, "Name", Modifier.fillMaxWidth())
            RatatoskrTextField(username, { username = it }, "Username", Modifier.fillMaxWidth().padding(top = 10.dp))
            RatatoskrTextField(password, { password = it }, "Password", Modifier.fillMaxWidth().padding(top = 10.dp), isPassword = true)
            Text(
                save.target.entryUrl,
                style = MaterialTheme.typography.bodySmall,
                color = RatatoskrColors.TextMuted,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (busy) {
                CircularProgressIndicator(color = RatatoskrColors.Brass, modifier = Modifier.padding(16.dp))
            } else {
                PrimaryButton(
                    "Save",
                    onClick = {
                        run({ vault.createEntry(EntryIn(site.trim(), username.trim(), password, save.target.entryUrl)) }, "Saved to Ratatoskr")
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    enabled = site.isNotBlank() && password.isNotEmpty(),
                )
            }
            ErrorText(error, modifier = Modifier.fillMaxWidth())
            GhostButton("Not now", onClick = { finish() }, modifier = Modifier.padding(top = 8.dp))
        }
    }

    @Composable
    private fun Header(title: String, lede: String?) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ratatoskr_icon), contentDescription = null, modifier = Modifier.size(36.dp))
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = RatatoskrColors.Text)
                if (lede != null) Text(lede, style = MaterialTheme.typography.bodySmall, color = RatatoskrColors.TextMuted)
            }
        }
    }

    /** Narrow centred column, like the app's own unlock screen. */
    @Composable
    private fun Card(title: String, lede: String, content: @Composable () -> Unit) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Header(title, lede)
                content()
            }
        }
    }

    @Composable
    private fun Centered(content: @Composable () -> Unit) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }

    companion object {
        const val EXTRA_MODE = "com.ratatoskr.android.autofill.MODE"
        const val EXTRA_SAVE_KEY = "com.ratatoskr.android.autofill.SAVE_KEY"
        const val MODE_UNLOCK = "unlock"
        const val MODE_PICK = "pick"
        const val MODE_SAVE = "save"
    }
}
