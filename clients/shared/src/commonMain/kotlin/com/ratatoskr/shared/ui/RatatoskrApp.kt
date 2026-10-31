package com.ratatoskr.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.ratatoskr.shared.api.EntryOut
import com.ratatoskr.shared.platform.PickedFile
import com.ratatoskr.shared.platform.PlatformFiles
import com.ratatoskr.shared.platform.SessionStore
import com.ratatoskr.shared.state.AppState
import com.ratatoskr.shared.state.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Root composable: owns the one AppState instance for the app's lifetime
 * and switches between screens based on appState.screen, the same
 * responsibility static/app.js's boot()/show*Screen() functions have in
 * the web UI. Call this once from each platform's entry point (desktop's
 * Main.kt now; Android/iOS's own entry points in their phases), wrapped
 * in RatatoskrTheme.
 *
 * `appIcon` is the logo, supplied by the platform (each has its own way to
 * load a bitmap resource); it's shown large on the server-connect screen
 * and small at the left of the vault header. `files` likewise
 * comes from the platform and enables CSV import/export when present, and
 * `sessionStore` lets the app remember its server and session across
 * launches (see AppState.restoreSession). `onAutofillSettings`, Android
 * only, opens the system screen for choosing the autofill service.
 */
@Composable
fun RatatoskrApp(
    appIcon: Painter? = null,
    files: PlatformFiles? = null,
    sessionStore: SessionStore? = null,
    onAutofillSettings: (() -> Unit)? = null,
) {
    val appState = remember { AppState(sessionStore) }
    var editingEntry by remember { mutableStateOf<EntryOut?>(null) }
    var showNewEntryDialog by remember { mutableStateOf(false) }
    var showGeneratorDialog by remember { mutableStateOf(false) }
    var generatorTarget by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var importFile by remember { mutableStateOf<PickedFile?>(null) }
    var importErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { appState.restoreSession() }

    // Leaving the vault (Lock, or a lost session) closes the
    // vault's dialogs, so none is left open over the Unlock screen.
    LaunchedEffect(appState.screen) {
        if (appState.screen != Screen.Vault) {
            editingEntry = null
            showNewEntryDialog = false
            showGeneratorDialog = false
            showExportDialog = false
            showChangePasswordDialog = false
            importFile = null
            importErrors = emptyList()
        }
    }

    // Same 2.4s lifetime as the web UI's toast().
    LaunchedEffect(appState.notice) {
        if (appState.notice != null) {
            delay(2400)
            appState.notice = null
        }
    }

    RatatoskrTheme {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize().background(RatatoskrColors.Bg)) {
            when (appState.screen) {
                Screen.ServerConnect -> ServerConnectScreen(appState, appIcon)
                Screen.CheckingServer -> ConnectingScreen(appState)

                Screen.Setup -> SetupScreen(appState)
                Screen.Unlock -> UnlockScreen(appState)
                Screen.AcceptInvite -> AcceptInviteScreen(appState)

                Screen.Vault -> VaultScreen(
                    appState = appState,
                    onNewEntry = { showNewEntryDialog = true },
                    onEditEntry = { editingEntry = it },
                    appIcon = appIcon,
                    onAutofillSettings = onAutofillSettings,
                    onOpenGenerator = {
                        generatorTarget = null
                        showGeneratorDialog = true
                    },
                    onChangePassword = { showChangePasswordDialog = true },
                    onExport = files?.let { { showExportDialog = true } },
                    onImport = files?.let { platformFiles ->
                        {
                            scope.launch {
                                try {
                                    importFile = platformFiles.pickCsvToImport()
                                } catch (e: Exception) {
                                    appState.errorMessage = "Couldn't read that file: ${e.message}"
                                }
                            }
                        }
                    },
                )
            }

            if (showExportDialog && files != null) {
                ExportDialog(appState, files, onDismiss = { showExportDialog = false })
            }

            importFile?.let { file ->
                ImportConfirmDialog(
                    appState = appState,
                    file = file,
                    onDismiss = { importFile = null },
                    onImported = { result ->
                        importFile = null
                        importErrors = result.errors
                    },
                )
            }

            if (importErrors.isNotEmpty()) {
                ImportErrorsDialog(importErrors, onDismiss = { importErrors = emptyList() })
            }

            if (showNewEntryDialog) {
                EntryEditDialog(
                    appState = appState,
                    existing = null,
                    onDismiss = { showNewEntryDialog = false },
                    onOpenGenerator = { onGenerated ->
                        generatorTarget = onGenerated
                        showGeneratorDialog = true
                    },
                )
            }

            editingEntry?.let { entry ->
                EntryEditDialog(
                    appState = appState,
                    existing = entry,
                    onDismiss = { editingEntry = null },
                    onOpenGenerator = { onGenerated ->
                        generatorTarget = onGenerated
                        showGeneratorDialog = true
                    },
                )
            }

            if (showChangePasswordDialog) {
                ChangePasswordDialog(appState, onDismiss = { showChangePasswordDialog = false })
            }

            if (showGeneratorDialog) {
                GeneratorDialog(
                    appState = appState,
                    onDismiss = { showGeneratorDialog = false },
                    onUse = generatorTarget,
                )
            }

            appState.notice?.let { message ->
                Box(modifier = Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
                    Toast(message)
                }
            }
        }
    }
}
