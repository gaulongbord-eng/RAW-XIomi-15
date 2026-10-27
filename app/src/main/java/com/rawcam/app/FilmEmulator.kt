package com.rawcam.app

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class FilmLook(
    val name: String,
    val desc: String,
    val bw: Boolean = false,
    val exposure: Float = 0f,        // EV shift
    val contrast: Float,             // 0..1 S-curve strength
    val saturation: Float,
    val gain: FloatArray = floatArrayOf(1f, 1f, 1f),       // linear RGB cast
    val shadow: FloatArray = floatArrayOf(0f, 0f, 0f),     // tint added in shadows (display space)
    val highlight: FloatArray = floatArrayOf(0f, 0f, 0f),  // tint added in highlights
    val fade: Float = 0f,            // lifted blacks
    val grain: Float,                // grain amplitude
    val grainSize: Float,            // relative grain size
    val shoulder: Float = 0.5f,      // highlight roll-off 0..1
    val vignette: Float = 0f,
    val bwMix: FloatArray = floatArrayOf(0.30f, 0.50f, 0.20f),
    val colorGrain: Boolean = true
)

/**
 * Develops a Bayer RAW16 frame into an sRGB JPEG-ready bitmap and applies a film look:
 * bilinear demosaic -> white balance -> camera->sRGB matrix -> tone/colour/grain of the stock.
 */
object FilmEmulator {

    val looks: List<FilmLook> = listOf(
        FilmLook("Portra 160", "Soft pastel, low contrast, fine grain",
            contrast = 0.22f, saturation = 0.86f, gain = floatArrayOf(1.02f, 1.0f, 0.98f),
            shadow = floatArrayOf(0f, 0.008f, 0.014f), highlight = floatArrayOf(0.018f, 0.008f, -0.008f),
            fade = 0.02f, grain = 0.030f, grainSize = 1.1f, shoulder = 0.6f),
        FilmLook("Portra 400", "Warm skin tones, gentle contrast",
            contrast = 0.30f, saturation = 0.95f, gain = floatArrayOf(1.03f, 1.0f, 0.96f),
            shadow = floatArrayOf(0f, 0.008f, 0.012f), highlight = floatArrayOf(0.02f, 0.01f, -0.01f),
            fade = 0.018f, grain = 0.048f, grainSize = 1.4f, shoulder = 0.6f),
        FilmLook("Portra 800", "Low-light portrait stock, visible grain",
            exposure = 0.15f, contrast = 0.34f, saturation = 1.0f, gain = floatArrayOf(1.04f, 1.0f, 0.95f),
            shadow = floatArrayOf(0.008f, 0.02f, 0.022f), highlight = floatArrayOf(0.025f, 0.01f, -0.012f),
            fade = 0.02f, grain = 0.080f, grainSize = 1.8f, shoulder = 0.6f, vignette = 0.08f),
        FilmLook("Ektar 100", "Vivid saturated colour, punchy contrast",
            contrast = 0.52f, saturation = 1.35f, gain = floatArrayOf(1.03f, 0.99f, 0.97f),
            fade = 0.0f, grain = 0.022f, grainSize = 1.0f, shoulder = 0.35f),
        FilmLook("Gold 200", "Golden warm everyday colour",
            contrast = 0.38f, saturation = 1.15f, gain = floatArrayOf(1.07f, 1.01f, 0.89f),
            highlight = floatArrayOf(0.03f, 0.015f, -0.02f), shadow = floatArrayOf(0.005f, 0.0f, -0.005f),
            fade = 0.015f, grain = 0.052f, grainSize = 1.3f, shoulder = 0.5f, vignette = 0.06f),
        FilmLook("ColorPlus 200", "Consumer film: warm, green shadows, nostalgic",
            contrast = 0.42f, saturation = 1.05f, gain = floatArrayOf(1.05f, 1.0f, 0.92f),
            shadow = floatArrayOf(0.0f, 0.014f, 0.004f), highlight = floatArrayOf(0.02f, 0.012f, -0.012f),
            fade = 0.035f, grain = 0.062f, grainSize = 1.4f, shoulder = 0.5f, vignette = 0.10f),
        FilmLook("Ektachrome E100", "Clean slide film, cool blue-biased, deep blacks",
            contrast = 0.56f, saturation = 1.2f, gain = floatArrayOf(0.97f, 1.0f, 1.05f),
            fade = 0.0f, grain = 0.020f, grainSize = 1.0f, shoulder = 0.2f),
        FilmLook("Kodachrome 64", "Rich reds, deep contrast, classic slide look",
            contrast = 0.62f, saturation = 1.25f, gain = floatArrayOf(1.06f, 0.99f, 0.95f),
            highlight = floatArrayOf(0.02f, 0.0f, -0.015f), shadow = floatArrayOf(0.006f, 0.0f, 0.004f),
            fade = 0.005f, grain = 0.032f, grainSize = 1.1f, shoulder = 0.25f, vignette = 0.07f),
        FilmLook("Tri-X 400", "Classic black & white, strong grain",
            bw = true, contrast = 0.62f, saturation = 0f, fade = 0.03f, grain = 0.115f, grainSize = 1.7f,
            shoulder = 0.45f, vignette = 0.16f, bwMix = floatArrayOf(0.34f, 0.46f, 0.20f), colorGrain = false),
        FilmLook("Vision3 500T", "Cinema tungsten stock: teal shadows, warm highlights",
            contrast = 0.30f, saturation = 0.92f, gain = floatArrayOf(0.94f, 0.99f, 1.10f),
            shadow = floatArrayOf(-0.008f, 0.016f, 0.03f), highlight = floatArrayOf(0.03f, 0.012f, -0.012f),
            fade = 0.03f, grain = 0.070f, grainSize = 1.7f, shoulder = 0.7f, vignette = 0.09f)
    )

