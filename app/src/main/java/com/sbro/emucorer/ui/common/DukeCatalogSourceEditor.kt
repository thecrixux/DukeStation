package com.sbro.emucorer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbro.emucorer.R
import com.sbro.emucorer.data.DukeCatalogSource

@Composable
fun DukeCatalogSourceEditor(kind: String, onChanged: () -> Unit) {
    val context = LocalContext.current
    var source by remember { mutableStateOf(DukeCatalogSource.get(context, kind)) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(source) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (source.isBlank()) Text(
            stringResource(R.string.duke_catalog_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row {
            TextButton(onClick = { draft = source; editing = true }) {
                Text(stringResource(R.string.duke_catalog_source))
            }
            if (source.isNotBlank()) TextButton(onClick = onChanged) {
                Text(stringResource(R.string.duke_catalog_retry))
            }
        }
    }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(stringResource(R.string.duke_catalog_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.duke_catalog_source_help))
                OutlinedTextField(value = draft, onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("HTTPS URL") }, isError = !DukeCatalogSource.isValid(draft))
            }
        },
        confirmButton = {
            TextButton(enabled = DukeCatalogSource.isValid(draft), onClick = {
                DukeCatalogSource.set(context, kind, draft)
                source = draft.trim(); editing = false; onChanged()
            }) { Text(stringResource(R.string.duke_catalog_save)) }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.duke_catalog_cancel)) } }
    )
}
