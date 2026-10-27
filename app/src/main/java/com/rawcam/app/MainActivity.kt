package com.rawcam.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val FAST_NS = 3_333_333L

class MainActivity : Activity(), CameraEngine.Listener {

    private enum class P { EV, ISO, SHUTTER, FOCUS }

    private lateinit var engine: CameraEngine
    private lateinit var texture: TextureView
    private lateinit var box: AspectBox
    private lateinit var grid: GridView
    private lateinit var hist: HistogramView
    private lateinit var focusRing: FocusRing
    private lateinit var flash: View
    private lateinit var readout: TextView
    private lateinit var lensRow: LinearLayout
    private lateinit var ruler: RulerView
    private lateinit var shutter: ShutterButton
    private lateinit var thumb: ImageView
    private lateinit var fastChip: TextView
    private lateinit var normalChip: TextView
    private lateinit var filmChip: TextView
    private val fmtChips = ArrayList<TextView>()
    private val paramChips = HashMap<P, TextView>()

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("rawcam", Context.MODE_PRIVATE) }

    private var lenses: List<Lens> = emptyList()
    private var curLens: Lens? = null
    private var info: CamInfo? = null
    private var sel = P.ISO
    private var busy = false
    private var deviceDeg = 0
    private var lastThumbUri: Uri? = null
    private var histRunning = false

    private val idx = HashMap<P, Int>()
    private var evLabels = listOf("0")
    private var evVals = listOf(0)
    private var isoLabels = listOf("AUTO")
    private var isoVals: List<Int?> = listOf(null)
    private var shLabels = listOf("AUTO")
    private var shVals: List<Long?> = listOf(null)
    private var foLabels = listOf("AUTO")
    private var foVals: List<Float?> = listOf(null)

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        engine = CameraEngine(this, this)
        engine.s.shadingMap = prefs.getBoolean("shading", true)
        engine.s.ois = prefs.getBoolean("ois", true)
        engine.s.format = prefs.getInt("format", FMT_RAW)
        engine.s.film = FilmEmulator.byName(prefs.getString("film", null))
        buildUi()
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                deviceDeg = ((o + 45) / 90 * 90) % 360
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
        loadLatest()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            maybeStart()
        }
    }

    override fun onPause() {
        super.onPause()
        histRunning = false
        engine.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) maybeStart()
        else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    private fun maybeStart() {
        if (!texture.isAvailable) return
        if (lenses.isEmpty()) {
            lenses = try { engine.discover() } catch (e: Exception) { emptyList() }
            if (lenses.isEmpty()) {
                Toast.makeText(this, "No camera with RAW support found", Toast.LENGTH_LONG).show()
                return
            }
            buildLensChips()
            curLens = lenses.firstOrNull { it.label == "1x" } ?: lenses.first()
        }
        openLens(curLens ?: lenses.first())
    }

    private fun openLens(l: Lens) {
        curLens = l
        refreshLensChips()
        texture.surfaceTexture?.let { engine.open(l, it) }
    }

    // ------------------------------------------------------------------ UI construction

    private fun pill(text: String, sizeSp: Float = 13f): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = sizeSp
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dpf(18f)
            setColor(Color.argb(60, 255, 255, 255))
        }
    }

    private fun setActive(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.BLACK else Color.WHITE)
        (v.background as GradientDrawable).setColor(if (on) ACCENT else Color.argb(60, 255, 255, 255))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { v, ins ->
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                ins
            }
        }

        // ---- top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val settings = pill("Menu").apply { setOnClickListener { showSettings() } }
        val spacer = View(this)
        fastChip = pill("Fast").apply { setOnClickListener { onFast() } }
        normalChip = pill("Normal").apply { setOnClickListener { onNormal() } }
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) } }
        top.addView(settings, lp())
        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(fastChip, lp())
        top.addView(normalChip, LinearLayout.LayoutParams(-2, -2))
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        // ---- viewfinder
        val wrap = FrameLayout(this)
        box = AspectBox(this)
        texture = TextureView(this)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) maybeStart()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusRing.show(e.x, e.y)
                setIndexAuto(P.FOCUS)
                engine.tapFocus(e.x / texture.width, e.y / texture.height)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                engine.resetAf()
                Toast.makeText(this@MainActivity, "Continuous AF", Toast.LENGTH_SHORT).show()
            }
        })
        texture.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
        box.addView(texture, FrameLayout.LayoutParams(-1, -1))
        grid = GridView(this).apply { visibility = if (prefs.getBoolean("grid", true)) View.VISIBLE else View.GONE }
        box.addView(grid, FrameLayout.LayoutParams(-1, -1))
        focusRing = FocusRing(this)
        box.addView(focusRing, FrameLayout.LayoutParams(-1, -1))
        hist = HistogramView(this).apply { visibility = if (prefs.getBoolean("hist", false)) View.VISIBLE else View.GONE }
        box.addView(hist, FrameLayout.LayoutParams(dp(110), dp(56), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(10), dp(36), 0, 0)
        })
        readout = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        box.addView(readout, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
        lensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dpf(24f)
                setColor(Color.argb(110, 0, 0, 0))
            }
        }
        box.addView(lensRow, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })
        flash = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            isClickable = false
        }
        box.addView(flash, FrameLayout.LayoutParams(-1, -1))
        wrap.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(wrap, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- pro panel
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        val fmtRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fmtNames = arrayOf("RAW", "JPG", "JPG+RAW")
        for (i in fmtNames.indices) {
            val t = pill(fmtNames[i], 12f)
            t.setOnClickListener { setFormat(i) }
            fmtChips.add(t)
            fmtRow.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        filmChip = pill("Film: Off", 12f).apply { setOnClickListener { pickFilm() } }
        fmtRow.addView(filmChip, LinearLayout.LayoutParams(0, -2, 1.6f).apply { marginStart = dp(4); marginEnd = dp(4) })
        panel.addView(fmtRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in P.values()) {
            val t = pill("", 13f)
            t.setOnClickListener { selectParam(p) }
            paramChips[p] = t
            chipRow.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            idx[p] = 0
        }
        panel.addView(chipRow, LinearLayout.LayoutParams(-1, -2))
        ruler = RulerView(this).apply { onChange = { onRuler(it) } }
        panel.addView(ruler, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(8) })
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))

        // ---- bottom bar
        val bottom = FrameLayout(this).apply { setPadding(0, dp(4), 0, dp(10)) }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.argb(60, 255, 255, 255))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setOnClickListener { openLast() }
        }
        thumb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setOval(0, 0, v.width, v.height)
            }
        }
        bottom.addView(thumb, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(32) })
        shutter = ShutterButton(this).apply { setOnClickListener { onShutterClick() } }
        bottom.addView(shutter, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        val reset = pill("Reset").apply { setOnClickListener { resetAll() } }
        bottom.addView(reset, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(28) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        refreshChips()
        selectParam(P.ISO)
        refreshFormatUi()
        updateModeChips()
    }

    private fun buildLensChips() {
        lensRow.removeAllViews()
        for (l in lenses) {
            val t = TextView(this).apply {
                text = l.label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                }
                setOnClickListener { if (curLens !== l) { openLens(l) } }
            }
            lensRow.addView(t, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
    }

    private fun refreshLensChips() {
        for (i in 0 until lensRow.childCount) {
            val t = lensRow.getChildAt(i) as TextView
            val on = lenses.getOrNull(i) === curLens
            t.setTextColor(if (on) Color.BLACK else Color.WHITE)
            (t.background as GradientDrawable).setColor(if (on) ACCENT else Color.TRANSPARENT)
        }
    }

    // ------------------------------------------------------------------ pro controls

    private fun labels(p: P) = when (p) { P.EV -> evLabels; P.ISO -> isoLabels; P.SHUTTER -> shLabels; P.FOCUS -> foLabels }

    private fun selectParam(p: P) {
        sel = p
        for ((k, v) in paramChips) setActive(v, k == p)
        ruler.items = labels(p)
        ruler.setIndex(idx[p] ?: 0)
    }

    private fun refreshChips() {
        paramChips[P.EV]?.text = "EV ${evLabels.getOrElse(idx[P.EV] ?: 0) { "0" }}"
        paramChips[P.ISO]?.text = "ISO ${isoLabels.getOrElse(idx[P.ISO] ?: 0) { "AUTO" }}"
        paramChips[P.SHUTTER]?.text = "S ${shLabels.getOrElse(idx[P.SHUTTER] ?: 0) { "AUTO" }}"
        paramChips[P.FOCUS]?.text = "MF ${foLabels.getOrElse(idx[P.FOCUS] ?: 0) { "AUTO" }}"
    }

    private fun setIndexAuto(p: P) {
        idx[p] = 0
        engine.setFocus(null)
        if (sel == p) ruler.setIndex(0)
        refreshChips()
    }

    private fun onRuler(i: Int) {
        idx[sel] = i
        when (sel) {
            P.EV -> engine.setEv(evVals[i])
            P.ISO -> applyIso(i)
            P.SHUTTER -> applyShutter(i)
            P.FOCUS -> engine.setFocus(foVals[i])
        }
        refreshChips()
    }

    private fun applyIso(i: Int) {
        idx[P.ISO] = i
        pushExposure()
    }

    private fun applyShutter(i: Int) {
        idx[P.SHUTTER] = i
        pushExposure()
    }

    /** ISO and shutter are independent; index 0 = AUTO for each one. */
    private fun pushExposure() {
        engine.setExposure(isoVals[idx[P.ISO] ?: 0], shVals[idx[P.SHUTTER] ?: 0])
        updateModeChips()
    }

    private fun onFast() {
        val i = shVals.indexOfFirst { it == FAST_NS }
        if (i < 0) {
            Toast.makeText(this, "1/300 is not supported by this lens", Toast.LENGTH_SHORT).show()
            return
        }
        idx[P.ISO] = 0
        idx[P.SHUTTER] = i
        pushExposure()
        ruler.items = labels(sel)
        ruler.setIndex(idx[sel] ?: 0)
        refreshChips()
    }

    private fun onNormal() {
        idx[P.ISO] = 0
        idx[P.SHUTTER] = 0
        pushExposure()
        ruler.setIndex(idx[sel] ?: 0)
        refreshChips()
    }

    private fun updateModeChips() {
        val isoAuto = (idx[P.ISO] ?: 0) == 0
        val shIdx = idx[P.SHUTTER] ?: 0
        setActive(fastChip, isoAuto && shVals.getOrNull(shIdx) == FAST_NS)
        setActive(normalChip, isoAuto && shIdx == 0)
    }

    private fun setFormat(f: Int) {
        engine.setFormat(f)
        prefs.edit().putInt("format", f).apply()
        refreshFormatUi()
    }

    private fun refreshFormatUi() {
        val f = engine.s.format
        for (i in fmtChips.indices) setActive(fmtChips[i], i == f)
        filmChip.alpha = if (f == FMT_JPG) 0.35f else 1f
        val film = engine.s.film
        filmChip.text = "Film: " + (film?.name ?: "Off")
        setActive(filmChip, film != null && f != FMT_JPG)
    }

    private fun pickFilm() {
        if (engine.s.format == FMT_JPG) {
            Toast.makeText(this, "Film emulation needs RAW: choose RAW or JPG+RAW", Toast.LENGTH_SHORT).show()
            return
        }
        val names = arrayOf("Off") + FilmEmulator.looks.map { "${it.name} - ${it.desc}" }
        val cur = FilmEmulator.looks.indexOfFirst { it === engine.s.film } + 1
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Film emulation (develops RAW to an extra JPG)")
            .setSingleChoiceItems(names, cur) { d, which ->
                val look = if (which == 0) null else FilmEmulator.looks[which - 1]
                engine.setFilm(look)
                prefs.edit().putString("film", look?.name).apply()
                refreshFormatUi()
                d.dismiss()
            }
            .show()
    }

    private fun resetAll() {
        idx[P.EV] = evVals.indexOf(0).coerceAtLeast(0)
        idx[P.ISO] = 0
        idx[P.SHUTTER] = 0
        idx[P.FOCUS] = 0
        engine.setEv(0)
        engine.setExposure(null, null)
        engine.setFocus(null)
        engine.resetAf()
        ruler.setIndex(idx[sel] ?: 0)
        refreshChips()
        updateModeChips()
    }

    private fun buildRulers(ci: CamInfo) {
        // EV
        val step = if (ci.evStep > 0f) ci.evStep else 0.33f
        val stride = max(1, Math.ceil(0.3 / step).toInt())
        val evI = (ci.evRange.lower..ci.evRange.upper).filter { it % stride == 0 }
        evVals = if (evI.isEmpty()) listOf(0) else evI
        evLabels = evVals.map { v ->
            val e = v * step
            if (v == 0) "0" else String.format(Locale.US, "%+.1f", e)
        }
        // ISO
        val stdIso = intArrayOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
            1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000)
        val isoList = stdIso.filter { it >= ci.isoRange.lower && it <= ci.isoRange.upper }.toMutableList()
        if (isoList.isEmpty()) isoList.add(ci.isoRange.lower)
        isoVals = listOf<Int?>(null) + isoList
        isoLabels = listOf("AUTO") + isoList.map { it.toString() }
        // Shutter
        val fr = intArrayOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400,
            320, 300, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4)
        val secs = doubleArrayOf(0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0)
        val sv = ArrayList<Long>()
        val sl = ArrayList<String>()
        for (d in fr) {
            val ns = if (d == 300) FAST_NS else (1e9 / d).toLong()
            if (ns in ci.expRange.lower..ci.expRange.upper) { sv.add(ns); sl.add("1/$d") }
        }
        for (x in secs) {
            val ns = (x * 1e9).toLong()
            if (ns in ci.expRange.lower..ci.expRange.upper) {
                sv.add(ns)
                sl.add(if (x == Math.floor(x)) "${x.toInt()}\"" else String.format(Locale.US, "%.1f\"", x))
            }
        }
        if (sv.isEmpty()) { sv.add(ci.expRange.lower); sl.add("min") }
        shVals = listOf<Long?>(null) + sv
        shLabels = listOf("AUTO") + sl
        // Manual focus
        val fv = ArrayList<Float?>(); val fl = ArrayList<String>()
        fv.add(null); fl.add("AUTO")
        if (ci.minFocusDiopter > 0f) {
            val n = 24
            for (i in 0..n) {
                val d = ci.minFocusDiopter * (1f - i / n.toFloat())
                fv.add(d)
                fl.add(if (i == n) "∞" else {
                    val m = 1f / d
                    if (m < 1f) "${(m * 100).roundToInt()}cm" else String.format(Locale.US, "%.1fm", m)
                })
            }
        }
        foVals = fv
        foLabels = fl

        idx[P.EV] = evVals.indexOf(0).coerceAtLeast(0)
        idx[P.ISO] = 0
        idx[P.SHUTTER] = 0
        idx[P.FOCUS] = 0
        engine.s.evIndex = 0
        engine.s.iso = null
        engine.s.expNs = null
        engine.s.focus = null
        engine.s.afRegion = null
        selectParam(sel)
        refreshChips()
    }

    // ------------------------------------------------------------------ capture

    private fun onShutterClick() {
        if (busy) return
        busy = true
        shutter.busy = true
        val so = info?.sensorOrientation ?: 90
        engine.capture((so + deviceDeg) % 360)
    }

    // ------------------------------------------------------------------ engine callbacks (main thread)

    override fun onCameraReady(info: CamInfo) {
        this.info = info
        // previewSize is landscape; viewfinder is portrait
        box.aspect = info.previewSize.height.toFloat() / info.previewSize.width
        buildRulers(info)
        updateModeChips()
        startHistogram()
    }

    override fun onLiveReadout(iso: Int, exposureNs: Long) {
        val sh = if (exposureNs >= 500_000_000L) String.format(Locale.US, "%.1f\"", exposureNs / 1e9)
        else "1/${max(1L, (1e9 / exposureNs).roundToInt().toLong())}"
        val ri = info?.rawSize
        readout.text = "ISO $iso   $sh   ${curLens?.mmLabel ?: ""}" +
            (if (ri != null) "   ${ri.width}x${ri.height}" else "")
    }

    override fun onShutter() {
        flash.animate().cancel()
        flash.alpha = 0.85f
        flash.animate().alpha(0f).setDuration(220).start()
    }

    override fun onSaved(uri: Uri) {
        busy = false
        shutter.busy = false
        showThumb(uri)
    }

    override fun onError(msg: String) {
        busy = false
        shutter.busy = false
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ gallery thumb

    private fun showThumb(uri: Uri) {
        lastThumbUri = uri
        thread {
            val bmp: Bitmap? = try { contentResolver.loadThumbnail(uri, Size(256, 256), null) } catch (_: Exception) { null }
            runOnUiThread { if (bmp != null) thumb.setImageBitmap(bmp) else thumb.setBackgroundColor(ACCENT) }
        }
    }

    private fun loadLatest() {
        thread {
            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                    arrayOf("Pictures/RawCam%"),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val u = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                        runOnUiThread { showThumb(u) }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun openLast() {
        val u = lastThumbUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/x-adobe-dng")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, "No app to open DNG", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ settings / histogram

    private fun showSettings() {
        val names = arrayOf(
            "Embed lens shading map (metadata only)",
            "Optical stabilization (OIS)",
            "Grid",
            "Histogram"
        )
        val state = booleanArrayOf(
            engine.s.shadingMap, engine.s.ois,
            prefs.getBoolean("grid", true), prefs.getBoolean("hist", false)
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Settings")
            .setMultiChoiceItems(names, state) { _, which, checked ->
                state[which] = checked
                when (which) {
                    0 -> { engine.setShadingMap(checked); prefs.edit().putBoolean("shading", checked).apply() }
                    1 -> { engine.setOis(checked); prefs.edit().putBoolean("ois", checked).apply() }
                    2 -> { grid.visibility = if (checked) View.VISIBLE else View.GONE; prefs.edit().putBoolean("grid", checked).apply() }
                    3 -> {
                        hist.visibility = if (checked) View.VISIBLE else View.GONE
                        prefs.edit().putBoolean("hist", checked).apply()
                        if (checked) startHistogram()
                    }
                }
            }
            .setPositiveButton("OK", null)
            .setNeutralButton("Diagnostics") { _, _ ->
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Cameras")
                    .setMessage(engine.diagnostics.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
            .show()
    }

    private fun startHistogram() {
        if (histRunning || hist.visibility != View.VISIBLE) return
        histRunning = true
        val loop = object : Runnable {
            override fun run() {
                if (!histRunning || hist.visibility != View.VISIBLE) { histRunning = false; return }
                if (texture.isAvailable) {
                    val bmp = texture.getBitmap(96, 128)
                    if (bmp != null) {
                        thread {
                            val px = IntArray(bmp.width * bmp.height)
                            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                            val bins = IntArray(64)
                            for (p in px) {
                                val y = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
                                bins[min(63, y / 4)]++
                            }
                            runOnUiThread { hist.setBins(bins) }
                        }
                    }
                }
                ui.postDelayed(this, 400)
            }
        }
        ui.post(loop)
    }
}
