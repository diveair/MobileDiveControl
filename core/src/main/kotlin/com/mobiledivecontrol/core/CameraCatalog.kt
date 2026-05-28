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
        val items = settingsBarItems(camera.activeMode, camera.deviceVariant, camera.showMoreSettings)
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
        showMoreSettings: Boolean = false,
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
            deviceVariant = deviceVariant,
            showMoreSettings = showMoreSettings,
        )
    }

    fun settingsBarItems(
        mode: CameraModeId,
        variant: GalaxyDeviceVariant,
        showMore: Boolean
    ): List<BottomBarItem> {
        val allSettings = settingsFor(mode, variant)

        val itemsWithoutModes = when {
            // PRO MODES
            mode == CameraModeId.Pro || mode == CameraModeId.ProVideo || mode == CameraModeId.ExpertRaw -> {
                val items = mutableListOf<BottomBarItem>()
                val iso = allSettings.find { it.id.endsWith(".iso") }
                val shutter = allSettings.find { it.id.endsWith(".shutter_speed") }
                val ev = allSettings.find { it.id.endsWith(".exposure_value") }
                val lens = allSettings.find { it.id.endsWith(".lens") }
                val focus = allSettings.find { it.id.endsWith(".manual_focus") }
                val wb = allSettings.find { it.id.endsWith(".white_balance") }

                val prioritySpecs = listOfNotNull(iso, shutter, ev, lens, focus, wb)
                prioritySpecs.forEach { items.add(BottomBarItem.Setting(it)) }

                val otherSettings = allSettings.filter { it !in prioritySpecs }
                if (showMore) {
                    otherSettings.forEach { items.add(BottomBarItem.Setting(it)) }
                }

                if (otherSettings.isNotEmpty()) {
                    items.add(BottomBarItem.MoreSettings)
                }
                items
            }
            // CAMERA MODE (Photo)
            mode == CameraModeId.Photo -> {
                val items = mutableListOf<BottomBarItem>()
                val flash = allSettings.find { it.id.endsWith(".flash") }
                val megapixels = allSettings.find { it.id.endsWith(".megapixels") }
                val saveFormat = allSettings.find { it.id.endsWith(".save_format") }
                val lens = allSettings.find { it.id.endsWith(".lens") }
                val focus = allSettings.find { it.id.endsWith(".manual_focus") }
                val exposureValue = allSettings.find { it.id.endsWith(".exposure_compensation") }
                val hdr = allSettings.find { it.id.endsWith(".hdr_log") }
                val filters = allSettings.find { it.id.endsWith(".filters") }
                listOfNotNull(flash, megapixels, saveFormat, lens, focus).forEach { items.add(BottomBarItem.Setting(it)) }
                listOfNotNull(exposureValue, hdr, filters).forEach { items.add(BottomBarItem.Setting(it)) }
                items.add(BottomBarItem.GalleryShortcut)
                items
            }
            mode == CameraModeId.Portrait || mode == CameraModeId.Night || mode == CameraModeId.Burst -> {
                val items = mutableListOf<BottomBarItem>()
                val flash = allSettings.find { it.id.endsWith(".flash") }
                if (flash != null) items.add(BottomBarItem.Setting(flash))

                val lenses = photoLenses(variant).filter { it != "front" }
                lenses.forEach { lensVal ->
                    items.add(BottomBarItem.LensShortcut(lensVal))
                }

                val megapixels = allSettings.find { it.id.endsWith(".megapixels") }
                if (megapixels != null) items.add(BottomBarItem.Setting(megapixels))

                val motionPhoto = allSettings.find { it.id.endsWith(".motion_photo") }
                if (motionPhoto != null) items.add(BottomBarItem.Setting(motionPhoto))

                val filters = allSettings.find { it.id.endsWith(".filters") }
                if (filters != null) items.add(BottomBarItem.Setting(filters))

                val prioritySpecs = listOfNotNull(flash, megapixels, motionPhoto, filters)
                val otherSettings = allSettings.filter { it !in prioritySpecs && !it.id.endsWith(".lens") }
                if (showMore) {
                    otherSettings.forEach { items.add(BottomBarItem.Setting(it)) }
                }

                if (otherSettings.isNotEmpty()) {
                    items.add(BottomBarItem.MoreSettings)
                }
                items
            }
            // VIDEO / NON-PRO VIDEO MODES
            else -> {
                val items = mutableListOf<BottomBarItem>()
                val flash = allSettings.find { it.id.endsWith(".flash") }
                if (flash != null) items.add(BottomBarItem.Setting(flash))

                val superSteady = allSettings.find { it.id.endsWith(".super_steady") }
                if (superSteady != null) items.add(BottomBarItem.Setting(superSteady))

                val resolution = allSettings.find { it.id.endsWith(".resolution") }
                if (resolution != null) items.add(BottomBarItem.Setting(resolution))

                val frameRate = allSettings.find { it.id.endsWith(".frame_rate") }
                if (frameRate != null) items.add(BottomBarItem.Setting(frameRate))

                val hdr = allSettings.find { it.id.endsWith(".hdr") }
                if (hdr != null) items.add(BottomBarItem.Setting(hdr))

                val lenses = photoLenses(variant).filter { it != "front" }
                lenses.forEach { lensVal ->
                    items.add(BottomBarItem.LensShortcut(lensVal))
                }
                items.add(BottomBarItem.LensShortcut("front"))

                val megapixels = allSettings.find { it.id.endsWith(".megapixels") }
                if (megapixels != null) items.add(BottomBarItem.Setting(megapixels))

                val motionPhoto = allSettings.find { it.id.endsWith(".motion_photo") }
                if (motionPhoto != null) items.add(BottomBarItem.Setting(motionPhoto))

                val filters = allSettings.find { it.id.endsWith(".filters") }
                if (filters != null) items.add(BottomBarItem.Setting(filters))

                val prioritySpecs = listOfNotNull(flash, superSteady, resolution, frameRate, hdr, megapixels, motionPhoto, filters)
                val otherSettings = allSettings.filter { it !in prioritySpecs && !it.id.endsWith(".lens") }
                if (showMore) {
                    otherSettings.forEach { items.add(BottomBarItem.Setting(it)) }
                }

                if (otherSettings.isNotEmpty()) {
                    items.add(BottomBarItem.MoreSettings)
                }
                items
            }
        }

        val insertAfterSettingId = when (mode) {
            CameraModeId.Photo -> "photo.manual_focus"
            else -> null
        }
        return withModesCentered(itemsWithoutModes, insertAfterSettingId)
    }

    private fun withModesCentered(items: List<BottomBarItem>, insertAfterSettingId: String? = null): List<BottomBarItem> {
        val preferredIndex = insertAfterSettingId?.let { settingId ->
            items.indexOfFirst { item ->
                item is BottomBarItem.Setting && item.spec.id == settingId
            }.takeIf { it >= 0 }?.plus(1)
        }
        val centerIndex = preferredIndex ?: (items.size / 2)
        return buildList(items.size + 1) {
            addAll(items.take(centerIndex))
            add(BottomBarItem.ModesButton)
            addAll(items.drop(centerIndex))
        }
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
                choice("photo.lens", "Lens", "Core", lenses, "1x"),
                slider("photo.manual_focus", "Focus", "Core", focusOptions(), "AF"),
                toggle("photo.focus_peaking", "Focus Assist", "Assist"),
                slider("photo.exposure_compensation", "EV", "Core", evOptions(), "0"),
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
                choice("expert.lens", "Lens", "Core", lenses, "1x"),
                slider("expert.white_balance", "White balance", "Manual", whiteBalanceOptions(), "5600K"),
                slider("expert.iso", "ISO", "Manual", isoOptions(), "100"),
                slider("expert.manual_focus", "Focus", "Manual", focusOptions(), "AF"),
                toggle("expert.focus_peaking", "Focus Assist", "Assist"),
                slider("expert.shutter_speed", "Shutter", "Manual", shutterOptions(), "1/60"),
                slider("expert.exposure_value", "Exposure Value", "Manual", evOptions(), "0"),
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
                slider("pro.white_balance", "White balance", "Manual", whiteBalanceOptions(), "5600K"),
                slider("pro.iso", "ISO", "Manual", isoOptions(), "100"),
                slider("pro.manual_focus", "Focus", "Manual", focusOptions(), "AF"),
                toggle("pro.focus_peaking", "Focus Assist", "Assist"),
                slider("pro.shutter_speed", "Shutter", "Manual", shutterOptions(), "1/60"),
                slider("pro.exposure_value", "Exposure Value", "Manual", evOptions(), "0"),
                choice("pro.flash", "Flash", "Core", listOf("Auto", "Off", "On"), "Off"),
                choice("pro.lens", "Lens", "Core", lenses, "1x"),
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
                slider("pro_video.white_balance", "White balance", "Manual", whiteBalanceOptions(), "5600K"),
                slider("pro_video.iso", "ISO", "Manual", isoOptions(), "100"),
                slider("pro_video.manual_focus", "Focus", "Manual", focusOptions(), "AF"),
                toggle("pro_video.focus_peaking", "Focus Assist", "Assist"),
                slider("pro_video.shutter_speed", "Shutter", "Manual", shutterOptions(), "1/60"),
                slider("pro_video.exposure_value", "Exposure Value", "Manual", evOptions(), "0"),
                slider("pro_video.frame_rate", "Frame rate", "Manual", frameRates, "30fps"),
                choice("pro_video.flash", "Flash / Torch", "Core", listOf("Off", "Torch"), "Off"),
                choice("pro_video.lens", "Lens", "Core", lenses, "1x"),
                choice("pro_video.microphone_source", "Microphone", "Audio", microphoneSources(), "Auto"),
                slider("pro_video.microphone_gain", "Microphone gain", "Audio", microphoneGainOptions(), "0dB"),
                toggle("pro_video.exposure_monitor", "Exposure monitor", "Assist"),
                choice("pro_video.guidelines", "Guidelines", "Assist", listOf("Off", "On"), "On"),
                choice("pro_video.grid", "Grid", "Assist", gridOptions(), "3x3"),
                choice("pro_video.hdr", "HDR", "Assist", listOf("Off", "On"), "On"),
                choice("pro_video.log", "LOG", "Assist", listOf("Off", "On"), "On"),
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
        GalaxyDeviceVariant.S26Plus -> listOf("0.6x", "1x", "2x", "3x", "front")
        GalaxyDeviceVariant.S26Ultra -> listOf("0.6x", "1x", "2x", "3x", "5x", "front")
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

    private fun whiteBalanceOptions(): List<String> = listOf("2300K", "2800K", "3200K", "4000K", "5600K", "6500K", "7500K", "8500K", "10000K")

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
        // 4x finer granularity: 0.025 EV steps over ±2.0 range (161 options)
        // applyExposure() rounds to nearest hardware compensation index
        for (step in -80..80) {
            val value = step / 40.0
            if (value == 0.0) {
                add("0")
            } else {
                add(String.format(Locale.US, "%+.2f", value))
            }
        }
    }
}

val CameraState.primaryHighlightedEntry: CameraRailEntry
    get() = CameraCatalog.highlightedPrimaryEntry(this)

val CameraState.secondaryHighlightedMode: CameraModeId
    get() = CameraCatalog.highlightedSecondaryMode(this)

val CameraState.selectedSetting: CameraSettingSpec?
    get() = CameraCatalog.selectedSetting(this)


