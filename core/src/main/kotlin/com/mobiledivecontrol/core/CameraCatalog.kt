package com.mobiledivecontrol.core

import java.util.Locale

data class CameraRailEntry(
    val key: String,
    val label: String,
    val mode: CameraModeId? = null,
    val opensSecondaryRail: Boolean = false,
)

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
    val primaryRailEntries: List<CameraRailEntry> = listOf(
        CameraRailEntry("photo", "Photo", CameraModeId.Photo),
        CameraRailEntry("expert_raw", "Expert RAW", CameraModeId.ExpertRaw),
        CameraRailEntry("pro", "Pro", CameraModeId.Pro),
        CameraRailEntry("panorama", "Panorama", CameraModeId.Panorama),
        CameraRailEntry("night", "Night", CameraModeId.Night),
        CameraRailEntry("burst", "Burst", CameraModeId.Burst),
        CameraRailEntry("single_take", "Single Take", CameraModeId.SingleTake),
        CameraRailEntry("hyperlapse", "Hyperlapse", CameraModeId.Hyperlapse),
        CameraRailEntry("video", "Video", CameraModeId.Video),
        CameraRailEntry("pro_video", "Pro Video", CameraModeId.ProVideo),
        CameraRailEntry("portrait_video", "Portrait Video", CameraModeId.PortraitVideo),
        CameraRailEntry("slow_motion", "Slow Motion", CameraModeId.SlowMotion),
        CameraRailEntry("dual_record", "Dual Record", CameraModeId.DualRecording),
        CameraRailEntry("night_video", "Night Video", CameraModeId.NightVideo),
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

    fun profile(mode: CameraModeId, variant: GalaxyDeviceVariant): CameraModeProfile = when (mode) {
        CameraModeId.Photo -> photoProfile(variant)
        CameraModeId.ExpertRaw -> expertRawProfile(variant)
        CameraModeId.Pro -> proProfile(variant)
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
        CameraModeId.Portrait,
        CameraModeId.Food,
        CameraModeId.SuperSlowMotion,
        CameraModeId.DirectorsView,
        CameraModeId.Macro,
        CameraModeId.BixbyVision,
        CameraModeId.ArZone -> hiddenLegacyProfile(mode, variant)
    }

    fun settingsFor(mode: CameraModeId, variant: GalaxyDeviceVariant): List<CameraSettingSpec> {
        return profile(mode, variant).settings
    }

    /**
     * Returns settings with lens options overridden by dynamically detected lenses
     * from the device hardware. Used by the UI to show only available lenses.
     */
    fun settingsFor(
        mode: CameraModeId,
        variant: GalaxyDeviceVariant,
        detectedLenses: List<String>,
    ): List<CameraSettingSpec> {
        val baseSettings = settingsFor(mode, variant)
        if (detectedLenses.isEmpty()) return baseSettings
        return baseSettings.map { spec ->
            if (spec.id.endsWith(".lens")) {
                spec.copy(options = detectedLenses)
            } else {
                spec
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
    ): List<CameraSettingSpec> {
        val base = settingsFor(mode, variant, detectedLenses)
        if (capabilities == null) return base
        return base.mapNotNull { spec -> applyCapabilities(spec, capabilities) }
    }

    private fun applyCapabilities(spec: CameraSettingSpec, caps: CameraCapabilities): CameraSettingSpec? {
        fun clip(keep: (String) -> Boolean): CameraSettingSpec? {
            val kept = spec.options.filter(keep)
            if (kept.isEmpty()) return null
            val default = if (spec.defaultValue in kept) spec.defaultValue else kept.first()
            return spec.copy(options = kept, defaultValue = default)
        }
        return when {
            spec.id.endsWith(".manual_focus") ->
                if (caps.manualFocusSupported == false) null else spec
            spec.id.endsWith(".iso") && caps.isoMin != null && caps.isoMax != null ->
                clip { option ->
                    val value = option.filter { it.isDigit() }.toIntOrNull()
                    value == null || value in caps.isoMin..caps.isoMax
                }
            spec.id.endsWith(".shutter_speed") && caps.exposureMinNs != null && caps.exposureMaxNs != null ->
                clip { option ->
                    val ns = shutterOptionNanos(option)
                    ns == null || ns in caps.exposureMinNs..caps.exposureMaxNs
                }
            (spec.id.endsWith(".exposure_value") || spec.id.endsWith(".exposure_compensation")) &&
                caps.evMin != null && caps.evMax != null ->
                clip { option ->
                    val ev = option.replace("+", "").toDoubleOrNull()
                    ev == null || ev in caps.evMin..caps.evMax
                }
            else -> spec
        }
    }

    /** "1/8000" → 125000ns, "2\"" or "2s" → 2s in ns; null for AUTO and other words. */
    private fun shutterOptionNanos(option: String): Long? {
        val text = option.trim().removeSuffix("\"").removeSuffix("s")
        return when {
            text.startsWith("1/") -> text.drop(2).toDoubleOrNull()?.takeIf { it > 0 }
                ?.let { (1_000_000_000L / it).toLong() }
            else -> text.toDoubleOrNull()?.let { (it * 1_000_000_000L).toLong() }
        }
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
        val settings = settingsFor(
            camera.activeMode,
            camera.deviceVariant,
            camera.detectedLenses,
            camera.capabilities,
        )
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
        val focus = settingsFor(
            camera.activeMode,
            camera.deviceVariant,
            camera.detectedLenses,
            camera.capabilities,
        ).firstOrNull { it.id.endsWith(".manual_focus") } ?: return false
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
     * The one bar template, identical for every mode:
     *
     *   [More] · extras… · Lens · Exposure · Shutter · ISO · [MODE] · Focus · WB · Slider · Gallery
     *
     * Mode-specific extras live at the far left behind the More toggle; the six-spec spine and
     * the two shortcut tiles are fixed, and a mode that lacks a spine setting simply skips that
     * tile. The mode token is anchored after ISO by construction — no midpoint arithmetic — so
     * the diver's muscle memory holds across every mode.
     */
    fun settingsBarItems(
        mode: CameraModeId,
        variant: GalaxyDeviceVariant,
        showMore: Boolean,
        detectedLenses: List<String> = emptyList(),
        capabilities: CameraCapabilities? = null,
    ): List<BottomBarItem> {
        val allSettings = settingsFor(mode, variant, detectedLenses, capabilities)

        fun find(vararg suffixes: String): CameraSettingSpec? =
            allSettings.firstOrNull { spec -> suffixes.any { spec.id.endsWith(it) } }

        val lens = find(".lens") ?: synthesizedLensSpec(mode, variant, detectedLenses)
        val ev = find(".exposure_value", ".exposure_compensation")
        val shutter = find(".shutter_speed")
        val iso = find(".iso")
        val focus = find(".manual_focus")
        val wb = find(".white_balance")

        val spine = listOfNotNull(lens, ev, shutter, iso, focus, wb)
        val extras = allSettings.filter { it !in spine }
        val slider = sliderAssignmentSpec(mode, allSettings)

        return buildList {
            if (extras.isNotEmpty()) {
                add(BottomBarItem.MoreSettings)
                if (showMore) extras.forEach { add(BottomBarItem.Setting(it)) }
            }
            listOfNotNull(lens, ev, shutter, iso).forEach { add(BottomBarItem.Setting(it)) }
            add(BottomBarItem.ModesButton)
            listOfNotNull(focus, wb).forEach { add(BottomBarItem.Setting(it)) }
            add(BottomBarItem.Setting(slider))
            add(BottomBarItem.GalleryShortcut)
        }
    }

    /** The bar exactly as the reducer must see it: same lenses, same capabilities as the UI. */
    fun settingsBarItems(camera: CameraState): List<BottomBarItem> = settingsBarItems(
        mode = camera.activeMode,
        variant = camera.deviceVariant,
        showMore = camera.showMoreSettings,
        detectedLenses = camera.detectedLenses,
        capabilities = camera.capabilities,
    )

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
        return camera.settingValues[spec.id] ?: spec.defaultValue
    }

    fun focusAssistSettingId(focusSettingId: String): String? = when (focusSettingId) {
        "photo.manual_focus" -> "photo.focus_peaking"
        "expert.manual_focus" -> "expert.focus_peaking"
        "pro.manual_focus" -> "pro.focus_peaking"
        "pro_video.manual_focus" -> "pro_video.focus_peaking"
        else -> null
    }

    fun focusCurveSettingId(focusSettingId: String): String? = when (focusSettingId) {
        "photo.manual_focus" -> "photo.focus_curve"
        "expert.manual_focus" -> "expert.focus_curve"
        "pro.manual_focus" -> "pro.focus_curve"
        "pro_video.manual_focus" -> "pro_video.focus_curve"
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
            availableFormatOptions = listOf("JPEG", "RAW", "RAW + JPEG"),
            availableExposureControls = listOf("Flash", "Lens", "Focus", "Exposure Value"),
            availableAssistTools = listOf("HDR / LOG", "Filters", "Gallery"),
            settings = listOf(
                choice("photo.flash", "Flash", "Core", listOf("Auto", "Off", "On"), "Auto"),
                choice("photo.megapixels", "Photo MP", "Core", megapixels, megapixels.first()),
                choice("photo.save_format", "RAW / JPEG", "Core", listOf("JPEG", "RAW", "RAW + JPEG"), "JPEG"),
                choice("photo.lens", "Lens", "Core", lenses, "Auto"),
                slider("photo.manual_focus", "Focus", "Core", focusOptions(), "AF"),
                toggle("photo.focus_peaking", "Focus Assist", "Assist"),
                choice("photo.focus_curve", "Focus Curve", "Assist", focusCurveOptions(), "SquareRoot"),
                slider("photo.exposure_compensation", "EV", "Core", evOptions(), "Auto"),
                choice("photo.hdr_log", "HDR / LOG", "Assist", listOf("HDR", "LOG", "Off"), "HDR"),
                choice("photo.filters", "Filters", "Core", underwaterFilterOptions(), "Off"),
            ),
        )
    }

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
            availableAssistTools = listOf("Exposure monitor", "Guidelines", "Grid", "HDR"),
            settings = listOf(
                choice("expert.flash", "Flash", "Core", listOf("Auto", "Off", "On"), "Off"),
                choice("expert.megapixels", "Photo MP", "Core", megapixels, megapixels.first()),
                choice("expert.save_format", "RAW / JPEG", "Core", listOf("RAW", "JPEG", "RAW + JPEG"), "RAW + JPEG"),
                choice("expert.lens", "Lens", "Core", lenses, "Auto"),
                slider("expert.white_balance", "White balance", "Manual", whiteBalanceOptions(), "Auto"),
                slider("expert.iso", "ISO", "Manual", isoOptions(), "100"),
                slider("expert.manual_focus", "Focus", "Manual", focusOptions(), "AF"),
                toggle("expert.focus_peaking", "Focus Assist", "Assist"),
                choice("expert.focus_curve", "Focus Curve", "Assist", focusCurveOptions(), "SquareRoot"),
                slider("expert.shutter_speed", "Shutter", "Manual", shutterOptions(), "1/60"),
                slider("expert.exposure_value", "Exposure Value", "Manual", evOptions(), "Auto"),
                toggle("expert.exposure_monitor", "Exposure monitor", "Assist"),
                choice("expert.guidelines", "Guidelines", "Assist", listOf("Off", "On"), "On"),
                choice("expert.grid", "Grid", "Assist", gridOptions(), "3x3"),
                choice("expert.hdr", "HDR", "Assist", listOf("Off", "On"), "On"),
            ),
        )
    }

    private fun proProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        return CameraModeProfile(
            mode = CameraModeId.Pro,
            modeName = CameraModeId.Pro.label,
            captureType = CameraCaptureType.Photo,
            availableLenses = lenses,
            availableResolutions = photoMegapixels(variant),
            availableExposureControls = listOf("White balance", "ISO", "Focus", "Shutter", "Exposure value"),
            availableAssistTools = listOf("Exposure monitor", "Guidelines", "Grid", "HDR"),
            settings = listOf(
                slider("pro.white_balance", "White balance", "Manual", whiteBalanceOptions(), "Auto"),
                slider("pro.iso", "ISO", "Manual", isoOptions(), "100"),
                slider("pro.manual_focus", "Focus", "Manual", focusOptions(), "AF"),
                toggle("pro.focus_peaking", "Focus Assist", "Assist"),
                choice("pro.focus_curve", "Focus Curve", "Assist", focusCurveOptions(), "SquareRoot"),
                slider("pro.shutter_speed", "Shutter", "Manual", shutterOptions(), "1/60"),
                slider("pro.exposure_value", "Exposure Value", "Manual", evOptions(), "Auto"),
                choice("pro.flash", "Flash", "Core", listOf("Auto", "Off", "On"), "Off"),
                choice("pro.lens", "Lens", "Core", lenses, "Auto"),
                toggle("pro.exposure_monitor", "Exposure monitor", "Assist"),
                choice("pro.guidelines", "Guidelines", "Assist", listOf("Off", "On"), "On"),
                choice("pro.grid", "Grid", "Assist", gridOptions(), "3x3"),
                choice("pro.hdr", "HDR", "Assist", listOf("Off", "On"), "On"),
            ),
        )
    }

    private fun panoramaProfile(_variant: GalaxyDeviceVariant): CameraModeProfile = CameraModeProfile(
        mode = CameraModeId.Panorama,
        modeName = CameraModeId.Panorama.label,
        captureType = CameraCaptureType.Photo,
        availableLenses = listOf("1x"),
        availableResolutions = listOf("Auto"),
        availableAssistTools = listOf("Guidelines", "Grid"),
        settings = listOf(
            choice("panorama.lens", "Lens", "Core", listOf("1x"), "1x"),
            choice("panorama.direction", "Sweep", "Core", listOf("Horizontal", "Vertical"), "Horizontal"),
            choice("panorama.grid", "Grid", "Assist", gridOptions(), "3x3"),
        ),
    )

    private fun nightProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        val megapixels = photoMegapixels(variant)
        return CameraModeProfile(
            mode = CameraModeId.Night,
            modeName = CameraModeId.Night.label,
            captureType = CameraCaptureType.Photo,
            availableLenses = lenses,
            availableResolutions = megapixels,
            availableExposureControls = listOf("Exposure value"),
            availableAssistTools = listOf("Grid"),
            settings = listOf(
                choice("night.flash", "Flash", "Core", listOf("Auto", "Off", "On"), "Auto"),
                choice("night.megapixels", "Photo MP", "Core", megapixels, megapixels.first()),
                choice("night.lens", "Lens", "Core", lenses, "1x"),
                slider("night.exposure", "Exposure Value", "Core", evOptions(), "0", supportsSensitivity = false),
                choice("night.grid", "Grid", "Assist", gridOptions(), "3x3"),
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
            availableFrameRates = listOf("15x", "30x", "60x", "Auto"),
            availableAssistTools = listOf("Grid"),
            settings = listOf(
                choice("hyperlapse.speed", "Speed", "Core", listOf("15x", "30x", "60x", "Auto"), "Auto"),
                choice("hyperlapse.frame_rate", "Frame rate", "Core", listOf("24fps", "30fps"), "30fps"),
                choice("hyperlapse.lens", "Lens", "Core", lenses, "1x"),
                choice("hyperlapse.grid", "Grid", "Assist", gridOptions(), "3x3"),
            ),
        )
    }

    private fun videoProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        val frameRates = videoFrameRates(variant)
        return CameraModeProfile(
            mode = CameraModeId.Video,
            modeName = CameraModeId.Video.label,
            captureType = CameraCaptureType.Video,
            availableLenses = lenses,
            availableResolutions = listOf("FHD", "UHD 4K", "8K"),
            availableFrameRates = frameRates,
            availableFormatOptions = listOf("Standard", "HDR", "LOG"),
            availableAudioControls = listOf("Microphone"),
            availableAssistTools = listOf("Exposure monitor", "Guidelines", "Grid"),
            settings = listOf(
                choice("video.resolution", "Resolution", "Core", listOf("FHD", "UHD 4K", "8K"), "UHD 4K"),
                choice("video.frame_rate", "Frame rate", "Core", frameRates, "30fps"),
                choice("video.lens", "Lens", "Core", lenses, "1x"),
                choice("video.flash", "Flash / Torch", "Core", listOf("Off", "Torch"), "Off"),
                choice("video.microphone", "Microphone", "Audio", microphoneSources(), "Auto"),
                toggle("video.exposure_monitor", "Exposure monitor", "Assist"),
                choice("video.guidelines", "Guidelines", "Assist", listOf("Off", "On"), "On"),
                choice("video.grid", "Grid", "Assist", gridOptions(), "3x3"),
                choice("video.hdr", "HDR", "Assist", listOf("Off", "On"), "On"),
                choice("video.log", "LOG", "Assist", listOf("Off", "On"), "Off"),
                toggle("video.super_steady", "Super Steady", "Core", "Off"),
                choice("video.filters", "Filters", "Core", underwaterFilterOptions(), "Off"),
                choice("video.megapixels", "Video MP", "Core", listOf("Auto", "12MP", "24MP", "50MP"), "Auto"),
                toggle("video.motion_photo", "Motion Photo", "Core", "Off"),
            ),
        )
    }

    private fun proVideoProfile(variant: GalaxyDeviceVariant): CameraModeProfile {
        val lenses = photoLenses(variant)
        val frameRates = videoFrameRates(variant)
        return CameraModeProfile(
            mode = CameraModeId.ProVideo,
            modeName = CameraModeId.ProVideo.label,
            captureType = CameraCaptureType.Video,
            availableLenses = lenses,
            availableResolutions = listOf("FHD", "UHD 4K", "8K"),
            availableFrameRates = frameRates,
            availableFormatOptions = listOf("Standard", "HDR", "LOG"),
            availableExposureControls = listOf("White balance", "ISO", "Focus", "Shutter", "Exposure value", "Frame rate"),
            availableAudioControls = listOf("Microphone", "Microphone gain"),
            availableAssistTools = listOf("Exposure monitor", "Guidelines", "Grid", "HDR", "LOG"),
            settings = listOf(
                slider("pro_video.white_balance", "White balance", "Manual", whiteBalanceOptions(), "Auto"),
                slider("pro_video.iso", "ISO", "Manual", isoOptions(), "100"),
                slider("pro_video.manual_focus", "Focus", "Manual", focusOptions(), "AF"),
                toggle("pro_video.focus_peaking", "Focus Assist", "Assist"),
                choice("pro_video.focus_curve", "Focus Curve", "Assist", focusCurveOptions(), "SquareRoot"),
                slider("pro_video.shutter_speed", "Shutter", "Manual", shutterOptions(), "1/60"),
                slider("pro_video.exposure_value", "Exposure Value", "Manual", evOptions(), "Auto"),
                slider("pro_video.frame_rate", "Frame rate", "Manual", frameRates, "30fps"),
                choice("pro_video.flash", "Flash / Torch", "Core", listOf("Off", "Torch"), "Off"),
                choice("pro_video.lens", "Lens", "Core", lenses, "auto"),
                choice("pro_video.microphone_source", "Microphone", "Audio", microphoneSources(), "Auto"),
                slider("pro_video.microphone_gain", "Microphone gain", "Audio", microphoneGainOptions(), "0dB"),
                toggle("pro_video.exposure_monitor", "Exposure monitor", "Assist"),
                choice("pro_video.guidelines", "Guidelines", "Assist", listOf("Off", "On"), "On"),
                choice("pro_video.grid", "Grid", "Assist", gridOptions(), "3x3"),
                // Both default OFF: LOG is a deliberate grading workflow, not a default look,
                // and shipping it on silently cost the field ~2 stops of apparent brightness.
                choice("pro_video.hdr", "HDR", "Assist", listOf("Off", "On"), "Off"),
                choice("pro_video.log", "LOG", "Assist", listOf("Off", "On"), "Off"),
            ),
        )
    }

    private fun portraitVideoProfile(_variant: GalaxyDeviceVariant): CameraModeProfile = CameraModeProfile(
        mode = CameraModeId.PortraitVideo,
        modeName = CameraModeId.PortraitVideo.label,
        captureType = CameraCaptureType.Video,
        availableLenses = listOf("1x", "2x", "3x"),
        availableResolutions = listOf("FHD", "UHD 4K"),
        availableFrameRates = listOf("24fps", "30fps", "60fps"),
        availableAudioControls = listOf("Microphone"),
        availableAssistTools = listOf("Grid"),
        settings = listOf(
            choice("portrait_video.lens", "Lens", "Core", listOf("1x", "2x", "3x"), "1x"),
            choice("portrait_video.frame_rate", "Frame rate", "Core", listOf("24fps", "30fps", "60fps"), "30fps"),
            choice("portrait_video.flash", "Flash / Torch", "Core", listOf("Off", "Torch"), "Off"),
            choice("portrait_video.microphone", "Microphone", "Audio", microphoneSources(), "Auto"),
            choice("portrait_video.grid", "Grid", "Assist", gridOptions(), "3x3"),
        ),
    )

    private fun slowMotionProfile(_variant: GalaxyDeviceVariant): CameraModeProfile = CameraModeProfile(
        mode = CameraModeId.SlowMotion,
        modeName = CameraModeId.SlowMotion.label,
        captureType = CameraCaptureType.Video,
        availableLenses = listOf("1x"),
        availableResolutions = listOf("HD", "FHD"),
        availableFrameRates = listOf("120fps", "240fps"),
        availableAudioControls = listOf("Microphone"),
        availableAssistTools = listOf("Grid"),
        settings = listOf(
            choice("slow_motion.frame_rate", "Frame rate", "Core", listOf("120fps", "240fps"), "240fps"),
            choice("slow_motion.lens", "Lens", "Core", listOf("1x"), "1x"),
            choice("slow_motion.microphone", "Microphone", "Audio", listOf("Off", "On"), "On"),
            choice("slow_motion.grid", "Grid", "Assist", gridOptions(), "3x3"),
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
            availableFormatOptions = listOf("HDR", "LOG"),
            availableAudioControls = listOf("Microphone"),
            availableAssistTools = listOf("Exposure monitor", "Guidelines", "Grid"),
            settings = listOf(
                choice("night_video.resolution", "Resolution", "Core", listOf("FHD", "UHD 4K"), "UHD 4K"),
                choice("night_video.frame_rate", "Frame rate", "Core", frameRates, "30fps"),
                choice("night_video.lens", "Lens", "Core", lenses, "1x"),
                choice("night_video.microphone", "Microphone", "Audio", microphoneSources(), "Auto"),
                toggle("night_video.exposure_monitor", "Exposure monitor", "Assist"),
                choice("night_video.guidelines", "Guidelines", "Assist", listOf("Off", "On"), "On"),
                choice("night_video.grid", "Grid", "Assist", gridOptions(), "3x3"),
                choice("night_video.hdr", "HDR", "Assist", listOf("Off", "On"), "On"),
                choice("night_video.log", "LOG", "Assist", listOf("Off", "On"), "Off"),
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
        GalaxyDeviceVariant.S26Plus -> listOf("12MP", "24MP", "50MP")
        GalaxyDeviceVariant.S26Ultra -> listOf("12MP", "24MP", "50MP", "200MP")
    }

    private fun videoFrameRates(variant: GalaxyDeviceVariant): List<String> = when (variant) {
        GalaxyDeviceVariant.S26Ultra -> listOf("24fps", "30fps", "60fps", "120fps")
        else -> listOf("24fps", "30fps", "60fps")
    }

    private fun microphoneSources(): List<String> = listOf("Auto", "Front", "Rear", "USB", "Mixed")

    private fun microphoneGainOptions(): List<String> = listOf("-12dB", "-6dB", "0dB", "+6dB", "+12dB")

    private fun gridOptions(): List<String> = listOf("Off", "3x3", "Square")

    private fun isoOptions(): List<String> = listOf("Auto", "50", "100", "200", "400", "800", "1600", "3200", "6400")

    private fun shutterOptions(): List<String> = listOf(
        "Auto",
        "1/8000",
        "1/4000",
        "1/2000",
        "1/1000",
        "1/500",
        "1/250",
        "1/125",
        "1/60",
        "1/30",
        "1/15",
        "1/8",
        "1/4",
        "1/2",
        "1\"",
        "2\"",
        "4\"",
        "8\"",
        "15\"",
        "30\"",
    )

    private fun whiteBalanceOptions(): List<String> =
        listOf("Auto", "2300K", "2800K", "3200K", "4000K", "5600K", "6500K", "7500K", "8500K", "10000K")

    private fun focusOptions(): List<String> = buildList {
        add("AF")
        for (step in 0..100) {
            add(String.format(Locale.US, "%.2f", step / 100.0))
        }
    }

    private fun underwaterFilterOptions(): List<String> = buildList {
        add("Off")
        add("Auto")
        for (depthMeters in 0..50 step 5) {
            add("${depthMeters}m")
        }
    }

    private fun evOptions(): List<String> = buildList {
        // "Auto" leads the ladder: auto-exposure with zero offset, the explicit hands-off
        // choice the field asked for. Then 4x finer granularity: 0.025 EV steps over the
        // +/-2.0 range (161 numeric options); applyExposure() rounds to the nearest
        // hardware compensation index.
        add("Auto")
        for (step in -80..80) {
            val value = step / 40.0
            if (value == 0.0) {
                add("0")
            } else {
                add(String.format(Locale.US, "%+.2f", value))
            }
        }
    }

    private fun focusCurveOptions(): List<String> = listOf("Linear", "SquareRoot", "Logarithmic")
}

val CameraState.primaryHighlightedEntry: CameraRailEntry
    get() = CameraCatalog.highlightedPrimaryEntry(this)

val CameraState.secondaryHighlightedMode: CameraModeId
    get() = CameraCatalog.highlightedSecondaryMode(this)

val CameraState.selectedSetting: CameraSettingSpec?
    get() = CameraCatalog.selectedSetting(this)


