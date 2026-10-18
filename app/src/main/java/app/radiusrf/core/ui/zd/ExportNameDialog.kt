package app.radiusrf.core.ui.zd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.radiusrf.core.sessions.ExportNames
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType

@Composable
fun ExportNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZdColors.Surface,
        title = { Text("Export log", style = ZdType.Title, color = ZdColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("File name", style = ZdType.Caption, color = ZdColors.Text3)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            ZdButton("Share", onClick = { onConfirm(ExportNames.clean(name, initialName)) }, height = 40.dp)
        },
        dismissButton = {
            ZdButton("Cancel", onClick = onDismiss, variant = ZdButtonVariant.Ghost, height = 40.dp)
        }
    )
}
