/*
THESIS: Make the phone feel like a calibrated camera, not a row of filters.
OWN-WORLD: Obsidian controls, satin brass focus marks, quiet pearl type, and a single recording red.
STORY: The photographer sees the scene first, knows the active capture state, then reaches real hardware controls.
FIRST VIEWPORT: Full-bleed viewfinder; compact capability-aware controls above; zoom, mode rail, and tactile shutter below.
FORM: Assigned structural candidate 4 (seed d659979c): an immersive camera instrument with direct preview manipulation, a compact lower control deck, and no decorative modes.
*/
package com.ultimatecamera

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraExtensionCharacteristics
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.ultimatecamera.camera.CameraEngine
import com.ultimatecamera.camera.ManualSettings
import com.ultimatecamera.camera.VideoStabilization
import com.ultimatecamera.hardware.CameraCaps
import com.ultimatecamera.hardware.HardwareAnalyzer
import com.ultimatecamera.hardware.VideoCodec
import com.ultimatecamera.hardware.VideoProfile
import com.ultimatecamera.ui.AutoFitFrameLayout
import com.ultimatecamera.ui.FocusRingView
import com.ultimatecamera.ui.GridOverlay
import com.ultimatecamera.ui.IconType
import com.ultimatecamera.ui.IconView
import com.ultimatecamera.ui.ModeSelector
import com.ultimatecamera.ui.ParamChip
import com.ultimatecamera.ui.ProMonitorView
import com.ultimatecamera.ui.FocusPeakingView
import com.ultimatecamera.ui.RulerSlider
import com.ultimatecamera.ui.ShutterButton
import com.ultimatecamera.ui.Theme
import com.ultimatecamera.ui.ZoomBar
import com.ultimatecamera.ui.addPressFeedback
import com.ultimatecamera.ui.dp
import com.ultimatecamera.ui.dpi
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraEngine.Listener, TextureView.SurfaceTextureListener {

    private enum class Param(val label: String) {
        ISO("ISO"), SHUTTER("SHUTTER"), FOCUS("FOCUS"), WB("WB"), KELVIN("KELVIN"), EV("EV")
    }

    companion object {
        private const val REQUEST_CAMERA = 1
        private const val REQUEST_AUDIO = 2
        private const val FOCUS_STEPS = 40
        private val ISO_BASE = listOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000,
            1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800
        )
        private val SHUTTER_BASE_NS: List<Long> =
            listOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2).map { (1e9 / it).toLong() } +
                listOf(1, 2, 4, 8, 15, 30).map { it * 1_000_000_000L }
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var engine: CameraEngine
    private lateinit var host: AutoFitFrameLayout
    private lateinit var texture: TextureView
    private lateinit var grid: GridOverlay
    private lateinit var focusRing: FocusRingView
    private lateinit var flashOverlay: View
    private lateinit var zoomBar: ZoomBar
    private lateinit var modeSelector: ModeSelector
    private lateinit var proPanel: LinearLayout
    private lateinit var proSubmodeBar: LinearLayout
    private lateinit var proPhotoButton: TextView
    private lateinit var proVideoButton: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var ruler: RulerSlider
    private lateinit var proActions: LinearLayout
    private lateinit var monitor: ProMonitorView
    private lateinit var focusPeaking: FocusPeakingView
    private lateinit var msgPill: TextView
    private lateinit var flashBtn: IconView
    private lateinit var gridBtn: IconView
    private lateinit var hdrBtn: IconView
    private lateinit var stabilizationBtn: IconView
    private lateinit var stabilizationLabel: TextView
    private lateinit var timerBtn: IconView
    private lateinit var settingsBtn: IconView
    private lateinit var flipBtn: IconView
    private lateinit var shutter: ShutterButton
    private lateinit var thumbImg: ImageView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomStack: LinearLayout
    private lateinit var recordingBadge: TextView
    private lateinit var countdownOverlay: TextView
    private lateinit var scaleDetector: ScaleGestureDetector

    private var allCaps: List<CameraCaps> = emptyList()
    private var current: CameraCaps? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var proMode = false
    private var proVideoMode = false
    private var currentMode = "PHOTO"
    private var histogramEnabled = true
    private var peakingEnabled = false
    private var levelEnabled = true
    private var recordingReadoutAt = 0L
    private var storageReadoutAt = 0L
    private var hasLevelSensor = false
    private var activityResumed = false
    private var modeItems: List<String> = emptyList()
    private var lastUri: Uri? = null
    private var lastMimeType = "image/jpeg"
    private var timerSeconds = 0
    private var burstEnabled = false
    private var audioEnabled = true
    private var audioHighQuality = true
    private var pendingAudioVideo = false
    private var recordingStartedAt = 0L
    private var countdownValue = 0
    private var countdownAction: (() -> Unit)? = null
    private val photoSizes = HashMap<String, Size>()
    private val videoProfiles = HashMap<String, VideoProfile>()
    private val failedExtensions = HashSet<Pair<String, Int>>()
    private var zoomAnim: ValueAnimator? = null

    // pro panel state
    private val chips = LinkedHashMap<Param, ParamChip>()
    private var selected: Param? = null
    private var isoStops: List<Int> = emptyList()
    private var shutterStops: List<Long> = emptyList()
    private var awbItems: List<Pair<Int, String>> = emptyList()
    private var focusLabels: List<String> = emptyList()
    private var kelvinStops: List<Int> = emptyList()
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val levelSensor: Sensor? by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val roll = Math.toDegrees(atan2(event.values[0].toDouble(), event.values[1].toDouble())).toFloat()
            monitor.setRoll(roll)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val monitorTick = object : Runnable {
        override fun run() {
            if (!proMode || !texture.isAvailable || (!histogramEnabled && !(peakingEnabled && !proVideoMode))) return
            try {
                val bitmap = texture.getBitmap(160, 90)
                if (bitmap != null) {
                    val w = bitmap.width
                    val h = bitmap.height
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    bitmap.recycle()
                    val bins = IntArray(48)
                    for (color in pixels) {
                        val luma = (Color.red(color) * 0.2126f + Color.green(color) * 0.7152f +
                            Color.blue(color) * 0.0722f).toInt().coerceIn(0, 255)
                        bins[luma * bins.size / 256]++
                    }
                    if (histogramEnabled) monitor.setHistogram(bins)
                    if (peakingEnabled && !proVideoMode) focusPeaking.setPoints(findPeakingEdges(pixels, w, h))
                }
            } catch (_: IllegalStateException) {
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onAudioLevel(amplitude: Int) {
        ui.post { monitor.setAudioLevel(amplitude) }
    }

    override fun onCaptureReadout(iso: Int, exposureNs: Long, focusDiopters: Float) {
        if (!proMode) return
        val now = System.currentTimeMillis()
        if (now - recordingReadoutAt < 400L) return
        recordingReadoutAt = now
        ui.post {
            val c = current ?: return@post
            val profile = videoProfiles[c.id] ?: c.defaultVideoProfile()
            val exposure = if (exposureNs > 0) CameraCaps.fmtShutter(exposureNs) else "AUTO"
            val isoText = if (iso > 0) "ISO $iso" else "ISO AUTO"
            val focusText = if (engine.settings.manualFocus) "MF" else "AF"
            monitor.setReadout(
                listOfNotNull(
                    profile?.takeIf { proVideoMode }?.let { "${it.size.width}×${it.size.height} ${it.frameRateLabel}" },
                    isoText,
                    exposure,
                    focusText
                ).joinToString("  ·  ")
            )
        }
    }

    // preview gesture state: 0 none, 1 tap, 2 exposure drag, 3 pinch
    private var gesture = 0
    private var downY = 0f
    private var startEv = 0

    private val hideMsg = Runnable { msgPill.animate().alpha(0f).setDuration(300).start() }
    private val recordingClock = object : Runnable {
        override fun run() {
            if (!engine.isRecording) return
            val seconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).toInt()
            recordingBadge.text = "●  REC   %02d:%02d".format(seconds / 60, seconds % 60)
            if (System.currentTimeMillis() - storageReadoutAt >= 10_000L) {
                storageReadoutAt = System.currentTimeMillis()
                monitor.setStorage(availableStorageLabel())
            }
            ui.postDelayed(this, 1000)
        }
    }
    private val countdownTick = object : Runnable {
        override fun run() {
            if (countdownValue <= 0) {
                countdownOverlay.animate().alpha(0f).setDuration(120).withEndAction {
                    countdownOverlay.visibility = View.GONE
                    countdownOverlay.alpha = 1f
                    countdownAction?.invoke()
                    countdownAction = null
                }.start()
                return
            }
            countdownOverlay.text = countdownValue.toString()
            countdownOverlay.scaleX = 0.72f
            countdownOverlay.scaleY = 0.72f
            countdownOverlay.alpha = 0f
            countdownOverlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
            countdownValue--
            ui.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allCaps = HardwareAnalyzer(this).analyze()
        engine = CameraEngine(this, this)
        current = allCaps.firstOrNull { !it.isFront } ?: allCaps.firstOrNull()
        buildUi()
        if (current == null) toast("No camera found")
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (proMode && levelEnabled && levelSensor != null) {
            sensorManager.registerListener(levelListener, levelSensor, SensorManager.SENSOR_DELAY_UI)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        if (texture.isAvailable) texture.surfaceTexture?.let { startCamera(it) }
    }

    override fun onPause() {
        activityResumed = false
        zoomAnim?.cancel()
        ui.removeCallbacks(countdownTick)
        ui.removeCallbacks(monitorTick)
        countdownAction = null
        countdownOverlay.visibility = View.GONE
        ui.removeCallbacks(recordingClock)
        sensorManager.unregisterListener(levelListener)
        engine.close()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        when (code) {
            REQUEST_CAMERA -> if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (texture.isAvailable) texture.surfaceTexture?.let { startCamera(it) }
            } else toast("Camera permission is required")
            REQUEST_AUDIO -> if (pendingAudioVideo) {
                pendingAudioVideo = false
                val granted = res.firstOrNull() == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    audioEnabled = false
                    toast("Microphone unavailable — recording without audio")
                }
                engine.startRecording(granted && audioEnabled)
            }
        }
    }

    private fun startCamera(st: SurfaceTexture) {
        val c = current ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        surfaceTexture = st
        val extensionMode = extensionModeFor(currentMode, c)
        val availablePhotos = if (extensionMode != null) {
            c.extensionPhotoSizes[extensionMode].orEmpty()
        } else c.jpegSizes
        val photoSize = photoSizes[c.id]?.takeIf { it in availablePhotos }
            ?: availablePhotos.maxByOrNull { it.width.toLong() * it.height }
            ?: c.maxJpeg
        val profile = videoProfiles[c.id]?.takeIf { it in c.videoProfiles } ?: c.defaultVideoProfile()
        if (profile != null) videoProfiles[c.id] = profile
        val previewTarget = if (isVideoCaptureMode()) profile?.size ?: photoSize else photoSize
        val size: Size = c.bestPreview(previewTarget, extensionMode)
        host.setAspectRatio(size.height, size.width) // portrait
        engine.displayRotationDegrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        configureUiForCamera(c)
        engine.open(c, st, size, photoSize, profile, extensionMode)
        engine.setVideoMode(isVideoCaptureMode())
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startCamera(st)
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onMessage(msg: String) {
        ui.post { toast(msg) }
    }

    override fun onSaved(uri: Uri, raw: Boolean) {
        ui.post {
            toast(if (raw) "RAW saved" else "Saved")
            if (!raw) {
                lastUri = uri
                lastMimeType = "image/jpeg"
                loadThumb(uri)
            }
        }
    }

    override fun onRecordingChanged(recording: Boolean) {
        ui.post {
            shutter.recording = recording
            recordingBadge.visibility = if (recording) View.VISIBLE else View.GONE
            if (recording) {
                recordingStartedAt = System.currentTimeMillis()
                recordingBadge.alpha = 0f
                recordingBadge.animate().alpha(1f).setDuration(180).start()
                ui.removeCallbacks(recordingClock)
                ui.post(recordingClock)
                recordingReadoutAt = 0L
                storageReadoutAt = 0L
                monitor.setStorage(availableStorageLabel())
                updateControlsForMode()
            } else {
                ui.removeCallbacks(recordingClock)
                monitor.setAudioLevel(0)
                updateControlsForMode()
            }
        }
    }

    override fun onVideoSaved(uri: Uri) {
        ui.post {
            lastUri = uri
            lastMimeType = "video/mp4"
            loadThumb(uri)
            toast("Video saved")
        }
    }

    override fun onCameraModeUnavailable() {
        ui.post {
            val c = current
            val failedMode = c?.let { extensionModeFor(currentMode, it) }
            if (c != null && failedMode != null) failedExtensions.add(c.id to failedMode)
            when (currentMode) {
                "PORTRAIT" -> {
                    currentMode = "PHOTO"
                    proMode = false
                    engine.setExtensionMode(null)
                    engine.settings = engine.settings.copy(night = false, hdr = false)
                    modeSelector.select(modeItems.indexOf("PHOTO"), true)
                    setProVisible(false)
                    updateControlsForMode()
                }
                "NIGHT" -> {
                    val sceneFallback = c?.supportsNightScene == true
                    engine.setExtensionMode(null)
                    if (sceneFallback) {
                        engine.settings = engine.settings.copy(night = true, hdr = false)
                    } else {
                        currentMode = "PHOTO"
                        engine.settings = engine.settings.copy(night = false, hdr = false)
                        modeSelector.select(modeItems.indexOf("PHOTO"), true)
                        toast("Night mode is unavailable on this camera")
                    }
                    updateControlsForMode()
                }
                "PHOTO" -> {
                    engine.setExtensionMode(null)
                    val sceneFallback = engine.settings.hdr && c?.supportsHdrScene == true
                    engine.settings = engine.settings.copy(hdr = sceneFallback)
                    updateControlsForMode()
                    toast(if (sceneFallback) "Using the camera's HDR scene mode" else "HDR extension unavailable")
                }
            }
            if (failedMode != null) surfaceTexture?.let { startCamera(it) }
        }
    }

    // ------------------------------------------------------------------ UI construction

    private fun circle(fill: Int, strokeDp: Float = 0f, stroke: Int = Color.WHITE) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (strokeDp > 0f) setStroke(dp(strokeDp).toInt().coerceAtLeast(1), stroke)
    }

    private fun ovalOutline() = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // ---- preview + overlays
        host = AutoFitFrameLayout(this)
        texture = TextureView(this)
        texture.surfaceTextureListener = this
        host.addView(texture, FrameLayout.LayoutParams(MATCH, MATCH))
        grid = GridOverlay(this)
        grid.alpha = 0f
        grid.visibility = View.GONE
        host.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        focusRing = FocusRingView(this)
        host.addView(focusRing, FrameLayout.LayoutParams(MATCH, MATCH))
        monitor = ProMonitorView(this)
        monitor.showLevel = false
        host.addView(monitor, FrameLayout.LayoutParams(MATCH, MATCH))
        focusPeaking = FocusPeakingView(this).apply { visibility = View.GONE }
        host.addView(focusPeaking, FrameLayout.LayoutParams(MATCH, MATCH))
        flashOverlay = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
        }
        host.addView(flashOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(host, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setupPreviewTouch()

        // ---- top bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(18), dpi(12), dpi(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC090909.toInt(), 0x00000000)
            )
        }
        fun topControl(view: IconView) {
            view.background = circle(0x44141414)
            topBar.addView(
                view,
                LinearLayout.LayoutParams(dpi(48), dpi(48)).apply { marginEnd = dpi(5) }
            )
        }
        flashBtn = IconView(this, IconType.FLASH).apply {
            contentDescription = "Flash"
            setOnClickListener { toggleTorch() }
        }
        hdrBtn = IconView(this, IconType.HDR).apply {
            contentDescription = "HDR"
            setOnClickListener { toggleHdr() }
        }
        stabilizationBtn = IconView(this, IconType.STABILIZATION).apply {
            contentDescription = "Video stabilization"
            background = circle(0x44141414)
            setOnClickListener { cycleStabilization() }
        }
        stabilizationLabel = TextView(this).apply {
            text = "AUTO"
            textSize = 8f
            typeface = Theme.MEDIUM
            gravity = Gravity.CENTER
            setTextColor(Theme.TEXT_DIM)
        }
        val stabilizationControl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(stabilizationBtn, LinearLayout.LayoutParams(dpi(42), dpi(38)))
            addView(stabilizationLabel, LinearLayout.LayoutParams(MATCH, dpi(10)))
        }
        timerBtn = IconView(this, IconType.TIMER).apply {
            contentDescription = "Capture timer"
            setOnClickListener { cycleTimer() }
        }
        gridBtn = IconView(this, IconType.GRID).apply {
            contentDescription = "Composition grid"
            setOnClickListener { toggleGrid() }
        }
        settingsBtn = IconView(this, IconType.SETTINGS).apply {
            contentDescription = "Camera settings"
            setOnClickListener { showSettings() }
        }
        topControl(flashBtn)
        topControl(hdrBtn)
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(stabilizationControl, LinearLayout.LayoutParams(dpi(48), dpi(48)).apply {
            marginEnd = dpi(5)
        })
        topControl(timerBtn)
        topControl(gridBtn)
        topControl(settingsBtn)
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        recordingBadge = TextView(this).apply {
            text = "●  REC   00:00"
            textSize = 13f
            typeface = Theme.MEDIUM
            letterSpacing = 0.05f
            setTextColor(Theme.RECORD)
            gravity = Gravity.CENTER
            setPadding(dpi(14), dpi(8), dpi(14), dpi(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(0xCC171313.toInt())
            }
            visibility = View.GONE
        }
        root.addView(
            recordingBadge,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dpi(96)
            }
        )

        countdownOverlay = TextView(this).apply {
            textSize = 76f
            typeface = Theme.LIGHT
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setShadowLayer(dp(12f), 0f, dp(2f), Color.BLACK)
        }
        root.addView(countdownOverlay, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // ---- floating message pill
        msgPill = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Theme.MEDIUM
            setPadding(dpi(16), dpi(8), dpi(16), dpi(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(0xCC1C1C22.toInt())
            }
            alpha = 0f
        }
        root.addView(
            msgPill,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(92) }
        )

        // ---- bottom stack
        bottomStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpi(40), 0, dpi(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xCC000000.toInt(), 0xF2000000.toInt())
            )
        }
        zoomBar = ZoomBar(this).apply { onPick = { animateZoomTo(it) } }
        bottomStack.addView(zoomBar, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dpi(10) })

        proPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dpi(8), 0, dpi(8), 0)
        }
        proSubmodeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpi(4), dpi(2), dpi(4), dpi(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f)
                setColor(0xAA151514.toInt())
            }
        }
        fun proSubmodeButton(title: String, onClick: () -> Unit) = TextView(this).apply {
            text = title
            textSize = 11f
            typeface = Theme.MEDIUM
            gravity = Gravity.CENTER
            setPadding(dpi(18), 0, dpi(18), 0)
            minHeight = dpi(36)
            setOnClickListener { onClick() }
            addPressFeedback(0.96f)
        }
        proPhotoButton = proSubmodeButton("PRO PHOTO") { setProVideoMode(false) }
        proVideoButton = proSubmodeButton("PRO VIDEO") { setProVideoMode(true) }
        proSubmodeBar.addView(proPhotoButton, LinearLayout.LayoutParams(0, WRAP, 1f))
        proSubmodeBar.addView(proVideoButton, LinearLayout.LayoutParams(0, WRAP, 1f))
        proPanel.addView(proSubmodeBar, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            bottomMargin = dpi(4)
        })
        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        ruler = RulerSlider(this)
        proPanel.addView(chipsRow, LinearLayout.LayoutParams(MATCH, WRAP))
        proPanel.addView(ruler, LinearLayout.LayoutParams(MATCH, dpi(64)))
        proActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dpi(2), 0, dpi(4))
        }
        proPanel.addView(proActions, LinearLayout.LayoutParams(MATCH, WRAP))
        bottomStack.addView(proPanel, LinearLayout.LayoutParams(MATCH, WRAP))

        modeSelector = ModeSelector(this).apply { onSelected = { onModeSelected(it) } }
        bottomStack.addView(modeSelector, LinearLayout.LayoutParams(WRAP, WRAP))

        // ---- shutter row
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val thumbHolder = FrameLayout(this).apply {
            background = circle(0x33FFFFFF, 1.5f)
            setOnClickListener { openLast() }
            addPressFeedback(0.9f)
        }
        thumbHolder.addView(IconView(this, IconType.GALLERY), FrameLayout.LayoutParams(MATCH, MATCH))
        thumbImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            outlineProvider = ovalOutline()
            clipToOutline = true
        }
        thumbHolder.addView(
            thumbImg,
            FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dpi(3), dpi(3), dpi(3), dpi(3)) }
        )
        shutter = ShutterButton(this).apply {
            contentDescription = "Take photo"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onShutterAction()
            }
            setOnLongClickListener {
                if (isVideoCaptureMode()) false else {
                    engine.captureBurst(5)
                    toast("5-frame burst")
                    true
                }
            }
        }
        flipBtn = IconView(this, IconType.FLIP).apply {
            background = circle(Theme.GLASS)
            visibility = if (allCaps.size > 1) View.VISIBLE else View.GONE
            setOnClickListener { flipCamera() }
        }
        fun slot(v: View, w: Int, h: Int) = FrameLayout(this).apply {
            addView(v, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        }
        row.addView(slot(thumbHolder, dpi(54), dpi(54)), LinearLayout.LayoutParams(0, dpi(100), 1f))
        row.addView(slot(shutter, dpi(82), dpi(82)), LinearLayout.LayoutParams(0, dpi(100), 1f))
        row.addView(slot(flipBtn, dpi(54), dpi(54)), LinearLayout.LayoutParams(0, dpi(100), 1f))
        bottomStack.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(bottomStack, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            topBar.setPadding(dpi(12), insets.systemWindowInsetTop + dpi(8), dpi(12), dpi(12))
            @Suppress("DEPRECATION")
            bottomStack.setPadding(0, dpi(40), 0, insets.systemWindowInsetBottom + dpi(14))
            insets
        }

        setContentView(root)
        @Suppress("DEPRECATION")
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun configureUiForCamera(c: CameraCaps) {
        engine.settings = engine.settings.copy(
            raw = engine.settings.raw && c.hasRaw && extensionModeFor(currentMode, c) == null,
            zoom = engine.settings.zoom.coerceIn(1f, c.maxZoom.coerceAtLeast(1f)),
            hdr = engine.settings.hdr && supportsHdr(c),
            night = engine.settings.night && supportsNight(c)
        )
        modeItems = buildList {
            add("PHOTO")
            if (c.videoProfiles.isNotEmpty()) add("VIDEO")
            if (supportsPortrait(c)) add("PORTRAIT")
            if (supportsNight(c)) add("NIGHT")
            if (hasProControls(c) || c.videoProfiles.isNotEmpty()) add("PRO")
        }
        if (currentMode !in modeItems) currentMode = "PHOTO"
        modeSelector.setItems(modeItems, modeItems.indexOf(currentMode))

        val stops = ArrayList<Float>()
        for (s in listOf(1f, 2f, 3f, 5f, 10f)) if (s <= c.maxZoom + 0.001f) stops.add(s)
        val top = c.maxZoom.toInt().toFloat()
        if (top >= stops.last() + 1f && stops.size < 6) stops.add(top)
        zoomBar.visibility = if (stops.size > 1 && canUseZoom(c)) View.VISIBLE else View.GONE
        zoomBar.setStops(stops)
        zoomBar.setZoom(engine.settings.zoom)

        hasLevelSensor = levelSensor != null
        buildProPanel(c)
        flipBtn.visibility = if (allCaps.size > 1) View.VISIBLE else View.GONE
        flipBtn.isEnabled = allCaps.size > 1
        flipBtn.alpha = 1f
        updateControlsForMode()
    }

    private fun hasProControls(c: CameraCaps): Boolean {
        val whiteBalanceModes = setOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT,
            CameraMetadata.CONTROL_AWB_MODE_SHADE
        )
        return (c.hasManualSensor && c.isoRange != null && c.exposureRangeNs != null) ||
            c.hasManualFocus ||
            c.hasManualWhiteBalance ||
            c.hasManualToneCurve ||
            c.awbModes.count { it in whiteBalanceModes } > 1 ||
            c.evRange.upper > c.evRange.lower ||
            c.hasRaw
    }

    private fun isVideoCaptureMode(): Boolean =
        currentMode == "VIDEO" || (currentMode == "PRO" && proVideoMode)

    private fun extensionModeFor(mode: String, c: CameraCaps): Int? {
        val extension = when (mode) {
            "PHOTO" -> if (engine.settings.hdr && c.supportsHdrExtension) {
                CameraExtensionCharacteristics.EXTENSION_HDR
            } else null
            "PORTRAIT" -> if (supportsPortrait(c)) CameraExtensionCharacteristics.EXTENSION_BOKEH else null
            "NIGHT" -> if (c.supportsNightExtension &&
                (c.id to CameraExtensionCharacteristics.EXTENSION_NIGHT) !in failedExtensions
            ) {
                CameraExtensionCharacteristics.EXTENSION_NIGHT
            } else null
            else -> null
        }
        return extension?.takeIf { (c.id to it) !in failedExtensions }
    }

    private fun supportsHdr(c: CameraCaps): Boolean =
        c.supportsHdrScene || (c.supportsHdrExtension &&
            (c.id to CameraExtensionCharacteristics.EXTENSION_HDR) !in failedExtensions)

    private fun supportsNight(c: CameraCaps): Boolean =
        c.supportsNightScene || (c.supportsNightExtension &&
            (c.id to CameraExtensionCharacteristics.EXTENSION_NIGHT) !in failedExtensions)

    private fun supportsPortrait(c: CameraCaps): Boolean =
        c.supportsPortrait &&
            (c.id to CameraExtensionCharacteristics.EXTENSION_BOKEH) !in failedExtensions

    private fun updateControlsForMode() {
        val c = current
        val isVideo = isVideoCaptureMode()
        val extension = c?.let { extensionModeFor(currentMode, it) }
        zoomBar.visibility = if (c != null && c.maxZoom > 1f && canUseZoom(c)) View.VISIBLE else View.GONE
        val flashWorksInMode = currentMode != "PRO" || !engine.settings.manualExposure
        flashBtn.visibility = if (c?.hasFlash == true && extension == null && flashWorksInMode) View.VISIBLE else View.GONE
        hdrBtn.visibility = if (c?.let { supportsHdr(it) } == true && currentMode == "PHOTO") View.VISIBLE else View.GONE
        stabilizationBtn.visibility = if (isVideo) View.VISIBLE else View.GONE
        stabilizationBtn.isEnabled = c?.supportsVideoStabilization == true
        stabilizationBtn.alpha = if (stabilizationBtn.isEnabled) 1f else 0.38f
        val stabilization = c?.let { effectiveStabilization(it) } ?: VideoStabilization.OFF
        stabilizationBtn.active = isVideo && stabilization != VideoStabilization.OFF
        stabilizationLabel.text = when {
            !stabilizationBtn.isEnabled -> "N/A"
            stabilization == VideoStabilization.OFF -> "OFF"
            else -> stabilization.name
        }
        stabilizationLabel.setTextColor(
            if (stabilization != VideoStabilization.OFF && stabilizationBtn.isEnabled) Theme.ACCENT
            else Theme.TEXT_DIM
        )
        timerBtn.active = timerSeconds > 0
        gridBtn.active = grid.visibility == View.VISIBLE
        flashBtn.active = if (isVideo) engine.settings.torch else engine.settings.flashMode > 0
        hdrBtn.active = engine.settings.hdr
        shutter.videoMode = isVideo
        shutter.recording = engine.isRecording
        hdrBtn.contentDescription = if (engine.settings.hdr) "HDR on" else "HDR off"
        stabilizationBtn.contentDescription = when {
            stabilizationBtn.isEnabled && stabilization != VideoStabilization.OFF &&
                engine.settings.stabilization == VideoStabilization.AUTO ->
                "Automatic video stabilization; ${stabilization.name} active"
            stabilizationBtn.isEnabled && stabilization != VideoStabilization.OFF ->
                "Video stabilization ${stabilization.name} active"
            stabilizationBtn.isEnabled -> "Video stabilization off"
            else -> "Video stabilization unavailable"
        }
        timerBtn.contentDescription = if (timerSeconds == 0) "Timer off" else "$timerSeconds second timer"
        gridBtn.contentDescription = if (grid.visibility == View.VISIBLE) "Composition grid on" else "Composition grid off"
        flashBtn.contentDescription = when {
            isVideo && engine.settings.torch -> "Torch on"
            isVideo -> "Torch off"
            engine.settings.flashMode == 1 -> "Flash auto"
            engine.settings.flashMode == 2 -> "Flash on"
            else -> "Flash off"
        }
        settingsBtn.isEnabled = !engine.isRecording
        flipBtn.isEnabled = !engine.isRecording
        modeSelector.isEnabled = !engine.isRecording
        timerBtn.isEnabled = !engine.isRecording
        proPhotoButton.isEnabled = !engine.isRecording
        proVideoButton.isEnabled = !engine.isRecording && c?.videoProfiles?.isNotEmpty() == true
        proPhotoButton.alpha = if (proPhotoButton.isEnabled) 1f else 0.45f
        proVideoButton.alpha = if (proVideoButton.isEnabled) 1f else 0.45f
        monitor.showHistogram = proMode && histogramEnabled
        monitor.showLevel = proMode && levelEnabled && hasLevelSensor
        monitor.showAudio = proMode && proVideoMode && audioEnabled &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) && engine.isRecording
        if (activityResumed && proMode && levelEnabled && levelSensor != null) {
            sensorManager.registerListener(levelListener, levelSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            sensorManager.unregisterListener(levelListener)
        }
        focusPeaking.visibility = if (proMode && !proVideoMode && peakingEnabled) View.VISIBLE else View.GONE
        if (!proMode) monitor.setReadout("")
        ui.removeCallbacks(monitorTick)
        if (proMode && (histogramEnabled || (peakingEnabled && !proVideoMode))) ui.post(monitorTick)
        updateProSubmodeAppearance()
        updateProActions()
        shutter.contentDescription = if (isVideo) {
            if (engine.isRecording) "Stop video recording" else "Start video recording"
        } else "Take photo"
    }

    private fun effectiveStabilization(c: CameraCaps): VideoStabilization =
        when (engine.settings.stabilization) {
            VideoStabilization.AUTO -> when {
                c.eisSupported -> VideoStabilization.EIS
                c.oisSupported -> VideoStabilization.OIS
                else -> VideoStabilization.OFF
            }
            VideoStabilization.EIS -> if (c.eisSupported) VideoStabilization.EIS else VideoStabilization.OFF
            VideoStabilization.OIS -> if (c.oisSupported) VideoStabilization.OIS else VideoStabilization.OFF
            VideoStabilization.OFF -> VideoStabilization.OFF
        }

    private fun canUseZoom(c: CameraCaps?): Boolean {
        if (c == null) return false
        val extension = extensionModeFor(currentMode, c) ?: return true
        return extension in c.extensionZoomModes
    }

    // ------------------------------------------------------------------ preview gestures

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (!canUseZoom(current)) return false
                zoomAnim?.cancel()
                applyZoom(engine.settings.zoom * d.scaleFactor)
                return true
            }
        })
        host.setOnTouchListener { v, e ->
            scaleDetector.onTouchEvent(e)
            if (e.pointerCount > 1 || scaleDetector.isInProgress) {
                gesture = 3
                return@setOnTouchListener true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val c = current
                    val settings = engine.settings
                    if (c != null && extensionModeFor(currentMode, c) == null &&
                        !(settings.manualFocus && settings.manualExposure)
                    ) {
                        val canMeter = c.maxAfRegions > 0 || c.maxAeRegions > 0
                        val canEv = !settings.manualExposure && c.evRange.upper > c.evRange.lower
                        if (canMeter || canEv) {
                            gesture = 1
                            downY = e.y
                            startEv = settings.evIndex
                            focusRing.show(
                                e.x, e.y,
                                if (canEv) evFraction(c, settings.evIndex) else null,
                                evLabel(settings.evIndex)
                            )
                            if (canMeter) engine.focusAt(e.x / v.width, e.y / v.height)
                        } else gesture = 0
                    } else gesture = 0
                }
                MotionEvent.ACTION_MOVE -> if (gesture != 3 && gesture != 0) {
                    val dy = downY - e.y
                    if (gesture == 1 && abs(dy) > slop * 2) gesture = 2
                    if (gesture == 2) adjustEv(dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gesture = 0
            }
            true
        }
    }

    private fun evFraction(c: CameraCaps, idx: Int): Float =
        (idx - c.evRange.lower).toFloat() / (c.evRange.upper - c.evRange.lower).coerceAtLeast(1)

    private fun evLabel(idx: Int): String {
        if (idx == 0) return "0"
        val step = current?.evStep ?: 0f
        return "%+.1f".format(idx * step)
    }

    private fun adjustEv(dy: Float) {
        val c = current ?: return
        if (engine.settings.manualExposure) return
        val lo = c.evRange.lower
        val hi = c.evRange.upper
        if (hi <= lo) return
        val idx = (startEv + dy / (host.height * 0.4f) * (hi - lo)).roundToInt().coerceIn(lo, hi)
        if (idx != engine.settings.evIndex) {
            engine.settings = engine.settings.copy(evIndex = idx)
            engine.updatePreview()
            host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            chips[Param.EV]?.setValue(evLabel(idx))
        }
        focusRing.updateEv(evFraction(c, idx), evLabel(idx))
    }

    // ------------------------------------------------------------------ zoom

    private fun applyZoom(z: Float) {
        val c = current ?: return
        if (!canUseZoom(c)) return
        val v = z.coerceIn(1f, c.maxZoom.coerceAtLeast(1f))
        engine.setZoom(v)
        zoomBar.setZoom(v)
    }

    private fun animateZoomTo(target: Float) {
        val c = current ?: return
        val to = target.coerceIn(1f, c.maxZoom.coerceAtLeast(1f))
        zoomAnim?.cancel()
        zoomAnim = ValueAnimator.ofFloat(ln(engine.settings.zoom), ln(to)).apply {
            duration = 400
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { applyZoom(exp(it.animatedValue as Float)) }
            start()
        }
    }

    // ------------------------------------------------------------------ top-bar actions

    private fun toggleTorch() {
        val c = current ?: return
        if (!c.hasFlash) return
        if (isVideoCaptureMode()) {
            val on = !engine.settings.torch
            engine.settings = engine.settings.copy(torch = on)
            toast(if (on) "Torch on" else "Torch off")
        } else {
            val mode = (engine.settings.flashMode + 1) % 3
            engine.settings = engine.settings.copy(flashMode = mode)
            toast(when (mode) { 1 -> "Flash auto"; 2 -> "Flash on"; else -> "Flash off" })
        }
        engine.updatePreview()
        updateControlsForMode()
    }

    private fun toggleHdr() {
        val c = current ?: return
        if (!supportsHdr(c) || currentMode != "PHOTO") return
        val previousExtension = extensionModeFor(currentMode, c)
        val enabled = !engine.settings.hdr
        val hdrExtension = CameraExtensionCharacteristics.EXTENSION_HDR
        val extensionMode = if (enabled && c.supportsHdrExtension && (c.id to hdrExtension) !in failedExtensions) {
            hdrExtension
        } else null
        engine.settings = engine.settings.copy(
            hdr = enabled,
            night = false,
            raw = engine.settings.raw && extensionMode == null
        )
        if (previousExtension != extensionMode) {
            surfaceTexture?.let { startCamera(it) } ?: run {
                engine.setExtensionMode(extensionMode)
                engine.updatePreview()
            }
        } else {
            engine.setExtensionMode(extensionMode)
            engine.updatePreview()
        }
        updateControlsForMode()
        toast(
            when {
                !enabled -> "HDR off"
                extensionMode != null -> "HDR capture enabled"
                else -> "HDR scene enabled"
            }
        )
    }

    private fun cycleStabilization() {
        val c = current ?: return
        if (!c.supportsVideoStabilization) {
            toast("Stabilization is not supported by this camera")
            return
        }
        val options = buildList {
            add(VideoStabilization.AUTO)
            add(VideoStabilization.OFF)
            if (c.eisSupported) add(VideoStabilization.EIS)
            if (c.oisSupported) add(VideoStabilization.OIS)
        }.distinct()
        val index = options.indexOf(engine.settings.stabilization).coerceAtLeast(0)
        val selected = options[(index + 1) % options.size]
        engine.settings = engine.settings.copy(stabilization = selected)
        engine.updatePreview()
        updateControlsForMode()
        val active = effectiveStabilization(c)
        val label = when {
            selected == VideoStabilization.OFF -> "Stabilization off"
            active == VideoStabilization.OFF -> "Stabilization unavailable"
            selected == VideoStabilization.AUTO -> "${active.name} stabilization auto"
            else -> "${active.name} stabilization on"
        }
        toast(label)
    }

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        timerBtn.contentDescription = if (timerSeconds == 0) "Timer off" else "$timerSeconds second timer"
        updateControlsForMode()
        toast(if (timerSeconds == 0) "Timer off" else "$timerSeconds second timer")
    }

    private fun onShutterAction() {
        if (isVideoCaptureMode()) {
            if (engine.isRecording) {
                engine.stopRecording()
                return
            }
            val hasMicrophone = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            if (audioEnabled && !hasMicrophone) toast("No microphone available — recording without audio")
            val start = {
                if (audioEnabled && hasMicrophone &&
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingAudioVideo = true
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
                } else engine.startRecording(audioEnabled && hasMicrophone)
            }
            if (timerSeconds > 0) startCountdown(timerSeconds, start) else start()
            return
        }
        val capture = {
            flashScreen()
            if (burstEnabled) engine.captureBurst(5) else engine.capture()
        }
        if (timerSeconds > 0) startCountdown(timerSeconds, capture) else capture()
    }

    private fun startCountdown(seconds: Int, action: () -> Unit) {
        ui.removeCallbacks(countdownTick)
        countdownAction = action
        countdownValue = seconds - 1
        countdownOverlay.visibility = View.VISIBLE
        countdownOverlay.text = seconds.toString()
        countdownOverlay.alpha = 0f
        countdownOverlay.scaleX = 0.72f
        countdownOverlay.scaleY = 0.72f
        countdownOverlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
        ui.postDelayed(countdownTick, 1000)
    }

    private fun showSettings() {
        if (engine.isRecording) {
            toast("Stop recording before changing capture settings")
            return
        }
        val c = current ?: return
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add("Photo size & aspect ratio" to { showPhotoSizeOptions() })
        if (c.videoProfiles.isNotEmpty()) actions.add("Video resolution & frame rate" to { showVideoProfileOptions() })
        if (isVideoCaptureMode()) {
            val profile = videoProfiles[c.id] ?: c.defaultVideoProfile()
            val codecs = profile?.let { c.videoCodecs[it].orEmpty() }.orEmpty()
            if (codecs.isNotEmpty()) {
                actions.add("Video codec: ${engine.settings.videoCodec.label}" to {
                    if (codecs.size > 1) showVideoCodecOptions()
                    else toast("${codecs.first().label} is the only supported codec for this profile")
                })
            }
            actions.add("Video bitrate: ${bitrateLabel(engine.settings.videoBitrateFactor)}" to {
                showVideoBitrateOptions()
            })
        }
        actions.add("Burst capture: ${if (burstEnabled) "On" else "Off"}" to {
            burstEnabled = !burstEnabled
            toast(if (burstEnabled) "5-frame burst on" else "Burst off")
        })
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            actions.add("Video audio: ${if (audioEnabled) "On" else "Off"}" to {
                audioEnabled = !audioEnabled
                toast(if (audioEnabled) "Video audio on" else "Video audio off")
                updateControlsForMode()
            })
            actions.add("Audio quality: ${if (audioHighQuality) "High · 48 kHz / 192 kbps" else "Balanced · 44.1 kHz / 128 kbps"}" to {
                audioHighQuality = !audioHighQuality
                engine.settings = engine.settings.copy(
                    audioSampleRate = if (audioHighQuality) 48_000 else 44_100,
                    audioBitrate = if (audioHighQuality) 192_000 else 128_000
                )
                toast(if (audioHighQuality) "High audio quality" else "Balanced audio quality")
            })
        }
        if (isVideoCaptureMode() && c.supportsVideoStabilization) {
            actions.add("Video stabilization: ${effectiveStabilization(c).name}" to {
                cycleStabilization()
            })
        }
        if (c.hasRaw && extensionModeFor(currentMode, c) == null) {
            actions.add("RAW + JPEG: ${if (engine.settings.raw) "On" else "Off"}" to { toggleRaw() })
        }
        if (proMode && !proVideoMode && c.hasManualToneCurve) {
            actions.add("Highlight and shadow tone curve" to { showToneCurveControls() })
        }
        actions.add("Hardware report" to { showInfo() })
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Capture setup")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPhotoSizeOptions() {
        val c = current ?: return
        val extensionMode = extensionModeFor(currentMode, c)
        val sizes = (if (extensionMode != null) c.extensionPhotoSizes[extensionMode].orEmpty() else c.jpegSizes)
            .sortedByDescending { it.width.toLong() * it.height }
        if (sizes.isEmpty()) {
            toast("No photo sizes available in this mode")
            return
        }
        val selected = sizes.indexOf(photoSizes[c.id] ?: c.maxJpeg).coerceAtLeast(0)
        val labels = sizes.map { size ->
            val ratio = size.width.toDouble() / size.height
            val aspect = when {
                abs(ratio - 4.0 / 3.0) < 0.04 -> "4:3"
                abs(ratio - 16.0 / 9.0) < 0.04 -> "16:9"
                abs(ratio - 3.0 / 2.0) < 0.04 -> "3:2"
                else -> "%.2f:1".format(ratio)
            }
            "${size.width} × ${size.height}   $aspect"
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Photo size · aspect ratio")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                val size = sizes[which]
                photoSizes[c.id] = size
                dialog.dismiss()
                surfaceTexture?.let { startCamera(it) }
                toast("${size.width} × ${size.height}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVideoProfileOptions() {
        val c = current ?: return
        val profiles = c.videoProfiles
        if (profiles.isEmpty()) {
            toast("Video recording is not supported by this camera")
            return
        }
        val labels = profiles.map { profile ->
            val quality = when {
                profile.size.width >= 3840 -> "4K"
                profile.size.width >= 1920 -> "1080p"
                profile.size.width >= 1280 -> "720p"
                else -> "${profile.size.width}×${profile.size.height}"
            }
            "$quality · ${profile.frameRateLabel}"
        }
        val selected = profiles.indexOf(videoProfiles[c.id]).coerceAtLeast(0)
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Video quality")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                val profile = profiles[which]
                videoProfiles[c.id] = profile
                engine.setVideoProfile(profile)
                buildProPanel(c)
                dialog.dismiss()
                surfaceTexture?.let { startCamera(it) }
                toast("Video set to ${labels[which]}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVideoCodecOptions() {
        val c = current ?: return
        val profile = videoProfiles[c.id] ?: c.defaultVideoProfile() ?: return
        val codecs = c.videoCodecs[profile].orEmpty()
        if (codecs.isEmpty()) return toast("No supported video encoder for this profile")
        val selected = codecs.indexOf(engine.settings.videoCodec).coerceAtLeast(0)
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Video codec")
            .setSingleChoiceItems(codecs.map { it.label }.toTypedArray(), selected) { dialog, which ->
                engine.setVideoCodec(codecs[which])
                dialog.dismiss()
                toast("${codecs[which].label} selected")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVideoBitrateOptions() {
        val factors = listOf(0.75f, 1f, 1.35f)
        val labels = listOf("Efficient", "Standard", "High")
        val selected = factors.indices.minByOrNull {
            abs(factors[it] - engine.settings.videoBitrateFactor)
        } ?: 1
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Video bitrate")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                engine.setVideoBitrateFactor(factors[which])
                dialog.dismiss()
                toast("${labels[which]} bitrate")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bitrateLabel(factor: Float) = when {
        factor < 0.9f -> "Efficient"
        factor > 1.15f -> "High"
        else -> "Standard"
    }

    private fun toggleGrid() {
        val on = grid.visibility != View.VISIBLE
        gridBtn.active = on
        grid.animate().cancel()
        if (on) {
            grid.visibility = View.VISIBLE
            grid.animate().alpha(1f).setDuration(200).start()
        } else {
            grid.animate().alpha(0f).setDuration(200).withEndAction { grid.visibility = View.GONE }.start()
        }
    }

    private fun toggleRaw() {
        val c = current
        if (c == null || !c.hasRaw || extensionModeFor(currentMode, c) != null) {
            toast("RAW is unavailable in this camera mode")
            return
        }
        val on = !engine.settings.raw
        engine.settings = engine.settings.copy(raw = on)
        toast(if (on) "RAW + JPEG" else "JPEG only")
        if (proMode) updateProActions()
    }

    private fun showInfo() {
        val text = if (allCaps.isEmpty()) "No cameras found" else allCaps.joinToString("\n") { it.describe() }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Hardware report")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------------------------ shutter row

    private fun flashScreen() {
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 0.55f
        flashOverlay.animate().alpha(0f).setDuration(200).start()
    }

    private fun flipCamera() {
        if (engine.isRecording) {
            toast("Stop recording before switching cameras")
            return
        }
        if (allCaps.size < 2) {
            toast("Only one camera available")
            return
        }
        flipBtn.animate().rotationBy(180f).setDuration(420).setInterpolator(DecelerateInterpolator()).start()
        host.animate().alpha(0f).setDuration(140).start()
        host.postDelayed({
            val idx = allCaps.indexOf(current)
            current = allCaps[(idx + 1) % allCaps.size]
            zoomAnim?.cancel()
            engine.settings = ManualSettings()
            surfaceTexture?.let { startCamera(it) }
            host.postDelayed({ host.animate().alpha(1f).setDuration(260).start() }, 300)
        }, 150)
    }

    private fun loadThumb(uri: Uri) {
        Thread {
            try {
                val bmp = contentResolver.loadThumbnail(uri, Size(256, 256), null)
                ui.post {
                    thumbImg.setImageBitmap(bmp)
                    thumbImg.visibility = View.VISIBLE
                    thumbImg.scaleX = 0.6f
                    thumbImg.scaleY = 0.6f
                    thumbImg.animate().scaleX(1f).scaleY(1f).setDuration(280)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun openLast() {
        val u = lastUri ?: return toast("No photos yet")
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(u, lastMimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("No gallery app found")
        }
    }

    private fun toast(msg: String) {
        msgPill.text = msg
        msgPill.removeCallbacks(hideMsg)
        msgPill.animate().cancel()
        msgPill.translationY = -dp(8f)
        msgPill.animate().alpha(1f).translationY(0f).setDuration(180).start()
        msgPill.postDelayed(hideMsg, 2000)
    }

    // ------------------------------------------------------------------ modes + pro panel

    private fun onModeSelected(i: Int) {
        val selectedMode = modeItems.getOrNull(i) ?: return
        if (engine.isRecording) {
            toast("Stop recording before changing mode")
            modeSelector.select(modeItems.indexOf(currentMode), true)
            return
        }
        if (selectedMode == "VIDEO" && current?.videoProfiles.isNullOrEmpty()) {
            toast("Video recording is not supported by this camera")
            modeSelector.select(modeItems.indexOf(currentMode), true)
            return
        }
        val pro = selectedMode == "PRO"
        if (pro && chips.isEmpty() && current?.videoProfiles.isNullOrEmpty()) {
            toast("No manual controls on this camera")
            modeSelector.select(modeItems.indexOf(currentMode), true)
            return
        }
        val wasVideoMode = isVideoCaptureMode()
        val previousExtension = current?.let { extensionModeFor(currentMode, it) }
        val extensionMode = current?.let { extensionModeFor(selectedMode, it) }
        currentMode = selectedMode
        proMode = pro
        engine.settings = if (selectedMode == "PRO") {
            engine.settings.copy(hdr = false, night = false, torch = false)
        } else {
            engine.settings.copy(
                manualExposure = false,
                manualFocus = false,
                awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO,
                manualKelvin = null,
                aeLocked = false,
                afLocked = false,
                raw = engine.settings.raw && extensionMode == null,
                hdr = selectedMode == "PHOTO" && engine.settings.hdr,
                night = selectedMode == "NIGHT" && extensionMode == null,
                torch = if (selectedMode == "VIDEO") engine.settings.torch else false
            )
        }
        val previewConfigurationChanged = previousExtension != extensionMode ||
            wasVideoMode != isVideoCaptureMode()
        if (previewConfigurationChanged) {
            surfaceTexture?.let { startCamera(it) } ?: run {
                engine.setExtensionMode(extensionMode)
                engine.setVideoMode(isVideoCaptureMode())
                engine.updatePreview()
            }
        } else {
            engine.setExtensionMode(extensionMode)
            engine.setVideoMode(isVideoCaptureMode())
            engine.updatePreview()
        }
        setProVisible(pro)
        updateControlsForMode()
        if (pro) {
            refreshChips()
            selected?.let { selectParam(it) }
        }
    }

    private fun setProVideoMode(video: Boolean) {
        if (video && current?.videoProfiles.isNullOrEmpty()) {
            toast("Video recording is not supported by this camera")
            return
        }
        val wasVideo = isVideoCaptureMode()
        if (proVideoMode == video) return
        proVideoMode = video
        if (video) {
            val c = current
            val profile = c?.let { videoProfiles[it.id] ?: it.defaultVideoProfile() }
            if (profile != null && engine.settings.manualExposure) {
                val maxShutter = 1_000_000_000L / profile.fps
                if (engine.settings.shutterNs > maxShutter) {
                    engine.settings = engine.settings.copy(shutterNs = maxShutter)
                }
            }
        }
        val isVideo = isVideoCaptureMode()
        if (wasVideo != isVideo) {
            surfaceTexture?.let { startCamera(it) } ?: engine.setVideoMode(isVideo)
        }
        current?.let { buildProPanel(it) }
        updateControlsForMode()
        selected?.let { selectParam(it) }
    }

    private fun setProVisible(show: Boolean) {
        proPanel.animate().cancel()
        if (show) {
            proPanel.visibility = View.VISIBLE
            proPanel.alpha = 0f
            proPanel.translationY = dp(20f)
            proPanel.animate().alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            ui.removeCallbacks(monitorTick)
            focusPeaking.setPoints(FloatArray(0))
            proPanel.animate().alpha(0f).translationY(dp(20f)).setDuration(180)
                .withEndAction { if (!proMode) proPanel.visibility = View.GONE }.start()
        }
    }

    private fun buildProPanel(c: CameraCaps) {
        chipsRow.removeAllViews()
        chips.clear()
        val iso = c.isoRange
        val ex = c.exposureRangeNs
        if (c.hasManualSensor && iso != null && ex != null) {
            isoStops = ISO_BASE.filter { it >= iso.lower && it <= iso.upper }
                .ifEmpty { listOf(iso.lower, iso.upper).distinct() }
            val selectedFps = (videoProfiles[c.id] ?: c.defaultVideoProfile())?.fps ?: 30
            val maxShutter = if (proVideoMode) minOf(ex.upper, 1_000_000_000L / selectedFps) else ex.upper
            shutterStops = SHUTTER_BASE_NS.filter { it >= ex.lower && it <= maxShutter }
                .ifEmpty { listOf(maxShutter.coerceIn(ex.lower, ex.upper)) }
            addChip(Param.ISO)
            addChip(Param.SHUTTER)
        }
        if (c.hasManualFocus) {
            focusLabels = (0..FOCUS_STEPS).map { j ->
                val d = j.toFloat() / FOCUS_STEPS * c.minFocusDiopters
                if (j == 0 || d < 0.01f) "∞" else {
                    val m = 1f / d
                    if (m >= 1f) "%.1f m".format(m) else "%.0f cm".format(m * 100f)
                }
            }
            addChip(Param.FOCUS)
        }
        val names = mapOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO to "AUTO",
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "INCAND",
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "FLUOR",
            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to "WARM FL",
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "DAYLIGHT",
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "CLOUDY",
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT to "TWILIGHT",
            CameraMetadata.CONTROL_AWB_MODE_SHADE to "SHADE"
        )
        awbItems = c.awbModes.filter { names.containsKey(it) }.sorted().map { it to names.getValue(it) }
        if (awbItems.size > 1) addChip(Param.WB)
        kelvinStops = (2500..8000 step 250).toList()
        if (c.hasManualWhiteBalance) addChip(Param.KELVIN)
        if (c.evRange.upper > c.evRange.lower) addChip(Param.EV)

        if (selected == null || !chips.containsKey(selected)) selected = chips.keys.firstOrNull()
        ruler.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
        chipsRow.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
        refreshChips()
        updateProActions()
        updateProSubmodeAppearance()
        if (proMode && chips.isEmpty()) {
            if (c.videoProfiles.isEmpty()) {
                proMode = false
                modeSelector.select(0, false)
                setProVisible(false)
            }
        } else if (proMode) {
            selected?.let { selectParam(it) }
        }
    }

    private fun addChip(p: Param) {
        val ch = ParamChip(this, p.label)
        ch.setOnClickListener { selectParam(p) }
        chips[p] = ch
        chipsRow.addView(ch, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun selectParam(p: Param) {
        selected = p
        for ((k, ch) in chips) ch.setChipSelected(k == p)
        val spacing = when (p) {
            Param.ISO, Param.SHUTTER -> 44f
            Param.FOCUS -> 14f
            Param.WB -> 78f
            Param.KELVIN -> 30f
            Param.EV -> 34f
        }
        ruler.onSelect = null
        ruler.setItems(labelsFor(p), indexFor(p), spacing)
        ruler.onSelect = { idx -> applyParam(p, idx) }
    }

    private fun labelsFor(p: Param): List<String> {
        val c = current
        return when (p) {
            Param.ISO -> listOf("AUTO") + isoStops.map { it.toString() }
            Param.SHUTTER -> listOf("AUTO") + shutterStops.map { CameraCaps.fmtShutter(it) }
            Param.FOCUS -> listOf("AF") + focusLabels
            Param.WB -> awbItems.map { it.second }
            Param.KELVIN -> listOf("AUTO") + kelvinStops.map { "${it}K" }
            Param.EV -> if (c == null) emptyList() else (c.evRange.lower..c.evRange.upper).map { evLabel(it) }
        }
    }

    private fun indexFor(p: Param): Int {
        val s = engine.settings
        val c = current ?: return 0
        return when (p) {
            Param.ISO -> if (!s.manualExposure) 0 else nearestIso(s.iso) + 1
            Param.SHUTTER -> if (!s.manualExposure) 0 else nearestShutter(s.shutterNs) + 1
            Param.FOCUS -> if (!s.manualFocus) 0 else
                ((s.focusDiopters / c.minFocusDiopters * FOCUS_STEPS).roundToInt() + 1).coerceIn(1, FOCUS_STEPS + 1)
            Param.WB -> awbItems.indexOfFirst { it.first == s.awbMode }.coerceAtLeast(0)
            Param.KELVIN -> s.manualKelvin?.let {
                (kelvinStops.indices.minByOrNull { index -> abs(kelvinStops[index] - it) } ?: 0) + 1
            } ?: 0
            Param.EV -> s.evIndex - c.evRange.lower
        }
    }

    private fun nearestIso(v: Int): Int =
        isoStops.indices.minByOrNull { abs(isoStops[it] - v) } ?: 0

    private fun nearestShutter(ns: Long): Int =
        shutterStops.indices.minByOrNull { abs(ln(shutterStops[it].toDouble() / ns.coerceAtLeast(1L))) } ?: 0

    /** Switching from auto to manual exposure starts from what auto was using, so the image doesn't jump. */
    private fun seeded(s: ManualSettings): ManualSettings {
        if (s.manualExposure || isoStops.isEmpty() || shutterStops.isEmpty()) return s
        val iso = if (engine.lastIso > 0) engine.lastIso else 100
        val ns = if (engine.lastExposureNs > 0) engine.lastExposureNs else 16_666_667L
        return s.copy(
            manualExposure = true,
            iso = isoStops[nearestIso(iso)],
            shutterNs = shutterStops[nearestShutter(ns)]
        )
    }

    private fun applyParam(p: Param, idx: Int) {
        val c = current ?: return
        var s = engine.settings
        when (p) {
            Param.ISO -> s = if (idx == 0) s.copy(manualExposure = false)
                else seeded(s).let { it.copy(iso = isoStops[idx - 1], aeLocked = false) }
            Param.SHUTTER -> s = if (idx == 0) s.copy(manualExposure = false)
                else seeded(s).let { it.copy(shutterNs = shutterStops[idx - 1], aeLocked = false) }
            Param.FOCUS -> s = if (idx == 0) s.copy(manualFocus = false, afLocked = false) else s.copy(
                manualFocus = true,
                afLocked = false,
                focusDiopters = (idx - 1).toFloat() / FOCUS_STEPS * c.minFocusDiopters
            )
            Param.WB -> s = s.copy(awbMode = awbItems[idx].first, manualKelvin = null)
            Param.KELVIN -> s = if (idx == 0) s.copy(manualKelvin = null)
                else s.copy(manualKelvin = kelvinStops[idx - 1], awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO)
            Param.EV -> s = s.copy(evIndex = c.evRange.lower + idx)
        }
        engine.settings = s
        engine.updatePreview()
        refreshChips()
        updateControlsForMode()
    }

    private fun refreshChips() {
        val s = engine.settings
        chips[Param.ISO]?.setValue(if (s.manualExposure) "${s.iso}" else "AUTO")
        chips[Param.SHUTTER]?.setValue(if (s.manualExposure) CameraCaps.fmtShutter(s.shutterNs) else "AUTO")
        chips[Param.FOCUS]?.setValue(
            if (s.manualFocus) {
                val c = current
                val j = if (c == null || c.minFocusDiopters <= 0f) 0
                else (s.focusDiopters / c.minFocusDiopters * FOCUS_STEPS).roundToInt().coerceIn(0, FOCUS_STEPS)
                focusLabels.getOrElse(j) { "MF" }
            } else "AF"
        )
        chips[Param.WB]?.setValue(
            if (s.manualKelvin != null) "KELVIN" else awbItems.firstOrNull { it.first == s.awbMode }?.second ?: "AUTO"
        )
        chips[Param.KELVIN]?.setValue(s.manualKelvin?.let { "${it}K" } ?: "AUTO")
        chips[Param.EV]?.let {
            it.setValue(evLabel(s.evIndex))
            it.visibility = if (s.manualExposure && chips.containsKey(Param.ISO)) View.GONE else View.VISIBLE
        }
        if (selected == Param.EV && s.manualExposure && chips.containsKey(Param.ISO)) selectParam(Param.ISO)
    }

    private fun updateProSubmodeAppearance() {
        val selectedBg = GradientDrawable().apply {
            cornerRadius = dp(15f)
            setColor(0x44332B1E)
        }
        proPhotoButton.background = if (!proVideoMode) selectedBg else null
        proVideoButton.background = if (proVideoMode) selectedBg else null
        proPhotoButton.setTextColor(if (!proVideoMode) Theme.ACCENT else Theme.TEXT_DIM)
        proVideoButton.setTextColor(if (proVideoMode) Theme.ACCENT else Theme.TEXT_DIM)
    }

    private fun updateProActions() {
        if (!::proActions.isInitialized) return
        proActions.removeAllViews()
        val c = current ?: return
        val actions = ArrayList<Triple<String, Boolean, () -> Unit>>()
        val canLockAe = c.hasAeLock && !engine.settings.manualExposure
        val canLockAf = c.hasAfLock && !engine.settings.manualFocus
        if (canLockAe || canLockAf) {
            val locked = engine.settings.aeLocked || engine.settings.afLocked
            actions.add(Triple("LOCK", locked) {
                val enable = !locked
                engine.settings = engine.settings.copy(
                    aeLocked = enable && canLockAe,
                    afLocked = enable && canLockAf
                )
                engine.updatePreview()
                if (enable && canLockAf) engine.triggerFocusLock()
                updateControlsForMode()
            })
        }
        if (!proVideoMode && c.hasRaw && extensionModeFor(currentMode, c) == null) {
            actions.add(Triple(if (engine.settings.raw) "RAW+JPG" else "JPEG", engine.settings.raw) {
                toggleRaw()
                updateProActions()
            })
        }
        if (!proVideoMode && c.hasManualFocus) {
            actions.add(Triple("PEAK", peakingEnabled) {
                peakingEnabled = !peakingEnabled
                focusPeaking.visibility = if (peakingEnabled) View.VISIBLE else View.GONE
                focusPeaking.setPoints(FloatArray(0))
                updateControlsForMode()
            })
        }
        if (!proVideoMode && c.hasManualToneCurve) {
            actions.add(Triple("TONE", engine.settings.highlights != 0f || engine.settings.shadows != 0f) {
                showToneCurveControls()
            })
        }
        if (hasLevelSensor) {
            actions.add(Triple("LEVEL", levelEnabled) {
                levelEnabled = !levelEnabled
                updateControlsForMode()
            })
        }
        actions.add(Triple("HIST", histogramEnabled) {
            histogramEnabled = !histogramEnabled
            updateControlsForMode()
        })
        actions.forEachIndexed { index, (label, active, action) ->
            val button = TextView(this).apply {
                text = label
                textSize = 9f
                typeface = Theme.MEDIUM
                gravity = Gravity.CENTER
                minHeight = dpi(34)
                setTextColor(if (active) Theme.ACCENT else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f)
                    setColor(if (active) 0x44332B1E else 0xAA151514.toInt())
                }
                setOnClickListener { action() }
                addPressFeedback(0.96f)
            }
            proActions.addView(button, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (index > 0) marginStart = dpi(4)
            })
        }
    }

    private fun findPeakingEdges(pixels: IntArray, width: Int, height: Int): FloatArray {
        val points = ArrayList<Float>(512)
        fun luma(index: Int): Int {
            val color = pixels[index]
            return (Color.red(color) * 0.2126f + Color.green(color) * 0.7152f +
                Color.blue(color) * 0.0722f).toInt()
        }
        for (y in 1 until height - 1 step 2) {
            for (x in 1 until width - 1 step 2) {
                val i = y * width + x
                val center = luma(i)
                val highFrequency = abs(4 * center - luma(i - 1) - luma(i + 1) - luma(i - width) - luma(i + width))
                if (highFrequency > 105) {
                    points.add(x.toFloat() / width)
                    points.add(y.toFloat() / height)
                }
            }
        }
        return points.toFloatArray()
    }

    private fun availableStorageLabel(): String {
        val path = getExternalFilesDir(null)?.path ?: Environment.getExternalStorageDirectory().path
        val free = StatFs(path).availableBytes
        return "FREE %.1f GB".format(free / 1_000_000_000.0)
    }

    private fun showToneCurveControls() {
        val c = current ?: return
        if (!c.hasManualToneCurve) {
            toast("Manual tone curves are not supported by this camera")
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), dpi(4))
        }
        fun addToneControl(label: String, value: Float, update: (Float) -> Unit) {
            val labelView = TextView(this).apply {
                text = "$label  ${"%+.2f".format(value)}"
                textSize = 13f
                typeface = Theme.MEDIUM
                setTextColor(Color.WHITE)
            }
            val slider = SeekBar(this).apply {
                max = 200
                progress = ((value.coerceIn(-1f, 1f) + 1f) * 100).roundToInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val next = progress / 100f - 1f
                        labelView.text = "$label  ${"%+.2f".format(next)}"
                        update(next)
                        engine.updatePreview()
                        updateProActions()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            content.addView(labelView, LinearLayout.LayoutParams(MATCH, WRAP))
            content.addView(slider, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = dpi(12)
            })
        }
        addToneControl("Shadows", engine.settings.shadows) {
            engine.settings = engine.settings.copy(shadows = it)
        }
        addToneControl("Highlights", engine.settings.highlights) {
            engine.settings = engine.settings.copy(highlights = it)
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Tone curve")
            .setView(content)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ ->
                engine.settings = engine.settings.copy(highlights = 0f, shadows = 0f)
                engine.updatePreview()
                updateProActions()
            }
            .show()
    }
}
