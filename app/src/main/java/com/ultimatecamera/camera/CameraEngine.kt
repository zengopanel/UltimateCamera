package com.ultimatecamera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.TonemapCurve
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.media.MediaRecorder
import android.util.Size
import android.view.Surface
import com.ultimatecamera.hardware.CameraCaps
import com.ultimatecamera.hardware.VideoCodec
import com.ultimatecamera.hardware.VideoProfile
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.pow

enum class VideoStabilization { AUTO, OFF, EIS, OIS }

data class ManualSettings(
    val manualExposure: Boolean = false,
    val iso: Int = 100,
    val shutterNs: Long = 16_666_667L,
    val manualFocus: Boolean = false,
    val focusDiopters: Float = 0f,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val manualKelvin: Int? = null,
    val aeLocked: Boolean = false,
    val afLocked: Boolean = false,
    val evIndex: Int = 0,
    val raw: Boolean = false,
    val zoom: Float = 1f,
    val torch: Boolean = false,
    val flashMode: Int = 0,
    val hdr: Boolean = false,
    val night: Boolean = false,
    val stabilization: VideoStabilization = VideoStabilization.AUTO,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val videoBitrateFactor: Float = 1f,
    val audioSampleRate: Int = 48_000,
    val audioBitrate: Int = 192_000,
    val highlights: Float = 0f,
    val shadows: Float = 0f
)

