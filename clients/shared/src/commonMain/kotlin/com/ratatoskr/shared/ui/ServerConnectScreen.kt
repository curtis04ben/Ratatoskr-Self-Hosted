package com.ratatoskr.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.ratatoskr.shared.state.AppState
import kotlinx.coroutines.launch

/**
 * The very first screen a native client shows. It has no equivalent in the
 * web UI, which is always already pointed at one server by virtue of being
 * served from it. This is the piece unique to "enter a server address"
 * that every native client phase in the dev plan calls for.
 */
@Composable
fun ServerConnectScreen(appState: AppState, appIcon: Painter? = null) {
    // Keyed on serverUrl so a saved server that couldn't be reached at
    // launch (AppState.restoreSession) shows up here, ready to retry.
    var url by remember(appState.serverUrl) { mutableStateOf(appState.serverUrl) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            appIcon?.let { BrandEmblem(it, modifier = Modifier.padding(bottom = 4.dp)) }

            Text(
                "Ratatoskr",
                style = MaterialTheme.typography.headlineMedium,
                color = RatatoskrColors.Text,
            )
            Text(
                "The messenger who runs the tree, end to end.",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = RatatoskrColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )

            RatatoskrTextField(
                value = url,
                onValueChange = { url = it },
                label = "Server address",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "e.g. 192.168.1.50:8000. http:// is assumed if you don't specify",
                style = MaterialTheme.typography.bodySmall,
                color = RatatoskrColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            // Plain HTTP is allowed (homelab servers rarely have TLS), but
            // the master password crosses the network, so say so.
            if (url.isNotBlank() && !url.trim().startsWith("https://", ignoreCase = true)) {
                Text(
                    "Not HTTPS: your master password is sent unencrypted unless the connection " +
                        "itself is private, e.g. over Tailscale or your home network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RatatoskrColors.BrassBright,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            if (appState.isLoading) {
                CircularProgressIndicator(color = RatatoskrColors.Brass, modifier = Modifier.padding(8.dp))
            } else {
                PrimaryButton(
                    text = "Connect",
                    onClick = { scope.launch { appState.connectToServer(url) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = url.isNotBlank(),
                )
            }

            ErrorText(appState.errorMessage, modifier = Modifier.fillMaxWidth())
        }
    }
}
