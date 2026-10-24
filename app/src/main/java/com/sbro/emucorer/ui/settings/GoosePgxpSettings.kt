package com.sbro.emucorer.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.sbro.emucorer.core.NativeApp
import com.sbro.emucorer.core.SwanStationCoreOptions

@Composable
internal fun GoosePgxpSettings() {
    var revision by remember { mutableIntStateOf(0) }
    val options = remember { SwanStationCoreOptions.forCategory("goose_pgxp") }
    val values = remember(revision) {
        options.associate { it.key to (NativeApp.getCoreOption(it.key) ?: it.defaultValue) }
    }
    val master = values["swanstation_GPU_PGXPEnable"] == "true"
    val depth = values["swanstation_GPU_PGXPDepthBuffer"] == "true"
    Column(Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PGXP", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface)
        // Store child preferences even while the parent is off.
        options.forEach { option ->
            val depthOption = option.key == "swanstation_GPU_PGXPTransparentDepth" ||
                option.key == "swanstation_GPU_PGXPDepthClearThreshold"
            key(option.key) {
                val enabled = option.key == "swanstation_GPU_PGXPEnable" ||
                    (master && (!depthOption || depth))
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
