package com.sbro.emucorer.ui.cheats

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sbro.emucorer.R
import com.sbro.emucorer.data.AppPreferences
import com.sbro.emucorer.data.CheatRepository
import com.sbro.emucorer.data.GameItem
import com.sbro.emucorer.data.InstalledRemoteCheat
import com.sbro.emucorer.data.RemoteCheatPack
import com.sbro.emucorer.data.RemoteContentCatalogRepository
import com.sbro.emucorer.data.RemoteContentInstallState
import com.sbro.emucorer.data.SelectedGameIdentity
import com.sbro.emucorer.ui.common.cheatCatalogGameTitleKey
import com.sbro.emucorer.ui.common.rememberRetainedState
import com.sbro.emucorer.ui.theme.neon.neonButtonShape
import com.sbro.emucorer.ui.theme.neon.neonShape
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class IndexedRemoteCheatCatalog(
    val byCrc: Map<String, List<RemoteCheatPack>>,
    val bySerial: Map<String, List<RemoteCheatPack>>,
    val byTitle: Map<String, List<RemoteCheatPack>>
)

internal data class RemoteCheatSelection(
    val matchingPacks: List<RemoteCheatPack>,
    val otherVersionPacks: List<RemoteCheatPack>
)

internal fun indexRemoteCheatCatalog(entries: List<RemoteCheatPack>): IndexedRemoteCheatCatalog {
    val byCrc = linkedMapOf<String, MutableList<RemoteCheatPack>>()
    val bySerial = linkedMapOf<String, MutableList<RemoteCheatPack>>()
    val byTitle = linkedMapOf<String, MutableList<RemoteCheatPack>>()
    entries.forEach { pack ->
        pack.crc.takeIf(String::isNotBlank)?.let { crc ->
            byCrc.getOrPut(crc.uppercase(Locale.US)) { mutableListOf() }.add(pack)
        }
        pack.serials.forEach { serial ->
            bySerial.getOrPut(serial.uppercase(Locale.US)) { mutableListOf() }.add(pack)
        }
        pack.title.cheatCatalogGameTitleKey()
            .takeIf(String::isNotBlank)
            ?.let { key -> byTitle.getOrPut(key) { mutableListOf() }.add(pack) }
    }
    return IndexedRemoteCheatCatalog(byCrc = byCrc, bySerial = bySerial, byTitle = byTitle)
}

internal fun selectRemoteCheatPacks(
    index: IndexedRemoteCheatCatalog,
    serial: String?,
    crc: String?,
    gameTitle: String?
): RemoteCheatSelection {
    val normalizedSerial = serial?.trim()?.uppercase(Locale.US)?.takeIf(String::isNotBlank)
    val normalizedCrc = crc?.trim()?.uppercase(Locale.US)?.takeIf(String::isNotBlank)
    val titleKey = gameTitle.orEmpty().cheatCatalogGameTitleKey()
    val matching = linkedSetOf<RemoteCheatPack>()
    if (normalizedSerial != null) {
        matching += index.bySerial[normalizedSerial].orEmpty()
    }
    if (normalizedCrc != null) {
        matching += index.byCrc[normalizedCrc].orEmpty()
            .filter { pack ->
                pack.serials.isEmpty() || normalizedSerial == null ||
                    pack.serials.any { it.equals(normalizedSerial, ignoreCase = true) }
            }
    }
    if (titleKey.isNotBlank()) {
        matching += index.byTitle[titleKey].orEmpty().filter { it.serials.isEmpty() }
    }
    val titlePacks = if (titleKey.isNotBlank()) index.byTitle[titleKey].orEmpty() else emptyList()
    val ordered = compareBy<RemoteCheatPack>({ it.title.lowercase(Locale.US) }, { it.id })
    return RemoteCheatSelection(
        matchingPacks = matching.sortedWith(ordered),
        otherVersionPacks = titlePacks.filterNot(matching::contains).sortedWith(ordered)
    )
}

