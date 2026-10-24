package com.sbro.emucorer.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucorer.BuildConfig
import com.sbro.emucorer.core.AndroidTouchHaptics
import com.sbro.emucorer.core.AudioDefaults
import com.sbro.emucorer.core.AppUpdateRelease
import com.sbro.emucorer.core.AppUpdateRepository
import com.sbro.emucorer.core.BiosValidator
import com.sbro.emucorer.core.DocumentPathResolver
import com.sbro.emucorer.core.EmulatorBridge
import com.sbro.emucorer.core.EmulatorDataLocation
import com.sbro.emucorer.core.EmulatorStorage
import com.sbro.emucorer.core.GpuHardwareProfiles
import com.sbro.emucorer.core.RendererDefaults
import com.sbro.emucorer.core.GamepadManager
import com.sbro.emucorer.core.GsHackDefaults
import com.sbro.emucorer.core.PerformanceProfiles
import com.sbro.emucorer.core.PerformancePresets
import com.sbro.emucorer.core.NativeApp
import com.sbro.emucorer.core.SetupValidator
import com.sbro.emucorer.core.StorageAccess
import com.sbro.emucorer.core.TvInterfaceMode
import com.sbro.emucorer.core.normalizeUpscale
import com.sbro.emucorer.data.AppPreferences
import com.sbro.emucorer.data.DisplayCrop
import com.sbro.emucorer.data.AppFontChoice
import com.sbro.emucorer.data.HomeBackgroundRepository
import com.sbro.emucorer.data.HomeBackgroundPreset
import com.sbro.emucorer.data.HomeBackgroundType
import com.sbro.emucorer.data.EmulationSideArtwork
import com.sbro.emucorer.data.EmulationSideArtworkRepository
import com.sbro.emucorer.data.RetroArchShaderPreset
import com.sbro.emucorer.data.RetroArchShaderRepository
import com.sbro.emucorer.data.PatchDatabaseDownloader
import com.sbro.emucorer.data.PatchDatabaseInstallProgress
import com.sbro.emucorer.data.PatchDatabaseInstallStage
import com.sbro.emucorer.data.ShaderPackInstallProgress
import com.sbro.emucorer.data.ShaderPackInstallStage
import com.sbro.emucorer.data.TouchControlVisualStyle
import com.sbro.emucorer.data.TouchControlPressEffect
import com.sbro.emucorer.data.GameMenuLayoutStyle
import com.sbro.emucorer.data.DrawerVisualStyle
import com.sbro.emucorer.data.DrawerItemId
import com.sbro.emucorer.data.GameMenuTabId
import com.sbro.emucorer.data.GameMenuSectionId
import com.sbro.emucorer.data.DefaultGameMenuTabOrder
import com.sbro.emucorer.data.DefaultGameMenuSectionOrder
import com.sbro.emucorer.data.AppPreferences.Companion.FPS_OVERLAY_MODE_SIMPLE
import com.sbro.emucorer.data.CoverArtRepository
import com.sbro.emucorer.data.CoverCacheClearResult
import com.sbro.emucorer.data.CustomFontRepository
import com.sbro.emucorer.data.CustomThemeConfig
import com.sbro.emucorer.data.CustomThemeLibrary
import com.sbro.emucorer.data.CustomTouchControlLibrary
import com.sbro.emucorer.data.SettingsSnapshot
import com.sbro.emucorer.data.PerformanceOverlayMetrics
import com.sbro.emucorer.ui.common.clearCoverImageMemoryCache
import com.sbro.emucorer.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoaded: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val customTheme: CustomThemeConfig = CustomThemeConfig.Default,
    val customThemeLibrary: CustomThemeLibrary = CustomThemeLibrary.Empty,
    val customTouchControls: CustomTouchControlLibrary = CustomTouchControlLibrary.Empty,
    val appFontChoice: AppFontChoice = AppFontChoice.SYSTEM,
    val appFontScale: Float = AppPreferences.DEFAULT_APP_FONT_SCALE,
    val customFontName: String? = null,
    val customFontRevision: Int = 0,
    val homeGridScale: Float = AppPreferences.DEFAULT_HOME_GRID_SCALE,
    val homeBackgroundType: HomeBackgroundType = HomeBackgroundType.NONE,
    val homeBackgroundPreset: HomeBackgroundPreset = HomeBackgroundPreset.OLYMPUS,
    val homeBackgroundRevision: Int = 0,
    val homeBackgroundDim: Int = AppPreferences.DEFAULT_HOME_BACKGROUND_DIM,
    val emulationSideArtwork: EmulationSideArtwork = EmulationSideArtwork.NONE,
    val emulationSideArtworkRevision: Int = 0,
    val emulationSideArtworkDim: Int = AppPreferences.DEFAULT_EMULATION_SIDE_ARTWORK_DIM,
    val isSideArtworkImporting: Boolean = false,
    val shaderChainEnabled: Boolean = false,
    val shaderChainPreset: String = "",
    val shaderPresets: List<RetroArchShaderPreset> = emptyList(),
    val isShaderPackInstalled: Boolean = false,
    val isShaderPackBusy: Boolean = false,
    val shaderPackProgress: ShaderPackInstallProgress? = null,
    val shaderPackMessageResId: Int? = null,
    val patchDatabaseUseOfficial: Boolean = true,
    val patchDatabaseCustomUrl: String? = null,
    val installedPatchCount: Int = 0,
    val isPatchDatabaseBusy: Boolean = false,
    val patchDatabaseProgress: PatchDatabaseInstallProgress? = null,
    val patchDatabaseMessageResId: Int? = null,
    val patchDatabaseMessageCount: Int? = null,
    val touchControlVisualStyle: TouchControlVisualStyle = TouchControlVisualStyle.CLASSIC,
    val touchControlPressEffect: TouchControlPressEffect = TouchControlPressEffect.GROW,
    val gameMenuLayoutStyle: GameMenuLayoutStyle = GameMenuLayoutStyle.SIDEBAR,
    val drawerVisualStyle: DrawerVisualStyle = DrawerVisualStyle.CLASSIC,
    val hiddenDrawerItems: Set<DrawerItemId> = emptySet(),
    val gameMenuTabOrder: List<GameMenuTabId> = DefaultGameMenuTabOrder,
    val hiddenGameMenuTabs: Set<GameMenuTabId> = emptySet(),
    val gameMenuSectionOrder: List<GameMenuSectionId> = DefaultGameMenuSectionOrder,
    val hiddenGameMenuSections: Set<GameMenuSectionId> = emptySet(),
    val isBackgroundImporting: Boolean = false,
    val customizationMessageResId: Int? = null,
    val languageTag: String? = null,
    val tvInterfaceMode: TvInterfaceMode = TvInterfaceMode.AUTO,
    val renderer: Int = RendererDefaults.defaultForHardware(),
    val upscaleMultiplier: Float = 1f,
    val aspectRatio: Int = 1,
    val localMultiplayerMode: Int = AppPreferences.LOCAL_MULTIPLAYER_OFF,
    val displayCrop: DisplayCrop = DisplayCrop.None,
    val audioVolume: Int = AudioDefaults.VOLUME_DEFAULT,
    val audioFastForwardVolume: Int = AudioDefaults.VOLUME_DEFAULT,
    val audioMuted: Boolean = false,
    val audioInterpolation: Int = AudioDefaults.INTERPOLATION_DEFAULT,
    val audioSyncMode: Int = AudioDefaults.SYNC_DEFAULT,
    val audioLightweightSpu2: Boolean = AudioDefaults.LIGHTWEIGHT_SPU2_DEFAULT,
    val audioBackend: Int = AudioDefaults.BACKEND_DEFAULT,
    val audioBufferMs: Int = AudioDefaults.BUFFER_MS_DEFAULT,
    val audioOutputLatencyMs: Int = AudioDefaults.OUTPUT_LATENCY_MS_DEFAULT,
    val audioMinimalOutputLatency: Boolean = AudioDefaults.MINIMAL_OUTPUT_LATENCY_DEFAULT,
    val autoProgressiveScan: Boolean = false,
    val padVibration: Boolean = true,
    val padVibrationStrength: Int = AppPreferences.DEFAULT_PAD_VIBRATION_STRENGTH,
    val padVibrationFallback: Boolean = true,
    val showFps: Boolean = true,
    val fpsOverlayMode: Int = FPS_OVERLAY_MODE_SIMPLE,
    val fpsOverlayCorner: Int = AppPreferences.FPS_OVERLAY_CORNER_TOP_RIGHT,
    val fpsOverlayScale: Int = AppPreferences.DEFAULT_FPS_OVERLAY_SCALE,
    val fpsOverlayMetrics: Int = PerformanceOverlayMetrics.DEFAULT,
    val confirmSaveLoadActions: Boolean = true,
    val backButtonExitsGame: Boolean = false,
    val compactControls: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showRecentGames: Boolean = true,
    val showHomeSearch: Boolean = false,
    val showDebugOptions: Boolean = false,
    val debugLogcatGs: Boolean = false,
    val profilerLogcat: Boolean = false,
    val preferEnglishGameTitles: Boolean = false,
    val biosPath: String? = null,
    val gamePath: String? = null,
    val gamePaths: List<String> = emptyList(),
    val emulatorDataPath: String? = null,
    val sdCardDataPath: String? = null,
    val coverDownloadBaseUrl: String? = null,
    val coverArtStyle: Int = AppPreferences.COVER_ART_STYLE_DEFAULT,
    val biosValid: Boolean = false,
    val setupComplete: Boolean = false,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val coreName: String = "unknown",
    val coreVersion: String = "unknown",
    val performanceProfile: Int = PerformanceProfiles.SAFE,
    // Extended settings
    val eeCycleRate: Int = 0,
    val eeCycleSkip: Int = 0,
    val enableEeRecompiler: Boolean = true,
    val enableIopRecompiler: Boolean = true,
    val enableVu0Recompiler: Boolean = true,
    val enableVu1Recompiler: Boolean = true,
    val enableFastmem: Boolean = true,
    val eeFpuRoundMode: Int = AppPreferences.DEFAULT_EE_FPU_ROUND_MODE,
    val vu0RoundMode: Int = AppPreferences.DEFAULT_VU_ROUND_MODE,
    val vu1RoundMode: Int = AppPreferences.DEFAULT_VU_ROUND_MODE,
    val eeFpuClampingMode: Int = AppPreferences.DEFAULT_EE_FPU_CLAMPING_MODE,
    val vu0ClampingMode: Int = AppPreferences.DEFAULT_VU0_CLAMPING_MODE,
    val vu1ClampingMode: Int = AppPreferences.DEFAULT_VU1_CLAMPING_MODE,
    val enableGameFixes: Boolean = true,
    val enableEeTimingHack: Boolean = false,
    val enableWaitLoopSpeedhack: Boolean = true,
    val enableIntcStatSpeedhack: Boolean = true,
    val enableVuFlagHack: Boolean = true,
    val enableInstantVu1: Boolean = true,
    val enableMtvu: Boolean = true,
    val enableThreadPinning: Boolean = AppPreferences.DEFAULT_THREAD_PINNING,
    val enableFastBoot: Boolean = true,
    val enableFastCdvd: Boolean = false,
    val enableCheats: Boolean = false,
    val hwDownloadMode: Int = GsHackDefaults.HW_DOWNLOAD_MODE_DEFAULT,
    val frameSkip: Int = 0,
    val skipDuplicateFrames: Boolean = true,
    val textureFiltering: Int = GsHackDefaults.BILINEAR_FILTERING_DEFAULT,
    val trilinearFiltering: Int = GsHackDefaults.TRILINEAR_FILTERING_DEFAULT,
    val blendingAccuracy: Int = GsHackDefaults.BLENDING_ACCURACY_DEFAULT,
    val texturePreloading: Int = GsHackDefaults.TEXTURE_PRELOADING_DEFAULT,
    val enableFxaa: Boolean = false,
    val sgsrMode: Int = 0,
    val casMode: Int = 0,
    val casSharpness: Int = 50,
    val tvShader: Int = GsHackDefaults.TV_SHADER_DEFAULT,
    val shadeBoostEnabled: Boolean = false,
    val shadeBoostBrightness: Int = 50,
    val shadeBoostContrast: Int = 50,
    val shadeBoostSaturation: Int = 50,
    val shadeBoostGamma: Int = 50,
    val enableWidescreenPatches: Boolean = false,
    val enableNoInterlacingPatches: Boolean = false,
    val deinterlaceMode: Int = GsHackDefaults.DEINTERLACE_MODE_DEFAULT,
    val dithering: Int = GsHackDefaults.DITHERING_DEFAULT,
    val anisotropicFiltering: Int = 0,
    val enableHwMipmapping: Boolean = GsHackDefaults.HW_MIPMAPPING_DEFAULT,
    val antiBlur: Boolean = GsHackDefaults.ANTI_BLUR_DEFAULT,
    val cpuSpriteRenderSize: Int = GsHackDefaults.CPU_SPRITE_RENDER_SIZE_DEFAULT,
    val cpuSpriteRenderLevel: Int = GsHackDefaults.CPU_SPRITE_RENDER_LEVEL_DEFAULT,
    val softwareClutRender: Int = GsHackDefaults.SOFTWARE_CLUT_RENDER_DEFAULT,
    val gpuTargetClutMode: Int = GsHackDefaults.GPU_TARGET_CLUT_DEFAULT,
    val skipDrawStart: Int = 0,
    val skipDrawEnd: Int = 0,
    val autoFlushHardware: Int = GsHackDefaults.AUTO_FLUSH_DEFAULT,
    val cpuFramebufferConversion: Boolean = false,
    val disableDepthConversion: Boolean = false,
    val disableSafeFeatures: Boolean = false,
    val disableRenderFixes: Boolean = false,
    val preloadFrameData: Boolean = false,
    val disablePartialInvalidation: Boolean = false,
    val textureInsideRt: Int = GsHackDefaults.TEXTURE_INSIDE_RT_DEFAULT,
    val readTargetsOnClose: Boolean = false,
    val estimateTextureRegion: Boolean = false,
    val gpuPaletteConversion: Boolean = false,
    val halfPixelOffset: Int = GsHackDefaults.HALF_PIXEL_OFFSET_DEFAULT,
    val nativeScaling: Int = GsHackDefaults.NATIVE_SCALING_DEFAULT,
    val roundSprite: Int = GsHackDefaults.ROUND_SPRITE_DEFAULT,
    val bilinearUpscale: Int = GsHackDefaults.BILINEAR_UPSCALE_DEFAULT,
    val textureOffsetX: Int = 0,
    val textureOffsetY: Int = 0,
    val alignSprite: Boolean = false,
    val mergeSprite: Boolean = false,
    val forceEvenSpritePosition: Boolean = false,
    val nativePaletteDraw: Boolean = false,
    val performancePreset: Int = PerformancePresets.CUSTOM,
    // Overlay
    val overlayScale: Int = 100,
    val overlayOpacity: Int = AppPreferences.DEFAULT_OVERLAY_OPACITY,
    val overlayShow: Boolean = true,
    val racingMode: Boolean = false,
    val touchscreenRightStick: Boolean = AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK,
    val touchscreenRightStickSensitivity: Int = AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK_SENSITIVITY,
    val touchHaptics: Boolean = false,
    val touchHapticsPreset: Int = AppPreferences.DEFAULT_TOUCH_HAPTICS_PRESET,
    val touchHapticsStrength: Int = AppPreferences.DEFAULT_TOUCH_HAPTICS_STRENGTH,
    val gyroMode: Int = AppPreferences.GYRO_MODE_OFF,
    val gyroSensitivity: Int = AppPreferences.DEFAULT_GYRO_SENSITIVITY,
    val gyroSmoothing: Int = AppPreferences.DEFAULT_GYRO_SMOOTHING,
    val gyroInvertX: Boolean = false,
    val gyroInvertY: Boolean = false,
    val leftStickSensitivity: Int = AppPreferences.DEFAULT_STICK_SENSITIVITY,
    val rightStickSensitivity: Int = AppPreferences.DEFAULT_STICK_SENSITIVITY,
    val invertLeftStick: Boolean = false,
    val invertRightStick: Boolean = false,
    val invertLeftStickHorizontal: Boolean = false,
    val invertRightStickHorizontal: Boolean = false,
    // Gamepad
    val enableAutoGamepad: Boolean = true,
    val hideOverlayOnGamepad: Boolean = true,
    val gamepadStickDeadzone: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_DEADZONE,
    val gamepadLeftStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadRightStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadRightStickUpToR2: Boolean = false,
    val gamepadRightStickDownToL2: Boolean = false,
    val gamepadButtonHaptics: Boolean = false,
    val pressureModifierAmount: Int = AppPreferences.DEFAULT_PRESSURE_MODIFIER_AMOUNT,
    val gamepadBindings: Map<String, Int> = emptyMap(),
    val gamepadBindingsByPad: Map<Int, Map<String, Int>> = emptyMap(),
    val gpuDriverType: Int = 0,
    val mediatekAngleOpenGl: Boolean = false,
    val customDriverPath: String? = null,
    val appUpdate: AppUpdateUiState = AppUpdateUiState(),
    val frameLimitEnabled: Boolean = true,
    val vSyncEnabled: Boolean = false,
    val fastForwardSpeed: Float = AppPreferences.DEFAULT_FAST_FORWARD_SPEED,
    val targetFps: Int = 0,
    val ntscFramerate: Float = AppPreferences.DEFAULT_NTSC_FRAMERATE,
    val palFramerate: Float = AppPreferences.DEFAULT_PAL_FRAMERATE,
    // Core-specific emulation settings
    val enableIcacheEmulation: Boolean = false,
    val enableDisableStalls: Boolean = false,
    val enablePreciseExceptions: Boolean = false,
    val enableTurboCd: Boolean = false,
    val enableCddaAudio: Boolean = true,
    val enableXaDecoding: Boolean = true,
    val enableSpuReverb: Boolean = true,
    val enableSpuThread: Boolean = false,
    val spuTempo: Int = 0,
    val neonEnhancement: Boolean = false,
    val neonEnhancementSpeedHack: Boolean = false,
    val neonEnhancementTexAdj: Boolean = true,
    val neonInterlace: Int = -1,
    val gpuThreadRendering: Int = -1,
    val showOverscan: Boolean = false,
    val screenCentering: Int = 0,
    val screenCenteringX: Int = 0,
    val screenCenteringY: Int = 0,
    val screenCenteringHAdj: Int = 0,
    val enableFractionalFramerate: Boolean = false,
    val altFlipMode: Int = 0,
    val enableRgb32Output: Boolean = false,
    val enableScaleHires: Boolean = false,
    val multitapMode: Int = 0,
    val analogAxisModifier: Int = 0,
    val dualshockToggleCombo: Int = 0,
    val cdReadAhead: Int = 0
)