/** Camera2 engine: preview, still capture (JPEG + optional DNG), zoom, tap-to-focus, manual controls. */
class CameraEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onMessage(msg: String)
        fun onSaved(uri: Uri, raw: Boolean)
        fun onRecordingChanged(recording: Boolean)
        fun onVideoSaved(uri: Uri)
        fun onCameraModeUnavailable()
        fun onAudioLevel(amplitude: Int) {}
        fun onCaptureReadout(iso: Int, exposureNs: Long, focusDiopters: Float) {}
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var extensionSession: CameraExtensionSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var caps: CameraCaps? = null
    private var chars: CameraCharacteristics? = null
    private var selectedPhotoSize: Size? = null
    private var selectedVideoProfile: VideoProfile? = null
    private var videoRecorder: MediaRecorder? = null
    private var recorderDescriptor: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    @Volatile private var recording = false
    @Volatile private var recordingStarting = false
    private var videoMode = false
    private var extensionMode: Int? = null
    private var extensionRequestKeys: Set<CaptureRequest.Key<*>> = emptySet()
    private val cameraExecutor = Executor { command -> handler?.post(command) }

    val isRecording: Boolean get() = recording || recordingStarting

    @Volatile
    var settings = ManualSettings()

    @Volatile
    var displayRotationDegrees = 0

    /** Last values the auto-exposure chose; used to seed manual mode so it doesn't jump. */
    @Volatile
    var lastIso = 0
        private set

    @Volatile
    var lastExposureNs = 0L
        private set
    @Volatile
    var lastFocusDiopters = 0f
        private set

    @Volatile
    var audioLevel = 0
        private set

    private val audioMeter = object : Runnable {
        override fun run() {
            if (!recording) return
            audioLevel = try { videoRecorder?.maxAmplitude ?: 0 } catch (_: IllegalStateException) { 0 }
            listener.onAudioLevel(audioLevel)
            handler?.postDelayed(this, 100)
        }
    }

    @Volatile
    private var regions: Array<MeteringRectangle>? = null
    private val clearRegions = Runnable {
        regions = null
        updatePreview()
    }

    private var pendingRaw: Image? = null
    private var pendingRawResult: TotalCaptureResult? = null
    private var pendingRawOrientation = 0

    @SuppressLint("MissingPermission")
    fun open(
        c: CameraCaps,
        texture: SurfaceTexture,
        previewSize: Size,
        photoSize: Size? = null,
        videoProfile: VideoProfile? = null,
        extensionMode: Int? = null
    ) {
        close()
        caps = c
        this.extensionMode = extensionMode?.takeIf {
            it in c.extensionModes &&
                c.extensionPhotoSizes[it].orEmpty().isNotEmpty() &&
                c.extensionPreviewSizes[it].orEmpty().isNotEmpty()
        }
        val availablePhotoSizes = this.extensionMode?.let { c.extensionPhotoSizes[it] }.orEmpty()
        selectedPhotoSize = if (this.extensionMode != null) {
            photoSize?.takeIf { it in availablePhotoSizes }
                ?: availablePhotoSizes.maxByOrNull { it.width.toLong() * it.height }
        } else photoSize?.takeIf { it in c.jpegSizes } ?: c.maxJpeg
        selectedVideoProfile = videoProfile?.takeIf { it in c.videoProfiles } ?: c.defaultVideoProfile()
        selectedVideoProfile?.let { profile ->
            if (settings.videoCodec !in c.videoCodecs[profile].orEmpty()) {
                settings = settings.copy(videoCodec = c.videoCodecs[profile].orEmpty().firstOrNull() ?: VideoCodec.H264)
            }
        }
        this.extensionRequestKeys = this.extensionMode?.let { readExtensionRequestKeys(c.id, it) }.orEmpty()
        regions = null
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

    fun setExtensionMode(mode: Int?) {
        val c = caps
        if (mode != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                c == null || mode !in c.extensionModes ||
                c.extensionPhotoSizes[mode].isNullOrEmpty() ||
                c.extensionPreviewSizes[mode].isNullOrEmpty())
        ) {
            listener.onMessage("This camera mode is not available")
            listener.onCameraModeUnavailable()
            return
        }
        if (mode == extensionMode) return
        extensionMode = mode
        extensionRequestKeys = mode?.let { selected -> c?.let { readExtensionRequestKeys(it.id, selected) } }.orEmpty()
        handler?.post {
            if (device != null && !recording && !recordingStarting) {
                session?.close()
                session = null
                extensionSession?.close()
                extensionSession = null
                jpegReader?.close()
                jpegReader = null
                rawReader?.close()
                rawReader = null
                createSession()
            }
        }
    }

    fun close() {
        handler?.removeCallbacks(clearRegions)
        if (recording || recordingStarting) stopRecordingInternal(restartPreview = false)
        try { session?.close() } catch (_: Exception) {}
        try { extensionSession?.close() } catch (_: Exception) {}
        session = null
        extensionSession = null
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
        videoMode = false
        extensionMode = null
        extensionRequestKeys = emptySet()
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            createSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            if (recording || recordingStarting) stopRecordingInternal(restartPreview = false)
            camera.close(); device = null
            listener.onMessage("Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            if (recording || recordingStarting) stopRecordingInternal(restartPreview = false)
            camera.close(); device = null
            listener.onMessage("Camera error $error")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val d = device ?: return
        val c = caps ?: return
        val preview = previewSurface ?: return
        if (recording) return
        try {
            jpegReader?.close()
            rawReader?.close()
            val extensionSizes = extensionMode?.let { c.extensionPhotoSizes[it] }.orEmpty()
            val photoSize = if (extensionMode != null) {
                selectedPhotoSize?.takeIf { it in extensionSizes }
                    ?: extensionSizes.maxByOrNull { it.width.toLong() * it.height }
                    ?: c.maxJpeg
            } else selectedPhotoSize?.takeIf { it in c.jpegSizes } ?: c.maxJpeg
            val jr = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 3)
            jr.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { saveJpeg(it) } }, handler)
            jpegReader = jr

            val rs = c.rawSize
            val rr = if (extensionMode == null && c.hasRaw && rs != null) {
                ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 3).also {
                    it.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { img -> onRawImage(img) } }, handler)
                }
            } else null
            rawReader = rr

            if (extensionMode != null) {
                val mode = extensionMode!!
                val outputConfigs = listOf(OutputConfiguration(preview), OutputConfiguration(jr.surface))
                val configuration = ExtensionSessionConfiguration(
                    mode,
                    outputConfigs,
                    cameraExecutor,
                    object : CameraExtensionSession.StateCallback() {
                        override fun onConfigured(s: CameraExtensionSession) {
                            if (device == null) {
                                s.close()
                                return
                            }
                            extensionSession = s
                            updatePreview()
                        }

                        override fun onConfigureFailed(s: CameraExtensionSession) {
                            s.close()
                            extensionMode = null
                            listener.onMessage("Camera mode failed to start; using standard capture")
                            listener.onCameraModeUnavailable()
                            createSession()
                        }
                    }
                )
                d.createExtensionSession(configuration)
                return
            }

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
            if (extensionMode != null) {
                extensionMode = null
                listener.onMessage("Camera mode failed to start; using standard capture")
                listener.onCameraModeUnavailable()
                createSession()
            } else listener.onMessage("Session error: ${e.message}")
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDiopters = it }
            listener.onCaptureReadout(lastIso, lastExposureNs, lastFocusDiopters)
        }
    }

    /** Re-apply current settings to the live preview. */
    fun updatePreview() {
        val d = device ?: return
        val p = previewSurface ?: return
        val extension = extensionSession
        val s = session
        if (extension == null && s == null) return
        try {
            val recordingSurface = if (recording) videoRecorder?.surface else null
            val b = d.createCaptureRequest(
                if (recordingSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            )
            b.addTarget(p)
            recordingSurface?.let { b.addTarget(it) }
            if (extension != null) {
                applyExtensionSettings(b)
                extension.setRepeatingRequest(
                    b.build(),
                    cameraExecutor,
                    object : CameraExtensionSession.ExtensionCaptureCallback() {}
                )
            } else {
                applySettings(b, preview = true, forVideo = videoMode)
                s!!.setRepeatingRequest(b.build(), previewCallback, handler)
            }
        } catch (e: CameraAccessException) {
            listener.onMessage("Preview error: ${e.message}")
        } catch (e: IllegalStateException) {
            // session closed meanwhile
        }
    }

    fun setZoom(z: Float) {
        settings = settings.copy(zoom = z)
        updatePreview()
    }

    fun setVideoMode(enabled: Boolean) {
        videoMode = enabled
        updatePreview()
    }

    fun setPhotoSize(size: Size) {
        val c = caps ?: return
        val available = extensionMode?.let { c.extensionPhotoSizes[it] }.orEmpty().ifEmpty { c.jpegSizes }
        if (size !in available || size == selectedPhotoSize) return
        selectedPhotoSize = size
        handler?.post {
            if (device != null && !recording && !recordingStarting) {
                session?.close()
                session = null
                jpegReader?.close()
                jpegReader = null
                rawReader?.close()
                rawReader = null
                createSession()
            }
        }
    }

    fun setVideoProfile(profile: VideoProfile) {
        val c = caps ?: return
        if (profile !in c.videoProfiles || recording || recordingStarting) return
        selectedVideoProfile = profile
        val codecs = c.videoCodecs[profile].orEmpty()
        if (settings.videoCodec !in codecs) {
            settings = settings.copy(videoCodec = codecs.firstOrNull() ?: VideoCodec.H264)
        }
        val maxExposure = 1_000_000_000L / profile.fps
        if (settings.shutterNs > maxExposure) settings = settings.copy(shutterNs = maxExposure)
    }

    fun setVideoCodec(codec: VideoCodec) {
        val profile = selectedVideoProfile ?: return
        val c = caps ?: return
        if (recording || recordingStarting) return
        if (codec !in c.videoCodecs[profile].orEmpty()) {
            listener.onMessage("${codec.label} is unavailable for this video size and frame rate")
            return
        }
        settings = settings.copy(videoCodec = codec)
    }

    fun setVideoBitrateFactor(factor: Float) {
        settings = settings.copy(videoBitrateFactor = factor.coerceIn(0.65f, 1.5f))
    }

    fun triggerFocusLock() {
        val d = device ?: return
        val s = session ?: return
        val preview = previewSurface ?: return
        val c = caps ?: return
        if (!c.hasAfLock || settings.manualFocus) return
        try {
            val recordingSurface = if (recording) videoRecorder?.surface else null
            val builder = d.createCaptureRequest(
                if (recordingSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            )
            builder.addTarget(preview)
            recordingSurface?.let { builder.addTarget(it) }
            applySettings(builder, preview = true, forVideo = videoMode)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(builder.build(), null, handler)
            updatePreview()
        } catch (_: IllegalStateException) {
            listener.onMessage("Focus lock is unavailable")
        } catch (e: CameraAccessException) {
            listener.onMessage("Could not lock focus: ${e.message}")
        }
    }

    fun captureBurst(count: Int) {
        val total = count.coerceIn(1, 10)
        repeat(total) { index ->
            handler?.postDelayed({ capture() }, index * 180L)
        }
    }

    fun startRecording(withAudio: Boolean) {
        val h = handler ?: return listener.onMessage("Camera not ready")
        h.post { startRecordingInternal(withAudio) }
    }

    fun stopRecording() {
        val h = handler
        if (h == null) stopRecordingInternal()
        else h.post { stopRecordingInternal() }
    }

    @Suppress("DEPRECATION")
    private fun startRecordingInternal(withAudio: Boolean) {
        val camera = device ?: return listener.onMessage("Camera not ready")
        val c = caps ?: return listener.onMessage("Camera not ready")
        val preview = previewSurface ?: return listener.onMessage("Preview not ready")
        val profile = selectedVideoProfile ?: return listener.onMessage("No supported video profile")
        if (recording || recordingStarting) return
        val codec = settings.videoCodec
        if (codec !in c.videoCodecs[profile].orEmpty()) {
            listener.onMessage("${codec.label} is unavailable for this video size and frame rate")
            return
        }

        recordingStarting = true
        var uri: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "VID_${stamp()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/UltimateCamera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create video file")
            videoUri = uri
            recorderDescriptor = context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Could not open video file")

            val recorder = MediaRecorder()
            videoRecorder = recorder
            if (withAudio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(codec.recorderEncoder)
            if (withAudio) recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setVideoSize(profile.size.width, profile.size.height)
            recorder.setVideoFrameRate(profile.fps)
            val baseBitrate = profile.pixels * profile.fps * 0.12
            val bitrate = (baseBitrate * settings.videoBitrateFactor)
                .toLong().coerceIn(2_000_000L, 120_000_000L)
            recorder.setVideoEncodingBitRate(bitrate.toInt())
            if (withAudio) {
                recorder.setAudioEncodingBitRate(settings.audioBitrate.coerceIn(64_000, 256_000))
                recorder.setAudioSamplingRate(settings.audioSampleRate.coerceIn(32_000, 48_000))
            }
            recorder.setOrientationHint(jpegOrientation())
            recorder.setOutputFile(recorderDescriptor!!.fileDescriptor)
            recorder.prepare()

            val outputs = listOf(preview, recorder.surface)
            session?.close()
            session = null
            camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!recordingStarting || device !== camera) {
                        s.close()
                        return
                    }
                    try {
                        session = s
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(preview)
                            addTarget(recorder.surface)
                            applySettings(this, preview = false, forVideo = true)
                        }
                        s.setRepeatingRequest(request.build(), previewCallback, handler)
                        recorder.start()
                        recordingStarting = false
                        recording = true
                        audioLevel = 0
                        if (withAudio) handler?.post(audioMeter)
                        listener.onRecordingChanged(true)
                    } catch (e: Exception) {
                        s.close()
                        session = null
                        failRecordingStart(uri, e)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    failRecordingStart(uri, IllegalStateException("This video profile is not supported"))
                }
            }, handler)
        } catch (e: Exception) {
            failRecordingStart(uri, e)
        }
    }

    private fun failRecordingStart(uri: Uri?, error: Exception) {
        recordingStarting = false
        recording = false
        releaseRecorder()
        uri?.let { context.contentResolver.delete(it, null, null) }
        videoUri = null
        listener.onMessage("Could not start video: ${error.message ?: "camera configuration failed"}")
        createSession()
    }

    private fun stopRecordingInternal(restartPreview: Boolean = true) {
        if (!recording && !recordingStarting) return
        handler?.removeCallbacks(audioMeter)
        val wasRecording = recording
        recording = false
        recordingStarting = false
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}

        var saved = false
        if (wasRecording) {
            try {
                videoRecorder?.stop()
                saved = true
            } catch (_: RuntimeException) {
                listener.onMessage("Video was too short to save")
            }
        }
        session?.close()
        session = null
        releaseRecorder()

        val uri = videoUri
        videoUri = null
        if (saved && uri != null) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
            listener.onVideoSaved(uri)
        } else {
            uri?.let { context.contentResolver.delete(it, null, null) }
        }
        if (wasRecording) listener.onRecordingChanged(false)
        if (restartPreview) createSession()
    }

    private fun releaseRecorder() {
        try { videoRecorder?.reset() } catch (_: Exception) {}
        try { videoRecorder?.release() } catch (_: Exception) {}
        videoRecorder = null
        try { recorderDescriptor?.close() } catch (_: Exception) {}
        recorderDescriptor = null
    }

    private fun cropFor(zoom: Float): Rect {
        val c = caps ?: return Rect()
        val a = c.activeArray
        val z = zoom.coerceIn(1f, c.maxZoom.coerceAtLeast(1f))
        val w = (a.width() / z).toInt()
        val h = (a.height() / z).toInt()
        val l = a.left + (a.width() - w) / 2
        val t = a.top + (a.height() - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    private fun readExtensionRequestKeys(cameraId: String, mode: Int): Set<CaptureRequest.Key<*>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptySet()
        return try {
            manager.getCameraExtensionCharacteristics(cameraId)
                .getAvailableCaptureRequestKeys(mode)
                .toSet()
        } catch (e: CameraAccessException) {
            listener.onMessage("Could not read camera mode controls: ${e.message}")
            emptySet()
        }
    }

    private fun applyExtensionSettings(b: CaptureRequest.Builder) {
        if (CaptureRequest.SCALER_CROP_REGION in extensionRequestKeys) {
            b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(settings.zoom))
        }
    }

    private fun applySettings(b: CaptureRequest.Builder, preview: Boolean, forVideo: Boolean = false) {
        val c = caps ?: return
        val s = settings
        val sceneMode = when {
            s.night && c.supportsNightScene -> CameraMetadata.CONTROL_SCENE_MODE_NIGHT
            s.hdr && c.supportsHdrScene -> CameraMetadata.CONTROL_SCENE_MODE_HDR
            else -> null
        }
        b.set(
            CaptureRequest.CONTROL_MODE,
            if (sceneMode != null) CameraMetadata.CONTROL_MODE_USE_SCENE_MODE else CameraMetadata.CONTROL_MODE_AUTO
        )
        sceneMode?.let { b.set(CaptureRequest.CONTROL_SCENE_MODE, it) }
        b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(s.zoom))
        b.set(
            CaptureRequest.FLASH_MODE,
            if (forVideo && s.torch && c.hasFlash) CameraMetadata.FLASH_MODE_TORCH
            else CameraMetadata.FLASH_MODE_OFF
        )

        if (s.manualExposure && c.hasManualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            val iso = c.isoRange?.let { s.iso.coerceIn(it.lower, it.upper) } ?: s.iso
            var ns = c.exposureRangeNs?.let { s.shutterNs.coerceIn(it.lower, it.upper) } ?: s.shutterNs
            // Keep the live preview fluid: long exposures are only used for the actual capture.
            if (preview) ns = minOf(ns, 100_000_000L)
            if (forVideo) {
                selectedVideoProfile?.let { ns = minOf(ns, 1_000_000_000L / it.fps) }
            }
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
        } else {
            val aeMode = if (!preview && !forVideo && c.hasFlash) {
                when (s.flashMode) {
                    1 -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                    2 -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    else -> CameraMetadata.CONTROL_AE_MODE_ON
                }
            } else CameraMetadata.CONTROL_AE_MODE_ON
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.evIndex.coerceIn(c.evRange.lower, c.evRange.upper))
            if (c.hasAeLock) b.set(CaptureRequest.CONTROL_AE_LOCK, s.aeLocked)
            regions?.let { if (c.maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, it) }
        }

        if (s.manualFocus && c.hasManualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters.coerceIn(0f, c.minFocusDiopters))
        } else {
            b.set(
                CaptureRequest.CONTROL_AF_MODE,
                when {
                    s.afLocked && c.hasAfLock -> CameraMetadata.CONTROL_AF_MODE_AUTO
                    forVideo -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    else -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                }
            )
            regions?.let { if (c.maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, it) }
        }

        if (s.manualKelvin != null && c.hasManualWhiteBalance) {
            val (red, green, blue) = kelvinGains(s.manualKelvin)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(red, green, green, blue))
            val colorTransform = chars?.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
                ?: chars?.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
            if (colorTransform != null) b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, colorTransform)
        } else if (c.awbModes.contains(s.awbMode)) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, s.awbMode)
        }
        if (c.hasManualToneCurve && (s.highlights != 0f || s.shadows != 0f)) {
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, toneCurve(c.toneCurvePoints, s.shadows, s.highlights))
        }
        if (forVideo) {
            val profile = selectedVideoProfile
            val fpsRange = profile?.aeFpsRange
            fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            val stabilization = when (s.stabilization) {
                VideoStabilization.AUTO -> when {
                    c.eisSupported -> VideoStabilization.EIS
                    c.oisSupported -> VideoStabilization.OIS
                    else -> VideoStabilization.OFF
                }
                VideoStabilization.EIS -> if (c.eisSupported) VideoStabilization.EIS else VideoStabilization.OFF
                VideoStabilization.OIS -> if (c.oisSupported) VideoStabilization.OIS else VideoStabilization.OFF
                VideoStabilization.OFF -> VideoStabilization.OFF
            }
            b.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (stabilization == VideoStabilization.EIS) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
            b.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (stabilization == VideoStabilization.OIS) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }
    }

    private fun kelvinGains(kelvin: Int): Triple<Float, Float, Float> {
        val t = (kelvin.coerceIn(2500, 8000) / 100f)
        val red: Float
        val green: Float
        val blue: Float
        if (t <= 66f) {
            red = 255f
            green = (99.4708f * kotlin.math.ln(t) - 161.1196f).coerceIn(0f, 255f)
            blue = if (t <= 19f) 0f else (138.5177f * kotlin.math.ln(t - 10f) - 305.0448f).coerceIn(0f, 255f)
        } else {
            red = (329.6987f * (t - 60f).pow(-0.1332f)).coerceIn(0f, 255f)
            green = (288.1222f * (t - 60f).pow(-0.0755f)).coerceIn(0f, 255f)
            blue = 255f
        }
        val g = green.coerceAtLeast(1f)
        return Triple(
            (g / red.coerceAtLeast(1f)).coerceIn(0.5f, 8f),
            1f,
            (g / blue.coerceAtLeast(1f)).coerceIn(0.5f, 8f)
        )
    }

    private fun toneCurve(points: Int, shadows: Float, highlights: Float): TonemapCurve {
        val curve = FloatArray(points.coerceAtLeast(2) * 2)
        var previous = 0f
        val shadow = shadows.coerceIn(-1f, 1f) * 0.18f
        val highlight = highlights.coerceIn(-1f, 1f) * 0.18f
        for (i in 0 until curve.size / 2) {
            val x = i.toFloat() / (curve.size / 2 - 1)
            val toe = (1f - x).pow(3)
            val shoulder = x.pow(3)
            val y = (x + shadow * toe - highlight * shoulder).coerceIn(previous, 1f)
            curve[i * 2] = x
            curve[i * 2 + 1] = y
            previous = y
        }
        return TonemapCurve(curve, curve, curve)
    }

    /** Tap to focus / meter. u,v are 0..1 positions on the upright (display) preview. */
    fun focusAt(u: Float, v: Float) {
        val c = caps ?: return
        val d = device ?: return
        val s = session ?: return
        if (extensionSession != null) return
        val p = previewSurface ?: return
        if (c.maxAfRegions <= 0 && c.maxAeRegions <= 0) return
        if (settings.manualFocus && settings.manualExposure) return

        val uu = u.coerceIn(0f, 1f)
        val vv = v.coerceIn(0f, 1f)
        val (nx, ny) = when (c.sensorOrientation) {
            90 -> Pair(vv, 1f - uu)
            270 -> Pair(1f - vv, uu)
            180 -> Pair(1f - uu, 1f - vv)
            else -> Pair(uu, vv)
        }
        val crop = cropFor(settings.zoom)
        val x = crop.left + (nx * crop.width()).toInt()
        val y = crop.top + (ny * crop.height()).toInt()
        val half = (minOf(crop.width(), crop.height()) * 0.08f).toInt().coerceAtLeast(8)
        val a = c.activeArray
        val left = (x - half).coerceIn(a.left, a.right - 2 * half)
        val top = (y - half).coerceIn(a.top, a.bottom - 2 * half)
        regions = arrayOf(MeteringRectangle(left, top, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1))

        try {
            val recordingSurface = if (recording) videoRecorder?.surface else null
            val b = d.createCaptureRequest(
                if (recordingSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            )
            b.addTarget(p)
            recordingSurface?.let { b.addTarget(it) }
            applySettings(b, preview = true, forVideo = videoMode)
            if (!(settings.manualFocus && c.hasManualFocus)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }
            s.capture(b.build(), null, handler)
            updatePreview()
            handler?.removeCallbacks(clearRegions)
            handler?.postDelayed(clearRegions, 5000)
        } catch (e: Exception) {
            // camera closing; ignore
        }
    }

    fun capture() {
        if (recording || recordingStarting) return listener.onMessage("Stop recording before taking a photo")
        val d = device ?: return listener.onMessage("Camera not ready")
        val extension = extensionSession
        val s = session
        if (s == null && extension == null) return listener.onMessage("Camera not ready")
        val jr = jpegReader ?: return
        val orientation = jpegOrientation()
        val wantRaw = settings.raw && rawReader != null && extension == null
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jr.surface)
            if (wantRaw) b.addTarget(rawReader!!.surface)
            if (extension == null) applySettings(b, preview = false) else applyExtensionSettings(b)
            b.set(CaptureRequest.JPEG_ORIENTATION, orientation)
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            pendingRawOrientation = orientation
            val request = b.build()
            if (extension != null) {
                extension.capture(
                    request,
                    cameraExecutor,
                    object : CameraExtensionSession.ExtensionCaptureCallback() {}
                )
                return
            }
            s!!.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    if (wantRaw) {
                        synchronized(this@CameraEngine) { pendingRawResult = result }
                        tryWriteRaw()
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure
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
