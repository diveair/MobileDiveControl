package com.mobiledivecontrol.core

import java.util.Locale

data class CameraRailEntry(
    val key: String,
    val label: String,
    val mode: CameraModeId? = null,
    val opensSecondaryRail: Boolean = false,
    val action: CameraRailAction? = null,
)

enum class CameraRailAction {
    TrackHeading,
    Diagnostics,
}

data class CameraSettingSpec(
    val id: String,
    val label: String,
    val group: String,
    val kind: CameraSettingKind,
    val options: List<String>,
    val defaultValue: String,
    val status: CameraFeatureStatus = CameraFeatureStatus.Confirmed,
    val note: String? = null,
    val supportsSensitivity: Boolean = false,
)

data class CameraModeProfile(
    val mode: CameraModeId,
    val modeName: String,
    val captureType: CameraCaptureType,
    val availableLenses: List<String>,
    val availableResolutions: List<String>,
    val availableFrameRates: List<String> = emptyList(),
    val availableFormatOptions: List<String> = emptyList(),
    val availableExposureControls: List<String> = emptyList(),
    val availableAudioControls: List<String> = emptyList(),
    val availableAssistTools: List<String> = emptyList(),
    val unavailableSettings: List<String> = emptyList(),
    val settings: List<CameraSettingSpec>,
    val status: CameraFeatureStatus = CameraFeatureStatus.Confirmed,
)

object CameraCatalog {
    /**
     * White-balance modes live on the Kelvin ring itself so the housing wheel can reach them
     * from either end without a separate menu. The names are persisted values: keep them stable
     * and use these constants everywhere instead of open-coded strings.
     */
    const val WB_AUTO_CONTINUOUS = "Auto Continuous"
    const val WB_AUTO_UNDERWATER = "Auto Underwater"
    const val WB_AUTO_SHUTTER = "Auto Shutter"

    val primaryRailEntries: List<CameraRailEntry> = listOf(
        // An overlay action, not a capture mode: it never tears down or rebinds CameraX.
        CameraRailEntry("track_heading", "Track Heading", action = CameraRailAction.TrackHeading),
        // Samsung Camera 16.5.02.36 on the reference Galaxy S24 exposes these three modes on
        // its primary rail, followed by the modes in More. Keep this list tied to what is
        // actually installed. Expert RAW remains a download tile in Samsung Camera on this
        // phone, but DiveControl owns an integrated Expert RAW profile, so it remains reachable
        // from the housing even when Samsung's separate package is absent.
        CameraRailEntry("photo", "Photo", CameraModeId.Photo),
        CameraRailEntry("portrait", "Portrait", CameraModeId.Portrait),
        CameraRailEntry("video", "Video", CameraModeId.Video),
        CameraRailEntry("pro", "Pro", CameraModeId.Pro),
        CameraRailEntry("expert_raw", "Expert RAW", CameraModeId.ExpertRaw),
        CameraRailEntry("food", "Food", CameraModeId.Food),
        CameraRailEntry("night", "Night", CameraModeId.Night),
        CameraRailEntry("panorama", "Panorama", CameraModeId.Panorama),
        CameraRailEntry("pro_video", "Pro Video", CameraModeId.ProVideo),
        CameraRailEntry("hyperlapse", "Hyperlapse", CameraModeId.Hyperlapse),
        CameraRailEntry("slow_motion", "Slow Motion", CameraModeId.SlowMotion),
        CameraRailEntry("portrait_video", "Portrait Video", CameraModeId.PortraitVideo),
        // A state screen rather than a capture profile. Keeping it as an action avoids inventing
        // a camera mode with fake lenses/settings while still placing it last in the Modes menu.
        CameraRailEntry("diagnostics", "Diagnostics", action = CameraRailAction.Diagnostics),
    )

    val secondaryModes: List<CameraModeId> = emptyList()

    private val defaultProfile = GalaxyDeviceVariant.S26Ultra

    private val allModeSettings: List<CameraSettingSpec> by lazy {
        CameraModeId.entries.flatMap { mode ->
            profile(mode, defaultProfile).settings
        }
    }

    val defaultSettingValues: Map<String, String> by lazy {
        allModeSettings.associate { it.id to it.defaultValue }
    }

    val defaultSliderSensitivities: Map<String, SliderSensitivity> by lazy {
        allModeSettings
            .filter { it.supportsSensitivity }
            .associate { it.id to SliderSensitivity.DEFAULT }
    }
    val defaultFocusCurveModes: Map<String, FocusCurveMode> by lazy {
        allModeSettings
            .filter { it.id.endsWith(".manual_focus") }
            .associate { it.id to FocusCurveMode.SquareRoot }
    }

    /**
     * Built profiles, keyed by everything they depend on.
     *
     * [profile] is a pure function of (mode, variant) returning immutable data — every consumer
     * reads it or derives a new value with `copy()`, none mutate it — so a shared instance is
     * indistinguishable from a freshly built one. Bounded by the key space itself — every
     * [CameraModeId] (including the hidden legacy modes routed to `hiddenLegacyProfile`) times
     * every [GalaxyDeviceVariant] — so there is nothing to evict. Concurrent because the reducer
     * reaches this from the housing-BLE executor while the UI reaches it from the main thread.
     */
    private val profileCache =
        java.util.concurrent.ConcurrentHashMap<Pair<CameraModeId, GalaxyDeviceVariant>, CameraModeProfile>()

    fun profile(mode: CameraModeId, variant: GalaxyDeviceVariant): CameraModeProfile =
        profileCache.getOrPut(mode to variant) { buildProfile(mode, variant) }

    private fun buildProfile(mode: CameraModeId, variant: GalaxyDeviceVariant): CameraModeProfile = when (mode) {
        CameraModeId.Photo -> photoProfile(variant)
        CameraModeId.Portrait -> portraitProfile()
        CameraModeId.ExpertRaw -> expertRawProfile(variant)
        CameraModeId.Pro -> proProfile(variant)
        CameraModeId.Food -> foodProfile()
        CameraModeId.Panorama -> panoramaProfile(variant)
        CameraModeId.Night -> nightProfile(variant)
        CameraModeId.Burst -> burstProfile(variant)
        CameraModeId.SingleTake -> singleTakeProfile(variant)
        CameraModeId.Hyperlapse -> hyperlapseProfile(variant)
        CameraModeId.Video -> videoProfile(variant)
        CameraModeId.ProVideo -> proVideoProfile(variant)
        CameraModeId.PortraitVideo -> portraitVideoProfile(variant)
        CameraModeId.SlowMotion -> slowMotionProfile(variant)
        CameraModeId.DualRecording -> dualRecordProfile(variant)
        CameraModeId.NightVideo -> nightVideoProfile(variant)
        CameraModeId.SuperSlowMotion,
        CameraModeId.DirectorsView,
        CameraModeId.Macro,
        CameraModeId.BixbyVision,
        CameraModeId.ArZone -> hiddenLegacyProfile(mode, variant)
    }

    fun settingsFor(mode: CameraModeId, variant: GalaxyDeviceVariant): List<CameraSettingSpec> {
        return profile(mode, variant).settings
    }

    private data class SettingsKey(
        val mode: CameraModeId,
        val variant: GalaxyDeviceVariant,
        val lenses: List<String>,
        val capabilities: CameraCapabilities?,
        val videoShutterCapNs: Long? = null,
        val selectedResolution: String? = null,
    )

    /**
     * Derived setting lists, keyed by every input that shapes them.
     *
     * All four key components are immutable value types with structural equality, and the result
     * is rebuilt from them alone, so a cache hit is byte-identical to a rebuild. The size guard
     * is a safety valve only: the real key space is a handful of entries (modes actually visited
     * x one lens set x one capability probe per bind), and clearing merely costs a rebuild.
     */
    private val settingsCache = java.util.concurrent.ConcurrentHashMap<SettingsKey, List<CameraSettingSpec>>()

    private fun cachedSettings(key: SettingsKey, build: () -> List<CameraSettingSpec>): List<CameraSettingSpec> {
        if (settingsCache.size > 256) settingsCache.clear()
        return settingsCache.getOrPut(key, build)
    }

    /**
     * Returns settings with lens options overridden by dynamically detected lenses
     * from the device hardware. Used by the UI to show only available lenses.
     */
    fun settingsFor(
        mode: CameraModeId,
        variant: GalaxyDeviceVariant,
        detectedLenses: List<String>,
    ): List<CameraSettingSpec> = cachedSettings(SettingsKey(mode, variant, detectedLenses, null)) {
        val baseSettings = settingsFor(mode, variant)
        if (detectedLenses.isEmpty()) {
            baseSettings
        } else {
            baseSettings.map { spec ->
                if (spec.id.endsWith(".lens")) {
                    // A mode can expose only a subset of the phone's lenses (Portrait is 1x/2x,
                    // Panorama is 0.6x/1x, Slow Motion is 0.6x/1x/3x on the reference S24).
                    // Replacing that subset with every detected lens made the menu disagree with
                    // Samsung immediately after the capability probe completed.
                    val supportedForMode = spec.options.filter { it in detectedLenses }
                    val options = supportedForMode.ifEmpty { spec.options }
                    val default = spec.defaultValue.takeIf { it in options } ?: options.first()
                    spec.copy(options = options, defaultValue = default)
                } else {
                    spec
                }
            }
        }
    }

    /**
     * [settingsFor] additionally clipped to what the probed hardware can honour. The rule is the
     * product's own: never render a dead control. Options outside a probed range are dropped;
     * a control the hardware cannot drive at all disappears. With no probe (simulator, tests,
     * pre-bind frames) the static catalog stands unchanged.
     */
    fun settingsFor(
        mode: CameraModeId,
        variant: GalaxyDeviceVariant,
        detectedLenses: List<String>,
        capabilities: CameraCapabilities?,
        videoShutterCapNs: Long? = null,
        selectedResolution: String? = null,
    ): List<CameraSettingSpec> {
        val base = settingsFor(mode, variant, detectedLenses)
        if (capabilities == null && videoShutterCapNs == null) return base
        // This is the reducer's per-tick call. Uncached it re-filtered every ladder on every
        // focus nudge, each option running a parse.
        return cachedSettings(
            SettingsKey(
                mode,
                variant,
                detectedLenses,
                capabilities,
                videoShutterCapNs,
                selectedResolution,
            ),
        ) {
            base.mapNotNull { spec ->
                applyCapabilities(
                    spec,
                    capabilities,
                    videoShutterCapNs,
                    selectedResolution,
                )
            }
        }
    }

    /**
     * The slowest shutter this camera state may honestly offer: one frame period in a video
     * mode, nothing anywhere else. This is exactly the native rule — ProUtil.getMaxVideoShutterSpeed
     * caps the video dial at the longest table entry not exceeding the recording frame period
     * (1/30 at 24-30 fps, 1/50 at 50, 1/60 at 60, 1/125 at 100/120), which filtering the ladder by
     * `ns <= period` reproduces for every rate — and the native app also demotes an over-cap
     * stored setting and pins SENSOR_FRAME_DURATION at write time, both mirrored in the runtime
     * controller. Without this clamp, choosing 1/8 while recording at 30 fps stretched the frame
     * duration and dragged the recording toward 8 fps.
     */
    fun videoShutterCapNs(camera: CameraState): Long? {
        if (profile(camera.activeMode, camera.deviceVariant).captureType != CameraCaptureType.Video) return null
        val frameRateId = modeKey(camera.activeMode) + ".frame_rate"
        val fps = captureFrameRateFps(
            camera.settingValues[frameRateId] ?: defaultSettingValues[frameRateId],
        ) ?: return null
        if (fps <= 0) return null
        // ROUNDED, not truncated: the native rule admits the table entry that IS one frame
        // period (1/60 at 60 fps), and 1/60 spells 16666667 ns while truncating division gives
        // 16666666 — a one-nanosecond error that would evict the native cap rung itself.
        return Math.round(1_000_000_000.0 / fps)
    }

