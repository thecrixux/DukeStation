// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreR-Proprietary
package com.sbro.emucorer.core

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import android.view.Surface
import com.sbro.emucorer.data.RetroArchShaderEffects
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of the native libretro session.
 *
 * The bundled SwanStation core is a libretro singleton; [sessionLock] serialises
 * every call that touches it. The Kotlin layer keeps the same per-frame PCM and
 * pad contract as before, so the rest of the app is unchanged.
 */
internal object CoreRuntime {
    private const val TAG = "CoreRuntime"
    private const val DEFAULT_FRAME_WIDTH = 320
    private const val DEFAULT_FRAME_HEIGHT = 240
    private const val SAVE_STATE_MAGIC = 0x54534345
    private const val SAVE_STATE_VERSION = 1
    private const val SAVE_STATE_HEADER_BYTES = 8

    val bridge: NativeCoreBridge by lazy { NativeCoreBridge() }
    val settings = ConcurrentHashMap<String, String>()
    private val _failure = MutableStateFlow<RuntimeFailure?>(null)
    val failure = _failure.asStateFlow()

    private val lifecycleLock = ReentrantLock()
    private val sessionLock = ReentrantLock()
    private var context: Context? = null
    @Volatile private var session = 0L
    @Volatile private var worker: Thread? = null
    private var audioOutput: NativeAudioOutput? = null

    private var systemDirectory = ""
    private var saveDirectory = ""
    private var coreAssetsDirectory = ""

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    @Volatile private var renderedFirstFrame = false
    @Volatile private var sessionStartedAtNanos = 0L
    @Volatile private var frameWidth = DEFAULT_FRAME_WIDTH
    @Volatile private var frameHeight = DEFAULT_FRAME_HEIGHT
    @Volatile private var requestedRenderer = RendererDefaults.defaultForHardware()
    @Volatile private var activeCoreRenderer = RendererDefaults.toCoreRenderer(requestedRenderer)
    @Volatile private var currentGamePath: String? = null
    @Volatile private var currentBiosOnly = false
    @Volatile private var performanceMetricsEnabled = false
    @Volatile private var detailedPerformanceMetrics = false
    @Volatile private var performanceMetricsSnapshot: String? = null
    private var discDescriptor: ParcelFileDescriptor? = null
    private var discLink: File? = null

    private val pendingPadButtons = AtomicIntegerArray(IntArray(2) { -1 })
    private val pendingPadAnalog = AtomicIntegerArray(IntArray(2) { 0x80808080.toInt() })

    private class FrameTask(
        val block: () -> Boolean,
        val completed: CountDownLatch
    ) {
        @Volatile var result: Boolean = false
    }

    private val frameTasks = ConcurrentLinkedQueue<FrameTask>()

