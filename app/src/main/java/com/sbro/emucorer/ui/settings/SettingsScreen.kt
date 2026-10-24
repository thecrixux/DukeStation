package com.sbro.emucorer.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StayPrimaryPortrait
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Texture
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.ArrowLeft
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbro.emucorer.R
import com.sbro.emucorer.core.AndroidGyroscopeInput
import com.sbro.emucorer.core.AudioDefaults
import com.sbro.emucorer.core.DocumentPathResolver
import com.sbro.emucorer.core.EmulatorBridge
import com.sbro.emucorer.core.EmulatorStorage
import com.sbro.emucorer.core.GamepadManager
import com.sbro.emucorer.core.NativeApp
import com.sbro.emucorer.core.buildUpscaleOptions
import com.sbro.emucorer.core.upscaleKeyToMultiplier
import com.sbro.emucorer.core.upscaleMultiplierKey
import com.sbro.emucorer.core.SwanStationCoreOptions
import com.sbro.emucorer.core.SwanStationCoreOptionStrings
import com.sbro.emucorer.core.LocalTvUiEnvironment
import com.sbro.emucorer.core.PerformanceProfiles
import com.sbro.emucorer.core.RendererDefaults
import com.sbro.emucorer.core.TvInterfaceMode
import com.sbro.emucorer.core.TvUiPolicy
import com.sbro.emucorer.data.AppFontChoice
import com.sbro.emucorer.data.AppPreferences
import com.sbro.emucorer.data.AppPreferences.Companion.FPS_OVERLAY_MODE_DETAILED
import com.sbro.emucorer.data.AppPreferences.Companion.FPS_OVERLAY_MODE_SIMPLE
import com.sbro.emucorer.data.CheatRepository
import com.sbro.emucorer.data.CoverArtRepository
import com.sbro.emucorer.data.CustomThemeConfig
import com.sbro.emucorer.data.CustomThemeLibrary
import com.sbro.emucorer.data.CustomTouchControlLibrary
import com.sbro.emucorer.data.DisplayCrop
import com.sbro.emucorer.data.DrawerItemId
import com.sbro.emucorer.data.DrawerVisualStyle
import com.sbro.emucorer.data.EmulationSideArtwork
import com.sbro.emucorer.data.EmulationSideArtworkRepository
import com.sbro.emucorer.data.GameMenuLayoutStyle
import com.sbro.emucorer.data.GameMenuSectionId
import com.sbro.emucorer.data.GameMenuTabId
import com.sbro.emucorer.data.HomeBackgroundPreset
import com.sbro.emucorer.data.HomeBackgroundRepository
import com.sbro.emucorer.data.HomeBackgroundType
import com.sbro.emucorer.data.MemoryCardRepository
import com.sbro.emucorer.data.OverlayLayoutSnapshot
import com.sbro.emucorer.data.PatchDatabaseDownloader
import com.sbro.emucorer.data.PatchDatabaseInstallStage
import com.sbro.emucorer.data.PerGameSettingsRepository
import com.sbro.emucorer.data.PerformanceOverlayMetrics
import com.sbro.emucorer.data.RetroArchShaderPreset
import com.sbro.emucorer.data.SettingsBackupRepository
import com.sbro.emucorer.data.SettingsSnapshot
import com.sbro.emucorer.data.ShaderPackInstallStage
import com.sbro.emucorer.data.TouchControlPressEffect
import com.sbro.emucorer.data.TouchControlVisualStyle
import com.sbro.emucorer.data.formatDownloadBytes
import com.sbro.emucorer.ui.common.EmulationSideArtworkOverlay
import com.sbro.emucorer.ui.common.EmulationSideArtworkThumbnail
import com.sbro.emucorer.ui.common.EmulatorDataLocationDialog
import com.sbro.emucorer.ui.common.GameCoverAspectRatio
import com.sbro.emucorer.ui.common.NavigationBackButton
import com.sbro.emucorer.ui.common.ProvideGamepadShoulderActions
import com.sbro.emucorer.ui.common.RequestFocusOnResume
import com.sbro.emucorer.ui.common.ScreenTopBar
import com.sbro.emucorer.ui.common.ScrollableFilterTabRow
import com.sbro.emucorer.ui.common.SettingHelpButton
import com.sbro.emucorer.ui.common.SettingsStyledDialog
import com.sbro.emucorer.ui.common.TvStoragePickerHost
import com.sbro.emucorer.ui.common.TvStorageRequest
import com.sbro.emucorer.ui.common.VectorAnalogStick
import com.sbro.emucorer.ui.common.VectorOverlayButton
import com.sbro.emucorer.ui.common.appScreenTopPadding
import com.sbro.emucorer.ui.common.calculateSideArtworkPreviewLayout
import com.sbro.emucorer.ui.common.gamepadFocusableCard
import com.sbro.emucorer.ui.common.navigationBarsHorizontalPaddingValues
import com.sbro.emucorer.ui.common.rememberDebouncedClick
import com.sbro.emucorer.ui.common.skipGamepadTextFieldFocus
import com.sbro.emucorer.ui.common.tvFocusGroup
import com.sbro.emucorer.ui.common.tvGamepadFocusableCard
import com.sbro.emucorer.ui.customization.HomeBackgroundMedia
import com.sbro.emucorer.ui.home.calculateHomeGridColumnCount
import com.sbro.emucorer.ui.theme.ScreenHorizontalPadding
import com.sbro.emucorer.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.sbro.emucorer.ui.common.AppAlertDialog as AlertDialog
import com.sbro.emucorer.ui.theme.neon.LocalNeonTheme
import com.sbro.emucorer.ui.theme.neon.NeonBlack
import com.sbro.emucorer.ui.theme.neon.NeonBlue
import com.sbro.emucorer.ui.theme.neon.NeonRed
import com.sbro.emucorer.ui.theme.neon.NeonYellow
import com.sbro.emucorer.ui.theme.neon.neonAccentColor
import com.sbro.emucorer.ui.theme.neon.neonChipShape
import com.sbro.emucorer.ui.theme.neon.neonCornerAccent
import com.sbro.emucorer.ui.theme.neon.neonShape
import com.sbro.emucorer.ui.theme.neon.neonShapeCorners
import com.sbro.emucorer.BuildConfig
import java.util.Locale

