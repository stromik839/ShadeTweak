package com.shadetweak.hyperos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Превью: шахматка (= "что под шторкой"), поверх неё выбранный цвет фона
 * и макет карточки уведомления. Так видно и цвет, и прозрачность.
 */
class ColorPreviewView(context: Context, dark: Boolean) : View(context) {

    var color: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val cell = 12 * density
    private val radius = 18 * density

    private val checkA = Paint().apply { color = if (dark) 0xFF3C3C3C.toInt() else 0xFFFFFFFF.toInt() }
    private val checkB = Paint().apply { color = if (dark) 0xFF262626.toInt() else 0xFFD8D8D8.toInt() }
    private val fill = Paint()
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val cardTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
        textSize = 14 * density
        isFakeBoldText = true
    }
    private val cardText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF666666.toInt()
        textSize = 12 * density
    }

    private val clip = Path()
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.reset()
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.save()
        canvas.clipPath(clip)

        var y = 0f
        var row = 0
        while (y < h) {
            var x = 0f
            var col = row
            while (x < w) {
                canvas.drawRect(x, y, x + cell, y + cell, if (col % 2 == 0) checkA else checkB)
                x += cell
                col++
            }
            y += cell
            row++
        }

        fill.color = color
        canvas.drawRect(0f, 0f, w, h, fill)

        val m = 16 * density
        val cardH = 58 * density
        val top = (h - cardH) / 2f
        rect.set(m, top, w - m, top + cardH)
        canvas.drawRoundRect(rect, 14 * density, 14 * density, card)
        canvas.drawText("Уведомление", m + 14 * density, top + 24 * density, cardTitle)
        canvas.drawText("Так будет выглядеть фон шторки", m + 14 * density, top + 44 * density, cardText)

        canvas.restore()
    }
}