    /** Capture column from either `30fps` or `240fps/23.976fps playback`. */
    fun captureFrameRateFps(value: String?): Int? = value
        ?.substringBefore('/')
        ?.trim()
        ?.removeSuffix("fps")
        ?.trim()
        ?.toIntOrNull()

    /** Playback column; ordinary entries play at their capture rate. */
    fun playbackFrameRateFps(value: String?): Double? {
        if (value == null) return null
        val playback = value.substringAfter('/', missingDelimiterValue = value)
            .substringBefore("fps")
            .trim()
            .toDoubleOrNull()
        return playback ?: captureFrameRateFps(value)?.toDouble()
    }

    /** [settingsFor] with every clamp the CameraState itself implies — the one call the reducer and UI share. */
    fun settingsFor(camera: CameraState): List<CameraSettingSpec> {
        val prefix = modeKey(camera.activeMode)
        return settingsFor(
            camera.activeMode,
            camera.deviceVariant,
            camera.detectedLenses,
            camera.capabilities,
            videoShutterCapNs(camera),
            camera.settingValues["$prefix.resolution"] ?: defaultSettingValues["$prefix.resolution"],
        )
    }

    private fun applyCapabilities(
        spec: CameraSettingSpec,
        caps: CameraCapabilities?,
        videoShutterCapNs: Long? = null,
        selectedResolution: String? = null,
    ): CameraSettingSpec? {
        fun clip(keep: (String) -> Boolean): CameraSettingSpec? {
            val kept = spec.options.filter(keep)
            if (kept.isEmpty()) return null
            val default = if (spec.defaultValue in kept) spec.defaultValue else kept.first()
            return spec.copy(options = kept, defaultValue = default)
        }
        return when {
            spec.id.endsWith(".manual_focus") ->
                if (caps?.manualFocusSupported == false) null else spec
            spec.id.endsWith(".iso") && caps?.isoMin != null && caps.isoMax != null ->
                clip { option ->
                    val value = option.filter { it.isDigit() }.toIntOrNull()
                    value == null || value in caps.isoMin..caps.isoMax
                }
            spec.id.endsWith(".shutter_speed") && (
                (caps?.exposureMinNs != null && caps.exposureMaxNs != null) || videoShutterCapNs != null
                ) ->
                clip { option ->
                    val ns = shutterOptionNanos(option) ?: return@clip true
                    val floor = caps?.exposureMinNs ?: Long.MIN_VALUE
                    val ceiling = minOf(
                        caps?.exposureMaxNs ?: Long.MAX_VALUE,
                        videoShutterCapNs ?: Long.MAX_VALUE,
                    )
                    ns in floor..ceiling
                }
            (spec.id.endsWith(".exposure_value") || spec.id.endsWith(".exposure_compensation")) &&
                caps?.evMin != null && caps.evMax != null ->
                clip { option ->
                    val ev = option.replace("+", "").toDoubleOrNull()
                    ev == null || ev in caps.evMin..caps.evMax
                }
            spec.id.endsWith(".frame_rate") && !caps?.availableVideoFrameRates.isNullOrEmpty() -> {
                val rates = caps?.videoFrameRatesByResolution
                    ?.get(selectedResolution)
                    ?.takeIf { it.isNotEmpty() }
                    ?: caps!!.availableVideoFrameRates
                clip { option ->
                    val fps = captureFrameRateFps(option)
                    fps in rates ||
                        (spec.id == "slow_motion.frame_rate" && fps == 48 && 60 in rates)
                }
            }
            spec.id.endsWith(".resolution") && !caps?.availableVideoResolutions.isNullOrEmpty() ->
                // Keep every resolution exposed for the selected camera. If the current FPS is
                // incompatible, the reducer moves it to the closest supported rate as the user
                // selects this resolution; hiding valid resolutions made the menu look incomplete.
                clip { option -> option in caps!!.availableVideoResolutions }
            spec.id.endsWith(".video_stabilization") && caps?.videoStabilizationSupported == false -> null
            spec.id.endsWith(".save_format") && caps?.ultraHdrJpegSupported == false ->
                clip { option -> option != "Ultra HDR JPEG" }
            // No .white_balance branch by design: kelvin WB is applied app-side through
            // COLOR_CORRECTION_GAINS, not through a CameraCharacteristics range, the ladder's
            // 2300..10000 bounds already match colorGainsForKelvin's own clamp, and the native
            // app applies no per-lens or per-mode clipping to kelvin either.
            else -> spec
        }
    }

    /**
     * The one shutter-label parser. "1/8000" → 125000 ns, "2\"" / "2s" / bare "2" → 2 s in ns;
     * null for "Auto", for unparseable words and for anything non-positive.
     *
     * Public because it must be the ONLY one. There used to be three near-copies — this, the
     * reducer's effect mapper and the runtime controller's capture-request builder — and they
     * disagreed about bare seconds, so a label this parser accepted could pass capability
     * clipping, reach the strip, and then produce neither an effect nor a capture-request change:
     * auto-exposure silently left on while the HUD read a manual shutter. That is precisely the
     * "never imply certainty where none exists" failure, so the parsers are now one function.
     */
    fun shutterOptionNanos(option: String): Long? {
        val text = option.trim().removeSuffix("\"").removeSuffix("s").trim()
        if (text.isEmpty()) return null
        val seconds = if (text.contains('/')) {
            val pieces = text.split('/')
            if (pieces.size != 2) return null
            val numerator = pieces[0].trim().toDoubleOrNull() ?: return null
            val denominator = pieces[1].trim().toDoubleOrNull() ?: return null
            if (denominator == 0.0) return null
            numerator / denominator
        } else {
            text.toDoubleOrNull() ?: return null
        }
        if (!seconds.isFinite() || seconds <= 0.0) return null
        return Math.round(seconds * 1_000_000_000.0)
    }

    // --- the wheel's assignment -----------------------------------------------------------

    const val SLIDER_ASSIGNMENT_SUFFIX = ".slider_assignment"
    const val SLIDER_TARGET_ZOOM = "Zoom"

    /** Assignment label → the setting-id suffixes it drives, in lookup order. */
    private val sliderTargetSuffixes: Map<String, List<String>> = mapOf(
        "Focus" to listOf(".manual_focus"),
        "ISO" to listOf(".iso"),
        "Shutter" to listOf(".shutter_speed"),
        "Exposure" to listOf(".exposure_value", ".exposure_compensation"),
        "White balance" to listOf(".white_balance"),
    )

    /**
     * The Slider tile: a per-mode pseudo-setting that never reaches the camera. Its choice list
     * is only what this mode actually offers (plus Zoom, which needs no spec), and its value
     * rides in [CameraState.settingValues] so it persists like everything else.
     */
    fun sliderAssignmentSpec(mode: CameraModeId, settings: List<CameraSettingSpec>): CameraSettingSpec {
        val options = buildList {
            sliderTargetSuffixes.forEach { (label, suffixes) ->
                if (settings.any { spec -> suffixes.any { spec.id.endsWith(it) } }) add(label)
            }
            add(SLIDER_TARGET_ZOOM)
        }
        val ordered = if ("Focus" in options) listOf("Focus") + (options - "Focus") else options
        return CameraSettingSpec(
            id = modeKey(mode) + SLIDER_ASSIGNMENT_SUFFIX,
            label = "Slider",
            group = "Control",
            kind = CameraSettingKind.Choice,
            options = ordered,
            defaultValue = ordered.first(),
        )
    }

    /** The spec the wheel currently drives, or null when the assignment is Zoom. */
    fun assignedSliderSpec(camera: CameraState): CameraSettingSpec? {
        val settings = settingsFor(camera)
        val assignSpec = sliderAssignmentSpec(camera.activeMode, settings)
        val choice = camera.settingValues[assignSpec.id] ?: assignSpec.defaultValue
        val suffixes = sliderTargetSuffixes[choice] ?: return null
        return settings.firstOrNull { spec -> suffixes.any { spec.id.endsWith(it) } }
    }

    /** Synthesized per-mode Choice that flips the wheel's travel over the focus scale. */
    fun focusDirectionSpec(focusSettingId: String): CameraSettingSpec = CameraSettingSpec(
        id = focusSettingId.removeSuffix(".manual_focus") + ".focus_direction",
        label = "Wheel Direction",
        group = "Focus",
        kind = CameraSettingKind.Choice,
        options = listOf("Normal", "Reversed"),
        defaultValue = "Normal",
    )

    /**
     * How fast a focus PULL travels, 1 (slowest) to 100 (fastest), per direction.
     *
     * A pull is what runs when focus jumps rather than steps: leaving autofocus, restoring a
     * session, or any move larger than a detent. The two directions are separately settable
     * because they are not symmetrical in practice — racking outward to infinity and racking
     * inward to a close subject want different pacing to read well on screen.
     */
    fun focusRampSpec(focusSettingId: String, inward: Boolean): CameraSettingSpec = CameraSettingSpec(
        id = focusSettingId.removeSuffix(".manual_focus") +
            if (inward) ".focus_ramp_in" else ".focus_ramp_out",
        label = if (inward) "Inward Focus Ramp" else "Outward Focus Ramp",
        group = "Focus",
        kind = CameraSettingKind.Slider,
        options = (1..100).map { it.toString() },
        defaultValue = FOCUS_RAMP_DEFAULT,
    )

    /** Ramp rate 1..100 for the active mode's focus, per direction. */
    fun focusRampLevel(camera: CameraState, focusSettingId: String, inward: Boolean): Int {
        val spec = focusRampSpec(focusSettingId, inward)
        return (camera.settingValues[spec.id] ?: spec.defaultValue).toIntOrNull()?.coerceIn(1, 100)
            ?: FOCUS_RAMP_DEFAULT.toInt()
    }

    const val FOCUS_RAMP_DEFAULT = "60"

    /** Whether the diver flipped the focus wheel for the active mode. */
    fun focusWheelReversed(camera: CameraState): Boolean {
        val focus = settingsFor(camera)
            .firstOrNull { it.id.endsWith(".manual_focus") } ?: return false
        val spec = focusDirectionSpec(focus.id)
        return (camera.settingValues[spec.id] ?: spec.defaultValue) == "Reversed"
    }

    private fun modeKey(mode: CameraModeId): String =
        primaryRailEntries.firstOrNull { it.mode == mode }?.key ?: mode.name.lowercase()

    fun primaryIndexForMode(mode: CameraModeId): Int {
        val direct = primaryRailEntries.indexOfFirst { it.mode == mode }
        return if (direct >= 0) direct else 0
    }

    fun secondaryIndexForMode(mode: CameraModeId): Int {
        return if (secondaryModes.isEmpty()) 0 else secondaryModes.indexOf(mode).coerceAtLeast(0)
    }

    fun highlightedPrimaryEntry(camera: CameraState): CameraRailEntry {
        return primaryRailEntries[camera.highlightedPrimaryIndex.coerceIn(0, primaryRailEntries.lastIndex)]
    }

    fun highlightedSecondaryMode(camera: CameraState): CameraModeId {
        return if (secondaryModes.isEmpty()) {
            camera.activeMode
        } else {
            secondaryModes[camera.highlightedSecondaryIndex.coerceIn(0, secondaryModes.lastIndex)]
        }
    }

