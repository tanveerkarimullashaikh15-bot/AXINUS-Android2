package com.axinus.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class CursorView(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFF22D3EE.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF22D3EE.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x3322D3EE }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        c.drawCircle(cx, cy, 44f, glow)
        c.drawCircle(cx, cy, 30f, ring)
        val p = Path()
        p.moveTo(cx, cy)
        p.lineTo(cx + 22f, cy + 62f)
        p.lineTo(cx + 2f, cy + 48f)
        p.lineTo(cx - 20f, cy + 58f)
        p.close()
        c.drawPath(p, fill)
    }
}
