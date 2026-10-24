package com.sbro.emucorer.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sbro.emucorer.R
import com.sbro.emucorer.core.EmulatorDataLocation
import com.sbro.emucorer.ui.theme.neon.neonShape

@Composable
fun EmulatorDataLocationDialog(
    selectedLocation: EmulatorDataLocation?,
    sdCardAvailable: Boolean,
    onSelect: (EmulatorDataLocation) -> Unit,
    onDismiss: () -> Unit,
    onCustomPath: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun showError() = android.widget.Toast.makeText(context,
        R.string.emulator_data_location_error, android.widget.Toast.LENGTH_LONG).show()
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = runCatching {
                require(uri.authority == "com.android.externalstorage.documents")
                val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
                val pieces = id.split(":", limit = 2)
                val volume = if (pieces[0].equals("primary", ignoreCase = true))
                    android.os.Environment.getExternalStorageDirectory()
                else java.io.File("/storage", pieces[0])
                val root = volume.canonicalFile
                val chosen = java.io.File(root, pieces.getOrElse(1) { "" }).canonicalFile
                require(chosen.path == root.path || chosen.path.startsWith(root.path + "/"))
                chosen.path
            }.getOrNull()
            if (path == null) showError() else onCustomPath(path)
        }
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager())
            folderPicker.launch(null)
        else showError()
    }
    val legacyPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) folderPicker.launch(null) else showError() }
    fun chooseFolder() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (android.os.Environment.isExternalStorageManager()) folderPicker.launch(null)
            else runCatching {
                permissionLauncher.launch(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + context.packageName)))
            }.onFailure { showError() }
        } else legacyPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    SettingsStyledDialog(
        title = stringResource(R.string.emulator_data_location_title),
        eyebrow = stringResource(R.string.settings_emulator_data_path),
        icon = Icons.Rounded.SdStorage,
        onDismissRequest = onDismiss,
    ) {
        Text(
            text = stringResource(R.string.duke_storage_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
        EmulatorDataLocationOption(
            icon = Icons.Rounded.PhoneAndroid,
            title = stringResource(R.string.emulator_data_location_internal),
            description = stringResource(R.string.emulator_data_location_internal_description),
            selected = selectedLocation == EmulatorDataLocation.INTERNAL,
            enabled = true,
            onClick = { onSelect(EmulatorDataLocation.INTERNAL) }
        )
        EmulatorDataLocationOption(
            icon = Icons.Rounded.SdStorage,
            title = stringResource(R.string.duke_choose_folder),
            description = stringResource(R.string.duke_choose_folder_description),
            selected = selectedLocation == null,
            enabled = true,
            onClick = { chooseFolder() }
        )
        val cancelInteractionSource = remember { MutableInteractionSource() }
        val cancelShape = neonShape(18.dp)
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .tvGamepadFocusableCard(
                    shape = cancelShape,
                    interactionSource = cancelInteractionSource,
                    addFocusTarget = false
                ),
            interactionSource = cancelInteractionSource,
            shape = cancelShape
        ) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun EmulatorDataLocationOption(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = neonShape(18.dp)
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val contentAlpha = if (enabled) 1f else 0.52f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .tvGamepadFocusableCard(
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
        },
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                )
            }
            if (selected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
