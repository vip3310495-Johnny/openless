package com.openless.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Gboard-style keyboard for the Taiwan build: 大千 Zhuyin through the shared Rime engine
 * (Taiwan glyphs, Rime's own selection learning), QWERTY English, the ?123 / =\< symbol pages and
 * a number pad, with a toolbar for voice, voice translation and clipboard history. The voice
 * pipeline, clipboard panel and settings stay OpenLess's own; this file only owns typing.
 */

// ============================================================================ theme

internal class GbTheme(val dark: Boolean) {
    val bg = if (dark) 0xFF1B1C1F.toInt() else 0xFFECEFF4.toInt()
    val key = if (dark) 0xFF3B3D42.toInt() else 0xFFFFFFFF.toInt()
    val keyPressed = if (dark) 0xFF56595F.toInt() else 0xFFD5D9E0.toInt()
    val func = if (dark) 0xFF2C2E33.toInt() else 0xFFD3D9E3.toInt()
    val funcPressed = if (dark) 0xFF474A50.toInt() else 0xFFB9C1CD.toInt()
    val accent = if (dark) 0xFFA8C7FA.toInt() else 0xFF0B57D0.toInt()
    val accentPressed = if (dark) 0xFF7FAAF0.toInt() else 0xFF08429E.toInt()
    val onAccent = if (dark) 0xFF062E6F.toInt() else 0xFFFFFFFF.toInt()
    val accentSoft = if (dark) 0xFF2A3A57.toInt() else 0xFFD3E3FD.toInt()
    val onAccentSoft = if (dark) 0xFFE3E3E6.toInt() else 0xFF0842A0.toInt()
    val text = if (dark) 0xFFE3E3E6.toInt() else 0xFF1F1F1F.toInt()
    val subText = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
    val shadow = if (dark) 0x33000000 else 0x29000000
    val popupBg = if (dark) 0xFF45474C.toInt() else 0xFFFFFFFF.toInt()
    val divider = if (dark) 0x33FFFFFF else 0x22000000
}

private fun Context.gbDp(v: Float): Float = v * resources.displayMetrics.density
private fun Context.gbDpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
private fun Context.gbSp(v: Float): Float = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

private fun gbRound(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

private fun gbRipple(t: GbTheme, content: Drawable?): Drawable =
    RippleDrawable(ColorStateList.valueOf(if (t.dark) 0x33FFFFFF else 0x22000000), content, content ?: ColorDrawable(0xFFFFFFFF.toInt()))

// ============================================================================ layouts

internal enum class GbKT { CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYM, SYM2, LETTERS, NUMPAD, GLOBE }

internal class GbKey(
    val type: GbKT,
    val label: String = "",
    val width: Float = 1f,
    val hint: String? = null,
    val popup: List<String> = emptyList(),
) {
    val isFunc: Boolean get() = type != GbKT.CHAR && type != GbKT.SPACE
}

internal class GbRow(val keys: List<GbKey>, val padL: Float = 0f, val padR: Float = 0f)

internal class GbLayout(val name: String, val units: Float, val rows: List<GbRow>, val charSp: Float)

internal object GbZhuyin {
    /** 大千 keyboard: bopomofo symbol -> the physical key Rime's bopomofo schema expects. */
    private const val SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙"
    private const val KEYS = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347"

    fun keyFor(symbol: String): Char? = if (symbol.length == 1) SYMBOLS.indexOf(symbol[0]).takeIf { it >= 0 }?.let { KEYS[it] } else null

    /** Unconverted Dachen keys in a Rime preview, shown as bopomofo again. */
    fun display(raw: String): String = buildString {
        for (c in raw) {
            val i = KEYS.indexOf(c)
            when {
                i >= 0 -> append(SYMBOLS[i])
                c == ' ' -> append('ˉ')
                c == '\'' -> {}
                else -> append(c)
            }
        }
    }
}

internal object GbLayouts {
    private fun c(label: String, popup: String = "", hint: String? = null, w: Float = 1f) =
        GbKey(GbKT.CHAR, label, w, hint, if (popup.isBlank()) emptyList() else popup.trim().split(" "))

    private fun chars(s: String) = s.trim().split(" ").map { c(it) }

    private const val EN_COMMA_POP = "' \" - : ; ! ? @ #"
    private const val EN_DOT_POP = "? ! , ' \" - : ; @ # & ( ) /"
    private const val ZH_COMMA_POP = "、 ； ： ？ ！ 「 」 『 』 …"
    private const val ZH_DOT_POP = "？ ！ … ～ — ． · 。"

    val ENGLISH = GbLayout(
        "en", 10f, listOf(
            GbRow(listOf(
                c("q", "1", "1"), c("w", "2", "2"), c("e", "3 è é ê ë ē", "3"), c("r", "4", "4"),
                c("t", "5", "5"), c("y", "6 ý ÿ", "6"), c("u", "7 ù ú û ü ū", "7"),
                c("i", "8 ì í î ï ī", "8"), c("o", "9 ò ó ô ö õ ø œ", "9"), c("p", "0", "0"),
            )),
            GbRow(listOf(
                c("a", "à á â ä æ ã å ā"), c("s", "ß ś š"), c("d"), c("f"), c("g"), c("h"), c("j"), c("k"), c("l", "ł"),
            ), padL = 0.5f, padR = 0.5f),
            GbRow(
                listOf(GbKey(GbKT.SHIFT, width = 1.5f)) +
                    listOf(c("z", "ž ź ż"), c("x"), c("c", "ç ć č"), c("v"), c("b"), c("n", "ñ ń"), c("m")) +
                    listOf(GbKey(GbKT.BACKSPACE, width = 1.5f))
            ),
            GbRow(listOf(
                GbKey(GbKT.SYM, "?123", 1.5f), c(",", EN_COMMA_POP), GbKey(GbKT.GLOBE), GbKey(GbKT.SPACE, width = 4f),
                c(".", EN_DOT_POP), GbKey(GbKT.ENTER, width = 1.5f),
            )),
        ), 23f
    )

    val ZHUYIN = GbLayout(
        "zh", 11f, listOf(
            GbRow(chars("ㄅ ㄉ ˇ ˋ ㄓ ˊ ˙ ㄚ ㄞ ㄢ ㄦ")),
            GbRow(chars("ㄆ ㄊ ㄍ ㄐ ㄔ ㄗ ㄧ ㄛ ㄟ ㄣ"), padL = 0.5f, padR = 0.5f),
            GbRow(chars("ㄇ ㄋ ㄎ ㄑ ㄕ ㄘ ㄨ ㄜ ㄠ ㄤ"), padL = 0.5f, padR = 0.5f),
            GbRow(chars("ㄈ ㄌ ㄏ ㄒ ㄖ ㄙ ㄩ ㄝ ㄡ ㄥ") + GbKey(GbKT.BACKSPACE)),
            GbRow(listOf(
                GbKey(GbKT.SYM, "?123", 1.65f), c("，", ZH_COMMA_POP, w = 1.1f), GbKey(GbKT.GLOBE, width = 1.1f),
                GbKey(GbKT.SPACE, width = 4.4f), c("。", ZH_DOT_POP, w = 1.1f), GbKey(GbKT.ENTER, width = 1.65f),
            )),
        ), 20f
    )

    val SYM_EN = GbLayout(
        "sym_en", 10f, listOf(
            GbRow(listOf(
                c("1", "¹ ½ ⅓ ¼ ⅛"), c("2", "² ⅔"), c("3", "³ ¾ ⅜"), c("4", "⁴"), c("5", "⅝"),
                c("6"), c("7", "⅞"), c("8"), c("9"), c("0", "ⁿ ∅"),
            )),
            GbRow(listOf(
                c("@"), c("#", "№"), c("$", "¢ £ € ¥ ₩ ₱"), c("_"), c("&"), c("-", "– — ·"),
                c("+", "±"), c("(", "< { ["), c(")", "> } ]"), c("/", "\\"),
            )),
            GbRow(
                listOf(GbKey(GbKT.SYM2, "=\\<", 1.5f)) +
                    listOf(c("*", "† ‡ ★"), c("\"", "“ ” „ « »"), c("'", "‘ ’ ‚ ‹ ›"), c(":"), c(";"), c("!", "¡"), c("?", "¿")) +
                    listOf(GbKey(GbKT.BACKSPACE, width = 1.5f))
            ),
            GbRow(listOf(
                GbKey(GbKT.LETTERS, "ABC", 1.5f), c(","), GbKey(GbKT.NUMPAD, "1234"), GbKey(GbKT.SPACE, width = 4f),
                c(".", "…"), GbKey(GbKT.ENTER, width = 1.5f),
            )),
        ), 21f
    )

    val SYM2_EN = GbLayout(
        "sym2_en", 10f, listOf(
            GbRow(chars("~ ` | • √ π ÷ × ¶ ∆")),
            GbRow(chars("£ ¢ € ¥ ^ ° = { } \\")),
            GbRow(listOf(GbKey(GbKT.SYM, "?123", 1.5f)) + chars("% © ® ™ ✓ [ ]") + listOf(GbKey(GbKT.BACKSPACE, width = 1.5f))),
            GbRow(listOf(
                GbKey(GbKT.LETTERS, "ABC", 1.5f), c("<"), GbKey(GbKT.NUMPAD, "1234"), GbKey(GbKT.SPACE, width = 4f),
                c(">"), GbKey(GbKT.ENTER, width = 1.5f),
            )),
        ), 21f
    )

    val SYM_ZH = GbLayout(
        "sym_zh", 10f, listOf(
            GbRow(chars("1 2 3 4 5 6 7 8 9 0")),
            GbRow(listOf(
                c("＠"), c("＃"), c("＄", "￥ € £ ¢"), c("％"), c("＆"), c("－", "— ～ ＿"),
                c("＋", "＝ ±"), c("（", "【 〔 ｛"), c("）", "】 〕 ｝"), c("／", "＼ ｜"),
            )),
            GbRow(
                listOf(GbKey(GbKT.SYM2, "更多", 1.5f)) +
                    listOf(c("「", "『 《 〈"), c("」", "』 》 〉"), c("："), c("；"), c("！"), c("？"), c("、")) +
                    listOf(GbKey(GbKT.BACKSPACE, width = 1.5f))
            ),
            GbRow(listOf(
                GbKey(GbKT.LETTERS, "注音", 1.5f), c("，", ZH_COMMA_POP), GbKey(GbKT.NUMPAD, "1234"),
                GbKey(GbKT.SPACE, width = 4f), c("。", ZH_DOT_POP), GbKey(GbKT.ENTER, width = 1.5f),
            )),
        ), 21f
    )

    val SYM2_ZH = GbLayout(
        "sym2_zh", 10f, listOf(
            GbRow(chars("～ … — · 『 』 《 》 〈 〉")),
            GbRow(chars("【 】 〔 〕 ＊ ＝ ＿ ＼ ｜ ＾")),
            GbRow(listOf(GbKey(GbKT.SYM, "?123", 1.5f)) + chars("※ ° ℃ ← → ↑ ↓") + listOf(GbKey(GbKT.BACKSPACE, width = 1.5f))),
            GbRow(listOf(
                GbKey(GbKT.LETTERS, "注音", 1.5f), c("“"), GbKey(GbKT.NUMPAD, "1234"), GbKey(GbKT.SPACE, width = 4f),
                c("”"), GbKey(GbKT.ENTER, width = 1.5f),
            )),
        ), 21f
    )

    val NUMPAD = GbLayout(
        "num", 5f, listOf(
            GbRow(listOf(c("+", "= ±"), c("1"), c("2"), c("3"), c("-", "_ –"))),
            GbRow(listOf(c("*", "× #"), c("4"), c("5"), c("6"), GbKey(GbKT.SPACE, "␣"))),
            GbRow(listOf(c("/", "÷ %"), c("7"), c("8"), c("9"), GbKey(GbKT.BACKSPACE))),
            GbRow(listOf(GbKey(GbKT.LETTERS, "ABC"), c(",", "( )"), c("0"), c(".", ":"), GbKey(GbKT.ENTER))),
        ), 25f
    )
}

// ============================================================================ icons

internal enum class GbIcon {
    BACKSPACE, SHIFT, SHIFT_ON, SHIFT_LOCK, ENTER, SEARCH, SEND, GO, NEXT, DONE, GLOBE, MIC, TRANSLATE,
    CLIPBOARD, SETTINGS, CHEVRON_DOWN, CHEVRON_UP
}

internal object GbIcons {
    private val path = Path()
    private val rect = RectF()

    fun draw(c: Canvas, icon: GbIcon, cx: Float, cy: Float, s: Float, color: Int, p: Paint) {
        p.reset()
        p.isAntiAlias = true
        p.color = color
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = s * 0.085f
        p.style = Paint.Style.STROKE
        path.reset()
        when (icon) {
            GbIcon.BACKSPACE -> {
                path.moveTo(cx - s * 0.48f, cy)
                path.lineTo(cx - s * 0.2f, cy - s * 0.32f)
                path.lineTo(cx + s * 0.46f, cy - s * 0.32f)
                path.lineTo(cx + s * 0.46f, cy + s * 0.32f)
                path.lineTo(cx - s * 0.2f, cy + s * 0.32f)
                path.close()
                c.drawPath(path, p)
                val x = cx + s * 0.13f
                val d = s * 0.12f
                c.drawLine(x - d, cy - d, x + d, cy + d, p)
                c.drawLine(x - d, cy + d, x + d, cy - d, p)
            }
            GbIcon.SHIFT, GbIcon.SHIFT_ON, GbIcon.SHIFT_LOCK -> {
                path.moveTo(cx, cy - s * 0.44f)
                path.lineTo(cx + s * 0.42f, cy + s * 0.02f)
                path.lineTo(cx + s * 0.19f, cy + s * 0.02f)
                path.lineTo(cx + s * 0.19f, cy + s * 0.28f)
                path.lineTo(cx - s * 0.19f, cy + s * 0.28f)
                path.lineTo(cx - s * 0.19f, cy + s * 0.02f)
                path.lineTo(cx - s * 0.42f, cy + s * 0.02f)
                path.close()
                if (icon != GbIcon.SHIFT) p.style = Paint.Style.FILL_AND_STROKE
                c.drawPath(path, p)
                if (icon == GbIcon.SHIFT_LOCK) {
                    p.style = Paint.Style.FILL
                    rect.set(cx - s * 0.24f, cy + s * 0.38f, cx + s * 0.24f, cy + s * 0.47f)
                    c.drawRoundRect(rect, s * 0.04f, s * 0.04f, p)
                }
            }
            GbIcon.ENTER -> {
                path.moveTo(cx + s * 0.38f, cy - s * 0.34f)
                path.lineTo(cx + s * 0.38f, cy + s * 0.08f)
                path.lineTo(cx - s * 0.38f, cy + s * 0.08f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(cx - s * 0.18f, cy - s * 0.13f)
                path.lineTo(cx - s * 0.4f, cy + s * 0.08f)
                path.lineTo(cx - s * 0.18f, cy + s * 0.29f)
                c.drawPath(path, p)
            }
            GbIcon.SEARCH -> {
                c.drawCircle(cx - s * 0.08f, cy - s * 0.08f, s * 0.27f, p)
                c.drawLine(cx + s * 0.13f, cy + s * 0.13f, cx + s * 0.42f, cy + s * 0.42f, p)
            }
            GbIcon.SEND -> {
                p.style = Paint.Style.FILL_AND_STROKE
                path.moveTo(cx - s * 0.4f, cy - s * 0.38f)
                path.lineTo(cx + s * 0.44f, cy)
                path.lineTo(cx - s * 0.4f, cy + s * 0.38f)
                path.lineTo(cx - s * 0.28f, cy)
                path.close()
                c.drawPath(path, p)
            }
            GbIcon.GO, GbIcon.NEXT -> {
                c.drawLine(cx - s * 0.38f, cy, cx + s * 0.36f, cy, p)
                path.moveTo(cx + s * 0.08f, cy - s * 0.28f)
                path.lineTo(cx + s * 0.38f, cy)
                path.lineTo(cx + s * 0.08f, cy + s * 0.28f)
                c.drawPath(path, p)
                if (icon == GbIcon.NEXT) c.drawLine(cx + s * 0.48f, cy - s * 0.3f, cx + s * 0.48f, cy + s * 0.3f, p)
            }
            GbIcon.DONE -> {
                path.moveTo(cx - s * 0.36f, cy + s * 0.02f)
                path.lineTo(cx - s * 0.1f, cy + s * 0.28f)
                path.lineTo(cx + s * 0.4f, cy - s * 0.28f)
                c.drawPath(path, p)
            }
            GbIcon.GLOBE -> {
                p.strokeWidth = s * 0.07f
                val r = s * 0.42f
                c.drawCircle(cx, cy, r, p)
                rect.set(cx - r * 0.42f, cy - r, cx + r * 0.42f, cy + r)
                c.drawOval(rect, p)
                c.drawLine(cx - r, cy, cx + r, cy, p)
                val dy = r * 0.5f
                val dx = r * 0.866f
                c.drawLine(cx - dx, cy - dy, cx + dx, cy - dy, p)
                c.drawLine(cx - dx, cy + dy, cx + dx, cy + dy, p)
            }
            GbIcon.MIC -> {
                p.style = Paint.Style.FILL
                rect.set(cx - s * 0.16f, cy - s * 0.46f, cx + s * 0.16f, cy + s * 0.12f)
                c.drawRoundRect(rect, s * 0.16f, s * 0.16f, p)
                p.style = Paint.Style.STROKE
                rect.set(cx - s * 0.3f, cy - s * 0.2f, cx + s * 0.3f, cy + s * 0.3f)
                c.drawArc(rect, 0f, 180f, false, p)
                c.drawLine(cx, cy + s * 0.3f, cx, cy + s * 0.44f, p)
            }
            GbIcon.TRANSLATE -> {
                p.style = Paint.Style.FILL
                p.textAlign = Paint.Align.CENTER
                p.isFakeBoldText = true
                p.textSize = s * 0.56f
                c.drawText("文", cx - s * 0.2f, cy + s * 0.02f, p)
                p.textSize = s * 0.5f
                c.drawText("A", cx + s * 0.25f, cy + s * 0.42f, p)
            }
            GbIcon.CLIPBOARD -> {
                p.strokeWidth = s * 0.075f
                rect.set(cx - s * 0.32f, cy - s * 0.36f, cx + s * 0.32f, cy + s * 0.44f)
                c.drawRoundRect(rect, s * 0.07f, s * 0.07f, p)
                rect.set(cx - s * 0.15f, cy - s * 0.46f, cx + s * 0.15f, cy - s * 0.28f)
                p.style = Paint.Style.FILL
                c.drawRoundRect(rect, s * 0.04f, s * 0.04f, p)
                p.style = Paint.Style.STROKE
                c.drawLine(cx - s * 0.17f, cy - s * 0.04f, cx + s * 0.17f, cy - s * 0.04f, p)
                c.drawLine(cx - s * 0.17f, cy + s * 0.16f, cx + s * 0.1f, cy + s * 0.16f, p)
            }
            GbIcon.SETTINGS -> {
                p.strokeWidth = s * 0.16f
                p.strokeCap = Paint.Cap.BUTT
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val cos = Math.cos(a).toFloat()
                    val sin = Math.sin(a).toFloat()
                    c.drawLine(cx + cos * s * 0.27f, cy + sin * s * 0.27f, cx + cos * s * 0.44f, cy + sin * s * 0.44f, p)
                }
                p.strokeWidth = s * 0.12f
                c.drawCircle(cx, cy, s * 0.24f, p)
            }
            GbIcon.CHEVRON_DOWN, GbIcon.CHEVRON_UP -> {
                val d = if (icon == GbIcon.CHEVRON_DOWN) 1f else -1f
                path.moveTo(cx - s * 0.32f, cy - d * s * 0.14f)
                path.lineTo(cx, cy + d * s * 0.18f)
                path.lineTo(cx + s * 0.32f, cy - d * s * 0.14f)
                c.drawPath(path, p)
            }
        }
    }
}

internal class GbIconView(
    ctx: Context,
    var icon: GbIcon,
    var color: Int,
    private val sizeDp: Float = 22f,
    var circleColor: Int = 0,
) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    var pressedColor: Int = 0x22000000

    init {
        isClickable = true
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - context.gbDp(3f)
        if (circleColor != 0) {
            bgPaint.color = circleColor
            canvas.drawCircle(cx, cy, r, bgPaint)
        }
        if (isPressed) {
            bgPaint.color = pressedColor
            canvas.drawCircle(cx, cy, r, bgPaint)
        }
        GbIcons.draw(canvas, icon, cx, cy, context.gbDp(sizeDp), color, paint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

// ============================================================================ key grid

internal enum class GbShift { OFF, ON, LOCK }

internal class GbKeyboardView(ctx: Context, private val host: Host) : View(ctx) {

    interface Host {
        fun onKeyDown(key: GbKey)
        fun onKey(key: GbKey)
        fun onText(text: String)
        fun onLongPress(key: GbKey): Boolean
        fun onCursorMove(steps: Int)
        fun labelFor(key: GbKey): String
        fun popupFor(key: GbKey): List<String>
    }

    private class Box(val key: GbKey, val hit: RectF, val vis: RectF)

    var theme = GbTheme(false)
        set(v) { field = v; invalidate() }
    var layout: GbLayout = GbLayouts.ZHUYIN
        set(v) { field = v; cancelGesture(); rebuild(); invalidate() }
    var shift = GbShift.OFF
        set(v) { if (field != v) { field = v; invalidate() } }
    var enterIcon = GbIcon.ENTER
        set(v) { field = v; invalidate() }
    var spaceLabel = ""
        set(v) { field = v; invalidate() }
    var showPreview = true
    var overlay: GbPopupOverlay? = null

    private val boxes = ArrayList<Box>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())

    private val padSide = ctx.gbDp(3f)
    private val padTop = ctx.gbDp(4f)
    private val padBottom = ctx.gbDp(6f)
    private val gapH = ctx.gbDp(5.5f)
    private val radius = ctx.gbDp(7f)

    private var activeId = -1
    private var active: Box? = null
    private var consumed = false
    private var downX = 0f
    private var lastX = 0f
    private var spaceDrag = false
    private var dragAcc = 0f
    private var alts: List<String>? = null
    private var altSel = 0
    private var altCells: List<RectF> = emptyList()

    private val gapV: Float get() = if (layout.rows.size >= 5) context.gbDp(7.5f) else context.gbDp(9f)

    private val longPressRunnable = Runnable { onLongPressTimeout() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val b = active ?: return
            if (b.key.type == GbKT.BACKSPACE) {
                host.onKey(b.key)
                handler.postDelayed(this, 55)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rebuild() {
        boxes.clear()
        if (width == 0 || height == 0) return
        val unit = (width - padSide * 2) / layout.units
        val rh = (height - padTop - padBottom) / layout.rows.size
        layout.rows.forEachIndexed { r, row ->
            val top = padTop + r * rh
            var x = padSide + row.padL * unit
            row.keys.forEachIndexed { i, key ->
                val w = key.width * unit
                val hit = RectF(x, top, x + w, top + rh)
                if (i == 0) hit.left = 0f
                if (i == row.keys.lastIndex) hit.right = width.toFloat()
                val vis = RectF(x + gapH / 2, top + gapV / 2, x + w - gapH / 2, top + rh - gapV / 2)
                boxes += Box(key, hit, vis)
                x += w
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (boxes.isEmpty()) rebuild()
        for (b in boxes) drawKey(canvas, b, b === active && (!consumed || b.key.type == GbKT.BACKSPACE))
    }

    private fun drawKey(c: Canvas, b: Box, pressed: Boolean) {
        val k = b.key
        val t = theme
        val color = when {
            k.type == GbKT.ENTER -> if (pressed) t.accentPressed else t.accent
            k.isFunc -> if (pressed) t.funcPressed else t.func
            else -> if (pressed) t.keyPressed else t.key
        }
        if (!t.dark) {
            fill.color = t.shadow
            c.drawRoundRect(b.vis.left, b.vis.top + context.gbDp(1f), b.vis.right, b.vis.bottom + context.gbDp(1f), radius, radius, fill)
        }
        fill.color = color
        c.drawRoundRect(b.vis, radius, radius, fill)
        val cx = b.vis.centerX()
        val cy = b.vis.centerY()
        val iconSize = context.gbDp(22f)
        when (k.type) {
            GbKT.SHIFT -> GbIcons.draw(
                c, when (shift) { GbShift.OFF -> GbIcon.SHIFT; GbShift.ON -> GbIcon.SHIFT_ON; GbShift.LOCK -> GbIcon.SHIFT_LOCK },
                cx, cy, iconSize, if (shift == GbShift.OFF) t.text else t.accent, iconPaint,
            )
            GbKT.BACKSPACE -> GbIcons.draw(c, GbIcon.BACKSPACE, cx, cy, iconSize, t.text, iconPaint)
            GbKT.ENTER -> GbIcons.draw(c, enterIcon, cx, cy, iconSize, t.onAccent, iconPaint)
            GbKT.GLOBE -> GbIcons.draw(c, GbIcon.GLOBE, cx, cy, context.gbDp(20f), t.text, iconPaint)
            GbKT.SPACE -> text(c, if (k.label.isNotEmpty()) k.label else spaceLabel, cx, cy, context.gbSp(13f), t.subText)
            GbKT.CHAR -> {
                val label = host.labelFor(k)
                val single = label.codePointCount(0, label.length) == 1
                text(c, label, cx, cy, if (single) context.gbSp(layout.charSp) else context.gbSp(17f), t.text)
                k.hint?.let { text(c, it, b.vis.right - context.gbDp(7f), b.vis.top + context.gbDp(10f), context.gbSp(10f), t.subText) }
            }
            else -> text(c, host.labelFor(k), cx, cy, context.gbSp(15f), t.text)
        }
    }

    private fun text(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.typeface = Typeface.DEFAULT
        val fm = textPaint.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, textPaint)
    }

    private fun find(x: Float, y: Float): Box? {
        boxes.firstOrNull { it.hit.contains(x, y) }?.let { return it }
        return boxes.minByOrNull {
            val dx = max(0f, max(it.hit.left - x, x - it.hit.right))
            val dy = max(0f, max(it.hit.top - y, y - it.hit.bottom))
            dx * dx + dy * dy
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // fast typing: a second finger lands before the first lifts
                if (active != null && !consumed && alts == null && !spaceDrag) fire(active!!)
                cancelTimers()
                closeAlts()
                val idx = e.actionIndex
                activeId = e.getPointerId(idx)
                press(find(e.getX(idx), e.getY(idx)), e.getX(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(activeId)
                if (idx >= 0) move(e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == activeId) release()
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    private fun press(b: Box?, x: Float) {
        active = b
        consumed = false
        spaceDrag = false
        dragAcc = 0f
        downX = x
        lastX = x
        if (b == null) return
        host.onKeyDown(b.key)
        if (b.key.type == GbKT.BACKSPACE) {
            host.onKey(b.key)
            consumed = true
            handler.postDelayed(repeatRunnable, 400)
        } else {
            handler.postDelayed(longPressRunnable, 380)
        }
        showKeyPreview(b)
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        val b = active ?: return
        val list = alts
        if (list != null) {
            val sel = altCells.indexOfFirst { x >= it.left && x < it.right }.let {
                if (it >= 0) it else if (x < altCells.first().left) 0 else altCells.lastIndex
            }
            if (sel != altSel) { altSel = sel; overlay?.showAlts(list, altSel, altCells, this) }
            return
        }
        if (b.key.type == GbKT.SPACE && b.key.label.isEmpty()) {
            if (!spaceDrag && abs(x - downX) > context.gbDp(14f)) {
                spaceDrag = true
                handler.removeCallbacks(longPressRunnable)
                lastX = x
            }
            if (spaceDrag) {
                dragAcc += x - lastX
                lastX = x
                val step = context.gbDp(11f)
                var steps = 0
                while (dragAcc >= step) { dragAcc -= step; steps++ }
                while (dragAcc <= -step) { dragAcc += step; steps-- }
                if (steps != 0) host.onCursorMove(steps)
            }
            return
        }
        if (consumed) return
        val now = find(x, y)
        if (now != null && now !== b && now.key.type == GbKT.CHAR && b.key.type == GbKT.CHAR) {
            handler.removeCallbacks(longPressRunnable)
            active = now
            handler.postDelayed(longPressRunnable, 380)
            showKeyPreview(now)
            invalidate()
        }
    }

    private fun release() {
        val b = active
        cancelTimers()
        if (b != null) {
            val a = alts
            if (a != null) host.onText(a[altSel]) else if (!consumed && !spaceDrag) fire(b)
        }
        active = null
        activeId = -1
        consumed = false
        spaceDrag = false
        closeAlts()
        overlay?.hidePreview()
        invalidate()
    }

    private fun fire(b: Box) {
        consumed = true
        host.onKey(b.key)
        overlay?.hidePreview()
        invalidate()
    }

    fun cancelGesture() {
        cancelTimers()
        active = null
        activeId = -1
        consumed = false
        spaceDrag = false
        closeAlts()
        overlay?.hidePreview()
        invalidate()
    }

    private fun cancelTimers() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(repeatRunnable)
    }

    private fun onLongPressTimeout() {
        val b = active ?: return
        if (consumed) return
        if (host.onLongPress(b.key)) {
            consumed = true
            overlay?.hidePreview()
            invalidate()
            return
        }
        val list = host.popupFor(b.key)
        if (list.isEmpty()) return
        alts = list
        altSel = 0
        altCells = layoutAlts(b, list.size)
        overlay?.hidePreview()
        overlay?.showAlts(list, altSel, altCells, this)
    }

    private fun layoutAlts(b: Box, n: Int): List<RectF> {
        val cellW = min(b.vis.width() * 1.05f, (width - context.gbDp(16f)) / n)
        val h = b.vis.height() * 1.05f
        val total = cellW * n
        var left = b.vis.centerX() - cellW / 2
        if (left + total > width - context.gbDp(6f)) left = width - context.gbDp(6f) - total
        if (left < context.gbDp(6f)) left = context.gbDp(6f)
        val bottom = b.vis.top - context.gbDp(6f)
        return List(n) { i -> RectF(left + i * cellW, bottom - h, left + (i + 1) * cellW, bottom) }
    }

    private fun closeAlts() {
        if (alts != null) {
            alts = null
            overlay?.hideAlts()
        }
    }

    private fun showKeyPreview(b: Box) {
        if (!showPreview || b.key.type != GbKT.CHAR) { overlay?.hidePreview(); return }
        overlay?.showPreview(host.labelFor(b.key), RectF(b.vis), this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelTimers()
    }
}

/** Key previews and long-press alternates, drawn over the whole panel so top-row bubbles can cover the toolbar. */
internal class GbPopupOverlay(ctx: Context) : View(ctx) {
    var theme = GbTheme(false)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var previewLabel: String? = null
    private val previewRect = RectF()
    private var altList: List<String>? = null
    private var altSel = 0
    private var altRects: List<RectF> = emptyList()
    private val me = IntArray(2)
    private val other = IntArray(2)

    init {
        isClickable = false
        isFocusable = false
    }

    // Never ask for height of its own; the parent gives it the panel's exact size.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    private fun offsetY(src: View): Float {
        getLocationInWindow(me)
        src.getLocationInWindow(other)
        return (other[1] - me[1]).toFloat()
    }

    fun showPreview(label: String, keyRect: RectF, src: View) {
        val dy = offsetY(src)
        val w = max(keyRect.width() * 1.18f, keyRect.width() + context.gbDp(10f))
        val h = keyRect.height() * 1.12f + context.gbDp(6f)
        var bottom = keyRect.top + dy - context.gbDp(5f)
        if (bottom - h < 0) bottom = h
        val cx = keyRect.centerX().coerceIn(w / 2, max(w / 2, width - w / 2))
        previewRect.set(cx - w / 2, bottom - h, cx + w / 2, bottom)
        previewLabel = label
        invalidate()
    }

    fun hidePreview() {
        if (previewLabel != null) { previewLabel = null; invalidate() }
    }

    fun showAlts(list: List<String>, sel: Int, cells: List<RectF>, src: View) {
        val dy = offsetY(src)
        val first = cells.firstOrNull() ?: return
        val shiftDown = if (first.top + dy < context.gbDp(2f)) context.gbDp(2f) - (first.top + dy) else 0f
        altRects = cells.map { RectF(it.left, it.top + dy + shiftDown, it.right, it.bottom + dy + shiftDown) }
        altList = list
        altSel = sel
        invalidate()
    }

    fun hideAlts() {
        altList = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val r = context.gbDp(10f)
        previewLabel?.let { label ->
            fill.color = t.popupBg
            fill.setShadowLayer(context.gbDp(4f), 0f, context.gbDp(1.5f), 0x40000000)
            canvas.drawRoundRect(previewRect, r, r, fill)
            fill.clearShadowLayer()
            text(canvas, label, previewRect.centerX(), previewRect.centerY(), context.gbSp(30f), t.text)
        }
        val list = altList ?: return
        if (altRects.isEmpty()) return
        val frame = RectF(altRects.first().left - context.gbDp(4f), altRects.first().top - context.gbDp(4f),
            altRects.last().right + context.gbDp(4f), altRects.last().bottom + context.gbDp(4f))
        fill.color = t.popupBg
        fill.setShadowLayer(context.gbDp(5f), 0f, context.gbDp(2f), 0x48000000)
        canvas.drawRoundRect(frame, r, r, fill)
        fill.clearShadowLayer()
        list.forEachIndexed { i, s ->
            val cell = altRects[i]
            if (i == altSel) {
                fill.color = t.accent
                canvas.drawRoundRect(cell, context.gbDp(8f), context.gbDp(8f), fill)
            }
            text(canvas, s, cell.centerX(), cell.centerY(), context.gbSp(22f), if (i == altSel) t.onAccent else t.text)
        }
    }

    private fun text(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        textPaint.textSize = size
        textPaint.color = color
        val fm = textPaint.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, textPaint)
    }
}

// ============================================================================ toolbar / candidates

internal class GbTopBar(ctx: Context, private val t: GbTheme, private val host: Host) : FrameLayout(ctx) {
    interface Host {
        fun onVoice(translate: Boolean)
        fun onClipboard()
        fun onSettings()
        fun onCandidate(index: Int)
        fun onToggleGrid()
    }

    private val toolbar = LinearLayout(ctx)
    private val strip = LinearLayout(ctx)
    private val scroll = HorizontalScrollView(ctx)
    private val list = LinearLayout(ctx)
    private val expand = GbIconView(ctx, GbIcon.CHEVRON_DOWN, t.subText, 20f)
    private val voiceViews = ArrayList<View>()

    init {
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.setPadding(ctx.gbDpi(6f), 0, ctx.gbDpi(4f), 0)
        val clip = chip(GbIcon.CLIPBOARD, "剪貼簿") { host.onClipboard() }
        val translate = chip(GbIcon.TRANSLATE, "語音翻譯") { host.onVoice(translate = true) }
        toolbar.addView(clip)
        toolbar.addView(translate)
        voiceViews += translate
        toolbar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val settings = GbIconView(ctx, GbIcon.SETTINGS, t.subText, 20f)
        settings.contentDescription = "設定"
        settings.setOnClickListener { host.onSettings() }
        toolbar.addView(settings, LinearLayout.LayoutParams(ctx.gbDpi(44f), MATCH))
        val mic = GbIconView(ctx, GbIcon.MIC, t.onAccent, 20f, t.accent)
        mic.contentDescription = "語音輸入"
        mic.pressedColor = 0x33000000
        mic.setOnClickListener { host.onVoice(translate = false) }
        toolbar.addView(mic, LinearLayout.LayoutParams(ctx.gbDpi(48f), MATCH))
        voiceViews += mic
        addView(toolbar, LayoutParams(MATCH, MATCH))

        strip.orientation = LinearLayout.HORIZONTAL
        list.orientation = LinearLayout.HORIZONTAL
        list.gravity = Gravity.CENTER_VERTICAL
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(list, LayoutParams(WRAP, MATCH))
        strip.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        expand.setOnClickListener { host.onToggleGrid() }
        strip.addView(expand, LinearLayout.LayoutParams(ctx.gbDpi(44f), MATCH))
        addView(strip, LayoutParams(MATCH, MATCH))
        strip.visibility = GONE
    }

    private fun chip(icon: GbIcon, label: String, onClick: () -> Unit): View {
        val c = context
        val box = LinearLayout(c)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(c.gbDpi(8f), 0, c.gbDpi(12f), 0)
        box.background = gbRipple(t, gbRound(t.accentSoft, c.gbDp(16f)))
        box.isClickable = true
        box.setOnClickListener { onClick() }
        val iv = GbIconView(c, icon, t.accent, 16f)
        iv.isClickable = false
        box.addView(iv, LinearLayout.LayoutParams(c.gbDpi(22f), c.gbDpi(22f)))
        box.addView(TextView(c).apply {
            text = label
            textSize = 13.5f
            setTextColor(t.onAccentSoft)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = c.gbDpi(3f) })
        box.layoutParams = LinearLayout.LayoutParams(WRAP, c.gbDpi(32f)).apply { rightMargin = c.gbDpi(6f) }
        return box
    }

    fun showToolbar(voiceAllowed: Boolean) {
        strip.visibility = GONE
        toolbar.visibility = VISIBLE
        voiceViews.forEach { it.visibility = if (voiceAllowed) VISIBLE else GONE }
    }

    fun showCandidates(items: List<String>, gridOpen: Boolean) {
        toolbar.visibility = GONE
        strip.visibility = VISIBLE
        expand.icon = if (gridOpen) GbIcon.CHEVRON_UP else GbIcon.CHEVRON_DOWN
        expand.invalidate()
        list.removeAllViews()
        val c = context
        items.forEachIndexed { i, s ->
            if (i > 0) list.addView(View(c).apply { setBackgroundColor(t.divider) }, LinearLayout.LayoutParams(1, c.gbDpi(18f)))
            val tv = TextView(c).apply {
                text = s
                textSize = 19f
                setTextColor(if (i == 0) t.accent else t.text)
                if (i == 0) typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                setPadding(c.gbDpi(13f), 0, c.gbDpi(13f), 0)
                minWidth = c.gbDpi(44f)
                background = gbRipple(t, null)
                setOnClickListener { host.onCandidate(i) }
            }
            list.addView(tv, LinearLayout.LayoutParams(WRAP, MATCH))
        }
        scroll.scrollTo(0, 0)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

internal class GbCandidateGrid(ctx: Context, private val t: GbTheme, private val onPick: (Int) -> Unit) : ScrollView(ctx) {
    private val col = LinearLayout(ctx)
    private val paint = Paint().apply { textSize = ctx.gbSp(20f) }

    init {
        col.orientation = LinearLayout.VERTICAL
        setBackgroundColor(t.bg)
        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun set(items: List<String>) {
        col.removeAllViews()
        val c = context
        val avail = resources.displayMetrics.widthPixels - c.gbDp(8f)
        val padH = c.gbDp(14f)
        val minW = c.gbDp(52f)
        var row: LinearLayout? = null
        var used = 0f
        items.forEachIndexed { i, s ->
            val w = max(minW, paint.measureText(s) + padH * 2)
            if (row == null || used + w > avail) {
                row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.gbDpi(50f)))
                col.addView(View(c).apply { setBackgroundColor(t.divider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                used = 0f
            }
            val tv = TextView(c).apply {
                text = s
                textSize = 20f
                setTextColor(t.text)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                background = gbRipple(t, null)
                setOnClickListener { onPick(i) }
            }
            row!!.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w))
            used += w
        }
        scrollTo(0, 0)
    }
}

// ============================================================================ controller

/**
 * Owns typing for the Gboard-style panel. Chinese goes through [zhuyin] (Rime, Taiwan Zhuyin):
 * every key, punctuation mark and Enter is queued on the same Rime worker so commits keep their
 * order with fast typing. English and symbols are committed directly.
 */
internal class GboardKeyboard(private val service: OpenLessImeService) : GbKeyboardView.Host, GbTopBar.Host {
    private enum class Lang { ZH, EN }
    private enum class Page { LETTERS, SYM1, SYM2, NUM }

    private val prefs get() = service.getSharedPreferences("openless_ime_ui", Context.MODE_PRIVATE)
    val zhuyin by lazy { RimePinyinController(service, zhuyin = true) }

    private var lang = Lang.ZH
    private var page = Page.LETTERS
    private var shift = GbShift.OFF
    private var shiftManual = false
    private var lastShiftTap = 0L
    private var lastSpace = 0L
    private var forcedEnglish = false
    private var voiceAllowed = true
    private var password = false
    private var composingShown = false
    private var candidates: List<String> = emptyList()
    private var gridOpen = false

    private var kv: GbKeyboardView? = null
    private var topBar: GbTopBar? = null
    private var grid: GbCandidateGrid? = null

    init {
        lang = if (prefs.getString("gboard_lang", "zh") == "en") Lang.EN else Lang.ZH
    }

    fun buildView(dark: Boolean, heightPx: Int): View {
        val t = GbTheme(dark)
        val root = FrameLayout(service)
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        root.setBackgroundColor(t.bg)
        val column = LinearLayout(service)
        column.orientation = LinearLayout.VERTICAL
        val bar = GbTopBar(service, t, this)
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, service.gbDpi(44f)))
        val keyArea = FrameLayout(service)
        val keyboard = GbKeyboardView(service, this)
        keyboard.theme = t
        keyboard.showPreview = prefs.getBoolean("key_popup_preview", true)
        keyArea.addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val g = GbCandidateGrid(service, t) { onCandidate(it) }
        g.visibility = View.GONE
        keyArea.addView(g, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(keyArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val overlay = GbPopupOverlay(service)
        overlay.theme = t
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        keyboard.overlay = overlay
        kv = keyboard
        topBar = bar
        grid = g
        gridOpen = false
        applyEditor(service.currentInputEditorInfo, resetPage = false)
        refreshKeyboard()
        if (candidates.isNotEmpty() && zhuyin.currentEncoding().isNotEmpty()) bar.showCandidates(candidates, false)
        else bar.showToolbar(voiceAllowed)
        return root
    }

    // ------------------------------------------------------------------ editor lifecycle

    fun onStartInput(info: EditorInfo?) {
        composingShown = false
        candidates = emptyList()
        zhuyin.setLearningAllowed(service.personalizedLearningAllowed())
        zhuyin.bindCommit { text ->
            if (password) false
            else {
                val ok = service.currentInputConnection?.commitText(text, 1) == true
                if (ok) composingShown = false
                ok
            }
        }
        applyEditor(info, resetPage = true)
    }

    fun onStartInputView(info: EditorInfo?) {
        applyEditor(info, resetPage = false)
        refreshKeyboard()
        updateShift()
    }

    fun onFinishInput() {
        zhuyin.clear()
        composingShown = false
        candidates = emptyList()
    }

    fun shutdown() = zhuyin.shutdown()

    /** The editor moved the cursor out of our composition (a tap elsewhere): keep its text, drop the reading. */
    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (!composingShown || zhuyin.currentEncoding().isEmpty() || candidatesStart < 0) return
        if (newSelStart != newSelEnd || newSelStart < candidatesStart || newSelStart > candidatesEnd) {
            service.currentInputConnection?.finishComposingText()
            composingShown = false
            zhuyin.clear()
            candidates = emptyList()
            showToolbar()
        }
    }

    private fun applyEditor(info: EditorInfo?, resetPage: Boolean) {
        val type = info?.inputType ?: 0
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        password = ImeLearningPolicy.isPassword(type)
        val latinOnly = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_URI)
        forcedEnglish = password || latinOnly
        voiceAllowed = !password && !service.isSensitiveField(info)
        if (forcedEnglish) lang = Lang.EN
        else if (resetPage) lang = if (prefs.getString("gboard_lang", "zh") == "en") Lang.EN else Lang.ZH
        if (resetPage) {
            page = when (cls) {
                InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> Page.NUM
                else -> Page.LETTERS
            }
            shift = GbShift.OFF
            shiftManual = false
        }
        kv?.enterIcon = enterIcon(info)
    }

    private fun enterIcon(info: EditorInfo?): GbIcon {
        val options = info?.imeOptions ?: return GbIcon.ENTER
        if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return GbIcon.ENTER
        return when (options and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> GbIcon.SEARCH
            EditorInfo.IME_ACTION_SEND -> GbIcon.SEND
            EditorInfo.IME_ACTION_GO -> GbIcon.GO
            EditorInfo.IME_ACTION_NEXT -> GbIcon.NEXT
            EditorInfo.IME_ACTION_DONE -> GbIcon.DONE
            else -> GbIcon.ENTER
        }
    }

    private fun refreshKeyboard() {
        val k = kv ?: return
        k.layout = when (page) {
            Page.LETTERS -> if (lang == Lang.ZH) GbLayouts.ZHUYIN else GbLayouts.ENGLISH
            Page.SYM1 -> if (lang == Lang.ZH) GbLayouts.SYM_ZH else GbLayouts.SYM_EN
            Page.SYM2 -> if (lang == Lang.ZH) GbLayouts.SYM2_ZH else GbLayouts.SYM2_EN
            Page.NUM -> GbLayouts.NUMPAD
        }
        k.spaceLabel = if (lang == Lang.ZH) "注音" else "English"
        k.shift = shift
    }

    // ------------------------------------------------------------------ keys

    private val zh get() = lang == Lang.ZH && !password

    override fun onKeyDown(key: GbKey) {
        val k = kv ?: return
        k.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onKey(key: GbKey) {
        when (key.type) {
            GbKT.CHAR -> typeText(labelFor(key), allowZhuyin = true)
            GbKT.SPACE -> space()
            GbKT.BACKSPACE -> backspace()
            GbKT.ENTER -> enter()
            GbKT.SHIFT -> toggleShift()
            GbKT.SYM -> switchPage(Page.SYM1)
            GbKT.SYM2 -> switchPage(Page.SYM2)
            GbKT.NUMPAD -> switchPage(Page.NUM)
            GbKT.LETTERS -> switchPage(Page.LETTERS)
            GbKT.GLOBE -> {
                if (forcedEnglish) { showImePicker(); return }
                flushComposition()
                lang = if (lang == Lang.ZH) Lang.EN else Lang.ZH
                prefs.edit().putString("gboard_lang", if (lang == Lang.ZH) "zh" else "en").apply()
                page = Page.LETTERS
                shift = GbShift.OFF
                shiftManual = false
                refreshKeyboard()
                updateShift()
            }
        }
    }

    override fun onText(text: String) = typeText(text, allowZhuyin = false)

    override fun onLongPress(key: GbKey): Boolean = when (key.type) {
        GbKT.GLOBE -> { showImePicker(); true }
        GbKT.SPACE -> if (key.label.isEmpty() && zhuyin.currentEncoding().isEmpty()) { showImePicker(); true } else false
        GbKT.SHIFT -> { shift = GbShift.LOCK; kv?.shift = shift; true }
        else -> false
    }

    override fun onCursorMove(steps: Int) {
        if (zhuyin.currentEncoding().isNotEmpty()) return
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(abs(steps)) { service.sendDownUpKeyEvents(code) }
    }

    override fun labelFor(key: GbKey): String = when (key.type) {
        GbKT.CHAR -> if (lang == Lang.EN && page == Page.LETTERS && shift != GbShift.OFF && key.label.length == 1 && key.label[0].isLetter())
            key.label.uppercase() else key.label
        GbKT.LETTERS -> if (lang == Lang.ZH) "注音" else "ABC"
        else -> key.label
    }

    override fun popupFor(key: GbKey): List<String> =
        if (lang == Lang.EN && page == Page.LETTERS && shift != GbShift.OFF) key.popup.map { it.uppercase() } else key.popup

    private fun typeText(t: String, allowZhuyin: Boolean) {
        if (zh) {
            if (allowZhuyin && page == Page.LETTERS) {
                GbZhuyin.keyFor(t)?.let { k -> zhuyin.appendLetter(k) { render(it) }; return }
            }
            // punctuation and symbols queue behind pending Zhuyin keys and commit the reading first
            zhuyin.insertLiteral(t) { render(it) }
            return
        }
        service.currentInputConnection?.commitText(t, 1)
        if (shift == GbShift.ON) { shift = GbShift.OFF; shiftManual = false }
        updateShift()
    }

    private fun space() {
        if (zh) { zhuyin.space { render(it) }; return }
        val ic = service.currentInputConnection ?: return
        val now = System.currentTimeMillis()
        if (lang == Lang.EN && page == Page.LETTERS && now - lastSpace < 700) {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpace = 0
                updateShift()
                return
            }
        }
        ic.commitText(" ", 1)
        lastSpace = now
        updateShift()
    }

    private fun backspace() {
        if (zh) {
            zhuyin.backspace({ render(it) }) { deleteInEditor() }
            return
        }
        deleteInEditor()
        updateShift()
    }

    private fun deleteInEditor() {
        val ic = service.currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun enter() {
        if (zh) { zhuyin.confirmFirst({ render(it) }) { editorEnter() }; return }
        editorEnter()
        updateShift()
    }

    private fun editorEnter() {
        val ic = service.currentInputConnection ?: return
        val info = service.currentInputEditorInfo
        val options = info?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noAction = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val multiLine = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        when {
            !noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED -> ic.performEditorAction(action)
            multiLine -> ic.commitText("\n", 1)
            else -> service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** Commits any reading as its converted text (language/page switches, voice). */
    fun flushComposition() {
        if (zhuyin.currentEncoding().isNotEmpty()) zhuyin.insertLiteral("") { render(it) }
    }

    private fun switchPage(p: Page) {
        flushComposition()
        page = p
        if (p != Page.LETTERS) { shift = GbShift.OFF; shiftManual = false }
        refreshKeyboard()
        updateShift()
    }

    private fun toggleShift() {
        val now = System.currentTimeMillis()
        shift = when (shift) {
            GbShift.OFF -> GbShift.ON
            GbShift.ON -> if (now - lastShiftTap < 350) GbShift.LOCK else GbShift.OFF
            GbShift.LOCK -> GbShift.OFF
        }
        lastShiftTap = now
        shiftManual = shift == GbShift.ON
        kv?.shift = shift
    }

    private fun updateShift() {
        if (lang != Lang.EN || page != Page.LETTERS || shift == GbShift.LOCK || shiftManual) return
        val info = service.currentInputEditorInfo
        val ic = service.currentInputConnection
        val caps = info != null && ic != null && info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            ic.getCursorCapsMode(info.inputType) != 0
        shift = if (caps) GbShift.ON else GbShift.OFF
        kv?.shift = shift
    }

    private fun showImePicker() {
        (service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    // ------------------------------------------------------------------ composition display

    /** Rime state changed: underline the converted preview in the editor and list the candidates. */
    private fun render(list: List<String>) {
        val ic = service.currentInputConnection
        val reading = zhuyin.currentEncoding()
        if (reading.isEmpty()) {
            if (composingShown) {
                ic?.setComposingText("", 1)
                ic?.finishComposingText()
                composingShown = false
            }
            candidates = emptyList()
            showToolbar()
            return
        }
        ic?.setComposingText(GbZhuyin.display(zhuyin.currentPreview().ifEmpty { reading }), 1)
        composingShown = true
        candidates = list
        topBar?.showCandidates(list, gridOpen)
        if (gridOpen) grid?.set(list)
    }

    private fun showToolbar() {
        if (gridOpen) setGrid(false)
        topBar?.showToolbar(voiceAllowed)
    }

    override fun onCandidate(index: Int) {
        val text = candidates.getOrNull(index) ?: return
        if (gridOpen) setGrid(false)
        zhuyin.selectCandidate(text) { render(it) }
    }

    override fun onToggleGrid() = setGrid(!gridOpen)

    private fun setGrid(open: Boolean) {
        gridOpen = open
        val g = grid ?: return
        if (open) {
            g.set(candidates)
            g.visibility = View.VISIBLE
            kv?.visibility = View.INVISIBLE
        } else {
            g.visibility = View.GONE
            kv?.visibility = View.VISIBLE
        }
        if (candidates.isNotEmpty()) topBar?.showCandidates(candidates, gridOpen)
    }

    // ------------------------------------------------------------------ toolbar

    override fun onVoice(translate: Boolean) {
        flushComposition()
        service.startVoiceFromKeyboard(translate)
    }

    override fun onClipboard() {
        flushComposition()
        service.openClipboardFromKeyboard()
    }

    override fun onSettings() = service.openSettingsFromKeyboard()
}