    fun selectedSetting(camera: CameraState): CameraSettingSpec? {
        val items = settingsBarItems(camera)
        if (items.isEmpty()) {
            return null
        }
        val cursor = camera.settingsCursor.coerceIn(0, items.lastIndex)
        val item = items.getOrNull(cursor)
        return if (item is BottomBarItem.Setting) item.spec else null
    }

    fun defaultSettingsCursor(
        mode: CameraModeId,
        variant: GalaxyDeviceVariant,
        showMore: Boolean = false,
    ): Int {
        val items = settingsBarItems(mode, variant, showMore)
        val modesIndex = items.indexOfFirst { it is BottomBarItem.ModesButton }
        return modesIndex.coerceAtLeast(0)
    }

    fun launchCameraState(
        activeMode: CameraModeId,
        deviceVariant: GalaxyDeviceVariant = GalaxyDeviceVariant.S26Ultra,
        settingValues: Map<String, String> = defaultSettingValues,
        sliderSensitivities: Map<String, SliderSensitivity> = defaultSliderSensitivities,
        focusCurveModes: Map<String, FocusCurveMode> = defaultFocusCurveModes,
        showMoreSettings: Boolean = false,
        detectedLenses: List<String> = emptyList(),
    ): CameraState {
        return CameraState(
            activeMode = activeMode,
            focusedZone = CameraUiZone.SettingsPanel,
            modeRailReturnZone = CameraUiZone.SettingsPanel,
            railLevel = if (secondaryModes.contains(activeMode)) CameraRailLevel.Secondary else CameraRailLevel.Primary,
            highlightedPrimaryIndex = primaryIndexForMode(activeMode),
            highlightedSecondaryIndex = secondaryIndexForMode(activeMode),
            settingsCursor = defaultSettingsCursor(activeMode, deviceVariant, showMoreSettings),
            settingsEditing = false,
            sliderEditTarget = SliderEditTarget.Value,
            settingValues = settingValues,
            sliderSensitivities = sliderSensitivities,
            focusCurveModes = focusCurveModes,
            detectedLenses = detectedLenses,
            deviceVariant = deviceVariant,
            showMoreSettings = showMoreSettings,
        )
    }

    /**
     * The persistent horizontal bar template, identical whether Options is open or closed:
     *
     *   [Options] · Lens · Exposure · Shutter · ISO · [MODE] · Focus · WB · Slider · Gallery
     *
     * Every other mode setting lives in [optionsMenuSettings]. Keeping the two collections
     * independent is deliberate: opening the vertical Options panel must never reflow this bar,
     * move its cursor, or replace controls the diver is operating by muscle memory.
     */
    fun settingsBarItems(
        mode: CameraModeId,
        variant: GalaxyDeviceVariant,
        showMore: Boolean,
        detectedLenses: List<String> = emptyList(),
        capabilities: CameraCapabilities? = null,
        videoShutterCapNs: Long? = null,
    ): List<BottomBarItem> {
        val allSettings = settingsFor(mode, variant, detectedLenses, capabilities, videoShutterCapNs)

        fun find(vararg suffixes: String): CameraSettingSpec? =
            allSettings.firstOrNull { spec -> suffixes.any { spec.id.endsWith(it) } }

        val lens = find(".lens") ?: synthesizedLensSpec(mode, variant, detectedLenses)
        val ev = find(".exposure_value", ".exposure_compensation")
        val shutter = find(".shutter_speed")
        val iso = find(".iso")
        val focus = find(".manual_focus")
        val wb = find(".white_balance")
        // Slow Motion's native Video size sheet buries 120/240 FPS behind another tap. In a
        // housing that is needlessly expensive, so FPS is promoted to the persistent bar and is
        // adjusted by the same Up/Down path as every other quick control.
        val priorityFrameRate = find(".frame_rate").takeIf { mode == CameraModeId.SlowMotion }

        val spine = listOfNotNull(lens, ev, shutter, iso, priorityFrameRate, focus, wb)
        val extras = allSettings.filter { it !in spine }
        val slider = sliderAssignmentSpec(mode, allSettings)

        return buildList {
            if (extras.isNotEmpty()) {
                add(BottomBarItem.MoreSettings)
            }
            listOfNotNull(lens, ev, shutter, iso, priorityFrameRate).forEach { add(BottomBarItem.Setting(it)) }
            add(BottomBarItem.ModesButton)
            listOfNotNull(focus, wb).forEach { add(BottomBarItem.Setting(it)) }
            add(BottomBarItem.Setting(slider))
            add(BottomBarItem.GalleryShortcut)
        }
    }

    /** The bar exactly as the reducer must see it: same lenses, same capabilities, same video clamp as the UI. */
    fun settingsBarItems(camera: CameraState): List<BottomBarItem> = settingsBarItems(
        mode = camera.activeMode,
        variant = camera.deviceVariant,
        showMore = camera.showMoreSettings,
        detectedLenses = camera.detectedLenses,
        capabilities = camera.capabilities,
        videoShutterCapNs = videoShutterCapNs(camera),
    )

    /**
     * Settings owned by the vertical Options panel. The six manual controls already present on
     * the horizontal bar are excluded by identity, not by group name, so FPS, resolution, audio,
     * assist tools and metadata remain available even when they are capture-critical controls.
     */
    fun optionsMenuSettings(camera: CameraState): List<CameraSettingSpec> {
        val allSettings = settingsFor(camera)
        fun isHorizontalSpine(spec: CameraSettingSpec): Boolean =
            spec.id.endsWith(".lens") ||
                spec.id.endsWith(".exposure_value") ||
                spec.id.endsWith(".exposure_compensation") ||
                spec.id.endsWith(".shutter_speed") ||
                spec.id.endsWith(".iso") ||
                spec.id.endsWith(".manual_focus") ||
                spec.id.endsWith(".white_balance") ||
                spec.id.endsWith(".focus_peaking") ||
                spec.id.endsWith(".focus_curve") ||
                (camera.activeMode == CameraModeId.SlowMotion && spec.id.endsWith(".frame_rate"))

        val extras = allSettings.filterNot(::isHorizontalSpine)
        if (camera.activeMode !in setOf(CameraModeId.Pro, CameraModeId.ProVideo)) return extras

        val locations = camera.recordingSaveLocations.ifEmpty { listOf(RecordingSaveLocation.Default) }
        val saveLocation = CameraSettingSpec(
            id = modeKey(camera.activeMode) + ".save_location",
            label = "Save location",
            group = "File",
            kind = CameraSettingKind.Choice,
            options = locations.map { it.name },
            defaultValue = camera.recordingSaveLocation.name,
            note = "MediaStore album; available albums are loaded when Options opens.",
        )
        val fileInsert = extras.indexOfFirst {
            it.id.endsWith(".save_format") || it.id.endsWith(".aspect_ratio")
        }.let { if (it < 0) 0 else it + 1 }
        return extras.toMutableList().apply { add(fileInsert.coerceIn(0, size), saveLocation) }
    }

    fun selectedOptionsSetting(camera: CameraState): CameraSettingSpec? {
        val settings = optionsMenuSettings(camera)
        return settings.getOrNull(camera.optionsMenuCursor.coerceIn(0, settings.lastIndex))
    }

    /**
     * Modes whose profile carries no `.lens` choice still get the Lens tile — built from the
     * probed lenses, or the variant's stock list. One tile, one mechanism, every mode.
     */
    private fun synthesizedLensSpec(
        mode: CameraModeId,
        variant: GalaxyDeviceVariant,
        detectedLenses: List<String>,
    ): CameraSettingSpec? {
        val options = (detectedLenses.ifEmpty { photoLenses(variant) })
        if (options.isEmpty()) return null
        return CameraSettingSpec(
            id = modeKey(mode) + ".lens",
            label = "Lens",
            group = "Optics",
            kind = CameraSettingKind.Choice,
            options = options,
            defaultValue = options.first(),
        )
    }

    fun currentValue(camera: CameraState, spec: CameraSettingSpec): String {
        if (spec.id.endsWith(".save_location")) return camera.recordingSaveLocation.name
        val value = camera.settingValues[spec.id] ?: spec.defaultValue
        return if (spec.id.endsWith(".grid") || spec.id.endsWith(".guides")) {
            canonicalGuideValue(value)
        } else {
            value
        }
    }

    /** Keeps guide selections made by older builds valid after the guide names were clarified. */
    fun canonicalGuideValue(value: String): String = when (value) {
        "3x3", "3×3 Grid" -> "Rule of Thirds"
        "Golden Ratio" -> "Phi Grid"
        "Fibonacci Left" -> "Fibonacci Spiral Left"
        "Fibonacci Right" -> "Fibonacci Spiral Right"
        "Lines & Patterns" -> "Lines and Patterns"
        else -> value
    }

    fun isWhiteBalanceAuto(value: String?): Boolean =
        value == WB_AUTO_CONTINUOUS || value == WB_AUTO_UNDERWATER ||
            value == WB_AUTO_SHUTTER || value == "Auto"

    /** Modes in which the phone HAL, rather than DiveControl's AU estimator, owns AWB. */
    fun isWhiteBalanceOemAuto(value: String?): Boolean =
        value == WB_AUTO_CONTINUOUS || value == WB_AUTO_SHUTTER || value == "Auto"

    fun isWhiteBalanceAutoUnderwater(value: String?): Boolean = value == WB_AUTO_UNDERWATER

    fun isWhiteBalanceAutoShutter(value: String?): Boolean = value == WB_AUTO_SHUTTER

    /** The four field controls whose physical wheel topology is a ring, not a rail. */
    fun isCircularSlider(spec: CameraSettingSpec): Boolean {
        if (spec.kind != CameraSettingKind.Slider) return false
        return spec.id.endsWith(".iso") ||
            spec.id.endsWith(".shutter_speed") ||
            spec.id.endsWith(".white_balance") ||
            spec.id.endsWith(".exposure_value") ||
            spec.id.endsWith(".exposure_compensation") ||
            spec.id.endsWith(".exposure")
    }

    /**
     * When the EV dial has no authority and flips into a read-only meter.
     *
     * The native rule (ProBasePresenter.updateEvState) locks EV only when BOTH ISO and shutter
     * are manual, because with one axis manual Samsung keeps AE metering the other through the
     * vendor aeExtraMode priority channel — and EV still steers that metering. That channel is
     * closed to third parties, so OUR lone-manual-axis implementation turns AE fully off and
     * freezes the other axis; the compensation index then does nothing the moment EITHER axis is
     * manual. The lock therefore follows what this app's sensor actually honours, not the native
     * UI's wider window — a dial that turns while the sensor ignores it is exactly the false
     * certainty CLAUDE.md forbids. This is also precisely the write-side gate: the controller
     * writes EV only while neither axis is manual (`!aeOff`), and the two rules must agree.
     */
    fun evMeterLocked(camera: CameraState, spec: CameraSettingSpec): Boolean {
        if (!spec.id.endsWith(".exposure_value") && !spec.id.endsWith(".exposure_compensation")) return false
        val prefix = spec.id.substringBeforeLast('.')
        val iso = camera.settingValues["$prefix.iso"]
        val shutter = camera.settingValues["$prefix.shutter_speed"]
        return (iso != null && iso != "Auto") || (shutter != null && shutter != "Auto")
    }