private enum class SettingsTab {
    General, Graphics, Patches, Controls, Emulation, System, BIOS, Video, Shaders, CDROM, TextureReplacements, Hacks, SkipEngine, PGXP, Audio, Library, Customization, GameMenu, About
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialTab: String = "general",
    onBackClick: (() -> Unit)? = null,
    onOpenLanguageScreen: (() -> Unit)? = null,
    onOpenMemoryCardManager: (() -> Unit)? = null,
    onOpenGameDbBrowser: (() -> Unit)? = null,
    onOpenControlsLayoutEditor: (() -> Unit)? = null,
    onOpenThemeManager: (() -> Unit)? = null,
    onOpenTouchControlCreator: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val gamepadDeviceAssignments by GamepadManager.gamepadDeviceAssignmentsState.collectAsState()
    val ignoredGamepadDevices by GamepadManager.ignoredGamepadDevicesState.collectAsState()
    val context = LocalContext.current
    val tvUiEnabled = LocalTvUiEnvironment.current.enabled
    val topInset = appScreenTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val horizontalSystemBarPadding = navigationBarsHorizontalPaddingValues()
    var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab.toSettingsTab()) }
    val pendingGamepadActionId = remember { mutableStateOf<String?>(null) }
    var pendingGamepadPadIndex by rememberSaveable { mutableIntStateOf(0) }
    var showGamepadAssignmentDialog by rememberSaveable { mutableStateOf(false) }
    var showTopBarMenu by remember { mutableStateOf(false) }
    val showResetAllSettingsDialog = remember { mutableStateOf(false) }
    var showBackupExportDialog by rememberSaveable { mutableStateOf(false) }
    var includeSaveStatesInBackup by rememberSaveable { mutableStateOf(false) }
    val showCoverUrlDialog = remember { mutableStateOf(false) }
    var showClearCoverCacheDialog by rememberSaveable { mutableStateOf(false) }
    val showBiosDialog = remember { mutableStateOf(false) }
    var showEmulatorDataLocationDialog by remember { mutableStateOf(false) }
    val pendingCoverUrl = remember { mutableStateOf("") }
    val showPatchUrlDialog = remember { mutableStateOf(false) }
    val pendingPatchUrl = remember { mutableStateOf("") }
    var searchEnabled by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedTabFocusRequester = remember { FocusRequester() }
    val shouldRequestGamepadFocus = tvUiEnabled || remember { GamepadManager.isGamepadConnected() }
    val scope = rememberCoroutineScope()
    val backupRepository = remember(context) {
        SettingsBackupRepository(
            context = context,
            preferences = AppPreferences(context),
            perGameSettingsRepository = PerGameSettingsRepository(context),
            cheatRepository = CheatRepository(context)
        )
    }
    val backupExportSuccessMessage = stringResource(R.string.settings_backup_export_success)
    val backupExportFailureMessage = stringResource(R.string.settings_backup_export_failed)
    val backupRestoreSuccessMessage = stringResource(R.string.settings_backup_restore_success)
    val backupRestoreFailureMessage = stringResource(R.string.settings_backup_restore_failed)
    val coverUrlCopiedMessage = stringResource(R.string.settings_cover_download_url_copied)
    val coverUrlInvalidMessage = stringResource(R.string.settings_cover_download_url_invalid)
    val coverCacheClearedMessage = stringResource(R.string.settings_clear_cover_cache_success)
    val coverCachePartiallyClearedMessage = stringResource(R.string.settings_clear_cover_cache_partial)
    stringResource(R.string.settings_not_set)
    val settingsScrollState = rememberScrollState()
    val customizationMessage = uiState.customizationMessageResId?.let { stringResource(it) }
    LaunchedEffect(customizationMessage) {
        val message = customizationMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearCustomizationMessage()
    }
    val shaderPackMessage = uiState.shaderPackMessageResId?.let { stringResource(it) }
    LaunchedEffect(shaderPackMessage) {
        val message = shaderPackMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearShaderPackMessage()
    }
    val patchDatabaseMessage = uiState.patchDatabaseMessageResId?.let { resId ->
        val count = uiState.patchDatabaseMessageCount
        if (count != null) stringResource(resId, count) else stringResource(resId)
    }
    LaunchedEffect(patchDatabaseMessage) {
        val message = patchDatabaseMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearPatchDatabaseMessage()
    }
    val patchUrlInvalidMessage = stringResource(R.string.settings_patches_custom_url_invalid)

    if (!uiState.isLoaded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val biosPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let(viewModel::setBiosPath) }

    val gamePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let(viewModel::setGamePath) }

    val homeBackgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::installHomeBackground) }

    val sideArtworkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::installEmulationSideArtwork) }

    val customFontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::installCustomFont) }

    val shaderPackPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::importShaderPack) }

    var tvStorageRequest by remember { mutableStateOf<TvStorageRequest?>(null) }
    TvStoragePickerHost(
        request = tvStorageRequest,
        onDismiss = { tvStorageRequest = null },
        onBiosSelected = viewModel::setBiosPath,
        onGameFolderSelected = viewModel::setGamePath
    )
    val launchBiosPicker = rememberDebouncedClick(
        onClick = {
            if (tvUiEnabled) tvStorageRequest = TvStorageRequest.BIOS_FILE
            else biosPicker.launch(null)
        }
    )
    val openBiosDialog = rememberDebouncedClick(onClick = { showBiosDialog.value = true })
    val launchGamePicker = rememberDebouncedClick(
        onClick = {
            if (tvUiEnabled) tvStorageRequest = TvStorageRequest.GAME_FOLDER
            else gamePicker.launch(null)
        }
    )
    val openEmulatorDataLocationDialog = rememberDebouncedClick(
        onClick = {
            viewModel.refreshEmulatorDataLocations()
            showEmulatorDataLocationDialog = true
        }
    )
    val openLanguageSheet = rememberDebouncedClick(onClick = { onOpenLanguageScreen?.invoke() })
    val settingsTabs = remember { SettingsTab.entries.toList() }
    fun selectRelativeTab(offset: Int) {
        val currentIndex = settingsTabs.indexOf(selectedTab).coerceAtLeast(0)
        selectedTab = settingsTabs[(currentIndex + offset + settingsTabs.size) % settingsTabs.size]
        searchEnabled = false
        searchQuery = ""
    }

    if (showEmulatorDataLocationDialog) {
        EmulatorDataLocationDialog(
            selectedLocation = EmulatorStorage.selectedStandardLocation(
                uiState.emulatorDataPath,
                uiState.sdCardDataPath
            ),
            sdCardAvailable = uiState.sdCardDataPath != null,
            onSelect = { location ->
                showEmulatorDataLocationDialog = false
                viewModel.setEmulatorDataLocation(location)
            },
            onCustomPath = { path ->
                showEmulatorDataLocationDialog = false
                viewModel.setCustomEmulatorDataPath(path)
            },
            onDismiss = { showEmulatorDataLocationDialog = false }
        )
    }

    LaunchedEffect(selectedTab, shouldRequestGamepadFocus) {
        if (shouldRequestGamepadFocus) {
            selectedTabFocusRequester.requestFocus()
        }
    }
    RequestFocusOnResume(
        focusRequester = selectedTabFocusRequester,
        enabled = shouldRequestGamepadFocus
    )
    DisposableEffect(pendingGamepadActionId.value) {
        val actionId = pendingGamepadActionId.value
        if (actionId != null) {
            GamepadManager.startBindingCapture(pendingGamepadPadIndex) { keyCode ->
                viewModel.setGamepadBinding(pendingGamepadPadIndex, actionId, keyCode)
                pendingGamepadActionId.value = null
            }
        } else {
            GamepadManager.cancelBindingCapture()
        }
        onDispose {
            GamepadManager.cancelBindingCapture()
        }
    }

    val settingsBackupExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                backupRepository.backup(
                    destination = uri,
                    includeSaveStates = includeSaveStatesInBackup
                )
            }
            Toast.makeText(
                context,
                if (success) backupExportSuccessMessage else backupExportFailureMessage,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val settingsBackupImporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                backupRepository.restore(uri)
            }
            Toast.makeText(
                context,
                if (success) backupRestoreSuccessMessage else backupRestoreFailureMessage,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    ProvideGamepadShoulderActions(
        enabled = !searchEnabled,
        onPrevious = { selectRelativeTab(-1) },
        onNext = { selectRelativeTab(1) }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontalSystemBarPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(settingsScrollState)
        ) {
            SettingsCompactTopBar(
                title = stringResource(R.string.settings_title),
                subtitle = if (searchEnabled) stringResource(R.string.settings_search_subtitle) else selectedTab.label(),
                topInset = topInset,
                onBackClick = onBackClick,
                menuExpanded = showTopBarMenu,
                onMenuExpandedChange = { showTopBarMenu = it },
                onResetAllSettingsClick = {
                    showTopBarMenu = false
                    showResetAllSettingsDialog.value = true
                },
                searchEnabled = searchEnabled,
                searchQuery = searchQuery,
                onSearchEnabledChange = {
                    searchEnabled = it
                    if (!it) searchQuery = ""
                },
                onSearchQueryChange = { searchQuery = it }
            )

            SettingsTabRow(
                selectedTab = selectedTab,
                onSelected = { tab ->
                    if (selectedTab == tab) return@SettingsTabRow
                    selectedTab = tab
                },
                selectedTabFocusRequester = selectedTabFocusRequester
            )

            SettingsContent(
                uiState = uiState,
                selectedTab = selectedTab,
                context = context,
                launchBiosPicker = openBiosDialog,
                launchGamePicker = launchGamePicker,
                openEmulatorDataLocationDialog = openEmulatorDataLocationDialog,
                launchHomeBackgroundPicker = {
                    homeBackgroundPicker.launch(arrayOf("image/*", "video/*"))
                },
                launchSideArtworkPicker = {
                    sideArtworkPicker.launch(arrayOf("image/*"))
                },
                launchCustomFontPicker = {
                    customFontPicker.launch(
                        arrayOf(
                            "font/ttf",
                            "font/otf",
                            "application/x-font-ttf",
                            "application/x-font-opentype",
                            "application/vnd.ms-opentype",
                            "application/octet-stream"
                        )
                    )
                },
                launchShaderPackPicker = {
                    shaderPackPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                onOpenCoverUrlEditor = {
                    pendingCoverUrl.value = uiState.coverDownloadBaseUrl.orEmpty()
                    showCoverUrlDialog.value = true
                },
                onOpenPatchUrlEditor = {
                    pendingPatchUrl.value = uiState.patchDatabaseCustomUrl.orEmpty()
                    showPatchUrlDialog.value = true
                },
                onClearCoverCache = { showClearCoverCacheDialog = true },
                launchSettingsBackupExport = {
                    includeSaveStatesInBackup = false
                    showBackupExportDialog = true
                },
                launchSettingsBackupImport = { settingsBackupImporter.launch(arrayOf("application/zip", "*/*")) },
                openLanguageSheet = openLanguageSheet,
                onRequestGamepadBinding = { padIndex, actionId ->
                    pendingGamepadPadIndex = padIndex
                    pendingGamepadActionId.value = actionId
                },
                onOpenGamepadAssignment = { showGamepadAssignmentDialog = true },
                searchQuery = searchQuery,
                onSearchResultSelected = { tab ->
                    selectedTab = tab
                    searchEnabled = false
                    searchQuery = ""
                },
                onOpenMemoryCardManager = onOpenMemoryCardManager,
                onOpenGameDbBrowser = onOpenGameDbBrowser,
                onOpenControlsLayoutEditor = onOpenControlsLayoutEditor,
                onOpenThemeManager = onOpenThemeManager,
                onOpenTouchControlCreator = onOpenTouchControlCreator,
                viewModel = viewModel,
                topInset = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(bottomInset))
        }
    }

    if (showGamepadAssignmentDialog) {
        GamepadAssignmentDialog(
            assignments = gamepadDeviceAssignments,
            ignoredDevices = ignoredGamepadDevices,
            onAssign = viewModel::setGamepadDeviceAssignment,
            onIgnore = viewModel::setGamepadDeviceIgnored,
            onReset = viewModel::resetGamepadDeviceAssignments,
            onDismiss = { showGamepadAssignmentDialog = false }
        )
    }



    if (pendingGamepadActionId.value != null) {
        val dialogFocusRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            dialogFocusRequester.requestFocus()
        }

        AlertDialog(
            onDismissRequest = { pendingGamepadActionId.value = null },
            modifier = Modifier
                .focusRequester(dialogFocusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    GamepadManager.handleBindingCapture(keyEvent.nativeKeyEvent)
                },
            title = {
                Text(stringResource(R.string.settings_gamepad_mapping_listening_title))
            },
            text = {
                Text(
                    stringResource(
                        R.string.settings_gamepad_mapping_listening_player_desc,
                        gamepadPlayerLabel(pendingGamepadPadIndex),
                        gamepadActionLabel(pendingGamepadActionId.value.orEmpty())
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingGamepadActionId.value = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showResetAllSettingsDialog.value) {
        AlertDialog(
            onDismissRequest = { showResetAllSettingsDialog.value = false },
            title = {
                Text(stringResource(R.string.settings_reset_all_title))
            },
            text = {
                Text(stringResource(R.string.settings_reset_all_confirm))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetAllSettingsDialog.value = false
                        viewModel.resetAllSettings()
                    }
                ) {
                    Text(stringResource(R.string.settings_reset_all_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllSettingsDialog.value = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showClearCoverCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCoverCacheDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.settings_clear_cover_cache_confirm_title)) },
            text = { Text(stringResource(R.string.settings_clear_cover_cache_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCoverCacheDialog = false
                        viewModel.clearCoverCache { result ->
                            Toast.makeText(
                                context,
                                if (result.fullyCleared) coverCacheClearedMessage else coverCachePartiallyClearedMessage,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_clear_cover_cache_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCoverCacheDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showBackupExportDialog) {
        SettingsStyledDialog(
            title = stringResource(R.string.settings_backup_export_title),
            eyebrow = stringResource(R.string.settings_backup_section_title),
            icon = Icons.Rounded.Save,
            onDismissRequest = { showBackupExportDialog = false },
        ) {
            Text(
                text = stringResource(R.string.settings_backup_export_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        includeSaveStatesInBackup = !includeSaveStatesInBackup
                    },
                shape = neonShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SaveAs,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_backup_include_save_states),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.settings_backup_include_save_states_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = includeSaveStatesInBackup,
                        onCheckedChange = { includeSaveStatesInBackup = it }
                    )
                }
            }

            Button(
                onClick = {
                    showBackupExportDialog = false
                    settingsBackupExporter.launch("emucorer-settings-backup.zip")
                },
                modifier = Modifier.fillMaxWidth(),
                shape = neonShape(18.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_backup_export_action),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            TextButton(
                onClick = { showBackupExportDialog = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    }

    if (showBiosDialog.value) {
        val biosDisplayName = uiState.biosPath?.let { DocumentPathResolver.getFallbackDisplayName(it) }
            ?: stringResource(R.string.settings_not_set)
        AlertDialog(
            onDismissRequest = { showBiosDialog.value = false },
            title = {
                Text(stringResource(R.string.settings_bios_picker_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_bios_picker_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = neonShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_bios_picker_current),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = biosDisplayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBiosDialog.value = false
                        launchBiosPicker()
                    }
                ) {
                    Text(stringResource(R.string.settings_bios_picker_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBiosDialog.value = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showCoverUrlDialog.value) {
        val coverUrlFocusRequester = remember { FocusRequester() }
        val exampleBundle = remember {
            "${CoverArtRepository.DEFAULT_COVER_BASE_URL} ${CoverArtRepository.DEFAULT_COVER_3D_BASE_URL}"
        }
        LaunchedEffect(showCoverUrlDialog.value) {
            if (showCoverUrlDialog.value) {
                coverUrlFocusRequester.requestFocus()
            }
        }
        AlertDialog(
            onDismissRequest = { showCoverUrlDialog.value = false },
            title = {
                Text(stringResource(R.string.settings_cover_download_url_dialog_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_cover_download_url_dialog_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pendingCoverUrl.value,
                        onValueChange = { pendingCoverUrl.value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(coverUrlFocusRequester),
                        minLines = 2,
                        maxLines = 4,
                        shape = neonShape(18.dp),
                        label = { Text(stringResource(R.string.settings_cover_download_url)) },
                        placeholder = { Text(stringResource(R.string.settings_cover_download_url_placeholder)) }
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = neonShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_cover_download_url_example),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            CoverUrlExampleRow(
                                label = stringResource(R.string.settings_cover_download_url_example_hint),
                                onClick = {
                                    pendingCoverUrl.value = exampleBundle
                                    scope.launch { coverUrlFocusRequester.requestFocus() }
                                },
                                onLongClick = {
                                    val clipboardManager = context.getSystemService(android.content.ClipboardManager::class.java)
                                    clipboardManager?.setPrimaryClip(
                                        ClipData.newPlainText("cover_urls", exampleBundle)
                                    )
                                    Toast.makeText(
                                        context,
                                        coverUrlCopiedMessage,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            pendingCoverUrl.value = ""
                            viewModel.setCoverDownloadBaseUrl(null)
                            showCoverUrlDialog.value = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_cover_download_url_use_default))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parts = pendingCoverUrl.value.trim()
                            .split(Regex("\\s+"))
                            .filter { it.isNotBlank() }
                        val value = parts.joinToString(" ")
                        val hasInvalidPart = parts.any {
                            !it.startsWith("http://") && !it.startsWith("https://")
                        }
                        if (hasInvalidPart || parts.size > 2) {
                            Toast.makeText(
                                context,
                                coverUrlInvalidMessage,
                                Toast.LENGTH_SHORT
                            ).show()
                            return@TextButton
                        }
                        viewModel.setCoverDownloadBaseUrl(value.ifBlank { null })
                        showCoverUrlDialog.value = false
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCoverUrlDialog.value = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showPatchUrlDialog.value) {
        val patchUrlFocusRequester = remember { FocusRequester() }
        LaunchedEffect(showPatchUrlDialog.value) {
            if (showPatchUrlDialog.value) {
                patchUrlFocusRequester.requestFocus()
            }
        }
        AlertDialog(
            onDismissRequest = { showPatchUrlDialog.value = false },
            title = {
                Text(stringResource(R.string.settings_patches_custom_url_dialog_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_patches_custom_url_dialog_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pendingPatchUrl.value,
                        onValueChange = { pendingPatchUrl.value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(patchUrlFocusRequester),
                        minLines = 2,
                        maxLines = 4,
                        shape = neonShape(18.dp),
                        label = { Text(stringResource(R.string.settings_patches_custom_url)) },
                        placeholder = { Text(stringResource(R.string.settings_patches_custom_url_placeholder)) }
                    )
                    TextButton(
                        onClick = {
                            pendingPatchUrl.value = ""
                            viewModel.setPatchDatabaseCustomUrl(null)
                            showPatchUrlDialog.value = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_patches_custom_url_clear))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = pendingPatchUrl.value.trim()
                        if (value.isNotBlank() &&
                            !value.startsWith("http://") &&
                            !value.startsWith("https://")
                        ) {
                            Toast.makeText(
                                context,
                                patchUrlInvalidMessage,
                                Toast.LENGTH_SHORT
                            ).show()
                            return@TextButton
                        }
                        viewModel.setPatchDatabaseCustomUrl(value.ifBlank { null })
                        showPatchUrlDialog.value = false
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPatchUrlDialog.value = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsCompactTopBar(
    title: String,
    subtitle: String,
    topInset: androidx.compose.ui.unit.Dp,
    onBackClick: (() -> Unit)?,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onResetAllSettingsClick: () -> Unit,
    searchEnabled: Boolean,
    searchQuery: String,
    onSearchEnabledChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ScreenHorizontalPadding,
                end = ScreenHorizontalPadding,
                top = topInset,
                bottom = 4.dp
            ),
        shape = neonShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBackClick != null) {
                NavigationBackButton(
                    onClick = onBackClick
                )
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 8.dp)
            ) {
                if (searchEnabled) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .skipGamepadTextFieldFocus(),
                        singleLine = true,
                        shape = neonShape(18.dp),
                        placeholder = { Text(stringResource(R.string.settings_search_placeholder)) }
                    )
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { onSearchEnabledChange(!searchEnabled) }) {
                Icon(
                    imageVector = if (searchEnabled) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.settings_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { onMenuExpandedChange(true) }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.settings_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) },
                    shape = neonShape(20.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_reset_all_action)) },
                        onClick = onResetAllSettingsClick
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTabRow(
    selectedTab: SettingsTab,
    onSelected: (SettingsTab) -> Unit,
    selectedTabFocusRequester: FocusRequester
) {
    val tabs = remember { SettingsTab.entries.toList() }
    ScrollableFilterTabRow(
        tabs = tabs,
        selectedTab = selectedTab,
        onSelected = onSelected,
        key = SettingsTab::name,
        label = SettingsTab::label,
        icon = SettingsTab::icon,
        selectedTabFocusRequester = selectedTabFocusRequester
    )
}

@Composable
private fun SettingsContent(
    uiState: SettingsUiState,
    selectedTab: SettingsTab,
    searchQuery: String,
    context: android.content.Context,
    launchBiosPicker: () -> Unit,
    launchGamePicker: () -> Unit,
    openEmulatorDataLocationDialog: () -> Unit,
    launchHomeBackgroundPicker: () -> Unit,
    launchSideArtworkPicker: () -> Unit,
    launchCustomFontPicker: () -> Unit,
    launchShaderPackPicker: () -> Unit,
    onOpenCoverUrlEditor: () -> Unit,
    onOpenPatchUrlEditor: () -> Unit,
    onClearCoverCache: () -> Unit,
    launchSettingsBackupExport: () -> Unit,
    launchSettingsBackupImport: () -> Unit,
    openLanguageSheet: () -> Unit,
    onRequestGamepadBinding: (Int, String) -> Unit,
    onOpenGamepadAssignment: () -> Unit,
    onSearchResultSelected: (SettingsTab) -> Unit,
    viewModel: SettingsViewModel,
    topInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onOpenMemoryCardManager: (() -> Unit)? = null,
    onOpenGameDbBrowser: (() -> Unit)? = null,
    onOpenControlsLayoutEditor: (() -> Unit)? = null,
    onOpenThemeManager: (() -> Unit)? = null,
    onOpenTouchControlCreator: (() -> Unit)? = null
) {
    val gamepadActions = remember { GamepadManager.mappableButtonActions() }
    val gamepadDeviceAssignments by GamepadManager.gamepadDeviceAssignmentsState.collectAsState()
    val ignoredGamepadDevices by GamepadManager.ignoredGamepadDevicesState.collectAsState()
    val defaults = remember { SettingsSnapshot() }
    val overlayDefaults = remember { OverlayLayoutSnapshot() }
    val searchEntries = rememberSettingsSearchEntries()
    val floatingQuickActionsEnabled by viewModel.floatingQuickActionsEnabled.collectAsState()
    val orientationLock by viewModel.orientationLock.collectAsState()
    val emulationAllowsBothOrientations by viewModel.emulationAllowsBothOrientations.collectAsState()
    val notSetLabel = stringResource(R.string.settings_not_set)
    var selectedGamepadPadIndex by rememberSaveable { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset + 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (searchQuery.isNotBlank()) {
                SettingsSearchResults(
                    query = searchQuery,
                    entries = searchEntries,
                    onOpen = onSearchResultSelected
                )
            } else when (selectedTab) {
                SettingsTab.General -> {
                    SettingsSection(title = stringResource(R.string.settings_general_tab)) {
                        SettingsItem(
                            icon = Icons.Rounded.Language,
                            label = stringResource(R.string.settings_language),
                            value = languageLabel(uiState.languageTag),
                            onClick = openLanguageSheet
                        )
                        val systemTelevision = remember(context) {
                            TvUiPolicy.isSystemTelevision(context)
                        }
                        ChoiceSection(
                            title = stringResource(R.string.settings_tv_interface),
                            options = listOf(
                                TvInterfaceMode.AUTO.preferenceValue to stringResource(R.string.settings_tv_interface_auto),
                                TvInterfaceMode.STANDARD.preferenceValue to stringResource(R.string.settings_tv_interface_standard),
                                TvInterfaceMode.TV.preferenceValue to stringResource(R.string.settings_tv_interface_tv)
                            ),
                            selectedValue = uiState.tvInterfaceMode.preferenceValue,
                            onSelect = { value ->
                                viewModel.setTvInterfaceMode(TvInterfaceMode.fromPreference(value))
                            },
                            helpText = stringResource(R.string.settings_help_tv_interface),
                            onResetToDefault = { viewModel.setTvInterfaceMode(TvInterfaceMode.AUTO) }
                        )
                        SettingsInlineNote(
                            stringResource(
                                if (systemTelevision) {
                                    R.string.settings_tv_interface_detected_tv
                                } else {
                                    R.string.settings_tv_interface_detected_standard
                                }
                            )
                        )
                        ChoiceSection(
                            title = stringResource(R.string.settings_orientation_lock),
                            options = listOf(
                                AppPreferences.ORIENTATION_LOCK_AUTO to
                                    stringResource(R.string.settings_orientation_lock_auto),
                                AppPreferences.ORIENTATION_LOCK_PORTRAIT to
                                    stringResource(R.string.settings_orientation_lock_portrait),
                                AppPreferences.ORIENTATION_LOCK_LANDSCAPE to
                                    stringResource(R.string.settings_orientation_lock_landscape)
                            ),
                            selectedValue = orientationLock,
                            onSelect = viewModel::setOrientationLock,
                            helpText = stringResource(R.string.settings_help_orientation_lock),
                            onResetToDefault = {
                                viewModel.setOrientationLock(AppPreferences.ORIENTATION_LOCK_AUTO)
                            }
                        )
                        ThemeSelector(
                            selected = uiState.themeMode,
                            customThemeLibrary = uiState.customThemeLibrary,
                            onSelected = viewModel::setThemeMode,
                            onCustomThemeSelected = { themeId ->
                                viewModel.saveCustomThemeLibrary(
                                    uiState.customThemeLibrary.copy(activeThemeId = themeId),
                                    activate = true
                                )
                            }
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Palette,
                            label = stringResource(R.string.settings_theme_manager),
                            value = if (uiState.customTheme.name == CustomThemeConfig.DEFAULT_NAME) {
                                stringResource(R.string.theme_manager_default_name)
                            } else {
                                uiState.customTheme.name
                            },
                            onClick = { onOpenThemeManager?.invoke() },
                            helpText = stringResource(R.string.settings_theme_manager_desc),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                            )
                        )
                        ToggleItem(
                            icon = Icons.Rounded.StayPrimaryPortrait,
                            title = stringResource(R.string.settings_keep_screen_on),
                            subtitle = stringResource(R.string.settings_keep_screen_on_desc),
                            checked = uiState.keepScreenOn,
                            onCheckedChange = viewModel::setKeepScreenOn,
                            helpText = stringResource(R.string.settings_help_keep_screen_on),
                            onResetToDefault = { viewModel.setKeepScreenOn(defaults.keepScreenOn) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.ScreenRotation,
                            title = stringResource(R.string.settings_emulation_both_orientations),
                            subtitle = stringResource(R.string.settings_emulation_both_orientations_desc),
                            checked = emulationAllowsBothOrientations,
                            onCheckedChange = viewModel::setEmulationAllowsBothOrientations,
                            helpText = stringResource(R.string.settings_help_emulation_both_orientations),
                            onResetToDefault = { viewModel.setEmulationAllowsBothOrientations(false) }
                        )
                        ToggleItem(
                            icon = Icons.AutoMirrored.Rounded.ExitToApp,
                            title = stringResource(R.string.settings_back_button_exits_game),
                            subtitle = stringResource(R.string.settings_back_button_exits_game_desc),
                            checked = uiState.backButtonExitsGame,
                            onCheckedChange = viewModel::setBackButtonExitsGame,
                            helpText = stringResource(R.string.settings_help_back_button_exits_game),
                            onResetToDefault = { viewModel.setBackButtonExitsGame(defaults.backButtonExitsGame) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Save,
                            title = stringResource(R.string.settings_confirm_save_load_actions),
                            subtitle = stringResource(R.string.settings_confirm_save_load_actions_desc),
                            checked = uiState.confirmSaveLoadActions,
                            onCheckedChange = viewModel::setConfirmSaveLoadActions,
                            helpText = stringResource(R.string.settings_help_confirm_save_load_actions),
                            onResetToDefault = { viewModel.setConfirmSaveLoadActions(defaults.confirmSaveLoadActions) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Save,
                            title = stringResource(R.string.settings_floating_quick_actions),
                            subtitle = stringResource(R.string.settings_floating_quick_actions_desc),
                            checked = floatingQuickActionsEnabled,
                            onCheckedChange = viewModel::setFloatingQuickActionsEnabled,
                            helpText = stringResource(R.string.settings_help_floating_quick_actions),
                            onResetToDefault = { viewModel.setFloatingQuickActionsEnabled(false) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Visibility,
                            title = stringResource(R.string.settings_show_recent_games),
                            subtitle = stringResource(R.string.settings_show_recent_games_desc),
                            checked = uiState.showRecentGames,
                            onCheckedChange = viewModel::setShowRecentGames,
                            helpText = stringResource(R.string.settings_help_recent_games),
                            onResetToDefault = { viewModel.setShowRecentGames(defaults.showRecentGames) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Search,
                            title = stringResource(R.string.settings_show_home_search),
                            subtitle = stringResource(R.string.settings_show_home_search_desc),
                            checked = uiState.showHomeSearch,
                            onCheckedChange = viewModel::setShowHomeSearch,
                            helpText = stringResource(R.string.settings_help_home_search),
                            onResetToDefault = { viewModel.setShowHomeSearch(defaults.showHomeSearch) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Language,
                            title = stringResource(R.string.settings_prefer_english_game_titles),
                            subtitle = stringResource(R.string.settings_prefer_english_game_titles_desc),
                            checked = uiState.preferEnglishGameTitles,
                            onCheckedChange = viewModel::setPreferEnglishGameTitles,
                            helpText = stringResource(R.string.settings_help_prefer_english_game_titles),
                            onResetToDefault = { viewModel.setPreferEnglishGameTitles(defaults.preferEnglishGameTitles) }
                        )
                    }
                }

                SettingsTab.Customization -> {
                    CustomizationSettingsTab(
                        uiState = uiState,
                        onPickBackground = launchHomeBackgroundPicker,
                        onPickSideArtwork = launchSideArtworkPicker,
                        onPickCustomFont = launchCustomFontPicker,
                        onOpenTouchControlCreator = onOpenTouchControlCreator,
                        viewModel = viewModel
                    )
                }

                SettingsTab.GameMenu -> {
                    GameMenuSettingsTab(uiState = uiState, viewModel = viewModel)
                }

                SettingsTab.Audio -> {
                    SettingsSection(title = stringResource(R.string.settings_audio_control)) {
                        ToggleItem(
                            icon = Icons.AutoMirrored.Rounded.VolumeOff,
                            title = stringResource(R.string.settings_audio_mute),
                            subtitle = stringResource(R.string.settings_audio_mute_desc),
                            checked = uiState.audioMuted,
                            onCheckedChange = viewModel::setAudioMuted,
                            helpText = stringResource(R.string.settings_help_audio_mute),
                            onResetToDefault = { viewModel.setAudioMuted(defaults.audioMuted) }
                        )
                        SliderItem(
                            icon = Icons.AutoMirrored.Rounded.VolumeUp,
                            title = stringResource(R.string.settings_audio_volume),
                            subtitle = "${uiState.audioVolume}%",
                            value = uiState.audioVolume.toFloat(),
                            range = AudioDefaults.VOLUME_MIN.toFloat()..AudioDefaults.VOLUME_MAX.toFloat(),
                            steps = 0,
                            onValueChange = { viewModel.setAudioVolume(it.roundToInt()) },
                            valueLabel = { "${it.roundToInt()}%" },
                            helpText = stringResource(R.string.settings_help_audio_volume),
                            onResetToDefault = { viewModel.setAudioVolume(defaults.audioVolume) }
                        )
                    }

                    SettingsSection(title = stringResource(R.string.settings_audio_output)) {
                        SliderItem(
                            icon = Icons.Rounded.GraphicEq,
                            title = stringResource(R.string.settings_audio_output_latency),
                            subtitle = "${uiState.audioOutputLatencyMs} ms",
                            value = uiState.audioOutputLatencyMs.toFloat(),
                            range = AudioDefaults.OUTPUT_LATENCY_MS_MIN.toFloat()..
                                AudioDefaults.OUTPUT_LATENCY_MS_MAX.toFloat(),
                            steps = 0,
                            onValueChange = { viewModel.setAudioOutputLatencyMs(it.roundToInt()) },
                            valueLabel = { "${it.roundToInt()} ms" },
                            helpText = stringResource(R.string.settings_help_audio_output_latency),
                            onResetToDefault = {
                                viewModel.setAudioOutputLatencyMs(defaults.audioOutputLatencyMs)
                            }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Speed,
                            title = stringResource(R.string.settings_audio_minimal_latency),
                            subtitle = stringResource(R.string.settings_audio_minimal_latency_desc),
                            checked = uiState.audioMinimalOutputLatency,
                            onCheckedChange = viewModel::setAudioMinimalOutputLatency,
                            helpText = stringResource(R.string.settings_help_audio_minimal_latency),
                            onResetToDefault = {
                                viewModel.setAudioMinimalOutputLatency(defaults.audioMinimalOutputLatency)
                            }
                        )
                    }

                    SettingsSection(title = stringResource(R.string.settings_core_audio)) {

                        var coreAudioOptionsVersion by remember { mutableIntStateOf(0) }
                        CoreOptionSettingsRows(
                            options = remember { SwanStationCoreOptions.audioOptions() },
                            version = coreAudioOptionsVersion,
                            onValueChange = { key, value ->
                                NativeApp.setCoreOption(key, value)
                                coreAudioOptionsVersion++
                            }
                        )
                    }
                }

                SettingsTab.Controls -> {
                    com.sbro.emucorer.ui.controls.DukeFastForwardSettings()
                    SettingsSection(title = stringResource(R.string.settings_touch_controls)) {
                        ActionItem(
                            icon = Icons.Rounded.Tune,
                            title = stringResource(R.string.settings_edit_global_controls),
                            subtitle = stringResource(R.string.settings_edit_global_controls_desc),
                            actionIcon = Icons.Rounded.Gamepad,
                            actionLabel = stringResource(R.string.settings_edit_controls_action),
                            onClick = { onOpenControlsLayoutEditor?.invoke() },
                            enabled = onOpenControlsLayoutEditor != null
                        )
                        SliderItem(
                            icon = Icons.Rounded.TouchApp,
                            title = stringResource(R.string.settings_overlay_scale),
                            subtitle = "${uiState.overlayScale}%",
                            value = uiState.overlayScale.toFloat(),
                            range = 50f..150f,
                            steps = 9,
                            onValueChange = { viewModel.setOverlayScale(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_overlay_scale),
                            onResetToDefault = { viewModel.setOverlayScale(overlayDefaults.overlayScale) }
                        )
                        SliderItem(
                            icon = Icons.Rounded.Visibility,
                            title = stringResource(R.string.settings_overlay_opacity),
                            subtitle = "${uiState.overlayOpacity}%",
                            value = uiState.overlayOpacity.toFloat(),
                            range = AppPreferences.OVERLAY_OPACITY_MIN.toFloat()..
                                AppPreferences.OVERLAY_OPACITY_MAX.toFloat(),
                            steps = AppPreferences.OVERLAY_OPACITY_MAX -
                                AppPreferences.OVERLAY_OPACITY_MIN - 1,
                            onValueChange = { viewModel.setOverlayOpacity(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_overlay_opacity),
                            onResetToDefault = { viewModel.setOverlayOpacity(overlayDefaults.overlayOpacity) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.TouchApp,
                            title = stringResource(R.string.settings_touchscreen_right_stick),
                            subtitle = stringResource(R.string.settings_touchscreen_right_stick_desc),
                            checked = uiState.touchscreenRightStick,
                            onCheckedChange = viewModel::setTouchscreenRightStick,
                            helpText = stringResource(R.string.settings_help_touchscreen_right_stick),
                            onResetToDefault = {
                                viewModel.setTouchscreenRightStick(
                                    AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK
                                )
                            }
                        )
                        if (uiState.touchscreenRightStick) {
                            SliderItem(
                                icon = Icons.Rounded.TouchApp,
                                title = stringResource(R.string.settings_touchscreen_right_stick_sensitivity),
                                subtitle = "${uiState.touchscreenRightStickSensitivity}%",
                                value = uiState.touchscreenRightStickSensitivity.toFloat(),
                                range = AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN.toFloat()..
                                    AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX.toFloat(),
                                steps = AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX -
                                    AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN - 1,
                                onValueChange = {
                                    viewModel.setTouchscreenRightStickSensitivity(it.roundToInt())
                                },
                                helpText = stringResource(
                                    R.string.settings_help_touchscreen_right_stick_sensitivity
                                ),
                                onResetToDefault = {
                                    viewModel.setTouchscreenRightStickSensitivity(
                                        AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK_SENSITIVITY
                                    )
                                }
                            )
                        }
                        ToggleItem(
                            icon = Icons.Rounded.TouchApp,
                            title = stringResource(R.string.settings_racing_mode),
                            subtitle = stringResource(R.string.settings_racing_mode_desc),
                            checked = uiState.racingMode,
                            onCheckedChange = viewModel::setRacingMode,
                            helpText = stringResource(R.string.settings_help_racing_mode),
                            onResetToDefault = { viewModel.setRacingMode(defaults.racingMode) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Vibration,
                            title = stringResource(R.string.settings_touch_haptics),
                            subtitle = stringResource(R.string.settings_touch_haptics_desc),
                            checked = uiState.touchHaptics,
                            onCheckedChange = viewModel::setTouchHaptics,
                            helpText = stringResource(R.string.settings_help_touch_haptics),
                            onResetToDefault = { viewModel.setTouchHaptics(defaults.touchHaptics) }
                        )
                        ChoiceSection(
                            title = stringResource(R.string.settings_touch_haptics_preset),
                            options = touchHapticsPresetOptions(),
                            selectedValue = uiState.touchHapticsPreset,
                            onSelect = viewModel::setTouchHapticsPreset,
                            helpText = stringResource(R.string.settings_help_touch_haptics_preset),
                            onResetToDefault = { viewModel.setTouchHapticsPreset(defaults.touchHapticsPreset) }
                        )
                        SettingsInlineNote(text = stringResource(R.string.settings_touch_haptics_preset_desc))
                        SliderItem(
                            icon = Icons.Rounded.Vibration,
                            title = stringResource(R.string.settings_touch_haptics_strength),
                            subtitle = "${uiState.touchHapticsStrength}%",
                            valueLabel = { "${it.roundToInt()}%" },
                            value = uiState.touchHapticsStrength.toFloat(),
                            range = 10f..100f,
                            steps = 8,
                            onValueChange = { viewModel.setTouchHapticsStrength(it.roundToInt()) },
                            helpText = stringResource(R.string.settings_help_touch_haptics_strength),
                            onResetToDefault = { viewModel.setTouchHapticsStrength(defaults.touchHapticsStrength) }
                        )
                        ActionItem(
                            icon = Icons.Rounded.Vibration,
                            title = stringResource(R.string.settings_touch_haptics_test),
                            subtitle = stringResource(R.string.settings_touch_haptics_test_desc),
                            actionIcon = Icons.Rounded.PlayArrow,
                            actionLabel = stringResource(R.string.settings_pad_vibration_test_action),
                            onClick = {
                                viewModel.testTouchHaptics(
                                    strengthPercent = uiState.touchHapticsStrength,
                                    preset = uiState.touchHapticsPreset
                                )
                            },
                            helpText = stringResource(R.string.settings_help_touch_haptics_test)
                        )
                        ChoiceSection(
                            title = stringResource(R.string.settings_gyro_mode),
                            options = gyroModeOptions(),
                            selectedValue = uiState.gyroMode,
                            onSelect = viewModel::setGyroMode,
                            helpText = stringResource(R.string.settings_help_gyro_mode),
                            onResetToDefault = { viewModel.setGyroMode(defaults.gyroMode) }
                        )
                        if (uiState.gyroMode != AppPreferences.GYRO_MODE_OFF &&
                            !AndroidGyroscopeInput.isModeAvailable(context, uiState.gyroMode)
                        ) {
                            SettingsInlineNote(text = stringResource(R.string.settings_gyro_unavailable))
                        }
                        if (uiState.gyroMode != AppPreferences.GYRO_MODE_OFF) {
                            SliderItem(
                                icon = Icons.Rounded.ScreenRotation,
                                title = stringResource(R.string.settings_gyro_sensitivity),
                                subtitle = "${uiState.gyroSensitivity}%",
                                valueLabel = { "${it.roundToInt()}%" },
                                value = uiState.gyroSensitivity.toFloat(),
                                range = 25f..300f,
                                steps = 10,
                                onValueChange = { viewModel.setGyroSensitivity(it.roundToInt()) },
                                helpText = stringResource(R.string.settings_help_gyro_sensitivity),
                                onResetToDefault = { viewModel.setGyroSensitivity(defaults.gyroSensitivity) }
                            )
                            SliderItem(
                                icon = Icons.Rounded.Tune,
                                title = stringResource(R.string.settings_gyro_smoothing),
                                subtitle = "${uiState.gyroSmoothing}%",
                                valueLabel = { "${it.roundToInt()}%" },
                                value = uiState.gyroSmoothing.toFloat(),
                                range = 0f..90f,
                                steps = 8,
                                onValueChange = { viewModel.setGyroSmoothing(it.roundToInt()) },
                                helpText = stringResource(R.string.settings_help_gyro_smoothing),
                                onResetToDefault = { viewModel.setGyroSmoothing(defaults.gyroSmoothing) }
                            )
                            ToggleItem(
                                icon = Icons.Rounded.SwapHoriz,
                                title = stringResource(R.string.settings_gyro_invert_x),
                                subtitle = stringResource(R.string.settings_gyro_invert_x_desc),
                                checked = uiState.gyroInvertX,
                                onCheckedChange = viewModel::setGyroInvertX,
                                onResetToDefault = { viewModel.setGyroInvertX(defaults.gyroInvertX) }
                            )
                            if (uiState.gyroMode == AppPreferences.GYRO_MODE_AIM) {
                                ToggleItem(
                                    icon = Icons.Rounded.SwapVert,
                                    title = stringResource(R.string.settings_gyro_invert_y),
                                    subtitle = stringResource(R.string.settings_gyro_invert_y_desc),
                                    checked = uiState.gyroInvertY,
                                    onCheckedChange = viewModel::setGyroInvertY,
                                    onResetToDefault = { viewModel.setGyroInvertY(defaults.gyroInvertY) }
                                )
                            }
                        }
                        SliderItem(
                            icon = Icons.Rounded.Gamepad,
                            title = stringResource(R.string.settings_left_stick_sensitivity),
                            subtitle = "${uiState.leftStickSensitivity}%",
                            value = uiState.leftStickSensitivity.toFloat(),
                            range = 50f..200f,
                            steps = 14,
                            onValueChange = { viewModel.setLeftStickSensitivity(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_left_stick_sensitivity),
                            onResetToDefault = { viewModel.setLeftStickSensitivity(overlayDefaults.leftStickSensitivity) }
                        )
                        SliderItem(
                            icon = Icons.Rounded.Gamepad,
                            title = stringResource(R.string.settings_right_stick_sensitivity),
                            subtitle = "${uiState.rightStickSensitivity}%",
                            value = uiState.rightStickSensitivity.toFloat(),
                            range = 50f..200f,
                            steps = 14,
                            onValueChange = { viewModel.setRightStickSensitivity(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_right_stick_sensitivity),
                            onResetToDefault = { viewModel.setRightStickSensitivity(overlayDefaults.rightStickSensitivity) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Gamepad,
                            title = stringResource(R.string.settings_invert_left_stick),
                            subtitle = stringResource(R.string.settings_invert_left_stick_desc),
                            checked = uiState.invertLeftStick,
                            onCheckedChange = viewModel::setInvertLeftStick,
                            helpText = stringResource(R.string.settings_help_invert_left_stick),
                            onResetToDefault = { viewModel.setInvertLeftStick(false) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Gamepad,
                            title = stringResource(R.string.settings_invert_left_stick_horizontal),
                            subtitle = stringResource(R.string.settings_invert_left_stick_horizontal_desc),
                            checked = uiState.invertLeftStickHorizontal,
                            onCheckedChange = viewModel::setInvertLeftStickHorizontal,
                            helpText = stringResource(R.string.settings_help_invert_left_stick_horizontal),
                            onResetToDefault = { viewModel.setInvertLeftStickHorizontal(false) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Gamepad,
                            title = stringResource(R.string.settings_invert_right_stick),
                            subtitle = stringResource(R.string.settings_invert_right_stick_desc),
                            checked = uiState.invertRightStick,
                            onCheckedChange = viewModel::setInvertRightStick,
                            helpText = stringResource(R.string.settings_help_invert_right_stick),
                            onResetToDefault = { viewModel.setInvertRightStick(false) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Gamepad,
                            title = stringResource(R.string.settings_invert_right_stick_horizontal),
                            subtitle = stringResource(R.string.settings_invert_right_stick_horizontal_desc),
                            checked = uiState.invertRightStickHorizontal,
                            onCheckedChange = viewModel::setInvertRightStickHorizontal,
                            helpText = stringResource(R.string.settings_help_invert_right_stick_horizontal),
                            onResetToDefault = { viewModel.setInvertRightStickHorizontal(false) }
                        )
                    }
                    SettingsSection(title = stringResource(R.string.settings_gamepad_section)) {
                        ChoiceSection(
                            title = stringResource(R.string.settings_gamepad_mode),
                            options = listOf(
                                1 to stringResource(R.string.settings_gamepad_mode_replace_touch),
                                0 to stringResource(R.string.settings_gamepad_mode_touch_plus_gamepad)
                            ),
                            selectedValue = if (uiState.enableAutoGamepad) 1 else 0,
                            onSelect = { viewModel.setEnableAutoGamepad(it == 1) },
                            helpText = stringResource(R.string.settings_help_gamepad_mode),
                            onResetToDefault = { viewModel.setEnableAutoGamepad(defaults.enableAutoGamepad) }
                        )
                        SettingsInlineNote(text = stringResource(R.string.settings_gamepad_mode_desc))
                        SettingsItem(
                            icon = Icons.Rounded.Gamepad,
                            label = stringResource(R.string.settings_gamepad_assignment_title),
                            value = if (
                                gamepadDeviceAssignments.isEmpty() &&
                                ignoredGamepadDevices.isEmpty()
                            ) {
                                stringResource(R.string.settings_gamepad_assignment_auto)
                            } else {
                                stringResource(R.string.settings_gamepad_assignment_custom)
                            },
                            onClick = onOpenGamepadAssignment,
                            helpText = stringResource(R.string.settings_help_gamepad_assignment)
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Visibility,
                            title = stringResource(R.string.settings_gamepad_hide_overlay),
                            subtitle = stringResource(R.string.settings_gamepad_hide_overlay_desc),
                            checked = uiState.hideOverlayOnGamepad,
                            onCheckedChange = viewModel::setHideOverlayOnGamepad,
                            helpText = stringResource(R.string.settings_help_hide_overlay_on_gamepad),
                            onResetToDefault = { viewModel.setHideOverlayOnGamepad(overlayDefaults.hideOverlayOnGamepad) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Vibration,
                            title = stringResource(R.string.settings_pad_vibration),
                            subtitle = stringResource(R.string.settings_pad_vibration_desc),
                            checked = uiState.padVibration,
                            onCheckedChange = viewModel::setPadVibration,
                            helpText = stringResource(R.string.settings_help_pad_vibration),
                            onResetToDefault = { viewModel.setPadVibration(defaults.padVibration) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Vibration,
                            title = stringResource(R.string.settings_gamepad_button_haptics),
                            subtitle = stringResource(R.string.settings_gamepad_button_haptics_desc),
                            checked = uiState.gamepadButtonHaptics,
                            onCheckedChange = viewModel::setGamepadButtonHaptics,
                            helpText = stringResource(R.string.settings_help_gamepad_button_haptics),
                            onResetToDefault = { viewModel.setGamepadButtonHaptics(defaults.gamepadButtonHaptics) }
                        )
                        SliderItem(
                            icon = Icons.Rounded.Vibration,
                            title = stringResource(R.string.settings_pad_vibration_strength),
                            subtitle = "${uiState.padVibrationStrength}%",
                            valueLabel = { "${it.roundToInt()}%" },
                            value = uiState.padVibrationStrength.toFloat(),
                            range = 0f..150f,
                            steps = 0,
                            onValueChange = { viewModel.setPadVibrationStrength(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_pad_vibration_strength),
                            onResetToDefault = { viewModel.setPadVibrationStrength(defaults.padVibrationStrength) }
                        )
                        ActionItem(
                            icon = Icons.Rounded.Vibration,
                            title = stringResource(R.string.settings_pad_vibration_test),
                            subtitle = stringResource(R.string.settings_pad_vibration_test_desc),
                            actionIcon = Icons.Rounded.PlayArrow,
                            actionLabel = stringResource(R.string.settings_pad_vibration_test_action),
                            onClick = { viewModel.testPadVibration(uiState.padVibrationStrength, 320L) },
                            helpText = stringResource(R.string.settings_help_pad_vibration_test)
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Vibration,
                            title = stringResource(R.string.settings_pad_vibration_fallback),
                            subtitle = stringResource(R.string.settings_pad_vibration_fallback_desc),
                            checked = uiState.padVibrationFallback,
                            onCheckedChange = viewModel::setPadVibrationFallback,
                            helpText = stringResource(R.string.settings_help_pad_vibration_fallback),
                            onResetToDefault = { viewModel.setPadVibrationFallback(defaults.padVibrationFallback) }
                        )
                        SliderItem(
                            icon = Icons.Rounded.Tune,
                            title = stringResource(R.string.settings_gamepad_stick_deadzone),
                            subtitle = "${uiState.gamepadStickDeadzone}%",
                            value = uiState.gamepadStickDeadzone.toFloat(),
                            range = 0f..35f,
                            steps = 6,
                            onValueChange = { viewModel.setGamepadStickDeadzone(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_gamepad_stick_deadzone),
                            onResetToDefault = { viewModel.setGamepadStickDeadzone(defaults.gamepadStickDeadzone) }
                        )
                        SliderItem(
                            icon = Icons.Rounded.Gamepad,
                            title = stringResource(R.string.settings_gamepad_left_stick_sensitivity),
                            subtitle = "${uiState.gamepadLeftStickSensitivity}%",
                            value = uiState.gamepadLeftStickSensitivity.toFloat(),
                            range = 50f..200f,
                            steps = 14,
                            onValueChange = { viewModel.setGamepadLeftStickSensitivity(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_gamepad_left_stick_sensitivity),
                            onResetToDefault = { viewModel.setGamepadLeftStickSensitivity(defaults.gamepadLeftStickSensitivity) }
                        )
                        SliderItem(
                            icon = Icons.Rounded.Gamepad,
                            title = stringResource(R.string.settings_gamepad_right_stick_sensitivity),
                            subtitle = "${uiState.gamepadRightStickSensitivity}%",
                            value = uiState.gamepadRightStickSensitivity.toFloat(),
                            range = 50f..200f,
                            steps = 14,
                            onValueChange = { viewModel.setGamepadRightStickSensitivity(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_gamepad_right_stick_sensitivity),
                            onResetToDefault = { viewModel.setGamepadRightStickSensitivity(defaults.gamepadRightStickSensitivity) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Gamepad,
                            title = stringResource(R.string.settings_gamepad_right_stick_up_to_r2),
                            subtitle = stringResource(R.string.settings_gamepad_right_stick_up_to_r2_desc),
                            checked = uiState.gamepadRightStickUpToR2,
                            onCheckedChange = viewModel::setGamepadRightStickUpToR2,
                            helpText = stringResource(R.string.settings_help_gamepad_right_stick_up_to_r2),
                            onResetToDefault = { viewModel.setGamepadRightStickUpToR2(defaults.gamepadRightStickUpToR2) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Gamepad,
                            title = stringResource(R.string.settings_gamepad_right_stick_down_to_l2),
                            subtitle = stringResource(R.string.settings_gamepad_right_stick_down_to_l2_desc),
                            checked = uiState.gamepadRightStickDownToL2,
                            onCheckedChange = viewModel::setGamepadRightStickDownToL2,
                            helpText = stringResource(R.string.settings_help_gamepad_right_stick_down_to_l2),
                            onResetToDefault = { viewModel.setGamepadRightStickDownToL2(defaults.gamepadRightStickDownToL2) }
                        )
                    }
                    SettingsSection(title = stringResource(R.string.settings_gamepad_mapping_title)) {
                        val selectedBindings = uiState.gamepadBindingsByPad[selectedGamepadPadIndex].orEmpty()
                        val connectedControllerName = GamepadManager.connectedControllerName(selectedGamepadPadIndex)
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .tvFocusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(end = 4.dp)
                        ) {
                            items(listOf(0, 1)) { padIndex ->
                                val interactionSource = remember { MutableInteractionSource() }
                                FilterChip(
                                    modifier = Modifier.tvGamepadFocusableCard(
                                        shape = neonShape(16.dp),
                                        interactionSource = interactionSource,
                                        addFocusTarget = false
                                    ),
                                    shape = neonChipShape(),
                                    selected = selectedGamepadPadIndex == padIndex,
                                    onClick = { selectedGamepadPadIndex = padIndex },
                                    interactionSource = interactionSource,
                                    label = { Text(gamepadPlayerLabel(padIndex)) }
                                )
                            }
                        }
                        SettingsInlineNote(
                            text = connectedControllerName?.let {
                                stringResource(
                                    R.string.settings_gamepad_mapping_player_connected,
                                    gamepadPlayerLabel(selectedGamepadPadIndex),
                                    it
                                )
                            } ?: stringResource(
                                R.string.settings_gamepad_mapping_player_disconnected,
                                gamepadPlayerLabel(selectedGamepadPadIndex)
                            )
                        )
                        for (action in gamepadActions) {
                            val assignedKeyCode = GamepadManager.resolveBindingForAction(
                                actionId = action.id,
                                customBindings = selectedBindings
                            )
                            val isCustomBinding = selectedBindings.containsKey(action.id)
                            GamepadBindingRow(
                                title = gamepadActionLabel(action.id),
                                value = assignedKeyCode?.let(GamepadManager::keyCodeLabel)
                                    ?: stringResource(R.string.settings_not_set),
                                autoLabel = if (isCustomBinding || action.defaultKeyCodes.isEmpty()) null else {
                                    stringResource(R.string.settings_gamepad_mapping_auto_format)
                                },
                                onBindClick = { onRequestGamepadBinding(selectedGamepadPadIndex, action.id) },
                                onClearClick = if (isCustomBinding) {
                                    { viewModel.clearGamepadBinding(selectedGamepadPadIndex, action.id) }
                                } else {
                                    null
                                }
                            )
                        }
                        SettingsItem(
                            icon = Icons.Rounded.SettingsSuggest,
                            label = stringResource(R.string.settings_gamepad_mapping_reset_title),
                            value = stringResource(R.string.settings_gamepad_mapping_reset_desc),
                            onClick = { viewModel.resetGamepadBindingsForPad(selectedGamepadPadIndex) }
                        )
                    }

                    SettingsSection(title = stringResource(R.string.settings_core_input)) {
                        ChoiceSection(
                            title = stringResource(R.string.settings_multitap_mode),
                            options = listOf(
                                0 to stringResource(R.string.settings_multitap_off),
                                1 to stringResource(R.string.settings_multitap_port1),
                                2 to stringResource(R.string.settings_multitap_port2),
                                3 to stringResource(R.string.settings_multitap_both)
                            ),
                            selectedValue = uiState.multitapMode,
                            onSelect = viewModel::setMultitapMode,
                            onResetToDefault = { viewModel.setMultitapMode(defaults.multitapMode) }
                        )
                        var coreControlsVersion by remember { mutableIntStateOf(0) }
                        CoreOptionSettingsRows(
                            options = remember { SwanStationCoreOptions.controlsOptions() },
                            version = coreControlsVersion,
                            onValueChange = { key, value ->
                                NativeApp.setCoreOption(key, value)
                                coreControlsVersion++
                            }
                        )
                    }
                }

                SettingsTab.Library -> {
                    val biosDisplayName = remember(uiState.biosPath, context, notSetLabel) {
                        uiState.biosPath?.let { DocumentPathResolver.getFallbackDisplayName(it) }
                            ?: notSetLabel
                    }
                    val gameDisplayName = if (uiState.gamePaths.isEmpty()) {
                        notSetLabel
                    } else {
                        stringResource(R.string.settings_game_folders_count, uiState.gamePaths.size)
                    }
                    val emulatorDataDisplayName = when {
                        uiState.emulatorDataPath.isNullOrBlank() -> stringResource(
                            R.string.emulator_data_location_internal
                        )
                        uiState.sdCardDataPath != null && uiState.emulatorDataPath == uiState.sdCardDataPath -> {
                            stringResource(R.string.emulator_data_location_sd_card)
                        }
                        else -> DocumentPathResolver.getFallbackDisplayName(uiState.emulatorDataPath)
                    }
                    val repository = remember(context) {
                        MemoryCardRepository(context, AppPreferences(context))
                    }
                    var memoryCardCount by remember { mutableIntStateOf(0) }
                    var slot1Name by remember { mutableStateOf<String?>(null) }
                    var slot2Name by remember { mutableStateOf<String?>(null) }
                    val builtInCoverSourceLabel = stringResource(R.string.settings_cover_download_url_builtin)
                    val coverDownloadDisabledLabel = stringResource(R.string.settings_cover_download_url_disabled)
                    val customCoverSourceLabel = stringResource(R.string.settings_cover_download_url_custom)
                    val coverUrlDisplay = if (!uiState.coverDownloadBaseUrl.isNullOrBlank()) {
                        customCoverSourceLabel
                    } else if (uiState.coverArtStyle == AppPreferences.COVER_ART_STYLE_DISABLED) {
                        coverDownloadDisabledLabel
                    } else {
                        builtInCoverSourceLabel
                    }
                    LaunchedEffect(repository) {
                        val assignments = repository.ensureDefaultCardsAssigned()
                        val cards = repository.listCards()
                        memoryCardCount = cards.size
                        slot1Name = assignments.slot1
                        slot2Name = assignments.slot2
                    }

                    SettingsSection(title = stringResource(R.string.settings_paths)) {
                        SettingsItem(
                            icon = Icons.Rounded.Memory,
                            label = stringResource(R.string.settings_bios_path),
                            value = biosDisplayName,
                            onClick = launchBiosPicker,
                            helpText = stringResource(R.string.settings_help_bios_path)
                        )
                        SettingsItem(
                            icon = Icons.Rounded.FolderOpen,
                            label = stringResource(R.string.settings_game_path),
                            value = gameDisplayName,
                            onClick = launchGamePicker,
                            helpText = stringResource(R.string.settings_help_game_path)
                        )
                        uiState.gamePaths.forEach { path ->
                            SettingsItem(
                                icon = Icons.Rounded.DeleteOutline,
                                label = DocumentPathResolver.getFallbackDisplayName(path),
                                value = stringResource(R.string.game_folders_remove),
                                onClick = { viewModel.removeGamePath(path) }
                            )
                        }
                        SettingsItem(
                            icon = Icons.Rounded.SaveAs,
                            label = stringResource(R.string.emulator_data_location_title),
                            value = emulatorDataDisplayName,
                            onClick = openEmulatorDataLocationDialog,
                            helpText = stringResource(R.string.emulator_data_location_description)
                        )
                    }

                    SettingsSection(title = stringResource(R.string.settings_memory_cards_tab)) {
                        SettingsItem(
                            icon = Icons.Rounded.Memory,
                            label = stringResource(R.string.settings_memory_cards_open),
                            value = stringResource(R.string.settings_memory_cards_open_desc),
                            onClick = { onOpenMemoryCardManager?.invoke() }
                        )
                        SettingsInlineNote(
                            text = stringResource(
                                R.string.settings_memory_cards_summary,
                                memoryCardCount,
                                slot1Name ?: stringResource(R.string.memory_card_slot_empty),
                                slot2Name ?: stringResource(R.string.memory_card_slot_empty)
                            )
                        )
                    }

                    SettingsSection(title = stringResource(R.string.settings_covers_tab)) {
                        ChoiceSection(
                            title = stringResource(R.string.settings_cover_art_style),
                            options = listOf(
                                AppPreferences.COVER_ART_STYLE_DISABLED to stringResource(R.string.settings_cover_art_style_off),
                                AppPreferences.COVER_ART_STYLE_DEFAULT to stringResource(R.string.settings_cover_art_style_flat),
                                AppPreferences.COVER_ART_STYLE_3D to stringResource(R.string.settings_cover_art_style_3d)
                            ),
                            selectedValue = uiState.coverArtStyle,
                            onSelect = viewModel::setCoverArtStyle,
                            helpText = stringResource(R.string.settings_help_cover_art_style),
                              onResetToDefault = { viewModel.setCoverArtStyle(AppPreferences.COVER_ART_STYLE_DEFAULT) }
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Link,
                            label = stringResource(R.string.settings_cover_download_url),
                            value = coverUrlDisplay,
                            onClick = onOpenCoverUrlEditor,
                            helpText = stringResource(R.string.settings_help_cover_download_url)
                        )
                        SettingsItem(
                            icon = Icons.Rounded.DeleteOutline,
                            label = stringResource(R.string.settings_clear_cover_cache),
                            value = stringResource(R.string.settings_clear_cover_cache_desc),
                            onClick = onClearCoverCache
                        )
                    }

                    SettingsSection(title = stringResource(R.string.settings_backup_section_title)) {
                        SettingsItem(
                            icon = Icons.Rounded.Save,
                            label = stringResource(R.string.settings_backup_export_title),
                            value = stringResource(R.string.settings_backup_export_desc),
                            onClick = launchSettingsBackupExport
                        )
                        SettingsItem(
                            icon = Icons.Rounded.FolderOpen,
                            label = stringResource(R.string.settings_backup_restore_title),
                            value = stringResource(R.string.settings_backup_restore_desc),
                            onClick = launchSettingsBackupImport
                        )
                    }
                }

                SettingsTab.Graphics -> {
                    GooseCoreSettings("display", "Display", appValues = emptyMap(),
                        setAppValue = { _, _ -> })
                }

                SettingsTab.Patches -> {
                    SettingsSection(title = stringResource(R.string.settings_patches_tab)) {
                        SettingsItem(
                            icon = if (uiState.installedPatchCount > 0) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.SystemUpdateAlt
                            },
                            label = stringResource(R.string.settings_patches_download),
                            value = when {
                                uiState.isPatchDatabaseBusy &&
                                    uiState.patchDatabaseProgress?.stage == PatchDatabaseInstallStage.DOWNLOADING ->
                                    stringResource(R.string.texture_download_status_downloading)
                                uiState.isPatchDatabaseBusy ->
                                    stringResource(R.string.settings_shader_pack_working)
                                uiState.installedPatchCount > 0 ->
                                    stringResource(
                                        R.string.settings_patches_installed_value,
                                        uiState.installedPatchCount
                                    )
                                !uiState.patchDatabaseUseOfficial &&
                                    uiState.patchDatabaseCustomUrl.isNullOrBlank() ->
                                    stringResource(R.string.settings_patches_source_missing)
                                else -> stringResource(R.string.settings_patches_download_desc)
                            },
                            onClick = viewModel::downloadPatchDatabase,
                            enabled = !uiState.isPatchDatabaseBusy &&
                                (uiState.patchDatabaseUseOfficial ||
                                    !uiState.patchDatabaseCustomUrl.isNullOrBlank()),
                            progressVisible = uiState.isPatchDatabaseBusy,
                            progress = uiState.patchDatabaseProgress?.fraction
                        )
                        ToggleItem(
                            icon = Icons.Rounded.Language,
                            title = stringResource(R.string.settings_patches_source_official),
                            subtitle = stringResource(R.string.settings_patches_source_official_desc),
                            checked = uiState.patchDatabaseUseOfficial,
                            onCheckedChange = viewModel::setPatchDatabaseUseOfficial,
                            helpText = stringResource(R.string.settings_help_patches_source_official),
                            onResetToDefault = { viewModel.setPatchDatabaseUseOfficial(true) }
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Link,
                            label = stringResource(R.string.settings_patches_custom_url),
                            value = uiState.patchDatabaseCustomUrl?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.settings_not_set),
                            onClick = onOpenPatchUrlEditor
                        )
                        AboutNote(
                            title = stringResource(R.string.settings_patches_attribution_title),
                            body = stringResource(R.string.settings_patches_attribution),
                            linkLabel = stringResource(R.string.settings_patches_source_link),
                            linkUrl = PatchDatabaseDownloader.OFFICIAL_SOURCE_PAGE_URL
                        )

                        ToggleItem(
                            icon = Icons.Rounded.Tune,
                            title = stringResource(R.string.settings_no_interlacing_patches),
                            subtitle = stringResource(R.string.settings_no_interlacing_patches_desc),
                            checked = uiState.enableNoInterlacingPatches,
                            onCheckedChange = viewModel::setEnableNoInterlacingPatches,
                            helpText = stringResource(R.string.settings_help_no_interlacing_patches),
                            onResetToDefault = {
                                viewModel.setEnableNoInterlacingPatches(defaults.enableNoInterlacingPatches)
                            }
                        )
                    }
                }

                SettingsTab.Emulation -> {
                    SettingsSection(title = stringResource(R.string.emulation_performance_stats)) {
                        ToggleItem(
                            icon = Icons.Rounded.Speed,
                            title = stringResource(R.string.settings_show_fps),
                            subtitle = stringResource(R.string.settings_show_fps_desc),
                            checked = uiState.showFps,
                            onCheckedChange = viewModel::setShowFps,
                            helpText = stringResource(R.string.settings_help_show_fps),
                            onResetToDefault = { viewModel.setShowFps(defaults.showFps) }
                        )
                        ToggleItem(
                            icon = Icons.Rounded.FastForward,
                            title = stringResource(R.string.settings_frame_limit),
                            subtitle = stringResource(R.string.settings_frame_limit_desc),
                            checked = uiState.frameLimitEnabled,
                            onCheckedChange = viewModel::setFrameLimitEnabled,
                            onResetToDefault = { viewModel.setFrameLimitEnabled(defaults.frameLimitEnabled) }
                        )
                        ChoiceSection(
                            title = stringResource(R.string.settings_target_fps),
                            options = listOf(
                                0 to stringResource(R.string.settings_aspect_ratio_auto),
                                50 to "50 Hz",
                                60 to "60 Hz"
                            ),
                            selectedValue = uiState.targetFps,
                            onSelect = viewModel::setTargetFps,
                            helpText = stringResource(R.string.settings_target_fps_desc),
                            onResetToDefault = { viewModel.setTargetFps(defaults.targetFps) }
                        )
                        SliderItem(
                            icon = Icons.Rounded.Timelapse,
                            title = stringResource(R.string.settings_vertical_refresh_ntsc),
                            subtitle = String.format(Locale.US, "%.2f Hz", uiState.ntscFramerate),
                            value = uiState.ntscFramerate,
                            range = AppPreferences.MIN_REGION_FRAMERATE..AppPreferences.MAX_REGION_FRAMERATE,
                            steps = 0,
                            onValueChange = viewModel::setNtscFramerate,
                            valueLabel = { String.format(Locale.US, "%.2f Hz", it) },
                            helpText = stringResource(R.string.settings_help_vertical_refresh),
                            onResetToDefault = {
                                viewModel.setNtscFramerate(defaults.ntscFramerate)
                            }
                        )
                        SliderItem(
                            icon = Icons.Rounded.Timelapse,
                            title = stringResource(R.string.settings_vertical_refresh_pal),
                            subtitle = String.format(Locale.US, "%.2f Hz", uiState.palFramerate),
                            value = uiState.palFramerate,
                            range = AppPreferences.MIN_REGION_FRAMERATE..AppPreferences.MAX_REGION_FRAMERATE,
                            steps = 0,
                            onValueChange = viewModel::setPalFramerate,
                            valueLabel = { String.format(Locale.US, "%.2f Hz", it) },
                            helpText = stringResource(R.string.settings_help_vertical_refresh),
                            onResetToDefault = {
                                viewModel.setPalFramerate(defaults.palFramerate)
                            }
                        )
                        ChoiceSection(
                            title = stringResource(R.string.settings_frame_skip),
                            options = listOf(
                                0 to stringResource(R.string.settings_disabled_short),
                                1 to "1",
                                2 to "2",
                                3 to "3",
                                4 to "4"
                            ),
                            selectedValue = uiState.frameSkip,
                            onSelect = viewModel::setFrameSkip,
                            helpText = stringResource(R.string.settings_frame_skip_desc),
                            onResetToDefault = { viewModel.setFrameSkip(defaults.frameSkip) }
                        )
                        ChoiceSection(
                            title = stringResource(R.string.settings_fps_overlay_position),
                            options = fpsOverlayCornerOptions(),
                            selectedValue = uiState.fpsOverlayCorner,
                            onSelect = viewModel::setFpsOverlayCorner,
                            helpText = stringResource(R.string.settings_help_fps_overlay_position),
                            onResetToDefault = { viewModel.setFpsOverlayCorner(defaults.fpsOverlayCorner) }
                        )
                        SliderItem(
                            icon = Icons.Rounded.FormatSize,
                            title = stringResource(R.string.settings_fps_overlay_scale),
                            subtitle = stringResource(R.string.settings_fps_overlay_scale_value, uiState.fpsOverlayScale),
                            value = uiState.fpsOverlayScale.toFloat(),
                            range = AppPreferences.MIN_FPS_OVERLAY_SCALE.toFloat()..AppPreferences.MAX_FPS_OVERLAY_SCALE.toFloat(),
                            steps = 24,
                            onValueChange = { viewModel.setFpsOverlayScale(it.toInt()) },
                            valueLabel = { "${it.toInt()}%" },
                            helpText = stringResource(R.string.settings_help_fps_overlay_scale),
                            onResetToDefault = { viewModel.setFpsOverlayScale(defaults.fpsOverlayScale) }
                        )
                        if (uiState.fpsOverlayMode == FPS_OVERLAY_MODE_DETAILED) {
                            BitmaskChoiceSection(
                                title = stringResource(R.string.settings_fps_overlay_metrics),
                                options = fpsOverlayMetricOptions(),
                                selectedMask = uiState.fpsOverlayMetrics,
                                onToggle = { metric ->
                                    viewModel.setFpsOverlayMetrics(uiState.fpsOverlayMetrics xor metric)
                                },
                                helpText = stringResource(R.string.settings_help_fps_overlay_metrics),
                                onResetToDefault = { viewModel.setFpsOverlayMetrics(defaults.fpsOverlayMetrics) }
                            )
                        }
                    }


                }


                SettingsTab.System -> {
                    GooseCoreSettings("system", "System", appValues = mapOf(
                        "goosestation_renderer" to when (RendererDefaults.normalizeAndroidRenderer(uiState.renderer)) {
                            12 -> "OpenGL"
                            13 -> "Software"
                            else -> "Vulkan"
                        },
                        "goosestation_resolution_scale" to uiState.upscaleMultiplier.toInt().toString(),
                        "goosestation_bios_fast_boot" to uiState.enableFastBoot.toString(),
                        "goosestation_widescreen_hack" to uiState.enableWidescreenPatches.toString(),
                        "goosestation_cdrom_mute_cd_audio" to (!uiState.enableCddaAudio).toString()
                    ), setAppValue = { key, value ->
                        when (key) {
                            "goosestation_renderer" -> viewModel.setRenderer(when (value) {
                                "OpenGL" -> 12
                                "Software" -> 13
                                else -> 14
                            })
                            "goosestation_resolution_scale" -> viewModel.setUpscaleMultiplier(value.toFloat())
                            "goosestation_bios_fast_boot" -> viewModel.setEnableFastBoot(value.toBoolean())
                            "goosestation_widescreen_hack" -> viewModel.setEnableWidescreenPatches(value.toBoolean())
                            "goosestation_cdrom_mute_cd_audio" -> viewModel.setEnableCddaAudio(!value.toBoolean())
                        }
                    })
                }
                SettingsTab.BIOS -> {
                    GooseCoreSettings("bios", "BIOS", appValues = mapOf(
                        "goosestation_renderer" to when (RendererDefaults.normalizeAndroidRenderer(uiState.renderer)) {
                            12 -> "OpenGL"
                            13 -> "Software"
                            else -> "Vulkan"
                        },
                        "goosestation_resolution_scale" to uiState.upscaleMultiplier.toInt().toString(),
                        "goosestation_bios_fast_boot" to uiState.enableFastBoot.toString(),
                        "goosestation_widescreen_hack" to uiState.enableWidescreenPatches.toString(),
                        "goosestation_cdrom_mute_cd_audio" to (!uiState.enableCddaAudio).toString()
                    ), setAppValue = { key, value ->
                        when (key) {
                            "goosestation_renderer" -> viewModel.setRenderer(when (value) {
                                "OpenGL" -> 12
                                "Software" -> 13
                                else -> 14
                            })
                            "goosestation_resolution_scale" -> viewModel.setUpscaleMultiplier(value.toFloat())
                            "goosestation_bios_fast_boot" -> viewModel.setEnableFastBoot(value.toBoolean())
                            "goosestation_widescreen_hack" -> viewModel.setEnableWidescreenPatches(value.toBoolean())
                            "goosestation_cdrom_mute_cd_audio" -> viewModel.setEnableCddaAudio(!value.toBoolean())
                        }
                    })
                }
                SettingsTab.Video -> {
                    GooseCoreSettings("video", "Video", appValues = mapOf(
                        "goosestation_renderer" to when (RendererDefaults.normalizeAndroidRenderer(uiState.renderer)) {
                            12 -> "OpenGL"
                            13 -> "Software"
                            else -> "Vulkan"
                        },
                        "goosestation_resolution_scale" to uiState.upscaleMultiplier.toInt().toString(),
                        "goosestation_bios_fast_boot" to uiState.enableFastBoot.toString(),
                        "goosestation_widescreen_hack" to uiState.enableWidescreenPatches.toString(),
                        "goosestation_cdrom_mute_cd_audio" to (!uiState.enableCddaAudio).toString()
                    ), setAppValue = { key, value ->
                        when (key) {
                            "goosestation_renderer" -> viewModel.setRenderer(when (value) {
                                "OpenGL" -> 12
                                "Software" -> 13
                                else -> 14
                            })
                            "goosestation_resolution_scale" -> viewModel.setUpscaleMultiplier(value.toFloat())
                            "goosestation_bios_fast_boot" -> viewModel.setEnableFastBoot(value.toBoolean())
                            "goosestation_widescreen_hack" -> viewModel.setEnableWidescreenPatches(value.toBoolean())
                            "goosestation_cdrom_mute_cd_audio" -> viewModel.setEnableCddaAudio(!value.toBoolean())
                        }
                    })

                }
                SettingsTab.Shaders -> {
                    SettingsSection(title = stringResource(R.string.settings_retroarch_shaders)) {
                        ToggleItem(
                            icon = Icons.Rounded.AutoFixHigh,
                            title = stringResource(R.string.settings_retroarch_shaders),
                            subtitle = stringResource(R.string.settings_retroarch_shaders_desc),
                            checked = uiState.shaderChainEnabled,
                            onCheckedChange = viewModel::setShaderChainEnabled,
                            helpText = stringResource(R.string.settings_help_retroarch_shaders),
                            onResetToDefault = { viewModel.setShaderChainEnabled(false) }
                        )
                        ShaderPresetSelector(
                            title = stringResource(R.string.settings_shader_preset),
                            presets = uiState.shaderPresets,
                            selectedPath = uiState.shaderChainPreset,
                            onSelect = viewModel::setShaderChainPreset,
                            helpText = stringResource(R.string.settings_help_shader_preset)
                        )
                        SettingsItem(
                            icon = if (uiState.isShaderPackInstalled) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.SystemUpdateAlt
                            },
                            label = stringResource(R.string.settings_shader_pack_download),
                            value = when {
                                uiState.isShaderPackBusy &&
                                    uiState.shaderPackProgress?.stage == ShaderPackInstallStage.DOWNLOADING ->
                                    stringResource(R.string.texture_download_status_downloading)
                                uiState.isShaderPackBusy -> stringResource(R.string.settings_shader_pack_working)
                                uiState.isShaderPackInstalled -> stringResource(R.string.settings_shader_pack_installed)
                                else -> stringResource(R.string.settings_shader_pack_download_desc)
                            },
                            onClick = viewModel::downloadOfficialShaderPack,
                            enabled = !uiState.isShaderPackBusy && !uiState.isShaderPackInstalled,
                            progressVisible = uiState.isShaderPackBusy,
                            progress = uiState.shaderPackProgress?.fraction
                        )
                        SettingsItem(
                            icon = Icons.Rounded.FolderOpen,
                            label = stringResource(R.string.settings_shader_pack_import),
                            value = stringResource(R.string.settings_shader_pack_import_desc),
                            onClick = launchShaderPackPicker
                        )
                    }
                }
                SettingsTab.CDROM -> {
                    GooseCoreSettings("cdrom", "CD-ROM", appValues = mapOf(
                        "goosestation_renderer" to when (RendererDefaults.normalizeAndroidRenderer(uiState.renderer)) {
                            12 -> "OpenGL"
                            13 -> "Software"
                            else -> "Vulkan"
                        },
                        "goosestation_resolution_scale" to uiState.upscaleMultiplier.toInt().toString(),
                        "goosestation_bios_fast_boot" to uiState.enableFastBoot.toString(),
                        "goosestation_widescreen_hack" to uiState.enableWidescreenPatches.toString(),
                        "goosestation_cdrom_mute_cd_audio" to (!uiState.enableCddaAudio).toString()
                    ), setAppValue = { key, value ->
                        when (key) {
                            "goosestation_renderer" -> viewModel.setRenderer(when (value) {
                                "OpenGL" -> 12
                                "Software" -> 13
                                else -> 14
                            })
                            "goosestation_resolution_scale" -> viewModel.setUpscaleMultiplier(value.toFloat())
                            "goosestation_bios_fast_boot" -> viewModel.setEnableFastBoot(value.toBoolean())
                            "goosestation_widescreen_hack" -> viewModel.setEnableWidescreenPatches(value.toBoolean())
                            "goosestation_cdrom_mute_cd_audio" -> viewModel.setEnableCddaAudio(!value.toBoolean())
                        }
                    })
                }
                SettingsTab.TextureReplacements -> {
                    GooseCoreSettings("texture_replacements", "Texture Replacements", appValues = mapOf(
                        "goosestation_renderer" to when (RendererDefaults.normalizeAndroidRenderer(uiState.renderer)) {
                            12 -> "OpenGL"
                            13 -> "Software"
                            else -> "Vulkan"
                        },
                        "goosestation_resolution_scale" to uiState.upscaleMultiplier.toInt().toString(),
                        "goosestation_bios_fast_boot" to uiState.enableFastBoot.toString(),
                        "goosestation_widescreen_hack" to uiState.enableWidescreenPatches.toString(),
                        "goosestation_cdrom_mute_cd_audio" to (!uiState.enableCddaAudio).toString()
                    ), setAppValue = { key, value ->
                        when (key) {
                            "goosestation_renderer" -> viewModel.setRenderer(when (value) {
                                "OpenGL" -> 12
                                "Software" -> 13
                                else -> 14
                            })
                            "goosestation_resolution_scale" -> viewModel.setUpscaleMultiplier(value.toFloat())
                            "goosestation_bios_fast_boot" -> viewModel.setEnableFastBoot(value.toBoolean())
                            "goosestation_widescreen_hack" -> viewModel.setEnableWidescreenPatches(value.toBoolean())
                            "goosestation_cdrom_mute_cd_audio" -> viewModel.setEnableCddaAudio(!value.toBoolean())
                        }
                    })
                }
                SettingsTab.Hacks -> {
                    GooseCoreSettings("hacks", "Hacks", appValues = mapOf(
                        "goosestation_renderer" to when (RendererDefaults.normalizeAndroidRenderer(uiState.renderer)) {
                            12 -> "OpenGL"
                            13 -> "Software"
                            else -> "Vulkan"
                        },
                        "goosestation_resolution_scale" to uiState.upscaleMultiplier.toInt().toString(),
                        "goosestation_bios_fast_boot" to uiState.enableFastBoot.toString(),
                        "goosestation_widescreen_hack" to uiState.enableWidescreenPatches.toString(),
                        "goosestation_cdrom_mute_cd_audio" to (!uiState.enableCddaAudio).toString()
                    ), setAppValue = { key, value ->
                        when (key) {
                            "goosestation_renderer" -> viewModel.setRenderer(when (value) {
                                "OpenGL" -> 12
                                "Software" -> 13
                                else -> 14
                            })
                            "goosestation_resolution_scale" -> viewModel.setUpscaleMultiplier(value.toFloat())
                            "goosestation_bios_fast_boot" -> viewModel.setEnableFastBoot(value.toBoolean())
                            "goosestation_widescreen_hack" -> viewModel.setEnableWidescreenPatches(value.toBoolean())
                            "goosestation_cdrom_mute_cd_audio" -> viewModel.setEnableCddaAudio(!value.toBoolean())
                        }
                    })
                }
                SettingsTab.PGXP -> {
                    GoosePgxpSettings()
                }
                SettingsTab.SkipEngine -> {
                    GooseSkipSettings()
                }


                SettingsTab.About -> {
                    SettingsSection(title = stringResource(R.string.settings_about)) {
                        SettingsItem(icon = Icons.Rounded.Info,
                            label = stringResource(R.string.settings_version),
                            value = BuildConfig.VERSION_NAME, onClick = {})
                        SettingsItem(icon = Icons.Rounded.Memory,
                            label = stringResource(R.string.settings_emulator_core),
                            value = "goosestation 0.6.9", onClick = {})
                        SettingsItem(icon = Icons.Rounded.Info,
                            label = stringResource(R.string.duke_developer),
                            value = "xxxCrixuxxx + GPT ASTRA", onClick = {})
                        SettingsItem(icon = Icons.Rounded.Info,
                            label = "DukeStation Repository",
                            value = "https://github.com/thecrixux/DukeStation",
                            onClick = { openUriInChrome(context, "https://github.com/thecrixux/DukeStation") })
                    }
                }
            }
        }
    }
}

@Composable
private fun homeBackgroundPresetLabel(preset: HomeBackgroundPreset): String = stringResource(
    when (preset) {
        HomeBackgroundPreset.OLYMPUS -> R.string.settings_customization_background_preset_olympus
        HomeBackgroundPreset.NEON_RACING -> R.string.settings_customization_background_preset_neon_racing
        HomeBackgroundPreset.TROPICAL_RUINS -> R.string.settings_customization_background_preset_tropical_ruins
        HomeBackgroundPreset.COLOSSUS_VALLEY -> R.string.settings_customization_background_preset_colossus_valley
        HomeBackgroundPreset.STEALTH_JUNGLE -> R.string.settings_customization_background_preset_stealth_jungle
        HomeBackgroundPreset.GOTHIC_CITY -> R.string.settings_customization_background_preset_gothic_city
        HomeBackgroundPreset.WEST_COAST -> R.string.settings_customization_background_preset_west_coast
        HomeBackgroundPreset.SAMURAI_NIGHT -> R.string.settings_customization_background_preset_samurai_night
        HomeBackgroundPreset.CRYSTAL_PILGRIMAGE -> R.string.settings_customization_background_preset_crystal_pilgrimage
    }
)

@Composable
private fun CustomizationSettingsTab(
    uiState: SettingsUiState,
    onPickBackground: () -> Unit,
    onPickSideArtwork: () -> Unit,
    onPickCustomFont: () -> Unit,
    onOpenTouchControlCreator: (() -> Unit)?,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val windowWidthDp = with(density) { windowSize.width.toDp().value.roundToInt() }.coerceAtLeast(1)
    val windowHeightDp = with(density) { windowSize.height.toDp().value.roundToInt() }.coerceAtLeast(1)
    val previewColumns = calculateHomeGridColumnCount(
        screenWidthDp = windowWidthDp,
        screenHeightDp = windowHeightDp,
        smallestScreenWidthDp = minOf(windowWidthDp, windowHeightDp),
        gridScale = uiState.homeGridScale
    )
    val sideArtworkPreviewLayout = calculateSideArtworkPreviewLayout(windowWidthDp, windowHeightDp)
    val backgroundRepository = remember(context) { HomeBackgroundRepository(context) }
    val sideArtworkRepository = remember(context) { EmulationSideArtworkRepository(context) }
    val backgroundFile = backgroundRepository.existingFile(uiState.homeBackgroundType)
    val backgroundLabel = when (uiState.homeBackgroundType) {
        HomeBackgroundType.NONE -> stringResource(R.string.settings_customization_background_none)
        HomeBackgroundType.IMAGE -> stringResource(R.string.settings_customization_background_image)
        HomeBackgroundType.GIF -> stringResource(R.string.settings_customization_background_gif)
        HomeBackgroundType.VIDEO -> stringResource(R.string.settings_customization_background_video)
        HomeBackgroundType.BUILT_IN -> homeBackgroundPresetLabel(uiState.homeBackgroundPreset)
    }
    val hasCustomSideArtwork = sideArtworkRepository.existingCustomFile() != null
    val sideArtworkOptions = listOf(
        EmulationSideArtwork.NONE.preferenceValue to stringResource(R.string.settings_customization_side_artwork_none),
        EmulationSideArtwork.OLYMPUS.preferenceValue to stringResource(R.string.settings_customization_side_artwork_olympus),
        EmulationSideArtwork.NIGHT_RACING.preferenceValue to stringResource(R.string.settings_customization_side_artwork_night_racing),
        EmulationSideArtwork.JUNGLE.preferenceValue to stringResource(R.string.settings_customization_side_artwork_jungle),
        EmulationSideArtwork.COLOSSUS.preferenceValue to stringResource(R.string.settings_customization_side_artwork_colossus),
        EmulationSideArtwork.GOTHIC.preferenceValue to stringResource(R.string.settings_customization_side_artwork_gothic),
        EmulationSideArtwork.STEALTH.preferenceValue to stringResource(R.string.settings_customization_side_artwork_stealth),
        EmulationSideArtwork.SAMURAI.preferenceValue to stringResource(R.string.settings_customization_side_artwork_samurai),
        EmulationSideArtwork.WEST_COAST.preferenceValue to stringResource(R.string.settings_customization_side_artwork_west_coast),
        EmulationSideArtwork.CRYSTAL.preferenceValue to stringResource(R.string.settings_customization_side_artwork_crystal)
    ) + if (hasCustomSideArtwork) {
        listOf(EmulationSideArtwork.CUSTOM.preferenceValue to stringResource(R.string.settings_customization_side_artwork_custom))
    } else {
        emptyList()
    }

    SettingsSection(title = stringResource(R.string.settings_customization_preview)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(210.dp),
            shape = neonShape(22.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                HomeBackgroundMedia(
                    type = uiState.homeBackgroundType,
                    file = backgroundFile,
                    preset = uiState.homeBackgroundPreset,
                    revision = uiState.homeBackgroundRevision,
                    modifier = Modifier.fillMaxSize()
                )
                if (uiState.homeBackgroundType != HomeBackgroundType.NONE) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.background.copy(
                                    alpha = uiState.homeBackgroundDim / 100f
                                )
                            )
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                            )
                        ) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.settings_customization_games_per_row,
                                    previewColumns,
                                    previewColumns
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.settings_customization_preview_caption),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        repeat(previewColumns) { index ->
                            Surface(
                                modifier = Modifier
                                    .width(52.dp * uiState.homeGridScale)
                                    .aspectRatio(GameCoverAspectRatio),
                                shape = neonShape(10.dp),
                                color = when (index % 3) {
                                    0 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                                    1 -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)
                                    else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(6.dp),
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.78f)
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f))
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.52f)
                                            .height(2.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                                    )
                                }
                            }
                        }
                    }
                }
                if (uiState.isBackgroundImporting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    SettingsSection(title = stringResource(R.string.settings_customization_background_section)) {
        Text(
            text = stringResource(R.string.settings_customization_background_presets),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(HomeBackgroundPreset.entries, key = { it.preferenceValue }) { preset ->
                val selected = uiState.homeBackgroundType == HomeBackgroundType.BUILT_IN &&
                    uiState.homeBackgroundPreset == preset
                Surface(
                    modifier = Modifier
                        .width(156.dp)
                        .aspectRatio(16f / 9f)
                        .clickable { viewModel.setHomeBackgroundPreset(preset) },
                    shape = neonShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        HomeBackgroundMedia(
                            type = HomeBackgroundType.BUILT_IN,
                            file = null,
                            preset = preset,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.35f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.82f)
                                    )
                                )
                        )
                        Text(
                            text = homeBackgroundPresetLabel(preset),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        SettingsItem(
            icon = Icons.Rounded.Wallpaper,
            label = stringResource(R.string.settings_customization_background),
            value = backgroundLabel,
            onClick = onPickBackground,
            helpText = stringResource(R.string.settings_customization_background_help)
        )
        if (uiState.homeBackgroundType != HomeBackgroundType.NONE) {
            SliderItem(
                icon = Icons.Rounded.Visibility,
                title = stringResource(R.string.settings_customization_background_dim),
                subtitle = "",
                value = uiState.homeBackgroundDim.toFloat(),
                range = 0f..85f,
                steps = 16,
                onValueChange = { viewModel.setHomeBackgroundDim(it.roundToInt()) },
                valueLabel = { "${it.roundToInt()}%" },
                onResetToDefault = {
                    viewModel.setHomeBackgroundDim(AppPreferences.DEFAULT_HOME_BACKGROUND_DIM)
                }
            )
            SettingsItem(
                icon = Icons.Rounded.DeleteOutline,
                label = stringResource(R.string.settings_customization_remove_background),
                value = stringResource(R.string.settings_customization_remove_background_desc),
                onClick = viewModel::clearHomeBackground
            )
        }
    }

    SettingsSection(title = stringResource(R.string.settings_customization_side_artwork_section)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(sideArtworkPreviewLayout.heightDp.dp),
            shape = neonShape(18.dp),
            color = Color.Black,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                EmulationSideArtworkOverlay(
                    artwork = uiState.emulationSideArtwork,
                    revision = uiState.emulationSideArtworkRevision,
                    aspectRatioMode = 2,
                    modifier = Modifier.fillMaxSize(),
                    preview = true,
                    previewContentFraction = sideArtworkPreviewLayout.contentFraction,
                    dimPercent = uiState.emulationSideArtworkDim
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(sideArtworkPreviewLayout.contentFraction)
                        .align(Alignment.Center)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF071A33), Color(0xFF142B46), Color(0xFF301B46), Color(0xFF081524))
                            )
                        )
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF4A86E8).copy(alpha = 0.24f), Color.Transparent)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Gamepad,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            tint = Color.White.copy(alpha = 0.72f)
                        )
                        Text(
                            text = stringResource(R.string.settings_customization_side_artwork_preview),
                            textAlign = TextAlign.Center,
                            color = Color.White.copy(alpha = 0.88f),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
                if (uiState.isSideArtworkImporting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        SideArtworkPicker(
            title = stringResource(R.string.settings_customization_side_artwork),
            options = sideArtworkOptions,
            selectedValue = uiState.emulationSideArtwork.preferenceValue,
            revision = uiState.emulationSideArtworkRevision,
            onSelect = { value ->
                viewModel.setEmulationSideArtwork(EmulationSideArtwork.fromPreference(value))
            },
            helpText = stringResource(R.string.settings_customization_side_artwork_help)
        )
        if (uiState.emulationSideArtwork != EmulationSideArtwork.NONE) {
            SliderItem(
                icon = Icons.Rounded.Visibility,
                title = stringResource(R.string.settings_customization_background_dim),
                subtitle = "",
                value = uiState.emulationSideArtworkDim.toFloat(),
                range = 0f..85f,
                steps = 16,
                onValueChange = { viewModel.setEmulationSideArtworkDim(it.roundToInt()) },
                valueLabel = { "${it.roundToInt()}%" },
                onResetToDefault = {
                    viewModel.setEmulationSideArtworkDim(AppPreferences.DEFAULT_EMULATION_SIDE_ARTWORK_DIM)
                }
            )
        }
        SettingsItem(
            icon = Icons.Rounded.Wallpaper,
            label = stringResource(R.string.settings_customization_side_artwork_import),
            value = stringResource(R.string.settings_customization_side_artwork_import_desc),
            onClick = onPickSideArtwork
        )
        if (hasCustomSideArtwork) {
            SettingsItem(
                icon = Icons.Rounded.DeleteOutline,
                label = stringResource(R.string.settings_customization_side_artwork_remove),
                value = stringResource(R.string.settings_customization_side_artwork_remove_desc),
                onClick = viewModel::clearCustomEmulationSideArtwork
            )
        }
    }

    SettingsSection(title = stringResource(R.string.settings_customization_library_section)) {
        SliderItem(
            icon = Icons.Rounded.Wallpaper,
            title = stringResource(R.string.settings_customization_grid_size),
            subtitle = "",
            value = uiState.homeGridScale,
            range = AppPreferences.MIN_HOME_GRID_SCALE..AppPreferences.MAX_HOME_GRID_SCALE,
            steps = 19,
            onValueChange = viewModel::setHomeGridScale,
            valueLabel = { "${(it * 100).roundToInt()}%" },
            helpText = stringResource(R.string.settings_customization_grid_size_help),
            onResetToDefault = {
                viewModel.setHomeGridScale(AppPreferences.DEFAULT_HOME_GRID_SCALE)
            }
        )
    }

    SettingsSection(title = stringResource(R.string.settings_customization_drawer_section)) {
        SettingsInlineNote(stringResource(R.string.settings_customization_drawer_summary))
        DrawerVisualStylePicker(
            selected = uiState.drawerVisualStyle,
            onSelect = viewModel::setDrawerVisualStyle
        )
        val groups = listOf(
            stringResource(R.string.shell_quick_actions) to listOf(
                DrawerItemId.LIBRARY,
                DrawerItemId.CATALOG_SEARCH,
                DrawerItemId.HUB
            ),
            stringResource(R.string.shell_executables_section) to listOf(
                DrawerItemId.LAUNCH_GAME,
                DrawerItemId.LAUNCH_BIOS
            ),
            stringResource(R.string.shell_app_section) to listOf(
                DrawerItemId.GAME_SETTINGS,
                DrawerItemId.DATA_TRANSFER,
                DrawerItemId.RESET_SETTINGS
            ),
            stringResource(R.string.shell_tools_section) to listOf(
                DrawerItemId.MEMORY_CARDS,
                DrawerItemId.CHEAT_MANAGER,
                DrawerItemId.TEXTURE_MANAGER,
                DrawerItemId.SAVE_STATES
            ),
            stringResource(R.string.settings_customization_drawer_other) to listOf(
                DrawerItemId.APP_SETTINGS,
                DrawerItemId.SUPPORTED_FORMATS,
                DrawerItemId.ACHIEVEMENTS,
                DrawerItemId.FEEDBACK,
                DrawerItemId.DISCORD
            )
        )
        groups.forEach { (title, items) ->
            Text(
                text = title,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            items.forEach { item ->
                DrawerItemEditorRow(
                    icon = drawerItemIcon(item),
                    title = drawerItemLabel(item),
                    visible = item !in uiState.hiddenDrawerItems,
                    required = item.required,
                    onVisibleChange = { viewModel.setDrawerItemVisible(item, it) }
                )
            }
        }
        SettingsInlineNote(stringResource(R.string.settings_customization_drawer_required_note))
    }

    SettingsSection(title = stringResource(R.string.settings_customization_touch_controls_section)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(124.dp),
            shape = neonShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                VectorAnalogStick(
                    analogSize = 76.dp,
                    visualStyle = uiState.touchControlVisualStyle,
                    pressEffect = uiState.touchControlPressEffect,
                    pressed = true,
                    interactive = false
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VectorOverlayButton(
                        drawableRes = R.drawable.ic_controller_square_button,
                        width = 44.dp,
                        height = 44.dp,
                        visualStyle = uiState.touchControlVisualStyle,
                        pressEffect = uiState.touchControlPressEffect,
                        interactive = false
                    )
                    VectorOverlayButton(
                        drawableRes = R.drawable.ic_controller_cross_button,
                        width = 44.dp,
                        height = 44.dp,
                        visualStyle = uiState.touchControlVisualStyle,
                        pressEffect = uiState.touchControlPressEffect,
                        pressed = true,
                        interactive = false
                    )
                }
            }
        }
        ChoiceSection(
            title = stringResource(R.string.settings_customization_touch_controls_style),
            options = listOf(
                TouchControlVisualStyle.CLASSIC.preferenceValue to stringResource(R.string.settings_customization_touch_style_classic),
                TouchControlVisualStyle.LEGACY.preferenceValue to stringResource(R.string.settings_customization_touch_style_glass),
                TouchControlVisualStyle.MODERN.preferenceValue to stringResource(R.string.settings_customization_touch_style_neon),
                TouchControlVisualStyle.ARCADE.preferenceValue to stringResource(R.string.settings_customization_touch_style_arcade),
                TouchControlVisualStyle.MINIMAL.preferenceValue to stringResource(R.string.settings_customization_touch_style_minimal)
            ),
            selectedValue = uiState.touchControlVisualStyle.preferenceValue,
            onSelect = { value -> viewModel.setTouchControlVisualStyle(TouchControlVisualStyle.fromPreference(value)) },
            helpText = stringResource(R.string.settings_customization_touch_controls_help),
            onResetToDefault = { viewModel.setTouchControlVisualStyle(TouchControlVisualStyle.CLASSIC) }
        )
        ChoiceSection(
            title = stringResource(R.string.settings_customization_touch_press_effect),
            options = listOf(
                TouchControlPressEffect.GROW.preferenceValue to stringResource(R.string.settings_customization_touch_press_effect_grow),
                TouchControlPressEffect.SHRINK.preferenceValue to stringResource(R.string.settings_customization_touch_press_effect_shrink),
                TouchControlPressEffect.SPRING.preferenceValue to stringResource(R.string.settings_customization_touch_press_effect_spring),
                TouchControlPressEffect.GLOW.preferenceValue to stringResource(R.string.settings_customization_touch_press_effect_glow)
            ),
            selectedValue = uiState.touchControlPressEffect.preferenceValue,
            onSelect = { value ->
                viewModel.setTouchControlPressEffect(TouchControlPressEffect.fromPreference(value))
            },
            helpText = stringResource(R.string.settings_customization_touch_press_effect_help),
            onResetToDefault = { viewModel.setTouchControlPressEffect(TouchControlPressEffect.GROW) }
        )
        if (uiState.customTouchControls.controls.isNotEmpty()) {
            CustomControlsQuickSelector(
                library = uiState.customTouchControls,
                onEnabledChange = { controlId, enabled ->
                    viewModel.saveCustomTouchControls(
                        uiState.customTouchControls.copy(
                            controls = uiState.customTouchControls.controls.map { control ->
                                if (control.id == controlId) {
                                    control.copy(enabled = enabled)
                                } else {
                                    control
                                }
                            }
                        )
                    )
                }
            )
        }
        ActionItem(
            icon = Icons.Rounded.Gamepad,
            title = stringResource(R.string.touch_control_creator_settings_entry),
            subtitle = stringResource(R.string.touch_control_creator_settings_desc),
            actionIcon = Icons.Rounded.Tune,
            actionLabel = stringResource(R.string.touch_control_creator_open),
            onClick = { onOpenTouchControlCreator?.invoke() },
            enabled = onOpenTouchControlCreator != null
        )
    }

    SettingsSection(title = stringResource(R.string.settings_customization_text_section)) {
        ChoiceSection(
            title = stringResource(R.string.settings_customization_font),
            options = listOf(
                AppFontChoice.SYSTEM.preferenceValue to stringResource(R.string.settings_customization_font_system),
                AppFontChoice.RUBIK.preferenceValue to stringResource(R.string.settings_customization_font_rubik),
                AppFontChoice.EXO_2.preferenceValue to stringResource(R.string.settings_customization_font_exo2)
            ) + if (uiState.customFontName != null) {
                listOf(AppFontChoice.CUSTOM.preferenceValue to stringResource(R.string.settings_customization_font_custom))
            } else {
                emptyList()
            },
            selectedValue = uiState.appFontChoice.preferenceValue,
            onSelect = { value ->
                viewModel.setAppFontChoice(AppFontChoice.fromPreference(value))
            },
            helpText = stringResource(R.string.settings_customization_font_help),
            onResetToDefault = { viewModel.setAppFontChoice(AppFontChoice.SYSTEM) }
        )
        SettingsItem(
            icon = Icons.Rounded.FolderOpen,
            label = stringResource(R.string.settings_customization_import_font),
            value = uiState.customFontName
                ?: stringResource(R.string.settings_customization_import_font_desc),
            onClick = onPickCustomFont,
            helpText = stringResource(R.string.settings_customization_import_font_help)
        )
        if (uiState.customFontName != null) {
            SettingsItem(
                icon = Icons.Rounded.DeleteOutline,
                label = stringResource(R.string.settings_customization_remove_font),
                value = stringResource(R.string.settings_customization_remove_font_desc),
                onClick = viewModel::clearCustomFont
            )
        }
        SliderItem(
            icon = Icons.Rounded.FormatSize,
            title = stringResource(R.string.settings_customization_font_size),
            subtitle = "",
            value = uiState.appFontScale,
            range = AppPreferences.MIN_APP_FONT_SCALE..AppPreferences.MAX_APP_FONT_SCALE,
            steps = 14,
            onValueChange = viewModel::setAppFontScale,
            valueLabel = { "${(it * 100).roundToInt()}%" },
            helpText = stringResource(R.string.settings_customization_font_size_help),
            onResetToDefault = {
                viewModel.setAppFontScale(AppPreferences.DEFAULT_APP_FONT_SCALE)
            }
        )
    }

    SettingsSection(title = stringResource(R.string.settings_customization_reset_section)) {
        SettingsItem(
            icon = Icons.Rounded.Restore,
            label = stringResource(R.string.settings_customization_reset),
            value = stringResource(R.string.settings_customization_reset_desc),
            onClick = viewModel::resetCustomization
        )
    }
}

@Composable
private fun GameMenuSettingsTab(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsSection(title = stringResource(R.string.settings_game_menu_preview_section)) {
        SettingsInlineNote(stringResource(R.string.settings_game_menu_content_summary))
        GameMenuLayoutStylePicker(
            selected = uiState.gameMenuLayoutStyle,
            visibleTabs = uiState.gameMenuTabOrder.filterNot(uiState.hiddenGameMenuTabs::contains),
            onSelect = viewModel::setGameMenuLayoutStyle
        )
    }

    SettingsSection(title = stringResource(R.string.settings_game_menu_tabs_section)) {
        uiState.gameMenuTabOrder.forEachIndexed { index, tab ->
            GameMenuEditorRow(
                icon = gameMenuTabIcon(tab),
                title = gameMenuTabLabel(tab),
                visible = tab !in uiState.hiddenGameMenuTabs,
                required = tab == GameMenuTabId.SESSION,
                canMoveUp = index > 0,
                canMoveDown = index < uiState.gameMenuTabOrder.lastIndex,
                onVisibleChange = { viewModel.setGameMenuTabVisible(tab, it) },
                onMoveUp = { viewModel.moveGameMenuTab(tab, -1) },
                onMoveDown = { viewModel.moveGameMenuTab(tab, 1) }
            )
        }
    }

    uiState.gameMenuTabOrder.forEach { tab ->
        val sections = uiState.gameMenuSectionOrder.filter { it.tab == tab }
        SettingsSection(
            title = stringResource(
                R.string.settings_game_menu_tab_content_format,
                gameMenuTabLabel(tab)
            )
        ) {
            sections.forEachIndexed { index, section ->
                GameMenuSectionRow(
                    title = gameMenuSectionLabel(section),
                    visible = section !in uiState.hiddenGameMenuSections,
                    canMoveUp = index > 0,
                    canMoveDown = index < sections.lastIndex,
                    onVisibleChange = { viewModel.setGameMenuSectionVisible(section, it) },
                    onMoveUp = { viewModel.moveGameMenuSection(section, -1) },
                    onMoveDown = { viewModel.moveGameMenuSection(section, 1) }
                )
            }
            if (tab == GameMenuTabId.SESSION) {
                SettingsInlineNote(stringResource(R.string.settings_game_menu_session_safety_note))
            }
        }
    }

    SettingsSection(title = stringResource(R.string.settings_game_menu_reset_section)) {
        SettingsItem(
            icon = Icons.Rounded.Restore,
            label = stringResource(R.string.settings_game_menu_reset),
            value = stringResource(R.string.settings_game_menu_full_reset_desc),
            onClick = viewModel::resetGameMenuCustomization
        )
    }
}

@Composable
private fun GameMenuLayoutStylePicker(
    selected: GameMenuLayoutStyle,
    visibleTabs: List<GameMenuTabId>,
    onSelect: (GameMenuLayoutStyle) -> Unit
) {
    Text(
        text = stringResource(R.string.settings_game_menu_layout_section),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusGroup(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(GameMenuLayoutStyle.entries, key = { it.name }) { style ->
            VisualStylePreviewCard(
                title = gameMenuLayoutStyleLabel(style),
                selected = selected == style,
                onClick = { onSelect(style) }
            ) {
                GameMenuLayoutMiniature(style = style, tabCount = visibleTabs.size.coerceAtLeast(1))
            }
        }
    }
    SettingsInlineNote(stringResource(R.string.settings_game_menu_layout_help))
}

@Composable
private fun DrawerVisualStylePicker(
    selected: DrawerVisualStyle,
    onSelect: (DrawerVisualStyle) -> Unit
) {
    Text(
        text = stringResource(R.string.settings_customization_drawer_style),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusGroup(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(DrawerVisualStyle.entries, key = { it.name }) { style ->
            VisualStylePreviewCard(
                title = drawerVisualStyleLabel(style),
                selected = selected == style,
                onClick = { onSelect(style) }
            ) {
                DrawerStyleMiniature(style)
            }
        }
    }
}

@Composable
private fun VisualStylePreviewCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = neonShape(18.dp)
    Surface(
        modifier = Modifier
            .width(176.dp)
            .height(132.dp)
            .tvGamepadFocusableCard(
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
        },
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(neonShape(12.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
                    .padding(7.dp)
            ) {
                preview()
            }
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GameMenuLayoutMiniature(style: GameMenuLayoutStyle, tabCount: Int) {
    val panelColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)
    val navColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    when (style) {
        GameMenuLayoutStyle.SIDEBAR -> Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.End
        ) {
            MiniatureContentPanel(Modifier.fillMaxHeight().weight(1f), panelColor)
            Spacer(Modifier.width(5.dp))
            MiniatureVerticalTabs(Modifier.fillMaxHeight().width(20.dp), tabCount, navColor)
        }

        GameMenuLayoutStyle.DASHBOARD -> Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 5.dp)
                .clip(neonShape(7.dp))
                .background(panelColor)
        ) {
            MiniatureVerticalTabs(Modifier.fillMaxHeight().width(38.dp), tabCount, navColor, labelled = true)
            MiniatureContentPanel(Modifier.fillMaxHeight().weight(1f), MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
        }

        GameMenuLayoutStyle.COMMAND_CENTER -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.78f)
                    .clip(neonShapeCorners(topStart = 10.dp, topEnd = 10.dp))
                    .background(panelColor)
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                MiniatureHorizontalTabs(Modifier.fillMaxWidth().height(16.dp), tabCount, navColor)
                MiniatureContentPanel(Modifier.fillMaxSize(), MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            }
        }

        GameMenuLayoutStyle.COMPACT -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.64f)
                    .clip(neonShape(7.dp))
                    .background(panelColor)
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                MiniatureHorizontalTabs(Modifier.fillMaxWidth().height(14.dp), tabCount, navColor)
                MiniatureContentPanel(Modifier.fillMaxSize(), MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            }
        }
    }
}

@Composable
private fun DrawerStyleMiniature(style: DrawerVisualStyle) {
    val shape = when (style) {
        DrawerVisualStyle.CLASSIC -> neonShape(9.dp)
        DrawerVisualStyle.COMPACT -> neonShape(3.dp)
        DrawerVisualStyle.GLASS -> neonShape(13.dp)
        DrawerVisualStyle.CONSOLE -> neonShape(2.dp)
    }
    val panelWidth = when (style) {
        DrawerVisualStyle.COMPACT -> 0.66f
        DrawerVisualStyle.CONSOLE -> 0.90f
        else -> 0.78f
    }
    val rowHeight = when (style) {
        DrawerVisualStyle.COMPACT -> 11.dp
        DrawerVisualStyle.CONSOLE -> 18.dp
        else -> 15.dp
    }
    val rowColor = when (style) {
        DrawerVisualStyle.GLASS -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        DrawerVisualStyle.CONSOLE -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.76f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(panelWidth)
                .clip(shape)
                .background(
                    if (style == DrawerVisualStyle.GLASS) {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                    }
                )
                .padding(if (style == DrawerVisualStyle.COMPACT) 5.dp else 7.dp),
            verticalArrangement = Arrangement.spacedBy(if (style == DrawerVisualStyle.COMPACT) 4.dp else 6.dp)
        ) {
            repeat(if (style == DrawerVisualStyle.COMPACT) 5 else 4) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .clip(shape)
                        .background(if (index == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f) else rowColor)
                )
            }
        }
    }
}

@Composable
private fun MiniatureContentPanel(modifier: Modifier, color: Color) {
    Column(
        modifier = modifier
            .clip(neonShape(7.dp))
            .background(color)
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.fillMaxWidth(0.62f).height(8.dp).clip(neonShape(4.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)))
        repeat(3) {
            Box(Modifier.fillMaxWidth().height(7.dp).clip(neonShape(4.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
        }
    }
}

@Composable
private fun MiniatureVerticalTabs(
    modifier: Modifier,
    tabCount: Int,
    color: Color,
    labelled: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(neonShape(7.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(tabCount.coerceAtMost(4)) { index ->
            Box(
                Modifier
                    .fillMaxWidth(if (labelled) 1f else 0.85f)
                    .height(if (labelled) 11.dp else 9.dp)
                    .clip(neonShape(4.dp))
                    .background(if (index == 0) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f))
            )
        }
    }
}

@Composable
private fun MiniatureHorizontalTabs(modifier: Modifier, tabCount: Int, color: Color) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(tabCount.coerceAtMost(5)) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(neonShape(4.dp))
                    .background(if (index == 0) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f))
            )
        }
    }
}

@Composable
private fun gameMenuLayoutStyleLabel(style: GameMenuLayoutStyle): String = stringResource(
    when (style) {
        GameMenuLayoutStyle.SIDEBAR -> R.string.settings_game_menu_layout_sidebar
        GameMenuLayoutStyle.DASHBOARD -> R.string.settings_game_menu_layout_dashboard
        GameMenuLayoutStyle.COMMAND_CENTER -> R.string.settings_game_menu_layout_command_center
        GameMenuLayoutStyle.COMPACT -> R.string.settings_game_menu_layout_compact
    }
)

@Composable
private fun drawerVisualStyleLabel(style: DrawerVisualStyle): String = stringResource(
    when (style) {
        DrawerVisualStyle.CLASSIC -> R.string.settings_drawer_style_classic
        DrawerVisualStyle.COMPACT -> R.string.settings_drawer_style_compact
        DrawerVisualStyle.GLASS -> R.string.settings_drawer_style_glass
        DrawerVisualStyle.CONSOLE -> R.string.settings_drawer_style_console
    }
)

@Composable
private fun DrawerItemEditorRow(
    icon: ImageVector,
    title: String,
    visible: Boolean,
    required: Boolean,
    onVisibleChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            if (required) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = stringResource(R.string.settings_game_menu_required),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(20.dp)
                )
            } else {
                Switch(checked = visible, onCheckedChange = onVisibleChange)
            }
        }
    }
}

private fun drawerItemIcon(item: DrawerItemId): ImageVector = when (item) {
    DrawerItemId.LIBRARY -> Icons.Rounded.Home
    DrawerItemId.CATALOG_SEARCH -> Icons.Rounded.Search
    DrawerItemId.HUB -> Icons.Rounded.Newspaper
    DrawerItemId.LAUNCH_GAME, DrawerItemId.LAUNCH_BIOS -> Icons.Rounded.PlayArrow
    DrawerItemId.GAME_SETTINGS -> Icons.Rounded.Tune
    DrawerItemId.DATA_TRANSFER -> Icons.Rounded.SwapVert
    DrawerItemId.RESET_SETTINGS -> Icons.Rounded.Restore
    DrawerItemId.MEMORY_CARDS, DrawerItemId.SUPPORTED_FORMATS -> Icons.Rounded.Memory
    DrawerItemId.TEXTURE_MANAGER -> Icons.Rounded.FolderOpen
    DrawerItemId.CHEAT_MANAGER -> Icons.Rounded.SportsEsports
    DrawerItemId.ACHIEVEMENTS -> Icons.Rounded.EmojiEvents
    DrawerItemId.SAVE_STATES -> Icons.Rounded.Save
    DrawerItemId.APP_SETTINGS -> Icons.Rounded.SettingsSuggest
    DrawerItemId.FEEDBACK -> Icons.Rounded.RateReview
    DrawerItemId.DISCORD -> Icons.Rounded.Forum
}

@Composable
private fun drawerItemLabel(item: DrawerItemId): String = when (item) {
    DrawerItemId.LIBRARY -> stringResource(R.string.shell_library)
    DrawerItemId.CATALOG_SEARCH -> stringResource(R.string.shell_catalog_search)
    DrawerItemId.HUB -> stringResource(R.string.hub_title)
    DrawerItemId.LAUNCH_GAME -> stringResource(R.string.shell_launch_game)
    DrawerItemId.LAUNCH_BIOS -> stringResource(R.string.shell_launch_bios)
    DrawerItemId.GAME_SETTINGS -> stringResource(R.string.shell_game_settings_manager)
    DrawerItemId.DATA_TRANSFER -> stringResource(R.string.shell_data_transfer)
    DrawerItemId.RESET_SETTINGS -> stringResource(R.string.settings_reset_all_action)
    DrawerItemId.MEMORY_CARDS -> stringResource(R.string.shell_memory_cards)
    DrawerItemId.TEXTURE_MANAGER -> stringResource(R.string.shell_texture_manager)
    DrawerItemId.CHEAT_MANAGER -> stringResource(R.string.shell_cheat_manager)
    DrawerItemId.ACHIEVEMENTS -> stringResource(R.string.shell_achievements)
    DrawerItemId.SAVE_STATES -> stringResource(R.string.shell_save_states)
    DrawerItemId.APP_SETTINGS -> stringResource(R.string.shell_app_settings)
    DrawerItemId.SUPPORTED_FORMATS -> stringResource(R.string.shell_supported_formats)
    DrawerItemId.FEEDBACK -> stringResource(R.string.feedback_title)
    DrawerItemId.DISCORD -> stringResource(R.string.discord_title)
}

@Composable
private fun GameMenuEditorRow(
    icon: ImageVector,
    title: String,
    visible: Boolean,
    required: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(title, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Rounded.KeyboardArrowUp, null) }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Rounded.KeyboardArrowDown, null) }
            if (required) {
                Icon(Icons.Rounded.Lock, stringResource(R.string.settings_game_menu_required), modifier = Modifier.padding(12.dp).size(20.dp))
            } else {
                Switch(checked = visible, onCheckedChange = onVisibleChange)
            }
        }
    }
}

@Composable
private fun GameMenuSectionRow(
    title: String,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Rounded.KeyboardArrowUp, null)
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Rounded.KeyboardArrowDown, null)
            }
            Switch(checked = visible, onCheckedChange = onVisibleChange)
        }
    }
}

@Composable
private fun gameMenuTabLabel(tab: GameMenuTabId): String = when (tab) {
    GameMenuTabId.SESSION -> stringResource(R.string.emulation_session_tab)
    GameMenuTabId.CONTROLS -> stringResource(R.string.settings_controls_tab)
    GameMenuTabId.EMULATION -> stringResource(R.string.settings_emulation_tab)
    GameMenuTabId.CHEATS -> stringResource(R.string.emulation_cheats_title)
    GameMenuTabId.GRAPHICS -> stringResource(R.string.settings_graphics_tab)
    GameMenuTabId.ACHIEVEMENTS -> stringResource(R.string.achievements_title)
}

private fun gameMenuTabIcon(tab: GameMenuTabId): ImageVector = when (tab) {
    GameMenuTabId.SESSION -> Icons.Rounded.MoreVert
    GameMenuTabId.CONTROLS -> Icons.Rounded.Gamepad
    GameMenuTabId.EMULATION -> Icons.Rounded.SettingsSuggest
    GameMenuTabId.CHEATS -> Icons.Rounded.Star
    GameMenuTabId.GRAPHICS -> Icons.Rounded.Wallpaper
    GameMenuTabId.ACHIEVEMENTS -> Icons.Rounded.EmojiEvents
}

@Composable
private fun gameMenuSectionLabel(section: GameMenuSectionId): String = when (section) {
    GameMenuSectionId.SAVE_STATES -> stringResource(R.string.settings_game_menu_section_save_states)
    GameMenuSectionId.AUTO_SAVE -> stringResource(R.string.settings_game_menu_section_auto_save)
    GameMenuSectionId.QUICK_ACTIONS -> stringResource(R.string.settings_game_menu_section_quick_actions)
    GameMenuSectionId.AUTOMATION -> stringResource(R.string.settings_game_menu_section_automation)
    GameMenuSectionId.GAME_PROFILE -> stringResource(R.string.settings_game_menu_section_game_profile)
    GameMenuSectionId.SESSION_DEBUG_TOOLS -> stringResource(R.string.settings_game_menu_section_debug_tools)
    GameMenuSectionId.CONTROLS_GENERAL -> stringResource(R.string.settings_game_menu_section_controls_general)
    GameMenuSectionId.CONTROLS_TOUCH -> stringResource(R.string.settings_touch_controls_section)
    GameMenuSectionId.CONTROLS_GAMEPAD -> stringResource(R.string.settings_gamepad_controls_section)
    GameMenuSectionId.EMULATION_PERFORMANCE -> stringResource(R.string.emulation_performance_stats)
    GameMenuSectionId.EMULATION_SPEED -> stringResource(R.string.settings_speed_hacks)
    GameMenuSectionId.EMULATION_CPU -> stringResource(R.string.settings_core_cpu)
    GameMenuSectionId.EMULATION_AUDIO -> stringResource(R.string.settings_core_audio)
    GameMenuSectionId.EMULATION_CHEATS -> stringResource(R.string.settings_enable_cheats)
    GameMenuSectionId.GRAPHICS_DISPLAY -> stringResource(R.string.settings_game_menu_section_graphics_display)
    GameMenuSectionId.GRAPHICS_RENDERING -> stringResource(R.string.settings_rendering_section)
    GameMenuSectionId.GRAPHICS_SCREEN -> stringResource(R.string.emulation_screen_tab)
}

@Composable
private fun AboutNote(
    title: String,
    body: String,
    linkLabel: String? = null,
    linkUrl: String? = null
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding, vertical = 4.dp),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!linkLabel.isNullOrBlank() && !linkUrl.isNullOrBlank()) {
                Text(
                    text = linkLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { openUriInChrome(context, linkUrl) }
                )
            }
        }
    }
}

