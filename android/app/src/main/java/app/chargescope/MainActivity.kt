package app.chargescope

// MainActivity.kt —— 界面层：整个应用唯一的页面。
// 用代码搭 UI（不写布局 XML），每 500ms 刷新一次，只显示功率、剩余时间、状态和一条曲线。

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * 主界面。
 *
 * 刻意保持极简：一屏只有「状态」「功率」「剩余时间」「曲线」四块，没有按钮、没有设置页。
 * 采样完全由自己这个 Activity 驱动，不依赖任何后台服务，退到后台就停。
 */
class MainActivity : Activity() {

    // 配色统一放这里，改主题只动这四个常量
    private val BG = 0xFF0B0F14.toInt()      // 背景：近黑
    private val TEXT = 0xFFE8EDF2.toInt()    // 主要文字：浅灰白
    private val MUTED = 0xFF8A96A3.toInt()   // 次要文字：灰
    private val ACCENT = 0xFF3DDC97.toInt()  // 强调色：绿，只在「正在充电」时用

    private lateinit var statusTv: TextView   // 顶部一行：充电中 / 电量 / 温度
    private lateinit var powerTv: TextView    // 中间大数字：功率
    private lateinit var unitTv: TextView     // 大数字右边的单位「W」
    private lateinit var remainTv: TextView   // 大数字下面：剩余时间
    private lateinit var chart: SparklineView // 底部 60 秒功率曲线

    private val handler = Handler(Looper.getMainLooper())
    private var last: Reading? = null         // 最近一次采样，长按看原始值时用

    /**
     * 刷新循环：每 500ms 读一次数据并重绘。
     *
     * 用 Runnable 自我重投递而不是 Timer/协程，是为了能在 onPause 用同一个引用
     * 精确摘除回调，避免界面不可见后仍在跑。
     */
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏和导航栏也染成背景色，避免出现两条亮边。
        // 这两个属性在 API 35 起被标记废弃，但本项目 targetSdk = 34，
        // 且没有用 WindowInsetsController 的替代方案，这里保留并忽略编译警告。
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())
    }

    /** 界面可见时开始刷新。注意不要放在 onCreate，否则从后台回到前台不会恢复 */
    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    /** 界面不可见时停掉刷新循环，避免白耗电 */
    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    /** dp 转像素，保证不同屏幕上间距一致 */
    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    /**
     * 用代码搭出整个页面。
     *
     * 结构是垂直排列的 LinearLayout，整体居中：
     * 状态行 → [功率大字 + 单位] → 剩余时间 → 曲线。
     */
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
        }

        statusTv = text("", 15f, MUTED)
        root.addView(statusTv)

        // 功率和单位横向排列，底部对齐，让「W」贴着数字的基线
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4f), 0, 0)
        }
        powerTv = text("--", 96f, ACCENT, bold = true)
        unitTv = text("W", 30f, ACCENT)
        row.addView(powerTv)
        row.addView(unitTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(8f)
            bottomMargin = dp(18f) // 把「W」往上提一点，视觉上对齐数字中部
        })
        root.addView(row)

        remainTv = text("", 26f, TEXT, bold = true)
        root.addView(remainTv)

        chart = SparklineView(this)
        root.addView(chart, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(90f)
        ).apply { topMargin = dp(28f) })

        // 隐藏入口：长按功率数字弹出原始读数。界面上不放按钮，保持干净
        powerTv.setOnLongClickListener {
            showDiagnostics()
            true
        }

        return root
    }

    /** 造一个指定字号、颜色、粗细的 TextView，避免重复写五遍属性 */
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        }

    /**
     * 取一次数据并把四个控件刷成新值。
     *
     * 显示规则集中在这里，不散落到各处：
     * 没在充电时功率显示 "--"（否则会把放电电流算成充电功率），
     * 只有充电中才把数字和曲线染成强调色。
     */
    private fun refresh() {
        val r = ChargeSampler.read(this)
        last = r

        // 顶部状态行：先判充满，再判充电中，最后是未充电
        statusTv.text = String.format(
            Locale.US, "%s · %s%%%s",
            when {
                r.full -> "已充满"
                r.charging -> "充电中"
                else -> "未充电"
            },
            r.percent?.toString() ?: "--",
            r.tempC?.let { String.format(Locale.US, " · %.0f°C", it) } ?: ""
        )
        statusTv.setTextColor(if (r.charging) ACCENT else MUTED)

        // 关键规则：没在充电时不要显示功率。拔线后系统仍会返回放电电流，
        // 乘上电压就是一个看起来很像充电功率的数字，会和「未充电」自相矛盾。
        val showPower = if (r.charging && (r.powerW ?: 0.0) > 0.1) r.powerW else null
        powerTv.text = ChargeSampler.fmt1(showPower)
        powerTv.setTextColor(if (showPower != null) ACCENT else MUTED)
        unitTv.setTextColor(powerTv.currentTextColor) // 单位颜色跟着数字走

        // 剩余时间：充满 → 已充满；充电中但还没算出 → 计算中；没插电 → 提示
        remainTv.text = when {
            r.full -> "已充满"
            r.charging && r.remainMin != null -> String.format(Locale.US, "约 %d 分钟充满", r.remainMin)
            r.charging -> "计算中…"
            else -> "插上充电器"
        }
        remainTv.setTextColor(if (r.charging) TEXT else MUTED)

        // 只有真实功率才进曲线，避免未充电时把一根 0 线画进去
        showPower?.let { chart.push(it.toFloat()) }
    }

    /**
     * 长按大数字弹出的原始读数。
     *
     * 不是给普通用户看的，是排查工具：当界面数字明显不对时，能立刻看出是电流读错、
     * 电压推算偏了，还是根本就没在充电。
     */
    private fun showDiagnostics() {
        val r = last ?: return
        val body = """
电流: ${ChargeSampler.fmt2(r.currentA)} A
电压: ${ChargeSampler.fmt2(r.voltageV)} V
功率: ${ChargeSampler.fmt2(r.powerW)} W
电量: ${r.percent}%
温度: ${ChargeSampler.fmt1(r.tempC)} °C
充电中: ${r.charging}   充满: ${r.full}

说明：系统不允许应用读电池电压
（EXTRA_VOLTAGE 给的是 4 这个无效值），
所以电压按电量查表推算，功率误差约 3%。
电流是系统给的精确值。
        """.trimIndent()

        // 等宽字体，方便对齐数值；可滚动、可选中，便于复制出来发给我排查
        val tv = text(body, 12f, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            movementMethod = ScrollingMovementMethod()
        }
        AlertDialog.Builder(this)
            .setTitle("原始数据")
            .setView(tv)
            .setPositiveButton("关闭", null)
            .show()
    }
}
