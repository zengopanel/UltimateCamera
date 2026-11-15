package com.ultimatecamera.hardware

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.media.MediaRecorder
import android.media.MediaCodecList
import android.util.Range
import android.util.Size
import android.util.SizeF
import android.util.Log
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

data class VideoProfile(
    val size: Size,
    val fps: Int,
    val aeFpsRange: Range<Int> = Range(fps, fps)
) {
    val label: String
        get() = "${size.width}×${size.height} · $frameRateLabel"

    val pixels: Long
        get() = size.width.toLong() * size.height

    val frameRateLabel: String
        get() = if (aeFpsRange.lower == aeFpsRange.upper) "${fps}fps"
        else "${fps}fps target · AE ${aeFpsRange.lower}–${aeFpsRange.upper}"
}

enum class VideoCodec(val mimeType: String, val recorderEncoder: Int, val label: String) {
    H264("video/avc", MediaRecorder.VideoEncoder.H264, "H.264"),
    H265("video/hevc", MediaRecorder.VideoEncoder.HEVC, "H.265")
}

/** Everything the UI and engine need to know about one camera, read from the real hardware. */
data class CameraCaps(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val focalLengths: List<Float>,
    val sensorSizeMm: SizeF?,
    val pixelArray: Size?,
    val activeArray: Rect,
    val maxJpeg: Size,
    val rawSize: Size?,
    val hasRaw: Boolean,
    val hasManualSensor: Boolean,
    val hasManualWhiteBalance: Boolean,
    val hasManualToneCurve: Boolean,
    val toneCurvePoints: Int,
    val hasAeLock: Boolean,
    val hasAfLock: Boolean,
    val isoRange: Range<Int>?,
    val exposureRangeNs: Range<Long>?,
    val minFocusDiopters: Float,
    val evRange: Range<Int>,
    val evStep: Float,
    val awbModes: List<Int>,
    val hardwareLevel: Int,
    val oisSupported: Boolean,
    val eisSupported: Boolean,
    val maxZoom: Float,
    val hasFlash: Boolean,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
    val physicalIds: Set<String>,
    val previewSizes: List<Size>,
    val jpegSizes: List<Size>,
    val videoProfiles: List<VideoProfile>,
    val videoCodecs: Map<VideoProfile, List<VideoCodec>>,
    val sceneModes: List<Int>,
    val fpsRanges: List<Range<Int>>,
    val extensionModes: List<Int>,
    val extensionPhotoSizes: Map<Int, List<Size>>,
    val extensionPreviewSizes: Map<Int, List<Size>>,
    val extensionZoomModes: Set<Int>
) {
    val hasManualFocus get() = minFocusDiopters > 0f
    val supportsVideoStabilization get() = eisSupported || oisSupported
    val isFront get() = facing == CameraCharacteristics.LENS_FACING_FRONT
    val supportsHdrScene get() = CameraMetadata.CONTROL_SCENE_MODE_HDR in sceneModes
    val supportsNightScene get() = CameraMetadata.CONTROL_SCENE_MODE_NIGHT in sceneModes
    val supportsHdrExtension get() = CameraExtensionCharacteristics.EXTENSION_HDR in extensionModes &&
        extensionPhotoSizes[CameraExtensionCharacteristics.EXTENSION_HDR].orEmpty().isNotEmpty() &&
        extensionPreviewSizes[CameraExtensionCharacteristics.EXTENSION_HDR].orEmpty().isNotEmpty()
    val supportsHdr get() = supportsHdrScene || supportsHdrExtension
    val supportsNightExtension get() = CameraExtensionCharacteristics.EXTENSION_NIGHT in extensionModes &&
        extensionPhotoSizes[CameraExtensionCharacteristics.EXTENSION_NIGHT].orEmpty().isNotEmpty() &&
        extensionPreviewSizes[CameraExtensionCharacteristics.EXTENSION_NIGHT].orEmpty().isNotEmpty()
    val supportsNight get() = supportsNightScene || supportsNightExtension
    val supportsPortrait get() = CameraExtensionCharacteristics.EXTENSION_BOKEH in extensionModes &&
        extensionPhotoSizes[CameraExtensionCharacteristics.EXTENSION_BOKEH].orEmpty().isNotEmpty() &&
        extensionPreviewSizes[CameraExtensionCharacteristics.EXTENSION_BOKEH].orEmpty().isNotEmpty()

    fun defaultVideoProfile(): VideoProfile? =
        videoProfiles.firstOrNull { it.fps == 30 && it.size.width <= 1920 }
            ?: videoProfiles.firstOrNull { it.fps == 30 }
            ?: videoProfiles.firstOrNull()

    /** Largest preview with the same aspect ratio as the photo, capped near 1080p to stay smooth. */
    fun bestPreview(photoSize: Size = maxJpeg, extensionMode: Int? = null): Size {
        val target = photoSize.width.toDouble() / photoSize.height
        val candidates = extensionMode?.let { extensionPreviewSizes[it] }.orEmpty().ifEmpty { previewSizes }
        val capped = candidates.filter { it.width <= 1920 && it.height <= 1440 }
        val matching = capped.filter { abs(it.width.toDouble() / it.height - target) < 0.02 }
        return (matching.ifEmpty { capped.ifEmpty { candidates } }).maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)
    }

    fun describe(): String = buildString {
        val lensName = if (isFront) "Front" else "Back"
        appendLine("Camera $id ($lensName)")
        appendLine("Level: ${levelName(hardwareLevel)}")
        appendLine("Max photo: ${maxJpeg.width}x${maxJpeg.height}")
        appendLine("RAW/DNG: ${if (hasRaw && rawSize != null) "yes ${rawSize.width}x${rawSize.height}" else "no"}")
        appendLine("Manual ISO/shutter: ${if (hasManualSensor) "yes" else "no"}")
        isoRange?.let { if (hasManualSensor) appendLine("ISO: ${it.lower}-${it.upper}") }
        exposureRangeNs?.let { if (hasManualSensor) appendLine("Shutter: ${fmtShutter(it.lower)} - ${fmtShutter(it.upper)}") }
        appendLine("Manual focus: ${if (hasManualFocus) "yes" else "no"}")
        appendLine("Manual Kelvin: ${if (hasManualWhiteBalance) "yes" else "no"}")
        appendLine("Highlight/shadow tone curve: ${if (hasManualToneCurve) "yes" else "no"}")
        appendLine("AE lock: ${if (hasAeLock) "yes" else "no"}  AF lock: ${if (hasAfLock) "yes" else "no"}")
        appendLine("Digital zoom: up to %.1fx".format(maxZoom))
        appendLine("Flash: ${if (hasFlash) "yes" else "no"}")
        appendLine("Focal length(s): ${focalLengths.joinToString { "%.2fmm".format(it) }}")
        sensorSizeMm?.let { appendLine("Sensor: %.2f x %.2f mm".format(it.width, it.height)) }
        appendLine("OIS: ${if (oisSupported) "yes" else "no"}  EIS: ${if (eisSupported) "yes" else "no"}")
        appendLine(
            "Video modes: ${videoProfiles.joinToString {
                "${it.label} (${videoCodecs[it].orEmpty().joinToString { codec -> codec.label }})"
            }.ifEmpty { "none" }}"
        )
        appendLine("HDR: ${if (supportsHdr) "yes" else "no"}  Night: ${if (supportsNight) "yes" else "no"}")
        appendLine("Portrait extension: ${if (supportsPortrait) "yes" else "no"}")
        if (physicalIds.isNotEmpty()) appendLine("Logical camera of: ${physicalIds.joinToString()}")
    }

    companion object {
        fun levelName(l: Int) = when (l) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }

        fun fmtShutter(ns: Long): String {
            val s = ns / 1e9
            if (s >= 0.5) {
                val r = (s * 10).roundToInt() / 10.0
                return if (r == floor(r)) "${r.toInt()}s" else "${r}s"
            }
            return "1/${(1.0 / s).roundToInt()}"
        }
    }
}

