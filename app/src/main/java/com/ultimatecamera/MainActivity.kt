package com.ultimatecamera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.ultimatecamera.camera.CameraEngine
import com.ultimatecamera.hardware.CameraCaps
import com.ultimatecamera.hardware.HardwareAnalyzer
import com.ultimatecamera.ui.AutoFitTextureView
import kotlin.math.pow

class MainActivity : Activity(), CameraEngine.Listener, TextureView.SurfaceTextureListener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var texture: AutoFitTextureView
    private lateinit var engine: CameraEngine
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var proBtn: Button
    private lateinit var rawBtn: Button

    private var allCaps: List<CameraCaps> = emptyList()
    private var current: CameraCaps? = null
    private var proMode = false
    private var surface: SurfaceTexture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allCaps = HardwareAnalyzer(this).analyze()
        engine = CameraEngine(this, this)
        current = allCaps.firstOrNull { !it.isFront } ?: allCaps.firstOrNull()
        buildUi()
        if (current == null) status.text = "No camera found"
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        if (texture.isAvailable) startCamera(texture.surfaceTexture!!) else texture.surfaceTextureListener = this
    }

    override fun onPause() {
        engine.close()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (texture.isAvailable) startCamera(texture.surfaceTexture!!) else texture.surfaceTextureListener = this
        } else {
            status.text = "Camera permission is required to take photos"
        }
    }

    // ---------- Camera lifecycle ----------

    private fun startCamera(st: SurfaceTexture) {
        val c = current ?: return
        surface = st
        val size = c.bestPreview()
        texture.setAspectRatio(size.height, size.width) // portrait
        engine.displayRotationDegrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        applyModeToEngine()
        engine.open(c, st, size)
        buildPanel()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startCamera(st)
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onMessage(msg: String) {
        ui.post { status.text = msg }
    }

    override fun onSaved(uri: Uri, raw: Boolean) {
        ui.post { status.text = if (raw) "Saved DNG: Pictures/UltimateCamera" else "Saved JPEG: Pictures/UltimateCamera" }
    }

    // ---------- Settings plumbing ----------

    private fun applyModeToEngine() {
        val c = current ?: return
        engine.settings = engine.settings.copy(
            manualExposure = proMode && c.hasManualSensor,
            manualFocus = engine.settings.manualFocus && proMode && c.hasManualFocus,
            raw = engine.settings.raw && c.hasRaw
        )
    }

    private fun change(f: (com.ultimatecamera.camera.ManualSettings) -> com.ultimatecamera.camera.ManualSettings) {
        engine.settings = f(engine.settings)
        engine.updatePreview()
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun styledButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#2A2A2E"))
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        texture = AutoFitTextureView(this)
        root.addView(texture, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC101012"))
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }

        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f }
        bottom.addView(status)

        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(panel)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        proBtn = styledButton("AUTO") {
            proMode = !proMode
            proBtn.text = if (proMode) "PRO" else "AUTO"
            applyModeToEngine()
            buildPanel()
            engine.updatePreview()
        }
        rawBtn = styledButton("RAW off") {
            val c = current
            if (c == null || !c.hasRaw) {
                status.text = "RAW not supported by this camera"
            } else {
                val on = !engine.settings.raw
                change { it.copy(raw = on) }
                rawBtn.text = if (on) "RAW + JPEG" else "RAW off"
            }
        }
        val shutter = Button(this).apply {
            text = "●"
            textSize = 28f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setOnClickListener { engine.capture() }
        }
        val flip = styledButton("Flip") { switchCamera() }
        val info = styledButton("Info") { showInfo() }

        row.addView(proBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(rawBtn, LinearLayout.LayoutParams(0, dp(48), 1.3f))
        row.addView(shutter, LinearLayout.LayoutParams(dp(76), dp(76)).apply { setMargins(dp(8), 0, dp(8), 0) })
        row.addView(flip, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(info, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottom.addView(row)

        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun switchCamera() {
        if (allCaps.size < 2) {
            status.text = "Only one camera available"
            return
        }
        val next = allCaps[(allCaps.indexOf(current) + 1) % allCaps.size]
        current = next
        engine.settings = com.ultimatecamera.camera.ManualSettings()
        rawBtn.text = "RAW off"
        surface?.let { startCamera(it) }
    }

    private fun showInfo() {
        val text = allCaps.joinToString("\n") { it.describe() }
        AlertDialog.Builder(this).setTitle("Hardware report").setMessage(text).setPositiveButton("OK", null).show()
    }

    /** Controls shown depend on what the current camera really supports. */
    private fun buildPanel() {
        panel.removeAllViews()
        val c = current ?: return

        if (c.evRange.upper > c.evRange.lower && !(proMode && c.hasManualSensor)) {
            val span = c.evRange.upper - c.evRange.lower
            panel.addView(seekRow("EV", span, engine.settings.evIndex - c.evRange.lower) { p ->
                change { it.copy(evIndex = p + c.evRange.lower) }
                "%+.1f".format((p + c.evRange.lower) * c.evStep)
            })
        }

        if (c.awbModes.size > 1) {
            val names = mapOf(
                CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto WB",
                CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "Incandescent",
                CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent",
                CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to "Warm fluorescent",
                CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
                CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy",
                CameraMetadata.CONTROL_AWB_MODE_TWILIGHT to "Twilight",
                CameraMetadata.CONTROL_AWB_MODE_SHADE to "Shade"
            )
            val modes = c.awbModes.filter { names.containsKey(it) }
            if (modes.size > 1) {
                var b: Button? = null
                b = styledButton("WB: ${names[engine.settings.awbMode] ?: "Auto"}") {
                    val i = modes.indexOf(engine.settings.awbMode)
                    val next = modes[(i + 1) % modes.size]
                    change { it.copy(awbMode = next) }
                    b?.text = "WB: ${names[next]}"
                }
                panel.addView(b, LinearLayout.LayoutParams(-1, dp(44)))
            }
        }

        if (!proMode) return

        if (c.hasManualSensor && c.isoRange != null && c.exposureRangeNs != null) {
            val isoLo = c.isoRange.lower.toDouble()
            val isoHi = c.isoRange.upper.toDouble()
            panel.addView(seekRow("ISO", 100, logPos(engine.settings.iso.toDouble(), isoLo, isoHi)) { p ->
                val iso = logMap(p, isoLo, isoHi).toInt()
                change { it.copy(iso = iso) }
                iso.toString()
            })
            val exLo = c.exposureRangeNs.lower.toDouble()
            val exHi = c.exposureRangeNs.upper.toDouble()
            panel.addView(seekRow("Shutter", 100, logPos(engine.settings.shutterNs.toDouble(), exLo, exHi)) { p ->
                val ns = logMap(p, exLo, exHi).toLong()
                change { it.copy(shutterNs = ns) }
                CameraCaps.fmtShutter(ns)
            })
        } else {
            panel.addView(TextView(this).apply {
                text = "Manual ISO/shutter not supported on this camera"
                setTextColor(Color.LTGRAY); textSize = 12f
            })
        }

        if (c.hasManualFocus) {
            var mf: Button? = null
            mf = styledButton(if (engine.settings.manualFocus) "Focus: manual" else "Focus: auto") {
                val on = !engine.settings.manualFocus
                change { it.copy(manualFocus = on) }
                mf?.text = if (on) "Focus: manual" else "Focus: auto"
            }
            panel.addView(mf, LinearLayout.LayoutParams(-1, dp(44)))
            panel.addView(seekRow("Focus", 100, 0) { p ->
                val d = p / 100f * c.minFocusDiopters
                change { it.copy(focusDiopters = d, manualFocus = true) }
                mf.text = "Focus: manual"
                if (d < 0.05f) "∞" else "%.0f cm".format(100f / d)
            })
        } else {
            panel.addView(TextView(this).apply {
                text = "Manual focus not supported on this camera"
                setTextColor(Color.LTGRAY); textSize = 12f
            })
        }
    }

    private fun seekRow(label: String, max: Int, start: Int, onChange: (Int) -> String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 12f }
        val value = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.END }
        val bar = SeekBar(this).apply {
            this.max = max
            progress = start.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val t = onChange(p)
                    value.text = t
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        row.addView(name, LinearLayout.LayoutParams(dp(56), -2))
        row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value, LinearLayout.LayoutParams(dp(64), -2))
        return row
    }

    private fun logMap(p: Int, lo: Double, hi: Double) = lo * (hi / lo).pow(p / 100.0)
    private fun logPos(v: Double, lo: Double, hi: Double): Int =
        (100 * Math.log(v.coerceIn(lo, hi) / lo) / Math.log(hi / lo)).toInt()
}
