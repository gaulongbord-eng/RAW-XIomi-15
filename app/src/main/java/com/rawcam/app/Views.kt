package com.rawcam.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val ACCENT: Int = Color.parseColor("#FFC400")

fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

/** Frame that keeps a fixed aspect ratio (w/h) and fits inside the space offered by its parent. */
class AspectBox(c: Context) : FrameLayout(c) {
    var aspect = 3f / 4f
        set(v) {
            field = v
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = (w / aspect).toInt()
        if (h > maxH) {
            h = maxH
            w = (h * aspect).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}

class GridView(c: Context) : View(c) {
    private val p = Paint().apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = c.dpf(0.8f)
    }

    override fun onDraw(canvas: Canvas) {
        for (i in 1..2) {
            canvas.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), p)
            canvas.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, p)
        }
    }
}

class HistogramView(c: Context) : View(c) {
    private var bins = IntArray(64)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255) }
    private val bg = Paint().apply { color = Color.argb(90, 0, 0, 0) }
    private val path = Path()

    fun setBins(b: IntArray) {
        bins = b
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val mx = max(1, bins.maxOrNull() ?: 1).toFloat()
        path.reset()
        path.moveTo(0f, height.toFloat())
        for (i in bins.indices) {
            val x = width * i / (bins.size - 1f)
            val y = height - height * (bins[i] / mx) * 0.95f
            path.lineTo(x, y)
        }
        path.lineTo(width.toFloat(), height.toFloat())
        path.close()
        canvas.drawPath(path, fill)
    }
}

class FocusRing(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ACCENT
        strokeWidth = c.dpf(1.8f)
    }
    private var cx = 0f
    private var cy = 0f
    private var scale = 1f
    private var anim: ValueAnimator? = null

    init {
        alpha = 0f
    }

    fun show(x: Float, y: Float) {
        cx = x
        cy = y
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            addUpdateListener {
                val t = it.animatedValue as Float
                scale = 1.5f - 0.5f * min(1f, t * 5f)
                alpha = if (t < 0.7f) 1f else 1f - (t - 0.7f) / 0.3f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = context.dpf(34f) * scale
        canvas.drawCircle(cx, cy, r, p)
        canvas.drawCircle(cx, cy, context.dpf(2f), p.apply { style = Paint.Style.FILL })
        p.style = Paint.Style.STROKE
    }
}

class ShutterButton(c: Context) : View(c) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = c.dpf(4f)
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var pressedScale = 1f

    var busy = false
        set(v) {
            field = v
            inner.color = if (v) Color.argb(120, 255, 255, 255) else Color.WHITE
            invalidate()
        }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedScale = 0.88f
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedScale = 1f
                invalidate()
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - ring.strokeWidth
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx, cy, (r - context.dpf(7f)) * pressedScale, inner)
    }
}

/** Horizontal scrubbing ruler similar to the stock "Pro" mode wheel. */
class RulerView(c: Context) : View(c) {
    var items: List<String> = emptyList()
        set(v) {
            field = v
            pos = pos.coerceIn(0f, max(0, v.size - 1).toFloat())
            invalidate()
        }
    var onChange: ((Int) -> Unit)? = null

    private var pos = 0f
    private var lastReported = -1
    private var lastX = 0f
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null
    private val spacing = c.dpf(58f)

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = c.dpf(1.4f)
        color = Color.WHITE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = c.dpf(13f)
        color = Color.WHITE
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = c.dpf(2.4f)
        color = ACCENT
        strokeCap = Paint.Cap.ROUND
    }

    val index: Int get() = pos.roundToInt()

    fun setIndex(i: Int) {
        anim?.cancel()
        pos = i.coerceIn(0, max(0, items.size - 1)).toFloat()
        lastReported = pos.roundToInt()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                anim?.cancel()
                lastX = e.x
                vt = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                pos = (pos - (e.x - lastX) / spacing).coerceIn(0f, (items.size - 1).toFloat())
                lastX = e.x
                report()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                vt?.recycle()
                vt = null
                val target = (pos - vx / spacing * 0.18f).roundToInt()
                    .coerceIn(0, items.size - 1).toFloat()
                anim = ValueAnimator.ofFloat(pos, target).apply {
                    duration = 220
                    addUpdateListener {
                        pos = it.animatedValue as Float
                        report()
                        invalidate()
                    }
                    start()
                }
            }
        }
        return true
    }

    private fun report() {
        val i = pos.roundToInt()
        if (i != lastReported) {
            lastReported = i
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange?.invoke(i)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val cx = width / 2f
        val half = (width / spacing / 2f).toInt() + 2
        val c0 = pos.roundToInt()
        val topY = context.dpf(8f)
        for (i in max(0, c0 - half)..min(items.size - 1, c0 + half)) {
            val x = cx + (i - pos) * spacing
            val d = abs(i - pos)
            val a = (255 * (1f - min(1f, d / (half + 0.5f)))).toInt().coerceIn(30, 255)
            tick.alpha = a
            canvas.drawLine(x, topY, x, topY + context.dpf(14f), tick)
            if (i < items.size - 1) {
                val xm = x + spacing / 2f
                tick.alpha = a / 2
                canvas.drawLine(xm, topY + context.dpf(4f), xm, topY + context.dpf(11f), tick)
            }
            val sel = i == c0
            text.color = if (sel) ACCENT else Color.WHITE
            text.alpha = if (sel) 255 else a
            text.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(items[i], x, topY + context.dpf(38f), text)
        }
        canvas.drawLine(cx, 0f, cx, context.dpf(20f), marker)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            floor(context.dpf(58f)).toInt()
        )
    }
}
