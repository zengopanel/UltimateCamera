package com.ultimatecamera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import com.ultimatecamera.hardware.CameraCaps
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ManualSettings(
    val manualExposure: Boolean = false,
    val iso: Int = 100,
    val shutterNs: Long = 16_666_667L,
    val manualFocus: Boolean = false,
    val focusDiopters: Float = 0f,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val evIndex: Int = 0,
    val raw: Boolean = false
)

/** Camera2 engine: preview, still capture (JPEG + optional DNG), manual controls. */
class CameraEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onMessage(msg: String)
        fun onSaved(uri: Uri, raw: Boolean)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var caps: CameraCaps? = null
    private var chars: CameraCharacteristics? = null

    @Volatile
    var settings = ManualSettings()

    @Volatile
    var displayRotationDegrees = 0

    private var pendingRaw: Image? = null
    private var pendingRawResult: TotalCaptureResult? = null
    private var pendingRawOrientation = 0

    @SuppressLint("MissingPermission")
    fun open(c: CameraCaps, texture: SurfaceTexture, previewSize: Size) {
        close()
        caps = c
        try {
            chars = manager.getCameraCharacteristics(c.id)
            thread = HandlerThread("CameraEngine").also { it.start() }
            handler = Handler(thread!!.looper)
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(texture)
            manager.openCamera(c.id, stateCallback, handler)
        } catch (e: Exception) {
            listener.onMessage("Cannot open camera: ${e.message}")
        }
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        jpegReader?.close(); jpegReader = null
        synchronized(this) {
            pendingRaw?.close(); pendingRaw = null
            pendingRawResult = null
        }
        rawReader?.close(); rawReader = null
        previewSurface?.release(); previewSurface = null
        thread?.quitSafely()
        try { thread?.join() } catch (_: InterruptedException) {}
        thread = null; handler = null
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            createSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close(); device = null
            listener.onMessage("Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close(); device = null
            listener.onMessage("Camera error $error")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val d = device ?: return
        val c = caps ?: return
        val preview = previewSurface ?: return
        try {
            val jr = ImageReader.newInstance(c.maxJpeg.width, c.maxJpeg.height, ImageFormat.JPEG, 2)
            jr.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { saveJpeg(it) } }, handler)
            jpegReader = jr

            val rs = c.rawSize
            val rr = if (c.hasRaw && rs != null) {
                ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 3).also {
                    it.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { img -> onRawImage(img) } }, handler)
                }
            } else null
            rawReader = rr

            val surfaces = listOfNotNull(preview, jr.surface, rr?.surface)
            d.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device == null) return
                    session = s
                    updatePreview()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener.onMessage("Camera session configuration failed")
                }
            }, handler)
        } catch (e: Exception) {
            listener.onMessage("Session error: ${e.message}")
        }
    }

    /** Re-apply current settings to the live preview. */
    fun updatePreview() {
        val s = session ?: return
        val d = device ?: return
        val p = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(p)
            applySettings(b, preview = true)
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (e: CameraAccessException) {
            listener.onMessage("Preview error: ${e.message}")
        } catch (e: IllegalStateException) {
            // session closed meanwhile
        }
    }

    private fun applySettings(b: CaptureRequest.Builder, preview: Boolean) {
        val c = caps ?: return
        val s = settings
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        if (s.manualExposure && c.hasManualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            val iso = c.isoRange?.let { s.iso.coerceIn(it.lower, it.upper) } ?: s.iso
            var ns = c.exposureRangeNs?.let { s.shutterNs.coerceIn(it.lower, it.upper) } ?: s.shutterNs
            // Keep the live preview fluid: long exposures are only used for the actual capture.
            if (preview) ns = minOf(ns, 100_000_000L)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.evIndex.coerceIn(c.evRange.lower, c.evRange.upper))
        }

        if (s.manualFocus && c.hasManualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters.coerceIn(0f, c.minFocusDiopters))
        } else {
            b.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (preview) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }

        if (c.awbModes.contains(s.awbMode)) b.set(CaptureRequest.CONTROL_AWB_MODE, s.awbMode)
    }

    fun capture() {
        val d = device ?: return listener.onMessage("Camera not ready")
        val s = session ?: return listener.onMessage("Camera not ready")
        val jr = jpegReader ?: return
        val orientation = jpegOrientation()
        val wantRaw = settings.raw && rawReader != null
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jr.surface)
            if (wantRaw) b.addTarget(rawReader!!.surface)
            applySettings(b, preview = false)
            b.set(CaptureRequest.JPEG_ORIENTATION, orientation)
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            pendingRawOrientation = orientation
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    if (wantRaw) {
                        synchronized(this@CameraEngine) { pendingRawResult = result }
                        tryWriteRaw()
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession, request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure
                ) {
                    listener.onMessage("Capture failed")
                }
            }, handler)
        } catch (e: Exception) {
            listener.onMessage("Capture error: ${e.message}")
        }
    }

    // ---------- Saving ----------

    private fun saveJpeg(image: Image) {
        try {
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            val uri = insert("IMG_${stamp()}.jpg", "image/jpeg") { it.write(bytes) }
            if (uri != null) listener.onSaved(uri, false) else listener.onMessage("Could not save JPEG")
        } finally {
            image.close()
        }
    }

    private fun onRawImage(image: Image) {
        synchronized(this) {
            pendingRaw?.close()
            pendingRaw = image
        }
        tryWriteRaw()
    }

    private fun tryWriteRaw() {
        val img: Image
        val result: TotalCaptureResult
        synchronized(this) {
            img = pendingRaw ?: return
            result = pendingRawResult ?: return
            pendingRaw = null
            pendingRawResult = null
        }
        try {
            val ch = chars ?: return
            val dng = DngCreator(ch, result)
            dng.setOrientation(exifOrientation(pendingRawOrientation))
            val uri = insert("IMG_${stamp()}.dng", "image/x-adobe-dng") { out -> dng.writeImage(out, img) }
            dng.close()
            if (uri != null) listener.onSaved(uri, true) else listener.onMessage("Could not save DNG")
        } catch (e: Exception) {
            listener.onMessage("DNG error: ${e.message}")
        } finally {
            img.close()
        }
    }

    private fun insert(name: String, mime: String, write: (OutputStream) -> Unit): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UltimateCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IllegalStateException("no stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun jpegOrientation(): Int {
        val c = caps ?: return 0
        val sign = if (c.isFront) -1 else 1
        return (c.sensorOrientation - displayRotationDegrees * sign + 360) % 360
    }

    private fun exifOrientation(deg: Int) = when (deg) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}
