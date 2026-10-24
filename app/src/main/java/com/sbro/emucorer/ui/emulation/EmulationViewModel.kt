
package com.sbro.emucorer.ui.emulation

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucorer.EmuCoreRApp
import com.sbro.emucorer.discord.DiscordIntegration
import com.sbro.emucorer.core.AndroidGamePerformance
import com.sbro.emucorer.core.AndroidGamePhase
import com.sbro.emucorer.core.AudioDefaults
import com.sbro.emucorer.core.BiosValidator
import com.sbro.emucorer.core.DocumentPathResolver
import com.sbro.emucorer.core.EmulatorBridge
import com.sbro.emucorer.core.RendererDefaults
import com.sbro.emucorer.core.SetupValidator
import com.sbro.emucorer.core.EmulatorStorage
import com.sbro.emucorer.core.GamepadManager
import com.sbro.emucorer.core.GpuHardwareProfiles
import com.sbro.emucorer.core.GsHackDefaults
import com.sbro.emucorer.core.MobileSocNameMapper
import com.sbro.emucorer.core.NativeApp
import com.sbro.emucorer.core.RuntimeFailure
import com.sbro.emucorer.core.SwanStationCoreOptions
import com.sbro.emucorer.core.PerformanceProfiles
import com.sbro.emucorer.core.PerformancePresets
import com.sbro.emucorer.core.resolveAndroidGamePhase
import com.sbro.emucorer.core.normalizeUpscale
import com.sbro.emucorer.data.AppPreferences
import com.sbro.emucorer.data.SettingsSnapshot
import com.sbro.emucorer.data.AppPreferences.Companion.FPS_OVERLAY_MODE_SIMPLE
import com.sbro.emucorer.data.AppPreferences.Companion.FPS_OVERLAY_MODE_DETAILED
import com.sbro.emucorer.data.CheatBlock
import com.sbro.emucorer.data.DisplayCrop
import com.sbro.emucorer.data.OverlayControlLayout
import com.sbro.emucorer.data.CheatRepository
import com.sbro.emucorer.data.GamePatchRepository
import com.sbro.emucorer.data.GameRepository
import com.sbro.emucorer.data.ps1.Ps1TitleIndexRepository
import com.sbro.emucorer.data.MemoryCardRepository
import com.sbro.emucorer.data.OverlayLayoutSnapshot
import com.sbro.emucorer.data.PerGameSettings
import com.sbro.emucorer.data.PerGameSettingsRepository
import com.sbro.emucorer.data.RetroAchievementsRepository
import com.sbro.emucorer.data.resolveShaderChain
import com.sbro.emucorer.data.TouchControlsLayoutProfile
import com.sbro.emucorer.data.PER_GAME_TOUCH_CONTROLS_LAYOUT_KEY
import com.sbro.emucorer.data.saveTouchControlsLayout
import com.sbro.emucorer.data.withTouchControlsLayout
import com.sbro.emucorer.data.withoutTouchControlsLayout
import com.sbro.emucorer.data.TouchControlVisualStyle
import com.sbro.emucorer.data.TouchControlPressEffect
import com.sbro.emucorer.data.CustomTouchControlLibrary
import com.sbro.emucorer.data.GameMenuLayoutStyle
import com.sbro.emucorer.data.GameMenuTabId
import com.sbro.emucorer.data.GameMenuSectionId
import com.sbro.emucorer.data.DefaultGameMenuTabOrder
import com.sbro.emucorer.data.DefaultGameMenuSectionOrder
import com.sbro.emucorer.data.PerformanceOverlayMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private val PER_GAME_GPU_DRIVER_KEYS = setOf(
    "gpuDriverType",
    "customDriverPath",
    "mediatekAngleOpenGl"
)

private val PER_GAME_AUDIO_KEYS = setOf(
    "audioVolume",
    "audioMuted",
    "audioOutputLatencyMs",
    "audioMinimalOutputLatency"
)

private fun buildPerformanceOverlayHeader(application: Application): String {
    val packageInfo = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0)
    }.getOrNull()
    val appVersion = packageInfo?.versionName?.takeIf(String::isNotBlank) ?: "?"
    val buildNumber = packageInfo?.longVersionCode?.toString() ?: "?"
    val coreName = runCatching { NativeApp.getCoreName().orEmpty() }
        .getOrDefault("")
        .ifBlank { "?" }
    val coreVersion = runCatching { NativeApp.getCoreVersion().orEmpty() }
        .getOrDefault("")
        .ifBlank { "?" }
    return "EmuCoreR-$appVersion | $buildNumber | $coreName $coreVersion"
}

internal fun replacePerformanceCpuName(text: String, cpuName: String): String {
    if (cpuName.isBlank() || cpuName == "Unknown") return text
    return text.lineSequence().joinToString("\n") { line ->
        if (!line.startsWith("CPU:")) return@joinToString line
        val valuesStart = line.indexOf(" | ")
        if (valuesStart < 0) "CPU:$cpuName" else "CPU:$cpuName${line.substring(valuesStart)}"
    }
}

internal fun replacePerformanceGpuName(text: String, gpuName: String): String {
    if (gpuName.isBlank() || gpuName == "Unknown") return text
    return text.lineSequence().joinToString("\n") { line ->
        if (!line.contains("GPU:")) return@joinToString line
        line.split(" | ").joinToString(" | ") { segment ->
            if (segment.startsWith("GPU:")) "GPU:$gpuName" else segment
        }
    }
}

data class EmulationUiState(
    val runtimeFailure: RuntimeFailure? = null,
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val isPaused: Boolean = false,
    val showMenu: Boolean = false,
    val isActionInProgress: Boolean = false,
    val actionLabel: String? = null,
    val controlsVisible: Boolean = true,
    val showFps: Boolean = true,
    val confirmSaveLoadActions: Boolean = true,
    val backButtonExitsGame: Boolean = false,
    val compactControls: Boolean = true,
    val keepScreenOn: Boolean = true,
    val fpsOverlayCorner: Int = AppPreferences.FPS_OVERLAY_CORNER_TOP_RIGHT,
    val fpsOverlayScale: Int = AppPreferences.DEFAULT_FPS_OVERLAY_SCALE,
    val fpsOverlayMetrics: Int = PerformanceOverlayMetrics.DEFAULT,
    val overlayScale: Int = 100,
    val overlayOpacity: Int = AppPreferences.DEFAULT_OVERLAY_OPACITY,
    val touchControlVisualStyle: TouchControlVisualStyle = TouchControlVisualStyle.CLASSIC,
    val touchControlPressEffect: TouchControlPressEffect = TouchControlPressEffect.GROW,
    val customTouchControls: CustomTouchControlLibrary = CustomTouchControlLibrary.Empty,
    val gameMenuLayoutStyle: GameMenuLayoutStyle = GameMenuLayoutStyle.SIDEBAR,
    val gameMenuTabOrder: List<GameMenuTabId> = DefaultGameMenuTabOrder,
    val hiddenGameMenuTabs: Set<GameMenuTabId> = emptySet(),
    val gameMenuSectionOrder: List<GameMenuSectionId> = DefaultGameMenuSectionOrder,
    val hiddenGameMenuSections: Set<GameMenuSectionId> = emptySet(),
    val hideOverlayOnGamepad: Boolean = true,
    val dpadOffset: Pair<Float, Float> = AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y,
    val lstickOffset: Pair<Float, Float> = AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
    val rstickOffset: Pair<Float, Float> = AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
    val actionOffset: Pair<Float, Float> = AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y,
    val lbtnOffset: Pair<Float, Float> = AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
    val rbtnOffset: Pair<Float, Float> = AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
    val centerOffset: Pair<Float, Float> = AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y,
    val stickScale: Int = 100,
    val leftStickSensitivity: Int = 100,
    val rightStickSensitivity: Int = 100,
    val invertLeftStick: Boolean = false,
    val invertRightStick: Boolean = false,
    val invertLeftStickHorizontal: Boolean = false,
    val invertRightStickHorizontal: Boolean = false,
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
    val gamepadStickDeadzone: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_DEADZONE,
    val gamepadLeftStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadRightStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadRightStickUpToR2: Boolean = false,
    val gamepadRightStickDownToL2: Boolean = false,
    val gamepadButtonHaptics: Boolean = false,
    val gamepadBindingsByPad: Map<Int, Map<String, Int>> = emptyMap(),
    val pressureModifierAmount: Int = AppPreferences.DEFAULT_PRESSURE_MODIFIER_AMOUNT,
    val stickSurfaceMode: Boolean = false,
    val controlLayouts: Map<String, OverlayControlLayout> = AppPreferences.defaultOverlayControlLayouts(),
    val fps: String = "0.0",
    val fpsOverlayMode: Int = FPS_OVERLAY_MODE_SIMPLE,
    val performanceOverlayText: String = "",
    val performanceOverlayHeader: String = "",
    val speedPercent: Float = 100f,
    val toastMessage: String? = null,
    val statusMessage: String? = null,
    val currentSlot: Int = 1,
    val renderer: Int = RendererDefaults.defaultForHardware(),
    val upscale: Float = 1f,
    val aspectRatio: Int = 1,
    val localMultiplayerMode: Int = AppPreferences.LOCAL_MULTIPLAYER_OFF,
    val displayCrop: DisplayCrop = DisplayCrop.None,
    val performancePreset: Int = PerformancePresets.CUSTOM,
    val enableInstantVu1: Boolean = true,
    val enableMtvu: Boolean = true,
    val enableThreadPinning: Boolean = AppPreferences.DEFAULT_THREAD_PINNING,
    val enableFastCdvd: Boolean = false,
    val enableFastBoot: Boolean = true,
    val enableCheats: Boolean = false,
    val hwDownloadMode: Int = GsHackDefaults.HW_DOWNLOAD_MODE_DEFAULT,
    val eeCycleRate: Int = PerformanceProfiles.safeConfig.eeCycleRate,
    val eeCycleSkip: Int = PerformanceProfiles.safeConfig.eeCycleSkip,
    val frameSkip: Int = 0,
    val skipDuplicateFrames: Boolean = true,
    val textureFiltering: Int = GsHackDefaults.BILINEAR_FILTERING_DEFAULT,
    val trilinearFiltering: Int = GsHackDefaults.TRILINEAR_FILTERING_DEFAULT,
    val blendingAccuracy: Int = GsHackDefaults.BLENDING_ACCURACY_DEFAULT,
    val texturePreloading: Int = GsHackDefaults.TEXTURE_PRELOADING_DEFAULT,
    val enableFxaa: Boolean = false,
    val casMode: Int = 0,
    val sgsrMode: Int = 0,
    val casSharpness: Int = 50,
    val tvShader: Int = GsHackDefaults.TV_SHADER_DEFAULT,
    val shadeBoostEnabled: Boolean = false,
    val shadeBoostBrightness: Int = 50,
    val shadeBoostContrast: Int = 50,
    val shadeBoostSaturation: Int = 50,
    val shadeBoostGamma: Int = 50,
    val anisotropicFiltering: Int = 0,
    val enableHwMipmapping: Boolean = GsHackDefaults.HW_MIPMAPPING_DEFAULT,
    val antiBlur: Boolean = GsHackDefaults.ANTI_BLUR_DEFAULT,
    val deinterlaceMode: Int = GsHackDefaults.DEINTERLACE_MODE_DEFAULT,
    val dithering: Int = GsHackDefaults.DITHERING_DEFAULT,
    val widescreenPatches: Boolean = false,
    val noInterlacingPatches: Boolean = false,
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
    val cheatsGameKey: String? = null,
    val availableCheats: List<CheatBlock> = emptyList(),
    val frameLimitEnabled: Boolean = true,
    val fastForwardSpeed: Float = AppPreferences.DEFAULT_FAST_FORWARD_SPEED,
    val targetFps: Int = 0,
    val ntscFramerate: Float = AppPreferences.DEFAULT_NTSC_FRAMERATE,
    val palFramerate: Float = AppPreferences.DEFAULT_PAL_FRAMERATE,
    val currentGameTitle: String = "",
    val currentGameSubtitle: String = "",
    val currentGameCoverPath: String? = null,
    val gameSettingsProfileActive: Boolean = false,
    val perGameCoreOptions: Map<String, String> = emptyMap(),
    val currentSlotLastModified: Long = 0L,
    val autoSaveEnabled: Boolean = false,
    val autoSaveIntervalMinutes: Int = 1,
    val autoSaveOnExit: Boolean = false,
    val autoLoadOnStart: Boolean = false,
    val autoSaveLastModified: Long = 0L,
    val isAutoSaveInProgress: Boolean = false,
    val activePlayTimeMs: Long = 0L,
    val showDebugOptions: Boolean = false,
    val isJitProfilerActive: Boolean = false,
    val isHangTraceActive: Boolean = false,
    val enableIcacheEmulation: Boolean = false,
    val enableDisableStalls: Boolean = false,
    val enablePreciseExceptions: Boolean = false,
    val enableTurboCd: Boolean = false,
    val cdReadAhead: Int = 0,
    val enableCddaAudio: Boolean = true,
    val enableXaDecoding: Boolean = true,
    val enableSpuReverb: Boolean = true,
    val enableSpuThread: Boolean = false,
    val spuTempo: Int = 0,
    val audioVolume: Int = AudioDefaults.VOLUME_DEFAULT,
    val audioMuted: Boolean = false,
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
    val dualshockToggleCombo: Int = 0
)

private data class EmulationLaunchConfig(
    val performanceProfile: Int,
    val biosPath: String?,
    val emulatorDataPath: String?,
    val memoryCardSlot1: String?,
    val memoryCardSlot2: String?,
    val renderer: Int,
    val upscaleMultiplier: Float,
    val gpuDriverType: Int,
    val customDriverPath: String?,
    val gpuHardwareProfile: Int,
    val mediatekAngleOpenGl: Boolean,
    val aspectRatio: Int,
    val localMultiplayerMode: Int,
    val displayCrop: DisplayCrop,
    val audioVolume: Int,
    val audioFastForwardVolume: Int,
    val audioMuted: Boolean,
    val audioInterpolation: Int,
    val audioSyncMode: Int,
    val audioLightweightSpu2: Boolean,
    val audioBackend: Int,
    val audioBufferMs: Int,
    val audioOutputLatencyMs: Int,
    val audioMinimalOutputLatency: Boolean,
    val enableEeRecompiler: Boolean,
    val enableIopRecompiler: Boolean,
    val enableVu0Recompiler: Boolean,
    val enableVu1Recompiler: Boolean,
    val enableFastmem: Boolean,
    val eeFpuRoundMode: Int,
    val vu0RoundMode: Int,
    val vu1RoundMode: Int,
    val eeFpuClampingMode: Int,
    val vu0ClampingMode: Int,
    val vu1ClampingMode: Int,
    val enableGameFixes: Boolean,
    val eeTimingHack: Boolean,
    val waitLoopSpeedhack: Boolean,
    val intcStatSpeedhack: Boolean,
    val vuFlagHack: Boolean,
    val instantVu1: Boolean,
    val mtvu: Boolean,
    val enableThreadPinning: Boolean,
    val fastCdvd: Boolean,
    val enableFastBoot: Boolean,
    val enableCheats: Boolean,
    val hwDownloadMode: Int,
    val eeCycleRate: Int,
    val eeCycleSkip: Int,
    val frameSkip: Int,
    val skipDuplicateFrames: Boolean,
    val frameLimitEnabled: Boolean,
    val vSyncEnabled: Boolean,
    val fastForwardSpeed: Float,
    val targetFps: Int,
    val ntscFramerate: Float,
    val palFramerate: Float,
    val textureFiltering: Int,
    val trilinearFiltering: Int,
    val blendingAccuracy: Int,
    val texturePreloading: Int,
    val shaderChainEnabled: Boolean,
    val shaderChainPreset: String,
    val enableFxaa: Boolean,
    val casMode: Int,
    val sgsrMode: Int,
    val casSharpness: Int,
    val tvShader: Int,
    val shadeBoostEnabled: Boolean,
    val shadeBoostBrightness: Int,
    val shadeBoostContrast: Int,
    val shadeBoostSaturation: Int,
    val shadeBoostGamma: Int,
    val deinterlaceMode: Int,
    val dithering: Int,
    val anisotropicFiltering: Int,
    val enableHwMipmapping: Boolean,
    val antiBlur: Boolean,
    val widescreenPatches: Boolean,
    val noInterlacingPatches: Boolean,
    val cpuSpriteRenderSize: Int,
    val cpuSpriteRenderLevel: Int,
    val softwareClutRender: Int,
    val gpuTargetClutMode: Int,
    val skipDrawStart: Int,
    val skipDrawEnd: Int,
    val autoFlushHardware: Int,
    val cpuFramebufferConversion: Boolean,
    val disableDepthConversion: Boolean,
    val disableSafeFeatures: Boolean,
    val disableRenderFixes: Boolean,
    val preloadFrameData: Boolean,
    val disablePartialInvalidation: Boolean,
    val textureInsideRt: Int,
    val readTargetsOnClose: Boolean,
    val estimateTextureRegion: Boolean,
    val gpuPaletteConversion: Boolean,
    val halfPixelOffset: Int,
    val nativeScaling: Int,
    val roundSprite: Int,
    val bilinearUpscale: Int,
    val textureOffsetX: Int,
    val textureOffsetY: Int,
    val alignSprite: Boolean,
    val mergeSprite: Boolean,
    val forceEvenSpritePosition: Boolean,
    val nativePaletteDraw: Boolean,
    val pressureModifierAmount: Int,
    val fpuCorrectAddSub: Boolean,
    val enableIcacheEmulation: Boolean = false,
    val enableDisableStalls: Boolean = false,
    val enablePreciseExceptions: Boolean = false,
    val enableTurboCd: Boolean = false,
    val cdReadAhead: Int = 0,
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
    val dualshockToggleCombo: Int = 0
)

private data class LiveRuntimeSnapshot(
    val showFps: Boolean,
    val fpsOverlayMode: Int,
    val confirmSaveLoadActions: Boolean,
    val backButtonExitsGame: Boolean,
    val renderer: Int,
    val upscale: Float,
    val aspectRatio: Int,
    val localMultiplayerMode: Int,
    val displayCrop: DisplayCrop,
    val performancePreset: Int,
    val enableInstantVu1: Boolean,
    val enableMtvu: Boolean,
    val enableThreadPinning: Boolean,
    val enableFastCdvd: Boolean,
    val enableFastBoot: Boolean,
    val enableCheats: Boolean,
    val hwDownloadMode: Int,
    val eeCycleRate: Int,
    val eeCycleSkip: Int,
    val frameSkip: Int,
    val skipDuplicateFrames: Boolean,
    val frameLimitEnabled: Boolean,
    val fastForwardSpeed: Float,
    val racingMode: Boolean,
    val touchscreenRightStick: Boolean,
    val touchscreenRightStickSensitivity: Int,
    val touchHaptics: Boolean,
    val touchHapticsPreset: Int,
    val touchHapticsStrength: Int,
    val touchControlVisualStyle: TouchControlVisualStyle,
    val touchControlPressEffect: TouchControlPressEffect,
    val gyroMode: Int,
    val gyroSensitivity: Int,
    val gyroSmoothing: Int,
    val gyroInvertX: Boolean,
    val gyroInvertY: Boolean,
    val gamepadRightStickUpToR2: Boolean,
    val gamepadRightStickDownToL2: Boolean,
    val gamepadButtonHaptics: Boolean,
    val gamepadStickDeadzone: Int,
    val gamepadLeftStickSensitivity: Int,
    val gamepadRightStickSensitivity: Int,
    val gamepadBindingsByPad: Map<Int, Map<String, Int>>,
    val pressureModifierAmount: Int,
    val autoSaveOnExit: Boolean,
    val autoLoadOnStart: Boolean,
    val targetFps: Int,
    val ntscFramerate: Float,
    val palFramerate: Float,
    val textureFiltering: Int,
    val trilinearFiltering: Int,
    val blendingAccuracy: Int,
    val texturePreloading: Int,
    val enableFxaa: Boolean,
    val casMode: Int,
    val sgsrMode: Int,
    val casSharpness: Int,
    val tvShader: Int,
    val shadeBoostEnabled: Boolean,
    val shadeBoostBrightness: Int,
    val shadeBoostContrast: Int,
    val shadeBoostSaturation: Int,
    val shadeBoostGamma: Int,
    val anisotropicFiltering: Int,
    val enableHwMipmapping: Boolean,
    val antiBlur: Boolean,
    val deinterlaceMode: Int,
    val dithering: Int,
    val widescreenPatches: Boolean,
    val noInterlacingPatches: Boolean,
    val cpuSpriteRenderSize: Int,
    val cpuSpriteRenderLevel: Int,
    val softwareClutRender: Int,
    val gpuTargetClutMode: Int,
    val skipDrawStart: Int,
    val skipDrawEnd: Int,
    val autoFlushHardware: Int,
    val cpuFramebufferConversion: Boolean,
    val disableDepthConversion: Boolean,
    val disableSafeFeatures: Boolean,
    val disableRenderFixes: Boolean,
    val preloadFrameData: Boolean,
    val disablePartialInvalidation: Boolean,
    val textureInsideRt: Int,
    val readTargetsOnClose: Boolean,
    val estimateTextureRegion: Boolean,
    val gpuPaletteConversion: Boolean,
    val halfPixelOffset: Int,
    val nativeScaling: Int,
    val roundSprite: Int,
    val bilinearUpscale: Int,
    val textureOffsetX: Int,
    val textureOffsetY: Int,
    val alignSprite: Boolean,
    val mergeSprite: Boolean,
    val forceEvenSpritePosition: Boolean,
    val nativePaletteDraw: Boolean,
    val enableIcacheEmulation: Boolean = false,
    val enableDisableStalls: Boolean = false,
    val enablePreciseExceptions: Boolean = false,
    val enableTurboCd: Boolean = false,
    val cdReadAhead: Int = 0,
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
    val dualshockToggleCombo: Int = 0
)

class EmulationViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "EmulationViewModel"
        private const val AUTO_SAVE_SLOT = 0
        private val SAVE_STATE_FILE_REGEX = Regex("""^(.+?)\.(\d{2})\.rstate$""")
    }


    private val preferences = AppPreferences(application)
    private val cheatRepository = CheatRepository(application)
    private val gamePatchRepository = GamePatchRepository(application)
    private val memoryCardRepository = MemoryCardRepository(application, preferences)
    private val perGameSettingsRepository = PerGameSettingsRepository(application)
    private val gameRepository = GameRepository()
    private val performanceCpuName = MobileSocNameMapper.currentDeviceName()
    private val performanceGpuName = GpuHardwareProfiles.gpuDisplayName()
    private val androidGamePerformance = AndroidGamePerformance(application)
    private val _uiState = MutableStateFlow(
        EmulationUiState(performanceOverlayHeader = buildPerformanceOverlayHeader(application))
    )
    // Derive terminal presentation from authoritative runtime state. Deferred
    // startup/save-load coroutines cannot overwrite a fatal error with Running.
    val uiState: StateFlow<EmulationUiState> = combine(_uiState, EmulatorBridge.runtimeFailure) {
        state, failure -> state.withRuntimeFailure(failure)
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        _uiState.value.withRuntimeFailure(EmulatorBridge.runtimeFailure.value))
    private val lifecycleMutex = Mutex()
    private var pausedForBackground = false
    @Volatile
    private var isShuttingDown = false
    @Volatile
    private var cancelPendingStart = false
    @Volatile
    private var currentGameTitle: String = ""
    @Volatile
    private var currentGamePath: String? = null
    private var currentAnalyticsAudioBackend: Int = AudioDefaults.BACKEND_DEFAULT
    @Volatile
    private var currentGameSerial: String = ""
    @Volatile
    private var currentGameRegionLabel: String = ""
    @Volatile
    private var currentGameCoverArtPath: String? = null
    @Volatile
    private var currentGameCrc: String = ""
    @Volatile
    private var currentGameSource: String = ""
    /** Per-game core option overrides resolved at launch, applied after boot. */
    private var pendingPerGameCoreOptions: Map<String, String> = emptyMap()
    private var currentTouchControlsLayoutProfile: TouchControlsLayoutProfile? = null
    private var lastAutoSavePlayTimeMs: Long = 0L
    init {
        viewModelScope.launch {
            preferences.cleanupLegacyClampingPreferencesIfNeeded()
            preferences.migrateOverlayLayoutIfNeeded()
        }
        viewModelScope.launch {
            uiState
                .map { state ->
                    resolveAndroidGamePhase(
                        isStarting = state.isStarting,
                        isRunning = state.isRunning,
                        isPaused = state.isPaused,
                        showMenu = state.showMenu
                    )
                }
                .distinctUntilChanged()
                .collect(androidGamePerformance::update)
        }
    }

    private inline fun applyGlobalRuntimePreferenceUpdate(
        crossinline transform: (EmulationUiState) -> EmulationUiState
    ) {
        val current = _uiState.value
        // A running game session owns its per-game overrides: a late global
        // preference emission must not overwrite them (e.g. upscale 2x set in
        // the in-game menu being reset to the global 1x a moment later).
        if (current.gameSettingsProfileActive || activePerGameKey() != null) return
        _uiState.value = transform(current)
        syncNativePerformanceOverlayState(_uiState.value)
        syncGamepadRuntimeSettings(_uiState.value)
    }

    private fun syncNativePerformanceOverlayState(state: EmulationUiState) {
        val detailed = state.showFps && state.fpsOverlayMode != FPS_OVERLAY_MODE_SIMPLE
        NativeApp.setPerformanceMetricsEnabled(visible = state.showFps, detailed = detailed)
    }

    private fun syncGamepadRightStickTriggerMapping(state: EmulationUiState) {
        GamepadManager.setRightStickTriggerMapping(
            upToR2 = state.gamepadRightStickUpToR2,
            downToL2 = state.gamepadRightStickDownToL2
        )
    }

    private fun syncGamepadRuntimeSettings(state: EmulationUiState) {
        syncGamepadRightStickTriggerMapping(state)
        GamepadManager.setButtonHapticsEnabled(
            enabled = state.gamepadButtonHaptics,
            strengthPercent = state.touchHapticsStrength,
            preset = state.touchHapticsPreset
        )
        NativeApp.setPadPressureModifierAmount(state.pressureModifierAmount.coerceIn(1, 100))
    }

    private fun stopHiddenDebugTools(state: EmulationUiState) {
        if (!state.isJitProfilerActive && !state.isHangTraceActive) return
        viewModelScope.launch {
            if (state.isJitProfilerActive) {
                EmulatorBridge.stopJitProfiler()
            }
            if (state.isHangTraceActive) {
                EmulatorBridge.stopHangTrace()
            }
            _uiState.value = _uiState.value.copy(
                isJitProfilerActive = false,
                isHangTraceActive = false
            )
        }
    }

    init {
        viewModelScope.launch {
            preferences.overlayShow.collect { enabled ->
                _uiState.value = _uiState.value.copy(controlsVisible = enabled)
            }
        }
        viewModelScope.launch {
            preferences.showFps.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(showFps = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.overlayLayoutSnapshot.collect { snapshot ->
                applyOverlayLayoutSnapshot(snapshot)
            }
        }
        viewModelScope.launch {
            preferences.touchControlVisualStyle.collect { style ->
                val override = withContext(Dispatchers.IO) {
                    activePerGameKey()
                        ?.let(perGameSettingsRepository::get)
                        ?.touchControlVisualStyle
                }
                _uiState.value = _uiState.value.copy(touchControlVisualStyle = override ?: style)
            }
        }
        viewModelScope.launch {
            preferences.touchControlPressEffect.collect { effect ->
                val override = withContext(Dispatchers.IO) {
                    activePerGameKey()
                        ?.let(perGameSettingsRepository::get)
                        ?.touchControlPressEffect
                }
                _uiState.value = _uiState.value.copy(touchControlPressEffect = override ?: effect)
            }
        }
        viewModelScope.launch {
            preferences.customTouchControls.collect { library ->
                _uiState.value = _uiState.value.copy(customTouchControls = library)
            }
        }
        viewModelScope.launch {
            preferences.gameMenuLayoutStyle.collect { style ->
                _uiState.value = _uiState.value.copy(gameMenuLayoutStyle = style)
            }
        }
        viewModelScope.launch {
            preferences.gameMenuTabOrder.collect { order ->
                _uiState.value = _uiState.value.copy(gameMenuTabOrder = order)
            }
        }
        viewModelScope.launch {
            preferences.hiddenGameMenuTabs.collect { hidden ->
                _uiState.value = _uiState.value.copy(hiddenGameMenuTabs = hidden)
            }
        }
        viewModelScope.launch {
            preferences.gameMenuSectionOrder.collect { order ->
                _uiState.value = _uiState.value.copy(gameMenuSectionOrder = order)
            }
        }
        viewModelScope.launch {
            preferences.hiddenGameMenuSections.collect { hidden ->
                _uiState.value = _uiState.value.copy(hiddenGameMenuSections = hidden)
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayMode.collect { mode ->
                applyGlobalRuntimePreferenceUpdate { it.copy(fpsOverlayMode = FPS_OVERLAY_MODE_SIMPLE) }
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayCorner.collect { corner ->
                _uiState.value = _uiState.value.copy(fpsOverlayCorner = corner)
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayScale.collect { scale ->
                _uiState.value = _uiState.value.copy(fpsOverlayScale = scale)
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayMetrics.collect { metrics ->
                val updated = _uiState.value.copy(fpsOverlayMetrics = metrics)
                _uiState.value = updated
                syncNativePerformanceOverlayState(updated)
            }
        }
        viewModelScope.launch {
            preferences.showDebugOptions.collect { enabled ->
                val state = _uiState.value
                _uiState.value = state.copy(showDebugOptions = enabled)
                if (!enabled) {
                    stopHiddenDebugTools(state)
                }
            }
        }
        viewModelScope.launch {
            preferences.compactControls.collect { enabled ->
                _uiState.value = _uiState.value.copy(compactControls = enabled)
            }
        }
        viewModelScope.launch {
            preferences.gamepadStickDeadzone.collect { value ->
                _uiState.value = _uiState.value.copy(gamepadStickDeadzone = value)
            }
        }
        viewModelScope.launch {
            preferences.invertLeftStick.collect { enabled ->
                _uiState.value = _uiState.value.copy(invertLeftStick = enabled)
            }
        }
        viewModelScope.launch {
            preferences.invertRightStick.collect { enabled ->
                _uiState.value = _uiState.value.copy(invertRightStick = enabled)
            }
        }
        viewModelScope.launch {
            preferences.invertLeftStickHorizontal.collect { enabled ->
                _uiState.value = _uiState.value.copy(invertLeftStickHorizontal = enabled)
            }
        }
        viewModelScope.launch {
            preferences.invertRightStickHorizontal.collect { enabled ->
                _uiState.value = _uiState.value.copy(invertRightStickHorizontal = enabled)
            }
        }
        viewModelScope.launch {
            preferences.gamepadLeftStickSensitivity.collect { value ->
                _uiState.value = _uiState.value.copy(gamepadLeftStickSensitivity = value)
            }
        }
        viewModelScope.launch {
            preferences.gamepadRightStickSensitivity.collect { value ->
                _uiState.value = _uiState.value.copy(gamepadRightStickSensitivity = value)
            }
        }
        viewModelScope.launch {
            preferences.gamepadRightStickUpToR2.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(gamepadRightStickUpToR2 = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.gamepadRightStickDownToL2.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(gamepadRightStickDownToL2 = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.gamepadButtonHaptics.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(gamepadButtonHaptics = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.pressureModifierAmount.collect { amount ->
                applyGlobalRuntimePreferenceUpdate { it.copy(pressureModifierAmount = amount.coerceIn(1, 100)) }
            }
        }
        viewModelScope.launch {
            preferences.confirmSaveLoadActions.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(confirmSaveLoadActions = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.backButtonExitsGame.collect { enabled ->
                _uiState.value = _uiState.value.copy(backButtonExitsGame = enabled)
            }
        }
        viewModelScope.launch {
            preferences.keepScreenOn.collect { enabled ->
                _uiState.value = _uiState.value.copy(keepScreenOn = enabled)
            }
        }
        viewModelScope.launch {
            preferences.performancePreset.collect { preset ->
                applyGlobalRuntimePreferenceUpdate { it.copy(performancePreset = preset) }
            }
        }
        viewModelScope.launch {
            preferences.hwDownloadMode.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(hwDownloadMode = value) }
            }
        }
        viewModelScope.launch {
            preferences.eeCycleRate.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(eeCycleRate = value) }
            }
        }
        viewModelScope.launch {
            preferences.eeCycleSkip.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(eeCycleSkip = value) }
            }
        }
        viewModelScope.launch {
            preferences.renderer.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(renderer = value) }
            }
        }
        viewModelScope.launch {
            preferences.upscaleMultiplier.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(upscale = value) }
            }
        }
        viewModelScope.launch {
            preferences.aspectRatio.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(aspectRatio = value) }
            }
        }
        viewModelScope.launch {
            preferences.localMultiplayerMode.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(localMultiplayerMode = value) }
            }
        }
        viewModelScope.launch {
            preferences.displayCrop.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(displayCrop = value) }
            }
        }
        viewModelScope.launch {
            preferences.enableInstantVu1.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(enableInstantVu1 = value) }
            }
        }
        viewModelScope.launch {
            preferences.enableMtvu.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(enableMtvu = value) }
            }
        }
        viewModelScope.launch {
            preferences.enableThreadPinning.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(enableThreadPinning = value) }
            }
        }
        viewModelScope.launch {
            preferences.enableFastCdvd.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(enableFastCdvd = value) }
            }
        }
        viewModelScope.launch {
            preferences.enableFastBoot.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(enableFastBoot = value) }
            }
        }
        viewModelScope.launch {
            preferences.enableCheats.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(enableCheats = value) }
            }
        }
        viewModelScope.launch {
            preferences.frameSkip.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(frameSkip = value) }
            }
        }
        viewModelScope.launch {
            preferences.skipDuplicateFrames.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(skipDuplicateFrames = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.textureFiltering.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(textureFiltering = value) }
            }
        }
        viewModelScope.launch {
            preferences.trilinearFiltering.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(trilinearFiltering = value) }
            }
        }
        viewModelScope.launch {
            preferences.blendingAccuracy.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(blendingAccuracy = value) }
            }
        }
        viewModelScope.launch {
            preferences.texturePreloading.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(texturePreloading = value) }
            }
        }
        viewModelScope.launch {
            preferences.enableFxaa.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(enableFxaa = value) }
            }
        }
        viewModelScope.launch {
            preferences.casMode.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(casMode = value) }
            }
        }
        viewModelScope.launch {
            preferences.sgsrMode.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(sgsrMode = value) }
            }
        }
        viewModelScope.launch {
            preferences.casSharpness.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(casSharpness = value) }
            }
        }
        viewModelScope.launch {
            preferences.tvShader.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(tvShader = value) }
            }
        }
        viewModelScope.launch {
            preferences.shadeBoostEnabled.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(shadeBoostEnabled = value) }
            }
        }
        viewModelScope.launch {
            preferences.shadeBoostBrightness.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(shadeBoostBrightness = value) }
            }
        }
        viewModelScope.launch {
            preferences.shadeBoostContrast.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(shadeBoostContrast = value) }
            }
        }
        viewModelScope.launch {
            preferences.shadeBoostSaturation.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(shadeBoostSaturation = value) }
            }
        }
        viewModelScope.launch {
            preferences.shadeBoostGamma.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(shadeBoostGamma = value) }
            }
        }
        viewModelScope.launch {
            preferences.anisotropicFiltering.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(anisotropicFiltering = value) }
            }
        }
        viewModelScope.launch {
            preferences.enableHwMipmapping.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(enableHwMipmapping = value) }
            }
        }
        viewModelScope.launch {
            preferences.antiBlur.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(antiBlur = value) }
            }
        }
        viewModelScope.launch {
            preferences.enableWidescreenPatches.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(widescreenPatches = value) }
            }
        }
        viewModelScope.launch {
            preferences.enableNoInterlacingPatches.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(noInterlacingPatches = value) }
            }
        }
        viewModelScope.launch {
            preferences.patchDatabaseRevision
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    val state = _uiState.value
                    if (state.isRunning || state.isPaused) {
                        withContext(Dispatchers.IO) { syncCheatsForCurrentGame() }
                    }
                }
        }
        viewModelScope.launch {
            preferences.cpuSpriteRenderSize.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(cpuSpriteRenderSize = value) }
            }
        }
        viewModelScope.launch {
            preferences.cpuSpriteRenderLevel.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(cpuSpriteRenderLevel = value) }
            }
        }
        viewModelScope.launch {
            preferences.softwareClutRender.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(softwareClutRender = value) }
            }
        }
        viewModelScope.launch {
            preferences.gpuTargetClutMode.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(gpuTargetClutMode = value) }
            }
        }
        viewModelScope.launch {
            preferences.skipDrawStart.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(skipDrawStart = value) }
            }
        }
        viewModelScope.launch {
            preferences.skipDrawEnd.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(skipDrawEnd = value) }
            }
        }
        viewModelScope.launch {
            preferences.autoFlushHardware.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(autoFlushHardware = value) }
            }
        }
        viewModelScope.launch {
            preferences.cpuFramebufferConversion.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(cpuFramebufferConversion = value) }
            }
        }
        viewModelScope.launch {
            preferences.disableDepthConversion.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(disableDepthConversion = value) }
            }
        }
        viewModelScope.launch {
            preferences.disableSafeFeatures.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(disableSafeFeatures = value) }
            }
        }
        viewModelScope.launch {
            preferences.disableRenderFixes.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(disableRenderFixes = value) }
            }
        }
        viewModelScope.launch {
            preferences.preloadFrameData.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(preloadFrameData = value) }
            }
        }
        viewModelScope.launch {
            preferences.disablePartialInvalidation.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(disablePartialInvalidation = value) }
            }
        }
        viewModelScope.launch {
            preferences.textureInsideRt.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(textureInsideRt = value) }
            }
        }
        viewModelScope.launch {
            preferences.readTargetsOnClose.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(readTargetsOnClose = value) }
            }
        }
        viewModelScope.launch {
            preferences.estimateTextureRegion.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(estimateTextureRegion = value) }
            }
        }
        viewModelScope.launch {
            preferences.gpuPaletteConversion.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(gpuPaletteConversion = value) }
            }
        }
        viewModelScope.launch {
            preferences.halfPixelOffset.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(halfPixelOffset = value) }
            }
        }
        viewModelScope.launch {
            preferences.nativeScaling.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(nativeScaling = value) }
            }
        }
        viewModelScope.launch {
            preferences.roundSprite.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(roundSprite = value) }
            }
        }
        viewModelScope.launch {
            preferences.bilinearUpscale.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(bilinearUpscale = value) }
            }
        }
        viewModelScope.launch {
            preferences.textureOffsetX.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(textureOffsetX = value) }
            }
        }
        viewModelScope.launch {
            preferences.textureOffsetY.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(textureOffsetY = value) }
            }
        }
        viewModelScope.launch {
            preferences.alignSprite.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(alignSprite = value) }
            }
        }
        viewModelScope.launch {
            preferences.mergeSprite.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(mergeSprite = value) }
            }
        }
        viewModelScope.launch {
            preferences.forceEvenSpritePosition.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(forceEvenSpritePosition = value) }
            }
        }
        viewModelScope.launch {
            preferences.nativePaletteDraw.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(nativePaletteDraw = value) }
            }
        }
        viewModelScope.launch {
            preferences.frameLimitEnabled.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(frameLimitEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.targetFps.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(targetFps = value) }
            }
        }
        viewModelScope.launch {
            preferences.fastForwardSpeed.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(fastForwardSpeed = value) }
            }
        }
        viewModelScope.launch {
            preferences.racingMode.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(racingMode = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.touchscreenRightStick.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchscreenRightStick = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.touchscreenRightStickSensitivity.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchscreenRightStickSensitivity = value) }
            }
        }
        viewModelScope.launch {
            preferences.touchHaptics.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchHaptics = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.touchHapticsPreset.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchHapticsPreset = value) }
            }
        }
        viewModelScope.launch {
            preferences.touchHapticsStrength.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchHapticsStrength = value) }
            }
        }
        viewModelScope.launch { preferences.gyroMode.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroMode = value) } } }
        viewModelScope.launch { preferences.gyroSensitivity.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroSensitivity = value) } } }
        viewModelScope.launch { preferences.gyroSmoothing.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroSmoothing = value) } } }
        viewModelScope.launch { preferences.gyroInvertX.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroInvertX = value) } } }
        viewModelScope.launch { preferences.gyroInvertY.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroInvertY = value) } } }
        viewModelScope.launch {
            preferences.ntscFramerate.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(ntscFramerate = value) }
            }
        }
        viewModelScope.launch {
            preferences.palFramerate.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(palFramerate = value) }
            }
        }
        viewModelScope.launch {
            preferences.autoSaveEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(autoSaveEnabled = enabled)
                if (enabled) {
                    lastAutoSavePlayTimeMs = _uiState.value.activePlayTimeMs
                }
            }
        }
        viewModelScope.launch {
            preferences.autoSaveIntervalMinutes.collect { value ->
                _uiState.value = _uiState.value.copy(autoSaveIntervalMinutes = value.coerceIn(1, 999))
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(1_000.milliseconds)
                pollNativePerformanceMetrics()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000.milliseconds)
                tickActivePlayTimeAndAutoSave()
            }
        }
        syncNativePerformanceOverlayState(_uiState.value)
    }

    private fun tickActivePlayTimeAndAutoSave() {
        val state = _uiState.value
        if (!state.isRunning || state.isStarting || state.isPaused || state.showMenu || isShuttingDown ||
            EmulatorBridge.runtimeFailure.value != null) {
            return
        }

        val nextPlayTimeMs = state.activePlayTimeMs + 1_000L
        _uiState.value = state.copy(activePlayTimeMs = nextPlayTimeMs)

        val intervalMs = state.autoSaveIntervalMinutes.coerceIn(1, 999) * 60_000L
        if (!state.autoSaveEnabled ||
            state.isActionInProgress ||
            state.isAutoSaveInProgress ||
            nextPlayTimeMs - lastAutoSavePlayTimeMs < intervalMs
        ) {
            return
        }

        lastAutoSavePlayTimeMs = nextPlayTimeMs
        performAutoSave()
    }

    private fun performAutoSave() {
        viewModelScope.launch(Dispatchers.IO) {
            saveAutoSaveSlot(allowWhileMenu = false, allowPaused = false, showActionProgress = false)
        }
    }

    private suspend fun saveAutoSaveSlot(
        allowWhileMenu: Boolean,
        allowPaused: Boolean,
        showActionProgress: Boolean
    ): Boolean {
        val path = currentGamePath ?: return false
        val previousModified = saveStateLastModified(path, AUTO_SAVE_SLOT)
        val before = _uiState.value
        _uiState.value = before.copy(
            isAutoSaveInProgress = true,
            isActionInProgress = if (showActionProgress) true else before.isActionInProgress,
            actionLabel = if (showActionProgress) "saving" else before.actionLabel
        )
        val scheduled = lifecycleMutex.withLock {
            val state = _uiState.value
            if (isShuttingDown ||
                !state.isRunning ||
                (!allowPaused && state.isPaused) ||
                (!allowWhileMenu && state.showMenu)
            ) {
                false
            } else {
                try {
                    EmulatorBridge.saveState(AUTO_SAVE_SLOT)
                } catch (_: Exception) {
                    false
                }
            }
        }
        val success = scheduled && waitForSaveStateUpdate(path, AUTO_SAVE_SLOT, previousModified)
        val after = _uiState.value
        _uiState.value = after.copy(
            isAutoSaveInProgress = false,
            isActionInProgress = if (showActionProgress) false else after.isActionInProgress,
            actionLabel = if (showActionProgress) null else after.actionLabel
        )
        refreshSaveStateMetadata()
        return success
    }

    private fun pollNativePerformanceMetrics() {
        val state = _uiState.value
        if (!state.isRunning || isShuttingDown || state.isPaused || !state.showFps) return
        val raw = NativeApp.getPerformanceMetricsSnapshot().orEmpty()
        if (raw.isBlank()) return

        val parts = raw.split('\n', limit = 3)
        if (parts.size < 3) return
        val fps = parts[0].toFloatOrNull() ?: return
        val speedPercent = parts[1].toFloatOrNull() ?: return
        val overlayText = replacePerformanceGpuName(
            replacePerformanceCpuName(parts[2], performanceCpuName),
            performanceGpuName
        )
        _uiState.value = state.copy(
            performanceOverlayText = overlayText,
            fps = "%.1f".format(fps),
            speedPercent = speedPercent
        )
    }

    fun startEmulation(
        path: String?,
        slotToLoad: Int? = null,
        bootToBios: Boolean = false,
        bootSmokeProbe: Boolean = false,
        autotestMode: Boolean = false,
        enableEeRecompilerOverride: Boolean? = null,
        enableIopRecompilerOverride: Boolean? = null,
        enableVu0RecompilerOverride: Boolean? = null,
        enableVu1RecompilerOverride: Boolean? = null,
        enableFastmemOverride: Boolean? = null,
        enableMtvuOverride: Boolean? = null,
        rendererOverride: Int? = null,
        gsDumpFrames: Int? = null,
        gsDumpDelayMs: Int? = null
    ) {
        val analyticsLaunchType = when {
            bootSmokeProbe -> "smoke_test"
            autotestMode -> "autotest"
            bootToBios -> "bios"
            else -> "game"
        }
        Log.i(
            TAG,
            "startEmulation requested path=$path bootBios=$bootToBios bootSmoke=$bootSmokeProbe autotest=$autotestMode"
        )
        if (_uiState.value.isStarting) {
            Log.w(TAG, "startEmulation skipped because another start is in progress")
            return
        }
        val normalizedSlotToLoad = slotToLoad?.let { normalizeSaveSlot(it) }
        val hasPendingStateLoad = !bootToBios && !bootSmokeProbe && normalizedSlotToLoad != null
        var analyticsPerformanceProfile = PerformanceProfiles.SAFE
        cancelPendingStart = false
        pausedForBackground = false
        currentGamePath = if (bootToBios) null else path?.takeIf { it.isNotBlank() }
        currentTouchControlsLayoutProfile = null
        currentGameCoverArtPath = null
        lastAutoSavePlayTimeMs = 0L
        _uiState.value = _uiState.value.copy(
            activePlayTimeMs = 0L,
            currentSlotLastModified = 0L,
            autoSaveLastModified = 0L,
            isAutoSaveInProgress = false
        )

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "status_preparing"
            )

            if (_uiState.value.isRunning || EmulatorBridge.hasValidVm()) {
                performShutdown()
                delay(300.milliseconds)
            }

            var finalLaunchPath: String? = null
            lifecycleMutex.withLock {
                if (isShuttingDown || cancelPendingStart) return@launch

                _uiState.value = _uiState.value.copy(
                    isStarting = true,
                    statusMessage = "status_checking_bios"
                )

                val config = loadLaunchConfig()
                analyticsPerformanceProfile = config.performanceProfile
                currentAnalyticsAudioBackend = config.audioBackend
                val renderer = rendererOverride ?: config.renderer
                Log.i(
                    TAG,
                    "Launch config loaded bios=${config.biosPath} renderer=$renderer override=${rendererOverride != null} eeJit=${config.enableEeRecompiler} iopJit=${config.enableIopRecompiler}"
                )
                val enableEeRecompiler = enableEeRecompilerOverride ?: config.enableEeRecompiler
                val enableIopRecompiler = enableIopRecompilerOverride ?: config.enableIopRecompiler
                val enableVu0Recompiler = enableVu0RecompilerOverride ?: config.enableVu0Recompiler
                val enableVu1Recompiler = enableVu1RecompilerOverride ?: config.enableVu1Recompiler
                val enableFastmem = enableFastmemOverride ?: config.enableFastmem
                val enableMtvu = enableMtvuOverride ?: config.mtvu

                if (!autotestMode) {
                    val resolvedBiosPath = DocumentPathResolver.prepareBiosDirectory(getApplication(), config.biosPath)
                        ?: config.biosPath?.let(DocumentPathResolver::resolveDirectoryPath)
                    val biosDirExists = !resolvedBiosPath.isNullOrBlank() && File(resolvedBiosPath).exists()
                    val biosLooksUsable = BiosValidator.hasUsableBiosFiles(getApplication(), config.biosPath)
                    if (!biosDirExists || !biosLooksUsable) {
                        _uiState.value = _uiState.value.copy(
                            isStarting = false,
                            statusMessage = null,
                            toastMessage = "bios_missing"
                        )
                        delay(2500.milliseconds)
                        _uiState.value = _uiState.value.copy(toastMessage = null)
                        return@withLock
                    }
                }

                _uiState.value = _uiState.value.copy(
                    statusMessage = "status_applying_config"
                )
                delay(200.milliseconds)

                EmulatorBridge.applyRuntimeConfig(
                    biosPath = config.biosPath,
                    emulatorDataPath = config.emulatorDataPath,
                    memoryCardSlot1 = config.memoryCardSlot1,
                    memoryCardSlot2 = config.memoryCardSlot2,
                    renderer = renderer,
                    upscaleMultiplier = config.upscaleMultiplier,
                    gpuDriverType = config.gpuDriverType,
                    customDriverPath = config.customDriverPath,
                    gpuHardwareProfile = config.gpuHardwareProfile,
                    mediatekAngleOpenGl = config.mediatekAngleOpenGl,
                    aspectRatio = config.aspectRatio,
                    localMultiplayerMode = config.localMultiplayerMode,
                    displayCrop = config.displayCrop,
                    audioVolume = config.audioVolume,
                    audioFastForwardVolume = config.audioFastForwardVolume,
                    audioMuted = config.audioMuted,
                    audioInterpolation = config.audioInterpolation,
                    audioSyncMode = config.audioSyncMode,
                    audioLightweightSpu2 = config.audioLightweightSpu2,
                    audioBackend = config.audioBackend,
                    audioBufferMs = config.audioBufferMs,
                    audioOutputLatencyMs = config.audioOutputLatencyMs,
                    audioMinimalOutputLatency = config.audioMinimalOutputLatency,
                    enableEeRecompiler = enableEeRecompiler,
                    enableIopRecompiler = enableIopRecompiler,
                    enableVu0Recompiler = enableVu0Recompiler,
                    enableVu1Recompiler = enableVu1Recompiler,
                    eeFpuRoundMode = config.eeFpuRoundMode,
                    vu0RoundMode = config.vu0RoundMode,
                    vu1RoundMode = config.vu1RoundMode,
                    eeFpuClampingMode = config.eeFpuClampingMode,
                    vu0ClampingMode = config.vu0ClampingMode,
                    vu1ClampingMode = config.vu1ClampingMode,
                    enableGameFixes = config.enableGameFixes,
                    eeTimingHack = config.eeTimingHack,
                    enableFastmem = enableFastmem,
                    waitLoopSpeedhack = config.waitLoopSpeedhack,
                    intcStatSpeedhack = config.intcStatSpeedhack,
                    vuFlagHack = config.vuFlagHack,
                    instantVu1 = config.instantVu1,
                    mtvu = enableMtvu,
                    enableThreadPinning = config.enableThreadPinning,
                    enableFastBoot = config.enableFastBoot,
                    fastCdvd = config.fastCdvd,
                    enableCheats = config.enableCheats,
                    hwDownloadMode = config.hwDownloadMode,
                    eeCycleRate = config.eeCycleRate,
                    eeCycleSkip = config.eeCycleSkip,
                    frameSkip = config.frameSkip,
                    skipDuplicateFrames = config.skipDuplicateFrames,
                    frameLimitEnabled = config.frameLimitEnabled,
                    vSyncEnabled = config.vSyncEnabled,
                    fastForwardSpeed = config.fastForwardSpeed,
                    targetFps = config.targetFps,
                    ntscFramerate = config.ntscFramerate,
                    palFramerate = config.palFramerate,
                    textureFiltering = config.textureFiltering,
                    trilinearFiltering = config.trilinearFiltering,
                    blendingAccuracy = config.blendingAccuracy,
                    texturePreloading = config.texturePreloading,
                    shaderChainEnabled = config.shaderChainEnabled,
                    shaderChainPreset = config.shaderChainPreset,
                    enableFxaa = config.enableFxaa,
                    casMode = config.casMode,
                    sgsrMode = config.sgsrMode,
                    casSharpness = config.casSharpness,
                    tvShader = config.tvShader,
                    shadeBoostEnabled = config.shadeBoostEnabled,
                    shadeBoostBrightness = config.shadeBoostBrightness,
                    shadeBoostContrast = config.shadeBoostContrast,
                    shadeBoostSaturation = config.shadeBoostSaturation,
                    shadeBoostGamma = config.shadeBoostGamma,
                    deinterlaceMode = config.deinterlaceMode,
                    dithering = config.dithering,
                    anisotropicFiltering = config.anisotropicFiltering,
                    enableHwMipmapping = config.enableHwMipmapping,
                    antiBlur = config.antiBlur,
                    widescreenPatches = config.widescreenPatches,
                    noInterlacingPatches = config.noInterlacingPatches,
                    cpuSpriteRenderSize = config.cpuSpriteRenderSize,
                    cpuSpriteRenderLevel = config.cpuSpriteRenderLevel,
                    softwareClutRender = config.softwareClutRender,
                    gpuTargetClutMode = config.gpuTargetClutMode,
                    skipDrawStart = config.skipDrawStart,
                    skipDrawEnd = config.skipDrawEnd,
                    autoFlushHardware = config.autoFlushHardware,
                    cpuFramebufferConversion = config.cpuFramebufferConversion,
                    disableDepthConversion = config.disableDepthConversion,
                    disableSafeFeatures = config.disableSafeFeatures,
                    disableRenderFixes = config.disableRenderFixes,
                    preloadFrameData = config.preloadFrameData,
                    disablePartialInvalidation = config.disablePartialInvalidation,
                    textureInsideRt = config.textureInsideRt,
                    readTargetsOnClose = config.readTargetsOnClose,
                    estimateTextureRegion = config.estimateTextureRegion,
                    gpuPaletteConversion = config.gpuPaletteConversion,
                    halfPixelOffset = config.halfPixelOffset,
                    nativeScaling = config.nativeScaling,
                    roundSprite = config.roundSprite,
                    bilinearUpscale = config.bilinearUpscale,
                    textureOffsetX = config.textureOffsetX,
                    textureOffsetY = config.textureOffsetY,
                    alignSprite = config.alignSprite,
                    mergeSprite = config.mergeSprite,
                    forceEvenSpritePosition = config.forceEvenSpritePosition,
                    nativePaletteDraw = config.nativePaletteDraw,
                    pressureModifierAmount = config.pressureModifierAmount,
                    autotestMode = autotestMode || bootSmokeProbe,
                    fpuCorrectAddSub = config.fpuCorrectAddSub,
                    enableIcacheEmulation = config.enableIcacheEmulation,
                    enableDisableStalls = config.enableDisableStalls,
                    enablePreciseExceptions = config.enablePreciseExceptions,
                    enableTurboCd = config.enableTurboCd,
                    cdReadAhead = config.cdReadAhead,
                    enableCddaAudio = config.enableCddaAudio,
                    enableXaDecoding = config.enableXaDecoding,
                    enableSpuReverb = config.enableSpuReverb,
                    enableSpuThread = config.enableSpuThread,
                    spuTempo = config.spuTempo,
                    neonEnhancement = config.neonEnhancement,
                    neonEnhancementSpeedHack = config.neonEnhancementSpeedHack,
                    neonEnhancementTexAdj = config.neonEnhancementTexAdj,
                    neonInterlace = config.neonInterlace,
                    gpuThreadRendering = config.gpuThreadRendering,
                    showOverscan = config.showOverscan,
                    screenCentering = config.screenCentering,
                    screenCenteringX = config.screenCenteringX,
                    screenCenteringY = config.screenCenteringY,
                    screenCenteringHAdj = config.screenCenteringHAdj,
                    enableFractionalFramerate = config.enableFractionalFramerate,
                    altFlipMode = config.altFlipMode,
                    enableRgb32Output = config.enableRgb32Output,
                    enableScaleHires = config.enableScaleHires,
                    multitapMode = config.multitapMode,
                    analogAxisModifier = config.analogAxisModifier,
                    dualshockToggleCombo = config.dualshockToggleCombo
                )

                _uiState.value = _uiState.value.copy(
                    statusMessage = "status_loading_game"
                )
                delay(200.milliseconds)

                val launchPath = when {
                    bootToBios -> ""
                    path.isNullOrBlank() -> null
                    path.startsWith("content://") && DocumentPathResolver.getDisplayName(getApplication(), path)
                        .substringAfterLast('.', "").equals("elf", ignoreCase = true) ->
                            DocumentPathResolver.prepareElfLaunchPath(getApplication(), path)
                    else -> DocumentPathResolver.prepareGameLaunchPath(getApplication(), path)
                }

                if (!bootToBios && launchPath.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isStarting = false,
                        statusMessage = null,
                        toastMessage = "launch_path_error"
                    )
                    delay(2500.milliseconds)
                    _uiState.value = _uiState.value.copy(toastMessage = null)
                    return@withLock
                }

                finalLaunchPath = launchPath
                Log.i(TAG, "Prepared launch path=$launchPath originalPath=$path bootBios=$bootToBios")
                if (bootToBios) {
                    currentGameTitle = "PlayStation BIOS"
                    currentGamePath = null
                    currentGameSerial = ""
                    currentGameRegionLabel = ""
                    currentGameCoverArtPath = null
                    currentGameCrc = ""
                    currentGameSource = "bios_only"
                    pendingPerGameCoreOptions = emptyMap()
                    _uiState.value = _uiState.value.copy(
                        currentGameTitle = currentGameTitle,
                        currentGameSubtitle = currentGameSubtitle(),
                        currentGameCoverPath = currentGameCoverArtPath,
                        gameSettingsProfileActive = false,
                        perGameCoreOptions = emptyMap(),
                        cheatsGameKey = null,
                        availableCheats = emptyList()
                    )
                } else if (autotestMode) {
                    val safePath = path.orEmpty()
                    currentGameTitle = File(safePath).nameWithoutExtension.ifBlank { "Autotest ELF" }
                    currentGameSerial = ""
                    currentGameRegionLabel = ""
                    currentGameCoverArtPath = null
                    currentGameCrc = ""
                    currentGameSource = "autotest_elf"
                    pendingPerGameCoreOptions = emptyMap()
                    _uiState.value = _uiState.value.copy(
                        currentGameTitle = currentGameTitle,
                        currentGameSubtitle = currentGameSubtitle(),
                        currentGameCoverPath = currentGameCoverArtPath,
                        gameSettingsProfileActive = false,
                        perGameCoreOptions = emptyMap(),
                        cheatsGameKey = null,
                        availableCheats = emptyList()
                    )
                } else {
                    val safePath = path.orEmpty()
                    val existingProfile = currentGamePath?.let(perGameSettingsRepository::get)
                    currentTouchControlsLayoutProfile = existingProfile?.touchControlsLayout
                    val metadata = EmulatorBridge.getGameMetadata(safePath)
                    currentGameTitle = EmulatorBridge.cleanGameDisplayTitle(metadata.title, safePath)
                    currentGameSerial = metadata.serial?.takeIf { it.isNotBlank() }
                        ?: resolveSerialFromTitle(currentGameTitle, safePath).orEmpty()
                    currentGameRegionLabel = resolveRegionLabel(currentGameSerial, safePath)
                    currentGameCoverArtPath = gameRepository.findCoverForGame(
                        path = safePath,
                        context = getApplication(),
                        serial = currentGameSerial.takeIf { it.isNotBlank() },
                        title = currentGameTitle
                    )
                    currentGameCrc = metadata.serialWithCrc.extractCrc().orEmpty()
                    currentGameSource = when {
                        safePath.startsWith("content://") -> "content_uri"
                        launchPath?.startsWith("/") == true -> "file"
                        else -> "unknown"
                    }
                    pendingPerGameCoreOptions = (
                        existingProfile
                            ?: safePath.takeIf { it.isNotBlank() }?.let(perGameSettingsRepository::get)
                        )?.coreOptions.orEmpty()
                        .filterKeys { !SwanStationCoreOptions.isManagedKey(it) }
                    refreshCurrentGameCheats(metadata, config.enableCheats)
                    _uiState.value = _uiState.value.copy(
                        currentGameTitle = currentGameTitle,
                        currentGameSubtitle = currentGameSubtitle(),
                        currentGameCoverPath = currentGameCoverArtPath,
                        gameSettingsProfileActive = existingProfile != null,
                        perGameCoreOptions = pendingPerGameCoreOptions
                    )
                    syncCurrentGameProfileMetadata()
                    if (!bootSmokeProbe) {
                        DiscordIntegration.setPlaying(
                            title = currentGameTitle,
                            serial = currentGameSerial.takeIf { it.isNotBlank() }
                        )
                    }
                }
                updateCrashContext(
                    launchState = "starting",
                    launchPath = path
                )

                _uiState.value = _uiState.value.copy(
                    isRunning = true,
                    isStarting = false,
                    statusMessage = "status_starting_core"
                )
                refreshSaveStateMetadata()
                if (!autotestMode && !bootSmokeProbe && !bootToBios && !path.isNullOrBlank()) {
                    preferences.markGameLaunched(
                        path = path,
                        title = currentGameTitle.ifBlank {
                            DocumentPathResolver.getDisplayName(getApplication(), path).substringBeforeLast('.')
                        },
                        serial = currentGameSerial.takeIf { it.isNotBlank() }
                    )
                }

                val liveRuntime = loadLiveRuntimeSnapshot()
                val overlaySnapshot = preferences.overlayLayoutSnapshot.first()

                val runtimeState = _uiState.value.copy(
                    showFps = liveRuntime.showFps,
                    fpsOverlayMode = FPS_OVERLAY_MODE_SIMPLE,
                    confirmSaveLoadActions = liveRuntime.confirmSaveLoadActions,
                    backButtonExitsGame = liveRuntime.backButtonExitsGame,
                    renderer = liveRuntime.renderer,
                    upscale = liveRuntime.upscale,
                    aspectRatio = liveRuntime.aspectRatio,
                    localMultiplayerMode = liveRuntime.localMultiplayerMode,
                    displayCrop = liveRuntime.displayCrop,
                    performancePreset = liveRuntime.performancePreset,
                    enableInstantVu1 = liveRuntime.enableInstantVu1,
                    enableMtvu = liveRuntime.enableMtvu,
                    enableThreadPinning = liveRuntime.enableThreadPinning,
                    enableFastCdvd = liveRuntime.enableFastCdvd,
                    enableFastBoot = liveRuntime.enableFastBoot,
                    enableCheats = liveRuntime.enableCheats,
                    hwDownloadMode = liveRuntime.hwDownloadMode,
                    eeCycleRate = liveRuntime.eeCycleRate,
                    eeCycleSkip = liveRuntime.eeCycleSkip,
                    frameSkip = liveRuntime.frameSkip,
                    skipDuplicateFrames = liveRuntime.skipDuplicateFrames,
                    textureFiltering = liveRuntime.textureFiltering,
                    trilinearFiltering = liveRuntime.trilinearFiltering,
                    blendingAccuracy = liveRuntime.blendingAccuracy,
                    texturePreloading = liveRuntime.texturePreloading,
                    enableFxaa = liveRuntime.enableFxaa,
                    casMode = liveRuntime.casMode,
                    sgsrMode = liveRuntime.sgsrMode,
                    casSharpness = liveRuntime.casSharpness,
                    tvShader = liveRuntime.tvShader,
                    shadeBoostEnabled = liveRuntime.shadeBoostEnabled,
                    shadeBoostBrightness = liveRuntime.shadeBoostBrightness,
                    shadeBoostContrast = liveRuntime.shadeBoostContrast,
                    shadeBoostSaturation = liveRuntime.shadeBoostSaturation,
                    shadeBoostGamma = liveRuntime.shadeBoostGamma,
                    anisotropicFiltering = liveRuntime.anisotropicFiltering,
                    enableHwMipmapping = liveRuntime.enableHwMipmapping,
                    antiBlur = liveRuntime.antiBlur,
                    deinterlaceMode = liveRuntime.deinterlaceMode,
                    dithering = liveRuntime.dithering,
                    widescreenPatches = liveRuntime.widescreenPatches,
                    noInterlacingPatches = liveRuntime.noInterlacingPatches,
                    cpuSpriteRenderSize = liveRuntime.cpuSpriteRenderSize,
                    cpuSpriteRenderLevel = liveRuntime.cpuSpriteRenderLevel,
                    softwareClutRender = liveRuntime.softwareClutRender,
                    gpuTargetClutMode = liveRuntime.gpuTargetClutMode,
                    skipDrawStart = liveRuntime.skipDrawStart,
                    skipDrawEnd = liveRuntime.skipDrawEnd,
                    autoFlushHardware = liveRuntime.autoFlushHardware,
                    cpuFramebufferConversion = liveRuntime.cpuFramebufferConversion,
                    disableDepthConversion = liveRuntime.disableDepthConversion,
                    disableSafeFeatures = liveRuntime.disableSafeFeatures,
                    disableRenderFixes = liveRuntime.disableRenderFixes,
                    preloadFrameData = liveRuntime.preloadFrameData,
                    disablePartialInvalidation = liveRuntime.disablePartialInvalidation,
                    textureInsideRt = liveRuntime.textureInsideRt,
                    readTargetsOnClose = liveRuntime.readTargetsOnClose,
                    estimateTextureRegion = liveRuntime.estimateTextureRegion,
                    gpuPaletteConversion = liveRuntime.gpuPaletteConversion,
                    halfPixelOffset = liveRuntime.halfPixelOffset,
                    nativeScaling = liveRuntime.nativeScaling,
                    roundSprite = liveRuntime.roundSprite,
                    bilinearUpscale = liveRuntime.bilinearUpscale,
                    textureOffsetX = liveRuntime.textureOffsetX,
                    textureOffsetY = liveRuntime.textureOffsetY,
                    alignSprite = liveRuntime.alignSprite,
                    mergeSprite = liveRuntime.mergeSprite,
                    forceEvenSpritePosition = liveRuntime.forceEvenSpritePosition,
                    nativePaletteDraw = liveRuntime.nativePaletteDraw,
                    frameLimitEnabled = liveRuntime.frameLimitEnabled,
                    fastForwardSpeed = liveRuntime.fastForwardSpeed,
                    racingMode = liveRuntime.racingMode,
                    touchscreenRightStick = liveRuntime.touchscreenRightStick,
                    touchscreenRightStickSensitivity = liveRuntime.touchscreenRightStickSensitivity,
                    touchHaptics = liveRuntime.touchHaptics,
                    touchHapticsPreset = liveRuntime.touchHapticsPreset,
                    touchHapticsStrength = liveRuntime.touchHapticsStrength,
                    touchControlVisualStyle = liveRuntime.touchControlVisualStyle,
                    touchControlPressEffect = liveRuntime.touchControlPressEffect,
                    gyroMode = liveRuntime.gyroMode,
                    gyroSensitivity = liveRuntime.gyroSensitivity,
                    gyroSmoothing = liveRuntime.gyroSmoothing,
                    gyroInvertX = liveRuntime.gyroInvertX,
                    gyroInvertY = liveRuntime.gyroInvertY,
                    gamepadRightStickUpToR2 = liveRuntime.gamepadRightStickUpToR2,
                    gamepadRightStickDownToL2 = liveRuntime.gamepadRightStickDownToL2,
                    gamepadButtonHaptics = liveRuntime.gamepadButtonHaptics,
                    gamepadStickDeadzone = liveRuntime.gamepadStickDeadzone,
                    gamepadLeftStickSensitivity = liveRuntime.gamepadLeftStickSensitivity,
                    gamepadRightStickSensitivity = liveRuntime.gamepadRightStickSensitivity,
                    gamepadBindingsByPad = liveRuntime.gamepadBindingsByPad,
                    pressureModifierAmount = liveRuntime.pressureModifierAmount,
                    autoSaveOnExit = liveRuntime.autoSaveOnExit,
                    autoLoadOnStart = liveRuntime.autoLoadOnStart,
                    targetFps = liveRuntime.targetFps,
                    ntscFramerate = liveRuntime.ntscFramerate,
                    palFramerate = liveRuntime.palFramerate,
                    enableIcacheEmulation = liveRuntime.enableIcacheEmulation,
                    enableDisableStalls = liveRuntime.enableDisableStalls,
                    enablePreciseExceptions = liveRuntime.enablePreciseExceptions,
                    enableTurboCd = liveRuntime.enableTurboCd,
                    cdReadAhead = liveRuntime.cdReadAhead,
                    enableCddaAudio = liveRuntime.enableCddaAudio,
                    enableXaDecoding = liveRuntime.enableXaDecoding,
                    enableSpuReverb = liveRuntime.enableSpuReverb,
                    enableSpuThread = liveRuntime.enableSpuThread,
                    spuTempo = liveRuntime.spuTempo,
                    neonEnhancement = liveRuntime.neonEnhancement,
                    neonEnhancementSpeedHack = liveRuntime.neonEnhancementSpeedHack,
                    neonEnhancementTexAdj = liveRuntime.neonEnhancementTexAdj,
                    neonInterlace = liveRuntime.neonInterlace,
                    gpuThreadRendering = liveRuntime.gpuThreadRendering,
                    showOverscan = liveRuntime.showOverscan,
                    screenCentering = liveRuntime.screenCentering,
                    screenCenteringX = liveRuntime.screenCenteringX,
                    screenCenteringY = liveRuntime.screenCenteringY,
                    screenCenteringHAdj = liveRuntime.screenCenteringHAdj,
                    enableFractionalFramerate = liveRuntime.enableFractionalFramerate,
                    altFlipMode = liveRuntime.altFlipMode,
                    enableRgb32Output = liveRuntime.enableRgb32Output,
                    enableScaleHires = liveRuntime.enableScaleHires,
                    multitapMode = liveRuntime.multitapMode,
                    analogAxisModifier = liveRuntime.analogAxisModifier,
                    dualshockToggleCombo = liveRuntime.dualshockToggleCombo
                )
                val overlayState = runtimeState.withOverlayLayoutSnapshot(overlaySnapshot)
                _uiState.value = currentTouchControlsLayoutProfile?.let { overlayState.withTouchControlsLayout(it) } ?: overlayState
                syncNativePerformanceOverlayState(_uiState.value)
                syncGamepadRuntimeSettings(_uiState.value)
                if (_uiState.value.gameSettingsProfileActive) {
                    val state = _uiState.value
                    GamepadManager.applyPerGameOverrides(
                        bindingsByPad = state.gamepadBindingsByPad,
                        deadzone = state.gamepadStickDeadzone,
                        leftSensitivity = state.gamepadLeftStickSensitivity,
                        rightSensitivity = state.gamepadRightStickSensitivity
                    )
                }
                updateCrashContext(
                    launchState = "starting",
                    launchPath = path
                )
            }

            if (hasPendingStateLoad) {
                viewModelScope.launch(Dispatchers.IO) {
                    var vmReadyWaitFrames = 0
                    while (vmReadyWaitFrames < 60 && isActive) {
                        try {
                            if (EmulatorBridge.hasValidVm()) break
                        } catch (_: Exception) { }
                        delay(250.milliseconds)
                        vmReadyWaitFrames++
                    }
                    if (!isActive) return@launch

                    if (!EmulatorBridge.hasValidVm()) {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = null,
                            toastMessage = "load_failed"
                        )
                        delay(2500.milliseconds)
                        if (_uiState.value.toastMessage == "load_failed") {
                            _uiState.value = _uiState.value.copy(toastMessage = null)
                        }
                        return@launch
                    }

                    delay(500.milliseconds)
                    val loaded = EmulatorBridge.loadState(normalizedSlotToLoad)
                    _uiState.value = _uiState.value.copy(
                        isRunning = true,
                        isPaused = false,
                        statusMessage = if (loaded) "status_running" else null,
                        toastMessage = if (loaded) null else "load_failed"
                    )
                    refreshSaveStateMetadata()
                    delay(2000.milliseconds)
                    if (_uiState.value.statusMessage == "status_running") {
                        _uiState.value = _uiState.value.copy(statusMessage = null)
                    }
                    if (_uiState.value.toastMessage == "load_failed") {
                        delay(500.milliseconds)
                        if (_uiState.value.toastMessage == "load_failed") {
                            _uiState.value = _uiState.value.copy(toastMessage = null)
                        }
                    }
                }
            }

            val pathToLaunch = finalLaunchPath ?: return@launch

            if (cancelPendingStart) {
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    isStarting = false,
                    statusMessage = null
                )
                return@launch
            }

            if (!hasPendingStateLoad) {
                viewModelScope.launch(Dispatchers.IO) {
                    var waitFrames = 0
                    while (waitFrames < 60 && isActive) {
                        if (EmulatorBridge.hasValidVm()) {
                            if (tryAutoLoadOnStart()) {
                                break
                            }
                            _uiState.value = _uiState.value.copy(statusMessage = "status_running")
                            delay(2000.milliseconds)
                            if (_uiState.value.statusMessage == "status_running") {
                                _uiState.value = _uiState.value.copy(statusMessage = null)
                            }
                            break
                        }
                        delay(250.milliseconds)
                        waitFrames++
                    }
                }
            }

            val started = try {
                EmulatorBridge.startEmulation(
                    pathToLaunch,
                    saveStateIdentityPath = currentGamePath,
                    bootSmokeProbe = bootSmokeProbe,
                    allowBiosBoot = bootToBios
                )
            } catch (error: Exception) {
                Log.e(TAG, "EmulatorBridge.startEmulation failed", error)
                false
            }
            Log.i(TAG, "EmulatorBridge.startEmulation returned $started path=$pathToLaunch")
            if (started) {
                RetroAchievementsRepository.get(getApplication()).onGameStarted(pathToLaunch)
                syncCheatsForCurrentGame()
                // Per-game core options win over the global store.
                pendingPerGameCoreOptions.forEach { (coreKey, coreValue) ->
                    NativeApp.applyCoreOption(coreKey, coreValue)
                }
                syncPadAnalogModeForLaunch()
            }
            if (started && gsDumpFrames != null && gsDumpFrames > 0) {
                val delayMs = gsDumpDelayMs?.coerceAtLeast(0) ?: 0
                viewModelScope.launch(Dispatchers.IO) {
                    delay(delayMs.milliseconds)
                    if (EmulatorBridge.hasValidVm()) {
                        Log.i(TAG, "Queueing GS dump frames=$gsDumpFrames delayMs=$delayMs")
                        NativeApp.queueGsDump(gsDumpFrames)
                    }
                }
            }
            updateCrashContext(
                launchState = if (started) "running" else "launch_failed",
                launchPath = path
            )

            if (!started &&
                !_uiState.value.isPaused &&
                !cancelPendingStart &&
                !isShuttingDown
            ) {
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    statusMessage = null,
                    toastMessage = "launch_failed"
                )
                delay(2500.milliseconds)
                _uiState.value = _uiState.value.copy(toastMessage = null)
            }
        }
    }

    fun toggleJitProfiler() {
        val state = _uiState.value
        val nextState = !state.isJitProfilerActive
        _uiState.value = state.copy(isJitProfilerActive = nextState)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (nextState) {
                    EmulatorBridge.startJitProfiler()
                } else {
                    EmulatorBridge.stopJitProfiler()
                }
            } catch (_: Exception) {}
        }
    }

    fun toggleHangTrace() {
        val state = _uiState.value
        val nextState = !state.isHangTraceActive
        _uiState.value = state.copy(isHangTraceActive = nextState)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (nextState) {
                    EmulatorBridge.startHangTrace()
                } else {
                    EmulatorBridge.stopHangTrace()
                }
            } catch (_: Exception) {}
        }
    }

    fun togglePause() {
        val state = _uiState.value
        if (state.showMenu) {
            closeMenu()
            return
        }

        val isPaused = state.isPaused
        pausedForBackground = false
        _uiState.value = _uiState.value.copy(
            isPaused = !isPaused,
            showMenu = if (isPaused) false else _uiState.value.showMenu
        )
        DiscordIntegration.setPaused(!isPaused)
        updateCrashContext(launchState = if (!isPaused) "paused" else "running")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isPaused) {
                    EmulatorBridge.resume()
                } else {
                    EmulatorBridge.pause()
                }
            } catch (_: Exception) { }
        }
    }

    fun toggleMenu() {
        val showMenu = !_uiState.value.showMenu
        if (showMenu) {
            pausedForBackground = false
            EmulatorBridge.resetKeyStatus()
            refreshSaveStateMetadata()
            _uiState.value = _uiState.value.copy(showMenu = true, isPaused = true)
            DiscordIntegration.setPaused(true)
            updateCrashContext(launchState = "paused")
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    EmulatorBridge.pause()
                } catch (_: Exception) { }
            }
        } else {
            closeMenu()
        }
    }

    fun swapDisc(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val displayName = DocumentPathResolver.getDisplayName(context, uri.toString())
            val readable = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.statSize != 0L
                } ?: false
            }.getOrDefault(false)

            if (!SetupValidator.isSupportedDiscImageName(displayName) || !readable) {
                _uiState.value = _uiState.value.copy(toastMessage = "disc_swap_invalid")
                delay(2500.milliseconds)
                if (_uiState.value.toastMessage == "disc_swap_invalid") {
                    _uiState.value = _uiState.value.copy(toastMessage = null)
                }
                return@launch
            }

            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            _uiState.value = _uiState.value.copy(
                isActionInProgress = true,
                actionLabel = "swapping_disc"
            )
            val success = lifecycleMutex.withLock {
                if (isShuttingDown || !_uiState.value.isRunning) {
                    false
                } else {
                    EmulatorBridge.changeDisc(uri.toString())
                }
            }

            // VMManager restores the old image when opening the selected image fails.
            // Either way the tray cycle must continue, so close the menu and resume.
            runCatching { EmulatorBridge.resume() }
            pausedForBackground = false
            _uiState.value = _uiState.value.copy(
                isPaused = false,
                showMenu = false,
                isActionInProgress = false,
                actionLabel = null,
                toastMessage = if (success) "disc_swap_success" else "disc_swap_failed"
            )
            DiscordIntegration.setPaused(false)
            updateCrashContext(launchState = "running")
            delay(3000.milliseconds)
            val expectedToast = if (success) "disc_swap_success" else "disc_swap_failed"
            if (_uiState.value.toastMessage == expectedToast) {
                _uiState.value = _uiState.value.copy(toastMessage = null)
            }
        }
    }

    private fun closeMenu() {
        pausedForBackground = false
        _uiState.value = _uiState.value.copy(showMenu = false, isPaused = false)
        DiscordIntegration.setPaused(false)
        updateCrashContext(launchState = "running")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                EmulatorBridge.resume()
            } catch (_: Exception) { }
        }
    }

    fun toggleControlsVisibility() {
        viewModelScope.launch {
            val newValue = !_uiState.value.controlsVisible
            preferences.setOverlayShow(newValue)
            _uiState.value = _uiState.value.copy(controlsVisible = newValue)
        }
    }

    fun saveCurrentGameSettingsProfile() {
        viewModelScope.launch {
            persistRuntimeState(_uiState.value)
        }
    }

    fun resetCurrentGameSettingsProfile() {
        viewModelScope.launch {
            resetCurrentGameProfile()
        }
    }

    /**
     * Applies a SwanStation core option while a game session is running.
     *
     * Core options edited in-game belong to the running game, so they are stored
     * in that game's profile instead of the global core-option store. A change
     * made while the core is live (BIOS only) still persists globally.
     */
    fun setCoreOption(key: String, value: String) {
        viewModelScope.launch {
            val gameKey = activePerGameKey()
            if (gameKey == null) {
                NativeApp.setCoreOption(key, value)
                return@launch
            }
            NativeApp.applyCoreOption(key, value)
            val existing = perGameSettingsRepository.get(gameKey)
            val coreOptions = (existing?.coreOptions ?: emptyMap()) + (key to value)
            val profile = existing?.copy(coreOptions = coreOptions)
                ?: PerGameSettings(
                    gameKey = gameKey,
                    gameTitle = resolvePerGameTitle(_uiState.value),
                    gameSerial = currentGameSerial.takeIf { it.isNotBlank() },
                    coreOptions = coreOptions,
                    providedKeys = setOf("coreOptions")
                )
            perGameSettingsRepository.save(profile)
            pendingPerGameCoreOptions =
                coreOptions.filterKeys { !SwanStationCoreOptions.isManagedKey(it) }
            _uiState.value = _uiState.value.copy(
                gameSettingsProfileActive = true,
                perGameCoreOptions = pendingPerGameCoreOptions
            )
        }
    }

    fun setOverlayScale(value: Int) {
        viewModelScope.launch {
            preferences.setOverlayScale(value)
            _uiState.value = _uiState.value.copy(overlayScale = value.coerceIn(50, 150))
        }
    }

    fun setOverlayOpacity(value: Int) {
        viewModelScope.launch {
            preferences.setOverlayOpacity(value)
            _uiState.value = _uiState.value.copy(
                overlayOpacity = value.coerceIn(
                    AppPreferences.OVERLAY_OPACITY_MIN,
                    AppPreferences.OVERLAY_OPACITY_MAX
                )
            )
        }
    }

    fun setHideOverlayOnGamepad(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setHideOverlayOnGamepad(enabled)
            _uiState.value = _uiState.value.copy(hideOverlayOnGamepad = enabled)
        }
    }

    fun setCompactControls(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setCompactControls(enabled)
            _uiState.value = _uiState.value.copy(compactControls = enabled)
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setKeepScreenOn(enabled)
            _uiState.value = _uiState.value.copy(keepScreenOn = enabled)
        }
    }

    fun setStickScale(value: Int) {
        viewModelScope.launch {
            val scaledValue = value.coerceIn(
                AppPreferences.OVERLAY_CONTROL_SCALE_MIN,
                AppPreferences.OVERLAY_CONTROL_SCALE_MAX
            )
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            listOf("left_stick", "right_stick").forEach { id ->
                val existing = updatedLayouts[id]
                if (existing != null) {
                    updatedLayouts[id] = existing.copy(scale = scaledValue)
                }
            }
            persistTouchControlsLayout(
                current.copy(
                    stickScale = scaledValue,
                    controlLayouts = updatedLayouts
                )
            )
        }
    }

    fun setLeftStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setLeftStickSensitivity(normalized)
            _uiState.value = _uiState.value.copy(leftStickSensitivity = normalized)
        }
    }

    fun setRightStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setRightStickSensitivity(normalized)
            _uiState.value = _uiState.value.copy(rightStickSensitivity = normalized)
        }
    }

    fun setInvertLeftStick(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertLeftStick(enabled)
            _uiState.value = _uiState.value.copy(invertLeftStick = enabled)
        }
    }

    fun setInvertRightStick(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertRightStick(enabled)
            _uiState.value = _uiState.value.copy(invertRightStick = enabled)
        }
    }

    fun setInvertLeftStickHorizontal(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertLeftStickHorizontal(enabled)
            _uiState.value = _uiState.value.copy(invertLeftStickHorizontal = enabled)
        }
    }

    fun setInvertRightStickHorizontal(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertRightStickHorizontal(enabled)
            _uiState.value = _uiState.value.copy(invertRightStickHorizontal = enabled)
        }
    }

    fun setGamepadStickDeadzone(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(0, 35)
            preferences.setGamepadStickDeadzone(normalized)
            _uiState.value = _uiState.value.copy(gamepadStickDeadzone = normalized)
        }
    }

    fun setGamepadLeftStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setGamepadLeftStickSensitivity(normalized)
            _uiState.value = _uiState.value.copy(gamepadLeftStickSensitivity = normalized)
        }
    }

    fun setGamepadRightStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setGamepadRightStickSensitivity(normalized)
            _uiState.value = _uiState.value.copy(gamepadRightStickSensitivity = normalized)
        }
    }

    fun setGamepadRightStickUpToR2(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(gamepadRightStickUpToR2 = enabled)
            persistRuntimeState(newState) {
                preferences.setGamepadRightStickUpToR2(enabled)
            }
            syncGamepadRightStickTriggerMapping(newState)
        }
    }

    fun setGamepadRightStickDownToL2(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(gamepadRightStickDownToL2 = enabled)
            persistRuntimeState(newState) {
                preferences.setGamepadRightStickDownToL2(enabled)
            }
            syncGamepadRightStickTriggerMapping(newState)
        }
    }

    fun setGamepadBindingsByPad(bindingsByPad: Map<Int, Map<String, Int>>) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(gamepadBindingsByPad = bindingsByPad)
            _uiState.value = newState
            GamepadManager.applyPerGameOverrides(
                bindingsByPad = bindingsByPad,
                deadzone = null,
                leftSensitivity = null,
                rightSensitivity = null
            )
        }
    }

    fun toggleLeftInputMode() {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val leftStickLayout = updatedLayouts["left_stick"] ?: defaults["left_stick"] ?: OverlayControlLayout(scale = current.stickScale)
            val showingStick = leftStickLayout.visible

            // Keep the DualShock: hiding the touch stick only stops stick input,
            // it must not demote the port to a digital pad (which kills rumble).
            NativeApp.setPadAnalogMode(0, true)

            updatedLayouts["left_stick"] = leftStickLayout.copy(visible = !showingStick)
            listOf("dpad_up", "dpad_down", "dpad_left", "dpad_right").forEach { id ->
                val currentLayout = updatedLayouts[id] ?: defaults[id] ?: OverlayControlLayout()
                updatedLayouts[id] = currentLayout.copy(visible = showingStick)
            }

            persistTouchControlsLayout(
                current.copy(
                    controlLayouts = updatedLayouts,
                    dpadOffset = current.lstickOffset,
                    lstickOffset = current.dpadOffset
                )
            )
        }
    }

    fun updateTouchControlOffset(controlId: String, offset: Pair<Float, Float>) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(offset = offset)
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlOffsets(offsets: Map<String, Pair<Float, Float>>) {
        if (offsets.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            offsets.forEach { (controlId, offset) ->
                val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
                updatedLayouts[controlId] = control.copy(offset = offset)
            }
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlScale(controlId: String, scale: Int) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(
                scale = scale.coerceIn(
                    AppPreferences.OVERLAY_CONTROL_SCALE_MIN,
                    AppPreferences.OVERLAY_CONTROL_SCALE_MAX
                )
            )
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlWidthScale(controlId: String, widthScale: Int) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(widthScale = widthScale.coerceIn(100, 240))
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlOpacity(controlId: String, opacity: Int) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(
                opacity = opacity.coerceIn(
                    AppPreferences.OVERLAY_CONTROL_OPACITY_MIN,
                    AppPreferences.OVERLAY_CONTROL_OPACITY_MAX
                )
            )
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun setTouchControlVisible(controlId: String, visible: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(visible = visible)
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun setTouchStickSurfaceMode(controlId: String, enabled: Boolean) {
        if (controlId != "left_stick" && controlId != "right_stick") return
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(surfaceOnly = enabled)
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun resetTouchControlsLayout() {
        viewModelScope.launch {
            resetTouchControlsLayoutForCurrentScope()
        }
    }
    fun toggleFpsVisibility() {
        viewModelScope.launch {
            val newValue = !_uiState.value.showFps
            persistRuntimeState(_uiState.value.copy(showFps = newValue)) {
                preferences.setShowFps(newValue)
            }
        }
    }

    fun setFpsOverlayMode(mode: Int) {
        viewModelScope.launch {
            persistRuntimeState(_uiState.value.copy(fpsOverlayMode = FPS_OVERLAY_MODE_SIMPLE)) {
                preferences.setFpsOverlayMode(mode)
            }
        }
    }

    fun setFpsOverlayCorner(corner: Int) {
        viewModelScope.launch {
            preferences.setFpsOverlayCorner(corner)
            _uiState.value = _uiState.value.copy(fpsOverlayCorner = corner)
        }
    }

    fun setRenderer(renderer: Int) {
        viewModelScope.launch {
            if (!EmulatorBridge.setRenderer(renderer)) return@launch
            // A renderer switch recreates the core session, which drops the
            // per-game core-option overrides applied after the last launch.
            pendingPerGameCoreOptions.forEach { (key, value) -> NativeApp.applyCoreOption(key, value) }
            val newState = markPerformancePresetCustom(_uiState.value).copy(renderer = renderer)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setRenderer(renderer)
            }
            updateCrashContext()
        }
    }

    fun setUpscale(upscale: Float) {
        viewModelScope.launch {
            val normalizedUpscale = normalizeUpscale(upscale)
            // The in-game selector only offers Native/2x; keep the profile's
            // enhanced-resolution flag aligned with it.
            val newState = markPerformancePresetCustom(_uiState.value).copy(
                upscale = normalizedUpscale,
                neonEnhancement = normalizedUpscale >= 1.5f
            )
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setUpscaleMultiplier(normalizedUpscale)
            }
            EmulatorBridge.setUpscaleMultiplier(normalizedUpscale)
            updateCrashContext()
        }
    }

    fun setAspectRatio(value: Int) {
        viewModelScope.launch {
            persistRuntimeState(_uiState.value.copy(aspectRatio = value)) {
                preferences.setAspectRatio(value)
            }
            EmulatorBridge.setAspectRatio(value)
            updateCrashContext()
        }
    }

    fun setMtvu(enabled: Boolean) {
        viewModelScope.launch {
            val effectiveEnabled = enabled && preferences.enableVu1Recompiler.first()
            val newState = markPerformancePresetCustom(_uiState.value).copy(enableMtvu = effectiveEnabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setEnableMtvu(effectiveEnabled)
            }
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "vuThread", "bool", effectiveEnabled.toString())
            updateCrashContext()
        }
    }

    fun setLocalMultiplayerMode(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(
                AppPreferences.LOCAL_MULTIPLAYER_OFF,
                AppPreferences.LOCAL_MULTIPLAYER_HORIZONTAL_CROP_SWAPPED
            )
            persistRuntimeState(_uiState.value.copy(localMultiplayerMode = normalized)) {
                preferences.setLocalMultiplayerMode(normalized)
            }
            EmulatorBridge.setLocalMultiplayerMode(normalized)
            updateCrashContext()
        }
    }

    fun setDisplayCrop(value: DisplayCrop) {
        viewModelScope.launch {
            val crop = value.sanitized()
            persistRuntimeState(_uiState.value.copy(displayCrop = crop)) {
                preferences.setDisplayCrop(crop)
            }
            EmulatorBridge.setDisplayCrop(crop)
            updateCrashContext()
        }
    }

    fun setFpsOverlayScale(scale: Int) {
        viewModelScope.launch {
            preferences.setFpsOverlayScale(scale)
        }
    }

    fun setFpsOverlayMetrics(metrics: Int) {
        viewModelScope.launch {
            preferences.setFpsOverlayMetrics(metrics)
        }
    }

    fun setThreadPinning(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(enableThreadPinning = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setEnableThreadPinning(enabled)
            }
            EmulatorBridge.setSetting("EmuCore", "EnableThreadPinning", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setFastCdvd(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(enableFastCdvd = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setEnableFastCdvd(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "fastCDVD", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setEnableCheats(enabled: Boolean) {
        viewModelScope.launch {
            persistRuntimeState(_uiState.value.copy(enableCheats = enabled)) {
                preferences.setEnableCheats(enabled)
            }
            EmulatorBridge.setSetting("EmuCore", "EnableCheats", "bool", enabled.toString())
            withContext(Dispatchers.IO) {
                syncCheatsForCurrentGame()
            }
            updateCrashContext()
        }
    }

    fun setCheatEnabled(blockId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentState = _uiState.value
            val gameKey = currentState.cheatsGameKey ?: return@launch
            val updatedBlocks = currentState.availableCheats.map { block ->
                if (block.id == blockId) block.copy(enabled = enabled) else block
            }
            // Only this ID is written, so toggles made in the cheat manager
            // (which share the same state file) are preserved.
            cheatRepository.setBlockEnabled(gameKey, blockId, enabled)
            persistRuntimeState(_uiState.value.copy(
                availableCheats = updatedBlocks,
                enableCheats = true
            )) {
                preferences.setEnableCheats(true)
            }
            syncCheatsForCurrentGame(gameKey)
            EmulatorBridge.setSetting("EmuCore", "EnableCheats", "bool", "true")
        }
    }

    /** Enables or disables every cheat in a group (category) at once. */
    fun setCheatGroupEnabled(blockIds: Collection<String>, enabled: Boolean) {
        if (blockIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val currentState = _uiState.value
            val gameKey = currentState.cheatsGameKey ?: return@launch
            val idSet = blockIds.toSet()
            val updatedBlocks = currentState.availableCheats.map { block ->
                if (block.id in idSet) block.copy(enabled = enabled) else block
            }
            cheatRepository.setBlocksEnabled(gameKey, idSet, enabled)
            persistRuntimeState(_uiState.value.copy(
                availableCheats = updatedBlocks,
                enableCheats = true
            )) {
                preferences.setEnableCheats(true)
            }
            syncCheatsForCurrentGame(gameKey)
            EmulatorBridge.setSetting("EmuCore", "EnableCheats", "bool", "true")
        }
    }

    /**
     * Re-reads the installed cheats from storage so changes made in the cheat
     * manager (or a freshly installed pack) are reflected in the in-game menu.
     */
    fun refreshAvailableCheats() {
        viewModelScope.launch(Dispatchers.IO) {
            val path = currentGamePath
            val metadata = path?.takeIf { it.isNotBlank() }?.let { EmulatorBridge.getGameMetadata(it) }
            val serial = currentGameSerial.takeIf { it.isNotBlank() }
                ?: metadata?.serial?.takeIf { it.isNotBlank() }
                .orEmpty()
            val crc = currentGameCrc.takeIf { it.isNotBlank() }
                ?: metadata?.serialWithCrc.extractCrc()
                .orEmpty()
            val keys = linkedSetOf<String>()
            metadata?.let { keys.addAll(cheatLookupKeys(it)) }
            if (serial.isNotBlank() && crc.isNotBlank()) keys.add("${serial}_$crc")
            if (crc.isNotBlank()) keys.add(crc)
            if (serial.isNotBlank()) keys.add(serial)
            val config = cheatRepository.getGameConfig(
                gameKeys = keys.toList(),
                serial = serial,
                crc = crc.takeIf { it.isNotBlank() }
            ) ?: return@launch
            _uiState.value = _uiState.value.copy(
                cheatsGameKey = config.gameKey,
                availableCheats = config.blocks
            )
        }
    }

    fun setHwDownloadMode(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(hwDownloadMode = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setHwDownloadMode(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "HWDownloadMode", "int", value.toString())
            updateCrashContext()
        }
    }

    fun setEeCycleRate(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(-3, 3)
            val newState = markPerformancePresetCustom(_uiState.value).copy(eeCycleRate = clamped)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setEeCycleRate(clamped)
            }
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "EECycleRate", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setEeCycleSkip(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(0, 3)
            val newState = markPerformancePresetCustom(_uiState.value).copy(eeCycleSkip = clamped)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setEeCycleSkip(clamped)
            }
            EmulatorBridge.setSetting("EmuCore/Speedhacks", "EECycleSkip", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setFrameSkip(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(frameSkip = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setFrameSkip(value)
            }
            EmulatorBridge.setFrameSkip(value)
            updateCrashContext()
        }
    }

    fun setSkipDuplicateFrames(enabled: Boolean) {
        viewModelScope.launch {
            persistRuntimeState(_uiState.value.copy(skipDuplicateFrames = enabled)) {
                preferences.setSkipDuplicateFrames(enabled)
            }
            EmulatorBridge.setSkipDuplicateFrames(enabled)
            updateCrashContext()
        }
    }

    fun toggleFrameLimit() {
        setFrameLimitEnabled(!_uiState.value.frameLimitEnabled)
    }

    fun setFrameLimitEnabled(enabled: Boolean) {
        viewModelScope.launch {
            persistRuntimeState(_uiState.value.copy(frameLimitEnabled = enabled)) {
                preferences.setFrameLimitEnabled(enabled)
            }
            EmulatorBridge.setFrameLimitEnabled(enabled)
            updateCrashContext()
        }
    }

    fun setTargetFps(value: Int) {
        viewModelScope.launch {
            val clamped = if (value <= 0) 0 else value.coerceIn(20, 120)
            persistRuntimeState(_uiState.value.copy(targetFps = clamped)) {
                preferences.setTargetFps(clamped)
            }
            EmulatorBridge.setTargetFps(clamped, _uiState.value.ntscFramerate, _uiState.value.palFramerate)
            updateCrashContext()
        }
    }

    fun setNtscFramerate(value: Float) {
        viewModelScope.launch {
            val sanitized = if (value.isFinite()) {
                value.coerceIn(AppPreferences.MIN_REGION_FRAMERATE, AppPreferences.MAX_REGION_FRAMERATE)
            } else {
                AppPreferences.DEFAULT_NTSC_FRAMERATE
            }
            persistRuntimeState(_uiState.value.copy(ntscFramerate = sanitized)) {
                preferences.setNtscFramerate(sanitized)
            }
            EmulatorBridge.setTargetFps(
                _uiState.value.targetFps,
                sanitized,
                _uiState.value.palFramerate
            )
            updateCrashContext()
        }
    }

    fun setPalFramerate(value: Float) {
        viewModelScope.launch {
            val sanitized = if (value.isFinite()) {
                value.coerceIn(AppPreferences.MIN_REGION_FRAMERATE, AppPreferences.MAX_REGION_FRAMERATE)
            } else {
                AppPreferences.DEFAULT_PAL_FRAMERATE
            }
            persistRuntimeState(_uiState.value.copy(palFramerate = sanitized)) {
                preferences.setPalFramerate(sanitized)
            }
            EmulatorBridge.setTargetFps(
                _uiState.value.targetFps,
                _uiState.value.ntscFramerate,
                sanitized
            )
            updateCrashContext()
        }
    }

    fun setTextureFiltering(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(textureFiltering = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setTextureFiltering(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "filter", "int", value.toString())
            updateCrashContext()
        }
    }

    fun setTrilinearFiltering(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(trilinearFiltering = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setTrilinearFiltering(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "TriFilter", "int", value.toString())
            updateCrashContext()
        }
    }

    fun setBlendingAccuracy(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(blendingAccuracy = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setBlendingAccuracy(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "accurate_blending_unit", "int", value.toString())
            updateCrashContext()
        }
    }

    fun setTexturePreloading(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(texturePreloading = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setTexturePreloading(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "texture_preloading", "int", value.toString())
            updateCrashContext()
        }
    }

    fun setEnableFxaa(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(enableFxaa = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setEnableFxaa(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "fxaa", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setCasMode(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(casMode = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setCasMode(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "CASMode", "int", value.toString())
            updateCrashContext()
        }
    }

    fun setCasSharpness(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(0, 100)
            val newState = markPerformancePresetCustom(_uiState.value).copy(casSharpness = clamped)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setCasSharpness(clamped)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "CASSharpness", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setSgsrMode(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(0, 3)
            val newState = markPerformancePresetCustom(_uiState.value).copy(sgsrMode = clamped)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setSgsrMode(clamped)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "SGSRMode", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setTvShader(value: Int) {
        viewModelScope.launch {
            val clamped = GsHackDefaults.coerceTvShader(value)
            val newState = markPerformancePresetCustom(_uiState.value).copy(tvShader = clamped)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setTvShader(clamped)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "TVShader", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setShadeBoostBrightness(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(1, 100)
            val enabled = isShadeBoostActive(
                brightness = clamped,
                contrast = _uiState.value.shadeBoostContrast,
                saturation = _uiState.value.shadeBoostSaturation,
                gamma = _uiState.value.shadeBoostGamma
            )
            val newState = markPerformancePresetCustom(_uiState.value).copy(
                shadeBoostEnabled = enabled,
                shadeBoostBrightness = clamped
            )
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setShadeBoostEnabled(enabled)
                preferences.setShadeBoostBrightness(clamped)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost", "bool", enabled.toString())
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost_Brightness", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setShadeBoostContrast(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(1, 100)
            val enabled = isShadeBoostActive(
                brightness = _uiState.value.shadeBoostBrightness,
                contrast = clamped,
                saturation = _uiState.value.shadeBoostSaturation,
                gamma = _uiState.value.shadeBoostGamma
            )
            val newState = markPerformancePresetCustom(_uiState.value).copy(
                shadeBoostEnabled = enabled,
                shadeBoostContrast = clamped
            )
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setShadeBoostEnabled(enabled)
                preferences.setShadeBoostContrast(clamped)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost", "bool", enabled.toString())
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost_Contrast", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setShadeBoostSaturation(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(1, 100)
            val enabled = isShadeBoostActive(
                brightness = _uiState.value.shadeBoostBrightness,
                contrast = _uiState.value.shadeBoostContrast,
                saturation = clamped,
                gamma = _uiState.value.shadeBoostGamma
            )
            val newState = markPerformancePresetCustom(_uiState.value).copy(
                shadeBoostEnabled = enabled,
                shadeBoostSaturation = clamped
            )
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setShadeBoostEnabled(enabled)
                preferences.setShadeBoostSaturation(clamped)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost", "bool", enabled.toString())
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost_Saturation", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setShadeBoostGamma(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(1, 100)
            val enabled = isShadeBoostActive(
                brightness = _uiState.value.shadeBoostBrightness,
                contrast = _uiState.value.shadeBoostContrast,
                saturation = _uiState.value.shadeBoostSaturation,
                gamma = clamped
            )
            val newState = markPerformancePresetCustom(_uiState.value).copy(
                shadeBoostEnabled = enabled,
                shadeBoostGamma = clamped
            )
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setShadeBoostEnabled(enabled)
                preferences.setShadeBoostGamma(clamped)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost", "bool", enabled.toString())
            EmulatorBridge.setSetting("EmuCore/GS", "ShadeBoost_Gamma", "int", clamped.toString())
            updateCrashContext()
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
            val newState = markPerformancePresetCustom(_uiState.value).copy(widescreenPatches = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setEnableWidescreenPatches(enabled)
            }
            EmulatorBridge.setSetting("EmuCore", "EnableWideScreenPatches", "bool", enabled.toString())
            withContext(Dispatchers.IO) { syncCheatsForCurrentGame() }
            updateCrashContext()
        }
    }

    fun setEnableNoInterlacingPatches(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(noInterlacingPatches = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setEnableNoInterlacingPatches(enabled)
            }
            EmulatorBridge.setSetting("EmuCore", "EnableNoInterlacingPatches", "bool", enabled.toString())
            withContext(Dispatchers.IO) { syncCheatsForCurrentGame() }
            updateCrashContext()
        }
    }

    fun setAnisotropicFiltering(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(anisotropicFiltering = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setAnisotropicFiltering(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "MaxAnisotropy", "int", value.toString())
            updateCrashContext()
        }
    }

    fun setEnableHwMipmapping(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(enableHwMipmapping = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setEnableHwMipmapping(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "hw_mipmap", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setAntiBlur(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(antiBlur = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setAntiBlur(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "pcrtc_antiblur", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setCpuSpriteRenderSize(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(cpuSpriteRenderSize = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setCpuSpriteRenderSize(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_CPUSpriteRenderBW", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setCpuSpriteRenderLevel(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(cpuSpriteRenderLevel = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setCpuSpriteRenderLevel(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_CPUSpriteRenderLevel", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setSoftwareClutRender(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(softwareClutRender = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setSoftwareClutRender(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_CPUCLUTRender", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setGpuTargetClutMode(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(gpuTargetClutMode = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setGpuTargetClutMode(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_GPUTargetCLUTMode", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setSkipDrawStart(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(skipDrawStart = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setSkipDrawStart(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_SkipDraw_Start", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setSkipDrawEnd(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(skipDrawEnd = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setSkipDrawEnd(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_SkipDraw_End", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setAutoFlushHardware(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(autoFlushHardware = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setAutoFlushHardware(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_AutoFlushLevel", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setCpuFramebufferConversion(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(cpuFramebufferConversion = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setCpuFramebufferConversion(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_CPU_FB_Conversion", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setDisableDepthConversion(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(disableDepthConversion = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setDisableDepthConversion(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_DisableDepthSupport", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setDisableSafeFeatures(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(disableSafeFeatures = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setDisableSafeFeatures(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_Disable_Safe_Features", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setDisableRenderFixes(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(disableRenderFixes = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setDisableRenderFixes(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_DisableRenderFixes", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setPreloadFrameData(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(preloadFrameData = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setPreloadFrameData(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "preload_frame_with_gs_data", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setDisablePartialInvalidation(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(disablePartialInvalidation = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setDisablePartialInvalidation(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_DisablePartialInvalidation", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setTextureInsideRt(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(textureInsideRt = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setTextureInsideRt(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_TextureInsideRt", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setReadTargetsOnClose(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(readTargetsOnClose = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setReadTargetsOnClose(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_ReadTCOnClose", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setEstimateTextureRegion(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(estimateTextureRegion = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setEstimateTextureRegion(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_EstimateTextureRegion", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setGpuPaletteConversion(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(gpuPaletteConversion = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setGpuPaletteConversion(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "paltex", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setHalfPixelOffset(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(halfPixelOffset = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setHalfPixelOffset(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_HalfPixelOffset", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setNativeScaling(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(nativeScaling = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setNativeScaling(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_native_scaling", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setRoundSprite(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(roundSprite = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setRoundSprite(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_round_sprite_offset", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setBilinearUpscale(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(bilinearUpscale = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setBilinearUpscale(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_BilinearHack", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setTextureOffsetX(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(textureOffsetX = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setTextureOffsetX(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_TCOffsetX", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setTextureOffsetY(value: Int) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(textureOffsetY = value)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setTextureOffsetY(value)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_TCOffsetY", "int", value.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setAlignSprite(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(alignSprite = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setAlignSprite(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_align_sprite_X", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setMergeSprite(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(mergeSprite = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setMergeSprite(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_merge_pp_sprite", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setForceEvenSpritePosition(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(forceEvenSpritePosition = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setForceEvenSpritePosition(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_ForceEvenSpritePosition", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setNativePaletteDraw(enabled: Boolean) {
        viewModelScope.launch {
            val newState = markPerformancePresetCustom(_uiState.value).copy(nativePaletteDraw = enabled)
            persistRuntimeState(newState) {
                preferences.setPerformancePreset(PerformancePresets.CUSTOM)
                preferences.setNativePaletteDraw(enabled)
            }
            EmulatorBridge.setSetting("EmuCore/GS", "UserHacks_NativePaletteDraw", "bool", enabled.toString())
            refreshManualHardwareFixes(newState)
            updateCrashContext()
        }
    }

    fun setEnableIcacheEmulation(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(enableIcacheEmulation = enabled)
            persistRuntimeState(newState) {
                preferences.setEnableIcacheEmulation(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/CPU", "enableIcacheEmulation", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setEnableDisableStalls(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(enableDisableStalls = enabled)
            persistRuntimeState(newState) {
                preferences.setEnableDisableStalls(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/CPU", "enableDisableStalls", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setEnablePreciseExceptions(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(enablePreciseExceptions = enabled)
            persistRuntimeState(newState) {
                preferences.setEnablePreciseExceptions(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/CPU", "enablePreciseExceptions", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setEnableTurboCd(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(enableTurboCd = enabled)
            persistRuntimeState(newState) {
                preferences.setEnableTurboCd(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/CPU", "enableTurboCd", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setCdReadAhead(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(0, 333000)
            val newState = _uiState.value.copy(cdReadAhead = clamped)
            persistRuntimeState(newState) {
                preferences.setCdReadAhead(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/CPU", "cdReadAhead", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setEnableCddaAudio(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(enableCddaAudio = enabled)
            persistRuntimeState(newState) {
                preferences.setEnableCddaAudio(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/Audio", "enableCddaAudio", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setEnableXaDecoding(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(enableXaDecoding = enabled)
            persistRuntimeState(newState) {
                preferences.setEnableXaDecoding(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/Audio", "enableXaDecoding", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setEnableSpuReverb(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(enableSpuReverb = enabled)
            persistRuntimeState(newState) {
                preferences.setEnableSpuReverb(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/Audio", "enableSpuReverb", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setEnableSpuThread(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(enableSpuThread = enabled)
            persistRuntimeState(newState) {
                preferences.setEnableSpuThread(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/Audio", "enableSpuThread", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setSpuTempo(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(0, 5)
            val newState = _uiState.value.copy(spuTempo = clamped)
            persistRuntimeState(newState) {
                preferences.setSpuTempo(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/Audio", "spuTempo", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setAudioVolume(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(AudioDefaults.VOLUME_MIN, AudioDefaults.VOLUME_MAX)
            val newState = _uiState.value.copy(audioVolume = clamped)
            persistRuntimeState(newState) {
                preferences.setAudioVolume(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/Audio", "Volume", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setAudioMuted(muted: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(audioMuted = muted)
            persistRuntimeState(newState) {
                preferences.setAudioMuted(muted)
            }
            EmulatorBridge.setSetting("EmuCoreR/Audio", "Mute", "bool", muted.toString())
            updateCrashContext()
        }
    }

    fun setNeonEnhancement(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(
                neonEnhancement = enabled,
                upscale = if (enabled) 2f else 1f
            )
            persistRuntimeState(newState) {
                preferences.setNeonEnhancement(enabled)
                preferences.setUpscaleMultiplier(if (enabled) 2f else 1f)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "neonEnhancement", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setNeonEnhancementSpeedHack(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(neonEnhancementSpeedHack = enabled)
            persistRuntimeState(newState) {
                preferences.setNeonEnhancementSpeedHack(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "neonEnhancementSpeedHack", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setNeonEnhancementTexAdj(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(neonEnhancementTexAdj = enabled)
            persistRuntimeState(newState) {
                preferences.setNeonEnhancementTexAdj(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "neonEnhancementTexAdj", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setNeonInterlace(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(-1, 1)
            val newState = _uiState.value.copy(neonInterlace = clamped)
            persistRuntimeState(newState) {
                preferences.setNeonInterlace(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "neonInterlace", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setGpuThreadRendering(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(-1, 1)
            val newState = _uiState.value.copy(gpuThreadRendering = clamped)
            persistRuntimeState(newState) {
                preferences.setGpuThreadRendering(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "gpuThreadRendering", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setShowOverscan(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(showOverscan = enabled)
            persistRuntimeState(newState) {
                preferences.setShowOverscan(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "showOverscan", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setScreenCentering(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(0, 3)
            val newState = _uiState.value.copy(screenCentering = clamped)
            persistRuntimeState(newState) {
                preferences.setScreenCentering(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "screenCentering", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setScreenCenteringX(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(-16, 16)
            val newState = _uiState.value.copy(screenCenteringX = clamped)
            persistRuntimeState(newState) {
                preferences.setScreenCenteringX(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "screenCenteringX", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setScreenCenteringY(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(-16, 16)
            val newState = _uiState.value.copy(screenCenteringY = clamped)
            persistRuntimeState(newState) {
                preferences.setScreenCenteringY(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "screenCenteringY", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setScreenCenteringHAdj(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(-64, 0)
            val newState = _uiState.value.copy(screenCenteringHAdj = clamped)
            persistRuntimeState(newState) {
                preferences.setScreenCenteringHAdj(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "screenCenteringHAdj", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setEnableFractionalFramerate(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(enableFractionalFramerate = enabled)
            persistRuntimeState(newState) {
                preferences.setEnableFractionalFramerate(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "enableFractionalFramerate", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setAltFlipMode(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(0, 2)
            val newState = _uiState.value.copy(altFlipMode = clamped)
            persistRuntimeState(newState) {
                preferences.setAltFlipMode(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "altFlipMode", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setEnableRgb32Output(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(enableRgb32Output = enabled)
            persistRuntimeState(newState) {
                preferences.setEnableRgb32Output(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "enableRgb32Output", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setEnableScaleHires(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(enableScaleHires = enabled)
            persistRuntimeState(newState) {
                preferences.setEnableScaleHires(enabled)
            }
            EmulatorBridge.setSetting("EmuCoreR/GPU", "enableScaleHires", "bool", enabled.toString())
            updateCrashContext()
        }
    }

    fun setMultitapMode(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(0, 3)
            val newState = _uiState.value.copy(multitapMode = clamped)
            persistRuntimeState(newState) {
                preferences.setMultitapMode(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/Input", "multitapMode", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setAnalogAxisModifier(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(0, 1)
            val newState = _uiState.value.copy(analogAxisModifier = clamped)
            persistRuntimeState(newState) {
                preferences.setAnalogAxisModifier(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/Input", "analogAxisModifier", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setDualshockToggleCombo(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(0, 5)
            val newState = _uiState.value.copy(dualshockToggleCombo = clamped)
            persistRuntimeState(newState) {
                preferences.setDualshockToggleCombo(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreR/Input", "dualshockToggleCombo", "int", clamped.toString())
            updateCrashContext()
        }
    }

    private fun markPerformancePresetCustom(state: EmulationUiState): EmulationUiState {
        return if (state.performancePreset == PerformancePresets.CUSTOM) {
            state
        } else {
            state.copy(performancePreset = PerformancePresets.CUSTOM)
        }
    }

    private suspend fun refreshManualHardwareFixes(state: EmulationUiState = _uiState.value) {
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

    private fun applyOverlayLayoutSnapshot(snapshot: OverlayLayoutSnapshot) {
        val overlayState = _uiState.value.withOverlayLayoutSnapshot(snapshot)
        _uiState.value = currentTouchControlsLayoutProfile?.let { overlayState.withTouchControlsLayout(it) } ?: overlayState
    }

    private fun EmulationUiState.withOverlayLayoutSnapshot(snapshot: OverlayLayoutSnapshot): EmulationUiState {
        return copy(
            overlayScale = snapshot.overlayScale,
            overlayOpacity = snapshot.overlayOpacity,
            hideOverlayOnGamepad = snapshot.hideOverlayOnGamepad,
            dpadOffset = snapshot.dpadOffset,
            lstickOffset = snapshot.lstickOffset,
            rstickOffset = snapshot.rstickOffset,
            actionOffset = snapshot.actionOffset,
            lbtnOffset = snapshot.lbtnOffset,
            rbtnOffset = snapshot.rbtnOffset,
            centerOffset = snapshot.centerOffset,
            stickScale = snapshot.stickScale,
            leftStickSensitivity = snapshot.leftStickSensitivity,
            rightStickSensitivity = snapshot.rightStickSensitivity,
            invertLeftStick = snapshot.invertLeftStick,
            invertRightStick = snapshot.invertRightStick,
            invertLeftStickHorizontal = snapshot.invertLeftStickHorizontal,
            invertRightStickHorizontal = snapshot.invertRightStickHorizontal,
            stickSurfaceMode = snapshot.stickSurfaceMode,
            controlLayouts = snapshot.controlLayouts
        )
    }

    private fun EmulationUiState.withTouchControlsLayout(profile: TouchControlsLayoutProfile): EmulationUiState {
        return copy(
            dpadOffset = profile.dpadOffset,
            lstickOffset = profile.lstickOffset,
            rstickOffset = profile.rstickOffset,
            actionOffset = profile.actionOffset,
            lbtnOffset = profile.lbtnOffset,
            rbtnOffset = profile.rbtnOffset,
            centerOffset = profile.centerOffset,
            stickScale = profile.stickScale,
            controlLayouts = profile.controlLayouts
        )
    }

    private fun EmulationUiState.toTouchControlsLayoutProfile(): TouchControlsLayoutProfile {
        return TouchControlsLayoutProfile(
            dpadOffset = dpadOffset,
            lstickOffset = lstickOffset,
            rstickOffset = rstickOffset,
            actionOffset = actionOffset,
            lbtnOffset = lbtnOffset,
            rbtnOffset = rbtnOffset,
            centerOffset = centerOffset,
            stickScale = stickScale,
            controlLayouts = controlLayouts
        )
    }

    private fun currentGameSubtitle(): String = buildList {
        currentGameSerial.takeIf { it.isNotBlank() }?.let(::add)
        currentGameRegionLabel.takeIf { it.isNotBlank() }?.let(::add)
        currentGameCrc.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("  /  ")

    /** Recovers a serial from the title when the dump filename has none. */
    private fun resolveSerialFromTitle(title: String, path: String): String? {
        return Ps1TitleIndexRepository(getApplication())
            .serialForTitle(title, filenameRegionHint(path))
            ?.let(::formatDiscSerial)
    }

    private fun formatDiscSerial(serial: String): String {
        val compact = serial.uppercase().replace(Regex("[^A-Z0-9]"), "")
        return if (compact.length >= 8) "${compact.take(4)}-${compact.substring(4)}" else serial
    }

    private fun resolveRegionLabel(serial: String, path: String): String {
        return serialRegionLabel(serial) ?: filenameRegionLabel(path).orEmpty()
    }

    private fun serialRegionLabel(serial: String): String? {
        val prefix = serial.uppercase().replace(Regex("[^A-Z0-9]"), "").take(4)
        if (prefix.length < 4) return null
        return when (prefix) {
            "SCUS", "SLUS", "LSP", "SCPS", "SLPS", "SLPM", "SCPM",
            "PAPX", "PBPX", "ESPM", "PCPX" -> "NTSC"
            "SCES", "SLES", "SCED", "SLED" -> "PAL"
            else -> null
        }
    }

    private fun filenameRegionHint(path: String?): Char? = when (filenameRegionLabel(path)) {
        "NTSC" -> 'U'
        "PAL" -> 'E'
        else -> null
    }

    private fun filenameRegionLabel(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val name = if (path.startsWith("content://")) {
            DocumentPathResolver.getDisplayName(getApplication(), path)
        } else {
            File(path).name
        }
        val lower = name.lowercase()
        return when {
            "usa" in lower || "(u)" in lower || "ntsc-u" in lower || "us " in lower -> "NTSC"
            "japan" in lower || "jpn" in lower || "(j)" in lower || "ntsc-j" in lower -> "NTSC"
            "europe" in lower || "eur" in lower || "(e)" in lower || "pal" in lower -> "PAL"
            else -> null
        }
    }

    private fun activePerGameKey(): String? = currentGamePath?.takeIf { it.isNotBlank() }

    private fun resolvePerGameTitle(state: EmulationUiState): String {
        return state.currentGameTitle
            .takeIf { it.isNotBlank() && it != "PlayStation BIOS" }
            ?: currentGameTitle.takeIf { it.isNotBlank() && it != "PlayStation BIOS" }
            ?: activePerGameKey()?.let { DocumentPathResolver.getDisplayName(getApplication(), it).substringBeforeLast('.') }
            ?: "Unknown Game"
    }

    private suspend fun persistGlobalTouchControlsLayout(state: EmulationUiState) {
        preferences.saveTouchControlsLayout(state.toTouchControlsLayoutProfile())
    }

    private suspend fun persistTouchControlsLayout(updatedState: EmulationUiState): EmulationUiState {
        val gameKey = activePerGameKey()
        return if (gameKey != null) {
            val layout = updatedState.toTouchControlsLayoutProfile()
            val existing = perGameSettingsRepository.get(gameKey)
            perGameSettingsRepository.save(
                existing.withTouchControlsLayout(
                    gameKey = gameKey,
                    gameTitle = resolvePerGameTitle(updatedState),
                    gameSerial = currentGameSerial.takeIf { it.isNotBlank() },
                    layout = layout
                )
            )
            currentTouchControlsLayoutProfile = layout
            val finalState = updatedState.copy(gameSettingsProfileActive = true)
            _uiState.value = finalState
            finalState
        } else {
            persistGlobalTouchControlsLayout(updatedState)
            currentTouchControlsLayoutProfile = null
            _uiState.value = updatedState
            updatedState
        }
    }

    private suspend fun resetTouchControlsLayoutForCurrentScope() {
        val gameKey = activePerGameKey()
        if (gameKey == null) {
            currentTouchControlsLayoutProfile = null
            preferences.resetControlsLayout()
            _uiState.value = _uiState.value.withOverlayLayoutSnapshot(preferences.overlayLayoutSnapshot.first())
            return
        }

        val existing = perGameSettingsRepository.get(gameKey)
        if (existing != null) {
            val updated = existing.withoutTouchControlsLayout()
            if (updated == null) {
                perGameSettingsRepository.delete(gameKey)
            } else {
                perGameSettingsRepository.save(updated)
            }
        }

        currentTouchControlsLayoutProfile = null
        val profileStillActive = perGameSettingsRepository.get(gameKey) != null
        _uiState.value = _uiState.value
            .withOverlayLayoutSnapshot(preferences.overlayLayoutSnapshot.first())
            .copy(gameSettingsProfileActive = profileStillActive)
    }

    private suspend fun persistRuntimeState(
        updatedState: EmulationUiState,
        persistGlobal: suspend () -> Unit = {}
    ): EmulationUiState {
        val gameKey = activePerGameKey()
        return if (gameKey != null) {
            val existingProfile = perGameSettingsRepository.get(gameKey)
            val touchControlsLayout = existingProfile?.touchControlsLayout ?: currentTouchControlsLayoutProfile
            val runtimeProfile = updatedState.toPerGameSettings(
                gameKey = gameKey,
                gameTitle = resolvePerGameTitle(updatedState),
                gameSerial = currentGameSerial.takeIf { it.isNotBlank() }
            )
            val visualStyleOverride = existingProfile?.touchControlVisualStyle
                ?: runtimeProfile.touchControlVisualStyle
            val pressEffectOverride = existingProfile?.touchControlPressEffect
                ?: runtimeProfile.touchControlPressEffect
            val driverOverrideKeys = when {
                existingProfile == null -> emptySet()
                existingProfile.providedKeys == null -> PER_GAME_GPU_DRIVER_KEYS
                else -> existingProfile.providedKeys.intersect(PER_GAME_GPU_DRIVER_KEYS)
            }
            val visualOverrideKeys = buildSet {
                if (visualStyleOverride != null) add("touchControlVisualStyle")
                if (pressEffectOverride != null) add("touchControlPressEffect")
            }
            val audioOverrideKeys = existingProfile?.let { profile ->
                if (profile.providedKeys == null) {
                    PER_GAME_AUDIO_KEYS
                } else {
                    profile.providedKeys.intersect(PER_GAME_AUDIO_KEYS)
                }
            }.orEmpty()
            val providedKeys = when {
                runtimeProfile.providedKeys == null -> null
                touchControlsLayout == null ->
                    runtimeProfile.providedKeys + visualOverrideKeys + driverOverrideKeys + audioOverrideKeys
                else -> runtimeProfile.providedKeys + visualOverrideKeys + driverOverrideKeys +
                    audioOverrideKeys + PER_GAME_TOUCH_CONTROLS_LAYOUT_KEY
            }
            perGameSettingsRepository.save(
                runtimeProfile.copy(
                    touchControlsLayout = touchControlsLayout,
                    touchControlVisualStyle = visualStyleOverride,
                    touchControlPressEffect = pressEffectOverride,
                    coreOptions = existingProfile?.coreOptions ?: runtimeProfile.coreOptions,
                    gpuDriverType = existingProfile?.gpuDriverType ?: runtimeProfile.gpuDriverType,
                    customDriverPath = existingProfile?.customDriverPath ?: runtimeProfile.customDriverPath,
                    mediatekAngleOpenGl = existingProfile?.mediatekAngleOpenGl ?: runtimeProfile.mediatekAngleOpenGl,
                    shaderChainOverrideEnabled = existingProfile?.shaderChainOverrideEnabled,
                    shaderChainPreset = existingProfile?.shaderChainPreset.orEmpty(),
                    audioVolume = if ("audioVolume" in audioOverrideKeys) {
                        existingProfile?.audioVolume ?: runtimeProfile.audioVolume
                    } else {
                        runtimeProfile.audioVolume
                    },
                    audioMuted = if ("audioMuted" in audioOverrideKeys) {
                        existingProfile?.audioMuted ?: runtimeProfile.audioMuted
                    } else {
                        runtimeProfile.audioMuted
                    },
                    audioOutputLatencyMs = if ("audioOutputLatencyMs" in audioOverrideKeys) {
                        existingProfile?.audioOutputLatencyMs ?: runtimeProfile.audioOutputLatencyMs
                    } else {
                        runtimeProfile.audioOutputLatencyMs
                    },
                    audioMinimalOutputLatency = if ("audioMinimalOutputLatency" in audioOverrideKeys) {
                        existingProfile?.audioMinimalOutputLatency ?: runtimeProfile.audioMinimalOutputLatency
                    } else {
                        runtimeProfile.audioMinimalOutputLatency
                    },
                    providedKeys = providedKeys
                )
            )
            val finalState = updatedState.copy(gameSettingsProfileActive = true)
            _uiState.value = finalState
            syncNativePerformanceOverlayState(finalState)
            finalState
        } else {
            persistGlobal()
            _uiState.value = updatedState
            syncNativePerformanceOverlayState(updatedState)
            updatedState
        }
    }

    private fun syncCurrentGameProfileMetadata() {
        val gameKey = activePerGameKey() ?: return
        val profile = perGameSettingsRepository.get(gameKey) ?: return
        val resolvedTitle = resolvePerGameTitle(_uiState.value)
        val serial = currentGameSerial.takeIf { it.isNotBlank() }
        if (profile.gameTitle != resolvedTitle || profile.gameSerial != serial) {
            perGameSettingsRepository.save(
                profile.copy(
                    gameTitle = resolvedTitle,
                    gameSerial = serial
                )
            )
        }
    }

    private fun resetCurrentGameProfile() {
        val gameKey = activePerGameKey() ?: return
        perGameSettingsRepository.delete(gameKey)
        currentTouchControlsLayoutProfile = null
        GamepadManager.clearPerGameOverrides()
        // Restore the global/core-default value of any option that was overridden
        // only by this game, so the live core matches the reset profile.
        pendingPerGameCoreOptions.keys.forEach { key ->
            NativeApp.getCoreOption(key)?.let { baseValue -> NativeApp.applyCoreOption(key, baseValue) }
        }
        pendingPerGameCoreOptions = emptyMap()
        _uiState.value = _uiState.value.copy(
            gameSettingsProfileActive = false,
            perGameCoreOptions = emptyMap(),
            gamepadBindingsByPad = emptyMap()
        )
        viewModelScope.launch {
            val settings = preferences.settingsSnapshot.first()
            _uiState.value = _uiState.value
                .withOverlayLayoutSnapshot(preferences.overlayLayoutSnapshot.first())
                .copy(
                    touchControlVisualStyle = settings.touchControlVisualStyle,
                    touchControlPressEffect = settings.touchControlPressEffect,
                    gameSettingsProfileActive = false,
                    perGameCoreOptions = emptyMap(),
                    gamepadStickDeadzone = settings.gamepadStickDeadzone,
                    gamepadLeftStickSensitivity = settings.gamepadLeftStickSensitivity,
                    gamepadRightStickSensitivity = settings.gamepadRightStickSensitivity
                )
        }
    }

    private suspend fun loadLaunchConfig(): EmulationLaunchConfig {
        val profile = activePerGameKey()?.let(perGameSettingsRepository::get)
        val ensuredAssignments = memoryCardRepository.ensureDefaultCardsAssigned()
        val settings = preferences.settingsSnapshot.first()
        val profileConfig = PerformanceProfiles.configFor(settings.performanceProfile)
        val savedGpuDriverType = settings.gpuDriverType
        val savedCustomDriverPath = settings.customDriverPath
        // Custom driver support is gone with the CPU-rasterizer-only core; a
        // legacy custom path is only honored when the file still exists.
        val resolvedCustomDriverPath = savedCustomDriverPath
            ?.takeIf { savedGpuDriverType == 1 && File(it).isFile }
        val resolvedGpuDriverType = if (savedGpuDriverType == 1 && !resolvedCustomDriverPath.isNullOrBlank()) 1 else 0
        if (savedGpuDriverType == 1 && resolvedGpuDriverType == 1 && resolvedCustomDriverPath != savedCustomDriverPath) {
            preferences.setCustomDriverPath(resolvedCustomDriverPath)
        }
        val mergedConfig = EmulationLaunchConfig(
            performanceProfile = settings.performanceProfile,
            biosPath = settings.biosPath,
            emulatorDataPath = settings.emulatorDataPath,
            memoryCardSlot1 = ensuredAssignments.slot1,
            memoryCardSlot2 = ensuredAssignments.slot2,
            renderer = settings.renderer,
            upscaleMultiplier = settings.upscaleMultiplier,
            gpuDriverType = resolvedGpuDriverType,
            customDriverPath = resolvedCustomDriverPath,
            gpuHardwareProfile = settings.gpuHardwareProfile,
            mediatekAngleOpenGl = settings.mediatekAngleOpenGl,
            aspectRatio = settings.aspectRatio,
            localMultiplayerMode = settings.localMultiplayerMode,
            displayCrop = settings.displayCrop,
            audioVolume = settings.audioVolume,
            audioFastForwardVolume = settings.audioFastForwardVolume,
            audioMuted = settings.audioMuted,
            audioInterpolation = settings.audioInterpolation,
            audioSyncMode = settings.audioSyncMode,
            audioLightweightSpu2 = settings.audioLightweightSpu2,
            audioBackend = settings.audioBackend,
            audioBufferMs = settings.audioBufferMs,
            audioOutputLatencyMs = settings.audioOutputLatencyMs,
            audioMinimalOutputLatency = settings.audioMinimalOutputLatency,
            enableEeRecompiler = settings.enableEeRecompiler,
            enableIopRecompiler = settings.enableIopRecompiler,
            enableVu0Recompiler = settings.enableVu0Recompiler,
            enableVu1Recompiler = settings.enableVu1Recompiler,
            enableFastmem = settings.enableFastmem,
            eeFpuRoundMode = settings.eeFpuRoundMode,
            vu0RoundMode = settings.vu0RoundMode,
            vu1RoundMode = settings.vu1RoundMode,
            eeFpuClampingMode = settings.eeFpuClampingMode,
            vu0ClampingMode = settings.vu0ClampingMode,
            vu1ClampingMode = settings.vu1ClampingMode,
            enableGameFixes = settings.enableGameFixes,
            eeTimingHack = settings.enableEeTimingHack,
            waitLoopSpeedhack = settings.enableWaitLoopSpeedhack,
            intcStatSpeedhack = settings.enableIntcStatSpeedhack,
            vuFlagHack = settings.enableVuFlagHack,
            instantVu1 = settings.enableInstantVu1,
            mtvu = settings.enableMtvu,
            enableThreadPinning = settings.enableThreadPinning,
            fastCdvd = settings.enableFastCdvd,
            enableFastBoot = settings.enableFastBoot,
            enableCheats = settings.enableCheats,
            hwDownloadMode = settings.hwDownloadMode,
            eeCycleRate = settings.eeCycleRate,
            eeCycleSkip = settings.eeCycleSkip,
            frameSkip = settings.frameSkip,
            skipDuplicateFrames = settings.skipDuplicateFrames,
            frameLimitEnabled = settings.frameLimitEnabled,
            vSyncEnabled = settings.vSyncEnabled,
            fastForwardSpeed = settings.fastForwardSpeed,
            targetFps = settings.targetFps,
            ntscFramerate = settings.ntscFramerate,
            palFramerate = settings.palFramerate,
            textureFiltering = settings.textureFiltering,
            trilinearFiltering = settings.trilinearFiltering,
            blendingAccuracy = settings.blendingAccuracy,
            texturePreloading = settings.texturePreloading,
            shaderChainEnabled = settings.shaderChainEnabled,
            shaderChainPreset = settings.shaderChainPreset,
            enableFxaa = settings.enableFxaa,
            casMode = settings.casMode,
            sgsrMode = settings.sgsrMode,
            casSharpness = settings.casSharpness,
            tvShader = settings.tvShader,
            shadeBoostEnabled = settings.shadeBoostEnabled,
            shadeBoostBrightness = settings.shadeBoostBrightness,
            shadeBoostContrast = settings.shadeBoostContrast,
            shadeBoostSaturation = settings.shadeBoostSaturation,
            shadeBoostGamma = settings.shadeBoostGamma,
            deinterlaceMode = settings.deinterlaceMode,
            dithering = settings.dithering,
            anisotropicFiltering = settings.anisotropicFiltering,
            enableHwMipmapping = settings.enableHwMipmapping,
            antiBlur = settings.antiBlur,
            widescreenPatches = settings.enableWidescreenPatches,
            noInterlacingPatches = settings.enableNoInterlacingPatches,
            cpuSpriteRenderSize = settings.cpuSpriteRenderSize,
            cpuSpriteRenderLevel = settings.cpuSpriteRenderLevel,
            softwareClutRender = settings.softwareClutRender,
            gpuTargetClutMode = settings.gpuTargetClutMode,
            skipDrawStart = settings.skipDrawStart,
            skipDrawEnd = settings.skipDrawEnd,
            autoFlushHardware = settings.autoFlushHardware,
            cpuFramebufferConversion = settings.cpuFramebufferConversion,
            disableDepthConversion = settings.disableDepthConversion,
            disableSafeFeatures = settings.disableSafeFeatures,
            disableRenderFixes = settings.disableRenderFixes,
            preloadFrameData = settings.preloadFrameData,
            disablePartialInvalidation = settings.disablePartialInvalidation,
            textureInsideRt = settings.textureInsideRt,
            readTargetsOnClose = settings.readTargetsOnClose,
            estimateTextureRegion = settings.estimateTextureRegion,
            gpuPaletteConversion = settings.gpuPaletteConversion,
            halfPixelOffset = settings.halfPixelOffset,
            nativeScaling = settings.nativeScaling,
            roundSprite = settings.roundSprite,
            bilinearUpscale = settings.bilinearUpscale,
            textureOffsetX = settings.textureOffsetX,
            textureOffsetY = settings.textureOffsetY,
            alignSprite = settings.alignSprite,
            mergeSprite = settings.mergeSprite,
            forceEvenSpritePosition = settings.forceEvenSpritePosition,
            nativePaletteDraw = settings.nativePaletteDraw,
            pressureModifierAmount = settings.pressureModifierAmount,
            fpuCorrectAddSub = profileConfig.fpuCorrectAddSub,
            enableIcacheEmulation = settings.enableIcacheEmulation,
            enableDisableStalls = settings.enableDisableStalls,
            enablePreciseExceptions = settings.enablePreciseExceptions,
            enableTurboCd = settings.enableTurboCd,
            cdReadAhead = settings.cdReadAhead,
            enableCddaAudio = settings.enableCddaAudio,
            enableXaDecoding = settings.enableXaDecoding,
            enableSpuReverb = settings.enableSpuReverb,
            enableSpuThread = settings.enableSpuThread,
            spuTempo = settings.spuTempo,
            neonEnhancement = settings.neonEnhancement,
            neonEnhancementSpeedHack = settings.neonEnhancementSpeedHack,
            neonEnhancementTexAdj = settings.neonEnhancementTexAdj,
            neonInterlace = settings.neonInterlace,
            gpuThreadRendering = settings.gpuThreadRendering,
            showOverscan = settings.showOverscan,
            screenCentering = settings.screenCentering,
            screenCenteringX = settings.screenCenteringX,
            screenCenteringY = settings.screenCenteringY,
            screenCenteringHAdj = settings.screenCenteringHAdj,
            enableFractionalFramerate = settings.enableFractionalFramerate,
            altFlipMode = settings.altFlipMode,
            enableRgb32Output = settings.enableRgb32Output,
            enableScaleHires = settings.enableScaleHires,
            multitapMode = settings.multitapMode,
            analogAxisModifier = settings.analogAxisModifier,
            dualshockToggleCombo = settings.dualshockToggleCombo
        ).applyProfile(profile)
        val mergedDriverPath = mergedConfig.customDriverPath?.takeIf { File(it).isFile }
        return mergedConfig.copy(
            gpuDriverType = if (mergedConfig.gpuDriverType == 1 && !mergedDriverPath.isNullOrBlank()) 1 else 0,
            customDriverPath = mergedDriverPath
        )
    }

    private suspend fun loadLiveRuntimeSnapshot(): LiveRuntimeSnapshot {
        val profile = activePerGameKey()?.let(perGameSettingsRepository::get)
        val settings = preferences.settingsSnapshot.first()
        return LiveRuntimeSnapshot(
            showFps = settings.showFps,
            fpsOverlayMode = FPS_OVERLAY_MODE_SIMPLE,
            confirmSaveLoadActions = settings.confirmSaveLoadActions,
            backButtonExitsGame = settings.backButtonExitsGame,
            renderer = settings.renderer,
            upscale = settings.upscaleMultiplier,
            aspectRatio = settings.aspectRatio,
            localMultiplayerMode = settings.localMultiplayerMode,
            displayCrop = settings.displayCrop,
            performancePreset = settings.performancePreset,
            enableInstantVu1 = settings.enableInstantVu1,
            enableMtvu = settings.enableMtvu,
            enableThreadPinning = settings.enableThreadPinning,
            enableFastCdvd = settings.enableFastCdvd,
            enableFastBoot = settings.enableFastBoot,
            enableCheats = settings.enableCheats,
            hwDownloadMode = settings.hwDownloadMode,
            eeCycleRate = settings.eeCycleRate,
            eeCycleSkip = settings.eeCycleSkip,
            frameSkip = settings.frameSkip,
            skipDuplicateFrames = settings.skipDuplicateFrames,
            frameLimitEnabled = settings.frameLimitEnabled,
            fastForwardSpeed = settings.fastForwardSpeed,
            racingMode = settings.racingMode,
            touchscreenRightStick = settings.touchscreenRightStick,
            touchscreenRightStickSensitivity = settings.touchscreenRightStickSensitivity,
            touchHaptics = settings.touchHaptics,
            touchHapticsPreset = settings.touchHapticsPreset,
            touchHapticsStrength = settings.touchHapticsStrength,
            touchControlVisualStyle = settings.touchControlVisualStyle,
            touchControlPressEffect = settings.touchControlPressEffect,
            gyroMode = settings.gyroMode,
            gyroSensitivity = settings.gyroSensitivity,
            gyroSmoothing = settings.gyroSmoothing,
            gyroInvertX = settings.gyroInvertX,
            gyroInvertY = settings.gyroInvertY,
            gamepadRightStickUpToR2 = settings.gamepadRightStickUpToR2,
            gamepadRightStickDownToL2 = settings.gamepadRightStickDownToL2,
            gamepadButtonHaptics = settings.gamepadButtonHaptics,
            gamepadStickDeadzone = settings.gamepadStickDeadzone,
            gamepadLeftStickSensitivity = settings.gamepadLeftStickSensitivity,
            gamepadRightStickSensitivity = settings.gamepadRightStickSensitivity,
            gamepadBindingsByPad = settings.gamepadBindingsByPad,
            pressureModifierAmount = settings.pressureModifierAmount,
            autoSaveOnExit = false,
            autoLoadOnStart = false,
            targetFps = settings.targetFps,
            ntscFramerate = settings.ntscFramerate,
            palFramerate = settings.palFramerate,
            textureFiltering = settings.textureFiltering,
            trilinearFiltering = settings.trilinearFiltering,
            blendingAccuracy = settings.blendingAccuracy,
            texturePreloading = settings.texturePreloading,
            enableFxaa = settings.enableFxaa,
            casMode = settings.casMode,
            sgsrMode = settings.sgsrMode,
            casSharpness = settings.casSharpness,
            tvShader = settings.tvShader,
            shadeBoostEnabled = settings.shadeBoostEnabled,
            shadeBoostBrightness = settings.shadeBoostBrightness,
            shadeBoostContrast = settings.shadeBoostContrast,
            shadeBoostSaturation = settings.shadeBoostSaturation,
            shadeBoostGamma = settings.shadeBoostGamma,
            anisotropicFiltering = settings.anisotropicFiltering,
            enableHwMipmapping = settings.enableHwMipmapping,
            antiBlur = settings.antiBlur,
            deinterlaceMode = settings.deinterlaceMode,
            dithering = settings.dithering,
            widescreenPatches = settings.enableWidescreenPatches,
            noInterlacingPatches = settings.enableNoInterlacingPatches,
            cpuSpriteRenderSize = settings.cpuSpriteRenderSize,
            cpuSpriteRenderLevel = settings.cpuSpriteRenderLevel,
            softwareClutRender = settings.softwareClutRender,
            gpuTargetClutMode = settings.gpuTargetClutMode,
            skipDrawStart = settings.skipDrawStart,
            skipDrawEnd = settings.skipDrawEnd,
            autoFlushHardware = settings.autoFlushHardware,
            cpuFramebufferConversion = settings.cpuFramebufferConversion,
            disableDepthConversion = settings.disableDepthConversion,
            disableSafeFeatures = settings.disableSafeFeatures,
            disableRenderFixes = settings.disableRenderFixes,
            preloadFrameData = settings.preloadFrameData,
            disablePartialInvalidation = settings.disablePartialInvalidation,
            textureInsideRt = settings.textureInsideRt,
            readTargetsOnClose = settings.readTargetsOnClose,
            estimateTextureRegion = settings.estimateTextureRegion,
            gpuPaletteConversion = settings.gpuPaletteConversion,
            halfPixelOffset = settings.halfPixelOffset,
            nativeScaling = settings.nativeScaling,
            roundSprite = settings.roundSprite,
            bilinearUpscale = settings.bilinearUpscale,
            textureOffsetX = settings.textureOffsetX,
            textureOffsetY = settings.textureOffsetY,
            alignSprite = settings.alignSprite,
            mergeSprite = settings.mergeSprite,
            forceEvenSpritePosition = settings.forceEvenSpritePosition,
            nativePaletteDraw = settings.nativePaletteDraw,
            enableIcacheEmulation = settings.enableIcacheEmulation,
            enableDisableStalls = settings.enableDisableStalls,
            enablePreciseExceptions = settings.enablePreciseExceptions,
            enableTurboCd = settings.enableTurboCd,
            cdReadAhead = settings.cdReadAhead,
            enableCddaAudio = settings.enableCddaAudio,
            enableXaDecoding = settings.enableXaDecoding,
            enableSpuReverb = settings.enableSpuReverb,
            enableSpuThread = settings.enableSpuThread,
            spuTempo = settings.spuTempo,
            neonEnhancement = settings.neonEnhancement,
            neonEnhancementSpeedHack = settings.neonEnhancementSpeedHack,
            neonEnhancementTexAdj = settings.neonEnhancementTexAdj,
            neonInterlace = settings.neonInterlace,
            gpuThreadRendering = settings.gpuThreadRendering,
            showOverscan = settings.showOverscan,
            screenCentering = settings.screenCentering,
            screenCenteringX = settings.screenCenteringX,
            screenCenteringY = settings.screenCenteringY,
            screenCenteringHAdj = settings.screenCenteringHAdj,
            enableFractionalFramerate = settings.enableFractionalFramerate,
            altFlipMode = settings.altFlipMode,
            enableRgb32Output = settings.enableRgb32Output,
            enableScaleHires = settings.enableScaleHires,
            multitapMode = settings.multitapMode,
            analogAxisModifier = settings.analogAxisModifier,
            dualshockToggleCombo = settings.dualshockToggleCombo
        ).applyProfile(profile)
    }

    private fun EmulationLaunchConfig.applyProfile(profile: PerGameSettings?): EmulationLaunchConfig {
        if (profile == null) return this
        val resolvedShaderChain = profile.resolveShaderChain(
            globalEnabled = shaderChainEnabled,
            globalPreset = shaderChainPreset
        )
        fun <T> pick(key: String, current: T, value: PerGameSettings.() -> T): T {
            val keys = profile.providedKeys
            return if (keys == null || key in keys) profile.value() else current
        }
        return copy(
            renderer = pick("renderer", renderer) { renderer },
            gpuDriverType = pick("gpuDriverType", gpuDriverType) { gpuDriverType },
            customDriverPath = pick("customDriverPath", customDriverPath) { customDriverPath },
            mediatekAngleOpenGl = pick("mediatekAngleOpenGl", mediatekAngleOpenGl) { mediatekAngleOpenGl },
            upscaleMultiplier = pick("upscaleMultiplier", upscaleMultiplier) { upscaleMultiplier },
            aspectRatio = pick("aspectRatio", aspectRatio) { aspectRatio },
            localMultiplayerMode = pick("localMultiplayerMode", localMultiplayerMode) { localMultiplayerMode },
            displayCrop = pick("displayCrop", displayCrop) { displayCrop },
            instantVu1 = pick("enableInstantVu1", instantVu1) { enableInstantVu1 },
            mtvu = pick("enableMtvu", mtvu) { enableMtvu },
            enableThreadPinning = pick("enableThreadPinning", enableThreadPinning) { enableThreadPinning },
            fastCdvd = pick("enableFastCdvd", fastCdvd) { enableFastCdvd },
            enableFastBoot = pick("enableFastBoot", enableFastBoot) { enableFastBoot },
            enableCheats = pick("enableCheats", enableCheats) { enableCheats },
            enableGameFixes = pick("enableGameFixes", enableGameFixes) { enableGameFixes },
            eeTimingHack = pick("enableEeTimingHack", eeTimingHack) { enableEeTimingHack },
            eeFpuRoundMode = pick("eeFpuRoundMode", eeFpuRoundMode) { eeFpuRoundMode },
            vu0RoundMode = pick("vu0RoundMode", vu0RoundMode) { vu0RoundMode },
            vu1RoundMode = pick("vu1RoundMode", vu1RoundMode) { vu1RoundMode },
            eeFpuClampingMode = pick("eeFpuClampingMode", eeFpuClampingMode) { eeFpuClampingMode },
            vu0ClampingMode = pick("vu0ClampingMode", vu0ClampingMode) { vu0ClampingMode },
            vu1ClampingMode = pick("vu1ClampingMode", vu1ClampingMode) { vu1ClampingMode },
            hwDownloadMode = pick("hwDownloadMode", hwDownloadMode) { hwDownloadMode },
            eeCycleRate = pick("eeCycleRate", eeCycleRate) { eeCycleRate },
            eeCycleSkip = pick("eeCycleSkip", eeCycleSkip) { eeCycleSkip },
            frameSkip = pick("frameSkip", frameSkip) { frameSkip },
            skipDuplicateFrames = pick("skipDuplicateFrames", skipDuplicateFrames) { skipDuplicateFrames },
            frameLimitEnabled = pick("frameLimitEnabled", frameLimitEnabled) { frameLimitEnabled },
            targetFps = pick("targetFps", targetFps) { targetFps },
            ntscFramerate = pick("ntscFramerate", ntscFramerate) { ntscFramerate },
            palFramerate = pick("palFramerate", palFramerate) { palFramerate },
            textureFiltering = pick("textureFiltering", textureFiltering) { textureFiltering },
            trilinearFiltering = pick("trilinearFiltering", trilinearFiltering) { trilinearFiltering },
            blendingAccuracy = pick("blendingAccuracy", blendingAccuracy) { blendingAccuracy },
            texturePreloading = pick("texturePreloading", texturePreloading) { texturePreloading },
            shaderChainEnabled = resolvedShaderChain.enabled,
            shaderChainPreset = resolvedShaderChain.preset,
            enableFxaa = pick("enableFxaa", enableFxaa) { enableFxaa },
            casMode = pick("casMode", casMode) { casMode },
            sgsrMode = pick("sgsrMode", sgsrMode) { sgsrMode },
            casSharpness = pick("casSharpness", casSharpness) { casSharpness },
            tvShader = pick("tvShader", tvShader) { tvShader },
            shadeBoostEnabled = pick("shadeBoostEnabled", shadeBoostEnabled) { shadeBoostEnabled },
            shadeBoostBrightness = pick("shadeBoostBrightness", shadeBoostBrightness) { shadeBoostBrightness },
            shadeBoostContrast = pick("shadeBoostContrast", shadeBoostContrast) { shadeBoostContrast },
            shadeBoostSaturation = pick("shadeBoostSaturation", shadeBoostSaturation) { shadeBoostSaturation },
            shadeBoostGamma = pick("shadeBoostGamma", shadeBoostGamma) { shadeBoostGamma },
            deinterlaceMode = pick("deinterlaceMode", deinterlaceMode) { deinterlaceMode },
            dithering = pick("dithering", dithering) { dithering },
            anisotropicFiltering = pick("anisotropicFiltering", anisotropicFiltering) { anisotropicFiltering },
            enableHwMipmapping = pick("enableHwMipmapping", enableHwMipmapping) { enableHwMipmapping },
            antiBlur = pick("antiBlur", antiBlur) { antiBlur },
            widescreenPatches = pick("enableWidescreenPatches", widescreenPatches) { enableWidescreenPatches },
            noInterlacingPatches = pick("enableNoInterlacingPatches", noInterlacingPatches) { enableNoInterlacingPatches },
            cpuSpriteRenderSize = pick("cpuSpriteRenderSize", cpuSpriteRenderSize) { cpuSpriteRenderSize },
            cpuSpriteRenderLevel = pick("cpuSpriteRenderLevel", cpuSpriteRenderLevel) { cpuSpriteRenderLevel },
            softwareClutRender = pick("softwareClutRender", softwareClutRender) { softwareClutRender },
            gpuTargetClutMode = pick("gpuTargetClutMode", gpuTargetClutMode) { gpuTargetClutMode },
            skipDrawStart = pick("skipDrawStart", skipDrawStart) { skipDrawStart },
            skipDrawEnd = pick("skipDrawEnd", skipDrawEnd) { skipDrawEnd },
            autoFlushHardware = pick("autoFlushHardware", autoFlushHardware) { autoFlushHardware },
            cpuFramebufferConversion = pick("cpuFramebufferConversion", cpuFramebufferConversion) { cpuFramebufferConversion },
            disableDepthConversion = pick("disableDepthConversion", disableDepthConversion) { disableDepthConversion },
            disableSafeFeatures = pick("disableSafeFeatures", disableSafeFeatures) { disableSafeFeatures },
            disableRenderFixes = pick("disableRenderFixes", disableRenderFixes) { disableRenderFixes },
            preloadFrameData = pick("preloadFrameData", preloadFrameData) { preloadFrameData },
            disablePartialInvalidation = pick("disablePartialInvalidation", disablePartialInvalidation) { disablePartialInvalidation },
            textureInsideRt = pick("textureInsideRt", textureInsideRt) { textureInsideRt },
            readTargetsOnClose = pick("readTargetsOnClose", readTargetsOnClose) { readTargetsOnClose },
            estimateTextureRegion = pick("estimateTextureRegion", estimateTextureRegion) { estimateTextureRegion },
            gpuPaletteConversion = pick("gpuPaletteConversion", gpuPaletteConversion) { gpuPaletteConversion },
            halfPixelOffset = pick("halfPixelOffset", halfPixelOffset) { halfPixelOffset },
            nativeScaling = pick("nativeScaling", nativeScaling) { nativeScaling },
            roundSprite = pick("roundSprite", roundSprite) { roundSprite },
            bilinearUpscale = pick("bilinearUpscale", bilinearUpscale) { bilinearUpscale },
            textureOffsetX = pick("textureOffsetX", textureOffsetX) { textureOffsetX },
            textureOffsetY = pick("textureOffsetY", textureOffsetY) { textureOffsetY },
            alignSprite = pick("alignSprite", alignSprite) { alignSprite },
            mergeSprite = pick("mergeSprite", mergeSprite) { mergeSprite },
            forceEvenSpritePosition = pick("forceEvenSpritePosition", forceEvenSpritePosition) { forceEvenSpritePosition },
            pressureModifierAmount = pick("pressureModifierAmount", pressureModifierAmount) { pressureModifierAmount },
            audioVolume = pick("audioVolume", audioVolume) { audioVolume },
            audioMuted = pick("audioMuted", audioMuted) { audioMuted },
            audioOutputLatencyMs = pick("audioOutputLatencyMs", audioOutputLatencyMs) { audioOutputLatencyMs },
            audioMinimalOutputLatency = pick("audioMinimalOutputLatency", audioMinimalOutputLatency) { audioMinimalOutputLatency },
            enableIcacheEmulation = pick("enableIcacheEmulation", enableIcacheEmulation) { enableIcacheEmulation },
            enableDisableStalls = pick("enableDisableStalls", enableDisableStalls) { enableDisableStalls },
            enablePreciseExceptions = pick("enablePreciseExceptions", enablePreciseExceptions) { enablePreciseExceptions },
            enableTurboCd = pick("enableTurboCd", enableTurboCd) { enableTurboCd },
            cdReadAhead = pick("cdReadAhead", cdReadAhead) { cdReadAhead },
            enableCddaAudio = pick("enableCddaAudio", enableCddaAudio) { enableCddaAudio },
            enableXaDecoding = pick("enableXaDecoding", enableXaDecoding) { enableXaDecoding },
            enableSpuReverb = pick("enableSpuReverb", enableSpuReverb) { enableSpuReverb },
            enableSpuThread = pick("enableSpuThread", enableSpuThread) { enableSpuThread },
            spuTempo = pick("spuTempo", spuTempo) { spuTempo },
            neonEnhancement = pick("neonEnhancement", neonEnhancement) { neonEnhancement },
            neonEnhancementSpeedHack = pick("neonEnhancementSpeedHack", neonEnhancementSpeedHack) { neonEnhancementSpeedHack },
            neonEnhancementTexAdj = pick("neonEnhancementTexAdj", neonEnhancementTexAdj) { neonEnhancementTexAdj },
            neonInterlace = pick("neonInterlace", neonInterlace) { neonInterlace },
            gpuThreadRendering = pick("gpuThreadRendering", gpuThreadRendering) { gpuThreadRendering },
            showOverscan = pick("showOverscan", showOverscan) { showOverscan },
            screenCentering = pick("screenCentering", screenCentering) { screenCentering },
            screenCenteringX = pick("screenCenteringX", screenCenteringX) { screenCenteringX },
            screenCenteringY = pick("screenCenteringY", screenCenteringY) { screenCenteringY },
            screenCenteringHAdj = pick("screenCenteringHAdj", screenCenteringHAdj) { screenCenteringHAdj },
            enableFractionalFramerate = pick("enableFractionalFramerate", enableFractionalFramerate) { enableFractionalFramerate },
            altFlipMode = pick("altFlipMode", altFlipMode) { altFlipMode },
            enableRgb32Output = pick("enableRgb32Output", enableRgb32Output) { enableRgb32Output },
            enableScaleHires = pick("enableScaleHires", enableScaleHires) { enableScaleHires },
            multitapMode = pick("multitapMode", multitapMode) { multitapMode },
            analogAxisModifier = pick("analogAxisModifier", analogAxisModifier) { analogAxisModifier },
            dualshockToggleCombo = pick("dualshockToggleCombo", dualshockToggleCombo) { dualshockToggleCombo }
        )
    }

    private fun LiveRuntimeSnapshot.applyProfile(profile: PerGameSettings?): LiveRuntimeSnapshot {
        if (profile == null) return this
        fun <T> pick(key: String, current: T, value: PerGameSettings.() -> T): T {
            val keys = profile.providedKeys
            return if (keys == null || key in keys) profile.value() else current
        }
        return copy(
            showFps = pick("showFps", showFps) { showFps },
            fpsOverlayMode = FPS_OVERLAY_MODE_SIMPLE,
            racingMode = pick("racingMode", racingMode) { racingMode },
            touchscreenRightStick = pick("touchscreenRightStick", touchscreenRightStick) { touchscreenRightStick },
            touchscreenRightStickSensitivity = pick(
                "touchscreenRightStickSensitivity",
                touchscreenRightStickSensitivity
            ) { touchscreenRightStickSensitivity },
            touchHaptics = pick("touchHaptics", touchHaptics) { touchHaptics },
            touchHapticsPreset = pick("touchHapticsPreset", touchHapticsPreset) { touchHapticsPreset },
            touchHapticsStrength = touchHapticsStrength,
            touchControlVisualStyle = profile.touchControlVisualStyle ?: touchControlVisualStyle,
            touchControlPressEffect = profile.touchControlPressEffect ?: touchControlPressEffect,
            gyroMode = pick("gyroMode", gyroMode) { gyroMode },
            gyroSensitivity = pick("gyroSensitivity", gyroSensitivity) { gyroSensitivity },
            gyroSmoothing = pick("gyroSmoothing", gyroSmoothing) { gyroSmoothing },
            gyroInvertX = pick("gyroInvertX", gyroInvertX) { gyroInvertX },
            gyroInvertY = pick("gyroInvertY", gyroInvertY) { gyroInvertY },
            gamepadRightStickUpToR2 = pick("gamepadRightStickUpToR2", gamepadRightStickUpToR2) { gamepadRightStickUpToR2 },
            gamepadRightStickDownToL2 = pick("gamepadRightStickDownToL2", gamepadRightStickDownToL2) { gamepadRightStickDownToL2 },
            gamepadButtonHaptics = pick("gamepadButtonHaptics", gamepadButtonHaptics) { gamepadButtonHaptics },
            gamepadStickDeadzone = pick("gamepadStickDeadzone", gamepadStickDeadzone) { gamepadStickDeadzone },
            gamepadLeftStickSensitivity = pick("gamepadLeftStickSensitivity", gamepadLeftStickSensitivity) { gamepadLeftStickSensitivity },
            gamepadRightStickSensitivity = pick("gamepadRightStickSensitivity", gamepadRightStickSensitivity) { gamepadRightStickSensitivity },
            gamepadBindingsByPad = if (profile.providedKeys == null || "gamepadBindingsByPad" in profile.providedKeys) profile.gamepadBindingsByPad else gamepadBindingsByPad,
            pressureModifierAmount = pick("pressureModifierAmount", pressureModifierAmount) { pressureModifierAmount },
            autoSaveOnExit = pick("autoSaveOnExit", autoSaveOnExit) { autoSaveOnExit },
            autoLoadOnStart = pick("autoLoadOnStart", autoLoadOnStart) { autoLoadOnStart },
            renderer = pick("renderer", renderer) { renderer },
            upscale = pick("upscaleMultiplier", upscale) { upscaleMultiplier },
            aspectRatio = pick("aspectRatio", aspectRatio) { aspectRatio },
            localMultiplayerMode = pick("localMultiplayerMode", localMultiplayerMode) { localMultiplayerMode },
            displayCrop = pick("displayCrop", displayCrop) { displayCrop },
            enableInstantVu1 = pick("enableInstantVu1", enableInstantVu1) { enableInstantVu1 },
            enableMtvu = pick("enableMtvu", enableMtvu) { enableMtvu },
            enableThreadPinning = pick("enableThreadPinning", enableThreadPinning) { enableThreadPinning },
            enableFastCdvd = pick("enableFastCdvd", enableFastCdvd) { enableFastCdvd },
            enableFastBoot = pick("enableFastBoot", enableFastBoot) { enableFastBoot },
            enableCheats = pick("enableCheats", enableCheats) { enableCheats },
            hwDownloadMode = pick("hwDownloadMode", hwDownloadMode) { hwDownloadMode },
            eeCycleRate = pick("eeCycleRate", eeCycleRate) { eeCycleRate },
            eeCycleSkip = pick("eeCycleSkip", eeCycleSkip) { eeCycleSkip },
            frameSkip = pick("frameSkip", frameSkip) { frameSkip },
            skipDuplicateFrames = pick("skipDuplicateFrames", skipDuplicateFrames) { skipDuplicateFrames },
            frameLimitEnabled = pick("frameLimitEnabled", frameLimitEnabled) { frameLimitEnabled },
            targetFps = pick("targetFps", targetFps) { targetFps },
            ntscFramerate = pick("ntscFramerate", ntscFramerate) { ntscFramerate },
            palFramerate = pick("palFramerate", palFramerate) { palFramerate },
            textureFiltering = pick("textureFiltering", textureFiltering) { textureFiltering },
            trilinearFiltering = pick("trilinearFiltering", trilinearFiltering) { trilinearFiltering },
            blendingAccuracy = pick("blendingAccuracy", blendingAccuracy) { blendingAccuracy },
            texturePreloading = pick("texturePreloading", texturePreloading) { texturePreloading },
            enableFxaa = pick("enableFxaa", enableFxaa) { enableFxaa },
            casMode = pick("casMode", casMode) { casMode },
            sgsrMode = pick("sgsrMode", sgsrMode) { sgsrMode },
            casSharpness = pick("casSharpness", casSharpness) { casSharpness },
            tvShader = pick("tvShader", tvShader) { tvShader },
            shadeBoostEnabled = pick("shadeBoostEnabled", shadeBoostEnabled) { shadeBoostEnabled },
            shadeBoostBrightness = pick("shadeBoostBrightness", shadeBoostBrightness) { shadeBoostBrightness },
            shadeBoostContrast = pick("shadeBoostContrast", shadeBoostContrast) { shadeBoostContrast },
            shadeBoostSaturation = pick("shadeBoostSaturation", shadeBoostSaturation) { shadeBoostSaturation },
            shadeBoostGamma = pick("shadeBoostGamma", shadeBoostGamma) { shadeBoostGamma },
            anisotropicFiltering = pick("anisotropicFiltering", anisotropicFiltering) { anisotropicFiltering },
            enableHwMipmapping = pick("enableHwMipmapping", enableHwMipmapping) { enableHwMipmapping },
            antiBlur = pick("antiBlur", antiBlur) { antiBlur },
            deinterlaceMode = pick("deinterlaceMode", deinterlaceMode) { deinterlaceMode },
            dithering = pick("dithering", dithering) { dithering },
            widescreenPatches = pick("enableWidescreenPatches", widescreenPatches) { enableWidescreenPatches },
            noInterlacingPatches = pick("enableNoInterlacingPatches", noInterlacingPatches) { enableNoInterlacingPatches },
            cpuSpriteRenderSize = pick("cpuSpriteRenderSize", cpuSpriteRenderSize) { cpuSpriteRenderSize },
            cpuSpriteRenderLevel = pick("cpuSpriteRenderLevel", cpuSpriteRenderLevel) { cpuSpriteRenderLevel },
            softwareClutRender = pick("softwareClutRender", softwareClutRender) { softwareClutRender },
            gpuTargetClutMode = pick("gpuTargetClutMode", gpuTargetClutMode) { gpuTargetClutMode },
            skipDrawStart = pick("skipDrawStart", skipDrawStart) { skipDrawStart },
            skipDrawEnd = pick("skipDrawEnd", skipDrawEnd) { skipDrawEnd },
            autoFlushHardware = pick("autoFlushHardware", autoFlushHardware) { autoFlushHardware },
            cpuFramebufferConversion = pick("cpuFramebufferConversion", cpuFramebufferConversion) { cpuFramebufferConversion },
            disableDepthConversion = pick("disableDepthConversion", disableDepthConversion) { disableDepthConversion },
            disableSafeFeatures = pick("disableSafeFeatures", disableSafeFeatures) { disableSafeFeatures },
            disableRenderFixes = pick("disableRenderFixes", disableRenderFixes) { disableRenderFixes },
            preloadFrameData = pick("preloadFrameData", preloadFrameData) { preloadFrameData },
            disablePartialInvalidation = pick("disablePartialInvalidation", disablePartialInvalidation) { disablePartialInvalidation },
            textureInsideRt = pick("textureInsideRt", textureInsideRt) { textureInsideRt },
            readTargetsOnClose = pick("readTargetsOnClose", readTargetsOnClose) { readTargetsOnClose },
            estimateTextureRegion = pick("estimateTextureRegion", estimateTextureRegion) { estimateTextureRegion },
            gpuPaletteConversion = pick("gpuPaletteConversion", gpuPaletteConversion) { gpuPaletteConversion },
            halfPixelOffset = pick("halfPixelOffset", halfPixelOffset) { halfPixelOffset },
            nativeScaling = pick("nativeScaling", nativeScaling) { nativeScaling },
            roundSprite = pick("roundSprite", roundSprite) { roundSprite },
            bilinearUpscale = pick("bilinearUpscale", bilinearUpscale) { bilinearUpscale },
            textureOffsetX = pick("textureOffsetX", textureOffsetX) { textureOffsetX },
            textureOffsetY = pick("textureOffsetY", textureOffsetY) { textureOffsetY },
            alignSprite = pick("alignSprite", alignSprite) { alignSprite },
            mergeSprite = pick("mergeSprite", mergeSprite) { mergeSprite },
            forceEvenSpritePosition = pick("forceEvenSpritePosition", forceEvenSpritePosition) { forceEvenSpritePosition },
            nativePaletteDraw = pick("nativePaletteDraw", nativePaletteDraw) { nativePaletteDraw },
            enableIcacheEmulation = pick("enableIcacheEmulation", enableIcacheEmulation) { enableIcacheEmulation },
            enableDisableStalls = pick("enableDisableStalls", enableDisableStalls) { enableDisableStalls },
            enablePreciseExceptions = pick("enablePreciseExceptions", enablePreciseExceptions) { enablePreciseExceptions },
            enableTurboCd = pick("enableTurboCd", enableTurboCd) { enableTurboCd },
            cdReadAhead = pick("cdReadAhead", cdReadAhead) { cdReadAhead },
            enableCddaAudio = pick("enableCddaAudio", enableCddaAudio) { enableCddaAudio },
            enableXaDecoding = pick("enableXaDecoding", enableXaDecoding) { enableXaDecoding },
            enableSpuReverb = pick("enableSpuReverb", enableSpuReverb) { enableSpuReverb },
            enableSpuThread = pick("enableSpuThread", enableSpuThread) { enableSpuThread },
            spuTempo = pick("spuTempo", spuTempo) { spuTempo },
            neonEnhancement = pick("neonEnhancement", neonEnhancement) { neonEnhancement },
            neonEnhancementSpeedHack = pick("neonEnhancementSpeedHack", neonEnhancementSpeedHack) { neonEnhancementSpeedHack },
            neonEnhancementTexAdj = pick("neonEnhancementTexAdj", neonEnhancementTexAdj) { neonEnhancementTexAdj },
            neonInterlace = pick("neonInterlace", neonInterlace) { neonInterlace },
            gpuThreadRendering = pick("gpuThreadRendering", gpuThreadRendering) { gpuThreadRendering },
            showOverscan = pick("showOverscan", showOverscan) { showOverscan },
            screenCentering = pick("screenCentering", screenCentering) { screenCentering },
            screenCenteringX = pick("screenCenteringX", screenCenteringX) { screenCenteringX },
            screenCenteringY = pick("screenCenteringY", screenCenteringY) { screenCenteringY },
            screenCenteringHAdj = pick("screenCenteringHAdj", screenCenteringHAdj) { screenCenteringHAdj },
            enableFractionalFramerate = pick("enableFractionalFramerate", enableFractionalFramerate) { enableFractionalFramerate },
            altFlipMode = pick("altFlipMode", altFlipMode) { altFlipMode },
            enableRgb32Output = pick("enableRgb32Output", enableRgb32Output) { enableRgb32Output },
            enableScaleHires = pick("enableScaleHires", enableScaleHires) { enableScaleHires },
            multitapMode = pick("multitapMode", multitapMode) { multitapMode },
            analogAxisModifier = pick("analogAxisModifier", analogAxisModifier) { analogAxisModifier },
            dualshockToggleCombo = pick("dualshockToggleCombo", dualshockToggleCombo) { dualshockToggleCombo }
        )
    }

    private suspend fun EmulationUiState.toPerGameSettings(
        gameKey: String,
        gameTitle: String,
        gameSerial: String?
    ): PerGameSettings {
        val settings = preferences.settingsSnapshot.first()
        val profile = buildPerGameSettingsProfile(
            gameKey = gameKey,
            gameTitle = gameTitle,
            gameSerial = gameSerial,
            settings = settings
        )
        val providedKeys = computeProvidedKeys(settings, profile)
        return profile.copy(providedKeys = providedKeys)
    }

    private fun EmulationUiState.buildPerGameSettingsProfile(
        gameKey: String,
        gameTitle: String,
        gameSerial: String?,
        settings: SettingsSnapshot
    ): PerGameSettings {
        return PerGameSettings(
            gameKey = gameKey,
            gameTitle = gameTitle,
            gameSerial = gameSerial,
            renderer = renderer,
            upscaleMultiplier = upscale,
            aspectRatio = aspectRatio,
            localMultiplayerMode = localMultiplayerMode,
            displayCrop = displayCrop,
            showFps = showFps,
            fpsOverlayMode = FPS_OVERLAY_MODE_SIMPLE,
            enableInstantVu1 = enableInstantVu1,
            enableMtvu = enableMtvu,
            enableThreadPinning = enableThreadPinning,
            enableFastCdvd = enableFastCdvd,
            enableFastBoot = enableFastBoot,
            enableCheats = enableCheats,
            hwDownloadMode = hwDownloadMode,
            eeCycleRate = eeCycleRate,
            eeCycleSkip = eeCycleSkip,
            frameSkip = frameSkip,
            skipDuplicateFrames = skipDuplicateFrames,
            frameLimitEnabled = frameLimitEnabled,
            racingMode = racingMode,
            touchscreenRightStick = touchscreenRightStick,
            touchscreenRightStickSensitivity = touchscreenRightStickSensitivity,
            touchHaptics = touchHaptics,
            touchHapticsPreset = touchHapticsPreset,
            touchControlVisualStyle = touchControlVisualStyle.takeIf { it != settings.touchControlVisualStyle },
            touchControlPressEffect = touchControlPressEffect.takeIf { it != settings.touchControlPressEffect },
            gyroMode = gyroMode,
            gyroSensitivity = gyroSensitivity,
            gyroSmoothing = gyroSmoothing,
            gyroInvertX = gyroInvertX,
            gyroInvertY = gyroInvertY,
            gamepadRightStickUpToR2 = gamepadRightStickUpToR2,
            gamepadRightStickDownToL2 = gamepadRightStickDownToL2,
            gamepadButtonHaptics = gamepadButtonHaptics,
            gamepadStickDeadzone = gamepadStickDeadzone,
            gamepadLeftStickSensitivity = gamepadLeftStickSensitivity,
            gamepadRightStickSensitivity = gamepadRightStickSensitivity,
            gamepadBindingsByPad = gamepadBindingsByPad,
            pressureModifierAmount = pressureModifierAmount,
            autoSaveOnExit = autoSaveOnExit,
            autoLoadOnStart = autoLoadOnStart,
            targetFps = targetFps,
            ntscFramerate = ntscFramerate,
            palFramerate = palFramerate,
            textureFiltering = textureFiltering,
            trilinearFiltering = trilinearFiltering,
            blendingAccuracy = blendingAccuracy,
            texturePreloading = texturePreloading,
            enableFxaa = enableFxaa,
            casMode = casMode,
            sgsrMode = sgsrMode,
            casSharpness = casSharpness,
            tvShader = tvShader,
            shadeBoostEnabled = shadeBoostEnabled,
            shadeBoostBrightness = shadeBoostBrightness,
            shadeBoostContrast = shadeBoostContrast,
            shadeBoostSaturation = shadeBoostSaturation,
            shadeBoostGamma = shadeBoostGamma,
            anisotropicFiltering = anisotropicFiltering,
            enableHwMipmapping = enableHwMipmapping,
            antiBlur = antiBlur,
            deinterlaceMode = deinterlaceMode,
            dithering = dithering,
            enableWidescreenPatches = widescreenPatches,
            enableNoInterlacingPatches = noInterlacingPatches,
            cpuSpriteRenderSize = cpuSpriteRenderSize,
            cpuSpriteRenderLevel = cpuSpriteRenderLevel,
            softwareClutRender = softwareClutRender,
            gpuTargetClutMode = gpuTargetClutMode,
            skipDrawStart = skipDrawStart,
            skipDrawEnd = skipDrawEnd,
            autoFlushHardware = autoFlushHardware,
            cpuFramebufferConversion = cpuFramebufferConversion,
            disableDepthConversion = disableDepthConversion,
            disableSafeFeatures = disableSafeFeatures,
            disableRenderFixes = disableRenderFixes,
            preloadFrameData = preloadFrameData,
            disablePartialInvalidation = disablePartialInvalidation,
            textureInsideRt = textureInsideRt,
            readTargetsOnClose = readTargetsOnClose,
            estimateTextureRegion = estimateTextureRegion,
            gpuPaletteConversion = gpuPaletteConversion,
            halfPixelOffset = halfPixelOffset,
            nativeScaling = nativeScaling,
            roundSprite = roundSprite,
            bilinearUpscale = bilinearUpscale,
            textureOffsetX = textureOffsetX,
            textureOffsetY = textureOffsetY,
            alignSprite = alignSprite,
            mergeSprite = mergeSprite,
            forceEvenSpritePosition = forceEvenSpritePosition,
            nativePaletteDraw = nativePaletteDraw,
            enableIcacheEmulation = enableIcacheEmulation,
            enableDisableStalls = enableDisableStalls,
            enablePreciseExceptions = enablePreciseExceptions,
            enableTurboCd = enableTurboCd,
            cdReadAhead = cdReadAhead,
            enableCddaAudio = enableCddaAudio,
            enableXaDecoding = enableXaDecoding,
            enableSpuReverb = enableSpuReverb,
            enableSpuThread = enableSpuThread,
            spuTempo = spuTempo,
            neonEnhancement = neonEnhancement,
            neonEnhancementSpeedHack = neonEnhancementSpeedHack,
            neonEnhancementTexAdj = neonEnhancementTexAdj,
            neonInterlace = neonInterlace,
            gpuThreadRendering = gpuThreadRendering,
            showOverscan = showOverscan,
            screenCentering = screenCentering,
            screenCenteringX = screenCenteringX,
            screenCenteringY = screenCenteringY,
            screenCenteringHAdj = screenCenteringHAdj,
            enableFractionalFramerate = enableFractionalFramerate,
            altFlipMode = altFlipMode,
            enableRgb32Output = enableRgb32Output,
            enableScaleHires = enableScaleHires,
            multitapMode = multitapMode,
            analogAxisModifier = analogAxisModifier,
            dualshockToggleCombo = dualshockToggleCombo
        )
    }

    private fun EmulationUiState.computeProvidedKeys(
        settings: SettingsSnapshot,
        profile: PerGameSettings
    ): Set<String> = buildSet {
        if (renderer != settings.renderer) add("renderer")
        if (upscale != settings.upscaleMultiplier) add("upscaleMultiplier")
        if (aspectRatio != settings.aspectRatio) add("aspectRatio")
        if (localMultiplayerMode != settings.localMultiplayerMode) add("localMultiplayerMode")
        if (displayCrop != settings.displayCrop) add("displayCrop")
        if (showFps != settings.showFps) add("showFps")
        if (fpsOverlayMode != settings.fpsOverlayMode) add("fpsOverlayMode")
        if (enableInstantVu1 != settings.enableInstantVu1) add("enableInstantVu1")
        if (enableMtvu != settings.enableMtvu) add("enableMtvu")
        if (enableThreadPinning != settings.enableThreadPinning) add("enableThreadPinning")
        if (enableFastCdvd != settings.enableFastCdvd) add("enableFastCdvd")
        if (enableFastBoot != settings.enableFastBoot) add("enableFastBoot")
        if (enableCheats != settings.enableCheats) add("enableCheats")
        if (hwDownloadMode != settings.hwDownloadMode) add("hwDownloadMode")
        if (eeCycleRate != settings.eeCycleRate) add("eeCycleRate")
        if (eeCycleSkip != settings.eeCycleSkip) add("eeCycleSkip")
        if (profile.frameSkip != settings.frameSkip) add("frameSkip")
        if (skipDuplicateFrames != settings.skipDuplicateFrames) add("skipDuplicateFrames")
        if (frameLimitEnabled != settings.frameLimitEnabled) add("frameLimitEnabled")
        if (racingMode != settings.racingMode) add("racingMode")
        if (touchscreenRightStick != settings.touchscreenRightStick) add("touchscreenRightStick")
        if (touchscreenRightStickSensitivity != settings.touchscreenRightStickSensitivity) {
            add("touchscreenRightStickSensitivity")
        }
        if (touchHaptics != settings.touchHaptics) add("touchHaptics")
        if (touchHapticsPreset != settings.touchHapticsPreset) add("touchHapticsPreset")
        if (profile.touchControlVisualStyle != null) add("touchControlVisualStyle")
        if (profile.touchControlPressEffect != null) add("touchControlPressEffect")
        if (gyroMode != settings.gyroMode) add("gyroMode")
        if (gyroSensitivity != settings.gyroSensitivity) add("gyroSensitivity")
        if (gyroSmoothing != settings.gyroSmoothing) add("gyroSmoothing")
        if (gyroInvertX != settings.gyroInvertX) add("gyroInvertX")
        if (gyroInvertY != settings.gyroInvertY) add("gyroInvertY")
        if (gamepadRightStickUpToR2 != settings.gamepadRightStickUpToR2) add("gamepadRightStickUpToR2")
        if (gamepadRightStickDownToL2 != settings.gamepadRightStickDownToL2) add("gamepadRightStickDownToL2")
        if (gamepadButtonHaptics != settings.gamepadButtonHaptics) add("gamepadButtonHaptics")
        if (gamepadStickDeadzone != settings.gamepadStickDeadzone) add("gamepadStickDeadzone")
        if (gamepadLeftStickSensitivity != settings.gamepadLeftStickSensitivity) add("gamepadLeftStickSensitivity")
        if (gamepadRightStickSensitivity != settings.gamepadRightStickSensitivity) add("gamepadRightStickSensitivity")
        if (gamepadBindingsByPad.isNotEmpty()) add("gamepadBindingsByPad")
        if (pressureModifierAmount != settings.pressureModifierAmount) add("pressureModifierAmount")
        if (autoSaveOnExit) add("autoSaveOnExit")
        if (autoLoadOnStart) add("autoLoadOnStart")
        if (targetFps != settings.targetFps) add("targetFps")
        if (ntscFramerate != settings.ntscFramerate) add("ntscFramerate")
        if (palFramerate != settings.palFramerate) add("palFramerate")
        if (textureFiltering != settings.textureFiltering) add("textureFiltering")
        if (trilinearFiltering != settings.trilinearFiltering) add("trilinearFiltering")
        if (blendingAccuracy != settings.blendingAccuracy) add("blendingAccuracy")
        if (texturePreloading != settings.texturePreloading) add("texturePreloading")
        if (enableFxaa != settings.enableFxaa) add("enableFxaa")
        if (casMode != settings.casMode) add("casMode")
        if (sgsrMode != settings.sgsrMode) add("sgsrMode")
        if (casSharpness != settings.casSharpness) add("casSharpness")
        if (tvShader != settings.tvShader) add("tvShader")
        if (shadeBoostEnabled != settings.shadeBoostEnabled) add("shadeBoostEnabled")
        if (shadeBoostBrightness != settings.shadeBoostBrightness) add("shadeBoostBrightness")
        if (shadeBoostContrast != settings.shadeBoostContrast) add("shadeBoostContrast")
        if (shadeBoostSaturation != settings.shadeBoostSaturation) add("shadeBoostSaturation")
        if (shadeBoostGamma != settings.shadeBoostGamma) add("shadeBoostGamma")
        if (anisotropicFiltering != settings.anisotropicFiltering) add("anisotropicFiltering")
        if (enableHwMipmapping != settings.enableHwMipmapping) add("enableHwMipmapping")
        if (antiBlur != settings.antiBlur) add("antiBlur")
        if (deinterlaceMode != settings.deinterlaceMode) add("deinterlaceMode")
        if (dithering != settings.dithering) add("dithering")
        if (profile.enableWidescreenPatches != settings.enableWidescreenPatches) add("enableWidescreenPatches")
        if (profile.enableNoInterlacingPatches != settings.enableNoInterlacingPatches) add("enableNoInterlacingPatches")
        if (cpuSpriteRenderSize != settings.cpuSpriteRenderSize) add("cpuSpriteRenderSize")
        if (cpuSpriteRenderLevel != settings.cpuSpriteRenderLevel) add("cpuSpriteRenderLevel")
        if (softwareClutRender != settings.softwareClutRender) add("softwareClutRender")
        if (gpuTargetClutMode != settings.gpuTargetClutMode) add("gpuTargetClutMode")
        if (skipDrawStart != settings.skipDrawStart) add("skipDrawStart")
        if (skipDrawEnd != settings.skipDrawEnd) add("skipDrawEnd")
        if (autoFlushHardware != settings.autoFlushHardware) add("autoFlushHardware")
        if (cpuFramebufferConversion != settings.cpuFramebufferConversion) add("cpuFramebufferConversion")
        if (disableDepthConversion != settings.disableDepthConversion) add("disableDepthConversion")
        if (disableSafeFeatures != settings.disableSafeFeatures) add("disableSafeFeatures")
        if (disableRenderFixes != settings.disableRenderFixes) add("disableRenderFixes")
        if (preloadFrameData != settings.preloadFrameData) add("preloadFrameData")
        if (disablePartialInvalidation != settings.disablePartialInvalidation) add("disablePartialInvalidation")
        if (textureInsideRt != settings.textureInsideRt) add("textureInsideRt")
        if (readTargetsOnClose != settings.readTargetsOnClose) add("readTargetsOnClose")
        if (estimateTextureRegion != settings.estimateTextureRegion) add("estimateTextureRegion")
        if (gpuPaletteConversion != settings.gpuPaletteConversion) add("gpuPaletteConversion")
        if (halfPixelOffset != settings.halfPixelOffset) add("halfPixelOffset")
        if (nativeScaling != settings.nativeScaling) add("nativeScaling")
        if (roundSprite != settings.roundSprite) add("roundSprite")
        if (bilinearUpscale != settings.bilinearUpscale) add("bilinearUpscale")
        if (textureOffsetX != settings.textureOffsetX) add("textureOffsetX")
        if (textureOffsetY != settings.textureOffsetY) add("textureOffsetY")
        if (alignSprite != settings.alignSprite) add("alignSprite")
        if (mergeSprite != settings.mergeSprite) add("mergeSprite")
        if (forceEvenSpritePosition != settings.forceEvenSpritePosition) add("forceEvenSpritePosition")
        if (nativePaletteDraw != settings.nativePaletteDraw) add("nativePaletteDraw")
        if (enableIcacheEmulation != settings.enableIcacheEmulation) add("enableIcacheEmulation")
        if (enableDisableStalls != settings.enableDisableStalls) add("enableDisableStalls")
        if (enablePreciseExceptions != settings.enablePreciseExceptions) add("enablePreciseExceptions")
        if (enableTurboCd != settings.enableTurboCd) add("enableTurboCd")
        if (cdReadAhead != settings.cdReadAhead) add("cdReadAhead")
        if (enableCddaAudio != settings.enableCddaAudio) add("enableCddaAudio")
        if (enableXaDecoding != settings.enableXaDecoding) add("enableXaDecoding")
        if (enableSpuReverb != settings.enableSpuReverb) add("enableSpuReverb")
        if (enableSpuThread != settings.enableSpuThread) add("enableSpuThread")
        if (spuTempo != settings.spuTempo) add("spuTempo")
        if (neonEnhancement != settings.neonEnhancement) add("neonEnhancement")
        if (neonEnhancementSpeedHack != settings.neonEnhancementSpeedHack) add("neonEnhancementSpeedHack")
        if (neonEnhancementTexAdj != settings.neonEnhancementTexAdj) add("neonEnhancementTexAdj")
        if (neonInterlace != settings.neonInterlace) add("neonInterlace")
        if (gpuThreadRendering != settings.gpuThreadRendering) add("gpuThreadRendering")
        if (showOverscan != settings.showOverscan) add("showOverscan")
        if (screenCentering != settings.screenCentering) add("screenCentering")
        if (screenCenteringX != settings.screenCenteringX) add("screenCenteringX")
        if (screenCenteringY != settings.screenCenteringY) add("screenCenteringY")
        if (screenCenteringHAdj != settings.screenCenteringHAdj) add("screenCenteringHAdj")
        if (enableFractionalFramerate != settings.enableFractionalFramerate) add("enableFractionalFramerate")
        if (altFlipMode != settings.altFlipMode) add("altFlipMode")
        if (enableRgb32Output != settings.enableRgb32Output) add("enableRgb32Output")
        if (enableScaleHires != settings.enableScaleHires) add("enableScaleHires")
        if (multitapMode != settings.multitapMode) add("multitapMode")
        if (analogAxisModifier != settings.analogAxisModifier) add("analogAxisModifier")
        if (dualshockToggleCombo != settings.dualshockToggleCombo) add("dualshockToggleCombo")
    }

    private fun refreshCurrentGameCheats(
        metadata: com.sbro.emucorer.core.GameMetadata,
        cheatsEnabled: Boolean = _uiState.value.enableCheats
    ) {
        val serial = metadata.serial.orEmpty()
        val crc = metadata.serialWithCrc.extractCrc()
        val config = cheatRepository.getGameConfig(
            gameKeys = cheatLookupKeys(metadata),
            serial = serial,
            crc = crc
        )
        _uiState.value = _uiState.value.copy(
            cheatsGameKey = config?.gameKey,
            availableCheats = config?.blocks.orEmpty()
        )
        if (config != null && cheatsEnabled) {
            cheatRepository.syncActiveCheats(config.gameKey, serial, crc)
        }
    }

    /**
     * Presents a DualShock on both ports. The core keeps it in digital mode
     * until a game enables analog/rumble, and DualShock is the only controller
     * class that can drive vibration, so rumble works with touch controls and
     * gamepads alike.
     */
    private fun syncPadAnalogModeForLaunch() {
        NativeApp.setPadAnalogMode(0, true)
        NativeApp.setPadAnalogMode(1, true)
    }

    private fun syncCheatsForCurrentGame(gameKeyOverride: String? = null) {
        val state = _uiState.value
        val serial = currentGameSerial.takeIf { it.isNotBlank() }
        val crc = currentGameCrc.takeIf { it.isNotBlank() }
        val gameKey = gameKeyOverride
            ?: state.cheatsGameKey
            ?: serial
            ?: crc
            ?: "game"
        val patchBlocks = gamePatchRepository.buildPatchBlocks(
            serial = serial,
            crc = crc,
            widescreen = state.widescreenPatches,
            noInterlacing = state.noInterlacingPatches
        )
        val patchDirectives = gamePatchRepository.resolveDirectives(
            serial = serial,
            crc = crc,
            widescreen = state.widescreenPatches,
            noInterlacing = state.noInterlacingPatches
        )
        if (patchDirectives.disableWidescreenHack) {
            NativeApp.applyCoreOption("swanstation_GPU_WidescreenHack", "false")
        }
        if (patchDirectives.aspectRatioOverride?.equals("16:9", ignoreCase = true) == true) {
            NativeApp.applyCoreOption("swanstation_Display_AspectRatio", "16:9")
        }
        cheatRepository.syncActiveCheats(
            gameKey = gameKey,
            serial = serial,
            crc = crc,
            includeCheats = state.enableCheats,
            patchBlocks = patchBlocks
        )
        val coreCheatFile = cheatRepository.activeCoreCheatFile(gameKey, serial, crc)
        if (coreCheatFile != null) {
            NativeApp.loadCheats(coreCheatFile.absolutePath)
        } else {
            NativeApp.clearCheats()
        }
    }

    private fun cheatLookupKeys(metadata: com.sbro.emucorer.core.GameMetadata): List<String> {
        val keys = linkedSetOf<String>()
        metadata.serialWithCrc?.trim()?.takeIf { it.isNotBlank() }?.let(keys::add)
        metadata.serialWithCrc.extractSerialAndCrcKey()?.let(keys::add)
        metadata.serialWithCrc.extractCrc()?.let(keys::add)
        metadata.serial?.trim()?.takeIf { it.isNotBlank() }?.let(keys::add)
        metadata.title.trim().takeIf { it.isNotBlank() }?.let(keys::add)
        return keys.toList()
    }

    fun setSlot(slot: Int) {
        _uiState.value = _uiState.value.copy(currentSlot = normalizeManualSaveSlot(slot))
        refreshSaveStateMetadata()
    }

    private fun normalizeSaveSlot(slot: Int): Int = slot.coerceIn(AUTO_SAVE_SLOT, 10)

    private fun normalizeManualSaveSlot(slot: Int): Int = slot.coerceIn(1, 10)

    fun setAutoSaveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoSaveEnabled(enabled)
        }
    }

    fun setAutoSaveIntervalMinutes(value: Int) {
        viewModelScope.launch {
            preferences.setAutoSaveIntervalMinutes(value)
        }
    }

    fun setAutoSaveOnExit(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = _uiState.value.copy(autoSaveOnExit = enabled)
            persistRuntimeState(updated)
        }
    }

    fun setAutoLoadOnStart(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = _uiState.value.copy(autoLoadOnStart = enabled)
            persistRuntimeState(updated)
        }
    }

    private fun refreshSaveStateMetadata() {
        val path = currentGamePath
        if (path.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                currentSlotLastModified = 0L,
                autoSaveLastModified = 0L
            )
            return
        }

        val currentSlot = _uiState.value.currentSlot
        val currentSlotModified = saveStateLastModified(path, currentSlot)
        val autoSaveModified = saveStateLastModified(path, AUTO_SAVE_SLOT)
        _uiState.value = _uiState.value.copy(
            currentSlotLastModified = currentSlotModified,
            autoSaveLastModified = autoSaveModified
        )
    }

    private fun saveStateLastModified(gamePath: String, slot: Int): Long {
        val file = resolveSaveStateFile(gamePath, slot) ?: return 0L
        return file.takeIf { it.exists() }?.lastModified() ?: 0L
    }

    private suspend fun waitForSaveStateUpdate(gamePath: String, slot: Int, previousModified: Long): Boolean {
        val statePath = runCatching { NativeApp.getCurrentSaveStatePath(slot) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { NativeApp.getSaveStatePathForFile(gamePath, slot) }.getOrNull()
            ?: return false
        val fallbackFile = File(statePath)
        repeat(40) {
            val file = resolveSaveStateFile(gamePath, slot) ?: fallbackFile
            val modified = file.takeIf { it.exists() }?.lastModified() ?: 0L
            if (modified > 0L && modified != previousModified) {
                return true
            }
            delay(250.milliseconds)
        }
        return (resolveSaveStateFile(gamePath, slot) ?: fallbackFile).exists()
    }

    private fun resolveSaveStateFile(gamePath: String, slot: Int): File? {
        // The identity path is what the native writer actually uses; fall back
        // to the caller's path and finally to a serial-named scan.
        val identityFile = runCatching { NativeApp.getCurrentSaveStatePath(slot) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        if (identityFile?.exists() == true) return identityFile
        val nativeFile = runCatching { NativeApp.getSaveStatePathForFile(gamePath, slot) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        if (nativeFile?.exists() == true) return nativeFile
        return findSaveStateFileForCurrentGame(slot) ?: nativeFile
    }

    private fun findSaveStateFileForCurrentGame(slot: Int): File? {
        val targetSerial = currentGameSerial.normalizeSaveSerialKey() ?: return null
        val matches = EmulatorStorage.saveStatesDir(getApplication(), preferences.getEmulatorDataPathSync())
            .listFiles()
            .orEmpty()
            .mapNotNull { file ->
                if (!file.isFile) return@mapNotNull null
                val parsed = SAVE_STATE_FILE_REGEX.matchEntire(file.name) ?: return@mapNotNull null
                val fileSlot = parsed.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (fileSlot != slot) return@mapNotNull null
                val fileSerial = parsed.groupValues[1].normalizeSaveSerialKey() ?: return@mapNotNull null
                if (fileSerial != targetSerial) return@mapNotNull null
                file
            }
        if (matches.isEmpty()) return null
        return matches.maxByOrNull { it.lastModified() }
    }

    fun quickSave() {
        val slot = _uiState.value.currentSlot
        viewModelScope.launch(Dispatchers.IO) {
            val path = currentGamePath
            val previousModified = path?.let { saveStateLastModified(it, slot) } ?: 0L
            _uiState.value = _uiState.value.copy(
                isActionInProgress = true,
                actionLabel = "saving"
            )
            val scheduled = lifecycleMutex.withLock {
                if (isShuttingDown) {
                    false
                } else {
                    try {
                        EmulatorBridge.saveState(slot)
                    } catch (_: Exception) { false }
                }
            }
            val success = scheduled && path != null && waitForSaveStateUpdate(path, slot, previousModified)
            _uiState.value = _uiState.value.copy(
                isActionInProgress = false,
                actionLabel = null,
                toastMessage = if (success) "saved" else "save_failed"
            )
            // Refresh even on failure: whatever is on disk is the truth, and a
            // silent failure used to leave the slot looking empty.
            refreshSaveStateMetadata()
            delay(2000.milliseconds)
            _uiState.value = _uiState.value.copy(toastMessage = null)
        }
    }

    fun quickLoad() {
        val slot = _uiState.value.currentSlot
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isActionInProgress = true,
                actionLabel = "loading"
            )
            val success = lifecycleMutex.withLock {
                if (isShuttingDown) {
                    false
                } else {
                    try {
                        EmulatorBridge.loadState(slot)
                    } catch (_: Exception) { false }
                }
            }
            _uiState.value = _uiState.value.copy(
                isActionInProgress = false,
                actionLabel = null,
                toastMessage = if (success) "loaded" else null
            )
            delay(2000.milliseconds)
            _uiState.value = _uiState.value.copy(toastMessage = null)
        }
    }

    fun loadAutoSave() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isActionInProgress = true,
                actionLabel = "loading"
            )
            val success = lifecycleMutex.withLock {
                if (isShuttingDown) {
                    false
                } else {
                    try {
                        EmulatorBridge.loadState(AUTO_SAVE_SLOT)
                    } catch (_: Exception) { false }
                }
            }
            _uiState.value = _uiState.value.copy(
                isActionInProgress = false,
                actionLabel = null,
                toastMessage = if (success) "loaded" else null
            )
            delay(2000.milliseconds)
            _uiState.value = _uiState.value.copy(toastMessage = null)
        }
    }

    private suspend fun tryAutoLoadOnStart(): Boolean {
        val path = currentGamePath ?: return false
        if (!_uiState.value.autoLoadOnStart || saveStateLastModified(path, AUTO_SAVE_SLOT) <= 0L) {
            return false
        }
        _uiState.value = _uiState.value.copy(
            isActionInProgress = true,
            actionLabel = "loading",
            statusMessage = "status_loading_state"
        )
        val success = lifecycleMutex.withLock {
            if (isShuttingDown || !_uiState.value.isRunning) {
                false
            } else {
                try {
                    EmulatorBridge.loadState(AUTO_SAVE_SLOT)
                } catch (_: Exception) {
                    false
                }
            }
        }
        _uiState.value = _uiState.value.copy(
            isActionInProgress = false,
            actionLabel = null,
            statusMessage = if (success) "status_running" else null,
            toastMessage = if (success) "loaded" else "load_failed"
        )
        refreshSaveStateMetadata()
        delay(2000.milliseconds)
        if (_uiState.value.statusMessage == "status_running") {
            _uiState.value = _uiState.value.copy(statusMessage = null)
        }
        if (_uiState.value.toastMessage == "loaded" || _uiState.value.toastMessage == "load_failed") {
            _uiState.value = _uiState.value.copy(toastMessage = null)
        }
        return success
    }

    fun stopEmulation(onExit: (() -> Unit)? = null) {
        cancelPendingStart = true
        pausedForBackground = false
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.shouldAutoSaveOnExit(currentGamePath, EmulatorBridge.runtimeFailure.value)) {
                saveAutoSaveSlot(
                    allowWhileMenu = true,
                    allowPaused = true,
                    showActionProgress = true
                )
            }
            performShutdown()
            if (onExit != null) {
                withContext(Dispatchers.Main) {
                    onExit.invoke()
                }
            }
        }
    }

    fun onHostBackgrounded() {
        viewModelScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val state = _uiState.value
                if (!state.isRunning || state.isStarting || state.isPaused || state.showMenu || isShuttingDown) {
                    return@withLock
                }
                try {
                    EmulatorBridge.pause()
                    pausedForBackground = true
                    _uiState.value = state.copy(isPaused = true)
                    DiscordIntegration.setPaused(true)
                    updateCrashContext(launchState = "paused")
                } catch (_: Exception) { }
            }
        }
    }

    fun onHostForegrounded() {
        viewModelScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val state = _uiState.value
                if (!pausedForBackground ||
                    !state.isRunning ||
                    state.isStarting ||
                    !state.isPaused ||
                    state.showMenu ||
                    isShuttingDown
                ) {
                    return@withLock
                }
                try {
                    EmulatorBridge.resume()
                    pausedForBackground = false
                    _uiState.value = state.copy(isPaused = false)
                    DiscordIntegration.setPaused(false)
                    updateCrashContext(launchState = "running")
                } catch (_: Exception) { }
            }
        }
    }

    private suspend fun performShutdown() {
        lifecycleMutex.withLock {
            if (!_uiState.value.isRunning && !_uiState.value.isStarting &&
                !EmulatorBridge.isVmActive() && EmulatorBridge.runtimeFailure.value == null) return
            if (isShuttingDown) return
            val analyticsState = _uiState.value
            val completedRunningSession = analyticsState.isRunning
            isShuttingDown = true
            pausedForBackground = false
            try {
                try {
                    EmulatorBridge.resetKeyStatus()
                } catch (_: Exception) { }
                try {
                    GamepadManager.clearPerGameOverrides()
                } catch (_: Exception) { }
                try {
                    if (_uiState.value.isHangTraceActive) {
                        EmulatorBridge.stopHangTrace()
                    }
                } catch (_: Exception) { }
                try {
                    EmulatorBridge.shutdown()
                    var waitTime = 0
                    while (EmulatorBridge.isVmActive() && waitTime < 2000) {
                        delay(50.milliseconds)
                        waitTime += 50
                    }
                    DocumentPathResolver.releasePreparedLaunchHandles()
                } catch (_: Exception) { }
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    isStarting = false,
                    isPaused = false,
                    showMenu = false,
                    isActionInProgress = false,
                    actionLabel = null,
                    fps = "0",
                    performanceOverlayText = "",
                    speedPercent = 100f,
                    isJitProfilerActive = false,
                    isHangTraceActive = false,
                    statusMessage = null
                )
                syncNativePerformanceOverlayState(_uiState.value)
                clearCrashContext()
                RetroAchievementsRepository.get(getApplication()).onGameStopped()
            } finally {
                isShuttingDown = false
            }
        }
    }

    private fun updateCrashContext(
        launchState: String? = null,
        launchPath: String? = null
    ) {
        val state = _uiState.value
        NativeApp.setCrashContextString("emu_launch_state", launchState ?: when {
            state.isStarting -> "starting"
            state.isPaused -> "paused"
            state.isRunning -> "running"
            else -> "idle"
        })
        NativeApp.setCrashContextString("emu_game_title", currentGameTitle)
        NativeApp.setCrashContextString("emu_game_serial", currentGameSerial)
        NativeApp.setCrashContextString("emu_game_source", currentGameSource)
        NativeApp.setCrashContextString("emu_game_path_hint", launchPath?.let { File(it).name }.orEmpty())
        NativeApp.setCrashContextInt("emu_renderer", state.renderer)
        NativeApp.setCrashContextString("emu_renderer_name", when (state.renderer) {
            12 -> "OpenGL"
            13 -> "Software"
            14 -> "Vulkan"
            else -> "Unknown(${state.renderer})"
        })
        NativeApp.setCrashContextString("emu_upscale", state.upscale.toString())
        NativeApp.setCrashContextInt("emu_aspect_ratio", state.aspectRatio)
        NativeApp.setCrashContextInt("emu_local_multiplayer_mode", state.localMultiplayerMode)
        NativeApp.setCrashContextInt("emu_crop_left", state.displayCrop.left)
        NativeApp.setCrashContextInt("emu_crop_top", state.displayCrop.top)
        NativeApp.setCrashContextInt("emu_crop_right", state.displayCrop.right)
        NativeApp.setCrashContextInt("emu_crop_bottom", state.displayCrop.bottom)
        NativeApp.setCrashContextBool("emu_mtvu", state.enableMtvu)
        NativeApp.setCrashContextBool("emu_thread_pinning", state.enableThreadPinning)
        NativeApp.setCrashContextBool("emu_fast_cdvd", state.enableFastCdvd)
        NativeApp.setCrashContextBool("emu_enable_cheats", state.enableCheats)
        NativeApp.setCrashContextInt("emu_hw_download_mode", state.hwDownloadMode)
        NativeApp.setCrashContextInt("emu_frame_skip", state.frameSkip)
        NativeApp.setCrashContextBool("emu_skip_duplicate_frames", state.skipDuplicateFrames)
        NativeApp.setCrashContextBool("emu_frame_limit_enabled", state.frameLimitEnabled)
        NativeApp.setCrashContextInt("emu_target_fps", state.targetFps)
        NativeApp.setCrashContextInt("emu_texture_filtering", state.textureFiltering)
        NativeApp.setCrashContextString("emu_device_model", Build.MODEL.orEmpty())
        NativeApp.setCrashContextString("emu_soc_model", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "")
        NativeApp.setCrashContextBool("emu_running", state.isRunning)
        NativeApp.setCrashContextBool("emu_paused", state.isPaused)
    }

    private fun clearCrashContext() {
        DiscordIntegration.clearGame()
        currentGameTitle = ""
        currentGamePath = null
        currentTouchControlsLayoutProfile = null
        currentGameSerial = ""
        currentGameCoverArtPath = null
        currentGameCrc = ""
        _uiState.value = _uiState.value.copy(
            currentGameTitle = "",
            currentGameSubtitle = "",
            currentGameCoverPath = null,
            gameSettingsProfileActive = false
        )
        currentGameSource = ""
        NativeApp.setCrashContextString("emu_launch_state", "idle")
        NativeApp.setCrashContextString("emu_game_title", "")
        NativeApp.setCrashContextString("emu_game_serial", "")
        NativeApp.setCrashContextString("emu_game_source", "")
        NativeApp.setCrashContextString("emu_game_path_hint", "")
        NativeApp.setCrashContextBool("emu_running", false)
        NativeApp.setCrashContextBool("emu_paused", false)
    }

    fun onPadInput(padIndex: Int, keyCode: Int, range: Int = 0, pressed: Boolean) {
        try {
            EmulatorBridge.setPadButton(padIndex, keyCode, range, pressed)
        } catch (_: Exception) { }
    }

    override fun onCleared() {
        DiscordIntegration.clearGame()
        androidGamePerformance.update(AndroidGamePhase.Idle)
        NativeApp.setPerformanceMetricsEnabled(visible = false, detailed = false)
        if (_uiState.value.isRunning) {
            EmulatorBridge.resetKeyStatus()
            runCatching {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    EmulatorBridge.shutdown()
                }
            }
        }
    }
}

private fun String?.extractCrc(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return null
    val parenthesized = raw.substringAfter('(', "").substringBefore(')').trim()
    if (parenthesized.matches(Regex("[0-9A-Fa-f]{8}"))) return parenthesized.uppercase()
    return Regex("([0-9A-Fa-f]{8})(?!.*[0-9A-Fa-f]{8})")
        .find(raw)
        ?.groupValues
        ?.getOrNull(1)
        ?.uppercase()
}

private fun String?.extractSerialAndCrcKey(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return null
    val crc = raw.extractCrc()
    val serial = raw.substringBefore('(')
        .replace(Regex("_[0-9A-Fa-f]{8}$"), "")
        .trim()
    return if (serial.isNotBlank() && !crc.isNullOrBlank()) "${serial}_$crc" else null
}

private fun String?.normalizeSaveSerialKey(): String? {
    if (this.isNullOrBlank()) return null
    val cleanSerial = trim().uppercase(Locale.ROOT)
    val splitRegex = Regex("([A-Z]{4})[^A-Z0-9]*([0-9]{3})[^A-Z0-9]*([0-9]{2})")
    val compactRegex = Regex("([A-Z]{4})[^A-Z0-9]*([0-9]{5})")
    splitRegex.find(cleanSerial)?.let { match ->
        return "${match.groupValues[1]}-${match.groupValues[2]}${match.groupValues[3]}"
    }
    compactRegex.find(cleanSerial)?.let { match ->
        return "${match.groupValues[1]}-${match.groupValues[2]}"
    }
    return cleanSerial.replace(Regex("[^A-Z0-9_-]"), "").takeIf { it.isNotBlank() }
}