@Composable
internal fun CheatOnlineCatalogSection(
    selectedGame: GameItem?,
    identity: SelectedGameIdentity?,
    resolvingIdentity: Boolean,
    gamesLoading: Boolean,
    onInstalled: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val catalogRepository = remember(context) { RemoteContentCatalogRepository(context) }
    val cheatRepository = remember(context) { CheatRepository(context) }
    val installState = remember(context) { RemoteContentInstallState(context) }
    val preferences = remember(context) { AppPreferences(context) }

    // Retained so scrolling the card out of view and back does not reset the
    // loaded catalog, re-run the loaders or replay the entrance animation.
    var packs by rememberRetainedState("cheat_online_catalog.packs", emptyList<RemoteCheatPack>())
    var installed by rememberRetainedState(
        "cheat_online_catalog.installed",
        emptyMap<String, InstalledRemoteCheat>()
    )
    var loading by rememberRetainedState("cheat_online_catalog.loading", true)
    var cached by rememberRetainedState("cheat_online_catalog.cached", false)
    var loadFailed by rememberRetainedState("cheat_online_catalog.load_failed", false)
    var installingPackId by rememberRetainedState<String?>("cheat_online_catalog.installing", null)

    val installSuccessMessage = stringResource(R.string.cheat_catalog_install_success)
    val installFailureMessage = stringResource(R.string.cheat_catalog_install_failed)

    var catalogRevision by remember { mutableStateOf(0) }
    LaunchedEffect(catalogRevision) {
        val loaded = withContext(Dispatchers.IO) {
            val catalog = catalogRepository.loadCheatCatalog(forceRefresh = catalogRevision > 0)
            catalog to installState.installedCheats()
        }
        // A failed refresh (offline, bad response) must not wipe a previously
        // loaded catalog: only replace the packs when the new result is usable.
        if (loaded.first.entries.isNotEmpty() || packs.isEmpty()) {
            packs = loaded.first.entries
            cached = loaded.first.fromCache
            loadFailed = loaded.first.entries.isEmpty()
        }
        installed = loaded.second
        loading = false
        if (loaded.first.cacheHit) {
            val refreshed = withContext(Dispatchers.IO) { catalogRepository.loadCheatCatalog(forceRefresh = true) }
            if (refreshed.entries.isNotEmpty()) {
                packs = refreshed.entries
                cached = refreshed.fromCache
                loadFailed = false
            }
        }
    }

    val serial = identity?.serial
    val crc = identity?.crc
    // The catalog index only depends on the downloaded packs, so it is built
    // off the main thread instead of on every game switch. Retaining it keeps
    // the section rendered while the LazyColumn item is scrolled out of view;
    // it is only cleared when the packs actually change so a stale index can
    // never match the fresh catalog.
    var catalogIndex by rememberRetainedState<IndexedRemoteCheatCatalog?>(
        "cheat_online_catalog.index",
        null
    )
    var indexedPacks by rememberRetainedState<List<RemoteCheatPack>?>(
        "cheat_online_catalog.indexed_packs",
        null
    )
    LaunchedEffect(packs) {
        if (catalogIndex != null && indexedPacks == packs) return@LaunchedEffect
        if (indexedPacks != null && indexedPacks != packs) catalogIndex = null
        val built = withContext(Dispatchers.Default) { indexRemoteCheatCatalog(packs) }
        catalogIndex = built
        indexedPacks = packs
    }
    val selection = remember(catalogIndex, serial, crc, selectedGame?.title) {
        val index = catalogIndex
        if (index == null) {
            RemoteCheatSelection(emptyList(), emptyList())
        } else {
            selectRemoteCheatPacks(
                index = index,
                serial = serial,
                crc = crc,
                gameTitle = selectedGame?.title
            )
        }
    }
    val phase = when {
        // The library can still be loading when the catalog arrives, so keep
        // showing the spinner instead of briefly flashing "no packs for this
        // game" before the selected game is known.
        gamesLoading || loading || resolvingIdentity || (catalogIndex == null && packs.isNotEmpty()) ->
            CheatOnlinePhase.LOADING
        selectedGame == null -> CheatOnlinePhase.NO_GAME
        loadFailed -> CheatOnlinePhase.ERROR
        selection.matchingPacks.isEmpty() && selection.otherVersionPacks.isEmpty() -> CheatOnlinePhase.EMPTY
        else -> CheatOnlinePhase.CONTENT
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            com.sbro.emucorer.ui.common.DukeCatalogSourceEditor("cheats") {
                packs = emptyList()
                loading = true
                cached = false
                loadFailed = false
                catalogRevision++
            }

            Text(
                text = stringResource(R.string.cheat_catalog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.cheat_catalog_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { height -> height / 10 }) togetherWith
                        (fadeOut(tween(140)) + slideOutVertically(tween(140)) { height -> -height / 12 })
                },
                label = "onlineCheatContent"
            ) { state ->
                when (state) {
                    CheatOnlinePhase.LOADING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.content_catalog_loading))
                    }
                    CheatOnlinePhase.NO_GAME, CheatOnlinePhase.EMPTY -> CheatCatalogEmptyState()
                    CheatOnlinePhase.ERROR -> Text(
                        text = stringResource(if (com.sbro.emucorer.data.DukeCatalogSource.get(context, "cheats").isBlank())
                            R.string.duke_catalog_no_source else R.string.duke_catalog_load_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    CheatOnlinePhase.CONTENT -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (selection.matchingPacks.isNotEmpty()) {
                            CheatCatalogSectionTitle(
                                text = stringResource(R.string.content_compatible_game_version_section)
                            )
                            selection.matchingPacks.forEach { pack ->
                                CheatCatalogCard(
                                    pack = pack,
                                    compatible = true,
                                    installed = installed.containsKey(pack.id),
                                    installing = installingPackId == pack.id,
                                    onSource = { runCatching { uriHandler.openUri(pack.sourceUrl) } },
                                    onInstall = {
                                        val targetSerial = serial ?: return@CheatCatalogCard
                                        installingPackId = pack.id
                                        scope.launch {
                                            val success = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    val text = catalogRepository.downloadCheatText(pack)
                                                    val gameKey = when {
                                                        !targetSerial.isBlank() && !crc.isNullOrBlank() ->
                                                            "${targetSerial}_$crc"
                                                        !crc.isNullOrBlank() -> crc
                                                        else -> targetSerial
                                                    }
                                                    val imported = cheatRepository.importCheatFile(
                                                        gameKey = gameKey,
                                                        contents = text,
                                                        enableAllByDefault = false,
                                                        mergeWithExisting = true
                                                    )
                                                    if (imported > 0) {
                                                        preferences.setEnableCheats(true)
                                                        installState.recordCheat(pack, targetSerial, crc)
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }.getOrDefault(false)
                                            }
                                            installingPackId = null
                                            if (success) {
                                                installed = withContext(Dispatchers.IO) {
                                                    installState.installedCheats()
                                                }
                                                onInstalled()
                                            }
                                            Toast.makeText(
                                                context,
                                                if (success) installSuccessMessage else installFailureMessage,
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                )
                            }
                        }
                        if (selection.otherVersionPacks.isNotEmpty()) {
                            CheatCatalogSectionTitle(
                                text = stringResource(R.string.content_other_game_versions_section)
                            )
                            selection.otherVersionPacks.forEach { pack ->
                                CheatCatalogCard(
                                    pack = pack,
                                    compatible = false,
                                    installed = false,
                                    installing = false,
                                    onSource = { runCatching { uriHandler.openUri(pack.sourceUrl) } },
                                    onInstall = {}
                                )
                            }
                        }
                    }
                }
            }
            if (cached) {
                Text(
                    text = stringResource(R.string.content_catalog_cached),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CheatCatalogEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = neonShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier.padding(11.dp).width(22.dp)
                )
            }
            Text(
                text = stringResource(R.string.cheat_catalog_no_packs),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CheatCatalogCard(
    pack: RemoteCheatPack,
    compatible: Boolean,
    installed: Boolean,
    installing: Boolean,
    onSource: () -> Unit,
    onInstall: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pack.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(R.string.cheat_catalog_pack_meta, pack.blockCount, pack.sourceName),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (installed) {
                    Text(
                        text = stringResource(R.string.content_installed),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            CheatCompatibilityBadge(compatible = compatible)
            if (!compatible && pack.serials.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.content_supported_serials, pack.serials.joinToString()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (pack.description.isNotBlank()) {
                Text(
                    text = pack.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stringResource(R.string.content_authors, pack.authors.joinToString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (compatible) {
                Button(
                    shape = neonButtonShape(),
                    onClick = onInstall,
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (installing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = stringResource(
                            if (installed) R.string.content_reinstall else R.string.content_download_install
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            OutlinedButton(
                shape = neonButtonShape(),
                onClick = onSource,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = stringResource(R.string.content_source),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CheatCatalogSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun CheatCompatibilityBadge(compatible: Boolean) {
    val background = if (compatible) {
        Color(0xFF1B6B3A)
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val foreground = if (compatible) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = neonShape(10.dp),
        color = background,
        contentColor = foreground
    ) {
        Text(
            text = stringResource(
                if (compatible) R.string.content_compatible
                else R.string.content_not_compatible
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private enum class CheatOnlinePhase {
    LOADING,
    NO_GAME,
    EMPTY,
    ERROR,
    CONTENT
}
