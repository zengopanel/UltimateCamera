package com.ultimatecamera.hardware

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import android.util.SizeF
import kotlin.math.abs

/** Everything the UI and engine need to know about one camera, read from the real hardware. */
data class CameraCaps(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val focalLengths: List<Float>,
    val sensorSizeMm: SizeF?,
    val pixelArray: Size?,
    val maxJpeg: Size,
    val rawSize: Size?,
    val hasRaw: Boolean,
    val hasManualSensor: Boolean,
    val isoRange: Range<Int>?,
    val exposureRangeNs: Range<Long>?,
    val minFocusDiopters: Float,
    val evRange: Range<Int>,
    val evStep: Float,
    val awbModes: List<Int>,
    val hardwareLevel: Int,
    val oisSupported: Boolean,
    val eisSupported: Boolean,
    val physicalIds: Set<String>,
    val previewSizes: List<Size>
) {
    val hasManualFocus get() = minFocusDiopters > 0f
    val isFront get() = facing == CameraCharacteristics.LENS_FACING_FRONT

    /** Largest preview with the same aspect ratio as the photo, capped near 1080p to stay smooth. */
    fun bestPreview(): Size {
        val target = maxJpeg.width.toDouble() / maxJpeg.height
        val capped = previewSizes.filter { it.width <= 1920 && it.height <= 1440 }
        val matching = capped.filter { abs(it.width.toDouble() / it.height - target) < 0.02 }
        return (matching.ifEmpty { capped.ifEmpty { previewSizes } }).maxByOrNull { it.width * it.height }
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
        appendLine("Focal length(s): ${focalLengths.joinToString { "%.2fmm".format(it) }}")
        sensorSizeMm?.let { appendLine("Sensor: %.2f x %.2f mm".format(it.width, it.height)) }
        appendLine("OIS: ${if (oisSupported) "yes" else "no"}  EIS: ${if (eisSupported) "yes" else "no"}")
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
            return if (s >= 0.5) "%.1fs".format(s) else "1/${(1.0 / s).toInt()}"
        }
    }
}

class HardwareAnalyzer(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

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
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        val hasRaw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps && rawSize != null
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps

        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

        return CameraCaps(
            id = id,
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
            sensorSizeMm = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
            pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            maxJpeg = jpeg,
            rawSize = if (hasRaw) rawSize else null,
            hasRaw = hasRaw,
            hasManualSensor = manual,
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            evRange = ev,
            evStep = evStep,
            awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList(),
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            oisSupported = ois,
            eisSupported = eis,
            physicalIds = c.physicalCameraIds,
            previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        )
    }
}