    /**
     * Runs [block] on the frame worker thread.
     *
     * Serialization must happen there because the OpenGL ES renderer owns its
     * EGL context on that thread: calling [NativeCoreBridge.saveState] /
     * [NativeCoreBridge.loadState] from any other thread silently skips the GL
     * VRAM readback/upload and produces corrupted save states.
     */
    private fun runOnFrameThread(block: () -> Boolean): Boolean {
        val activeWorker = worker
        if (activeWorker == null || !activeWorker.isAlive || activeWorker === Thread.currentThread()) {
            return block()
        }
        val task = FrameTask(block, CountDownLatch(1))
        frameTasks.add(task)
        return try {
            if (!task.completed.await(30, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out waiting for the frame worker to run a state operation")
                false
            } else {
                task.result
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun drainFrameTasks() {
        while (true) {
            val task = frameTasks.poll() ?: return
            task.result = try {
                task.block()
            } catch (error: Throwable) {
                Log.e(TAG, "Frame-thread state operation failed", error)
                false
            }
            task.completed.countDown()
        }
    }

    fun initialize(context: Context) {
        this.context = context.applicationContext
        val root = File(context.filesDir, "swanstation")
        systemDirectory = File(root, "system").apply { mkdirs() }.absolutePath
        saveDirectory = File(root, "save").apply { mkdirs() }.absolutePath
        coreAssetsDirectory = File(root, "assets").apply { mkdirs() }.absolutePath
        SwanStationOptions.initialize(context.applicationContext)
        runCatching {
            bridge.nativeInit(systemDirectory, saveDirectory, coreAssetsDirectory)
            bridge.apiVersion()
        }.onFailure { Log.e(TAG, "Unable to initialise libretro frontend", it) }
    }

    fun isRunning(): Boolean = running && failure.value == null && sessionLock.withLock { session != 0L }
    fun hasSession(): Boolean = sessionLock.withLock { session != 0L }

    // Serialize offline card transactions with PS1 start/restart/shutdown.
    fun withStoppedPs1(action: Runnable): Boolean = lifecycleLock.withLock {
        if (running || worker?.isAlive == true || hasSession()) return@withLock false
        action.run()
        true
    }


    fun setPerformanceMetricsEnabled(visible: Boolean, detailed: Boolean) {
        performanceMetricsEnabled = visible
        detailedPerformanceMetrics = visible && detailed
        if (!visible) performanceMetricsSnapshot = null
    }

    fun performanceMetricsSnapshot(): String? = performanceMetricsSnapshot

    fun setAudioGain(volume: Int, muted: Boolean) {
        val normalized = volume.coerceIn(AudioDefaults.VOLUME_MIN, AudioDefaults.VOLUME_MAX) /
            AudioDefaults.VOLUME_MAX.toFloat()
        bridge.setAudioGain(if (muted) 0f else normalized)
    }

    fun start(gamePath: String, biosOnly: Boolean): Boolean = lifecycleLock.withLock {
        startSession(gamePath, biosOnly)
    }

    /**
     * Restarts the running session on [renderer] while preserving the emulated
     * state. The core only accepts a renderer change on boot, so the in-game
     * selector is served by a serialize -> restart -> unserialize cycle.
     */
    fun restartWithRenderer(renderer: Int): Boolean = lifecycleLock.withLock lock@{
        val normalized = RendererDefaults.normalizeAndroidRenderer(renderer)
        val previousRenderer = requestedRenderer
        val gamePath = currentGamePath
        if (!isRunning() || gamePath.isNullOrBlank()) {
            requestedRenderer = normalized
            return@lock true
        }
        val biosOnly = currentBiosOnly
        val wasPaused = paused
        val stateFile = context?.let { File(it.cacheDir, "renderer-switch.rstate") }
        val statePath = stateFile?.absolutePath
        val stateSaved = statePath != null && runOnFrameThread {
            sessionLock.withLock {
                session != 0L && bridge.saveState(session, statePath) == 0
            }
        }
        Log.i(TAG, "Renderer restart renderer=" +
            RendererDefaults.coreRendererName(RendererDefaults.toCoreRenderer(normalized)) +
            " stateSaved=$stateSaved")
        shutdownSession()
        requestedRenderer = normalized
        var started = startSession(gamePath, biosOnly)
        if (!started && normalized != previousRenderer) {
            Log.w(TAG, "Renderer restart failed; reverting to " +
                RendererDefaults.coreRendererName(RendererDefaults.toCoreRenderer(previousRenderer)))
            requestedRenderer = previousRenderer
            started = startSession(gamePath, biosOnly)
        }
        try {
            if (statePath != null && started && stateSaved) {
                val restored = runOnFrameThread {
                    sessionLock.withLock {
                        session != 0L && bridge.loadState(session, statePath) == 0
                    }
                }
                if (!restored) Log.w(TAG, "Unable to restore state after renderer switch")
            }
        } finally {
            stateFile?.delete()
        }
        if (started && wasPaused) pause()
        started
    }

    private fun startSession(gamePath: String, biosOnly: Boolean): Boolean {
        val startupStartedAtNanos = System.nanoTime()
        if (!biosOnly && !isSupportedDiscPath(gamePath)) {
            Log.e(TAG, "Unsupported PS1 image: $gamePath")
            return false
        }
        val biosPath = resolveConfiguredBiosPath()
            ?: run {
                Log.e(TAG, "Cannot start: a valid PS1 BIOS was not configured")
                return false
            }
        val stagedBios = stageBios(biosPath) ?: return false
        val requestedCore = RendererDefaults.toCoreRenderer(requestedRenderer)
        // A Vulkan request is attempted first; if the device cannot provide a
        // Vulkan swapchain the session transparently falls back to OpenGL ES.
        val candidates = if (requestedCore == RendererDefaults.CORE_VULKAN)
            listOf(RendererDefaults.CORE_VULKAN, RendererDefaults.CORE_OPENGL)
        else listOf(requestedCore)

        shutdownSession()
        // The native audio ring is shared between sessions; clear it so the new
        // AAudio stream does not replay the tail of the previous game.
        bridge.resetAudioQueue()
        currentGamePath = gamePath
        currentBiosOnly = biosOnly
        running = true
        paused = false
        renderedFirstFrame = false
        sessionStartedAtNanos = startupStartedAtNanos
        val ready = CountDownLatch(1)
        var created = false // Published to the caller by ready.countDown().
        var started = false
        try {
            val output = NativeAudioOutput()
            audioOutput = output
            // Init, load, execution and teardown must share the owner thread.
            // GooseStation rejects page faults from any other pthread.
            worker = thread(name = "EmuCoreR-Frame", isDaemon = true, start = false) {
                try {
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    for (candidate in candidates) {
                        if (createSessionLocked(gamePath, biosOnly, stagedBios, candidate)) {
                            created = true
                            break
                        }
                        if (candidate != candidates.last()) {
                            Log.w(TAG, "Renderer unavailable; trying fallback on core thread")
                        }
                    }
                    if (created) {
                        output.play()
                        Log.i(TAG, "GOOSE-THREAD init/load/run owner tid=" + android.os.Process.myTid())
                    }
                    ready.countDown()
                    if (created) runLoop(output)
                } catch (error: Exception) {
                    Log.e(TAG, "Core owner thread failed", error)
                } finally {
                    running = false
                    try {
                        sessionLock.withLock {
                            if (session != 0L) {
                                bridge.destroySession(session)
                                session = 0L
                            }
                        }
                    } finally {
                        ready.countDown()
                    }
                }
            }
            worker!!.start()
            ready.await()
            started = created && running
            return started
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start frame/audio runtime", error)
            return false
        } finally {
            if (!started) shutdownSession()
        }
    }

    private fun createSessionLocked(gamePath: String, biosOnly: Boolean, stagedBios: String,
                                    coreRenderer: Int): Boolean = sessionLock.withLock {
        // The native core needs a real writable save directory, not a document URI.
        val appContext = context ?: return@withLock false
        val customRoot = NativeApp.customDataRootPath()
        val nativeSave = if (customRoot.isNullOrBlank()) File(appContext.filesDir, "swanstation/save")
            else File(customRoot, "save")
        if ((!nativeSave.exists() && !nativeSave.mkdirs()) || !nativeSave.canWrite()) {
            Log.e(TAG, "Selected native save directory is not writable: $nativeSave")
            return@withLock false
        }
        saveDirectory = nativeSave.absolutePath
        bridge.nativeInit(systemDirectory, saveDirectory, coreAssetsDirectory)
        // Publish core options before retro_init so the core's initial settings
        // load already sees them.
        applyStartOptions(coreRenderer)
        val handle = bridge.createSession()
        if (handle == 0L) return@withLock false
        // Attach the surface before loading content: SET_HW_RENDER fires inside
        // retro_load_game and needs a window to build the render context.
        if (bridge.setSurface(handle, surface, coreRenderer) != 0) {
            Log.e(TAG, "Failed to initialize ${RendererDefaults.coreRendererName(coreRenderer)} renderer")
            bridge.destroySession(handle)
            return@withLock false
        }
        if (bridge.loadBios(handle, stagedBios) != 0) {
            bridge.destroySession(handle)
            return@withLock false
        }
        if (biosOnly) {
            // Boot the core with no content so the GPU/display are created
            // before the frame worker calls retro_run().
            if (bridge.loadBiosOnly(handle) != 0) {
                bridge.destroySession(handle)
                return@withLock false
            }
        } else if (loadDisc(handle, gamePath) != 0) {
            bridge.destroySession(handle)
            return@withLock false
        }
        session = handle
        activeCoreRenderer = coreRenderer
        true
    }

    private fun applyStartOptions(coreRenderer: Int) {
        // The libretro GPU renderer option is read while the core boots; the
        // frontend negotiates the matching hardware context per selection.
        bridge.nativeSetOption("swanstation_GPU_Renderer", rendererOptionName(coreRenderer))
        val upscale = settings["EmuCoreR/Display:Upscale"]?.toFloatOrNull()
            ?: settings["EmuCoreR:UpscaleMultiplier"]?.toFloatOrNull()
        upscale?.let {
            val scale = Math.round(it).coerceIn(1, 32)
            bridge.nativeSetOption("swanstation_GPU_ResolutionScale", scale.toString())
        }
        settings["EmuCore:EnableFastBoot"]?.toBooleanStrictOrNull()?.let { fastBoot ->
            bridge.nativeSetOption("swanstation_BIOS_PatchFastBoot", fastBoot.toString())
        }
        settings["EmuCore:EnableWideScreenPatches"]?.toBooleanStrictOrNull()?.let { widescreen ->
            bridge.nativeSetOption("swanstation_GPU_WidescreenHack", widescreen.toString())
        }
        settings["InputSources:PadVibration"]?.toBooleanStrictOrNull()?.let { rumble ->
            bridge.nativeSetOption("swanstation_Controller_EnableRumble", rumble.toString())
        }
        // Shader-based upscaling. The libretro core does not expose SwanStation
        // post-processing chains, but its texture filters (JINC2/xBR) are the
        // equivalent GPU shaders. A selected shader preset selects the closest
        // filter; otherwise the app's texture filtering mode is translated.
        bridge.nativeSetOption("swanstation_GPU_TextureFilter", swanStationTextureFilter())
        // Warm the batch pipeline matrix on background workers while the
        // game boots; user overrides from the core option store win below.
        bridge.nativeSetOption("swanstation_GPU_ShaderPrecompile", "Lazy")
        pushShaderEffect()
        pushShaderPreset()
        settings["EmuCore/GS:LoadTextureReplacements"]?.toBooleanStrictOrNull()?.let { replacements ->
            bridge.nativeSetOption("swanstation_TextureReplacements_EnableVRAMWriteReplacements",
                replacements.toString())
        }
        settings["EmuCore/GS:PrecacheTextureReplacements"]?.toBooleanStrictOrNull()?.let { preload ->
            bridge.nativeSetOption("swanstation_TextureReplacements_PreloadTextures", preload.toString())
        }
        // Sensible defaults for a handheld.
        bridge.nativeSetOption("swanstation_GPU_UseThread", "true")
        bridge.nativeSetOption("swanstation_CDROM_ReadThread", "true")
        bridge.nativeSetOption("swanstation_Main_ApplyGameSettings", "true")
        // Explicit user choices from the settings / game manager / in-game menu
        // win over every derived default.
        SwanStationOptions.all.forEach { option ->
            SwanStationOptions.value(option.key)?.let { bridge.nativeSetOption(option.key, it) }
        }
        // Full catalogue overrides (settings screen / game manager / in-game
        // menu) win over the curated defaults.
        SwanStationOptions.persistedEntries().forEach { (key, value) ->
            bridge.nativeSetOption(key, value)
        }
        // The core advertises "Borders" (crop every black border) as the default
        // Crop Mode, but its internal fallback is "Overscan". Frontends are
        // expected to seed option defaults; without it games that leave padding
        // inside the active area show black strips around the image (very
        // visible next to the side artwork).
        coreOptionValue("swanstation_Display_CropMode")?.let { cropMode ->
            bridge.nativeSetOption("swanstation_Display_CropMode", cropMode)
        }
        // Seed the core catalogue after inherited defaults. App-owned controls
        // keep their existing preference source; all other settings use core defaults.
        val appOwned = setOf("goosestation_renderer", "goosestation_resolution_scale",
            "goosestation_bios_fast_boot", "goosestation_widescreen_hack",
            "goosestation_cdrom_mute_cd_audio")
        GooseCoreOptions.entries.filter { it.nativeKey !in appOwned }.forEach { entry ->
            bridge.nativeSetOption(entry.option.key,
                SwanStationOptions.value(entry.option.key) ?: entry.option.defaultValue)
        }
        // Internal resolution is owned by the app's per-game upscale setting,
        // so re-assert it after the persisted core-option store so a legacy
        // swanstation_GPU_ResolutionScale entry cannot shadow it.
        upscale?.let { preference ->
            val scale = Math.round(preference).coerceIn(1, 32)
            bridge.nativeSetOption("swanstation_GPU_ResolutionScale", scale.toString())
        }
        // Preserve a full Display selector over the inherited five-value control.
        displayAspectRatioPreference()?.let { pushAspectRatio(it) }
    }

    /** Persists and forwards a single SwanStation core option. */
    fun setCoreOption(key: String, value: String) {
        SwanStationOptions.set(key, value)
        bridge.nativeSetOption(key, value)
    }

    /** Effective value of a core option (user override or core default). */
    fun coreOptionValue(key: String): String? =
        SwanStationOptions.value(key) ?: GooseCoreOptions.option(key)?.defaultValue
            ?: SwanStationCoreOptions.option(key)?.defaultValue

    /**
     * Forwards a core option without persisting it. Used for per-game overrides
     * that must not pollute the global option store.
     */
    fun applyCoreOption(key: String, value: String) {
        bridge.nativeSetOption(key, value)
    }

    /** Persists and forwards the app's aspect-ratio selection (0..4). */
    fun setDisplayAspectRatio(type: Int) {
        val normalized = if (type in ASPECT_RATIO_STRETCH..ASPECT_RATIO_CUSTOM) type else ASPECT_RATIO_AUTO
        settings["EmuCoreR/Display:AspectRatio"] = normalized.toString()
        pushAspectRatio(normalized)
    }

    private fun displayAspectRatioPreference(): Int? {
        return settings["EmuCoreR/Display:AspectRatio"]?.toIntOrNull()
            ?: settings["EmuCore/GS:AspectRatio"]?.toIntOrNull()
    }

    private fun pushAspectRatio(type: Int) {
        SwanStationOptions.value("swanstation_Display_AspectRatio")?.let {
            bridge.nativeSetOption("swanstation_Display_AspectRatio", it)
            return
        }
        val normalized = if (type in ASPECT_RATIO_STRETCH..ASPECT_RATIO_CUSTOM) type else ASPECT_RATIO_AUTO
        bridge.nativeSetOption(
            "swanstation_Display_AspectRatio",
            when (normalized) {
                ASPECT_RATIO_STRETCH -> "Stretch"
                ASPECT_RATIO_AUTO -> "Auto (Game Native)"
                ASPECT_RATIO_4_3 -> "4:3"
                ASPECT_RATIO_16_9 -> "16:9"
                else -> "Custom"
            }
        )
        if (normalized == ASPECT_RATIO_CUSTOM) {
            bridge.nativeSetOption("swanstation_Display_CustomAspectRatioNumerator", "10")
            bridge.nativeSetOption("swanstation_Display_CustomAspectRatioDenominator", "7")
        }
    }

    private fun swanStationTextureFilter(): String {
        val filter = settings["EmuCore/GS:filter"]?.toIntOrNull() ?: 0
        val enabled = settings["EmuCore/GS:ShaderChainEnabled"]?.toBooleanStrictOrNull() == true
        val preset = settings["EmuCore/GS:ShaderChainPreset"].orEmpty().lowercase()
        return when {
            enabled && preset.contains("xbr") -> "xBR"
            enabled && preset.contains("jinc") -> "JINC2"
            enabled && (preset.contains("nearest") || preset.contains("pixel")) -> "Nearest"
            enabled && (preset.contains("smooth") || preset.contains("bilinear") || preset.contains("linear")) ->
                "Bilinear"
            filter <= 0 -> "Nearest"
            filter == 1 -> "Bilinear"
            filter == 2 -> "BilinearBinAlpha"
            else -> "xBR"
        }
    }

    private fun rendererOptionName(coreRenderer: Int): String = when (coreRenderer) {
        RendererDefaults.CORE_VULKAN -> "Vulkan"
        RendererDefaults.CORE_OPENGL -> "OpenGL"
        else -> "Software"
    }

    private fun currentShaderEffect(): Int {
        val enabled = settings["EmuCore/GS:ShaderChainEnabled"]?.toBooleanStrictOrNull() == true
        if (!enabled) return RetroArchShaderEffects.NONE
        return RetroArchShaderEffects.classify(settings["EmuCore/GS:ShaderChainPreset"])
    }

    private fun pushShaderEffect() {
        runCatching { bridge.nativeSetShaderEffect(currentShaderEffect()) }
            .onFailure { Log.w(TAG, "Unable to apply shader effect", it) }
    }

    private fun pushShaderPreset() {
        val enabled = settings["EmuCore/GS:ShaderChainEnabled"]?.toBooleanStrictOrNull() == true
        val preset = settings["EmuCore/GS:ShaderChainPreset"].orEmpty()
        runCatching { bridge.nativeSetShaderPreset(if (enabled) preset else "", enabled) }
            .onFailure { Log.w(TAG, "Unable to apply shader preset", it) }
    }

    private fun stageBios(biosPath: String): String? {
        val source = File(biosPath)
        if (!source.isFile || source.length() != BIOS_BYTES) {
            Log.e(TAG, "BIOS is missing or not 512 KiB: $biosPath")
            return null
        }
        // Keep every imported BIOS available to the core's regional selectors.
        source.parentFile?.listFiles()?.filter { it.isFile && it.length() == BIOS_BYTES }
            ?.forEach { sibling ->
                val destination = File(systemDirectory, sibling.name)
                if (destination.absolutePath != sibling.absolutePath) {
                    runCatching { sibling.copyTo(destination, overwrite = true) }
                        .onFailure { Log.w(TAG, "Unable to stage BIOS ${sibling.name}", it) }
                }
            }
        val target = File(systemDirectory, source.name)
        if (target.absolutePath != source.absolutePath) {
            runCatching { source.copyTo(target, overwrite = true) }
                .onFailure { Log.e(TAG, "Unable to stage BIOS into ${systemDirectory}", it) }
                .getOrNull() ?: return null
        }
        return target.absolutePath
    }

    @Volatile private var fastForwardPressed = false

    private val fastForwardSources = mutableSetOf<String>()

    @Synchronized fun setFastForwardPressed(pressed: Boolean, source: String = "touch") {
        if (pressed && running && !paused) fastForwardSources.add(source)
        else fastForwardSources.remove(source)
        fastForwardPressed = fastForwardSources.isNotEmpty()
    }

    @Synchronized fun resetFastForward() {
        fastForwardSources.clear()
        fastForwardPressed = false
    }

    fun pause() = lifecycleLock.withLock {
        paused = true
        resetFastForward()
        audioOutput?.pause()
    }

    fun resume() = lifecycleLock.withLock {
        if (!running) return@withLock
        audioOutput?.play()
        paused = false
    }

    fun shutdown() = lifecycleLock.withLock {
        shutdownSession()
    }

    private fun shutdownSession() {
        resetFastForward()
        val activeWorker = worker
        check(activeWorker !== Thread.currentThread()) {
            "The frame worker cannot synchronously shut itself down"
        }
        running = false
        audioOutput?.let { output -> runCatching { output.pause() } }
        activeWorker?.interrupt()
        var callerInterrupted = false
        try {
            if (activeWorker != null) {
                while (activeWorker.isAlive) {
                    try {
                        activeWorker.join()
                    } catch (_: InterruptedException) {
                        callerInterrupted = true
                    }
                }
            }
            worker = null
            audioOutput?.let { output -> runCatching { output.release() } }
            audioOutput = null
            sessionLock.withLock {
                if (session != 0L) {
                    bridge.destroySession(session)
                    session = 0L
                }
            }
            releaseDiscDescriptor()
            paused = false
            renderedFirstFrame = false
            sessionStartedAtNanos = 0L
            performanceMetricsSnapshot = null
            _failure.value = null
        } finally {
            if (callerInterrupted) Thread.currentThread().interrupt()
        }
    }

    fun changeDisc(path: String): Boolean {
        if (!isSupportedDiscPath(path)) return false
        return sessionLock.withLock { session != 0L && loadDisc(session, path) == 0 }
    }

    fun saveState(path: String): Boolean {
        val target = File(path)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.saving")
        return try {
            val saved = runOnFrameThread {
                sessionLock.withLock {
                    session != 0L && bridge.saveState(session, temporary.absolutePath) == 0
                }
            }
            saved && writeSaveStateFile(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    fun loadState(path: String): Boolean = lifecycleLock.withLock lifecycle@{
        val file = File(path)
        if (!file.isFile) return@lifecycle false
        val wasPaused = paused
        paused = true
        try {
            audioOutput?.pause()
            val raw = File(file.parentFile, ".${file.name}.loading")
            val prepared = runCatching {
                val bytes = file.readBytes()
                val payload = if (
                    bytes.size > SAVE_STATE_HEADER_BYTES &&
                    readSaveStateMagic(bytes) == SAVE_STATE_MAGIC
                ) {
                    bytes.copyOfRange(SAVE_STATE_HEADER_BYTES, bytes.size)
                } else {
                    bytes
                }
                raw.writeBytes(payload)
            }.isSuccess
            val loaded = prepared && runOnFrameThread {
                sessionLock.withLock {
                    session != 0L && bridge.loadState(session, raw.absolutePath) == 0
                }
            }
            raw.delete()
            if (loaded) {
                audioOutput?.flush()
                renderedFirstFrame = false
            }
            loaded
        } finally {
            if (!wasPaused && running) audioOutput?.play()
            paused = wasPaused
        }
    }

    /** Writes the EmuCoreR state container (magic + version + libretro payload). */
    private fun writeSaveStateFile(rawFile: File, target: File): Boolean {
        val payload = runCatching { rawFile.readBytes() }.getOrNull() ?: return false
        val staging = File(target.parentFile, ".${target.name}.tmp")
        return try {
            staging.outputStream().use { output ->
                val header = ByteArray(SAVE_STATE_HEADER_BYTES)
                for (index in 0 until 4) {
                    header[index] = (SAVE_STATE_MAGIC ushr (index * 8)).toByte()
                    header[4 + index] = (SAVE_STATE_VERSION ushr (index * 8)).toByte()
                }
                output.write(header)
                output.write(payload)
            }
            if (staging.renameTo(target)) {
                true
            } else {
                staging.delete()
                false
            }
        } catch (_: Exception) {
            staging.delete()
            false
        }
    }

    private fun readSaveStateMagic(bytes: ByteArray): Int {
        var value = 0
        for (index in 0 until 4) value = value or ((bytes[index].toInt() and 0xFF) shl (index * 8))
        return value
    }

    @Volatile private var lastCheatFilePath: String? = null

    fun loadCheats(path: String) {
        lastCheatFilePath = path
        sessionLock.withLock { runCatching { bridge.loadCheats(path) } }
    }

    fun clearCheats() {
        lastCheatFilePath = null
        sessionLock.withLock { runCatching { bridge.clearCheats() } }
    }

    fun reloadCheats() {
        val path = lastCheatFilePath ?: return
        sessionLock.withLock { runCatching { bridge.loadCheats(path) } }
    }

    fun setMemoryCardPath(slot: Int, path: String?) {
        if (slot !in 0..1) return
        runCatching { bridge.setMemoryCardPath(slot, path) }
    }

    fun setTextureReplacementsPathOverride(path: String?) {
        runCatching { bridge.setTextureReplacementsPathOverride(path?.takeIf(String::isNotBlank)) }
            .onFailure { Log.w(TAG, "Unable to set texture replacements path", it) }
    }

    fun setPadButtons(port: Int, buttons: Int): Boolean {
        if (port !in 0..1) return false
        pendingPadButtons.set(port, buttons and 0xFFFF)
        return true
    }

    fun setPadAnalog(port: Int, lx: Int, ly: Int, rx: Int, ry: Int): Boolean {
        if (port !in 0..1) return false
        val packed = lx.coerceIn(0, 255) or
            (ly.coerceIn(0, 255) shl 8) or
            (rx.coerceIn(0, 255) shl 16) or
            (ry.coerceIn(0, 255) shl 24)
        pendingPadAnalog.set(port, packed)
        return true
    }

    fun setPadAnalogMode(port: Int, enabled: Boolean): Boolean = sessionLock.withLock {
        if (session == 0L) return@withLock false
        bridge.setPadAnalogMode(session, port, enabled)
        true
    }

    fun togglePadAnalogMode(port: Int): Boolean? = sessionLock.withLock {
        if (session == 0L) return@withLock null
        val state = bridge.getPadState(session, port)
        val analog = (state >= 0) && (state and PAD_ANALOG_MODE_BIT) == 0
        bridge.setPadAnalogMode(session, port, analog)
        analog
    }

    /** Strong/weak rumble latched by the core, as [strong, weak] in 0..1. */
    fun getPadRumble(port: Int): FloatArray? = sessionLock.withLock {
        if (session == 0L || port !in 0..1) return@withLock null
        val state = bridge.getPadState(session, port)
        if (state < 0) return@withLock null
        floatArrayOf(((state shr 8) and 0xFF) / 255f, (state and 0xFF) / 255f)
    }

    fun attachSurface(value: Surface, width: Int, height: Int) {
        surface = value
        surfaceWidth = width
        surfaceHeight = height
        sessionLock.withLock {
            if (session != 0L && bridge.setSurface(session, value, activeCoreRenderer) != 0)
                Log.e(TAG, "Failed to attach ${RendererDefaults.coreRendererName(activeCoreRenderer)} presentation surface")
        }
    }

    fun detachSurface() {
        sessionLock.withLock {
            if (session != 0L && bridge.setSurface(session, null, activeCoreRenderer) != 0)
                Log.w(TAG, "Failed to detach presentation surface")
        }
        surface = null
        surfaceWidth = 0
        surfaceHeight = 0
        renderedFirstFrame = false
    }

    fun displayRect(): FloatArray? {
        if (!renderedFirstFrame || surfaceWidth <= 0 || surfaceHeight <= 0) return null
        // The presenters letterbox using the core's display aspect ratio, which
        // can differ from the raw frame pixel aspect (pixel-aspect games,
        // widescreen overrides, hi-res modes). Ask them for the exact rect so
        // the side artwork never overlaps the emulated image.
        val presented = runCatching { bridge.getPresentRect() }
            .getOrNull()
            ?.takeIf { it.size >= 4 && it[2] > it[0] && it[3] > it[1] }
        if (presented != null) return presented
        val rect = fitRect(surfaceWidth, surfaceHeight, frameWidth, frameHeight)
        return floatArrayOf(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    }

    fun diagnostics(): String = sessionLock.withLock { bridge.getDiagnostics() }

    fun gpuBackendSubmissions(): Long = 0L

    fun updateSetting(section: String, key: String, value: String): Boolean {
        if ((section == "EmuCoreR" || section == "EmuCore/GS") && key == "Renderer") {
            val renderer = value.toIntOrNull() ?: return false
            requestedRenderer = RendererDefaults.normalizeAndroidRenderer(renderer)
            settings["$section:$key"] = value
            return true
        }
        settings["$section:$key"] = value
        forwardCoreSetting(section, key, value)
        return true
    }

    /**
     * Translates the app's existing settings into the real SwanStation
     * libretro option keys and forwards them to the core at runtime. Options the
     * PS1 core does not understand are simply ignored.
     */
    private fun forwardCoreSetting(section: String, key: String, value: String) {
        val bool = value.toBooleanStrictOrNull()
        val target: Pair<String, String>? = when ("$section:$key") {
            "EmuCore/GS:filter" -> value.toIntOrNull()?.let {
                "swanstation_GPU_TextureFilter" to textureFilterName(it)
            }
            // The selected RetroArch shader preset is applied through the core's
            // GPU texture filter (its real hardware "shader" stage), at runtime.
            "EmuCore/GS:ShaderChainEnabled", "EmuCore/GS:ShaderChainPreset" ->
                "swanstation_GPU_TextureFilter" to swanStationTextureFilter()
            "EmuCore:EnableFastBoot" -> bool?.let { "swanstation_BIOS_PatchFastBoot" to it.toString() }
            "EmuCore:EnableWideScreenPatches" ->
                bool?.let { "swanstation_GPU_WidescreenHack" to it.toString() }
            "InputSources:PadVibration" ->
                bool?.let { "swanstation_Controller_EnableRumble" to it.toString() }
            "EmuCoreR/CPU:enableIcacheEmulation" ->
                bool?.let { "swanstation_CPU_RecompilerICache" to it.toString() }
            "EmuCoreR/CPU:cdReadAhead" -> value.toIntOrNull()?.let {
                "swanstation_CDROM_ReadaheadSectors" to it.coerceAtLeast(0).toString()
            }
            "EmuCoreR/Audio:enableCddaAudio" ->
                bool?.let { "swanstation_CDROM_MuteCDAudio" to (!it).toString() }
            "EmuCoreR/Display:Upscale" -> value.toFloatOrNull()?.let {
                "swanstation_GPU_ResolutionScale" to Math.round(it).coerceIn(1, 32).toString()
            }
            "EmuCoreR/Input:multitapMode" -> value.toIntOrNull()?.let {
                val modes = listOf("Disabled", "Port1Only", "Port2Only", "BothPorts")
                "swanstation_ControllerPorts_MultitapMode" to modes[it.coerceIn(0, modes.lastIndex)]
            }
            "EmuCore/GS:LoadTextureReplacements" ->
                bool?.let { "swanstation_TextureReplacements_EnableVRAMWriteReplacements" to it.toString() }
            "EmuCore/GS:PrecacheTextureReplacements" ->
                bool?.let { "swanstation_TextureReplacements_PreloadTextures" to it.toString() }
            "EmuCoreR/Display:AspectRatio" -> value.toIntOrNull()?.let { type ->
                setDisplayAspectRatio(type)
                null
            }
            else -> null
        }
        target?.let { (coreKey, coreValue) ->
            // A saved core selector takes precedence over inherited derived controls.
            val effective = if (coreKey in setOf("swanstation_GPU_TextureFilter",
                    "swanstation_CPU_RecompilerICache", "swanstation_CDROM_ReadaheadSectors"))
                SwanStationOptions.value(coreKey) ?: coreValue else coreValue
            bridge.nativeSetOption(coreKey, effective)
        }
        if (section == "EmuCore/GS" && (key == "ShaderChainEnabled" || key == "ShaderChainPreset")) {
            pushShaderEffect()
            pushShaderPreset()
        }
    }

    private fun textureFilterName(filter: Int): String = when (filter) {
        1 -> "Bilinear"
        2 -> "BilinearBinAlpha"
        else -> if (filter <= 0) "Nearest" else "xBR"
    }

    private fun publishPerformanceMetrics(fps: Double, frames: Int, frameNanos: Long,
                                          audioStats: LongArray?, cpuLoadPercent: Double) {
        if (frames <= 0 || !performanceMetricsEnabled) return
        val softwareRenderer = activeCoreRenderer == RendererDefaults.CORE_SOFTWARE
        val targetFps = 59.94
        val speed = fps / targetFps * 100.0
        val renderer = RendererDefaults.coreRendererName(activeCoreRenderer)
        val frameMs = frameNanos / frames / 1_000_000.0
        val gpuLoad = if (detailedPerformanceMetrics) GpuLoadReader.loadPercent() else null
        val overlay = buildString {
            append(String.format(Locale.US, "FPS:%.1f | Speed:%.1f%% | Target:%.2f", fps, speed, targetFps))
            if (detailedPerformanceMetrics) {
                // The renderer line must end with " HW |" / " SW |" so the
                // overlay recognises it as the active backend and keeps it on
                // its own bottom line instead of duplicating it inline.
                append('\n').append(renderer).append(if (softwareRenderer) " SW |" else " HW |")
                append('\n').append("CPU:Host | ").append(String.format(Locale.US, "%.1f%%", cpuLoadPercent))
                append('\n').append("GPU:Host")
                if (gpuLoad != null) append(String.format(Locale.US, " | %.1f%%", gpuLoad))
                append('\n').append("Res:").append(frameWidth).append('x').append(frameHeight)
                append('\n').append(String.format(Locale.US, "Frame:%.1f ms", frameMs))
                if (audioStats != null && audioStats.size >= 8) {
                    append('\n').append(String.format(Locale.US, "Audio:%d Hz | queue %d", audioStats[2], audioStats[4]))
                }
            }
        }
        performanceMetricsSnapshot = String.format(Locale.US, "%.3f\n%.3f\n%s", fps, speed, overlay)
    }

    private fun paceToAudioClock(output: NativeAudioOutput) {
        val highWater = runCatching { output.pacingHighWaterFrames() }.getOrDefault(0)
        if (highWater <= 0) return
        val deadline = System.nanoTime() + AUDIO_PACING_MAX_WAIT_NANOS
        while (running && !paused && !fastForwardPressed && output.bufferedFrames() > highWater) {
            drainFrameTasks()
            if (System.nanoTime() >= deadline) return
            Thread.sleep(1)
        }
    }

    /**
     * Audio-clock driven frame pacing. Returns [basePeriodNanos] trimmed by up
     * to [AUDIO_PACING_MAX_TRIM] so the queued sample level moves towards
     * [AUDIO_PACING_TARGET_FRACTION] of the output's pacing high water mark.
     * A queue above the target stretches the period (emulation slows down), a
     * queue below it shortens the period (emulation speeds up). The trim is far
     * too small to be heard, but it removes the slow buffer drain that would
     * otherwise end in periodic underruns.
     *
     * When the queue falls below a quarter of the high water mark, the frame
     * period is halved so the buffer refills at up to twice the target rate.
     * The refill is deliberately bounded: dropping the deadline entirely made
     * the reported FPS spike above the configured target after every underrun.
     */
    private fun audioAdjustedFramePeriod(output: NativeAudioOutput, basePeriodNanos: Long): Long {
        val highWater = runCatching { output.pacingHighWaterFrames() }.getOrDefault(0)
        if (highWater <= 0) return basePeriodNanos
        val queued = runCatching { output.bufferedFrames() }.getOrDefault(-1)
        if (queued < 0) return basePeriodNanos
        if (queued < highWater / 4) {
            return (basePeriodNanos / AUDIO_PACING_REFILL_DIVISOR).coerceAtLeast(1L)
        }
        val target = (highWater * AUDIO_PACING_TARGET_FRACTION).toInt()
        val error = (queued - target).toDouble() / highWater.toDouble()
        val trim = (error * AUDIO_PACING_MAX_TRIM).coerceIn(-AUDIO_PACING_MAX_TRIM, AUDIO_PACING_MAX_TRIM)
        return (basePeriodNanos * (1.0 + trim)).toLong()
    }

    private fun runLoop(output: NativeAudioOutput) {
        var metricsStartNanos = System.nanoTime()
        var metricsFrames = 0
        var metricsFrameTotalNanos = 0L
        var metricsStartCpuMs = android.os.Process.getElapsedCpuTime()
        var frameDeadlineNanos = 0L
        var previousTurbo = 1f
        try {
            while (running) {
                // Owns the thread-affine EGL context, so any queued save/load
                // task must wait until the hardware context is ready here.
                bridge.ensureHardwareContext()
                drainFrameTasks()
                val turbo = if (fastForwardPressed && !paused) {
                    (settings["Framerate:TurboScalar"]?.toFloatOrNull() ?: 2f)
                        .takeIf { it.isFinite() }?.coerceIn(2f, 8f) ?: 2f
                } else 1f
                if (turbo != previousTurbo) {
                    frameDeadlineNanos = 0L
                    previousTurbo = turbo
                }
                bridge.setFastForwardMultiplier(kotlin.math.ceil(turbo.toDouble()).toInt())
                if (paused) {
                    Thread.sleep(8)
                    continue
                }
                // Frame limiting also keeps audio in sync; when the user turns
                // it off the emulator runs as fast as the host allows.
                val frameLimitEnabled = settings["EmuCore/GS:FrameLimitEnable"]?.toBooleanStrictOrNull() ?: true
                if (frameLimitEnabled || turbo > 1f) {
                    if (turbo == 1f) paceToAudioClock(output)
                    // A manual target rate overrides the console's reported one.
                    val manualTargetFps = settings["EmuCore/GS:TargetFps"]?.toIntOrNull() ?: 0
                    val frameRate = if (manualTargetFps in 20..120) {
                        manualTargetFps.toDouble()
                    } else {
                        runCatching { bridge.getFrameRate(session) }.getOrDefault(0.0)
                    }
                    if (frameRate > 1.0) {
                        val basePeriodNanos = (1_000_000_000.0 / (frameRate * turbo)).toLong()
                        if (frameDeadlineNanos == 0L) frameDeadlineNanos = System.nanoTime()
                        // Trim the frame period by a fraction of a percent to
                        // keep the sample queue near its target. Emulation then
                        // follows the audio clock, so slow drift and short
                        // scheduling hiccups can no longer drain the buffer.
                        val framePeriodNanos = if (turbo > 1f) basePeriodNanos
                            else audioAdjustedFramePeriod(output, basePeriodNanos)
                        // Sleeping only has millisecond granularity, so a
                        // sleep-only wait can overshoot the frame deadline by
                        // up to a whole millisecond. Sleep while a comfortable
                        // margin remains, then busy-wait the last stretch so
                        // the frame lands on its intended boundary.
                        while (running && !paused) {
                            val remainingNanos = frameDeadlineNanos - System.nanoTime()
                            if (remainingNanos <= 0L) break
                            drainFrameTasks()
                            if (remainingNanos > FRAME_PACING_SPIN_NANOS) Thread.sleep(1)
                        }
                        val afterNanos = System.nanoTime()
                        frameDeadlineNanos += framePeriodNanos
                        if (frameDeadlineNanos < afterNanos - framePeriodNanos * 4) {
                            frameDeadlineNanos = afterNanos + framePeriodNanos
                        }
                    }
                } else {
                    frameDeadlineNanos = 0L
                }
                val t0 = System.nanoTime()
                var skippedPausedFrame = false
                val coreNanos = sessionLock.withLock {
                    if (!running || session == 0L) null else if (paused) {
                        skippedPausedFrame = true
                        null
                    } else {
                        for (port in 0..1) {
                            val buttons = pendingPadButtons.getAndSet(port, -1)
                            if (buttons >= 0) bridge.setPadButtons(session, port, buttons)
                            val analog = pendingPadAnalog.get(port)
                            bridge.setPadAnalog(
                                session,
                                port,
                                analog and 0xFF,
                                (analog ushr 8) and 0xFF,
                                (analog ushr 16) and 0xFF,
                                (analog ushr 24) and 0xFF
                            )
                        }
                        val coreStartNanos = System.nanoTime()
                        bridge.runFrame(session)
                        val elapsed = System.nanoTime() - coreStartNanos
                        bridge.getDisplayRect(session)
                            ?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
                            ?.let {
                                frameWidth = it[2]
                                frameHeight = it[3]
                            }
                        if (!renderedFirstFrame) {
                            renderedFirstFrame = true
                            val startedAt = sessionStartedAtNanos
                            if (startedAt != 0L) {
                                Log.i(TAG, String.format(Locale.US,
                                    "First emulated frame after %.1f ms (core %.1f ms)",
                                    (System.nanoTime() - startedAt) / 1_000_000.0,
                                    elapsed / 1_000_000.0))
                            }
                        }
                        elapsed
                    }
                } ?: if (skippedPausedFrame) continue else break
                val frameNanos = System.nanoTime() - t0

                metricsFrames++
                metricsFrameTotalNanos += frameNanos
                val now = System.nanoTime()
                if (!performanceMetricsEnabled) {
                    metricsStartNanos = now
                    metricsFrames = 0
                    metricsFrameTotalNanos = 0L
                    metricsStartCpuMs = android.os.Process.getElapsedCpuTime()
                } else if (now - metricsStartNanos >= 1_000_000_000L) {
                    val elapsed = now - metricsStartNanos
                    val fps = metricsFrames * 1_000_000_000.0 / elapsed
                    val cpuNowMs = android.os.Process.getElapsedCpuTime()
                    val cpuDeltaMs = (cpuNowMs - metricsStartCpuMs).coerceAtLeast(0L)
                    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                    val cpuLoad = if (elapsed > 0L) {
                        cpuDeltaMs.toDouble() / (elapsed / 1_000_000.0) / cores * 100.0
                    } else {
                        0.0
                    }
                    publishPerformanceMetrics(fps, metricsFrames, metricsFrameTotalNanos,
                        output.stats(), cpuLoad)
                    metricsStartNanos = now
                    metricsStartCpuMs = cpuNowMs
                    metricsFrames = 0
                    metricsFrameTotalNanos = 0L
                }
            }
        } catch (error: InterruptedException) {
            if (running) reportFailure("Emulation worker was interrupted unexpectedly")
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            Log.e(TAG, "Emulation frame loop stopped", error)
            reportFailure("Emulation frame loop stopped: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        } finally {
            drainFrameTasks()
            running = false
        }
    }

    private fun reportFailure(detail: String) {
        if (_failure.compareAndSet(null, RuntimeFailure(detail))) Log.e(TAG, detail)
    }

    private fun loadDisc(handle: Long, gamePath: String): Int {
        if (!gamePath.startsWith("content://")) return bridge.loadDisc(handle, gamePath)
        val appContext = context
        if (appContext != null) {
            val directory = File(appContext.cacheDir, "swanstation-cue/${gamePath.hashCode()}")
            DocumentPathResolver.materializePreparedCue(gamePath, directory)?.let { cuePath ->
                return bridge.loadDisc(handle, cuePath)
            }
            // Single-file images (CHD/ISO/PBP/...) cannot be reopened through a
            // /proc/self/fd symlink under scoped storage, so stream them into
            // app-owned cache with their original extension first.
            DocumentPathResolver.materializeSingleFileDisc(appContext, gamePath, directory)?.let { imagePath ->
                return bridge.loadDisc(handle, imagePath)
            }
        }
        val resolver = context?.contentResolver ?: return -1
        val descriptor = runCatching {
            resolver.openFileDescriptor(Uri.parse(gamePath), "r")
        }.getOrNull() ?: return -1
        releaseDiscDescriptor()
        discDescriptor = descriptor
        // The core picks its disc container by file extension, so expose the
        // live SAF descriptor through a cache symlink that keeps the original
        // extension ("/proc/self/fd/N" alone would be rejected as unknown).
        val extension = discExtensionFor(gamePath)
        val link = context?.cacheDir?.let { File(it, "swanstation-disc/disc-${gamePath.hashCode()}.$extension") }
        if (link != null && createDiscSymlink(link, descriptor.fd)) {
            discLink = link
            val linked = bridge.loadDisc(handle, link.absolutePath)
            if (linked == 0) return 0
            releaseDiscDescriptor()
        }
        return runCatching {
            bridge.loadDiscFd(handle, descriptor.fd, 0L, descriptor.statSize.coerceAtLeast(0L))
        }.onFailure { error ->
            Log.e(TAG, "Unable to open PS1 disc image through SAF: $gamePath", error)
        }.getOrDefault(-1)
    }

    private fun discExtensionFor(gamePath: String): String {
        val name = runCatching {
            context?.let { DocumentPathResolver.getDisplayName(it, gamePath) }
        }.getOrNull().orEmpty()
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() && it.length <= 4 }
            ?: "bin"
    }

    private fun createDiscSymlink(link: File, fd: Int): Boolean = runCatching {
        link.parentFile?.mkdirs()
        if (link.exists()) link.delete()
        Os.symlink("/proc/self/fd/$fd", link.absolutePath)
        true
    }.getOrDefault(false)

    private fun releaseDiscDescriptor() {
        discLink?.let { link -> runCatching { link.delete() } }
        discLink = null
        discDescriptor?.let { descriptor -> runCatching { descriptor.close() } }
        discDescriptor = null
    }

    fun hasDiscMedia(): Boolean = sessionLock.withLock {
        session != 0L && runCatching { bridge.hasDiscMedia(session) }.getOrDefault(false)
    }

    private fun resolveConfiguredBiosPath(): String? {
        findBiosInDirectory(settings["Folders:Bios"], settings["Filenames:BIOS"])
            ?.let { return it }

        val source = settings["EmuCoreR:BiosSource"]
        val appContext = context
        if (appContext != null && !source.isNullOrBlank()) {
            val prepared = DocumentPathResolver.prepareBiosSelection(appContext, source)
            findBiosInDirectory(prepared?.directoryPath, prepared?.fileName)?.let { return it }
        }

        return resolveLocalBiosPath(source)
    }

    private fun resolveLocalBiosPath(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val selected = File(value)
        if (selected.isFile && selected.length() == BIOS_BYTES) return selected.absolutePath
        return findBiosInDirectory(selected.takeIf(File::isDirectory)?.absolutePath, null)
    }

    private fun findBiosInDirectory(directoryPath: String?, preferredFileName: String?): String? {
        if (directoryPath.isNullOrBlank()) return null
        val directory = File(directoryPath)
        if (!directory.isDirectory) return null

        if (!preferredFileName.isNullOrBlank()) {
            val preferred = File(directory, preferredFileName)
            if (preferred.isFile && preferred.length() == BIOS_BYTES) return preferred.absolutePath
        }

        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.length() == BIOS_BYTES }
            .sortedBy { it.name.lowercase() }
            .firstOrNull()
            ?.absolutePath
    }

    private fun isSupportedDiscPath(path: String): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase()
        return extension == "cue" || extension == "bin" || extension == "img" ||
            extension == "iso" || extension == "chd" || extension == "pbp" || extension == "m3u" ||
            extension == "ecm" || extension == "mds" || extension == "psf" ||
            extension == "exe" || extension == "psexe" || extension == "cpe"
    }

    private fun fitRect(containerWidth: Int, containerHeight: Int, contentWidth: Int, contentHeight: Int): Rect {
        val scale = minOf(containerWidth.toFloat() / contentWidth, containerHeight.toFloat() / contentHeight)
        val width = (contentWidth * scale).toInt().coerceAtLeast(1)
        val height = (contentHeight * scale).toInt().coerceAtLeast(1)
        val left = (containerWidth - width) / 2
        val top = (containerHeight - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private const val BIOS_BYTES = 512L * 1024L
    private const val PAD_ANALOG_MODE_BIT = 1 shl 16
    private const val AUDIO_PACING_MAX_WAIT_NANOS = 500_000_000L
    private const val FRAME_PACING_SPIN_NANOS = 2_000_000L
    // Maximum frame-period trim used to follow the audio clock (0.5%, which is
    // an order of magnitude below the audible pitch threshold).
    private const val AUDIO_PACING_MAX_TRIM = 0.005
    // Frame period divisor used while the audio queue refills after an
    // underrun. 2 keeps the catch-up at most twice the target frame rate.
    private const val AUDIO_PACING_REFILL_DIVISOR = 2L
    // Portion of the output high water mark the sample queue is steered to.
    private const val AUDIO_PACING_TARGET_FRACTION = 0.75

    // App aspect-ratio preference values (mirrors the display settings UI).
    private const val ASPECT_RATIO_STRETCH = 0
    private const val ASPECT_RATIO_AUTO = 1
    private const val ASPECT_RATIO_4_3 = 2
    private const val ASPECT_RATIO_16_9 = 3
    private const val ASPECT_RATIO_CUSTOM = 4
}
