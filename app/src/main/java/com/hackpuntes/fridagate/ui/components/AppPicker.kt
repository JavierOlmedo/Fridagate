package com.hackpuntes.fridagate.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hackpuntes.fridagate.utils.InstalledApps

/**
 * Dropdown to pick an installed app, shared by the Extras and Proxy tabs.
 *
 * @param selected      Package name of the selected app, "" for none / all apps
 * @param allAppsOption If set, a first entry with this text selects "" (all apps)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPicker(
    apps: List<InstalledApps.AppInfo>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    label: String = "Select app",
    allAppsOption: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = apps.firstOrNull { it.packageName == selected }?.name ?: selected
    val text = when {
        selected.isEmpty() -> allAppsOption ?: ""
        displayName != selected -> "$displayName\n$selected"
        else -> selected
    }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            value         = text,
            onValueChange = {},
            readOnly      = true,
            label         = { Text(label) },
            placeholder   = { Text("No app selected") },
            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier      = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
            enabled       = enabled,
            maxLines      = 2,
            textStyle     = MaterialTheme.typography.bodyMedium
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 300.dp)
        ) {
            if (allAppsOption != null) {
                DropdownMenuItem(
                    text = { Text(allAppsOption, fontWeight = FontWeight.Medium) },
                    onClick = {
                        onSelect("")
                        expanded = false
                    }
                )
                HorizontalDivider()
            }
            if (apps.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Loading apps...", style = MaterialTheme.typography.bodySmall) },
                    onClick = {}
                )
            } else {
                apps.forEach { app ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(app.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onSelect(app.packageName)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