class HardwareAnalyzer(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val videoEncoders by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
    }

    /** Reading characteristics does not require the CAMERA permission. */
    fun analyze(): List<CameraCaps> = manager.cameraIdList.mapNotNull { id ->
        try {
            read(id)
        } catch (e: Exception) {
            null
        }
    }

    private fun read(id: String): CameraCaps? {
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()

        val jpeg = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height } ?: return null
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)?.toList()
            ?.distinctBy { it.width to it.height }
            ?.sortedByDescending { it.width.toLong() * it.height }
            ?: listOf(jpeg)
        val videoSizes = map.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val candidateVideoProfiles = videoSizes.flatMap { size ->
            val minFrameDuration = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
            val maxFpsForSize = if (minFrameDuration > 0L) (1_000_000_000L / minFrameDuration).toInt() else Int.MAX_VALUE
            val standardFps = listOf(24, 25, 30, 50, 60, 90, 120, 240)
            fpsRanges.sortedBy { it.upper - it.lower }.flatMap { range ->
                standardFps.filter { it in range.lower..range.upper && it <= maxFpsForSize }
                    .map { fps -> VideoProfile(size, fps, range) }
            }
        }.distinctBy { Triple(it.size.width, it.size.height, it.fps) }
            .sortedWith(compareByDescending<VideoProfile> { it.pixels }.thenByDescending { it.fps })
        val sceneModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.toList().orEmpty()
        val extensionCharacteristics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                manager.getCameraExtensionCharacteristics(id)
            } catch (e: Exception) {
                Log.w("HardwareAnalyzer", "Camera extensions unavailable for $id", e)
                null
            }
        } else null
        val extensionModes = extensionCharacteristics?.supportedExtensions?.toList().orEmpty()
        val extensionPhotoSizes = extensionCharacteristics?.let { extension ->
            extensionModes.associateWith { mode ->
                try {
                    extension.getExtensionSupportedSizes(mode, ImageFormat.JPEG)?.toList().orEmpty()
                } catch (e: Exception) {
                    Log.w("HardwareAnalyzer", "Could not read extension output sizes for $id", e)
                    emptyList()
                }
            }
        }.orEmpty()
        val extensionPreviewSizes = extensionCharacteristics?.let { extension ->
            extensionModes.associateWith { mode ->
                try {
                    extension.getExtensionSupportedSizes(mode, SurfaceTexture::class.java).orEmpty()
                } catch (e: Exception) {
                    Log.w("HardwareAnalyzer", "Could not read extension preview sizes for $id", e)
                    emptyList()
                }
            }
        }.orEmpty()
        val extensionZoomModes = extensionCharacteristics?.let { extension ->
            extensionModes.filter { mode ->
                try {
                    extension.getAvailableCaptureRequestKeys(mode).contains(CaptureRequest.SCALER_CROP_REGION)
                } catch (e: Exception) {
                    Log.w("HardwareAnalyzer", "Could not read extension controls for $id", e)
                    false
                }
            }.toSet()
        }.orEmpty()
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        val hasRaw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps && rawSize != null
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList().orEmpty()
        val hasColorTransform = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null ||
            c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2) != null
        val manualWhiteBalance =
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps &&
                CameraMetadata.CONTROL_AWB_MODE_OFF in awbModes &&
                hasColorTransform
        val toneCurvePoints = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
        val toneModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty()
        val manualToneCurve =
            CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in toneModes &&
            toneCurvePoints >= 2
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        val aeLock = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val afLock = CameraMetadata.CONTROL_AF_MODE_AUTO in afModes

        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        val candidateVideoCodecs = candidateVideoProfiles.associateWith { profile ->
            VideoCodec.values().filter { codec ->
                supportsVideoCodec(codec, profile)
            }
        }
        val videoProfiles = candidateVideoProfiles.filter { !candidateVideoCodecs[it].isNullOrEmpty() }
        val videoCodecs = videoProfiles.associateWith { candidateVideoCodecs.getValue(it) }
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, jpeg.width, jpeg.height)

        return CameraCaps(
            id = id,
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
            sensorSizeMm = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
            pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            activeArray = active,
            maxJpeg = jpeg,
            rawSize = if (hasRaw) rawSize else null,
            hasRaw = hasRaw,
            hasManualSensor = manual,
            hasManualWhiteBalance = manualWhiteBalance,
            hasManualToneCurve = manualToneCurve,
            toneCurvePoints = toneCurvePoints,
            hasAeLock = aeLock,
            hasAfLock = afLock,
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            evRange = ev,
            evStep = evStep,
            awbModes = awbModes,
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            oisSupported = ois,
            eisSupported = eis,
            maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            maxAfRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            maxAeRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
            physicalIds = c.physicalCameraIds,
            previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList(),
            jpegSizes = jpegSizes,
            videoProfiles = videoProfiles,
            videoCodecs = videoCodecs,
            sceneModes = sceneModes,
            fpsRanges = fpsRanges,
            extensionModes = extensionModes,
            extensionPhotoSizes = extensionPhotoSizes,
            extensionPreviewSizes = extensionPreviewSizes,
            extensionZoomModes = extensionZoomModes
        )
    }

    private fun supportsVideoCodec(codec: VideoCodec, profile: VideoProfile): Boolean =
        try {
            videoEncoders.any { info ->
                codec.mimeType in info.supportedTypes && info.getCapabilitiesForType(codec.mimeType)
                    .videoCapabilities?.areSizeAndRateSupported(
                        profile.size.width,
                        profile.size.height,
                        profile.fps.toDouble()
                    ) == true
            }
        } catch (_: IllegalArgumentException) {
            false
        }
}
