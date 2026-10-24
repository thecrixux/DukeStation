package com.sbro.emucorer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.sbro.emucorer.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.sbro.emucorer.core.GooseCoreOptions
import com.sbro.emucorer.core.NativeApp

@Composable
internal fun GooseCoreSettings(
    category: String,
    title: String,
    appValues: Map<String, String>,
    setAppValue: (String, String) -> Unit,
) {
    val stretchLabel = stringResource(R.string.duke_display_stretch)
    fun choiceLabel(value: String, label: String): String = if (value == "Stretch") stretchLabel else label
    var revision by remember(category) { mutableIntStateOf(0) }
    var selected by remember(category) { mutableStateOf<GooseCoreOptions.Entry?>(null) }
    val entries = remember(category) { GooseCoreOptions.forCategory(category) }
    fun value(entry: GooseCoreOptions.Entry): String = appValues[entry.nativeKey]
        ?: NativeApp.getCoreOption(entry.option.key) ?: entry.option.defaultValue
    fun current(suffix: String): String = GooseCoreOptions.entries.firstOrNull {
        it.nativeKey == "goosestation_$suffix"
    }?.let { value(it) } ?: ""
    fun save(entry: GooseCoreOptions.Entry, value: String) {
        if (entry.nativeKey in appValues) setAppValue(entry.nativeKey, value)
        else NativeApp.setCoreOption(entry.option.key, value)
        revision++
    }
    // Reading revision invalidates this composition after synchronous core preference writes.
    @Suppress("UNUSED_VARIABLE") val observedRevision = revision
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        if (category == "bios") {
            Text("Import BIOS files using the BIOS manager. Regional selections use files available in the system directory. Restart the game after changing BIOS.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entries.forEach { entry ->
            val option = entry.option
            val enabled = when (entry.nativeKey) {
                "goosestation_internal_runahead_analog" -> current("internal_runahead") != "Disabled"
                "goosestation_texture_replacement_enable" -> current("gpu_texture_cache") == "true"
                else -> true
            }
            val boolean = option.choices.map { it.value }.toSet() == setOf("true", "false")
            key(entry.nativeKey) {
                Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)
                    .clickable(enabled = enabled && !boolean) { selected = entry }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(option.label, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        if (boolean) Switch(checked = value(entry) == "true", enabled = enabled,
                            onCheckedChange = { save(entry, it.toString()) })
                    }
                    Text(option.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!boolean) TextButton(enabled = enabled, onClick = { selected = entry }) {
                        Text(option.choices.firstOrNull { it.value == value(entry) }?.let { choiceLabel(it.value, it.label) } ?: value(entry))
                    }
                }
            }
        }
    }
    selected?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text(entry.option.label) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    items(entry.option.choices, key = { it.value }) { choice ->
                        Row(Modifier.fillMaxWidth().clickable {
                            save(entry, choice.value)
                            selected = null
                        }.padding(vertical = 8.dp)) {
                            RadioButton(selected = value(entry) == choice.value, onClick = null)
                            Text(choiceLabel(choice.value, choice.label), modifier = Modifier.padding(start = 12.dp),
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
}
