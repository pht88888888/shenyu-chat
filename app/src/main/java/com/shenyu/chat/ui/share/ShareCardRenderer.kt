package com.shenyu.chat.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * 分享图片的排版渲染器。
 *
 * 官方「生成图片」不是截屏，而是把问答重新排成一张卡片（`share_image_footer_alert_v2`
 * 底部固定写「回答由 AI 生成，仅供参考」）。这里用 Android 原生 Canvas + StaticLayout
 * 按窄栏排版，超长内容自动截断并加省略号，避免生成超大位图。
 */
object ShareCardRenderer {

    // 900px 宽、最高 5200px（约 18MB ARGB_8888），避免长回答把内存顶爆
    private const val WIDTH = 900
    private const val PADDING = 72
    /** 单张图最大高度，超过就截断正文 */
    private const val MAX_HEIGHT = 5200

    fun render(
        context: Context,
        question: String,
        answer: String,
        dark: Boolean = false,
        brandName: String = "DeepSeek",
    ): Bitmap {
        val contentWidth = WIDTH - PADDING * 2
        val bg = if (dark) Color.parseColor("#141416") else Color.WHITE
        val titleColor = if (dark) Color.parseColor("#F5F6F7") else Color.parseColor("#0F0F13")
        val bodyColor = if (dark) Color.parseColor("#D7D9DD") else Color.parseColor("#23252B")
        val divider = if (dark) Color.parseColor("#2A2C31") else Color.parseColor("#E8E9EC")
        val captionColor = if (dark) Color.parseColor("#7A7E85") else Color.parseColor("#9AA0A6")

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = titleColor
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor
            textSize = 32f
        }
        val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = captionColor
            textSize = 24f
        }

        val questionText = question.trim().ifBlank { brandName }
        val questionLayout = layout(questionText, titlePaint, contentWidth)
        var answerText = answer.trim()
        var answerLayout = layout(answerText, bodyPaint, contentWidth)

        val captionText = "回答由 AI 生成，仅供参考"
        val footerHeight = 120
        val fixedHeight = PADDING * 2 + questionLayout.height + 56 + 2 + 56 + footerHeight
        // 正文超长：按行裁剪，末尾补省略号
        if (fixedHeight + answerLayout.height > MAX_HEIGHT) {
            val budget = MAX_HEIGHT - fixedHeight - 60
            val maxLines = (budget / (bodyPaint.textSize * 1.6f)).toInt().coerceAtLeast(1)
            val cut = answerLayout.getLineEnd((maxLines - 1).coerceAtMost(answerLayout.lineCount - 1))
            answerText = answerText.take(cut.coerceAtMost(answerText.length)).trimEnd() + "…"
            answerLayout = layout(answerText, bodyPaint, contentWidth)
        }

        val height = (fixedHeight + answerLayout.height).coerceAtMost(MAX_HEIGHT)
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bg)

        var y = PADDING.toFloat()
        canvas.save()
        canvas.translate(PADDING.toFloat(), y)
        questionLayout.draw(canvas)
        canvas.restore()
        y += questionLayout.height + 56

        canvas.drawRect(
            PADDING.toFloat(),
            y,
            (WIDTH - PADDING).toFloat(),
            y + 2f,
            Paint().apply { color = divider },
        )
        y += 2 + 56

        canvas.save()
        canvas.translate(PADDING.toFloat(), y)
        answerLayout.draw(canvas)
        canvas.restore()

        // 底部：品牌名 + 「回答由 AI 生成，仅供参考」
        val footerTop = height - footerHeight + 12
        canvas.drawText(brandName, PADDING.toFloat(), footerTop + 30f, captionPaint.apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        captionPaint.typeface = Typeface.DEFAULT
        canvas.drawText(captionText, PADDING.toFloat(), footerTop + 74f, captionPaint)

        return bitmap
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setLineSpacing(0f, 1.55f)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
}