    /**
     * Re-spells the four exposure scales' CURRENT values onto the capability-clipped ladders.
     * Run when the hardware ranges arrive (UpdateCameraCapabilities).
     *
     * Persistence snaps against the FULL native tables because it cannot know this device, so a
     * stored value can be a real native rung the probed window then removes — "0.5\"" on a
     * sensor whose live-preview ceiling is 0.15 s. Left alone, the strip keeps showing a value
     * the write path clamps away, and the reducer resolves the unfound value from index 0 on the
     * next detent. The snap is nearest-by-value in each scale's own metric (log-space for the
     * two multiplicative scales), never lands on "Auto", and passes through anything already on
     * its clipped ladder — so it is idempotent and a no-op on the simulator.
     */
    fun resnapToClippedLadders(camera: CameraState): CameraState {
        var values = camera.settingValues
        CameraModeId.entries.forEach { mode ->
            val clippedSettings = settingsFor(camera.copy(activeMode = mode))
            clippedSettings
                .filter { spec -> spec.kind == CameraSettingKind.Slider }
                .forEach specLoop@{ spec ->
                    val magnitude: (String) -> Double? = when {
                        spec.id.endsWith(".iso") ->
                            { option -> option.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { kotlin.math.ln(it) } }
                        spec.id.endsWith(".shutter_speed") ->
                            { option -> shutterOptionNanos(option)?.takeIf { it > 0L }?.let { kotlin.math.ln(it.toDouble()) } }
                        spec.id.endsWith(".white_balance") ->
                            { option -> option.removeSuffix("K").toDoubleOrNull() }
                        spec.id.endsWith(".exposure_value") || spec.id.endsWith(".exposure_compensation") ->
                            { option -> option.replace("+", "").toDoubleOrNull() }
                        else -> return@specLoop
                    }
                    val stored = values[spec.id] ?: return@specLoop
                    if (stored in spec.options) return@specLoop
                    if (spec.id.endsWith(".white_balance") && stored == "Auto") {
                        values = values + (spec.id to WB_AUTO_CONTINUOUS)
                        return@specLoop
                    }
                    val target = magnitude(stored) ?: return@specLoop
                    val nearest = spec.options
                        .mapNotNull { option -> magnitude(option)?.let { option to it } }
                        .minByOrNull { (_, value) -> kotlin.math.abs(value - target) }
                        ?.first ?: return@specLoop
                    values = values + (spec.id to nearest)
                }
            clippedSettings
                .filter { spec -> spec.kind != CameraSettingKind.Slider }
                .forEach { spec ->
                    val stored = values[spec.id] ?: return@forEach
                    if (stored !in spec.options) {
                        values = values + (spec.id to spec.defaultValue)
                    }
                }
        }
        return if (values === camera.settingValues) camera else camera.copy(settingValues = values)
    }

    /**
     * The native auto-to-manual handoff (ProSliderContainerPresenter.onScrollStart): the first
     * movement out of Auto lands on the rung nearest what auto-exposure / auto-white-balance is
     * metering RIGHT NOW, not on the first rung of the dial. Distances are linear in the native
     * app's own metric (findNearestIso, findNearestShutterSpeed, kelvin/100 rounding) and the
     * candidates are the mode's CLIPPED options, so the seed can never name an unreachable rung.
     * Null when there is no metered seed — no telemetry yet, or a setting that has no Auto
     * conversion — and the caller steps the ladder normally instead.
     */
    fun meteredSeedValue(camera: CameraState, spec: CameraSettingSpec): String? {
        val metered = camera.meteredExposure
        return when {
            spec.id.endsWith(".iso") -> metered.iso?.let { iso ->
                spec.options.mapNotNull { option -> option.toIntOrNull()?.let { option to it } }
                    .minByOrNull { (_, value) -> kotlin.math.abs(value - iso) }?.first
            }
            spec.id.endsWith(".shutter_speed") -> metered.shutterNs?.let { ns ->
                nearestShutterOption(ns, spec.options)
            }
            spec.id.endsWith(".white_balance") -> metered.wbKelvin?.let { kelvin ->
                nearestWhiteBalanceOption(kelvin, spec.options)
            }
            else -> null
        }
    }

    fun focusAssistSettingId(focusSettingId: String): String? = when (focusSettingId) {
        "photo.manual_focus" -> "photo.focus_peaking"
        "expert.manual_focus" -> "expert.focus_peaking"
        "pro.manual_focus" -> "pro.focus_peaking"
        "pro_video.manual_focus" -> "pro_video.focus_peaking"
        "portrait_video.manual_focus" -> "portrait_video.focus_peaking"
        "hyperlapse.manual_focus" -> "hyperlapse.focus_peaking"
        else -> null
    }

    fun focusCurveSettingId(focusSettingId: String): String? = when (focusSettingId) {
        "photo.manual_focus" -> "photo.focus_curve"
        "expert.manual_focus" -> "expert.focus_curve"
        "pro.manual_focus" -> "pro.focus_curve"
        "pro_video.manual_focus" -> "pro_video.focus_curve"
        "portrait_video.manual_focus" -> "portrait_video.focus_curve"
        "hyperlapse.manual_focus" -> "hyperlapse.focus_curve"
        else -> null
    }

    /**
     * Returns the effective lens list for a given camera state.
     * Uses dynamically detected lenses when available, otherwise falls back to
     * variant-based defaults. This ensures the app works on any phone model.
     */
    fun effectiveLenses(camera: CameraState): List<String> {
        return if (camera.detectedLenses.isNotEmpty()) {
            camera.detectedLenses
        } else {
            photoLenses(camera.deviceVariant)
        }
    }

    private fun choice(
        id: String,
        label: String,
        group: String,
        options: List<String>,
        defaultValue: String,
        status: CameraFeatureStatus = CameraFeatureStatus.Confirmed,
        note: String? = null,
    ): CameraSettingSpec {
        return CameraSettingSpec(
            id = id,
            label = label,
            group = group,
            kind = CameraSettingKind.Choice,
            options = options,
            defaultValue = defaultValue,
            status = status,
            note = note,
        )
    }

    private fun toggle(
        id: String,
        label: String,
        group: String,
        defaultValue: String = "Off",
        status: CameraFeatureStatus = CameraFeatureStatus.Confirmed,
        note: String? = null,
    ): CameraSettingSpec {
        return CameraSettingSpec(
            id = id,
            label = label,
            group = group,
            kind = CameraSettingKind.Toggle,
            options = listOf("Off", "On"),
            defaultValue = defaultValue,
            status = status,
            note = note,
        )
    }

    private fun slider(
        id: String,
        label: String,
        group: String,
        options: List<String>,
        defaultValue: String,
        status: CameraFeatureStatus = CameraFeatureStatus.Confirmed,
        note: String? = null,
        supportsSensitivity: Boolean = true,
    ): CameraSettingSpec {
        return CameraSettingSpec(
            id = id,
            label = label,
            group = group,
            kind = CameraSettingKind.Slider,
            options = options,
            defaultValue = defaultValue,
            status = status,
            note = note,
            supportsSensitivity = supportsSensitivity,
        )
    }

