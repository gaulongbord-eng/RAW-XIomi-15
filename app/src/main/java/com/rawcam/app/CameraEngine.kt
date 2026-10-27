package com.rawcam.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val FMT_RAW = 0
const val FMT_JPG = 1
const val FMT_BOTH = 2

data class Route(val openId: String, val physicalId: String?)

class Lens(val chars: CameraCharacteristics, val routes: MutableList<Route>, val equivMm: Float) {
    var label = ""
    var mmLabel = ""
    var sensorKey = ""
}

data class CamInfo(
    val rawSize: Size,
    val previewSize: Size,
    val isoRange: Range<Int>,
    val expRange: Range<Long>,
    val minFocusDiopter: Float,
    val evRange: Range<Int>,
    val evStep: Float,
    val sensorOrientation: Int
)

class CamSettings {
    @Volatile var evIndex = 0
    @Volatile var iso: Int? = null        // null = auto
    @Volatile var expNs: Long? = null     // null = auto
    @Volatile var focus: Float? = null
    @Volatile var shadingMap = true
    @Volatile var ois = true
    @Volatile var afRegion: MeteringRectangle? = null
    @Volatile var format = FMT_RAW
    @Volatile var film: FilmLook? = null
}

private class CaptureTag(
    val deg: Int, val lens: Lens, val physicalId: String?,
    val needRaw: Boolean, val needJpeg: Boolean, val film: FilmLook?
)

/**
 * Camera2 engine. Preview = normal stream. RAW shot = RAW_SENSOR stream -> DNG (DngCreator) with
 * all optional ISP stages disabled. JPG mode = regular processed JPEG stream.
 * ISO and shutter are independent: if one is fixed and the other is AUTO, the auto one is computed
 * from the camera's own AE solution so that exposure stays correct.
 */
