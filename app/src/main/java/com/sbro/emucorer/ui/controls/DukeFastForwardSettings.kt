package com.sbro.emucorer.ui.controls

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbro.emucorer.R
import com.sbro.emucorer.core.EmulatorBridge
import com.sbro.emucorer.core.CoreRuntime
import com.sbro.emucorer.data.*
import kotlinx.coroutines.launch

@Composable
internal fun DukeFastForwardSettings() {
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context.applicationContext) }
    val library by preferences.customTouchControls.collectAsState(initial = CustomTouchControlLibrary.Empty)
    val speed by preferences.fastForwardSpeed.collectAsState(initial = 2f)
    val scope = rememberCoroutineScope()
    var chooseSpeed by remember { mutableStateOf(false) }
    val buttonName = stringResource(R.string.duke_ff_title)
    val existing = library.controls.firstOrNull { it.actionId == "fast_forward" }
    val control = existing ?: CustomTouchControl(
        id = "duke_fast_forward", name = buttonName, actionId = "fast_forward",
        label = "⏩", content = CustomTouchControlContent.TEXT,
        positionX = 0.88f, positionY = 0.16f, widthDp = 56, heightDp = 56,
        borderColor = 0xFFFFB800.toInt(), enabled = false)
    fun save(updated: CustomTouchControl) {
        CoreRuntime.resetFastForward()
        scope.launch {
            preferences.setCustomTouchControls(library.copy(controls =
                if (existing == null) library.controls + updated
                else library.controls.map { if (it.id == existing.id) updated else it }))
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(buttonName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.duke_ff_show), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            Switch(checked = control.enabled, onCheckedChange = { save(control.copy(enabled = it)) })
        }
        Text(stringResource(R.string.duke_ff_position), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.duke_ff_toggle), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            Switch(checked = control.pressMode == CustomTouchControlPressMode.TOGGLE,
                onCheckedChange = { save(control.copy(pressMode = if (it) CustomTouchControlPressMode.TOGGLE else CustomTouchControlPressMode.HOLD)) })
        }
        Text(stringResource(if (control.pressMode == CustomTouchControlPressMode.TOGGLE) R.string.duke_ff_toggle_help else R.string.duke_ff_hold_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { chooseSpeed = true }) {
            Text(stringResource(R.string.duke_ff_speed) + ": ${speed.toInt()}×")
        }
        Text(stringResource(R.string.duke_ff_audio), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (chooseSpeed) AlertDialog(onDismissRequest = { chooseSpeed = false },
        title = { Text(stringResource(R.string.duke_ff_speed)) },
        text = { Column {
            listOf(2, 3, 4, 6, 8).forEach { value ->
                TextButton(onClick = {
                    scope.launch {
                        preferences.setFastForwardSpeed(value.toFloat())
                        EmulatorBridge.setFastForwardSpeed(value.toFloat())
                    }
                    chooseSpeed = false
                }) { Text(if (speed.toInt() == value) "✓ ${value}×" else "${value}×") }
            }
        } }, confirmButton = {})
}
