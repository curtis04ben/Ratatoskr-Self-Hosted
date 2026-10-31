package com.ratatoskr.android

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.ratatoskr.shared.ui.RatatoskrApp
import com.ratatoskr.shared.ui.RatatoskrColors

/**
 * Android entry point, the counterpart of desktop's Main.kt. All screens
 * and logic are the shared RatatoskrApp; this supplies the Android pieces:
 * the Keystore session store, Storage Access Framework file pickers, the
 * app icon, window setup, and the way into the autofill settings.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep vault contents out of screenshots, screen recordings and the
        // recent-apps thumbnail. Compose dialogs inherit this. Skipped in
        // debug builds only, so development screenshots aren't black.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Draw behind the system bars (light icons on the dark theme);
        // safeDrawingPadding below keeps content clear of them and of the
        // keyboard.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val sessionStore = KeystoreSessionStore(applicationContext)
        val files = AndroidFiles(this)

        setContent {
            Box(modifier = Modifier.fillMaxSize().background(RatatoskrColors.Bg).safeDrawingPadding()) {
                RatatoskrApp(
                    appIcon = painterResource(R.drawable.ratatoskr_icon),
                    files = files,
                    sessionStore = sessionStore,
                    onAutofillSettings = ::openAutofillSettings,
                )
            }
        }
    }

    /** Asks the system to make Ratatoskr the autofill service. That
     * screen closes itself immediately if it already is, so say so. */
    private fun openAutofillSettings() {
        if (getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true) {
            Toast.makeText(this, "${getString(R.string.app_name)} is your autofill service", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            Toast.makeText(this, "Choose ${getString(R.string.app_name)} under Settings, Passwords and autofill", Toast.LENGTH_LONG).show()
        }
    }
}
