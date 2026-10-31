package com.ratatoskr.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ratatoskr.shared.api.EntryOut
import com.ratatoskr.shared.api.Roles
import com.ratatoskr.shared.state.AppState
import kotlinx.coroutines.launch

/** The main screen once unlocked: header (brand + role badge + lock),
 * search/export/import/new-entry toolbar, and the entry list. Mirrors
 * #app-screen in static/index.html. Sharing and the admin users panel are
 * intentionally not here yet, deferred fast-follows per the client dev
 * plan, not an oversight. `onExport`/`onImport` are null when the platform
 * has no file picker wired up yet, which hides those buttons. `appIcon` is
 * the logo shown at the header's left, like the web UI's brand mark.
 * `onAutofillSettings` is supplied only where the OS has a password
 * autofill service to choose (Android), and adds a menu item for it. */
@Composable
fun VaultScreen(
    appState: AppState,
    onNewEntry: () -> Unit,
    onEditEntry: (EntryOut) -> Unit,
    onOpenGenerator: () -> Unit,
    onChangePassword: () -> Unit,
    onExport: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    appIcon: Painter? = null,
    onAutofillSettings: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Phones are too narrow for the desktop's one-line header and toolbar,
    // so below this width the role badge sits under the title and the
    // search field gets its own row above the (wrapping) buttons.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxWidth < 560.dp
        val gutter = if (compact) 12.dp else 20.dp

        Column(modifier = Modifier.fillMaxSize().background(RatatoskrColors.Bg)) {
            // ---------- header ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = RatatoskrColors.Hairline)
                    .padding(horizontal = gutter, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val roleBadge: @Composable () -> Unit = {
                    Box(
                        modifier = Modifier
                            .border(1.dp, RatatoskrColors.Verdigris, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(appState.role, style = MaterialTheme.typography.labelSmall, color = RatatoskrColors.Verdigris)
                    }
                }
                appIcon?.let {
                    Image(
                        painter = it,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(if (compact) 40.dp else 32.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Ratatoskr",
                        style = MaterialTheme.typography.titleMedium,
                        color = RatatoskrColors.Text,
                    )
                    if (compact && appState.role.isNotBlank()) roleBadge()
                }
                if (appState.role.isNotBlank()) {
                    if (!compact) roleBadge()
                    Row(modifier = Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // No room for Generate beside Lock on a phone; it
                        // moves into the menu there.
                        if (!compact) GhostButton("Generate", onClick = onOpenGenerator, modifier = Modifier.padding(end = 8.dp))
                        GhostButton("Lock", onClick = { scope.launch { appState.lock() } })
                        AccountMenu(
                            onGenerate = onOpenGenerator.takeIf { compact },
                            onChangePassword = onChangePassword,
                            onAutofillSettings = onAutofillSettings,
                        )
                    }
                }
            }

            // ---------- toolbar ----------
            // Visitors are read-only: no import or new entry, same as showAppScreen() in app.js
            val isVisitor = appState.role == Roles.VISITOR
            val toolbarButtons: @Composable () -> Unit = {
                onExport?.let { CautionButton("Export\u2026", onClick = it) }
                if (!isVisitor) {
                    onImport?.let { GhostButton("Import", onClick = it) }
                    PrimaryButton("+ New entry", onClick = onNewEntry)
                }
            }
            if (compact) {
                Column(modifier = Modifier.fillMaxWidth().padding(gutter)) {
                    RatatoskrTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "Search",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        toolbarButtons()
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(gutter),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RatatoskrTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "Search",
                        modifier = Modifier.weight(1f),
                    )
                    toolbarButtons()
                }
            }

            ErrorText(appState.errorMessage, modifier = Modifier.padding(horizontal = gutter))

            // ---------- entry list ----------
            val filtered = appState.entries.filter {
                query.isBlank() || it.site.contains(query, ignoreCase = true) || it.username.contains(query, ignoreCase = true)
            }

            if (appState.entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing here yet. Entries you can see will appear here.",
                        color = RatatoskrColors.TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = gutter)) {
                    items(filtered, key = { it.id }) { entry ->
                        EntryRow(entry, onClick = { onEditEntry(entry) })
                    }
                }
            }
        }
    }
}

/** The header's "⋮" menu: account actions that don't need a permanent
 * button. `onGenerate` is non-null only on narrow screens, where Generate
 * has no room in the header itself. */
@Composable
private fun AccountMenu(onGenerate: (() -> Unit)?, onChangePassword: () -> Unit, onAutofillSettings: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "More options" }) {
            Text("⋮", style = MaterialTheme.typography.titleLarge, color = RatatoskrColors.Text)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = RatatoskrColors.Panel,
        ) {
            onGenerate?.let {
                DropdownMenuItem(
                    text = { Text("Generate a password", color = RatatoskrColors.Text) },
                    onClick = { expanded = false; it() },
                )
            }
            DropdownMenuItem(
                text = { Text("Change master password", color = RatatoskrColors.Text) },
                onClick = { expanded = false; onChangePassword() },
            )
            onAutofillSettings?.let {
                DropdownMenuItem(
                    text = { Text("Autofill settings", color = RatatoskrColors.Text) },
                    onClick = { expanded = false; it() },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(entry: EntryOut, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(width = 1.dp, color = RatatoskrColors.Hairline)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.site, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = RatatoskrColors.Text)
                if (!entry.can_write) {
                    Text(
                        "  read-only",
                        style = MaterialTheme.typography.labelSmall,
                        color = RatatoskrColors.DangerBright,
                    )
                }
                if (entry.totp_secret.isNotBlank()) {
                    Text(
                        "  2FA",
                        style = MaterialTheme.typography.labelSmall,
                        color = RatatoskrColors.BrassBright,
                    )
                }
            }
            if (entry.username.isNotBlank()) {
                Text(entry.username, style = MaterialTheme.typography.bodySmall, color = RatatoskrColors.TextMuted)
            }
        }
    }
}
