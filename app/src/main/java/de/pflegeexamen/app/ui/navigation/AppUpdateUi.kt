package de.pflegeexamen.app.ui.navigation

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pflegeexamen.app.data.update.AppUpdateManager
import de.pflegeexamen.app.data.update.AppUpdateState
import de.pflegeexamen.app.data.update.InstallResult
import kotlinx.coroutines.launch

/** Shows update after automatic check; installation always requires a user action. */
@Composable
fun AppUpdatePrompt(manager: AppUpdateManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    val code = when (val s = state) {
        is AppUpdateState.Ready -> s.code
        is AppUpdateState.WaitingForWifi -> s.code
        else -> null
    }
    var dismissed by rememberSaveable(code) { mutableStateOf(false) }
    var info by rememberSaveable(code) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (code != null && !dismissed) {
        UpdatePromptContent(state, info, busy, onDismiss = { dismissed = true }, onInstall = {
            busy = true
            scope.launch {
                try {
                    when (manager.install()) {
                        InstallResult.Started -> dismissed = true
                        InstallResult.PermissionNeeded -> info = "Bitte die Installation aus dieser Quelle erlauben, zurückkehren und erneut auf Installieren tippen."
                        InstallResult.Unavailable -> info = "Die Installation konnte nicht gestartet werden. Bitte später erneut versuchen."
                    }
                } finally { busy = false }
            }
        })
    }
}

@Composable
internal fun UpdatePromptContent(state: AppUpdateState, info: String, busy: Boolean, onDismiss: () -> Unit, onInstall: () -> Unit) {
    val version = when (state) {
        is AppUpdateState.Ready -> state.version
        is AppUpdateState.WaitingForWifi -> state.version
        else -> return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("App-Update $version") },
        text = {
            Text(if (state is AppUpdateState.Ready)
                "Das signierte Update wurde heruntergeladen und geprüft. Android fragt vor der Installation nach deiner Bestätigung. App nicht deinstallieren. $info"
            else "Ein Update ist verfügbar. Es wird automatisch heruntergeladen, sobald die App mit einer nicht getakteten Verbindung geöffnet ist. $info")
        },
        confirmButton = {
            if (state is AppUpdateState.Ready) TextButton(onClick = onInstall, enabled = !busy) { Text("Installieren") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Später") } })
}

@Composable
internal fun AppUpdateSettings(manager: AppUpdateManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Text("App-Version und Updates", style = MaterialTheme.typography.titleLarge)
    Text("App-Updates werden beim Öffnen und regelmäßig während der Nutzung geprüft. Der Download erfolgt automatisch über eine nicht getaktete Verbindung. Android verlangt eine Bestätigung der Installation.")
    Text(when (val s = state) {
        AppUpdateState.Checking -> "App-Update wird geprüft …"
        AppUpdateState.Current -> "Kein neueres öffentliches App-Update gefunden."
        AppUpdateState.Unavailable -> "App-Updateprüfung nicht verfügbar. Bitte später erneut versuchen."
        is AppUpdateState.WaitingForWifi -> "Version ${s.version} wartet auf eine nicht getaktete Verbindung."
        is AppUpdateState.Downloading -> "Version ${s.version} wird heruntergeladen …"
        is AppUpdateState.Ready -> "Version ${s.version} ist zum Installieren bereit."
    })
    OutlinedButton(onClick = { manager.checkNow() }, enabled = state !is AppUpdateState.Downloading && state != AppUpdateState.Checking) {
        Text("App-Update erneut prüfen")
    }
    if (state is AppUpdateState.Ready) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    info = when (manager.install()) {
                        InstallResult.Started -> "Android-Installation gestartet."
                        InstallResult.PermissionNeeded -> "Installation aus dieser Quelle erlauben, zurückkehren und erneut auf Installieren tippen."
                        InstallResult.Unavailable -> "Installation konnte nicht gestartet werden."
                    }
                } finally { busy = false }
            }
        }) { Text("App-Update installieren") }
    }
    if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
}