data class AppUpdateUiState(
    val releaseHistory: List<AppUpdateRelease> = emptyList(),
    val historyLoading: Boolean = false,
    val historyErrorMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences(application)
    private val customFontRepository = CustomFontRepository(application)
    private val homeBackgroundRepository = HomeBackgroundRepository(application)
    private val emulationSideArtworkRepository = EmulationSideArtworkRepository(application)
    private val retroArchShaderRepository = RetroArchShaderRepository(application)
    private val patchDatabaseDownloader = PatchDatabaseDownloader(application)
    private val appUpdateRepository = AppUpdateRepository(application)
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    val floatingQuickActionsEnabled: StateFlow<Boolean> = preferences.floatingQuickActionsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val orientationLock: StateFlow<Int> = preferences.orientationLock
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.ORIENTATION_LOCK_AUTO)
    val emulationAllowsBothOrientations: StateFlow<Boolean> = preferences.emulationAllowsBothOrientations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        initializeAboutInfo()
        refreshShaderPresets()
        refreshPatchDatabaseState()
        viewModelScope.launch {
            preferences.cleanupLegacyClampingPreferencesIfNeeded()
            preferences.settingsSnapshot.collect { snapshot ->
                applySettingsSnapshot(snapshot)
            }
        }
        viewModelScope.launch {
            preferences.biosPath.distinctUntilChanged().collect { path ->
                val biosValid = withContext(Dispatchers.IO) {
                    BiosValidator.hasUsableBiosFiles(getApplication(), path)
                }
                _uiState.value = _uiState.value.copy(biosValid = biosValid)
            }
        }
        refreshEmulatorDataLocations()
    }

    private fun initializeAboutInfo() {
        val application = getApplication<Application>()
        val appVersion = runCatching {
            application.packageManager.getPackageInfo(application.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
        _uiState.value = _uiState.value.copy(
            appVersion = appVersion,
            coreName = CORE_NAME,
            coreVersion = CORE_VERSION
        )
    }

    private fun applySettingsSnapshot(snapshot: SettingsSnapshot) {
        _uiState.value = _uiState.value.copy(
            isLoaded = true,
            themeMode = snapshot.themeMode,
            customTheme = snapshot.customTheme,
            customThemeLibrary = snapshot.customThemeLibrary,
            customTouchControls = snapshot.customTouchControls,
            appFontChoice = snapshot.appFontChoice,
            appFontScale = snapshot.appFontScale,
            customFontName = snapshot.customFontName,
            customFontRevision = snapshot.customFontRevision,
            homeGridScale = snapshot.homeGridScale,
            homeBackgroundType = snapshot.homeBackgroundType,
            homeBackgroundPreset = snapshot.homeBackgroundPreset,
            homeBackgroundRevision = snapshot.homeBackgroundRevision,
            homeBackgroundDim = snapshot.homeBackgroundDim,
            emulationSideArtwork = snapshot.emulationSideArtwork,
            emulationSideArtworkRevision = snapshot.emulationSideArtworkRevision,
            emulationSideArtworkDim = snapshot.emulationSideArtworkDim,
            shaderChainEnabled = snapshot.shaderChainEnabled,
            shaderChainPreset = snapshot.shaderChainPreset,
            touchControlVisualStyle = snapshot.touchControlVisualStyle,
            touchControlPressEffect = snapshot.touchControlPressEffect,
            gameMenuLayoutStyle = snapshot.gameMenuLayoutStyle,
            drawerVisualStyle = snapshot.drawerVisualStyle,
            hiddenDrawerItems = snapshot.hiddenDrawerItems,
            gameMenuTabOrder = snapshot.gameMenuTabOrder,
            hiddenGameMenuTabs = snapshot.hiddenGameMenuTabs,
            gameMenuSectionOrder = snapshot.gameMenuSectionOrder,
            hiddenGameMenuSections = snapshot.hiddenGameMenuSections,
            languageTag = snapshot.languageTag,
            tvInterfaceMode = snapshot.tvInterfaceMode,
            renderer = snapshot.renderer,
            upscaleMultiplier = snapshot.upscaleMultiplier,
            aspectRatio = snapshot.aspectRatio,
            localMultiplayerMode = snapshot.localMultiplayerMode,
            displayCrop = snapshot.displayCrop,
            audioVolume = snapshot.audioVolume,
            audioFastForwardVolume = snapshot.audioFastForwardVolume,
            audioMuted = snapshot.audioMuted,
            audioInterpolation = snapshot.audioInterpolation,
            audioSyncMode = snapshot.audioSyncMode,
            audioLightweightSpu2 = snapshot.audioLightweightSpu2,
            audioBackend = snapshot.audioBackend,
            audioBufferMs = snapshot.audioBufferMs,
            audioOutputLatencyMs = snapshot.audioOutputLatencyMs,
            audioMinimalOutputLatency = snapshot.audioMinimalOutputLatency,
            autoProgressiveScan = snapshot.autoProgressiveScan,
            padVibration = snapshot.padVibration,
            padVibrationStrength = snapshot.padVibrationStrength,
            padVibrationFallback = snapshot.padVibrationFallback,
            showFps = snapshot.showFps,
            fpsOverlayMode = snapshot.fpsOverlayMode,
            fpsOverlayCorner = snapshot.fpsOverlayCorner,
            fpsOverlayScale = snapshot.fpsOverlayScale,
            fpsOverlayMetrics = snapshot.fpsOverlayMetrics,
            confirmSaveLoadActions = snapshot.confirmSaveLoadActions,
            backButtonExitsGame = snapshot.backButtonExitsGame,
            compactControls = snapshot.compactControls,
            keepScreenOn = snapshot.keepScreenOn,
            showRecentGames = snapshot.showRecentGames,
            showHomeSearch = snapshot.showHomeSearch,
            showDebugOptions = snapshot.showDebugOptions,
            debugLogcatGs = snapshot.debugLogcatGs,
            profilerLogcat = snapshot.profilerLogcat,
            preferEnglishGameTitles = snapshot.preferEnglishGameTitles,
            biosPath = snapshot.biosPath,
            gamePath = snapshot.gamePath,
            gamePaths = snapshot.gamePaths,
            emulatorDataPath = snapshot.emulatorDataPath,
            coverDownloadBaseUrl = snapshot.coverDownloadBaseUrl,
            coverArtStyle = snapshot.coverArtStyle,
            setupComplete = snapshot.setupComplete,
            performanceProfile = snapshot.performanceProfile,
            eeCycleRate = snapshot.eeCycleRate,
            eeCycleSkip = snapshot.eeCycleSkip,
            enableEeRecompiler = snapshot.enableEeRecompiler,
            enableIopRecompiler = snapshot.enableIopRecompiler,
            enableVu0Recompiler = snapshot.enableVu0Recompiler,
            enableVu1Recompiler = snapshot.enableVu1Recompiler,
            enableFastmem = snapshot.enableFastmem,
            eeFpuRoundMode = snapshot.eeFpuRoundMode,
            vu0RoundMode = snapshot.vu0RoundMode,
            vu1RoundMode = snapshot.vu1RoundMode,
            eeFpuClampingMode = snapshot.eeFpuClampingMode,
            vu0ClampingMode = snapshot.vu0ClampingMode,
            vu1ClampingMode = snapshot.vu1ClampingMode,
            enableGameFixes = snapshot.enableGameFixes,
            enableEeTimingHack = snapshot.enableEeTimingHack,
            enableWaitLoopSpeedhack = snapshot.enableWaitLoopSpeedhack,
            enableIntcStatSpeedhack = snapshot.enableIntcStatSpeedhack,
            enableVuFlagHack = snapshot.enableVuFlagHack,
            enableInstantVu1 = snapshot.enableInstantVu1,
            enableMtvu = snapshot.enableMtvu,
            enableThreadPinning = snapshot.enableThreadPinning,
            enableFastBoot = snapshot.enableFastBoot,
            enableFastCdvd = snapshot.enableFastCdvd,
            enableCheats = snapshot.enableCheats,
            hwDownloadMode = snapshot.hwDownloadMode,
            frameSkip = snapshot.frameSkip,
            skipDuplicateFrames = snapshot.skipDuplicateFrames,
            textureFiltering = snapshot.textureFiltering,
            trilinearFiltering = snapshot.trilinearFiltering,
            blendingAccuracy = snapshot.blendingAccuracy,
            texturePreloading = snapshot.texturePreloading,
            enableFxaa = snapshot.enableFxaa,
            sgsrMode = snapshot.sgsrMode,
            casMode = snapshot.casMode,
            casSharpness = snapshot.casSharpness,
            tvShader = snapshot.tvShader,
            shadeBoostEnabled = snapshot.shadeBoostEnabled,
            shadeBoostBrightness = snapshot.shadeBoostBrightness,
            shadeBoostContrast = snapshot.shadeBoostContrast,
            shadeBoostSaturation = snapshot.shadeBoostSaturation,
            shadeBoostGamma = snapshot.shadeBoostGamma,
            enableWidescreenPatches = snapshot.enableWidescreenPatches,
            enableNoInterlacingPatches = snapshot.enableNoInterlacingPatches,
            patchDatabaseUseOfficial = snapshot.patchDatabaseUseOfficial,
            patchDatabaseCustomUrl = snapshot.patchDatabaseCustomUrl,
            deinterlaceMode = snapshot.deinterlaceMode,
            dithering = snapshot.dithering,
            anisotropicFiltering = snapshot.anisotropicFiltering,
            enableHwMipmapping = snapshot.enableHwMipmapping,
            antiBlur = snapshot.antiBlur,
            cpuSpriteRenderSize = snapshot.cpuSpriteRenderSize,
            cpuSpriteRenderLevel = snapshot.cpuSpriteRenderLevel,
            softwareClutRender = snapshot.softwareClutRender,
            gpuTargetClutMode = snapshot.gpuTargetClutMode,
            skipDrawStart = snapshot.skipDrawStart,
            skipDrawEnd = snapshot.skipDrawEnd,
            autoFlushHardware = snapshot.autoFlushHardware,
            cpuFramebufferConversion = snapshot.cpuFramebufferConversion,
            disableDepthConversion = snapshot.disableDepthConversion,
            disableSafeFeatures = snapshot.disableSafeFeatures,
            disableRenderFixes = snapshot.disableRenderFixes,
            preloadFrameData = snapshot.preloadFrameData,
            disablePartialInvalidation = snapshot.disablePartialInvalidation,
            textureInsideRt = snapshot.textureInsideRt,
            readTargetsOnClose = snapshot.readTargetsOnClose,
            estimateTextureRegion = snapshot.estimateTextureRegion,
            gpuPaletteConversion = snapshot.gpuPaletteConversion,
            halfPixelOffset = snapshot.halfPixelOffset,
            nativeScaling = snapshot.nativeScaling,
            roundSprite = snapshot.roundSprite,
            bilinearUpscale = snapshot.bilinearUpscale,
            textureOffsetX = snapshot.textureOffsetX,
            textureOffsetY = snapshot.textureOffsetY,
            alignSprite = snapshot.alignSprite,
            mergeSprite = snapshot.mergeSprite,
            forceEvenSpritePosition = snapshot.forceEvenSpritePosition,
            nativePaletteDraw = snapshot.nativePaletteDraw,
            performancePreset = snapshot.performancePreset,
            overlayScale = snapshot.overlayScale,
            overlayOpacity = snapshot.overlayOpacity,
            overlayShow = snapshot.overlayShow,
            racingMode = snapshot.racingMode,
            touchscreenRightStick = snapshot.touchscreenRightStick,
            touchscreenRightStickSensitivity = snapshot.touchscreenRightStickSensitivity,
            touchHaptics = snapshot.touchHaptics,
            touchHapticsPreset = snapshot.touchHapticsPreset,
            touchHapticsStrength = snapshot.touchHapticsStrength,
            gyroMode = snapshot.gyroMode,
            gyroSensitivity = snapshot.gyroSensitivity,
            gyroSmoothing = snapshot.gyroSmoothing,
            gyroInvertX = snapshot.gyroInvertX,
            gyroInvertY = snapshot.gyroInvertY,
            leftStickSensitivity = snapshot.leftStickSensitivity,
            rightStickSensitivity = snapshot.rightStickSensitivity,
            invertLeftStick = snapshot.invertLeftStick,
            invertRightStick = snapshot.invertRightStick,
            invertLeftStickHorizontal = snapshot.invertLeftStickHorizontal,
            invertRightStickHorizontal = snapshot.invertRightStickHorizontal,
            enableAutoGamepad = snapshot.enableAutoGamepad,
            hideOverlayOnGamepad = snapshot.hideOverlayOnGamepad,
            gamepadStickDeadzone = snapshot.gamepadStickDeadzone,
            gamepadLeftStickSensitivity = snapshot.gamepadLeftStickSensitivity,
            gamepadRightStickSensitivity = snapshot.gamepadRightStickSensitivity,
            gamepadRightStickUpToR2 = snapshot.gamepadRightStickUpToR2,
            gamepadRightStickDownToL2 = snapshot.gamepadRightStickDownToL2,
            gamepadButtonHaptics = snapshot.gamepadButtonHaptics,
            pressureModifierAmount = snapshot.pressureModifierAmount,
            gamepadBindings = snapshot.gamepadBindings,
            gamepadBindingsByPad = snapshot.gamepadBindingsByPad,
            gpuDriverType = snapshot.gpuDriverType,
            mediatekAngleOpenGl = snapshot.mediatekAngleOpenGl,
            customDriverPath = snapshot.customDriverPath,
            frameLimitEnabled = snapshot.frameLimitEnabled,
            vSyncEnabled = snapshot.vSyncEnabled,
            fastForwardSpeed = snapshot.fastForwardSpeed,
            targetFps = snapshot.targetFps,
            ntscFramerate = snapshot.ntscFramerate,
            palFramerate = snapshot.palFramerate,
            enableIcacheEmulation = snapshot.enableIcacheEmulation,
            enableDisableStalls = snapshot.enableDisableStalls,
            enablePreciseExceptions = snapshot.enablePreciseExceptions,
            enableTurboCd = snapshot.enableTurboCd,
            enableCddaAudio = snapshot.enableCddaAudio,
            enableXaDecoding = snapshot.enableXaDecoding,
            enableSpuReverb = snapshot.enableSpuReverb,
            enableSpuThread = snapshot.enableSpuThread,
            spuTempo = snapshot.spuTempo,
            neonEnhancement = snapshot.neonEnhancement,
            neonEnhancementSpeedHack = snapshot.neonEnhancementSpeedHack,
            neonEnhancementTexAdj = snapshot.neonEnhancementTexAdj,
            neonInterlace = snapshot.neonInterlace,
            gpuThreadRendering = snapshot.gpuThreadRendering,
            showOverscan = snapshot.showOverscan,
            screenCentering = snapshot.screenCentering,
            screenCenteringX = snapshot.screenCenteringX,
            screenCenteringY = snapshot.screenCenteringY,
            screenCenteringHAdj = snapshot.screenCenteringHAdj,
            enableFractionalFramerate = snapshot.enableFractionalFramerate,
            altFlipMode = snapshot.altFlipMode,
            enableRgb32Output = snapshot.enableRgb32Output,
            enableScaleHires = snapshot.enableScaleHires,
            multitapMode = snapshot.multitapMode,
            analogAxisModifier = snapshot.analogAxisModifier,
            dualshockToggleCombo = snapshot.dualshockToggleCombo,
            cdReadAhead = snapshot.cdReadAhead
        )
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        preferences.setThemeMode(mode)
        if (mode == ThemeMode.NEON) {
            preferences.setTouchControlVisualStyle(TouchControlVisualStyle.MODERN)
        }
    }
    fun saveCustomTheme(config: CustomThemeConfig, activate: Boolean) = viewModelScope.launch {
        if (activate) preferences.applyCustomTheme(config) else preferences.setCustomTheme(config)
    }
    fun saveCustomThemeLibrary(library: CustomThemeLibrary, activate: Boolean) {
        val current = _uiState.value
        val safeLibrary = library.sanitized()
        val activeConfig = safeLibrary.activeTheme()?.config
        val nextThemeMode = when {
            activate && activeConfig != null -> ThemeMode.CUSTOM
            current.themeMode == ThemeMode.CUSTOM && activeConfig == null -> ThemeMode.SYSTEM
            else -> current.themeMode
        }
        _uiState.value = current.copy(
            themeMode = nextThemeMode,
            customTheme = activeConfig ?: CustomThemeConfig.Default,
            customThemeLibrary = safeLibrary
        )
        viewModelScope.launch {
            preferences.setCustomThemeLibrary(safeLibrary, activate)
        }
    }

    fun saveCustomTouchControls(library: CustomTouchControlLibrary) {
        val current = _uiState.value
        val safeLibrary = library.sanitized()
        _uiState.value = current.copy(customTouchControls = safeLibrary)
        viewModelScope.launch {
            preferences.setCustomTouchControls(safeLibrary)
        }
    }
    fun setAppFontChoice(choice: AppFontChoice) = viewModelScope.launch {
        if (choice == AppFontChoice.CUSTOM && customFontRepository.installedFile() == null) return@launch
        preferences.setAppFontChoice(choice)
    }

    fun installCustomFont(uri: Uri) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(customizationMessageResId = null)
        val result = customFontRepository.install(uri)
        result.getOrNull()?.let { installed ->
            preferences.setCustomFontInstalled(installed.displayName)
        }
        _uiState.value = _uiState.value.copy(
            customizationMessageResId = if (result.isSuccess) {
                com.sbro.emucorer.R.string.settings_customization_custom_font_applied
            } else {
                com.sbro.emucorer.R.string.settings_customization_custom_font_failed
            }
        )
    }

    fun clearCustomFont() = viewModelScope.launch(Dispatchers.IO) {
        customFontRepository.clear()
        preferences.clearCustomFont()
        _uiState.value = _uiState.value.copy(
            customizationMessageResId = com.sbro.emucorer.R.string.settings_customization_custom_font_removed
        )
    }

    fun setAppFontScale(scale: Float) = viewModelScope.launch {
        preferences.setAppFontScale(scale)
    }

    fun setHomeGridScale(scale: Float) = viewModelScope.launch {
        preferences.setHomeGridScale(scale)
    }

    fun setHomeBackgroundDim(dim: Int) = viewModelScope.launch {
        preferences.setHomeBackgroundDim(dim)
    }

    fun setEmulationSideArtworkDim(dim: Int) = viewModelScope.launch {
        preferences.setEmulationSideArtworkDim(dim)
    }

    fun setTouchControlVisualStyle(style: TouchControlVisualStyle) = viewModelScope.launch {
        preferences.setTouchControlVisualStyle(style)
    }

    fun setGameMenuLayoutStyle(style: GameMenuLayoutStyle) = viewModelScope.launch {
        preferences.setGameMenuLayoutStyle(style)
    }

    fun setDrawerVisualStyle(style: DrawerVisualStyle) = viewModelScope.launch {
        preferences.setDrawerVisualStyle(style)
    }

    fun setDrawerItemVisible(item: DrawerItemId, visible: Boolean) = viewModelScope.launch {
        if (item.required) return@launch
        val hidden = _uiState.value.hiddenDrawerItems.toMutableSet()
        if (visible) hidden.remove(item) else hidden.add(item)
        preferences.setHiddenDrawerItems(hidden)
    }

    fun setGameMenuTabVisible(tab: GameMenuTabId, visible: Boolean) = viewModelScope.launch {
        if (tab == GameMenuTabId.SESSION) return@launch
        val hidden = _uiState.value.hiddenGameMenuTabs.toMutableSet()
        if (visible) hidden.remove(tab) else hidden.add(tab)
        preferences.setHiddenGameMenuTabs(hidden)
    }

    fun moveGameMenuTab(tab: GameMenuTabId, direction: Int) = viewModelScope.launch {
        val order = _uiState.value.gameMenuTabOrder.toMutableList()
        val from = order.indexOf(tab)
        val to = (from + direction).coerceIn(0, order.lastIndex)
        if (from >= 0 && from != to) {
            order.removeAt(from)
            order.add(to, tab)
            preferences.setGameMenuTabOrder(order)
        }
    }

    fun setGameMenuSectionVisible(section: GameMenuSectionId, visible: Boolean) = viewModelScope.launch {
        val hidden = _uiState.value.hiddenGameMenuSections.toMutableSet()
        if (visible) hidden.remove(section) else hidden.add(section)
        preferences.setHiddenGameMenuSections(hidden)
    }

    fun moveGameMenuSection(section: GameMenuSectionId, direction: Int) = viewModelScope.launch {
        val order = _uiState.value.gameMenuSectionOrder.toMutableList()
        val sameTab = order.filter { it.tab == section.tab }
        val fromWithinTab = sameTab.indexOf(section)
        val toWithinTab = (fromWithinTab + direction).coerceIn(0, sameTab.lastIndex)
        if (fromWithinTab < 0 || fromWithinTab == toWithinTab) return@launch

        val reorderedTab = sameTab.toMutableList().apply {
            removeAt(fromWithinTab)
            add(toWithinTab, section)
        }
        var nextIndex = 0
        val normalized = order.map { current ->
            if (current.tab == section.tab) reorderedTab[nextIndex++] else current
        }
        preferences.setGameMenuSectionOrder(normalized)
    }

    fun resetGameMenuCustomization() = viewModelScope.launch {
        preferences.setGameMenuLayoutStyle(GameMenuLayoutStyle.SIDEBAR)
        preferences.setGameMenuTabOrder(DefaultGameMenuTabOrder)
        preferences.setHiddenGameMenuTabs(emptySet())
        preferences.setGameMenuSectionOrder(DefaultGameMenuSectionOrder)
        preferences.setHiddenGameMenuSections(emptySet())
    }

    fun setTouchControlPressEffect(effect: TouchControlPressEffect) = viewModelScope.launch {
        preferences.setTouchControlPressEffect(effect)
    }

    fun installHomeBackground(uri: Uri) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(
            isBackgroundImporting = true,
            customizationMessageResId = null
        )
        val result = homeBackgroundRepository.install(uri)
        result.getOrNull()?.let { preferences.setHomeBackgroundType(it) }
        _uiState.value = _uiState.value.copy(
            isBackgroundImporting = false,
            customizationMessageResId = if (result.isSuccess) {
                com.sbro.emucorer.R.string.settings_customization_background_applied
            } else {
                com.sbro.emucorer.R.string.settings_customization_background_failed
            }
        )
    }

    fun setHomeBackgroundPreset(preset: HomeBackgroundPreset) = viewModelScope.launch {
        preferences.setHomeBackgroundPreset(preset)
    }

    fun clearHomeBackground() = viewModelScope.launch(Dispatchers.IO) {
        homeBackgroundRepository.clear()
        preferences.setHomeBackgroundType(HomeBackgroundType.NONE)
        _uiState.value = _uiState.value.copy(
            customizationMessageResId = com.sbro.emucorer.R.string.settings_customization_background_removed
        )
    }

    fun setEmulationSideArtwork(artwork: EmulationSideArtwork) = viewModelScope.launch {
        if (artwork != EmulationSideArtwork.CUSTOM || emulationSideArtworkRepository.existingCustomFile() != null) {
            preferences.setEmulationSideArtwork(artwork)
        }
    }

    fun installEmulationSideArtwork(uri: Uri) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(
            isSideArtworkImporting = true,
            customizationMessageResId = null
        )
        val result = emulationSideArtworkRepository.install(uri)
        if (result.isSuccess) {
            preferences.setEmulationSideArtwork(EmulationSideArtwork.CUSTOM)
        }
        _uiState.value = _uiState.value.copy(
            isSideArtworkImporting = false,
            customizationMessageResId = if (result.isSuccess) {
                com.sbro.emucorer.R.string.settings_customization_side_artwork_applied
            } else {
                com.sbro.emucorer.R.string.settings_customization_side_artwork_failed
            }
        )
    }

    fun clearCustomEmulationSideArtwork() = viewModelScope.launch(Dispatchers.IO) {
        emulationSideArtworkRepository.clear()
        preferences.setEmulationSideArtwork(EmulationSideArtwork.NONE)
        _uiState.value = _uiState.value.copy(
            customizationMessageResId = com.sbro.emucorer.R.string.settings_customization_side_artwork_removed
        )
    }

    fun refreshShaderPresets() = viewModelScope.launch(Dispatchers.IO) {
        val presets = retroArchShaderRepository.listPresets()
        _uiState.value = _uiState.value.copy(
            shaderPresets = presets,
            isShaderPackInstalled = retroArchShaderRepository.hasInstalledPack()
        )
    }

    fun setShaderChainEnabled(enabled: Boolean) = viewModelScope.launch {
        val preset = _uiState.value.shaderChainPreset
        preferences.setShaderChain(enabled, preset)
        EmulatorBridge.setSetting("EmuCore/GS", "ShaderChainEnabled", "bool", enabled.toString())
        EmulatorBridge.setSetting("EmuCore/GS", "ShaderChainPreset", "string", preset)
    }

    fun setShaderChainPreset(path: String) = viewModelScope.launch {
        val enabled = path.isNotBlank() && _uiState.value.shaderChainEnabled
        preferences.setShaderChain(enabled, path)
        EmulatorBridge.setSetting("EmuCore/GS", "ShaderChainEnabled", "bool", enabled.toString())
        EmulatorBridge.setSetting("EmuCore/GS", "ShaderChainPreset", "string", path)
    }

    fun downloadOfficialShaderPack() {
        if (_uiState.value.isShaderPackInstalled) return
        installShaderPack(
            initialProgress = ShaderPackInstallProgress(ShaderPackInstallStage.DOWNLOADING)
        ) { onProgress ->
            retroArchShaderRepository.downloadOfficialPack(onProgress)
        }
    }

    fun importShaderPack(uri: Uri) = installShaderPack(
        initialProgress = ShaderPackInstallProgress(ShaderPackInstallStage.INSTALLING)
    ) {
        retroArchShaderRepository.importArchive(uri)
    }

    private fun installShaderPack(
        initialProgress: ShaderPackInstallProgress,
        block: ((ShaderPackInstallProgress) -> Unit) -> Result<Int>
    ) = viewModelScope.launch(Dispatchers.IO) {
        if (_uiState.value.isShaderPackBusy) return@launch
        _uiState.value = _uiState.value.copy(
            isShaderPackBusy = true,
            shaderPackProgress = initialProgress,
            shaderPackMessageResId = null
        )
        val result = block { progress ->
            _uiState.value = _uiState.value.copy(shaderPackProgress = progress)
        }
        val presets = retroArchShaderRepository.listPresets()
        _uiState.value = _uiState.value.copy(
            isShaderPackBusy = false,
            shaderPackProgress = null,
            shaderPresets = presets,
            isShaderPackInstalled = retroArchShaderRepository.hasInstalledPack(),
            shaderPackMessageResId = if (result.isSuccess) {
                com.sbro.emucorer.R.string.settings_shader_pack_installed
            } else {
                com.sbro.emucorer.R.string.settings_shader_pack_failed
            }
        )
    }

    fun clearShaderPackMessage() {
        _uiState.value = _uiState.value.copy(shaderPackMessageResId = null)
    }

    fun refreshPatchDatabaseState() = viewModelScope.launch(Dispatchers.IO) {
        _uiState.value = _uiState.value.copy(
            installedPatchCount = patchDatabaseDownloader.installedPatchCount()
        )
    }

    fun downloadPatchDatabase() = viewModelScope.launch(Dispatchers.IO) {
        if (_uiState.value.isPatchDatabaseBusy) return@launch
        if (!patchDatabaseDownloader.hasConfiguredSource()) {
            _uiState.value = _uiState.value.copy(
                patchDatabaseMessageResId = com.sbro.emucorer.R.string.settings_patches_source_missing,
                patchDatabaseMessageCount = null
            )
            return@launch
        }
        _uiState.value = _uiState.value.copy(
            isPatchDatabaseBusy = true,
            patchDatabaseProgress = PatchDatabaseInstallProgress(PatchDatabaseInstallStage.DOWNLOADING),
            patchDatabaseMessageResId = null,
            patchDatabaseMessageCount = null
        )
        val result = patchDatabaseDownloader.download { progress ->
            _uiState.value = _uiState.value.copy(patchDatabaseProgress = progress)
        }
        val installedCount = patchDatabaseDownloader.installedPatchCount()
        _uiState.value = _uiState.value.copy(
            isPatchDatabaseBusy = false,
            patchDatabaseProgress = null,
            installedPatchCount = installedCount,
            patchDatabaseMessageResId = if (result.isSuccess) {
                com.sbro.emucorer.R.string.settings_patches_download_success
            } else {
                com.sbro.emucorer.R.string.settings_patches_download_failed
            },
            patchDatabaseMessageCount = installedCount.takeIf { result.isSuccess }
        )
        if (result.isSuccess) {
            preferences.bumpPatchDatabaseRevision()
            EmulatorBridge.reloadPatches()
        }
    }

    fun clearPatchDatabaseMessage() {
        _uiState.value = _uiState.value.copy(
            patchDatabaseMessageResId = null,
            patchDatabaseMessageCount = null
        )
    }

    fun setPatchDatabaseUseOfficial(enabled: Boolean) = viewModelScope.launch {
        preferences.setPatchDatabaseUseOfficial(enabled)
    }

    fun setPatchDatabaseCustomUrl(url: String?) = viewModelScope.launch {
        preferences.setPatchDatabaseCustomUrl(url)
    }

    fun resetCustomization() = viewModelScope.launch(Dispatchers.IO) {
        homeBackgroundRepository.clear()
        emulationSideArtworkRepository.clear()
        customFontRepository.clear()
        preferences.setHomeBackgroundType(HomeBackgroundType.NONE)
        preferences.setHomeBackgroundDim(AppPreferences.DEFAULT_HOME_BACKGROUND_DIM)
        preferences.setEmulationSideArtwork(EmulationSideArtwork.NONE)
        preferences.setEmulationSideArtworkDim(AppPreferences.DEFAULT_EMULATION_SIDE_ARTWORK_DIM)
        preferences.setHomeGridScale(AppPreferences.DEFAULT_HOME_GRID_SCALE)
        preferences.setAppFontChoice(AppFontChoice.SYSTEM)
        preferences.clearCustomFont()
        preferences.setAppFontScale(AppPreferences.DEFAULT_APP_FONT_SCALE)
        preferences.setTouchControlVisualStyle(TouchControlVisualStyle.CLASSIC)
        preferences.setTouchControlPressEffect(TouchControlPressEffect.GROW)
        preferences.setDrawerVisualStyle(DrawerVisualStyle.CLASSIC)
        preferences.setHiddenDrawerItems(emptySet())
        preferences.setCustomTheme(CustomThemeConfig.Default)
        _uiState.value = _uiState.value.copy(
            customizationMessageResId = com.sbro.emucorer.R.string.settings_customization_reset_done
        )
    }

    fun clearCustomizationMessage() {
        _uiState.value = _uiState.value.copy(customizationMessageResId = null)
    }
    fun setLanguage(tag: String?) { viewModelScope.launch { preferences.setLanguageTag(tag) } }

    fun setRenderer(value: Int) {
        viewModelScope.launch {
            if (!EmulatorBridge.setRenderer(value)) return@launch
            markPerformancePresetCustom()
            preferences.setRenderer(value)
        }
    }

    fun setPerformanceProfile(value: Int) {
        viewModelScope.launch {
            preferences.setPerformanceProfile(value)
        }
    }

    fun loadAppReleaseHistory(showErrors: Boolean = true, force: Boolean = false) {
        if (_uiState.value.appUpdate.historyLoading) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                appUpdate = _uiState.value.appUpdate.copy(
                    historyLoading = true,
                    historyErrorMessage = null
                )
            )
            runCatching {
                appUpdateRepository.loadReleaseHistory(force)
            }.onSuccess { releases ->
                _uiState.value = _uiState.value.copy(
                    appUpdate = _uiState.value.appUpdate.copy(
                        releaseHistory = releases,
                        historyLoading = false,
                        historyErrorMessage = null
                    )
                )
            }.onFailure { error ->
                val errorMsg = if (showErrors) {
                    if (error is com.sbro.emucorer.core.RateLimitException) {
                        val minutes = ((error.resetTimestampMs - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
                        getApplication<Application>().getString(com.sbro.emucorer.R.string.settings_updates_rate_limit_error, minutes)
                    } else {
                        error.message ?: "Could not load release history"
                    }
                } else null

                _uiState.value = _uiState.value.copy(
                    appUpdate = _uiState.value.appUpdate.copy(
                        historyLoading = false,
                        historyErrorMessage = errorMsg
                    )
                )
            }
        }
    }
    fun setUpscaleMultiplier(value: Float) {
        viewModelScope.launch {
            val normalizedValue = normalizeUpscale(value)
            markPerformancePresetCustom()
            preferences.setUpscaleMultiplier(normalizedValue)
            preferences.setNeonEnhancement(normalizedValue >= 1.5f)
            EmulatorBridge.setUpscaleMultiplier(normalizedValue)
        }
    }

    fun setAspectRatio(value: Int) {
        viewModelScope.launch {
            preferences.setAspectRatio(value)
            EmulatorBridge.setAspectRatio(value)
        }
    }

    fun setLocalMultiplayerMode(value: Int) {
        viewModelScope.launch {
            preferences.setLocalMultiplayerMode(value)
            EmulatorBridge.setLocalMultiplayerMode(value)
        }
    }

    fun setDisplayCrop(value: DisplayCrop) {
        viewModelScope.launch {
            val crop = value.sanitized()
            preferences.setDisplayCrop(crop)
            EmulatorBridge.setDisplayCrop(crop)
        }
    }

    fun setAutoProgressiveScan(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoProgressiveScan(enabled)
        }
    }

    fun setPadVibration(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setPadVibration(enabled)
            EmulatorBridge.setPadVibration(enabled)
        }
    }

    fun setPadVibrationStrength(value: Int) {
        viewModelScope.launch {
            preferences.setPadVibrationStrength(value)
        }
    }

    fun setPadVibrationFallback(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setPadVibrationFallback(enabled)
        }
    }

    fun testPadVibration(
        strengthPercent: Int = _uiState.value.padVibrationStrength,
        durationMs: Long = 260L
    ) {
        GamepadManager.ensureInitialized(getApplication())
        GamepadManager.testPadVibration(
            padIndex = 0,
            strengthPercent = strengthPercent,
            durationMs = durationMs
        )
    }

    fun setGamepadBinding(padIndex: Int, actionId: String, keyCode: Int) {
        viewModelScope.launch {
            preferences.setGamepadBinding(padIndex, actionId, keyCode)
        }
    }

    fun clearGamepadBinding(padIndex: Int, actionId: String) {
        viewModelScope.launch {
            preferences.clearGamepadBinding(padIndex, actionId)
        }
    }

    fun resetGamepadBindingsForPad(padIndex: Int) {
        viewModelScope.launch {
            preferences.resetGamepadBindingsForPad(padIndex)
        }
    }

    fun setGamepadDeviceAssignment(padIndex: Int, deviceKey: String?) {
        viewModelScope.launch { preferences.setGamepadDeviceAssignment(padIndex, deviceKey) }
    }

    fun setGamepadDeviceIgnored(deviceKey: String, ignored: Boolean) {
        viewModelScope.launch { preferences.setGamepadDeviceIgnored(deviceKey, ignored) }
    }

    fun resetGamepadDeviceAssignments() {
        viewModelScope.launch { preferences.resetGamepadDeviceAssignments() }
    }

    fun resetAllSettings() {
        viewModelScope.launch {
            preferences.resetAllSettings()
        }
    }

    fun setEnableCheats(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setEnableCheats(enabled)
            EmulatorBridge.setSetting("EmuCore", "EnableCheats", "bool", enabled.toString())
            if (enabled) {
                EmulatorBridge.reloadPatches()
            }
        }
    }

    fun setShowFps(enabled: Boolean) { viewModelScope.launch { preferences.setShowFps(enabled) } }
    fun setAudioVolume(value: Int) {
        viewModelScope.launch {
            val normalized = AudioDefaults.coerceVolume(value)
            preferences.setAudioVolume(normalized)
            EmulatorBridge.setSetting("SPU2/Output", "StandardVolume", "int", normalized.toString())
        }
    }

    fun setAudioFastForwardVolume(value: Int) {
        viewModelScope.launch {
            val normalized = AudioDefaults.coerceVolume(value)
            preferences.setAudioFastForwardVolume(normalized)
            EmulatorBridge.setSetting("SPU2/Output", "FastForwardVolume", "int", normalized.toString())
        }
    }

    fun setAudioMuted(muted: Boolean) {
        viewModelScope.launch {
            preferences.setAudioMuted(muted)
            EmulatorBridge.setSetting("SPU2/Output", "OutputMuted", "bool", muted.toString())
        }
    }

    fun setAudioInterpolation(value: Int) {
        viewModelScope.launch {
            val normalized = AudioDefaults.coerceInterpolation(value)
            preferences.setAudioInterpolation(normalized)
            EmulatorBridge.setSetting(
                "SPU2/Output",
                "InterpolationMode",
                "string",
                AudioDefaults.interpolationCoreName(
                    AudioDefaults.effectiveInterpolation(normalized, _uiState.value.audioLightweightSpu2)
                )
            )
        }
    }

    fun setAudioSyncMode(value: Int) {
        viewModelScope.launch {
            val normalized = AudioDefaults.coerceSyncMode(value)
            preferences.setAudioSyncMode(normalized)
            EmulatorBridge.setSetting(
                "SPU2/Output",
                "SyncMode",
                "string",
                AudioDefaults.syncModeCoreName(
                    AudioDefaults.effectiveSyncMode(normalized, _uiState.value.audioLightweightSpu2)
                )
            )
        }
    }

    fun setAudioLightweightSpu2(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAudioLightweightSpu2(enabled)
            val state = _uiState.value
            _uiState.value = state.copy(audioLightweightSpu2 = enabled)
            EmulatorBridge.setSetting("SPU2/Output", "LightweightMode", "bool", enabled.toString())
            EmulatorBridge.setSetting(
                "SPU2/Output",
                "InterpolationMode",
                "string",
                AudioDefaults.interpolationCoreName(
                    AudioDefaults.effectiveInterpolation(state.audioInterpolation, enabled)
                )
            )
            EmulatorBridge.setSetting(
                "SPU2/Output",
                "SyncMode",
                "string",
                AudioDefaults.syncModeCoreName(
                    AudioDefaults.effectiveSyncMode(state.audioSyncMode, enabled)
                )
            )
        }
    }

    fun setAudioBackend(value: Int) {
        viewModelScope.launch {
            val normalized = AudioDefaults.coerceBackend(value)
            preferences.setAudioBackend(normalized)
            _uiState.value = _uiState.value.copy(audioBackend = normalized)
            // A backend change is applied the next time the game starts.
        }
    }

    fun setAudioBufferMs(value: Int) {
        viewModelScope.launch {
            val normalized = AudioDefaults.coerceBufferMs(value)
            preferences.setAudioBufferMs(normalized)
            EmulatorBridge.setSetting("SPU2/Output", "BufferMS", "int", normalized.toString())
        }
    }

    fun setAudioOutputLatencyMs(value: Int) {
        viewModelScope.launch {
            val normalized = AudioDefaults.coerceOutputLatencyMs(value)
            preferences.setAudioOutputLatencyMs(normalized)
            EmulatorBridge.setSetting("SPU2/Output", "OutputLatencyMS", "int", normalized.toString())
        }
    }

    fun setAudioMinimalOutputLatency(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAudioMinimalOutputLatency(enabled)
            EmulatorBridge.setSetting("SPU2/Output", "OutputLatencyMinimal", "bool", enabled.toString())
        }
    }

    fun setFpsOverlayMode(mode: Int) { viewModelScope.launch { preferences.setFpsOverlayMode(mode) } }
    fun setFpsOverlayCorner(corner: Int) { viewModelScope.launch { preferences.setFpsOverlayCorner(corner) } }
    fun setFpsOverlayScale(scale: Int) { viewModelScope.launch { preferences.setFpsOverlayScale(scale) } }
    fun setFpsOverlayMetrics(metrics: Int) { viewModelScope.launch { preferences.setFpsOverlayMetrics(metrics) } }
    fun setConfirmSaveLoadActions(enabled: Boolean) { viewModelScope.launch { preferences.setConfirmSaveLoadActions(enabled) } }
    fun setFloatingQuickActionsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setFloatingQuickActionsEnabled(enabled) }
    }

    fun setOrientationLock(value: Int) {
        viewModelScope.launch { preferences.setOrientationLock(value) }
    }

    fun setEmulationAllowsBothOrientations(enabled: Boolean) {
        viewModelScope.launch { preferences.setEmulationAllowsBothOrientations(enabled) }
    }
    fun setBackButtonExitsGame(enabled: Boolean) { viewModelScope.launch { preferences.setBackButtonExitsGame(enabled) } }
    fun setKeepScreenOn(enabled: Boolean) { viewModelScope.launch { preferences.setKeepScreenOn(enabled) } }
    fun setTvInterfaceMode(mode: TvInterfaceMode) {
        viewModelScope.launch { preferences.setTvInterfaceMode(mode) }
    }
    fun setRacingMode(enabled: Boolean) { viewModelScope.launch { preferences.setRacingMode(enabled) } }
    fun setTouchHaptics(enabled: Boolean) { viewModelScope.launch { preferences.setTouchHaptics(enabled) } }
    fun setTouchscreenRightStick(enabled: Boolean) { viewModelScope.launch { preferences.setTouchscreenRightStick(enabled) } }
    fun setTouchscreenRightStickSensitivity(value: Int) {
        viewModelScope.launch { preferences.setTouchscreenRightStickSensitivity(value) }
    }
    fun setTouchHapticsPreset(value: Int) { viewModelScope.launch { preferences.setTouchHapticsPreset(value) } }
    fun setTouchHapticsStrength(value: Int) { viewModelScope.launch { preferences.setTouchHapticsStrength(value) } }
    fun setGyroMode(value: Int) { viewModelScope.launch { preferences.setGyroMode(value) } }
    fun setGyroSensitivity(value: Int) { viewModelScope.launch { preferences.setGyroSensitivity(value) } }
    fun setGyroSmoothing(value: Int) { viewModelScope.launch { preferences.setGyroSmoothing(value) } }
    fun setGyroInvertX(value: Boolean) { viewModelScope.launch { preferences.setGyroInvertX(value) } }
    fun setGyroInvertY(value: Boolean) { viewModelScope.launch { preferences.setGyroInvertY(value) } }
    fun testTouchHaptics(
        strengthPercent: Int = _uiState.value.touchHapticsStrength,
        preset: Int = _uiState.value.touchHapticsPreset
    ) {
        viewModelScope.launch {
            AndroidTouchHaptics.playButton(
                context = getApplication(),
                strengthPercent = strengthPercent,
                preset = preset,
                phase = AndroidTouchHaptics.ButtonPhase.PRESS
            )
            delay(85.milliseconds)
            AndroidTouchHaptics.playButton(
                context = getApplication(),
                strengthPercent = strengthPercent,
                preset = preset,
                phase = AndroidTouchHaptics.ButtonPhase.RELEASE
            )
        }
    }
    fun setShowRecentGames(enabled: Boolean) { viewModelScope.launch { preferences.setShowRecentGames(enabled) } }
    fun setShowHomeSearch(enabled: Boolean) { viewModelScope.launch { preferences.setShowHomeSearch(enabled) } }
    fun setShowDebugOptions(enabled: Boolean) { viewModelScope.launch { preferences.setShowDebugOptions(enabled) } }
    fun setDebugLogcatGs(enabled: Boolean) { viewModelScope.launch { preferences.setDebugLogcatGs(enabled) } }
    fun setProfilerLogcat(enabled: Boolean) { viewModelScope.launch { preferences.setProfilerLogcat(enabled) } }
    fun setPreferEnglishGameTitles(enabled: Boolean) {
        viewModelScope.launch {
            EmulatorBridge.setSetting("UI", "PreferEnglishGameTitles", "bool", enabled.toString())
            preferences.setPreferEnglishGameTitles(enabled)
        }
    }

    // Extended settings
    fun setEeCycleRate(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEeCycleRate(value)
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "EECycleRate", "int", value.toString())
        }
    }

    fun setEeCycleSkip(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEeCycleSkip(value)
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "EECycleSkip", "int", value.toString())
        }
    }

    fun setEnableEeRecompiler(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableEeRecompiler(enabled)
            EmulatorBridge.setSetting("EmuCore/CPU/Recompiler", "EnableEE", "bool", enabled.toString())
        }
    }

    fun setEnableIopRecompiler(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableIopRecompiler(enabled)
            EmulatorBridge.setSetting("EmuCore/CPU/Recompiler", "EnableIOP", "bool", enabled.toString())
        }
    }

    fun setEnableVu0Recompiler(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableVu0Recompiler(enabled)
            EmulatorBridge.setSetting("EmuCore/CPU/Recompiler", "EnableVU0", "bool", enabled.toString())
        }
    }

    fun setEnableVu1Recompiler(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableVu1Recompiler(enabled)
            EmulatorBridge.setSetting("EmuCore/CPU/Recompiler", "EnableVU1", "bool", enabled.toString())
            if (!enabled) {
                preferences.setEnableMtvu(false)
                EmulatorBridge.setSetting("EmuCore/Speedhacks", "vuThread", "bool", "false")
            }
        }
    }

    fun setEnableFastmem(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableFastmem(enabled)
            EmulatorBridge.setSetting("EmuCore/CPU/Recompiler", "EnableFastmem", "bool", enabled.toString())
        }
    }

    fun setEeFpuRoundMode(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(AppPreferences.FLOAT_ROUND_NEAREST, AppPreferences.FLOAT_ROUND_CHOP)
            markPerformancePresetCustom()
            preferences.setEeFpuRoundMode(normalized)
            EmulatorBridge.setSetting("EmuCore/CPU", "FPU.Roundmode", "int", normalized.toString())
        }
    }

    fun setVu0RoundMode(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(AppPreferences.FLOAT_ROUND_NEAREST, AppPreferences.FLOAT_ROUND_CHOP)
            markPerformancePresetCustom()
            preferences.setVu0RoundMode(normalized)
            EmulatorBridge.setSetting("EmuCore/CPU", "VU0.Roundmode", "int", normalized.toString())
        }
    }

    fun setVu1RoundMode(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(AppPreferences.FLOAT_ROUND_NEAREST, AppPreferences.FLOAT_ROUND_CHOP)
            markPerformancePresetCustom()
            preferences.setVu1RoundMode(normalized)
            EmulatorBridge.setSetting("EmuCore/CPU", "VU1.Roundmode", "int", normalized.toString())
        }
    }

    fun setEeFpuClampingMode(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(AppPreferences.CLAMPING_NONE, AppPreferences.CLAMPING_FULL)
            markPerformancePresetCustom()
            preferences.setEeFpuClampingMode(normalized)
            setEeFpuClampingModeCore(normalized)
        }
    }

    fun setVu0ClampingMode(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(AppPreferences.CLAMPING_NONE, AppPreferences.CLAMPING_FULL)
            markPerformancePresetCustom()
            preferences.setVu0ClampingMode(normalized)
            setVuClampingModeCore("VU0ClampMode", "vu0", normalized)
        }
    }

    fun setVu1ClampingMode(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(AppPreferences.CLAMPING_NONE, AppPreferences.CLAMPING_FULL)
            markPerformancePresetCustom()
            preferences.setVu1ClampingMode(normalized)
            setVuClampingModeCore("VU1ClampMode", "vu1", normalized)
        }
    }

    fun setEnableGameFixes(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableGameFixes(enabled)
            EmulatorBridge.setSetting("EmuCore", "EnableGameFixes", "bool", enabled.toString())
        }
    }

    fun setEnableEeTimingHack(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableEeTimingHack(enabled)
            EmulatorBridge.setSetting("EmuCore/Gamefixes", "EETimingHack", "bool", enabled.toString())
        }
    }

    fun setEnableWaitLoopSpeedhack(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableWaitLoopSpeedhack(enabled)
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "WaitLoop", "bool", enabled.toString())
        }
    }

    private suspend fun setEeFpuClampingModeCore(value: Int) {
        EmulatorBridge.setSetting("EmuCoreR/CPU", "EEClampMode", "int", value.toString())
        EmulatorBridge.setSetting("EmuCore/CPU/Recompiler", "fpuOverflow", "bool", (value >= AppPreferences.CLAMPING_NORMAL).toString())
        EmulatorBridge.setSetting("EmuCore/CPU/Recompiler", "fpuExtraOverflow", "bool", (value >= AppPreferences.CLAMPING_EXTRA).toString())
        EmulatorBridge.setSetting("EmuCore/CPU/Recompiler", "fpuFullMode", "bool", (value >= AppPreferences.CLAMPING_FULL).toString())
    }

    private suspend fun setVuClampingModeCore(modeKey: String, prefix: String, value: Int) {
        EmulatorBridge.setSetting("EmuCoreR/CPU", modeKey, "int", value.toString())
        EmulatorBridge.setSetting("EmuCore/CPU/Recompiler", "${prefix}Overflow", "bool", (value >= AppPreferences.CLAMPING_NORMAL).toString())
        EmulatorBridge.setSetting("EmuCore/CPU/Recompiler", "${prefix}ExtraOverflow", "bool", (value >= AppPreferences.CLAMPING_EXTRA).toString())
        EmulatorBridge.setSetting("EmuCore/CPU/Recompiler", "${prefix}SignOverflow", "bool", (value >= AppPreferences.CLAMPING_FULL).toString())
    }

    fun setEnableIntcStatSpeedhack(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableIntcStatSpeedhack(enabled)
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "IntcStat", "bool", enabled.toString())
        }
    }

    fun setEnableVuFlagHack(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableVuFlagHack(enabled)
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "vuFlagHack", "bool", enabled.toString())
        }
    }

    fun setEnableInstantVu1(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableInstantVu1(enabled)
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "vu1Instant", "bool", enabled.toString())
        }
    }

    fun setEnableMtvu(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            val effectiveEnabled = enabled && _uiState.value.enableVu1Recompiler
            preferences.setEnableMtvu(effectiveEnabled)
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "vuThread", "bool", effectiveEnabled.toString())
        }
    }

    fun setEnableThreadPinning(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableThreadPinning(enabled)
            EmulatorBridge.setSetting("EmuCore", "EnableThreadPinning", "bool", enabled.toString())
        }
    }

    fun setEnableFastBoot(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setEnableFastBoot(enabled)
            EmulatorBridge.setSetting("EmuCore", "EnableFastBoot", "bool", enabled.toString())
        }
    }

    fun setEnableFastCdvd(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableFastCdvd(enabled)
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "fastCDVD", "bool", enabled.toString())
        }
    }

    fun setHwDownloadMode(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setHwDownloadMode(value)
            EmulatorBridge.setSetting("EmuCore/GS", "HWDownloadMode", "int", value.toString())
        }
    }

    fun setFrameSkip(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setFrameSkip(value)
            EmulatorBridge.setFrameSkip(value)
        }
    }

    fun setSkipDuplicateFrames(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setSkipDuplicateFrames(enabled)
            EmulatorBridge.setSkipDuplicateFrames(enabled)
        }
    }

    fun setFrameLimitEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setFrameLimitEnabled(enabled)
            EmulatorBridge.setFrameLimitEnabled(enabled)
        }
    }

    fun setVSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setVSyncEnabled(enabled)
            EmulatorBridge.setVSyncEnabled(enabled)
        }
    }

    fun setFastForwardSpeed(value: Float) {
        viewModelScope.launch {
            preferences.setFastForwardSpeed(value)
            EmulatorBridge.setFastForwardSpeed(value)
        }
    }

    fun setTargetFps(value: Int) {
        viewModelScope.launch {
            preferences.setTargetFps(if (value <= 0) 0 else value)
            EmulatorBridge.setTargetFps(value, _uiState.value.ntscFramerate, _uiState.value.palFramerate)
        }
    }

    fun setNtscFramerate(value: Float) {
        viewModelScope.launch {
            preferences.setNtscFramerate(value)
            EmulatorBridge.setTargetFps(_uiState.value.targetFps, value, _uiState.value.palFramerate)
        }
    }

    fun setPalFramerate(value: Float) {
        viewModelScope.launch {
            preferences.setPalFramerate(value)
            EmulatorBridge.setTargetFps(_uiState.value.targetFps, _uiState.value.ntscFramerate, value)
        }
    }

    fun setTextureFiltering(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setTextureFiltering(value)
            EmulatorBridge.setSetting("EmuCore/GS", "filter", "int", value.toString())
        }
    }

    fun setTrilinearFiltering(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setTrilinearFiltering(value)
            EmulatorBridge.setSetting("EmuCore/GS", "TriFilter", "int", value.toString())
        }
    }

    fun setBlendingAccuracy(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setBlendingAccuracy(value)
            EmulatorBridge.setSetting("EmuCore/GS", "accurate_blending_unit", "int", value.toString())
        }
    }

    fun setMediatekAngleOpenGl(enabled: Boolean) {
        viewModelScope.launch {
            val effectiveEnabled = enabled &&
                GpuHardwareProfiles.isMediaTekHardware() &&
                EmulatorBridge.isBundledAngleAvailable()
            preferences.setMediatekAngleOpenGl(effectiveEnabled)
            EmulatorBridge.setSetting("EmuCore/GS", "AndroidUseAngleOpenGL", "bool", effectiveEnabled.toString())
        }
    }

    fun setTexturePreloading(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setTexturePreloading(value)
            EmulatorBridge.setSetting("EmuCore/GS", "texture_preloading", "int", value.toString())
        }
    }

    fun setEnableFxaa(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableFxaa(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "fxaa", "bool", enabled.toString())
        }
    }

    fun setSgsrMode(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            val clamped = value.coerceIn(0, 3)
            preferences.setSgsrMode(clamped)
            EmulatorBridge.setSetting("EmuCore/GS", "SGSRMode", "int", clamped.toString())
        }
    }

    fun setCasMode(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setCasMode(value)
            EmulatorBridge.setSetting("EmuCore/GS", "CASMode", "int", value.toString())
        }
    }

    fun setCasSharpness(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setCasSharpness(value)
            EmulatorBridge.setSetting("EmuCore/GS", "CASSharpness", "int", value.toString())
        }
    }

    fun setTvShader(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            val clamped = GsHackDefaults.coerceTvShader(value)
            preferences.setTvShader(clamped)
            EmulatorBridge.setSetting("EmuCore/GS", "TVShader", "int", clamped.toString())
        }
    }

    fun setShadeBoostBrightness(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(1, 100)
            markPerformancePresetCustom()
            val enabled = isShadeBoostActive(
                brightness = clamped,
                contrast = _uiState.value.shadeBoostContrast,
                saturation = _uiState.value.shadeBoostSaturation,
                gamma = _uiState.value.shadeBoostGamma
            )
            preferences.setShadeBoostEnabled(enabled)
            preferences.setShadeBoostBrightness(clamped)
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost", "bool", enabled.toString())
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost_Brightness", "int", clamped.toString())
        }
    }

    fun setShadeBoostContrast(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(1, 100)
            markPerformancePresetCustom()
            val enabled = isShadeBoostActive(
                brightness = _uiState.value.shadeBoostBrightness,
                contrast = clamped,
                saturation = _uiState.value.shadeBoostSaturation,
                gamma = _uiState.value.shadeBoostGamma
            )
            preferences.setShadeBoostEnabled(enabled)
            preferences.setShadeBoostContrast(clamped)
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost", "bool", enabled.toString())
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost_Contrast", "int", clamped.toString())
        }
    }

    fun setShadeBoostSaturation(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(1, 100)
            markPerformancePresetCustom()
            val enabled = isShadeBoostActive(
                brightness = _uiState.value.shadeBoostBrightness,
                contrast = _uiState.value.shadeBoostContrast,
                saturation = clamped,
                gamma = _uiState.value.shadeBoostGamma
            )
            preferences.setShadeBoostEnabled(enabled)
            preferences.setShadeBoostSaturation(clamped)
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost", "bool", enabled.toString())
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost_Saturation", "int", clamped.toString())
        }
    }

    fun setShadeBoostGamma(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(1, 100)
            markPerformancePresetCustom()
            val enabled = isShadeBoostActive(
                brightness = _uiState.value.shadeBoostBrightness,
                contrast = _uiState.value.shadeBoostContrast,
                saturation = _uiState.value.shadeBoostSaturation,
                gamma = clamped
            )
            preferences.setShadeBoostEnabled(enabled)
            preferences.setShadeBoostGamma(clamped)
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost", "bool", enabled.toString())
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost_Gamma", "int", clamped.toString())
        }
    }

    private fun isShadeBoostActive(
        brightness: Int,
        contrast: Int,
        saturation: Int,
        gamma: Int
    ): Boolean {
        return brightness != 50 || contrast != 50 || saturation != 50 || gamma != 50
    }

    fun setEnableWidescreenPatches(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableWidescreenPatches(enabled)
            EmulatorBridge.setSetting("EmuCore", "EnableWideScreenPatches", "bool", enabled.toString())
            NativeApp.reloadPatches()
        }
    }

    fun setEnableNoInterlacingPatches(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableNoInterlacingPatches(enabled)
            EmulatorBridge.setSetting("EmuCore", "EnableNoInterlacingPatches", "bool", enabled.toString())
            NativeApp.reloadPatches()
        }
    }

    fun setDeinterlaceMode(value: Int) {
        viewModelScope.launch {
            val normalized = GsHackDefaults.coerceDeinterlaceMode(value)
            preferences.setDeinterlaceMode(normalized)
            EmulatorBridge.setSetting("EmuCore/GS", "deinterlace_mode", "int", normalized.toString())
        }
    }

    fun setDithering(value: Int) {
        viewModelScope.launch {
            val normalized = GsHackDefaults.coerceDithering(value)
            preferences.setDithering(normalized)
            EmulatorBridge.setSetting("EmuCore/GS", "dithering_ps2", "int", normalized.toString())
        }
    }

    fun setAnisotropicFiltering(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setAnisotropicFiltering(value)
            EmulatorBridge.setSetting("EmuCore/GS", "MaxAnisotropy", "int", value.toString())
        }
    }

    fun setEnableHwMipmapping(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableHwMipmapping(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "hw_mipmap", "bool", enabled.toString())
        }
    }

    fun setAntiBlur(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setAntiBlur(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "pcrtc_antiblur", "bool", enabled.toString())
        }
    }

    fun setCpuSpriteRenderSize(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setCpuSpriteRenderSize(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_CPUSpriteRenderBW", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(cpuSpriteRenderSize = value))
        }
    }

    fun setCpuSpriteRenderLevel(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setCpuSpriteRenderLevel(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_CPUSpriteRenderLevel", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(cpuSpriteRenderLevel = value))
        }
    }

    fun setSoftwareClutRender(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setSoftwareClutRender(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_CPUCLUTRender", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(softwareClutRender = value))
        }
    }

    fun setGpuTargetClutMode(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setGpuTargetClutMode(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_GPUTargetCLUTMode", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(gpuTargetClutMode = value))
        }
    }

    fun setSkipDrawStart(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setSkipDrawStart(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_SkipDraw_Start", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(skipDrawStart = value))
        }
    }

    fun setSkipDrawEnd(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setSkipDrawEnd(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_SkipDraw_End", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(skipDrawEnd = value))
        }
    }

    fun setAutoFlushHardware(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setAutoFlushHardware(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_AutoFlushLevel", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(autoFlushHardware = value))
        }
    }

    fun setCpuFramebufferConversion(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setCpuFramebufferConversion(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_CPU_FB_Conversion", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(cpuFramebufferConversion = enabled))
        }
    }

    fun setDisableDepthConversion(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setDisableDepthConversion(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_DisableDepthSupport", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(disableDepthConversion = enabled))
        }
    }

    fun setDisableSafeFeatures(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setDisableSafeFeatures(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_Disable_Safe_Features", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(disableSafeFeatures = enabled))
        }
    }

    fun setDisableRenderFixes(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setDisableRenderFixes(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_DisableRenderFixes", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(disableRenderFixes = enabled))
        }
    }

    fun setPreloadFrameData(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setPreloadFrameData(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "preload_frame_with_gs_data", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(preloadFrameData = enabled))
        }
    }

    fun setDisablePartialInvalidation(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setDisablePartialInvalidation(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_DisablePartialInvalidation", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(disablePartialInvalidation = enabled))
        }
    }

    fun setTextureInsideRt(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setTextureInsideRt(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_TextureInsideRt", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(textureInsideRt = value))
        }
    }

    fun setReadTargetsOnClose(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setReadTargetsOnClose(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_ReadTCOnClose", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(readTargetsOnClose = enabled))
        }
    }

    fun setEstimateTextureRegion(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEstimateTextureRegion(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_EstimateTextureRegion", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(estimateTextureRegion = enabled))
        }
    }

    fun setGpuPaletteConversion(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setGpuPaletteConversion(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "paltex", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(gpuPaletteConversion = enabled))
        }
    }

    fun setHalfPixelOffset(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setHalfPixelOffset(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_HalfPixelOffset", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(halfPixelOffset = value))
        }
    }

    fun setNativeScaling(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setNativeScaling(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_native_scaling", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(nativeScaling = value))
        }
    }

    fun setRoundSprite(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setRoundSprite(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_round_sprite_offset", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(roundSprite = value))
        }
    }

    fun setBilinearUpscale(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setBilinearUpscale(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_BilinearHack", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(bilinearUpscale = value))
        }
    }

    fun setTextureOffsetX(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setTextureOffsetX(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_TCOffsetX", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(textureOffsetX = value))
        }
    }

    fun setTextureOffsetY(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setTextureOffsetY(value)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_TCOffsetY", "int", value.toString())
            refreshManualHardwareFixes(_uiState.value.copy(textureOffsetY = value))
        }
    }

    fun setAlignSprite(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setAlignSprite(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_align_sprite_X", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(alignSprite = enabled))
        }
    }

    fun setMergeSprite(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setMergeSprite(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_merge_pp_sprite", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(mergeSprite = enabled))
        }
    }

    fun setForceEvenSpritePosition(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setForceEvenSpritePosition(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_ForceEvenSpritePosition", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(forceEvenSpritePosition = enabled))
        }
    }

    fun setNativePaletteDraw(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setNativePaletteDraw(enabled)
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_NativePaletteDraw", "bool", enabled.toString())
            refreshManualHardwareFixes(_uiState.value.copy(nativePaletteDraw = enabled))
        }
    }

    // Core-specific emulation settings

    fun setEnableIcacheEmulation(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableIcacheEmulation(enabled)
        }
    }

    fun setEnableDisableStalls(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableDisableStalls(enabled)
        }
    }

    fun setEnablePreciseExceptions(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnablePreciseExceptions(enabled)
        }
    }

    fun setEnableTurboCd(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableTurboCd(enabled)
        }
    }

    fun setEnableCddaAudio(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setEnableCddaAudio(enabled)
        }
    }

    fun setEnableXaDecoding(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setEnableXaDecoding(enabled)
        }
    }

    fun setEnableSpuReverb(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setEnableSpuReverb(enabled)
        }
    }

    fun setEnableSpuThread(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setEnableSpuThread(enabled)
        }
    }

    fun setSpuTempo(value: Int) {
        viewModelScope.launch {
            preferences.setSpuTempo(value)
        }
    }

    fun setNeonEnhancement(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setNeonEnhancement(enabled)
            // Keep the enhanced-resolution state in sync with the upscale
            // multiplier so the in-game "Native/2x" selector agrees.
            preferences.setUpscaleMultiplier(if (enabled) 2f else 1f)
        }
    }

    fun setNeonEnhancementSpeedHack(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setNeonEnhancementSpeedHack(enabled)
        }
    }

    fun setNeonEnhancementTexAdj(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setNeonEnhancementTexAdj(enabled)
        }
    }

    fun setNeonInterlace(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setNeonInterlace(value)
        }
    }

    fun setGpuThreadRendering(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setGpuThreadRendering(value)
        }
    }

    fun setShowOverscan(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setShowOverscan(enabled)
        }
    }

    fun setScreenCentering(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setScreenCentering(value)
        }
    }

    fun setScreenCenteringX(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setScreenCenteringX(value)
        }
    }

    fun setScreenCenteringY(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setScreenCenteringY(value)
        }
    }

    fun setScreenCenteringHAdj(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setScreenCenteringHAdj(value)
        }
    }

    fun setEnableFractionalFramerate(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableFractionalFramerate(enabled)
        }
    }

    fun setAltFlipMode(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setAltFlipMode(value)
        }
    }

    fun setEnableRgb32Output(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableRgb32Output(enabled)
        }
    }

    fun setEnableScaleHires(enabled: Boolean) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setEnableScaleHires(enabled)
        }
    }

    fun setMultitapMode(value: Int) {
        viewModelScope.launch {
            preferences.setMultitapMode(value)
        }
    }

    fun setAnalogAxisModifier(value: Int) {
        viewModelScope.launch {
            preferences.setAnalogAxisModifier(value)
        }
    }

    fun setDualshockToggleCombo(value: Int) {
        viewModelScope.launch {
            preferences.setDualshockToggleCombo(value)
        }
    }

    fun setCdReadAhead(value: Int) {
        viewModelScope.launch {
            markPerformancePresetCustom()
            preferences.setCdReadAhead(value)
        }
    }

    private suspend fun markPerformancePresetCustom() {
        if (_uiState.value.performancePreset != PerformancePresets.CUSTOM) {
            preferences.setPerformancePreset(PerformancePresets.CUSTOM)
        }
    }

    private suspend fun refreshManualHardwareFixes(state: SettingsUiState = _uiState.value) {
        val enabled = GsHackDefaults.shouldEnableManualHardwareFixes(
            cpuSpriteRenderSize = state.cpuSpriteRenderSize,
            cpuSpriteRenderLevel = state.cpuSpriteRenderLevel,
            softwareClutRender = state.softwareClutRender,
            gpuTargetClutMode = state.gpuTargetClutMode,
            skipDrawStart = state.skipDrawStart,
            skipDrawEnd = state.skipDrawEnd,
            autoFlushHardware = state.autoFlushHardware,
            cpuFramebufferConversion = state.cpuFramebufferConversion,
            disableDepthConversion = state.disableDepthConversion,
            disableSafeFeatures = state.disableSafeFeatures,
            disableRenderFixes = state.disableRenderFixes,
            preloadFrameData = state.preloadFrameData,
            disablePartialInvalidation = state.disablePartialInvalidation,
            textureInsideRt = state.textureInsideRt,
            readTargetsOnClose = state.readTargetsOnClose,
            estimateTextureRegion = state.estimateTextureRegion,
            gpuPaletteConversion = state.gpuPaletteConversion,
            halfPixelOffset = state.halfPixelOffset,
            nativeScaling = state.nativeScaling,
            roundSprite = state.roundSprite,
            bilinearUpscale = state.bilinearUpscale,
            textureOffsetX = state.textureOffsetX,
            textureOffsetY = state.textureOffsetY,
            alignSprite = state.alignSprite,
            mergeSprite = state.mergeSprite,
            forceEvenSpritePosition = state.forceEvenSpritePosition,
            nativePaletteDraw = state.nativePaletteDraw
        )
        EmulatorBridge.setSetting("EmuCore/GS", "UserHacks", "bool", enabled.toString())
    }

    // Overlay
    fun setOverlayScale(value: Int) { viewModelScope.launch { preferences.setOverlayScale(value) } }
    fun setOverlayOpacity(value: Int) { viewModelScope.launch { preferences.setOverlayOpacity(value) } }
    fun setLeftStickSensitivity(value: Int) { viewModelScope.launch { preferences.setLeftStickSensitivity(value) } }
    fun setRightStickSensitivity(value: Int) { viewModelScope.launch { preferences.setRightStickSensitivity(value) } }
    fun setInvertLeftStick(enabled: Boolean) { viewModelScope.launch { preferences.setInvertLeftStick(enabled) } }
    fun setInvertRightStick(enabled: Boolean) { viewModelScope.launch { preferences.setInvertRightStick(enabled) } }
    fun setInvertLeftStickHorizontal(enabled: Boolean) { viewModelScope.launch { preferences.setInvertLeftStickHorizontal(enabled) } }
    fun setInvertRightStickHorizontal(enabled: Boolean) { viewModelScope.launch { preferences.setInvertRightStickHorizontal(enabled) } }

    // Gamepad
    fun setEnableAutoGamepad(enabled: Boolean) { viewModelScope.launch { preferences.setEnableAutoGamepad(enabled) } }
    fun setHideOverlayOnGamepad(enabled: Boolean) { viewModelScope.launch { preferences.setHideOverlayOnGamepad(enabled) } }
    fun setGamepadStickDeadzone(value: Int) { viewModelScope.launch { preferences.setGamepadStickDeadzone(value) } }
    fun setGamepadLeftStickSensitivity(value: Int) { viewModelScope.launch { preferences.setGamepadLeftStickSensitivity(value) } }
    fun setGamepadRightStickSensitivity(value: Int) { viewModelScope.launch { preferences.setGamepadRightStickSensitivity(value) } }
    fun setGamepadRightStickUpToR2(enabled: Boolean) { viewModelScope.launch { preferences.setGamepadRightStickUpToR2(enabled) } }
    fun setGamepadRightStickDownToL2(enabled: Boolean) { viewModelScope.launch { preferences.setGamepadRightStickDownToL2(enabled) } }
    fun setGamepadButtonHaptics(enabled: Boolean) { viewModelScope.launch { preferences.setGamepadButtonHaptics(enabled) } }
    fun setPressureModifierAmount(value: Int) { viewModelScope.launch { preferences.setPressureModifierAmount(value) } }

    fun setBiosPath(uri: Uri) {
        val application = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val previousPath = preferences.biosPath.first()
            StorageAccess.takePersistableReadPermission(application, uri)
            preferences.setBiosPath(uri.toString())
            if (previousPath != uri.toString()) {
                StorageAccess.releasePersistedPermission(application, previousPath)
            }
            EmulatorBridge.applyRuntimeConfig(
                biosPath = uri.toString(),
                emulatorDataPath = _uiState.value.emulatorDataPath,
                memoryCardSlot1 = preferences.memoryCardSlot1.first(),
                memoryCardSlot2 = preferences.memoryCardSlot2.first(),
                renderer = _uiState.value.renderer,
                upscaleMultiplier = _uiState.value.upscaleMultiplier,
                gpuDriverType = _uiState.value.gpuDriverType,
                customDriverPath = _uiState.value.customDriverPath,
                gpuHardwareProfile = GpuHardwareProfiles.detectHardwareProfile(),
                mediatekAngleOpenGl = _uiState.value.mediatekAngleOpenGl,
                aspectRatio = _uiState.value.aspectRatio,
                localMultiplayerMode = _uiState.value.localMultiplayerMode,
                audioVolume = _uiState.value.audioVolume,
                audioFastForwardVolume = _uiState.value.audioFastForwardVolume,
                audioMuted = _uiState.value.audioMuted,
                audioInterpolation = _uiState.value.audioInterpolation,
                audioSyncMode = _uiState.value.audioSyncMode,
                audioLightweightSpu2 = _uiState.value.audioLightweightSpu2,
                audioBackend = _uiState.value.audioBackend,
                audioBufferMs = _uiState.value.audioBufferMs,
                audioOutputLatencyMs = _uiState.value.audioOutputLatencyMs,
                audioMinimalOutputLatency = _uiState.value.audioMinimalOutputLatency,
                enableEeRecompiler = _uiState.value.enableEeRecompiler,
                enableIopRecompiler = _uiState.value.enableIopRecompiler,
                enableVu0Recompiler = _uiState.value.enableVu0Recompiler,
                enableVu1Recompiler = _uiState.value.enableVu1Recompiler,
                enableFastmem = _uiState.value.enableFastmem,
                eeFpuRoundMode = _uiState.value.eeFpuRoundMode,
                vu0RoundMode = _uiState.value.vu0RoundMode,
                vu1RoundMode = _uiState.value.vu1RoundMode,
                eeFpuClampingMode = _uiState.value.eeFpuClampingMode,
                vu0ClampingMode = _uiState.value.vu0ClampingMode,
                vu1ClampingMode = _uiState.value.vu1ClampingMode,
                enableGameFixes = _uiState.value.enableGameFixes,
                eeTimingHack = _uiState.value.enableEeTimingHack,
                waitLoopSpeedhack = _uiState.value.enableWaitLoopSpeedhack,
                intcStatSpeedhack = _uiState.value.enableIntcStatSpeedhack,
                vuFlagHack = _uiState.value.enableVuFlagHack,
                instantVu1 = _uiState.value.enableInstantVu1,
                mtvu = _uiState.value.enableMtvu,
                enableThreadPinning = _uiState.value.enableThreadPinning,
                enableFastBoot = _uiState.value.enableFastBoot,
                hwDownloadMode = _uiState.value.hwDownloadMode,
                frameLimitEnabled = _uiState.value.frameLimitEnabled,
                vSyncEnabled = _uiState.value.vSyncEnabled,
                targetFps = _uiState.value.targetFps,
                ntscFramerate = _uiState.value.ntscFramerate,
                palFramerate = _uiState.value.palFramerate,
                textureFiltering = _uiState.value.textureFiltering,
                trilinearFiltering = _uiState.value.trilinearFiltering,
                blendingAccuracy = _uiState.value.blendingAccuracy,
                texturePreloading = _uiState.value.texturePreloading,
                enableFxaa = _uiState.value.enableFxaa,
                sgsrMode = _uiState.value.sgsrMode,
                casMode = _uiState.value.casMode,
                casSharpness = _uiState.value.casSharpness,
                tvShader = _uiState.value.tvShader,
                deinterlaceMode = _uiState.value.deinterlaceMode,
                dithering = _uiState.value.dithering,
                anisotropicFiltering = _uiState.value.anisotropicFiltering,
                enableHwMipmapping = _uiState.value.enableHwMipmapping,
                antiBlur = _uiState.value.antiBlur,
                cpuSpriteRenderSize = _uiState.value.cpuSpriteRenderSize,
                cpuSpriteRenderLevel = _uiState.value.cpuSpriteRenderLevel,
                softwareClutRender = _uiState.value.softwareClutRender,
                gpuTargetClutMode = _uiState.value.gpuTargetClutMode,
                skipDrawStart = _uiState.value.skipDrawStart,
                skipDrawEnd = _uiState.value.skipDrawEnd,
                autoFlushHardware = _uiState.value.autoFlushHardware,
                cpuFramebufferConversion = _uiState.value.cpuFramebufferConversion,
                disableDepthConversion = _uiState.value.disableDepthConversion,
                disableSafeFeatures = _uiState.value.disableSafeFeatures,
                disableRenderFixes = _uiState.value.disableRenderFixes,
                preloadFrameData = _uiState.value.preloadFrameData,
                disablePartialInvalidation = _uiState.value.disablePartialInvalidation,
                textureInsideRt = _uiState.value.textureInsideRt,
                readTargetsOnClose = _uiState.value.readTargetsOnClose,
                estimateTextureRegion = _uiState.value.estimateTextureRegion,
                gpuPaletteConversion = _uiState.value.gpuPaletteConversion,
                halfPixelOffset = _uiState.value.halfPixelOffset,
                nativeScaling = _uiState.value.nativeScaling,
                roundSprite = _uiState.value.roundSprite,
                bilinearUpscale = _uiState.value.bilinearUpscale,
                textureOffsetX = _uiState.value.textureOffsetX,
                textureOffsetY = _uiState.value.textureOffsetY,
                alignSprite = _uiState.value.alignSprite,
                mergeSprite = _uiState.value.mergeSprite,
                forceEvenSpritePosition = _uiState.value.forceEvenSpritePosition,
                nativePaletteDraw = _uiState.value.nativePaletteDraw
            )
        }
    }

    fun setGamePath(uri: Uri) {
        val application = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            StorageAccess.takePersistableReadPermission(application, uri)
            val rawPath = uri.toString()
            preferences.addGamePath(rawPath)
        }
    }

    fun removeGamePath(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            preferences.removeGamePath(path)
            StorageAccess.releasePersistedPermission(getApplication(), path)
        }
    }

    fun setCustomEmulatorDataPath(path: String) {
        val application = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            if (!EmulatorStorage.prepareCustomDataRoot(path)) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(application,
                        com.sbro.emucorer.R.string.emulator_data_location_error,
                        android.widget.Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            preferences.setEmulatorDataPath(path)
            NativeApp.reloadDataRoot(path)
        }
    }

    fun setEmulatorDataLocation(location: EmulatorDataLocation) {
        val application = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val preparedRoot = EmulatorStorage.prepareStandardDataRoot(application, location)
            if (preparedRoot == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        application,
                        com.sbro.emucorer.R.string.emulator_data_location_error,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                refreshEmulatorDataLocations()
                return@launch
            }
            preferences.setEmulatorDataPath(preparedRoot.preferencePath)
            NativeApp.reloadDataRoot(preparedRoot.preferencePath ?: "")
        }
    }

    fun refreshEmulatorDataLocations() {
        viewModelScope.launch(Dispatchers.IO) {
            val sdCardDataPath = EmulatorStorage.sdCardRoot(getApplication())?.absolutePath
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(sdCardDataPath = sdCardDataPath)
            }
        }
    }

    fun setCoverDownloadBaseUrl(url: String?) {
        viewModelScope.launch {
            preferences.setCoverDownloadBaseUrl(url)
            CoverArtRepository(getApplication()).clearCache()
            clearCoverImageMemoryCache()
        }
    }

    fun setCoverArtStyle(style: Int) {
        viewModelScope.launch {
            preferences.setCoverArtStyle(style)
            CoverArtRepository(getApplication()).clearCache()
            clearCoverImageMemoryCache()
        }
    }

    fun clearCoverCache(onComplete: (CoverCacheClearResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = CoverArtRepository(getApplication()).clearAllTemporaryImageCaches()
            clearCoverImageMemoryCache()
            preferences.notifyCoverCacheCleared()
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    fun setCustomDriverPath(path: String?) {
        viewModelScope.launch {
            preferences.setCustomDriverPath(path)
            if (path != null) {
                preferences.setGpuDriverType(1)
                EmulatorBridge.setCustomDriverPath(path)
            } else {
                preferences.setGpuDriverType(0)
                EmulatorBridge.setCustomDriverPath("")
            }
        }
    }

    private companion object {
        const val CORE_NAME = "SwanStation"
        const val CORE_VERSION = "1.0.0"
    }
}