    fun byName(n: String?): FilmLook? = looks.firstOrNull { it.name == n }

    private const val LUT_N = 16384
    private const val LUT_MAX = 4f

    private fun buildLut(look: FilmLook): FloatArray {
        val k = 1.3f
        val norm = 1f - exp(-k)
        return FloatArray(LUT_N) { i ->
            val x = i * LUT_MAX / (LUT_N - 1)
            val tm = (1f - exp(-k * x)) / norm
            val v = (min(x, 1f) * (1f - look.shoulder) + tm * look.shoulder).coerceIn(0f, 1f)
            if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
        }
    }

    private const val NS = 1024

    private fun noiseTile(size: Float): FloatArray {
        val rnd = Random(12345)
        var a = FloatArray(NS * NS) { rnd.nextGaussian().toFloat() }
        val k = max(1, min(5, size.roundToInt()))
        if (k > 1) {
            repeat(2) {
                val b = FloatArray(NS * NS)
                for (y in 0 until NS) for (x in 0 until NS) {
                    var s = 0f
                    for (d in 0 until k) s += a[y * NS + ((x + d) and (NS - 1))]
                    b[y * NS + x] = s / k
                }
                val c = FloatArray(NS * NS)
                for (y in 0 until NS) for (x in 0 until NS) {
                    var s = 0f
                    for (d in 0 until k) s += b[((y + d) and (NS - 1)) * NS + x]
                    c[y * NS + x] = s / k
                }
                a = c
            }
        }
        var m = 0.0
        for (v in a) m += v * v
        val sd = sqrt(m / a.size).toFloat().coerceAtLeast(1e-6f)
        for (i in a.indices) a[i] /= sd
        return a
    }

    fun develop(
        raw: Image, meta: CaptureResult, chars: CameraCharacteristics, look: FilmLook, deg: Int
    ): Bitmap {
        val w = raw.width
        val h = raw.height
        val plane = raw.planes[0]
        val rs = plane.rowStride
        val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

        val cfa = when (chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0) {
            1 -> intArrayOf(1, 0, 2, 1)   // GRBG
            2 -> intArrayOf(1, 2, 0, 1)   // GBRG
            3 -> intArrayOf(2, 1, 1, 0)   // BGGR
            else -> intArrayOf(0, 1, 1, 2) // RGGB  (0=R 1=G 2=B), index = (y&1)*2 + (x&1)
        }
        val white = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
        val pat = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val bl = FloatArray(4) { i -> pat?.getOffsetForIndex(i and 1, i shr 1)?.toFloat() ?: 64f }
        val inv = FloatArray(4) { 1f / max(1f, white - bl[it]) }

        // ---- white balance
        var gr = 1f
        var gb = 1f
        val neutral = meta.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        if (neutral != null && neutral.size >= 3 && neutral[0].toFloat() > 0f && neutral[2].toFloat() > 0f) {
            gr = neutral[1].toFloat() / neutral[0].toFloat()
            gb = neutral[1].toFloat() / neutral[2].toFloat()
        } else {
            meta.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
                if (it.greenEven > 0f) {
                    gr = it.red / it.greenEven
                    gb = it.blue / it.greenEven
                }
            }
        }

