package com.sbro.emucorer.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.sbro.emucorer.core.NativeApp
import com.sbro.emucorer.core.SwanStationCoreOptions

@Composable
internal fun GooseSkipSettings() {
    var revision by remember { mutableIntStateOf(0) }
    val options = remember { SwanStationCoreOptions.forCategory("goose_skip") }
    val values = remember(revision) {
        options.associate { it.key to (NativeApp.getCoreOption(it.key) ?: it.defaultValue) }
    }
    val master = values["goosestation_skip_engine"] == "true"
    val cd = values["goosestation_skip_cd_loads"] == "true"
    Column(Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Skip Engine / Trainer", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface)
        // Store child preferences even while the parent is off.
        options.forEach { option ->
            val sensitivity = option.key == "goosestation_skip_cd_load_niceness"
            if (!sensitivity || (master && cd)) {
                val enabled = option.key == "goosestation_skip_engine" || master
                Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(option.label, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        if (option.isBooleanToggle) {
                            Switch(checked = values[option.key] == "true", enabled = enabled,
                                onCheckedChange = {
                                    NativeApp.setCoreOption(option.key, it.toString())
                                    revision++
                                })
                        }
                    }
                    Text(option.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!option.isBooleanToggle) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(enabled = enabled, onClick = { expanded = true }) {
                                Text(option.choices.firstOrNull { it.value == values[option.key] }?.label
                                    ?: option.defaultValue)
                            }
                            DropdownMenu(expanded = expanded && enabled,
                                onDismissRequest = { expanded = false }) {
                                option.choices.forEach { choice ->
                                    DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                        NativeApp.setCoreOption(option.key, choice.value)
                                        revision++
                                        expanded = false
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
