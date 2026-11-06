package com.example.cafe_kiosk_ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** A static, responsive rendering of the supplied voice-order confirmation screen. */
class KioskScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val serif = Typeface.create("serif", Typeface.NORMAL)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val purple = 0xFF706CF4.toInt()
    private val ink = 0xFF101010.toInt()
    private val pink = 0xFFF8E8E8.toInt()
    private val frame = 0xFFF5F1F1.toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFFFFFFFF.toInt())
        // Design coordinates follow the 1080 × 1920 reference and scale to any portrait device.
        val scale = width / 1080f
        val heightScale = height / 1920f
        val s = minOf(scale, heightScale)
        val left = (width - 1080f * s) / 2f
        val top = (height - 1920f * s) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(s, s)

        // Header
        text(canvas, "☕", 100, 125, 112, ink, serif)
        text(canvas, "음성 주문 키오스크", 225, 103, 46, ink, serif)
        text(canvas, "🏠", 995, 118, 92, ink, sans, Paint.Align.CENTER)

        // Conversation panel with a soft outer frame.
        round(canvas, 60f, 208f, 1020f, 1122f, 26f, frame)
        round(canvas, 85f, 228f, 995f, 1090f, 27f, 0xFFFFFFFF.toInt())
        robot(canvas, 155f, 300f)
        round(canvas, 225f, 265f, 880f, 331f, 27f, pink)
        text(canvas, "주문하신 상품이 맞나요?", 250, 310, 45, ink, serif)

        robot(canvas, 155f, 420f)
        round(canvas, 225f, 368f, 981f, 1068f, 25f, pink)
        // Product image placeholder is intentionally self-contained; no external asset or service needed.
        round(canvas, 421f, 389f, 783f, 752f, 0f, 0xFFFFFFFF.toInt())
        text(canvas, "🥤", 602, 685, 220, 0xFF8D3B19.toInt(), sans, Paint.Align.CENTER)
        text(canvas, "아메리카노", 602, 810, 39, ink, bold, Paint.Align.CENTER)
        text(canvas, "온도 :", 263, 887, 40, ink, serif)
        text(canvas, "ICE(차가움)", 376, 887, 40, 0xFF1685F8.toInt(), serif)
        text(canvas, "크기 : R(보통 크기)", 263, 963, 40, ink, serif)
        text(canvas, "수량", 798, 871, 39, ink, serif, Paint.Align.CENTER)
        text(canvas, "2", 798, 985, 90, ink, serif, Paint.Align.CENTER)

        // Voice affordance and static listening status.
        paint.color = purple
        canvas.drawCircle(540f, 1360f, 160f, paint)
        text(canvas, "🗣️", 540, 1371, 104, 0xFFFFD59E.toInt(), sans, Paint.Align.CENTER)
        text(canvas, "🎙️", 633, 1383, 80, 0xFF32333D.toInt(), sans, Paint.Align.CENTER)
        text(canvas, "말하기", 540, 1474, 64, 0xFFFFFFFF.toInt(), serif, Paint.Align.CENTER)
        paint.color = 0xFF00BF35.toInt()
        canvas.drawCircle(451f, 1558f, 14f, paint)
        text(canvas, "듣는 중...", 487, 1572, 44, ink, bold)

        round(canvas, 60f, 1669f, 1020f, 1873f, 25f, purple)
        text(canvas, "“맞아요”", 540, 1790, 76, 0xFFFFFFFF.toInt(), serif, Paint.Align.CENTER)
        canvas.restore()
    }

    private fun round(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        paint.color = color
        c.drawRoundRect(RectF(l, t, r, b), radius, radius, paint)
    }

    private fun text(c: Canvas, value: String, x: Int, baseline: Int, size: Int, color: Int,
                     face: Typeface, align: Paint.Align = Paint.Align.LEFT) {
        paint.color = color
        paint.textSize = size.toFloat()
        paint.typeface = face
        paint.textAlign = align
        c.drawText(value, x.toFloat(), baseline.toFloat(), paint)
    }

    private fun robot(c: Canvas, cx: Float, cy: Float) {
        paint.color = 0xFFE9EDF3.toInt()
        c.drawCircle(cx, cy, 40f, paint)
        paint.color = 0xFF343A46.toInt()
        c.drawRoundRect(RectF(cx - 20f, cy - 12f, cx + 20f, cy + 18f), 9f, 9f, paint)
        c.drawRoundRect(RectF(cx - 26f, cy - 5f, cx - 21f, cy + 10f), 3f, 3f, paint)
        c.drawRoundRect(RectF(cx + 21f, cy - 5f, cx + 26f, cy + 10f), 3f, 3f, paint)
        c.drawLine(cx, cy - 20f, cx, cy - 13f, paint)
        c.drawCircle(cx, cy - 22f, 4f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx - 9f, cy, 3f, paint)
        c.drawCircle(cx + 9f, cy, 3f, paint)
        c.drawArc(RectF(cx - 8f, cy + 2f, cx + 8f, cy + 12f), 15f, 150f, false, paint)
    }
}