        // ---- camera (white balanced) -> linear sRGB
        val x2s = floatArrayOf(
            3.1338561f, -1.6168667f, -0.4906146f,
            -0.9787684f, 1.9161415f, 0.0334540f,
            0.0719453f, -0.2289914f, 1.4052427f
        )
        val m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val fm = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
            ?: chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
        if (fm != null) {
            val f = FloatArray(9) { fm.getElement(it % 3, it / 3).toFloat() }
            for (r in 0..2) for (c in 0..2) {
                var s = 0f
                for (k in 0..2) s += x2s[r * 3 + k] * f[k * 3 + c]
                m[r * 3 + c] = s
            }
        }

        fun rd(x: Int, y: Int): Float {
            val xx = if (x < 0) -x else if (x >= w) 2 * w - 2 - x else x
            val yy = if (y < 0) -y else if (y >= h) 2 * h - 2 - y else y
            val i = ((yy and 1) shl 1) or (xx and 1)
            val v = ((buf.getShort(yy * rs + xx * 2).toInt() and 0xFFFF) - bl[i]) * inv[i]
            return if (v < 0f) 0f else v
        }

        // ---- scene key: log-average of green sites -> partial auto exposure
        var sumLog = 0.0
        var cnt = 0
        var sy = 1
        while (sy < h - 1) {
            var sx = 1
            while (sx < w - 1) {
                if (cfa[((sy and 1) shl 1) or (sx and 1)] == 1) {
                    sumLog += ln(max(rd(sx, sy), 1e-4f).toDouble())
                    cnt++
                }
                sx += 13
            }
            sy += 13
        }
        val avg = if (cnt > 0) exp(sumLog / cnt).toFloat() else 0.1f
        val autoScale = (0.20f / max(avg, 1e-4f)).pow(0.75f).coerceIn(0.4f, 10f) * 2f.pow(look.exposure)

        val lut = buildLut(look)
        val lutScale = (LUT_N - 1) / LUT_MAX
        val noise = noiseTile(look.grainSize * (w / 2200f))
        val gScale = w / 2200f

        val rot = when (deg) { 90 -> 1; 180 -> 2; 270 -> 3; else -> 0 }
        val outW = if (rot == 1 || rot == 3) h else w
        val outH = if (rot == 1 || rot == 3) w else h
        val out = IntArray(outW * outH)
        val cx = w / 2f
        val cy = h / 2f

        val gainR = look.gain[0] * autoScale
        val gainG = look.gain[1] * autoScale
        val gainB = look.gain[2] * autoScale
        val sh = look.shadow
        val hi = look.highlight