private fun openUriInChrome(context: android.content.Context, url: String) {
    val uri = url.toUri()
    val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.android.chrome")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chromeIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(fallbackIntent)
    }
}

private fun normalizeSettingsSearchToken(value: String): String {
    return value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

@Composable
private fun ThemeSelector(
    selected: ThemeMode,
    customThemeLibrary: CustomThemeLibrary,
    onSelected: (ThemeMode) -> Unit,
    onCustomThemeSelected: (String) -> Unit
) {
    val customThemes = customThemeLibrary.sanitized().themes
    val customThemeOptionStart = 100
    val neonThemeOptionValue = 50
    val defaultThemeName = stringResource(R.string.theme_manager_default_name)
    val options = buildList {
        add(0 to stringResource(R.string.settings_theme_system))
        add(1 to stringResource(R.string.settings_theme_light))
        add(2 to stringResource(R.string.settings_theme_dark))
        add(neonThemeOptionValue to stringResource(R.string.settings_theme_neon))
        customThemes.forEachIndexed { index, savedTheme ->
            val name = savedTheme.config.name
                .takeUnless { it == CustomThemeConfig.DEFAULT_NAME }
                ?: defaultThemeName
            add((customThemeOptionStart + index) to name)
        }
    }
    val activeCustomThemeIndex = customThemes.indexOfFirst {
        it.id == customThemeLibrary.activeThemeId
    }

    ChoiceSection(
        title = stringResource(R.string.settings_theme),
        options = options,
        selectedValue = when (selected) {
            ThemeMode.SYSTEM -> 0
            ThemeMode.LIGHT -> 1
            ThemeMode.DARK -> 2
            ThemeMode.NEON -> neonThemeOptionValue
            ThemeMode.CUSTOM -> if (activeCustomThemeIndex >= 0) {
                customThemeOptionStart + activeCustomThemeIndex
            } else {
                -1
            }
        },
        onResetToDefault = { onSelected(ThemeMode.SYSTEM) },
        onSelect = { value ->
            when (value) {
                1 -> onSelected(ThemeMode.LIGHT)
                2 -> onSelected(ThemeMode.DARK)
                neonThemeOptionValue -> onSelected(ThemeMode.NEON)
                in customThemeOptionStart until customThemeOptionStart + customThemes.size -> {
                    onCustomThemeSelected(customThemes[value - customThemeOptionStart].id)
                }
                else -> onSelected(ThemeMode.SYSTEM)
            }
        }
    )
}

@Composable
private fun CustomControlsQuickSelector(
    library: CustomTouchControlLibrary,
    onEnabledChange: (controlId: String, enabled: Boolean) -> Unit
) {
    val controls = library.sanitized().controls
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.touch_control_creator_library),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = controls,
                key = { it.id }
            ) { control ->
                val interactionSource = remember { MutableInteractionSource() }
                FilterChip(
                    modifier = Modifier.tvGamepadFocusableCard(
                        shape = neonShape(16.dp),
                        interactionSource = interactionSource,
                        addFocusTarget = false
                    ),
                    shape = neonChipShape(),
                    selected = control.enabled,
                    onClick = { onEnabledChange(control.id, !control.enabled) },
                    interactionSource = interactionSource,
                    colors = premiumFilterChipColors(),
                    label = {
                        Text(
                            text = control.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val neon = LocalNeonTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = if (neon) title.uppercase() else title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = if (neon) 1.2.sp else MaterialTheme.typography.titleLarge.letterSpacing
            ),
            color = if (neon) NeonYellow else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)
        )
        if (neon) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenHorizontalPadding)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(NeonRed, NeonYellow, Color.Transparent)
                        )
                    )
            ) {
                Box(modifier = Modifier.fillMaxWidth())
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalPadding),
            shape = neonShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = if (neon) {
                BorderStroke(1.dp, NeonYellow.copy(alpha = 0.14f))
            } else {
                null
            }
        ) {
            Column(
                modifier = Modifier.padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
internal fun SettingsItem(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    helpText: String? = null,
    border: BorderStroke? = null,
    enabled: Boolean = true,
    progressVisible: Boolean = false,
    progress: Float? = null,
    progressLabel: String? = null,
    horizontalPadding: Dp = 16.dp
) {
    val debouncedClick = rememberDebouncedClick(onClick = onClick)
    val interactionSource = remember { MutableInteractionSource() }
    val tvUiEnabled = LocalTvUiEnvironment.current.enabled
    val itemFocusRequester = remember { FocusRequester() }
    val helpFocusRequester = remember { FocusRequester() }
    val shape = neonShape(18.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .neonCornerAccent(
                accent = neonAccentColor(label.hashCode().mod(3)),
                markSize = 9.dp
            )
            .then(
                if (tvUiEnabled && helpText != null) {
                    Modifier
                        .focusRequester(itemFocusRequester)
                        .focusProperties { right = helpFocusRequester }
                } else {
                    Modifier
                }
            )
            .gamepadFocusableCard(
                enabled = enabled,
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = border,
        interactionSource = interactionSource,
        enabled = enabled,
        onClick = debouncedClick
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(neonShape(12.dp))
                        .then(
                            if (LocalNeonTheme.current) {
                                Modifier
                                    .background(if (enabled) NeonBlack else NeonBlack.copy(alpha = 0.6f))
                                    .border(
                                        1.dp,
                                        NeonBlue.copy(alpha = if (enabled) 0.35f else 0.18f),
                                        neonShape(12.dp)
                                    )
                            } else {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.1f else 0.05f)
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (LocalNeonTheme.current) {
                            NeonBlue.copy(alpha = if (enabled) 1f else 0.5f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.5f)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        helpText?.let {
                            SettingHelpButton(
                                title = label,
                                description = it,
                                focusRequester = helpFocusRequester,
                                returnFocusRequester = itemFocusRequester
                            )
                        }
                    }
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f)
                    )
                }
            }
            if (progressVisible) {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                progressLabel?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverUrlExampleRow(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = neonShape(14.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .gamepadFocusableCard(
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

private data class SettingsSearchEntry(
    val tab: SettingsTab,
    val title: String,
    val summary: String
)

@Composable
private fun rememberSettingsSearchEntries(): List<SettingsSearchEntry> {
    @Composable
    fun entry(tab: SettingsTab, @StringRes titleRes: Int): SettingsSearchEntry {
        return SettingsSearchEntry(tab = tab, title = stringResource(titleRes), summary = tab.label())
    }
    return listOfNotNull(
        entry(SettingsTab.General, R.string.settings_language),
        entry(SettingsTab.General, R.string.settings_tv_interface),
        entry(SettingsTab.General, R.string.settings_theme),
        entry(SettingsTab.General, R.string.settings_theme_manager),
        entry(SettingsTab.Customization, R.string.settings_customization_background),
        entry(SettingsTab.Customization, R.string.settings_customization_grid_size),
        entry(SettingsTab.Customization, R.string.settings_customization_font),
        entry(SettingsTab.Customization, R.string.settings_customization_font_size),
        entry(SettingsTab.Customization, R.string.settings_customization_touch_controls_style),
        entry(SettingsTab.Customization, R.string.settings_customization_drawer_style),
        entry(SettingsTab.Customization, R.string.settings_customization_touch_press_effect),
        entry(SettingsTab.GameMenu, R.string.settings_game_menu_tabs_section),
        entry(SettingsTab.GameMenu, R.string.settings_game_menu_layout_section),
        entry(SettingsTab.GameMenu, R.string.settings_game_menu_session_sections),
        entry(SettingsTab.General, R.string.settings_keep_screen_on),
        entry(SettingsTab.General, R.string.settings_emulation_both_orientations),
        entry(SettingsTab.General, R.string.settings_orientation_lock),
        entry(SettingsTab.General, R.string.settings_back_button_exits_game),
        entry(SettingsTab.General, R.string.settings_confirm_save_load_actions),
        entry(SettingsTab.General, R.string.settings_floating_quick_actions),
        entry(SettingsTab.General, R.string.settings_show_recent_games),
        entry(SettingsTab.General, R.string.settings_show_home_search),
        entry(SettingsTab.General, R.string.settings_prefer_english_game_titles),
        entry(SettingsTab.Audio, R.string.settings_audio_volume),
        entry(SettingsTab.Audio, R.string.settings_audio_mute),
        entry(SettingsTab.Audio, R.string.settings_audio_output_latency),
        entry(SettingsTab.Audio, R.string.settings_audio_minimal_latency),
        entry(SettingsTab.Audio, R.string.settings_enable_cdda_audio),
        entry(SettingsTab.Audio, R.string.ss_core_audio_fasthook),
        entry(SettingsTab.Controls, R.string.settings_overlay_scale),
        entry(SettingsTab.Controls, R.string.settings_overlay_opacity),
        entry(SettingsTab.Controls, R.string.settings_touchscreen_right_stick),
        entry(SettingsTab.Controls, R.string.settings_touchscreen_right_stick_sensitivity),
        entry(SettingsTab.Controls, R.string.settings_racing_mode),
        entry(SettingsTab.Controls, R.string.settings_left_stick_sensitivity),
        entry(SettingsTab.Controls, R.string.settings_right_stick_sensitivity),
        entry(SettingsTab.Controls, R.string.settings_invert_left_stick),
        entry(SettingsTab.Controls, R.string.settings_invert_left_stick_horizontal),
        entry(SettingsTab.Controls, R.string.settings_invert_right_stick),
        entry(SettingsTab.Controls, R.string.settings_invert_right_stick_horizontal),
        entry(SettingsTab.Controls, R.string.settings_gamepad_mode),
        entry(SettingsTab.Controls, R.string.settings_gamepad_assignment_title),
        entry(SettingsTab.Controls, R.string.settings_gamepad_hide_overlay),
        entry(SettingsTab.Controls, R.string.settings_touch_haptics),
        entry(SettingsTab.Controls, R.string.settings_touch_haptics_preset),
        entry(SettingsTab.Controls, R.string.settings_touch_haptics_strength),
        entry(SettingsTab.Controls, R.string.settings_touch_haptics_test),
        entry(SettingsTab.Controls, R.string.settings_gyro_mode),
        entry(SettingsTab.Controls, R.string.settings_gyro_sensitivity),
        entry(SettingsTab.Controls, R.string.settings_gyro_smoothing),
        entry(SettingsTab.Controls, R.string.settings_gamepad_stick_deadzone),
        entry(SettingsTab.Controls, R.string.settings_gamepad_left_stick_sensitivity),
        entry(SettingsTab.Controls, R.string.settings_gamepad_right_stick_sensitivity),
        entry(SettingsTab.Controls, R.string.settings_gamepad_right_stick_up_to_r2),
        entry(SettingsTab.Controls, R.string.settings_gamepad_right_stick_down_to_l2),
        entry(SettingsTab.Controls, R.string.settings_pad_vibration),
        entry(SettingsTab.Controls, R.string.settings_pad_vibration_strength),
        entry(SettingsTab.Controls, R.string.settings_pad_vibration_test),
        entry(SettingsTab.Controls, R.string.settings_pad_vibration_fallback),
        entry(SettingsTab.Library, R.string.settings_bios_path),
        entry(SettingsTab.Library, R.string.settings_game_path),
        entry(SettingsTab.Library, R.string.emulator_data_location_title),
        entry(SettingsTab.Library, R.string.settings_memory_cards_tab),
        entry(SettingsTab.Library, R.string.settings_cover_art_style),
        entry(SettingsTab.Library, R.string.settings_cover_download_url),
        entry(SettingsTab.Library, R.string.settings_clear_cover_cache),
        entry(SettingsTab.Library, R.string.settings_backup_export_title),
        entry(SettingsTab.Library, R.string.settings_backup_restore_title),
        entry(SettingsTab.Shaders, R.string.settings_retroarch_shaders),
        entry(SettingsTab.Shaders, R.string.settings_shader_preset),
        entry(SettingsTab.Video, R.string.settings_renderer),
        entry(SettingsTab.Video, R.string.settings_upscale),
        entry(SettingsTab.Graphics, R.string.settings_aspect_ratio),
        entry(SettingsTab.Patches, R.string.settings_patches_download),
        entry(SettingsTab.Patches, R.string.settings_patches_source_official),
        entry(SettingsTab.Patches, R.string.settings_patches_custom_url),
        entry(SettingsTab.Patches, R.string.settings_widescreen_patches),
        entry(SettingsTab.Patches, R.string.settings_no_interlacing_patches),
        entry(SettingsTab.Emulation, R.string.settings_show_fps),
        entry(SettingsTab.Emulation, R.string.settings_fast_boot),
        entry(SettingsTab.Emulation, R.string.settings_fps_overlay_position),
        entry(SettingsTab.Emulation, R.string.settings_fps_overlay_scale),
        entry(SettingsTab.Emulation, R.string.settings_fps_overlay_metrics),
    )
}

@Composable
private fun SettingsSearchResults(
    query: String,
    entries: List<SettingsSearchEntry>,
    onOpen: (SettingsTab) -> Unit
) {
    val normalizedQuery = remember(query) { normalizeSettingsSearchToken(query) }
    val filtered = remember(entries, normalizedQuery) {
        entries.filter { entry ->
            val haystack = normalizeSettingsSearchToken("${entry.title} ${entry.summary}")
            haystack.contains(normalizedQuery)
        }
    }
    SettingsSection(title = stringResource(R.string.settings_search_results_title)) {
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            filtered.forEach { entry ->
                SettingsItem(
                    icon = entry.tab.icon(),
                    label = entry.title,
                    value = entry.summary,
                    onClick = { onOpen(entry.tab) }
                )
            }
        }
    }
}

@Composable
private fun GamepadBindingRow(
    title: String,
    value: String,
    autoLabel: String?,
    onBindClick: () -> Unit,
    onClearClick: (() -> Unit)?
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = neonShape(18.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .gamepadFocusableCard(
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        interactionSource = interactionSource,
        onClick = onBindClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(neonShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Gamepad,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    autoLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            onClearClick?.let {
                TextButton(onClick = it) {
                    Text(stringResource(R.string.settings_gamepad_mapping_clear))
                }
            }
        }
    }
}

@Composable
internal fun ToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val tvUiEnabled = LocalTvUiEnvironment.current.enabled
    val itemFocusRequester = remember { FocusRequester() }
    val helpFocusRequester = remember { FocusRequester() }
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)
    val shape = neonShape(18.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(
                if (tvUiEnabled && helpText != null) {
                    Modifier
                        .focusRequester(itemFocusRequester)
                        .focusProperties { right = helpFocusRequester }
                } else {
                    Modifier
                }
            )
            .gamepadFocusableCard(
                enabled = enabled,
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = { onCheckedChange(!checked) },
                onLongClick = onResetToDefault?.let {
                    {
                        it()
                        Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                    }
                }
        ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(neonShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    helpText?.let {
                        SettingHelpButton(
                            title = title,
                            description = it,
                            focusRequester = helpFocusRequester,
                            returnFocusRequester = itemFocusRequester
                        )
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.padding(end = 2.dp)
            )
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionIcon: ImageVector,
    actionLabel: String,
    onClick: () -> Unit,
    helpText: String? = null,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val tvUiEnabled = LocalTvUiEnvironment.current.enabled
    val itemFocusRequester = remember { FocusRequester() }
    val helpFocusRequester = remember { FocusRequester() }
    val shape = neonShape(18.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(
                if (tvUiEnabled && helpText != null) {
                    Modifier
                        .focusRequester(itemFocusRequester)
                        .focusProperties { right = helpFocusRequester }
                } else {
                    Modifier
                }
            )
            .gamepadFocusableCard(
                enabled = enabled,
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(neonShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    helpText?.let {
                        SettingHelpButton(
                            title = title,
                            description = it,
                            focusRequester = helpFocusRequester,
                            returnFocusRequester = itemFocusRequester
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onClick,
                enabled = enabled
            ) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun SliderItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    valueLabel: ((Float) -> String)? = null,
    onValueChangeLive: ((Float) -> Unit)? = null,
    onValueChangeFinished: ((Float) -> Unit)? = null,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val tvUiEnabled = LocalTvUiEnvironment.current.enabled
    val itemFocusRequester = remember { FocusRequester() }
    val helpFocusRequester = remember { FocusRequester() }
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)

    LaunchedEffect(value) {
        sliderValue = value
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(
                if (tvUiEnabled && helpText != null) {
                    Modifier
                        .focusRequester(itemFocusRequester)
                        .focusProperties { right = helpFocusRequester }
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
                onLongClick = onResetToDefault?.let {
                    {
                        it()
                        Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                    }
                }
            ),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(neonShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    helpText?.let {
                        SettingHelpButton(
                            title = title,
                            description = it,
                            focusRequester = helpFocusRequester,
                            returnFocusRequester = itemFocusRequester
                        )
                    }
                    }
                    Text(
                        text = valueLabel?.invoke(sliderValue) ?: subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    onValueChangeLive?.invoke(it)
                },
                onValueChangeFinished = {
                    onValueChange(sliderValue)
                    onValueChangeFinished?.invoke(sliderValue)
                },
                valueRange = range,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
internal fun ShaderPresetSelector(
    title: String,
    presets: List<RetroArchShaderPreset>,
    selectedPath: String,
    onSelect: (String) -> Unit,
    helpText: String,
    leadingOptions: List<Pair<String, String>> = emptyList(),
    cardHorizontalPadding: Dp = 16.dp
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedCategoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    val categoryListState = rememberLazyListState()
    val noneLabel = stringResource(R.string.settings_shader_preset_none)
    val selectedLabel = leadingOptions.firstOrNull { (path, _) -> path == selectedPath }?.second
        ?: presets
            .firstOrNull { it.absolutePath == selectedPath }
            ?.label
            ?.substringAfterLast('/')
        ?: noneLabel

    SettingsItem(
        icon = Icons.Rounded.AutoFixHigh,
        label = title,
        value = selectedLabel,
        onClick = {
            query = ""
            searchExpanded = false
            expandedCategoryKey = null
            showDialog = true
        },
        helpText = helpText,
        horizontalPadding = cardHorizontalPadding
    )

    if (showDialog) {
        val generalLabel = stringResource(R.string.settings_general_tab)
        val groups = remember(presets, noneLabel, generalLabel, leadingOptions, query) {
            buildShaderPresetDialogGroups(
                presets = presets,
                leadingOptions = leadingOptions,
                noneLabel = noneLabel,
                generalLabel = generalLabel,
                query = query
            )
        }
        val searchFocusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        LaunchedEffect(searchExpanded) {
            if (searchExpanded) searchFocusRequester.requestFocus()
        }

        LaunchedEffect(query) {
            expandedCategoryKey = null
        }

        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val isLandscape = maxWidth > maxHeight
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(if (isLandscape) 0.82f else 0.94f)
                        .widthIn(max = if (isLandscape) 960.dp else 720.dp)
                        .fillMaxHeight(if (isLandscape) 0.94f else 0.86f),
                    shape = neonShape(30.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                    )
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(if (isLandscape) 50.dp else 56.dp),
                                shape = neonShape(18.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoFixHigh,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(if (isLandscape) 27.dp else 30.dp)
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_retroarch_shaders),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = {
                                    searchExpanded = !searchExpanded
                                    if (!searchExpanded) {
                                        query = ""
                                        keyboardController?.hide()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = stringResource(R.string.settings_search),
                                    tint = if (searchExpanded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        )

                        AnimatedVisibility(
                            visible = searchExpanded,
                            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 22.dp,
                                        top = 6.dp,
                                        end = 22.dp,
                                        bottom = 4.dp
                                    )
                                    .focusRequester(searchFocusRequester)
                                    .skipGamepadTextFieldFocus(),
                                label = { Text(title) },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            query = ""
                                            searchExpanded = false
                                            keyboardController?.hide()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.home_search_clear)
                                        )
                                    }
                                },
                                shape = neonShape(18.dp)
                            )
                        }

                        if (groups.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.home_empty_search_title),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            AnimatedContent(
                                targetState = expandedCategoryKey,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                label = "shader-preset-category"
                            ) { categoryKey ->
                                val expandedGroup = groups.firstOrNull { it.key == categoryKey }
                                if (expandedGroup == null) {
                                    ShaderPresetCategoryList(
                                        groups = groups,
                                        selectedPath = selectedPath,
                                        listState = categoryListState,
                                        onOpenCategory = { expandedCategoryKey = it }
                                    )
                                } else {
                                    ShaderPresetCategoryOptions(
                                        group = expandedGroup,
                                        selectedPath = selectedPath,
                                        onCloseCategory = { expandedCategoryKey = null },
                                        onSelect = { path ->
                                            onSelect(path)
                                            showDialog = false
                                        }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showDialog = false }) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    }
                }
            }
        }
    }
}

internal data class ShaderPresetDialogGroup(
    val key: String,
    val title: String,
    val options: List<Pair<String, String>>,
    val stripsCategoryPrefix: Boolean
)

private const val SHADER_PRESET_GENERAL_GROUP_KEY = "__shader_preset_general__"

internal fun buildShaderPresetDialogGroups(
    presets: List<RetroArchShaderPreset>,
    leadingOptions: List<Pair<String, String>>,
    noneLabel: String,
    generalLabel: String,
    query: String
): List<ShaderPresetDialogGroup> {
    val rootPresets = presets
        .filterNot { '/' in it.label }
        .map { it.absolutePath to it.label }
    val generalGroup = ShaderPresetDialogGroup(
        key = SHADER_PRESET_GENERAL_GROUP_KEY,
        title = generalLabel,
        options = leadingOptions + listOf("" to noneLabel) + rootPresets,
        stripsCategoryPrefix = false
    )
    val presetGroups = presets
        .filter { '/' in it.label }
        .groupBy { it.label.substringBefore('/') }
        .map { (category, categoryPresets) ->
            ShaderPresetDialogGroup(
                key = category,
                title = shaderPresetCategoryTitle(category),
                options = categoryPresets
                    .sortedBy { it.label.lowercase() }
                    .map { it.absolutePath to it.label },
                stripsCategoryPrefix = true
            )
        }
        .sortedBy { it.title.lowercase() }

    val normalizedQuery = query.trim()
    return (listOf(generalGroup) + presetGroups).mapNotNull { group ->
        if (normalizedQuery.isEmpty() || group.title.contains(normalizedQuery, ignoreCase = true)) {
            group
        } else {
            group.copy(
                options = group.options.filter { (_, label) ->
                    label.contains(normalizedQuery, ignoreCase = true)
                }
            ).takeIf { it.options.isNotEmpty() }
        }
    }
}

internal fun shaderPresetCategoryTitle(category: String): String {
    return category
        .replace('-', ' ')
        .replace('_', ' ')
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { word ->
            when (word.lowercase()) {
                "crt", "gba", "gb", "lcd", "nes", "ntsc", "pal", "snes", "vhs" -> word.uppercase()
                else -> word.replaceFirstChar { it.titlecase() }
            }
        }
}

@Composable
private fun ShaderPresetCategoryList(
    groups: List<ShaderPresetDialogGroup>,
    selectedPath: String,
    listState: LazyListState,
    onOpenCategory: (String) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .tvFocusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 10.dp)
    ) {
        items(items = groups, key = ShaderPresetDialogGroup::key) { group ->
            ShaderPresetCategoryCard(
                group = group,
                selected = group.options.any { (path, _) -> path == selectedPath },
                onClick = { onOpenCategory(group.key) }
            )
        }
    }
}

@Composable
private fun ShaderPresetCategoryOptions(
    group: ShaderPresetDialogGroup,
    selectedPath: String,
    onCloseCategory: () -> Unit,
    onSelect: (String) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(group.key, selectedPath) {
        val selectedIndex = group.options.indexOfFirst { (path, _) -> path == selectedPath }
        if (selectedIndex >= 0) listState.scrollToItem(selectedIndex)
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ShaderPresetCategoryCard(
            group = group,
            selected = group.options.any { (path, _) -> path == selectedPath },
            expanded = true,
            onClick = onCloseCategory,
            modifier = Modifier.padding(horizontal = 22.dp)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .tvFocusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 10.dp)
        ) {
            items(
                items = group.options,
                key = { (path, _) -> path.ifEmpty { "shader-preset-none" } }
            ) { (path, label) ->
                ShaderPresetDialogOption(
                    label = if (group.stripsCategoryPrefix) label.substringAfter('/') else label,
                    selected = path == selectedPath,
                    onClick = { onSelect(path) }
                )
            }
        }
    }
}

@Composable
private fun ShaderPresetCategoryCard(
    group: ShaderPresetDialogGroup,
    selected: Boolean,
    expanded: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = neonShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .tvGamepadFocusableCard(
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            ),
        shape = shape,
        interactionSource = interactionSource,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = group.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f))
            ) {
                Text(
                    text = group.options.size.toString(),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ShaderPresetDialogOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = neonShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .tvGamepadFocusableCard(
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            ),
        shape = shape,
        interactionSource = interactionSource,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.62f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun SideArtworkPicker(
    title: String,
    options: List<Pair<Int, String>>,
    selectedValue: Int,
    revision: Int,
    onSelect: (Int) -> Unit,
    helpText: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            SettingHelpButton(title = title, description = helpText)
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(options, key = { it.first }) { (value, label) ->
                val artwork = EmulationSideArtwork.fromPreference(value)
                val selected = selectedValue == value
                val interactionSource = remember { MutableInteractionSource() }
                Surface(
                    onClick = { onSelect(value) },
                    modifier = Modifier
                        .width(156.dp)
                        .aspectRatio(16f / 9f)
                        .tvGamepadFocusableCard(
                            shape = neonShape(14.dp),
                            interactionSource = interactionSource,
                            addFocusTarget = false
                        ),
                    shape = neonShape(14.dp),
                    color = Color.Black,
                    interactionSource = interactionSource,
                    border = BorderStroke(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        EmulationSideArtworkThumbnail(
                            artwork = artwork,
                            revision = revision,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.42f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.86f)
                                    )
                                )
                        )
                        Text(
                            text = label,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChoiceSection(
    title: String,
    options: List<Pair<Int, String>>,
    selectedValue: Int,
    onSelect: (Int) -> Unit,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val tvUiEnabled = LocalTvUiEnvironment.current.enabled
    val titleFocusRequester = remember { FocusRequester() }
    val helpFocusRequester = remember { FocusRequester() }
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .then(
                    if (tvUiEnabled && helpText != null) {
                        Modifier
                            .focusRequester(titleFocusRequester)
                            .focusProperties { right = helpFocusRequester }
                    } else {
                        Modifier
                    }
                )
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClick = onResetToDefault?.let {
                        {
                            it()
                            Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            helpText?.let {
                SettingHelpButton(
                    title = title,
                    description = it,
                    focusRequester = helpFocusRequester,
                    returnFocusRequester = titleFocusRequester
                )
            }
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(options) { (value, label) ->
                val optionInteractionSource = remember { MutableInteractionSource() }
                FilterChip(
                    modifier = Modifier.tvGamepadFocusableCard(
                        shape = neonShape(16.dp),
                        interactionSource = optionInteractionSource,
                        addFocusTarget = false
                    ),
                    shape = neonChipShape(),
                    selected = selectedValue == value,
                    onClick = { onSelect(value) },
                    interactionSource = optionInteractionSource,
                    colors = premiumFilterChipColors(),
                    label = { Text(text = label) }
                )
            }
        }
    }
}

@Suppress("UNUSED_EXPRESSION")
@Composable
internal fun CoreOptionSettingsRows(
    options: List<SwanStationCoreOptions.Option>,
    version: Int,
    onValueChange: (String, String) -> Unit
) {
    version
    options.forEach { option ->
        val current = NativeApp.getCoreOption(option.key) ?: option.defaultValue
        val titleRes = SwanStationCoreOptionStrings.optionLabelRes[option.key]
        val title = if (titleRes != null) stringResource(titleRes) else option.label
        val descRes = SwanStationCoreOptionStrings.optionDescriptionRes[option.key]
        val help = if (descRes != null) {
            stringResource(descRes)
        } else {
            option.description.takeIf { it.isNotBlank() }
        }
        if (option.isBooleanToggle) {
            ToggleItem(
                icon = Icons.Rounded.Tune,
                title = title,
                subtitle = "",
                checked = current.equals("true", ignoreCase = true),
                onCheckedChange = { enabled -> onValueChange(option.key, enabled.toString()) },
                helpText = help,
                onResetToDefault = { onValueChange(option.key, option.defaultValue) }
            )
        } else {
            CoreStringChoiceSection(
                title = title,
                options = option.choices.map { choice ->
                    val choiceRes = SwanStationCoreOptionStrings.choiceLabelRes[choice.label]
                    choice.value to (if (choiceRes != null) stringResource(choiceRes) else choice.label)
                },
                selectedValue = current,
                onSelect = { value -> onValueChange(option.key, value) },
                helpText = help,
                onResetToDefault = { onValueChange(option.key, option.defaultValue) }
            )
        }
    }
}

@Composable
private fun CoreStringChoiceSection(
    title: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val tvUiEnabled = LocalTvUiEnvironment.current.enabled
    val titleFocusRequester = remember { FocusRequester() }
    val helpFocusRequester = remember { FocusRequester() }
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .then(
                    if (tvUiEnabled && helpText != null) {
                        Modifier
                            .focusRequester(titleFocusRequester)
                            .focusProperties { right = helpFocusRequester }
                    } else {
                        Modifier
                    }
                )
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClick = onResetToDefault?.let {
                        {
                            it()
                            Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            helpText?.let {
                SettingHelpButton(
                    title = title,
                    description = it,
                    focusRequester = helpFocusRequester,
                    returnFocusRequester = titleFocusRequester
                )
            }
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(options) { (value, label) ->
                val optionInteractionSource = remember { MutableInteractionSource() }
                FilterChip(
                    modifier = Modifier.tvGamepadFocusableCard(
                        shape = neonShape(16.dp),
                        interactionSource = optionInteractionSource,
                        addFocusTarget = false
                    ),
                    shape = neonChipShape(),
                    selected = selectedValue == value,
                    onClick = { onSelect(value) },
                    interactionSource = optionInteractionSource,
                    colors = premiumFilterChipColors(),
                    label = { Text(text = label) }
                )
            }
        }
    }
}

@Composable
private fun BitmaskChoiceSection(
    title: String,
    options: List<Pair<Int, String>>,
    selectedMask: Int,
    onToggle: (Int) -> Unit,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val tvUiEnabled = LocalTvUiEnvironment.current.enabled
    val titleFocusRequester = remember { FocusRequester() }
    val helpFocusRequester = remember { FocusRequester() }
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .then(
                    if (tvUiEnabled && helpText != null) {
                        Modifier
                            .focusRequester(titleFocusRequester)
                            .focusProperties { right = helpFocusRequester }
                    } else {
                        Modifier
                    }
                )
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClick = onResetToDefault?.let {
                        {
                            it()
                            Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            helpText?.let {
                SettingHelpButton(
                    title = title,
                    description = it,
                    focusRequester = helpFocusRequester,
                    returnFocusRequester = titleFocusRequester
                )
            }
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(options) { (metric, label) ->
                val optionInteractionSource = remember { MutableInteractionSource() }
                FilterChip(
                    modifier = Modifier.tvGamepadFocusableCard(
                        shape = neonShape(16.dp),
                        interactionSource = optionInteractionSource,
                        addFocusTarget = false
                    ),
                    shape = neonChipShape(),
                    selected = PerformanceOverlayMetrics.isEnabled(selectedMask, metric),
                    onClick = { onToggle(metric) },
                    interactionSource = optionInteractionSource,
                    colors = premiumFilterChipColors(),
                    label = { Text(text = label) }
                )
            }
        }
    }
}

@Composable
private fun premiumFilterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    iconColor = MaterialTheme.colorScheme.primary,
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
)

@Composable
internal fun SettingsInlineNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun touchHapticsPresetOptions(): List<Pair<Int, String>> = listOf(
    AppPreferences.TOUCH_HAPTICS_PRESET_SOFT to stringResource(R.string.settings_touch_haptics_preset_soft),
    AppPreferences.TOUCH_HAPTICS_PRESET_BALANCED to stringResource(R.string.settings_touch_haptics_preset_balanced),
    AppPreferences.TOUCH_HAPTICS_PRESET_CRISP to stringResource(R.string.settings_touch_haptics_preset_crisp),
    AppPreferences.TOUCH_HAPTICS_PRESET_STRONG to stringResource(R.string.settings_touch_haptics_preset_strong)
)

@Composable
private fun gyroModeOptions(): List<Pair<Int, String>> = listOf(
    AppPreferences.GYRO_MODE_OFF to stringResource(R.string.settings_gyro_off),
    AppPreferences.GYRO_MODE_AIM to stringResource(R.string.settings_gyro_aim),
    AppPreferences.GYRO_MODE_STEERING to stringResource(R.string.settings_gyro_steering)
)

private fun resolveManualTargetFps(currentTargetFps: Int, defaultTargetFps: Int): Int {
    return when {
        currentTargetFps > 0 -> currentTargetFps
        defaultTargetFps > 0 -> defaultTargetFps
        else -> 60
    }
}

private fun formatFramerateHz(value: Float): String {
    val rounded = kotlin.math.round(value * 100f) / 100f
    val whole = rounded.toInt()
    return if (rounded == whole.toFloat()) {
        "$whole Hz"
    } else {
        "$rounded Hz"
    }
}

private fun formatSpeedMultiplier(value: Float): String {
    return "%.2fx".format(java.util.Locale.US, value)
}

@Composable
private fun fpsOverlayCornerOptions(): List<Pair<Int, String>> = listOf(
    AppPreferences.FPS_OVERLAY_CORNER_TOP_LEFT to stringResource(R.string.settings_fps_overlay_corner_top_left),
    AppPreferences.FPS_OVERLAY_CORNER_TOP_RIGHT to stringResource(R.string.settings_fps_overlay_corner_top_right),
    AppPreferences.FPS_OVERLAY_CORNER_BOTTOM_LEFT to stringResource(R.string.settings_fps_overlay_corner_bottom_left),
    AppPreferences.FPS_OVERLAY_CORNER_BOTTOM_RIGHT to stringResource(R.string.settings_fps_overlay_corner_bottom_right)
)

@Composable
private fun cpuSpriteRenderSizeOptions(): List<Pair<Int, String>> = (0..10).map { value ->
    value to if (value == 0) stringResource(R.string.settings_disabled_short) else value.toString()
}

@Composable
private fun fpsOverlayMetricOptions(): List<Pair<Int, String>> = listOf(
    PerformanceOverlayMetrics.FPS to stringResource(R.string.settings_fps_metric_fps),
    PerformanceOverlayMetrics.SPEED to stringResource(R.string.settings_fps_metric_speed),
    PerformanceOverlayMetrics.RENDERER to stringResource(R.string.settings_fps_metric_renderer),
    PerformanceOverlayMetrics.FRAME_TIME to stringResource(R.string.settings_fps_metric_frame_time),
    PerformanceOverlayMetrics.RESOLUTION to stringResource(R.string.settings_fps_metric_resolution),
    PerformanceOverlayMetrics.HOST_CPU to stringResource(R.string.settings_fps_metric_host_cpu),
    PerformanceOverlayMetrics.AUDIO to stringResource(R.string.settings_fps_metric_audio)
)

@StringRes
private fun gamepadActionLabelRes(actionId: String): Int = when (actionId) {
    "cross" -> R.string.settings_gamepad_action_cross
    "circle" -> R.string.settings_gamepad_action_circle
    "square" -> R.string.settings_gamepad_action_square
    "triangle" -> R.string.settings_gamepad_action_triangle
    "l1" -> R.string.settings_gamepad_action_l1
    "r1" -> R.string.settings_gamepad_action_r1
    "l2" -> R.string.settings_gamepad_action_l2
    "r2" -> R.string.settings_gamepad_action_r2
    "l3" -> R.string.settings_gamepad_action_l3
    "r3" -> R.string.settings_gamepad_action_r3
    "select" -> R.string.settings_gamepad_action_select
    "start" -> R.string.settings_gamepad_action_start
    "left_input_toggle" -> R.string.settings_gamepad_action_left_input_toggle
    "pressure" -> R.string.settings_gamepad_action_pressure
    GamepadManager.ACTION_QUICK_SAVE -> R.string.emulation_quick_save
    GamepadManager.ACTION_QUICK_LOAD -> R.string.emulation_quick_load
    "dpad_up" -> R.string.settings_gamepad_action_dpad_up
    "dpad_down" -> R.string.settings_gamepad_action_dpad_down
    "dpad_left" -> R.string.settings_gamepad_action_dpad_left
    "dpad_right" -> R.string.settings_gamepad_action_dpad_right
    else -> R.string.settings_gamepad_section
}

@Composable
private fun gamepadActionLabel(actionId: String): String = when (actionId) {
    "cross" -> "\u2715"
    "circle" -> "\u25cb"
    "square" -> "\u25a1"
    "triangle" -> "\u25b3"
    "pressure" -> stringResource(R.string.settings_gamepad_action_pressure)
    else -> stringResource(gamepadActionLabelRes(actionId))
}

@Composable
private fun gamepadPlayerLabel(padIndex: Int): String {
    return stringResource(
        if (padIndex == 0) R.string.settings_gamepad_player_1 else R.string.settings_gamepad_player_2
    )
}

@Composable
private fun GamepadAssignmentDialog(
    assignments: Map<Int, String>,
    ignoredDevices: Set<String>,
    onAssign: (Int, String?) -> Unit,
    onIgnore: (String, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val gamepads by GamepadManager.availableGamepadsState.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Gamepad,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.settings_gamepad_assignment_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_gamepad_assignment_desc),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (gamepads.isEmpty()) {
                    SettingsInlineNote(text = stringResource(R.string.settings_gamepad_assignment_empty))
                } else {
                    for (gamepad in gamepads) {
                        GamepadAssignmentRow(
                            gamepad = gamepad,
                            assignments = assignments,
                            ignoredDevices = ignoredDevices,
                            onAssign = onAssign,
                            onIgnore = onIgnore
                        )
                    }
                    SettingsInlineNote(text = stringResource(R.string.settings_gamepad_assignment_note))
                }
            }
        },
        confirmButton = {
            if (gamepads.isNotEmpty() || assignments.isNotEmpty() || ignoredDevices.isNotEmpty()) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.settings_gamepad_assignment_reset))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun GamepadAssignmentRow(
    gamepad: GamepadManager.AvailableGamepad,
    assignments: Map<Int, String>,
    ignoredDevices: Set<String>,
    onAssign: (Int, String?) -> Unit,
    onIgnore: (String, Boolean) -> Unit
) {
    val assignedPadIndex = assignments.entries.firstOrNull { it.value == gamepad.key }?.key
    val ignored = gamepad.key in ignoredDevices
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Gamepad,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = gamepad.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(
                            if (gamepad.isExternal) {
                                R.string.settings_gamepad_assignment_external
                            } else {
                                R.string.settings_gamepad_assignment_built_in
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    GamepadAssignmentChip(
                        label = stringResource(R.string.settings_gamepad_assignment_auto),
                        selected = assignedPadIndex == null && !ignored,
                        onClick = {
                            assignedPadIndex?.let { onAssign(it, null) }
                            if (ignored) onIgnore(gamepad.key, false)
                        }
                    )
                }
                items(listOf(0, 1)) { padIndex ->
                    GamepadAssignmentChip(
                        label = gamepadPlayerLabel(padIndex),
                        selected = assignedPadIndex == padIndex && !ignored,
                        onClick = {
                            if (ignored) onIgnore(gamepad.key, false)
                            onAssign(padIndex, gamepad.key)
                        }
                    )
                }
                item {
                    GamepadAssignmentChip(
                        label = stringResource(R.string.settings_gamepad_assignment_off),
                        selected = ignored,
                        onClick = {
                            assignedPadIndex?.let { onAssign(it, null) }
                            onIgnore(gamepad.key, true)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GamepadAssignmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    FilterChip(
        modifier = Modifier.tvGamepadFocusableCard(
            shape = neonShape(16.dp),
            interactionSource = interactionSource,
            addFocusTarget = false
        ),
        shape = neonChipShape(),
        selected = selected,
        onClick = onClick,
        interactionSource = interactionSource,
        label = { Text(label) }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val topInset = appScreenTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val horizontalSystemBarPadding = navigationBarsHorizontalPaddingValues()
    val options = rememberLanguageOptions()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontalSystemBarPadding)
            .verticalScroll(rememberScrollState())
    ) {
        ScreenTopBar(
            title = stringResource(R.string.settings_language),
            subtitle = stringResource(R.string.settings_language_screen_subtitle),
            onBackClick = onBackClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalPadding, vertical = 0.dp)
                .padding(top = topInset, bottom = 10.dp),
            backContentColor = MaterialTheme.colorScheme.onSurface
        )

        Column(
            modifier = Modifier
                .padding(horizontal = ScreenHorizontalPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            options.forEach { option ->
                LanguageOptionCard(
                    badgeText = option.badge,
                    title = stringResource(option.titleRes),
                    subtitle = option.subtitleRes?.let { stringResource(it) },
                    selected = uiState.languageTag == option.tag,
                    onClick = { viewModel.setLanguage(option.tag) }
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        Spacer(modifier = Modifier.height(bottomInset))
    }
}

@Composable
private fun LanguageOptionCard(
    badgeText: String,
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = neonShape(20.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .gamepadFocusableCard(
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            ),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        interactionSource = interactionSource,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(neonShape(14.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

private data class LanguageUiOption(
    val tag: String?,
    val badge: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int? = null
)

@Composable
private fun rememberLanguageOptions(): List<LanguageUiOption> {
    return remember {
        listOf(
            LanguageUiOption(null, "SYS", R.string.settings_language_system, R.string.settings_language_system_subtitle),
            LanguageUiOption("en", "EN", R.string.settings_language_english, R.string.settings_language_native_english),
            LanguageUiOption("uk", "UA", R.string.settings_language_ukrainian, R.string.settings_language_native_ukrainian),
            LanguageUiOption("ru", "RU", R.string.settings_language_russian, R.string.settings_language_native_russian),
            LanguageUiOption("es", "ES", R.string.settings_language_spanish, R.string.settings_language_native_spanish),
            LanguageUiOption("fr", "FR", R.string.settings_language_french, R.string.settings_language_native_french),
            LanguageUiOption("de", "DE", R.string.settings_language_german, R.string.settings_language_native_german),
            LanguageUiOption("pt", "PT", R.string.settings_language_portuguese, R.string.settings_language_native_portuguese),
            LanguageUiOption("it", "IT", R.string.settings_language_italian, R.string.settings_language_native_italian),
            LanguageUiOption("id", "ID", R.string.settings_language_indonesian, R.string.settings_language_native_indonesian),
            LanguageUiOption("hi", "HI", R.string.settings_language_hindi, R.string.settings_language_native_hindi),
            LanguageUiOption("zh", "繁", R.string.settings_language_traditional_chinese, R.string.settings_language_native_traditional_chinese),
            LanguageUiOption("ar", "AR", R.string.settings_language_arabic, R.string.settings_language_native_arabic),
            LanguageUiOption("fa", "FA", R.string.settings_language_persian, R.string.settings_language_native_persian),
            LanguageUiOption("ja", "JA", R.string.settings_language_japanese, R.string.settings_language_native_japanese),
            LanguageUiOption("ko", "KO", R.string.settings_language_korean, R.string.settings_language_native_korean),
            LanguageUiOption("tr", "TR", R.string.settings_language_turkish, R.string.settings_language_native_turkish),
            LanguageUiOption("pl", "PL", R.string.settings_language_polish, R.string.settings_language_native_polish),
            LanguageUiOption("cs", "CS", R.string.settings_language_czech, R.string.settings_language_native_czech)
        )
    }
}

@Composable
private fun languageLabel(tag: String?): String {
    return when (tag) {
        "en" -> stringResource(R.string.settings_language_english)
        "uk" -> stringResource(R.string.settings_language_ukrainian)
        "ru" -> stringResource(R.string.settings_language_russian)
        "es" -> stringResource(R.string.settings_language_spanish)
        "fr" -> stringResource(R.string.settings_language_french)
        "de" -> stringResource(R.string.settings_language_german)
        "pt" -> stringResource(R.string.settings_language_portuguese)
        "it" -> stringResource(R.string.settings_language_italian)
        "id", "id-ID", "in", "in-ID" -> stringResource(R.string.settings_language_indonesian)
        "hi" -> stringResource(R.string.settings_language_hindi)
        "zh", "zh-TW", "zh-Hant", "zh-Hant-TW" -> stringResource(R.string.settings_language_traditional_chinese)
        "ar" -> stringResource(R.string.settings_language_arabic)
        "fa", "fa-IR" -> stringResource(R.string.settings_language_persian)
        "ja" -> stringResource(R.string.settings_language_japanese)
        "ko" -> stringResource(R.string.settings_language_korean)
        "tr" -> stringResource(R.string.settings_language_turkish)
        "pl" -> stringResource(R.string.settings_language_polish)
        "cs" -> stringResource(R.string.settings_language_czech)
        else -> stringResource(R.string.settings_language_system)
    }
}

@Composable
private fun SettingsTab.label(): String {
    return when (this) {
        SettingsTab.General -> stringResource(R.string.settings_general_tab)
        SettingsTab.Graphics -> "Display"
        SettingsTab.Patches -> stringResource(R.string.settings_patches_tab)
        SettingsTab.Customization -> stringResource(R.string.settings_customization_tab)
        SettingsTab.GameMenu -> stringResource(R.string.settings_game_menu_tab)
        SettingsTab.Audio -> stringResource(R.string.settings_audio_tab)
        SettingsTab.Controls -> stringResource(R.string.settings_controls_tab)
        SettingsTab.System -> "System"
        SettingsTab.BIOS -> "BIOS"
        SettingsTab.Video -> "Video"
        SettingsTab.Shaders -> "Shaders"
        SettingsTab.CDROM -> "CD-ROM"
        SettingsTab.TextureReplacements -> "Texture Replacements"
        SettingsTab.Hacks -> "Hacks"
        SettingsTab.PGXP -> "PGXP"
        SettingsTab.SkipEngine -> "Skip Engine / Trainer"
        SettingsTab.Emulation -> stringResource(R.string.settings_emulation_tab)
        SettingsTab.Library -> stringResource(R.string.settings_library_tab)
        SettingsTab.About -> stringResource(R.string.settings_about)
    }
}

private fun SettingsTab.icon(): ImageVector {
    return when (this) {
        SettingsTab.General -> Icons.Rounded.Tune
        SettingsTab.Graphics -> Icons.Rounded.Wallpaper
        SettingsTab.Patches -> Icons.Rounded.AutoFixHigh
        SettingsTab.Customization -> Icons.Rounded.Palette
        SettingsTab.GameMenu -> Icons.Rounded.MoreVert
        SettingsTab.Audio -> Icons.AutoMirrored.Rounded.VolumeUp
        SettingsTab.Controls -> Icons.Rounded.Gamepad
        SettingsTab.System -> Icons.Rounded.SettingsSuggest
        SettingsTab.BIOS -> Icons.Rounded.SettingsSuggest
        SettingsTab.Video -> Icons.Rounded.SettingsSuggest
        SettingsTab.Shaders -> Icons.Rounded.AutoFixHigh
        SettingsTab.CDROM -> Icons.Rounded.SettingsSuggest
        SettingsTab.TextureReplacements -> Icons.Rounded.Texture
        SettingsTab.Hacks -> Icons.Rounded.SettingsSuggest
        SettingsTab.PGXP -> Icons.Rounded.AutoFixHigh
        SettingsTab.SkipEngine -> Icons.Rounded.Speed
        SettingsTab.Emulation -> Icons.Rounded.Speed
        SettingsTab.Library -> Icons.Rounded.FolderOpen
        SettingsTab.About -> Icons.Rounded.Info
    }
}

private fun String.toSettingsTab(): SettingsTab {
    return when (lowercase()) {
        "customization", "customize", "appearance", "background", "font", "layout" -> SettingsTab.Customization
        "game_menu", "game-menu", "ingame", "in-game", "menu" -> SettingsTab.GameMenu
        "audio", "sound" -> SettingsTab.Audio
        "controls" -> SettingsTab.Controls
        "graphics", "display" -> SettingsTab.Graphics
        "video", "renderer" -> SettingsTab.Video
        "shader", "shaders" -> SettingsTab.Shaders
        "patches", "patch", "patch_database", "patch-database" -> SettingsTab.Patches
        "paths", "files", "memorycards", "memory_cards", "memory-cards", "memcards", "covers",
        "cover-art", "cover_art", "data_transfer", "transfer", "backup", "data-transfer", "library" -> SettingsTab.Library
        "performance", "emulation" -> SettingsTab.Emulation
        "about" -> SettingsTab.About
        else -> SettingsTab.General
    }
}