class CameraEngine(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onCameraReady(info: CamInfo)
        fun onLiveReadout(iso: Int, exposureNs: Long)
        fun onShutter()
        fun onSaved(uri: Uri)
        fun onError(msg: String)
    }

    val s = CamSettings()
    val diagnostics = StringBuilder()

    @Volatile private var aeIso = 100
    @Volatile private var aeExp = 10_000_000L
    @Volatile private var isoR: Range<Int> = Range(50, 3200)
    @Volatile private var expR: Range<Long> = Range(100_000L, 1_000_000_000L)

    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("RawCamEngine").also { it.start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val saver = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private var gen = 0
    private var cfgToken = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var lens: Lens? = null
    private var routeIdx = 0
    private var route: Route? = null
    private var openChars: CameraCharacteristics? = null
    private var lastReadoutPost = 0L
    private var jpegCapMp = 0

    private val rawQ = TreeMap<Long, Image>()
    private val jpgQ = TreeMap<Long, Image>()
    private val resQ = TreeMap<Long, TotalCaptureResult>()

    // ---------------------------------------------------------------- discovery

    private fun sensorKey(c: CameraCharacteristics): String {
        val pa = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val ps = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return "${pa?.width}x${pa?.height}/${ps?.width?.times(100)?.roundToInt()}x${ps?.height?.times(100)?.roundToInt()}"
    }

    fun discover(): List<Lens> {
        diagnostics.setLength(0)
        val found = ArrayList<Lens>()
        fun rawOk(c: CameraCharacteristics): Boolean {
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return false
            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            return sizes != null && sizes.isNotEmpty()
        }

        fun add(c: CameraCharacteristics, r: Route) {
            val mm = equivMm(c)
            val dup = found.firstOrNull { abs(it.equivMm - mm) / max(1f, mm) < 0.06f }
            if (dup != null) {
                if (dup.routes.none { it == r }) dup.routes.add(r)
            } else {
                found.add(Lens(c, mutableListOf(r), mm).also { it.sensorKey = sensorKey(c) })
            }
        }

        val ids = mgr.cameraIdList
        diagnostics.append("Camera ids: ${ids.joinToString()}\n")
        for (id in ids) {
            val c = mgr.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val phys = c.physicalCameraIds
            diagnostics.append("id=$id equiv=${equivMm(c).roundToInt()}mm sensor=${sensorKey(c)} " +
                "raw=${rawOk(c)} physical=[${phys.joinToString()}]\n")
            if (rawOk(c)) add(c, Route(id, null))
            for (pid in phys) {
                try {
                    val pc = mgr.getCameraCharacteristics(pid)
                    diagnostics.append("  phys=$pid equiv=${equivMm(pc).roundToInt()}mm sensor=${sensorKey(pc)} raw=${rawOk(pc)}\n")
                    if (!rawOk(pc)) continue
                    add(pc, Route(pid, null))   // true hardware stream: open the physical id directly
                    add(pc, Route(id, pid))     // fallback: physical stream through the logical camera
                } catch (e: Exception) {
                    diagnostics.append("  phys=$pid unavailable: ${e.javaClass.simpleName}\n")
                }
            }
        }
        found.sortBy { it.equivMm }

        // A "lens" that shares the sensor of a wider lens but has a longer focal length is only a
        // digital crop of that sensor -> not a real telephoto, hide it.
        val kept = ArrayList<Lens>()
        for (l in found) {
            val twin = kept.firstOrNull { it.sensorKey == l.sensorKey && l.equivMm > it.equivMm * 1.06f }
            if (twin != null) {
                diagnostics.append("HIDDEN ${l.equivMm.roundToInt()}mm: same sensor as ${twin.equivMm.roundToInt()}mm (digital crop)\n")
            } else {
                kept.add(l)
            }
        }
        if (kept.isEmpty()) return kept
        val mainLens = kept.minByOrNull { abs(it.equivMm - 24f) }!!
        for (l in kept) {
            val ratio = l.equivMm / mainLens.equivMm
            val near = ratio.roundToInt()
            l.label = if (abs(ratio - near) < 0.12f && near >= 1) "${near}x"
            else String.format(Locale.US, "%.1fx", ratio)
            l.mmLabel = "${l.equivMm.roundToInt()}mm"
            diagnostics.append("LENS ${l.label} ${l.mmLabel} routes=${l.routes.joinToString { "${it.openId}/${it.physicalId}" }}\n")
        }
        return kept
    }

    private fun equivMm(c: CameraCharacteristics): Float {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return f
        return f * 43.27f / hypot(sz.width, sz.height)
    }

    // ---------------------------------------------------------------- open / close

    fun open(l: Lens, st: SurfaceTexture) {
        handler.post {
            closeInternal()
            lens = l
            surfaceTexture = st
            routeIdx = 0
            jpegCapMp = 0
            openRoute()
        }
    }

    fun close() {
        handler.post { closeInternal() }
    }

    fun release() {
        handler.post {
            closeInternal()
            thread.quitSafely()
        }
        saver.shutdown()
    }

    private fun err(msg: String) = main.post { listener.onError(msg) }

    private fun openRoute() {
        val l = lens ?: return
        if (routeIdx >= l.routes.size) {
            err("Cannot open this lens as a real hardware stream (HyperOS does not expose it to third-party apps).")
            return
        }
        val r = l.routes[routeIdx]
        val myGen = gen
        try {
            mgr.openCamera(r.openId, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    if (myGen != gen) {
                        cd.close()
                        return
                    }
                    device = cd
                    route = r
                    try {
                        createSession(r)
                    } catch (e: Exception) {
                        nextRoute(myGen)
                    }
                }

                override fun onDisconnected(cd: CameraDevice) {
                    cd.close()
                    if (myGen == gen) {
                        device = null
                        nextRoute(myGen)
                    }
                }

                override fun onError(cd: CameraDevice, error: Int) {
                    cd.close()
                    if (myGen == gen) {
                        device = null
                        nextRoute(myGen)
                    }
                }
            }, handler)
        } catch (e: Exception) {
            nextRoute(myGen)
        }
    }

    private fun nextRoute(g: Int) {
        if (g != gen) return
        session?.close()
        session = null
        device?.close()
        device = null
        routeIdx++
        jpegCapMp = 0
        openRoute()
    }

    private fun closeInternal() {
        gen++
        cfgToken++
        releaseOutputs()
        try { device?.close() } catch (_: Exception) {}
        device = null
    }

    private fun releaseOutputs() {
        try { session?.close() } catch (_: Exception) {}
        try { rawReader?.close() } catch (_: Exception) {}
        try { jpegReader?.close() } catch (_: Exception) {}
        try { previewSurface?.release() } catch (_: Exception) {}
        session = null
        rawReader = null
        jpegReader = null
        previewSurface = null
        synchronized(lock) {
            rawQ.values.forEach { try { it.close() } catch (_: Exception) {} }
            jpgQ.values.forEach { try { it.close() } catch (_: Exception) {} }
            rawQ.clear(); jpgQ.clear(); resQ.clear()
        }
    }

    // ---------------------------------------------------------------- session

    private fun createSession(r: Route) {
        val l = lens ?: return
        val cd = device ?: return
        val st = surfaceTexture ?: return
        val myGen = gen
        val token = ++cfgToken
        val fmt = s.format
        val wantRaw = fmt != FMT_JPG
        val wantJpeg = fmt != FMT_RAW

        val rawChars = l.chars
        val oChars = mgr.getCameraCharacteristics(r.openId)
        openChars = oChars
        val rawMap = rawChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        val rawSize = rawMap.getOutputSizes(ImageFormat.RAW_SENSOR)
            .maxByOrNull { it.width.toLong() * it.height }!!
        val ar = rawSize.width.toFloat() / rawSize.height

        val pSizes = (if (r.physicalId == null) oChars else rawChars)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(SurfaceTexture::class.java)
        val pv = pSizes.filter {
            abs(it.width.toFloat() / it.height - ar) < 0.02f && it.width * it.height <= 1920 * 1440
        }.maxByOrNull { it.width * it.height }
            ?: pSizes.filter { it.width * it.height <= 1920 * 1080 }.maxByOrNull { it.width * it.height }
            ?: pSizes.first()

        isoR = rawChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
        expR = rawChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)

        // fresh outputs
        releaseOutputs()
        cfgToken = token
        st.setDefaultBufferSize(pv.width, pv.height)
        val ps = Surface(st)
        previewSurface = ps

        val prevCfg = OutputConfiguration(ps)
        if (r.physicalId != null) prevCfg.setPhysicalCameraId(r.physicalId)
        val outs = ArrayList<OutputConfiguration>()
        outs.add(prevCfg)

        if (wantRaw) {
            val rd = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3)
            rd.setOnImageAvailableListener({ onImage(it, true) }, handler)
            rawReader = rd
            val cfg = OutputConfiguration(rd.surface)
            if (r.physicalId != null) cfg.setPhysicalCameraId(r.physicalId)
            outs.add(cfg)
        }
        if (wantJpeg) {
            val jSizes = rawMap.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            var cand = jSizes.filter { abs(it.width.toFloat() / it.height - ar) < 0.02f }
            if (jpegCapMp > 0) cand = cand.filter { it.width.toLong() * it.height <= jpegCapMp * 1_000_000L }
            val js = cand.maxByOrNull { it.width.toLong() * it.height } ?: rawSize
            val jr = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 3)
            jr.setOnImageAvailableListener({ onImage(it, false) }, handler)
            jpegReader = jr
            val cfg = OutputConfiguration(jr.surface)
            if (r.physicalId != null) cfg.setPhysicalCameraId(r.physicalId)
            outs.add(cfg)
        }

        val exec = Executor { handler.post(it) }
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outs, exec,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    if (myGen != gen || token != cfgToken) {
                        cs.close()
                        return
                    }
                    session = cs
                    updateRepeating()
                    val info = CamInfo(
                        rawSize = rawSize,
                        previewSize = pv,
                        isoRange = isoR,
                        expRange = expR,
                        minFocusDiopter = rawChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                        evRange = oChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
                        evStep = oChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.33f,
                        sensorOrientation = rawChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                    )
                    main.post { listener.onCameraReady(info) }
                }

                override fun onConfigureFailed(cs: CameraCaptureSession) {
                    cs.close()
                    if (myGen != gen || token != cfgToken) return
                    if (wantJpeg && jpegCapMp == 0) {
                        // very large JPEG + RAW may not be a supported combination: retry smaller
                        jpegCapMp = 13
                        try { createSession(r) } catch (e: Exception) { nextRoute(myGen) }
                    } else {
                        nextRoute(myGen)
                    }
                }
            }
        )
        cd.createCaptureSession(cfg)
    }

    // ---------------------------------------------------------------- exposure logic

    /** Final ISO / exposure that will be used (also what the UI shows). */
    private fun effective(): Pair<Int, Long> {
        val iso = s.iso
        val exp = s.expNs
        if (iso != null && exp != null) return iso to exp
        val total = aeIso.toDouble() * aeExp.toDouble()
        if (iso != null) {
            val e = (total / iso).toLong().coerceIn(expR.lower, expR.upper)
            return iso to e
        }
        if (exp != null) {
            val i = (total / exp).roundToInt().coerceIn(isoR.lower, isoR.upper)
            return i to exp
        }
        return aeIso to aeExp
    }

    private fun IntArray?.has(v: Int) = this?.contains(v) == true

    private fun applyCommon(b: CaptureRequest.Builder, capture: Boolean) {
        val c = openChars ?: return
        val iso = s.iso
        val exp = s.expNs
        val fmt = s.format

        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        val bothManual = iso != null && exp != null
        val semiOnCapture = capture && (iso != null || exp != null)
        if (bothManual || semiOnCapture) {
            val (i0, e0) = effective()
            var i = i0
            var e = e0
            if (!capture && e > 100_000_000L) {
                // keep the viewfinder alive on long shutters, compensate brightness with ISO
                val f = e / 100_000_000.0
                e = 100_000_000L
                i = min(isoR.upper.toDouble(), i0 * f).toInt()
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, i)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
            var fd = max(e, 33_333_333L)
            val maxFd = lens?.chars?.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            if (maxFd != null) fd = min(fd, maxFd)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, fd)
        } else {
            // full auto, or preview of a semi-auto mode (camera AE gives the reference exposure)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.evIndex)
        }

        val fdist = s.focus
        val region = s.afRegion
        if (fdist != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, fdist)
        } else if (region != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            if (iso == null && exp == null) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        if (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                .has(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON))
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (s.ois) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)

        // RAW-only mode: switch off every optional in-camera processing step.
        // (RAW+JPG shares one request, so the JPEG keeps normal processing there.)
        if (fmt == FMT_RAW) {
            if (c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                    .has(CaptureRequest.NOISE_REDUCTION_MODE_OFF))
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            if (c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES).has(CaptureRequest.EDGE_MODE_OFF))
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            if (c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
                    .has(CaptureRequest.HOT_PIXEL_MODE_OFF))
                b.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
            if (c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES).has(CaptureRequest.SHADING_MODE_OFF))
                b.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
            if (c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
                    .has(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF))
                b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
            if (c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                    .has(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF))
                b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
        }
        if (fmt != FMT_JPG &&
            c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                .has(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON))
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                if (s.shadingMap) CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                else CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF)
    }

    private val previewCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            if (s.iso == null || s.expNs == null) {   // preview is running on camera AE
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { aeIso = it }
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { aeExp = it }
            }
            val now = System.currentTimeMillis()
            if (now - lastReadoutPost > 250) {
                lastReadoutPost = now
                val (i, e) = effective()
                main.post { listener.onLiveReadout(i, e) }
            }
        }
    }

    private fun updateRepeating() {
        val cd = device ?: return
        val ses = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            applyCommon(b, false)
            ses.setRepeatingRequest(b.build(), previewCb, handler)
        } catch (e: Exception) {
            err("Preview error: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- settings API

    fun setEv(index: Int) { s.evIndex = index; handler.post { updateRepeating() } }

    /** null = auto. Each value is independent. */
    fun setExposure(iso: Int?, expNs: Long?) {
        s.iso = iso
        s.expNs = expNs
        handler.post { updateRepeating() }
    }

    fun setFocus(diopter: Float?) {
        s.focus = diopter
        if (diopter != null) s.afRegion = null
        handler.post { updateRepeating() }
    }

    fun setShadingMap(on: Boolean) { s.shadingMap = on; handler.post { updateRepeating() } }
    fun setOis(on: Boolean) { s.ois = on; handler.post { updateRepeating() } }
    fun setFilm(f: FilmLook?) { s.film = f }

    /** Reconfigure the session for RAW / JPG / RAW+JPG. */
    fun setFormat(fmt: Int) {
        s.format = fmt
        handler.post {
            val r = route
            if (device != null && r != null) {
                try { createSession(r) } catch (e: Exception) { err("Format switch failed: ${e.message}") }
            }
        }
    }

    fun resetAf() {
        s.afRegion = null
        handler.post { updateRepeating() }
    }

    /** nx, ny: touch position inside the (portrait) preview, 0..1. */
    fun tapFocus(nx: Float, ny: Float) {
        handler.post {
            val c = openChars ?: return@post
            val cd = device ?: return@post
            val ses = session ?: return@post
            val ps = previewSurface ?: return@post
            val ar = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
            val orient = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val sx: Float
            val sy: Float
            if (orient == 270) {
                sx = ar.left + (1f - ny) * ar.width()
                sy = ar.top + nx * ar.height()
            } else {
                sx = ar.left + ny * ar.width()
                sy = ar.top + (1f - nx) * ar.height()
            }
            val half = (ar.width() / 12f).toInt()
            val left = (sx - half).toInt().coerceIn(ar.left, ar.right - 2 * half)
            val top = (sy - half).toInt().coerceIn(ar.top, ar.bottom - 2 * half)
            s.focus = null
            s.afRegion = MeteringRectangle(left, top, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1)
            try {
                val b = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(ps)
                applyCommon(b, false)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                ses.capture(b.build(), null, handler)
            } catch (_: Exception) {
            }
            updateRepeating()
        }
    }

    // ---------------------------------------------------------------- capture

    /** deg = clockwise rotation (0/90/180/270) needed to make the picture upright. */
    fun capture(deg: Int) {
        handler.post {
            val cd = device
            val ses = session
            val l = lens
            val fmt = s.format
            val rr = rawReader
            val jr = jpegReader
            if (cd == null || ses == null || l == null ||
                (fmt != FMT_JPG && rr == null) || (fmt != FMT_RAW && jr == null)) {
                err("Camera not ready")
                return@post
            }
            try {
                val b = cd.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                if (fmt != FMT_JPG) b.addTarget(rr!!.surface)
                if (fmt != FMT_RAW) {
                    b.addTarget(jr!!.surface)
                    b.set(CaptureRequest.JPEG_ORIENTATION, deg)
                    b.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
                }
                applyCommon(b, true)
                b.setTag(CaptureTag(deg, l, route?.physicalId, fmt != FMT_JPG, fmt != FMT_RAW,
                    if (fmt != FMT_JPG) s.film else null))
                ses.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long
                    ) {
                        main.post { listener.onShutter() }
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                    ) {
                        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        synchronized(lock) {
                            resQ[ts] = result
                            pair()
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure
                    ) {
                        err("Capture failed")
                    }
                }, handler)
            } catch (e: Exception) {
                err("Capture error: ${e.message}")
            }
        }
    }

    private fun onImage(r: ImageReader, isRaw: Boolean) {
        val img = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return
        synchronized(lock) {
            (if (isRaw) rawQ else jpgQ)[img.timestamp] = img
            pair()
        }
    }

    // must be called with `lock` held
    private fun pair() {
        val it = resQ.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val res = e.value
            val tag = res.request.tag as? CaptureTag ?: continue
            if ((tag.needRaw && !rawQ.containsKey(e.key)) || (tag.needJpeg && !jpgQ.containsKey(e.key))) continue
            it.remove()
            val raw = if (tag.needRaw) rawQ.remove(e.key) else null
            val jpg = if (tag.needJpeg) jpgQ.remove(e.key) else null
            saver.execute { save(raw, jpg, res, tag) }
        }
        while (rawQ.size > 3) rawQ.pollFirstEntry()?.value?.close()
        while (jpgQ.size > 3) jpgQ.pollFirstEntry()?.value?.close()
        while (resQ.size > 4) resQ.pollFirstEntry()
    }

    // ---------------------------------------------------------------- saving

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun store(name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RawCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { write(it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun exifOf(deg: Int) = when (deg) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 }

    private fun save(raw: Image?, jpg: Image?, res: TotalCaptureResult, tag: CaptureTag) {
        val stamp = stamp()
        try {
            if (jpg != null) {
                try {
                    val buf = jpg.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    val uri = store("IMG_$stamp.jpg", "image/jpeg") { it.write(bytes) }
                    main.post { listener.onSaved(uri) }
                } catch (e: Exception) {
                    err("JPG save failed: ${e.message}")
                } finally {
                    try { jpg.close() } catch (_: Exception) {}
                }
            }
            if (raw != null) {
                val meta: CaptureResult = tag.physicalId?.let { res.physicalCameraTotalResults[it] } ?: res
                checkLens(tag, meta)
                var dng: DngCreator? = null
                try {
                    dng = DngCreator(tag.lens.chars, meta)
                    dng.setOrientation(exifOf(tag.deg))
                    val d = dng
                    val uri = store("RAW_$stamp.dng", "image/x-adobe-dng") { d.writeImage(it, raw) }
                    main.post { listener.onSaved(uri) }
                } catch (e: Exception) {
                    err("DNG save failed: ${e.message}")
                } finally {
                    try { dng?.close() } catch (_: Exception) {}
                }
                val film = tag.film
                if (film != null) {
                    try {
                        val bmp: Bitmap = FilmEmulator.develop(raw, meta, tag.lens.chars, film, tag.deg)
                        val uri = store("FILM_${film.name.replace(' ', '_')}_$stamp.jpg", "image/jpeg") {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
                        }
                        bmp.recycle()
                        main.post { listener.onSaved(uri) }
                    } catch (e: Throwable) {
                        err("Film develop failed: ${e.message}")
                    }
                }
            }
        } finally {
            try { raw?.close() } catch (_: Exception) {}
            try { jpg?.close() } catch (_: Exception) {}
        }
    }

    /** Warns if the HAL delivered a different focal length than the selected lens (digital crop). */
    private fun checkLens(tag: CaptureTag, meta: CaptureResult) {
        val got = meta.get(CaptureResult.LENS_FOCAL_LENGTH) ?: return
        val want = tag.lens.chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return
        if (abs(got - want) / want > 0.12f) {
            err("Warning: expected focal length %.1f mm but camera reported %.1f mm - this lens may be a crop of another camera".format(Locale.US, want, got))
        }
    }
}