        fun row(y: Int) {
            for (x in 0 until w) {
                val c = cfa[((y and 1) shl 1) or (x and 1)]
                val ctr = rd(x, y)
                var r: Float
                var g: Float
                var b: Float
                if (c == 1) {
                    val hAvg = (rd(x - 1, y) + rd(x + 1, y)) * 0.5f
                    val vAvg = (rd(x, y - 1) + rd(x, y + 1)) * 0.5f
                    val hIsRed = cfa[((y and 1) shl 1) or ((x + 1) and 1)] == 0
                    g = ctr
                    if (hIsRed) { r = hAvg; b = vAvg } else { b = hAvg; r = vAvg }
                } else {
                    val cross = (rd(x - 1, y) + rd(x + 1, y) + rd(x, y - 1) + rd(x, y + 1)) * 0.25f
                    val diag = (rd(x - 1, y - 1) + rd(x + 1, y - 1) + rd(x - 1, y + 1) + rd(x + 1, y + 1)) * 0.25f
                    g = cross
                    if (c == 0) { r = ctr; b = diag } else { b = ctr; r = diag }
                }
                r *= gr
                b *= gb
                var lr = m[0] * r + m[1] * g + m[2] * b
                var lg = m[3] * r + m[4] * g + m[5] * b
                var lb = m[6] * r + m[7] * g + m[8] * b
                if (lr < 0f) lr = 0f
                if (lg < 0f) lg = 0f
                if (lb < 0f) lb = 0f
                lr *= gainR; lg *= gainG; lb *= gainB

                if (look.bw) {
                    val yy = look.bwMix[0] * lr + look.bwMix[1] * lg + look.bwMix[2] * lb
                    lr = yy; lg = yy; lb = yy
                }
                var dr = lut[min(LUT_N - 1, (lr * lutScale).toInt())]
                var dg = lut[min(LUT_N - 1, (lg * lutScale).toInt())]
                var db = lut[min(LUT_N - 1, (lb * lutScale).toInt())]

                var l = 0.2126f * dr + 0.7152f * dg + 0.0722f * db
                if (!look.bw) {
                    dr = l + (dr - l) * look.saturation
                    dg = l + (dg - l) * look.saturation
                    db = l + (db - l) * look.saturation
                }
                // S-curve contrast
                dr = dr.coerceIn(0f, 1f); dg = dg.coerceIn(0f, 1f); db = db.coerceIn(0f, 1f)
                dr += look.contrast * (dr * dr * (3f - 2f * dr) - dr)
                dg += look.contrast * (dg * dg * (3f - 2f * dg) - dg)
                db += look.contrast * (db * db * (3f - 2f * db) - db)
                l = 0.2126f * dr + 0.7152f * dg + 0.0722f * db
                // split toning
                val ws = (1f - l) * (1f - l)
                val wh = l * l
                dr += sh[0] * ws + hi[0] * wh
                dg += sh[1] * ws + hi[1] * wh
                db += sh[2] * ws + hi[2] * wh
                // fade
                if (look.fade > 0f) {
                    val f = look.fade
                    dr = f + dr * (1f - f); dg = f + dg * (1f - f); db = f + db * (1f - f)
                }
                // vignette
                if (look.vignette > 0f) {
                    val dx = (x - cx) / cx
                    val dy = (y - cy) / cy
                    val v = 1f - look.vignette * (dx * dx + dy * dy) * 0.5f
                    dr *= v; dg *= v; db *= v
                }
                // grain (strongest in mid-tones)
                if (look.grain > 0f) {
                    val amp = look.grain * (0.35f + 1.3f * 4f * l * (1f - l))
                    val i0 = ((y and (NS - 1)) shl 10) or (x and (NS - 1))
                    val n0 = noise[i0]
                    if (look.colorGrain) {
                        val n1 = noise[(((y + 311) and (NS - 1)) shl 10) or ((x + 517) and (NS - 1))]
                        val n2 = noise[(((y + 673) and (NS - 1)) shl 10) or ((x + 149) and (NS - 1))]
                        dr += n0 * amp; dg += n1 * amp; db += n2 * amp
                    } else {
                        val nn = n0 * amp
                        dr += nn; dg += nn; db += nn
                    }
                }
                val ir = (dr.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val ig = (dg.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val ib = (db.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

                val ox: Int
                val oy: Int
                when (rot) {
                    1 -> { ox = h - 1 - y; oy = x }
                    2 -> { ox = w - 1 - x; oy = h - 1 - y }
                    3 -> { ox = y; oy = w - 1 - x }
                    else -> { ox = x; oy = y }
                }
                out[oy * outW + ox] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
            }
        }

        val nThreads = max(2, min(4, Runtime.getRuntime().availableProcessors()))
        val workers = (0 until nThreads).map { t ->
            Thread { var y = t; while (y < h) { row(y); y += nThreads } }.also { it.start() }
        }
        workers.forEach { it.join() }

        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, outW, 0, 0, outW, outH)
        return bmp
    }
}
