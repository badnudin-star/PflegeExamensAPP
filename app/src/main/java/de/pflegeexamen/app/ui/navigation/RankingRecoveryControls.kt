package de.pflegeexamen.app.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import de.pflegeexamen.app.data.quiz.RecoveryExport
import de.pflegeexamen.app.data.quiz.RecoveryRestore
import kotlinx.coroutines.launch

/** Sensitive values intentionally live only in ordinary remember state, never rememberSaveable or logs. */
@Composable
internal fun RankingRecoveryControls(
    service: RankedParticipationService,
    canExport: Boolean,
    canRestore: Boolean,
    busy: Boolean,
    setBusy: (Boolean) -> Unit,
    onRestored: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var warnBeforeExport by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var restoreDialog by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var dialogMessage by remember { mutableStateOf<String?>(null) }
    val secure = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canExport) OutlinedButton(enabled = !busy, onClick = { message = null; warnBeforeExport = true }) { Text("Sicherungscode anzeigen") }
        if (canRestore) OutlinedButton(enabled = !busy, onClick = { message = null; entered = ""; dialogMessage = null; restoreDialog = true }) { Text("Zugang wiederherstellen") }
        message?.let { Text(it) }
    }
    if (warnBeforeExport) AlertDialog(
        onDismissRequest = { warnBeforeExport = false }, title = { Text("Sicherungscode schützen") },
        text = { Text("Der Code ist dein vollständiger Zugang: Wer ihn kennt, kann dein Ranglistenkonto benutzen und löschen. Niemals teilen oder in einen Chat eingeben. Bewahre ihn sicher und getrennt vom Gerät auf. Verwende denselben Zugang nicht gleichzeitig auf mehreren Geräten und kläre offene Abgaben vor einem Gerätewechsel.") },
        confirmButton = { TextButton(onClick = {
            warnBeforeExport = false; setBusy(true)
            scope.launch {
                try {
                    when (val result = service.exportRecovery()) {
                        is RecoveryExport.Ready -> code = result.code
                        RecoveryExport.NoAccount -> message = "Kein Zugang auf diesem Gerät vorhanden."
                        RecoveryExport.StorageUnavailable -> message = "Der Zugangsspeicher ist nicht verfügbar."
                        RecoveryExport.DeletionPending -> message = "Eine Kontolöschung ist offen; der Code kann nicht angezeigt werden."
                        RecoveryExport.NotConfigured -> message = "Der Dienst ist nicht eingerichtet."
                    }
                } finally { setBusy(false) }
            }
        }) { Text("Code anzeigen") } },
        dismissButton = { TextButton(onClick = { warnBeforeExport = false }) { Text("Abbrechen") } }
    )
    code?.let { secret ->
        AlertDialog(
            properties = secure,
            onDismissRequest = { code = null }, title = { Text("Dein Sicherungscode") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Geheim halten und sicher außerhalb dieses Geräts aufbewahren. Der Code wird nicht automatisch gesichert.")
                Text(secret)
            } },
            confirmButton = { TextButton(onClick = { code = null }) { Text("Schließen") } }
        )
    }
    if (restoreDialog) AlertDialog(
        properties = secure,
        onDismissRequest = { if (!busy) { restoreDialog = false; entered = ""; dialogMessage = null } },
        title = { Text("Zugang wiederherstellen") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gib deinen Sicherungscode nur hier ein, niemals in einem Chat. Verwende eine vertrauenswürdige Tastatur. Ein vorhandener anderer Zugang wird nicht überschrieben. Offene Abgaben vom alten Gerät werden nicht übertragen.")
            OutlinedTextField(value = entered, onValueChange = { if (it.length <= 128) entered = it }, enabled = !busy,
                label = { Text("Geheimer Sicherungscode") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation())
            dialogMessage?.let { Text(it) }
        } },
        confirmButton = { TextButton(enabled = !busy && entered.isNotBlank(), onClick = {
            setBusy(true); dialogMessage = null
            scope.launch {
                try {
                    when (val result = service.restoreRecovery(entered)) {
                        is RecoveryRestore.Restored -> { entered = ""; restoreDialog = false; message = "Zugang für ${result.username} gespeichert. Der Kontostatus war zum Prüfzeitpunkt bestätigt."; onRestored(result.username) }
                        RecoveryRestore.InvalidCode -> dialogMessage = "Der Code hat nicht das erwartete Format."
                        RecoveryRestore.ExistingDifferentAccount -> dialogMessage = "Auf diesem Gerät besteht bereits ein anderer Zugang; er wurde nicht überschrieben."
                        RecoveryRestore.PendingAccountMismatch -> dialogMessage = "Eine ausstehende Abgabe gehört zu einem anderen Zugang. Nichts wurde überschrieben."
                        RecoveryRestore.StorageUnavailable -> dialogMessage = "Der Zugangsspeicher ist nicht verfügbar. Nichts wurde überschrieben."
                        RecoveryRestore.Unavailable -> dialogMessage = "Der Zugang konnte nicht bestätigt werden. Prüfe die Verbindung und den Code."
                        RecoveryRestore.DeletionPending -> dialogMessage = "Eine Kontolöschung ist offen. Zuerst die Löschung abschließen."
                        RecoveryRestore.NotConfigured -> dialogMessage = "Der Dienst ist nicht eingerichtet."
                    }
                } finally { setBusy(false) }
            }
        }) { Text("Wiederherstellen") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { restoreDialog = false; entered = ""; dialogMessage = null }) { Text("Abbrechen") } }
    )
}