    private fun photoProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        val megapixels = photoMegapixels(variant)
        return CameraModeProfile(
            mode = CameraModeId.Photo,
            modeName = CameraModeId.Photo.label,
            captureType = CameraCaptureType.Photo,
            availableLenses = lenses,
            availableResolutions = megapixels,
            availableFormatOptions = listOf("JPEG", "HEIF"),
            availableExposureControls = listOf("Flash", "Lens", "Exposure Value"),
            availableAssistTools = listOf("Motion photo", "Timer", "Filters", "Grid"),
            settings = listOf(
                choice("photo.flash", "Flash", "Core", listOf("Auto", "Off", "On"), "Auto"),
                choice("photo.megapixels", "Photo MP", "Core", megapixels, megapixels.first()),
                choice(
                    "photo.save_format",
                    "Photo format",
                    "File",
                    listOf("JPEG", "HEIF"),
                    "JPEG",
                    CameraFeatureStatus.NeedsVerification,
                    "HEIF output needs the Samsung vendor capture pipeline.",
                ),
                choice("photo.aspect_ratio", "Aspect ratio", "Core", photoAspectRatios(), "4:3"),
                choice("photo.timer", "Timer", "Core", timerOptions(), "Off"),
                toggle(
                    "photo.motion_photo",
                    "Motion photo",
                    "Core",
                    status = CameraFeatureStatus.NeedsVerification,
                    note = "Motion Photo packaging needs the Samsung vendor capture pipeline.",
                ),
                choice("photo.lens", "Lens", "Core", lenses, "Auto"),
                // DiveControl housing enhancement: Samsung keeps these in Pro, but retaining
                // them here preserves the underwater focus workflow without hiding any native
                // Photo control added above.
                slider("photo.manual_focus", "Focus", "DiveControl", focusOptions, "AF"),
                toggle("photo.focus_peaking", "Focus Assist", "DiveControl"),
                choice("photo.focus_curve", "Focus Curve", "DiveControl", focusCurveOptions(), "SquareRoot"),
                slider("photo.exposure_compensation", "EV", "Core", evQuickOptions, "0.0"),
                choice("photo.filters", "Filters", "Core", underwaterFilterOptions, "Off"),
                choice("photo.grid", "Guides", "Assist", gridOptions(), "Rule of Thirds"),
            ),
        )
    }

    private fun portraitProfile(): CameraModeProfile = CameraModeProfile(
        mode = CameraModeId.Portrait,
        modeName = CameraModeId.Portrait.label,
        captureType = CameraCaptureType.Photo,
        availableLenses = listOf("1x", "2x", "3x"),
        availableResolutions = listOf("Auto"),
        availableExposureControls = listOf("Flash", "Lens", "Exposure Value"),
        availableAssistTools = listOf("Timer", "Beauty", "Lighting", "Background effects", "Grid"),
        settings = listOf(
            choice("portrait.flash", "Flash", "Core", listOf("Off", "On"), "Off"),
            choice("portrait.lens", "Lens", "Core", listOf("1x", "2x", "3x"), "1x"),
            choice("portrait.timer", "Timer", "Core", timerOptions(), "Off"),
            choice("portrait.aspect_ratio", "Aspect ratio", "Core", photoAspectRatios(), "4:3"),
            slider("portrait.exposure", "EV", "Core", evQuickOptions, "0.0", supportsSensitivity = false),
            choice(
                "portrait.background_effect",
                "Background effect",
                "Portrait",
                listOf("Blur", "Studio", "High-key mono", "Low-key mono", "Backdrop", "Color point"),
                "Blur",
                CameraFeatureStatus.NeedsVerification,
                "Samsung computational portrait effects are vendor-only.",
            ),
            slider(
                "portrait.effect_strength",
                "Effect strength",
                "Portrait",
                (0..7).map(Int::toString),
                "4",
                CameraFeatureStatus.NeedsVerification,
                "The native eight-step control is catalogued; rendering is vendor-only.",
                supportsSensitivity = false,
            ),
            choice(
                "portrait.beauty",
                "Skin smoothness",
                "Portrait",
                listOf("Off") + (1..8).map(Int::toString),
                "Off",
                CameraFeatureStatus.NeedsVerification,
                "Samsung face-retouch processing is vendor-only.",
            ),
            slider(
                "portrait.lighting",
                "Lighting",
                "Portrait",
                (0..7).map(Int::toString),
                "4",
                CameraFeatureStatus.NeedsVerification,
                "The native eight-step lighting control is catalogued; rendering is vendor-only.",
                supportsSensitivity = false,
            ),
            choice("portrait.grid", "Guides", "Assist", gridOptions(), "Rule of Thirds"),
        ),
    )

    private fun foodProfile(): CameraModeProfile = CameraModeProfile(
        mode = CameraModeId.Food,
        modeName = CameraModeId.Food.label,
        captureType = CameraCaptureType.Photo,
        availableLenses = listOf("1x", "2x", "3x"),
        availableResolutions = listOf("Auto"),
        availableExposureControls = listOf("Colour temperature", "Exposure Value"),
        availableAssistTools = listOf("Blur effect", "Grid"),
        settings = listOf(
            choice("food.lens", "Lens", "Core", listOf("1x", "2x", "3x"), "1x"),
            slider(
                "food.color_temperature",
                "Colour temperature",
                "Food",
                (-4..4).map { if (it > 0) "+$it" else it.toString() },
                "0",
                CameraFeatureStatus.NeedsVerification,
                "Mapped onto DiveControl's calibrated 3200K-6800K white-balance renderer.",
                supportsSensitivity = false,
            ),
            toggle(
                "food.radial_blur",
                "Blur effect",
                "Food",
                "On",
                CameraFeatureStatus.NeedsVerification,
                "The movable food focus area is a Samsung computational effect.",
            ),
            slider("food.exposure", "EV", "Core", evQuickOptions, "0.0", supportsSensitivity = false),
            choice("food.aspect_ratio", "Aspect ratio", "Core", photoAspectRatios(), "4:3"),
            choice("food.grid", "Guides", "Assist", gridOptions(), "Rule of Thirds"),
        ),
    )

    private fun expertRawProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        val megapixels = photoMegapixels(variant)
        return CameraModeProfile(
            mode = CameraModeId.ExpertRaw,
            modeName = CameraModeId.ExpertRaw.label,
            captureType = CameraCaptureType.Photo,
            availableLenses = lenses,
            availableResolutions = megapixels,
            availableFormatOptions = listOf("RAW", "JPEG", "RAW + JPEG"),
            availableExposureControls = listOf("White balance", "ISO", "Focus", "Shutter", "Exposure value"),
            availableAssistTools = listOf(
                "Exposure monitor", "Virtual aperture", "ND filter", "Astrophotography",
                "Astro Portrait", "Multi Exposures", "Ocean Mode", "Guidelines", "Grid", "HDR",
            ),
            settings = listOf(
                choice("expert.flash", "Flash", "Core", listOf("Auto", "Off", "On"), "Off"),
                choice("expert.megapixels", "Photo MP", "Core", megapixels, megapixels.first()),
                choice("expert.save_format", "RAW / JPEG", "Core", listOf("RAW", "JPEG", "RAW + JPEG"), "RAW + JPEG"),
                choice("expert.lens", "Lens", "Core", lenses, "Auto"),
                slider("expert.white_balance", "White balance", "Manual", whiteBalanceOptions, WB_AUTO_CONTINUOUS),
                slider("expert.iso", "ISO", "Manual", isoOptions, "Auto"),
                slider("expert.manual_focus", "Focus", "Manual", focusOptions, "AF"),
                toggle("expert.focus_peaking", "Focus Assist", "Assist"),
                choice("expert.focus_curve", "Focus Curve", "Assist", focusCurveOptions(), "SquareRoot"),
                slider("expert.shutter_speed", "Shutter", "Manual", shutterOptions, "Auto"),
                slider("expert.exposure_value", "Exposure Value", "Manual", evProOptions, "0.0"),
                toggle("expert.exposure_monitor", "Exposure monitor", "Assist"),
                choice("expert.guidelines", "Guidelines", "Assist", listOf("Off", "On"), "On"),
                choice("expert.grid", "Guides", "Assist", gridOptions(), "Rule of Thirds"),
                choice("expert.hdr", "HDR", "Assist", listOf("Off", "On"), "On"),
                slider(
                    "expert.virtual_aperture",
                    "Virtual aperture",
                    "Expert RAW Labs",
                    virtualApertureOptions,
                    "F16.0",
                    CameraFeatureStatus.NeedsVerification,
                    "Control surface for Samsung's 22-step F1.4-F16 software depth-of-field feature; rendering requires compatible vendor support.",
                    supportsSensitivity = false,
                ),
                choice(
                    "expert.nd_filter",
                    "ND filter",
                    "Expert RAW Labs",
                    listOf("Off", "2 stops", "4 stops", "6 stops", "8 stops", "10 stops"),
                    "Off",
                    CameraFeatureStatus.NeedsVerification,
                    "Samsung's multi-frame virtual ND rendering is vendor-only.",
                ),
                choice(
                    "expert.astrophotography",
                    "Astrophotography",
                    "Expert RAW Labs",
                    listOf("Off", "4 min", "7 min", "10 min"),
                    "Off",
                    CameraFeatureStatus.NeedsVerification,
                    "Long multi-frame stacking and Sky Guide are supplied by Samsung's Expert RAW engine.",
                ),
                toggle(
                    "expert.sky_guide",
                    "Sky Guide",
                    "Expert RAW Labs",
                    status = CameraFeatureStatus.NeedsVerification,
                    note = "Samsung's constellation overlay is vendor-only.",
                ),
                toggle(
                    "expert.astro_portrait",
                    "Astro Portrait",
                    "Expert RAW Labs",
                    status = CameraFeatureStatus.NeedsVerification,
                    note = "Samsung's subject-and-sky multi-frame compositing is vendor-only.",
                ),
                toggle(
                    "expert.multi_exposure",
                    "Multi Exposures",
                    "Expert RAW Labs",
                    status = CameraFeatureStatus.NeedsVerification,
                    note = "Samsung's RAW multi-frame compositor is vendor-only.",
                ),
                choice(
                    "expert.multi_exposure_shutter",
                    "Multi Exposure shutter",
                    "Expert RAW Labs",
                    listOf("Continuous", "Manual"),
                    "Manual",
                    CameraFeatureStatus.NeedsVerification,
                    "Used when Multi Exposures is enabled.",
                ),
                choice(
                    "expert.multi_exposure_overlay",
                    "Multi Exposure overlay",
                    "Expert RAW Labs",
                    listOf("Add", "Average", "Bright", "Dark"),
                    "Average",
                    CameraFeatureStatus.NeedsVerification,
                    "Used when Multi Exposures is enabled.",
                ),
                slider(
                    "expert.multi_exposure_frames",
                    "Multi Exposure frames",
                    "Expert RAW Labs",
                    (2..9).map(Int::toString),
                    "2",
                    CameraFeatureStatus.NeedsVerification,
                    "Samsung Expert RAW combines up to nine exposures.",
                    supportsSensitivity = false,
                ),
                toggle(
                    "expert.ocean_mode",
                    "Ocean Mode",
                    "Expert RAW Labs",
                    status = CameraFeatureStatus.NeedsVerification,
                    note = "Uses DiveControl's live underwater white-balance estimator; Samsung's lens-distortion correction remains vendor-only.",
                ),
                slider(
                    "expert.aqua_tone",
                    "Aqua tone",
                    "Ocean Mode",
                    (-4..4).map { if (it > 0) "+$it" else it.toString() },
                    "0",
                    CameraFeatureStatus.NeedsVerification,
                    "Fine-tunes the underwater colour balance while Ocean Mode is on.",
                    supportsSensitivity = false,
                ),
                choice(
                    "expert.ocean_capture_interval",
                    "Capture interval",
                    "Ocean Mode",
                    listOf("Off", "2s", "5s", "10s"),
                    "Off",
                    CameraFeatureStatus.NeedsVerification,
                    "Samsung Ocean Mode interval shooting; DiveControl currently keeps housing shutter control manual.",
                ),
            ),
        )
    }

    private fun proProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        val megapixels = photoMegapixels(variant)
        return CameraModeProfile(
            mode = CameraModeId.Pro,
            modeName = CameraModeId.Pro.label,
            captureType = CameraCaptureType.Photo,
            availableLenses = lenses,
            availableResolutions = megapixels,
            availableFormatOptions = listOf("JPEG", "RAW + JPEG"),
            availableExposureControls = listOf("White balance", "ISO", "Focus", "Shutter", "Exposure value"),
            availableAssistTools = listOf("Zebra", "False colour", "Guides", "HDR"),
            settings = listOf(
                slider("pro.white_balance", "White balance", "Manual", whiteBalanceOptions, WB_AUTO_CONTINUOUS),
                slider("pro.iso", "ISO", "Manual", isoOptions, "Auto"),
                slider("pro.manual_focus", "Focus", "Manual", focusOptions, "AF"),
                toggle("pro.focus_peaking", "Focus Assist", "Assist"),
                choice("pro.focus_curve", "Focus Curve", "Assist", focusCurveOptions(), "SquareRoot"),
                slider("pro.shutter_speed", "Shutter", "Manual", shutterOptions, "Auto"),
                slider("pro.exposure_value", "Exposure Value", "Manual", evProOptions, "0.0"),
                choice("pro.megapixels", "Photo resolution", "File", megapixels, megapixels.first()),
                choice(
                    "pro.save_format",
                    "Photo format",
                    "File",
                    listOf("JPEG", "RAW + JPEG"),
                    "JPEG",
                    CameraFeatureStatus.NeedsVerification,
                    "RAW output needs the Samsung vendor capture pipeline.",
                ),
                choice(
                    "pro.aspect_ratio",
                    "Aspect ratio",
                    "File",
                    photoAspectRatios(),
                    "4:3",
                ),
                choice("pro.timer", "Timer", "Core", timerOptions(), "Off"),
                choice("pro.flash", "Flash", "Core", listOf("Auto", "Off", "On"), "Off"),
                choice("pro.lens", "Lens", "Core", lenses, "Auto"),
                choice("pro.metering", "Metering", "Exposure", meteringOptions(), "Matrix"),
                toggle("pro.histogram", "Histogram", "Assist", "On"),
                choice("pro.filters", "Filters", "Core", underwaterFilterOptions, "Off"),
                choice("pro.guides", "Guides", "Assist", guideOptions(), "Rule of Thirds"),
                choice("pro.exposure_display", "Exposure display", "Assist", exposureDisplayOptions(), "Off"),
                choice("pro.hdr", "HDR photo", "Dynamic range", listOf("Off", "On"), "On"),
                slider(
                    "pro.virtual_aperture",
                    "Virtual aperture",
                    "Creative",
                    virtualApertureOptions,
                    "F16.0",
                    CameraFeatureStatus.NeedsVerification,
                    "Control surface for virtual depth of field; the phone's physical aperture does not change and rendering requires compatible support.",
                    supportsSensitivity = false,
                ),
                *metadataSettings("pro").toTypedArray(),
            ),
        )
    }

    private fun panoramaProfile(_variant: GalaxyDeviceVariant): CameraModeProfile = CameraModeProfile(
        mode = CameraModeId.Panorama,
        modeName = CameraModeId.Panorama.label,
        captureType = CameraCaptureType.Photo,
        availableLenses = listOf("0.6x", "1x"),
        availableResolutions = listOf("Auto"),
        availableAssistTools = listOf("Guidelines", "Grid"),
        settings = listOf(
            choice("panorama.lens", "Lens", "Core", listOf("0.6x", "1x"), "1x"),
            choice(
                "panorama.direction",
                "Sweep direction",
                "Core",
                listOf("Left", "Right", "Up", "Down"),
                "Right",
                CameraFeatureStatus.NeedsVerification,
                "Panorama stitching is supplied by Samsung's vendor pipeline.",
            ),
            toggle("panorama.guide", "Panorama guide", "Assist", "On"),
            slider("panorama.exposure", "EV", "Core", evQuickOptions, "0.0", supportsSensitivity = false),
            choice("panorama.grid", "Guides", "Assist", gridOptions(), "Rule of Thirds"),
        ),
    )

    private fun nightProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        return CameraModeProfile(
            mode = CameraModeId.Night,
            modeName = CameraModeId.Night.label,
            captureType = CameraCaptureType.Photo,
            availableLenses = lenses,
            availableResolutions = listOf("Auto"),
            availableExposureControls = listOf("Exposure value"),
            availableAssistTools = listOf("Grid"),
            settings = listOf(
                choice("night.lens", "Lens", "Core", lenses, "1x"),
                slider("night.exposure", "Exposure Value", "Core", evQuickOptions, "0.0", supportsSensitivity = false),
                choice("night.timer", "Timer", "Core", timerOptions(), "Off"),
                choice("night.aspect_ratio", "Aspect ratio", "Core", photoAspectRatios(), "4:3"),
                choice(
                    "night.capture_time",
                    "Capture time",
                    "Night",
                    listOf("Auto", "Max"),
                    "Auto",
                    CameraFeatureStatus.NeedsVerification,
                    "Samsung's multi-frame Night exposure is vendor-only.",
                ),
                choice("night.grid", "Guides", "Assist", gridOptions(), "Rule of Thirds"),
            ),
        )
    }

    private fun burstProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        val megapixels = photoMegapixels(variant)
        return CameraModeProfile(
            mode = CameraModeId.Burst,
            modeName = CameraModeId.Burst.label,
            captureType = CameraCaptureType.Photo,
            availableLenses = lenses,
            availableResolutions = megapixels,
            availableFormatOptions = listOf("JPEG", "RAW"),
            settings = listOf(
                choice("burst.flash", "Flash", "Core", listOf("Off", "On"), "Off"),
                choice("burst.megapixels", "Photo MP", "Core", megapixels, megapixels.first()),
                choice("burst.lens", "Lens", "Core", lenses, "1x"),
                choice("burst.capture_length", "Burst length", "Core", listOf("Short", "Medium", "Long"), "Medium"),
            ),
        )
    }

    private fun singleTakeProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        return CameraModeProfile(
            mode = CameraModeId.SingleTake,
            modeName = CameraModeId.SingleTake.label,
            captureType = CameraCaptureType.Hybrid,
            availableLenses = lenses,
            availableResolutions = photoMegapixels(variant),
            availableFrameRates = listOf("Auto"),
            availableFormatOptions = listOf("JPEG", "MP4"),
            settings = listOf(
                choice("single_take.duration", "Duration", "Core", listOf("5s", "10s", "15s"), "10s"),
                choice("single_take.megapixels", "Photo MP", "Core", photoMegapixels(variant), photoMegapixels(variant).first()),
                choice("single_take.lens", "Lens", "Core", lenses, "1x"),
            ),
        )
    }

    private fun hyperlapseProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        return CameraModeProfile(
            mode = CameraModeId.Hyperlapse,
            modeName = CameraModeId.Hyperlapse.label,
            captureType = CameraCaptureType.Video,
            availableLenses = lenses,
            availableResolutions = listOf("FHD", "UHD 4K"),
            availableFrameRates = listOf("Night 45x", "Night 15x", "Auto", "5x", "10x", "15x", "30x", "60x"),
            availableExposureControls = listOf("Exposure value", "Focus"),
            availableAssistTools = listOf("Day / Night", "Focus Assist", "Grid"),
            settings = listOf(
                choice("hyperlapse.flash", "Flash / Torch", "Core", listOf("Off", "Torch"), "Off"),
                choice("hyperlapse.resolution", "Video size", "Core", listOf("FHD", "UHD 4K"), "FHD"),
                choice("hyperlapse.lens", "Lens", "Core", lenses, "1x"),
                slider("hyperlapse.exposure", "EV", "Core", evQuickOptions, "0.0", supportsSensitivity = false),
                choice(
                    "hyperlapse.recording_time",
                    "Recording time",
                    "Core",
                    listOf("∞", "10s", "30s", "60s", "120s", "180s", "300s"),
                    "∞",
                ),
                choice(
                    "hyperlapse.speed",
                    "Speed",
                    "Core",
                    listOf("Night 45x", "Night 15x", "Auto", "5x", "10x", "15x", "30x", "60x"),
                    "Auto",
                    CameraFeatureStatus.NeedsVerification,
                    "The dial order matches Samsung Camera 16.5.02.36; accelerated encoding remains device-dependent.",
                ),
                choice(
                    "hyperlapse.day_night",
                    "Day / Night",
                    "Core",
                    listOf("Day", "Night"),
                    "Day",
                    CameraFeatureStatus.NeedsVerification,
                    "Samsung's night time-lapse processing is vendor-only.",
                ),
                slider("hyperlapse.manual_focus", "Focus", "Manual", focusOptions, "AF"),
                toggle("hyperlapse.focus_peaking", "Focus Assist", "Assist"),
                choice("hyperlapse.focus_curve", "Focus Curve", "Assist", focusCurveOptions(), "SquareRoot"),
                choice("hyperlapse.grid", "Guides", "Assist", gridOptions(), "Rule of Thirds"),
            ),
        )
    }

    private fun videoProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        val resolutions = listOf("8K", "UHD 4K", "FHD", "HD 720p", "QHD")
        val frameRates = listOf("30fps", "60fps")
        return CameraModeProfile(
            mode = CameraModeId.Video,
            modeName = CameraModeId.Video.label,
            captureType = CameraCaptureType.Video,
            availableLenses = lenses,
            availableResolutions = resolutions,
            availableFrameRates = frameRates,
            availableFormatOptions = listOf("H.264", "HEVC", "HDR10+", "Log"),
            availableAudioControls = listOf("Audio recording"),
            availableAssistTools = listOf("Auto FPS", "Video stabilization", "Super Steady", "Filters", "Grid"),
            settings = listOf(
                choice(
                    "video.resolution",
                    "Video size",
                    "Core",
                    resolutions,
                    "UHD 4K",
                    CameraFeatureStatus.NeedsVerification,
                    "8K and Super Steady QHD depend on Samsung encoder profiles not exposed by CameraX.",
                ),
                choice("video.frame_rate", "Frame rate", "Core", frameRates, "30fps"),
                choice("video.aspect_ratio", "Aspect ratio", "Core", videoAspectRatios(), "16:9"),
                choice("video.lens", "Lens", "Core", lenses, "1x"),
                choice("video.flash", "Flash / Torch", "Core", listOf("Off", "Torch"), "Off"),
                slider("video.exposure", "EV", "Core", evQuickOptions, "0.0", supportsSensitivity = false),
                toggle("video.auto_fps", "Auto FPS", "Video", "Off"),
                choice("video.video_format", "Video format", "File", listOf("H.264", "HEVC"), "H.264"),
                choice("video.video_stabilization", "Video stabilization", "Stabilization", listOf("Off", "Standard"), "Standard"),
                toggle(
                    "video.super_steady",
                    "Super Steady",
                    "Stabilization",
                    "Off",
                    CameraFeatureStatus.NeedsVerification,
                    "Standard stabilization is applied; Samsung's wide-crop Super Steady path is vendor-only.",
                ),
                choice("video.hdr", "HDR10+", "Dynamic range", listOf("Off", "On"), "Off"),
                choice("video.log", "Log", "Dynamic range", listOf("Off", "On"), "Off"),
                toggle("video.audio_recording", "Audio recording", "Audio", "On"),
                choice("video.filters", "Filters", "Core", underwaterFilterOptions, "Off"),
                choice("video.grid", "Guides", "Assist", gridOptions(), "Rule of Thirds"),
            ),
        )
    }

    private fun proVideoProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        val frameRates = videoFrameRates(variant).filterNot { it == "240fps" } + listOf(
            "240fps/23.976fps playback",
            "240fps/24fps playback",
            "240fps/29.97fps playback",
            "240fps/30fps playback",
            "240fps/48fps playback",
        )
        return CameraModeProfile(
            mode = CameraModeId.ProVideo,
            modeName = CameraModeId.ProVideo.label,
            captureType = CameraCaptureType.Video,
            availableLenses = lenses,
            availableResolutions = listOf("FHD", "UHD 4K", "8K"),
            availableFrameRates = frameRates,
            availableFormatOptions = listOf("Standard", "HDR", "10-bit HLG"),
            availableExposureControls = listOf(
                "White balance", "ISO", "Focus", "Shutter", "Exposure value",
                "Frame rate / playback",
            ),
            availableAudioControls = listOf("Microphone audio"),
            availableAssistTools = listOf("Zebra", "False colour", "Guides", "HDR", "10-bit HLG / Log grade"),
            settings = listOf(
                slider("pro_video.white_balance", "White balance", "Manual", whiteBalanceOptions, WB_AUTO_CONTINUOUS),
                slider("pro_video.iso", "ISO", "Manual", isoOptions, "Auto"),
                slider("pro_video.manual_focus", "Focus", "Manual", focusOptions, "AF"),
                toggle("pro_video.focus_peaking", "Focus Assist", "Assist"),
                choice("pro_video.focus_curve", "Focus Curve", "Assist", focusCurveOptions(), "SquareRoot"),
                slider("pro_video.shutter_speed", "Shutter", "Manual", shutterOptions, "Auto"),
                slider("pro_video.exposure_value", "Exposure Value", "Manual", evProOptions, "0.0"),
                choice("pro_video.resolution", "Resolution", "File", videoResolutionOptions, "UHD 4K"),
                choice("pro_video.frame_rate", "FPS", "File", frameRates, "30fps"),
                choice(
                    "pro_video.aspect_ratio",
                    "Aspect ratio",
                    "File",
                    listOf("16:9", "4:3"),
                    "16:9",
                ),
                choice("pro_video.flash", "Flash / Torch", "Core", listOf("Off", "Torch"), "Off"),
                choice("pro_video.lens", "Lens", "Core", lenses, "Auto"),
                choice("pro_video.metering", "Metering", "Exposure", meteringOptions(), "Matrix"),
                choice("pro_video.guides", "Guides", "Assist", guideOptions(), "Rule of Thirds"),
                choice("pro_video.exposure_display", "Exposure display", "Assist", exposureDisplayOptions(), "Off"),
                // Both default OFF: wide-dynamic-range capture is a deliberate grading workflow.
                choice("pro_video.hdr", "HDR video", "Dynamic range", listOf("Off", "On"), "Off"),
                choice("pro_video.log", "10-bit HLG / Log grade", "Dynamic range", listOf("Off", "On"), "Off"),
                choice("pro_video.video_stabilization", "Video stabilization", "Stabilization", listOf("Off", "Standard"), "Off"),
                toggle("pro_video.audio_recording", "Microphone audio", "Audio", "On"),
                *metadataSettings("pro_video").toTypedArray(),
            ),
        )
    }

    private fun portraitVideoProfile(_variant: GalaxyDeviceVariant): CameraModeProfile = CameraModeProfile(
        mode = CameraModeId.PortraitVideo,
        modeName = CameraModeId.PortraitVideo.label,
        captureType = CameraCaptureType.Video,
        availableLenses = listOf("1x", "2x"),
        availableResolutions = listOf("FHD", "UHD 4K"),
        availableFrameRates = listOf("30fps"),
        availableAudioControls = listOf("Audio recording"),
        availableExposureControls = listOf("Exposure value", "Focus"),
        availableAssistTools = listOf("Portrait Video Effects", "Focus Assist", "HDR", "Grid"),
        settings = listOf(
            choice("portrait_video.lens", "Lens", "Core", listOf("1x", "2x"), "1x"),
            choice("portrait_video.resolution", "Video size", "Core", listOf("FHD", "UHD 4K"), "FHD"),
            choice("portrait_video.frame_rate", "Frame rate", "Core", listOf("30fps"), "30fps"),
            choice("portrait_video.flash", "Flash / Torch", "Core", listOf("Off", "Torch"), "Off"),
            slider("portrait_video.exposure", "EV", "Core", evQuickOptions, "0.0", supportsSensitivity = false),
            choice(
                "portrait_video.background_effect",
                "Portrait Video Effects",
                "Portrait",
                listOf("Blur", "Big circle", "Colour point", "Glitch"),
                "Blur",
                CameraFeatureStatus.NeedsVerification,
                "Samsung computational portrait-video effects are vendor-only.",
            ),
            slider(
                "portrait_video.effect_strength",
                "Effect strength",
                "Portrait",
                (0..7).map(Int::toString),
                "4",
                CameraFeatureStatus.NeedsVerification,
                "The native eight-step control is catalogued; rendering is vendor-only.",
                supportsSensitivity = false,
            ),
            slider("portrait_video.manual_focus", "Focus", "Manual", focusOptions, "AF"),
            toggle("portrait_video.focus_peaking", "Focus Assist", "Assist"),
            choice("portrait_video.focus_curve", "Focus Curve", "Assist", focusCurveOptions(), "SquareRoot"),
            choice("portrait_video.hdr", "HDR", "Dynamic range", listOf("Off", "On"), "Off"),
            toggle("portrait_video.audio_recording", "Audio recording", "Audio", "On"),
            choice("portrait_video.grid", "Guides", "Assist", gridOptions(), "Rule of Thirds"),
        ),
    )

    private fun slowMotionProfile(_variant: GalaxyDeviceVariant): CameraModeProfile = CameraModeProfile(
        mode = CameraModeId.SlowMotion,
        modeName = CameraModeId.SlowMotion.label,
        captureType = CameraCaptureType.Video,
        availableLenses = listOf("0.6x", "1x", "3x"),
        availableResolutions = listOf("FHD", "UHD 4K"),
        availableFrameRates = listOf("48fps", "60fps", "120fps", "240fps"),
        availableExposureControls = listOf("Exposure value", "Focus"),
        availableAssistTools = listOf("HDR", "Grid"),
        settings = listOf(
            choice("slow_motion.resolution", "Video size", "Core", listOf("FHD", "UHD 4K"), "FHD"),
            choice(
                "slow_motion.frame_rate",
                "Frame rate",
                "Core",
                listOf("48fps", "60fps", "120fps", "240fps"),
                "240fps",
            ),
            choice("slow_motion.lens", "Lens", "Core", listOf("0.6x", "1x", "3x"), "1x"),
            choice("slow_motion.flash", "Flash / Torch", "Core", listOf("Off", "Torch"), "Off"),
            slider("slow_motion.exposure", "EV", "Core", evQuickOptions, "0.0", supportsSensitivity = false),
            choice(
                "slow_motion.focus_mode",
                "Focus",
                "Manual",
                listOf("Continuous AF", "Single AF"),
                "Continuous AF",
                CameraFeatureStatus.NeedsVerification,
                "Android constrained high-speed capture requires automatic 3A; this selects the supported AF behavior instead of exposing a dead manual-focus dial.",
            ),
            choice(
                "slow_motion.hdr",
                "HDR",
                "Dynamic range",
                listOf("Off", "On"),
                "Off",
                CameraFeatureStatus.NeedsVerification,
                "Public Android high-speed sessions record SDR; On is available only if a device vendor path accepts high-speed HDR.",
            ),
            choice("slow_motion.grid", "Guides", "Assist", gridOptions(), "Rule of Thirds"),
        ),
    )

    private fun dualRecordProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        return CameraModeProfile(
            mode = CameraModeId.DualRecording,
            modeName = CameraModeId.DualRecording.label,
            captureType = CameraCaptureType.Video,
            availableLenses = lenses,
            availableResolutions = listOf("FHD", "UHD 4K"),
            availableFrameRates = listOf("24fps", "30fps", "60fps"),
            availableAudioControls = listOf("Microphone"),
            settings = listOf(
                choice("dual_record.layout", "Layout", "Core", listOf("Picture in picture", "Split"), "Picture in picture"),
                choice("dual_record.frame_rate", "Frame rate", "Core", listOf("24fps", "30fps", "60fps"), "30fps"),
                choice("dual_record.lens", "Primary lens", "Core", lenses, "1x"),
                choice("dual_record.microphone", "Microphone", "Audio", microphoneSources(), "Mixed"),
            ),
        )
    }

    private fun nightVideoProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        val frameRates = listOf("24fps", "30fps")
        return CameraModeProfile(
            mode = CameraModeId.NightVideo,
            modeName = CameraModeId.NightVideo.label,
            captureType = CameraCaptureType.Video,
            availableLenses = lenses,
            availableResolutions = listOf("FHD", "UHD 4K"),
            availableFrameRates = frameRates,
            availableFormatOptions = listOf("HDR", "10-bit HLG"),
            availableAudioControls = listOf("Microphone"),
            availableAssistTools = listOf("Exposure monitor", "Guidelines", "Grid"),
            settings = listOf(
                choice("night_video.resolution", "Resolution", "Core", listOf("FHD", "UHD 4K"), "UHD 4K"),
                choice("night_video.frame_rate", "Frame rate", "Core", frameRates, "30fps"),
                choice("night_video.lens", "Lens", "Core", lenses, "1x"),
                choice("night_video.microphone", "Microphone", "Audio", microphoneSources(), "Auto"),
                toggle("night_video.exposure_monitor", "Exposure monitor", "Assist"),
                choice("night_video.guidelines", "Guidelines", "Assist", listOf("Off", "On"), "On"),
                choice("night_video.grid", "Guides", "Assist", gridOptions(), "Rule of Thirds"),
                choice("night_video.hdr", "HDR", "Assist", listOf("Off", "On"), "On"),
                choice("night_video.log", "10-bit HLG", "Assist", listOf("Off", "On"), "Off"),
            ),
        )
    }

    private fun hiddenLegacyProfile(mode: CameraModeId, variant: GalaxyDeviceVariant): CameraModeProfile = CameraModeProfile(
        mode = mode,
        modeName = mode.label,
        captureType = mode.captureType,
        availableLenses = photoLenses(variant),
        availableResolutions = listOf("Hidden"),
        unavailableSettings = listOf("This legacy mode is intentionally hidden from the housing UI."),
        settings = emptyList(),
        status = CameraFeatureStatus.NeedsVerification,
    )

    private fun photoLenses(variant: GalaxyDeviceVariant): List<String> = when (variant) {
        GalaxyDeviceVariant.S26,
        GalaxyDeviceVariant.S26Plus -> listOf("Auto", "0.6x", "1x", "2x", "3x", "front")
        GalaxyDeviceVariant.S26Ultra -> listOf("Auto", "0.6x", "1x", "2x", "3x", "5x", "front")
    }

    private fun photoMegapixels(variant: GalaxyDeviceVariant): List<String> = when (variant) {
        GalaxyDeviceVariant.S26,
        GalaxyDeviceVariant.S26Plus -> listOf("12MP", "50MP")
        GalaxyDeviceVariant.S26Ultra -> listOf("12MP", "50MP", "200MP")
    }

    private fun timerOptions(): List<String> = listOf("Off", "2s", "5s", "10s")

    private fun photoAspectRatios(): List<String> = listOf("4:3", "16:9", "1:1", "Full")

    private fun videoAspectRatios(): List<String> = listOf("16:9", "1:1", "Full")

    private fun videoFrameRates(_variant: GalaxyDeviceVariant): List<String> =
        listOf(24, 25, 30, 48, 50, 60, 90, 100, 120, 240).map { "${it}fps" }

    private val videoResolutionOptions =
        listOf("SD 480p", "HD 720p", "FHD 1920×824", "FHD", "UHD 4K")

    private fun microphoneSources(): List<String> = listOf("Auto", "Front", "Rear", "USB", "Mixed")

    private fun microphoneGainOptions(): List<String> = listOf("-12dB", "-6dB", "0dB", "+6dB", "+12dB")

    private fun gridOptions(): List<String> = guideOptions()

    private fun guideOptions(): List<String> = listOf(
        "Off",
        "Rule of Thirds",
        "Phi Grid",
        "Symmetry",
        "Fibonacci Spiral Left",
        "Fibonacci Spiral Right",
        "Fibonacci Spiral Top Left",
        "Fibonacci Spiral Top Right",
        "Golden Triangles",
        "Vanishing Point",
        "Framing Depth",
        "Landscape Depth",
        "Leading Lines",
        "Lines and Patterns",
        "4×4 Grid",
        "Square",
        "Diagonal",
    )

    private fun exposureDisplayOptions(): List<String> = listOf(
        "Off",
        "Zebra ≥70 IRE",
        "Zebra ≥95 IRE",
        "False colour",
    )

    private fun meteringOptions(): List<String> = listOf("Matrix", "Center", "Spot")

    private fun metadataSettings(prefix: String): List<CameraSettingSpec> = listOf(
        toggle(prefix + ".metadata_depth", "Dive depth metadata", "Metadata", "On"),
        toggle(prefix + ".metadata_temperature", "Water temperature metadata", "Metadata", "On"),
        toggle(prefix + ".metadata_heading", "Heading metadata", "Metadata", "On"),
        toggle(prefix + ".metadata_pressure", "Pressure metadata", "Metadata", "On"),
        toggle(prefix + ".metadata_exposure", "Exposure metadata", "Metadata", "On"),
    )

    /**
     * The native camera's ISO table, VERBATIM: MakerParameter.SENSOR_SENSITIVITY_ARRAY minus its
     * leading 0 (index 0 is the HAL's "let AE decide" sentinel — our "Auto"). Thirds of a stop
     * from 50 to 800, then whole stops to 3200. Fifteen rungs on every device that runs the
     * native APK — ProConstants.initializeIsoValues() carries no feature gate.
     *
     * The field asked for an exact native match, so the finer generated ladder this replaces is
     * deliberately gone: same values, same count, same span as the stock Pro / Pro Video dial.
     * Note what that means at the edges, because the native app means it too: manual ISO starts
     * at 50 even though the sensor floor is 25 — the 25..49 band is Auto-only there and Auto-only
     * here — and while ISO is on Auto the stock chip prints the raw metered integer from
     * CaptureResult.SENSOR_SENSITIVITY (which is why a bright scene reads "25"). We reproduce
     * that readout from [CameraState.meteredExposure].
     *
     * Identical in Pro photo and Pro Video: ISO is the one exposure control with no
     * mode-dependent clamp in the native app.
     */
    internal val isoNativeValues = listOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1600, 3200,
    )

    private val isoOptions: List<String> by lazy {
        buildList {
            add("Auto")
            isoNativeValues.forEach { add(it.toString()) }
        }
    }

    /**
     * The native camera's shutter table, VERBATIM: MakerParameter.EXPOSURE_TIME_ARRAY minus its
     * leading 0 (Auto). Thirty-seven hand-written speeds from 1/24000 to 30 seconds — roughly
     * half-stops, interrupted by a mains-flicker cluster at 1/60-1/50-1/45, a third-stop nudge at
     * 1/8, and whole stops from 1" up. There is no closed-form formula behind it; it is a hand-made
     * list and is COPIED, not generated, which is the field's explicit instruction.
     *
     * THE LABEL IS THE VALUE: every rung reaches the capture request by parsing its own text
     * through [shutterOptionNanos], and each of these labels parses back to the native table's
     * exact nanosecond entry (e.g. "1/180" -> 5555556, "1/45" -> 22222222, "0.3\"" -> 300000000).
     * Sub-second rungs keep our `"` suffix where Samsung's dial prints a bare "0.3"/"1"/"30" —
     * same value, established HUD spelling.
     *
     * What trims it, exactly as in the native app:
     *  - the FAST end by the sensor: Samsung validates against the vendor characteristic
     *    samsung.android.sensor.info.exposureTimeRange, whose floor is 1/12000 on every S24 lens
     *    ([SHUTTER_NATIVE_MIN_NS]); [applyCapabilities] applies the same floor, so 1/24000 and
     *    1/16000 exist in the table but never render there or here.
     *  - the SLOW end by mode: in video the slowest rung is one frame period (1/30 at 30 fps,
     *    1/60 at 60, 1/125 at 120 — ProUtil.getMaxVideoShutterSpeed), applied through
     *    [settingsFor]'s videoShutterCapNs; in photo the ~0.15 s public live-preview ceiling
     *    clips the long tail Samsung reaches only through its private still-capture pipeline.
     *
     * The 30" rung is gated behind SUPPORT_EXPAND_SHUTTER_SPEED in the native app; that flag is a
     * CSC lookup we cannot read, so it ships and capability clipping decides.
     */
    private val shutterNativeLabels = listOf(
        "1/24000", "1/16000", "1/12000", "1/8000", "1/6000", "1/4000", "1/3000", "1/2000",
        "1/1500", "1/1000", "1/750", "1/500", "1/350", "1/250", "1/180", "1/125", "1/90",
        "1/60", "1/50", "1/45", "1/30", "1/20", "1/15", "1/10", "1/8", "1/6", "1/4",
        "0.3\"", "0.5\"", "1\"", "2\"", "4\"", "8\"", "10\"", "15\"", "20\"", "30\"",
    )

    /**
     * The native app's fast-end floor: the lower bound of the vendor characteristic
     * samsung.android.sensor.info.exposureTimeRange (83333 ns = 1/12000 on every S24 lens), which
     * is what the stock camera's shutter dial is trimmed by — NOT the public
     * SENSOR_INFO_EXPOSURE_TIME_RANGE, whose 49856 ns floor would admit 1/16000. The runtime
     * controller reads the vendor tag when it can and falls back to this constant, so the dial
     * matches the native one either way.
     */
    const val SHUTTER_NATIVE_MIN_NS = 83_333L

    private val shutterOptions: List<String> by lazy {
        buildList {
            add("Auto")
            addAll(shutterNativeLabels)
        }.also { ladder ->
            val nanos = ladder.drop(1).map { checkNotNull(shutterOptionNanos(it)) { "unparseable shutter label $it" } }
            check(nanos.zipWithNext().all { (a, b) -> b > a }) { "shutter ladder not strictly increasing" }
        }
    }

    /**
     * The nearest native rung label to a live metered exposure, by linear nanosecond distance —
     * the same metric as the native app's findNearestShutterSpeed. Used for the Auto readout
     * (Samsung snaps the metered exposure to its table before printing it) and for seeding
     * manual control from Auto. [options] is the mode's CLIPPED ladder so the snap can never
     * name a rung the current mode cannot reach.
     */
    fun nearestShutterOption(ns: Long, options: List<String>): String? =
        options.mapNotNull { option -> shutterOptionNanos(option)?.let { option to it } }
            .minByOrNull { (_, value) -> kotlin.math.abs(value - ns) }?.first

    /**
     * The native Samsung white-balance scale, verbatim: 78 values from 2300 K through 10000 K in
     * flat 100 K steps (`kelvin_value`; MakerParameter.getColorTemperature is `step * 100`).
     *
     * The three automatic modes follow the cold end. That ordering is the wheel topology the
     * housing exposes:
     *
     *   10000 K -> Auto Continuous -> Auto Underwater -> Auto Shutter -> 2300 K
     *
     * and the exact reverse when turned the other way. Continuous leaves Samsung AWB running;
     * Shutter runs it during preview and locks its converged gain-plus-transform solution for the
     * capture/recording. The latter is deliberately not represented by a made-up Kelvin beyond
     * 10000 K: underwater neutral-card correction needs green/tint and independent channel gains,
     * not merely a longer colour-temperature rail.
     */
    private val whiteBalanceOptions: List<String> by lazy {
        buildList {
            for (kelvin in WB_MIN_KELVIN..WB_MAX_KELVIN step WB_KELVIN_STEP) add("${kelvin}K")
            add(WB_AUTO_CONTINUOUS)
            add(WB_AUTO_UNDERWATER)
            add(WB_AUTO_SHUTTER)
        }.also { ladder ->
            val kelvin = ladder.mapNotNull { it.removeSuffix("K").toIntOrNull() }
            check(kelvin.first() == WB_MIN_KELVIN && kelvin.last() == WB_MAX_KELVIN) {
                "WB ladder endpoints must be exact"
            }
            check(kelvin.zipWithNext().all { (a, b) -> b - a == WB_KELVIN_STEP }) {
                "WB ladder must use the native 100 K step"
            }
        }
    }

    /** The Samsung dial's exact bounds, step and manual-rung count. */
    const val WB_MIN_KELVIN = 2_300
    const val WB_MAX_KELVIN = 10_000
    const val WB_KELVIN_STEP = 100
    const val WB_LADDER_RUNGS = 78

    /**
     * Metered kelvin -> the nearest manual rung of the given dial, by linear kelvin distance.
     * Automatic-mode HUD readouts use this without changing the ring's selected value.
     */
    fun nearestWhiteBalanceOption(kelvin: Int, options: List<String>): String? =
        options.mapNotNull { option -> option.removeSuffix("K").toIntOrNull()?.let { option to it } }
            .minByOrNull { (_, value) -> kotlin.math.abs(value - kelvin) }?.first

    /**
     * Read-only views of the four ladders, for CameraSessionStore's restore-time snapping.
     * `get() =` rather than a direct alias so these stay lazy and cannot be read during this
     * object's construction.
     */
    val isoLadder: List<String> get() = isoOptions
    val shutterLadder: List<String> get() = shutterOptions
    val whiteBalanceLadder: List<String> get() = whiteBalanceOptions
    /** The Pro-mode +/-4.0 scale and the quick-bar +/-2.0 scale — snapped separately, matching the native split. */
    val exposureProLadder: List<String> get() = evProOptions
    val exposureQuickLadder: List<String> get() = evQuickOptions

    /**
     * The option ladders are built ONCE, not per profile lookup.
     *
     * They are constants that happen to be spelled as loops: the same 201 focus rungs and the
     * same exposure rungs, formatted identically every time. Rebuilding them per call cost a
     * [String.format] invocation each, and a focus adjustment reaches [profile] about thirty
     * times per state application at up to 500 applications per second — around a million
     * formatter calls per second on the main thread, every one of them discarded.
     *
     * [lazy] defers the value, not the delegate: the backing `$delegate` field is still assigned
     * in declaration order while this object is constructed, so it is a convenience here and not
     * a safety net. The standing rule is that no EAGERLY initialised member of this object may
     * call [profile] — such a call re-enters and reads either a not-yet-assigned ladder delegate
     * or a null [profileCache]. Today none does: every profile-derived property
     * ([allModeSettings], [defaultSettingValues], [defaultSliderSensitivities],
     * [defaultFocusCurveModes]) is itself lazy.
     */
    /**
     * 201 rungs at 0.005 spacing, matching the increment count the native camera offers.
     *
     * Three decimals is forced by the spacing: at two, 0.005 / 0.010 / 0.015 all render "0.01"
     * and every other click looks dead. The rung COUNT is load-bearing beyond display — the
     * reducer indexes into this list, so its length is literally how far one detent moves the
     * lens, which is why [com.mobiledivecontrol.core.CameraCatalog] pins it in tests.
     */
    private val focusOptions: List<String> by lazy {
        buildList {
            add("AF")
            for (step in 0..200) {
                add(String.format(Locale.US, "%.3f", step / 200.0))
            }
        }
    }

    private val underwaterFilterOptions: List<String> by lazy {
        buildList {
            add("Off")
            add("Auto")
            for (depthMeters in 0..50 step 5) {
                add("${depthMeters}m")
            }
        }
    }

    /** Samsung Expert RAW's documented 22 virtual-aperture stops, F1.4 through F16. */
    private val virtualApertureOptions: List<String> = listOf(
        "F1.4", "F1.6", "F1.8", "F2.0", "F2.2", "F2.5", "F2.8", "F3.2", "F3.5",
        "F4.0", "F4.5", "F5.0", "F5.6", "F6.3", "F7.1", "F8.0", "F9.0", "F10.0",
        "F11.0", "F13.0", "F14.0", "F16.0",
    )

    /**
     * Exposure compensation, exactly as the native camera ships it — TWO scales, one spacing.
     *
     * 0.1 EV is both the native spacing (ProUtil.getExposureValueString = step / 10.0) and the
     * hardware atom: CONTROL_AE_EXPOSURE_COMPENSATION is an integer index whose unit is
     * android.control.aeCompensationStep = 1/10 on every camera id. Nothing finer exists to ship.
     *
     * SPANS, from the decompiled app: the Pro / Pro Video dialer is the full 81-entry
     * exposure_value array, -4.0..+4.0; the quick EV bar every other mode shows is that same
     * array's middle 41 entries, -2.0..+2.0. [evProOptions] and [evQuickOptions] mirror that
     * split. Labels are the native spellings: signed one-decimal, zero printed "0.0".
     *
     * NO "Auto" RUNG, because the native app has none: EV is not auto-convertible (dialer id 3 is
     * excluded from isAutoToManualConversionSupported), it has a reset-to-0.0 affordance instead,
     * and it is live exactly while at least one of ISO / shutter is on Auto. When BOTH are manual
     * the native EV field flips to a read-only meter of the HAL's measured deviation — the
     * reducer refuses EV detents in that state and the HUD shows the metered value from
     * [CameraState.meteredExposure] instead.
     *
     * HOW +/-4.0 travels, from the decompile: through the ordinary PUBLIC key. Samsung sizes its
     * ruler from the vendor CHARACTERISTIC samsung.android.control.aeCompensationRange ([-40,40]
     * here, vs the public [-20,20]) and writes the raw index on
     * CONTROL_AE_EXPOSURE_COMPENSATION — there is no vendor request key for EV anywhere in the
     * APK, and the focusLensPos package-gate does not apply to public request metadata. The
     * runtime controller therefore reads the vendor characteristic into
     * [CameraCapabilities.evMin]/[evMax] (falling back to the public one) and writes the index
     * through the unvalidated Camera2 interop route — CameraX's setExposureCompensationIndex
     * rejects anything past the public range outright. [applyCapabilities] clips this ladder to
     * whichever range was actually read, so on a device where the vendor characteristic does not
     * resolve the dial honestly stops at +/-2.0. Whether the HAL ACTS on indices past +/-20 from
     * a third-party client is verified on-device by reading the result echo alongside
     * SENSOR_SENSITIVITY x SENSOR_EXPOSURE_TIME during the field sweep — if it saturates, the
     * clip bound is where to pull back.
     */
    private val evProOptions: List<String> by lazy { evLadder(-40..40) }
    private val evQuickOptions: List<String> by lazy { evLadder(-20..20) }

    private fun evLadder(tenthsRange: IntRange): List<String> =
        tenthsRange.map { tenths -> evLabel(tenths) }

    /** Native spelling: "+%.1f" above zero, "%.1f" at and below it — zero prints "0.0". */
    fun evLabel(tenths: Int): String = String.format(
        Locale.US,
        if (tenths > 0) "+%.1f" else "%.1f",
        tenths / 10.0,
    )

    private fun focusCurveOptions(): List<String> = listOf("Linear", "SquareRoot", "Logarithmic")
}

val CameraState.primaryHighlightedEntry: CameraRailEntry
    get() = CameraCatalog.highlightedPrimaryEntry(this)

val CameraState.secondaryHighlightedMode: CameraModeId
    get() = CameraCatalog.highlightedSecondaryMode(this)

val CameraState.selectedSetting: CameraSettingSpec?
    get() = CameraCatalog.selectedSetting(this)
