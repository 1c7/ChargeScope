package app.chargescope

// SparklineView.kt —— 自绘的 60 秒功率曲线。
// 不引第三方图表库（省下几 MB 依赖），只用 Canvas 画折线 + 填充 + 平均线，约 120 行。

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 示波器式的滚动曲线：最新点在右边，历史点往左推，画满一屏就是最近 60 秒。
 *
 * 只负责画，不做采样——数据由 MainActivity 通过 [push] 喂进来。
 */
class SparklineView(context: Context) : View(context) {

    /** 已采样的功率值，单位 W，按时间先后排列 */
    private val values = ArrayList<Float>()

    /** 最多保留多少点。500ms 一个点 × 120 = 60 秒窗口 */
    private val capacity = 120

    private val d = resources.displayMetrics.density

    /** 主折线：绿色，2dp 宽，圆角接头 */
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3DDC97.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /** 折线下方的半透明填充：0x22 是约 13% 不透明度 */
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x223DDC97
        style = Paint.Style.FILL
    }

    /** 背景横格线：白色 10% 不透明度，只做参考，不标注刻度 */
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1AFFFFFF
        strokeWidth = 1f * d
    }

    /** 平均值虚线：琥珀色，让人一眼看出当前是高于还是低于本次平均 */
    private val avgLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66F5A524
        strokeWidth = 1f * d
        style = Paint.Style.STROKE
    }

    private val path = Path()     // 折线路径
    private val fillPath = Path() // 填充区域路径（折线 + 底边闭合）

    /** 清空历史。切换充电状态导致曲线断开时调用 */
    fun clear() {
        values.clear()
        invalidate()
    }

    /**
     * 追加一个采样点并重绘。
     *
     * @param v 功率，单位 W
     */
    fun push(v: Float) {
        values.add(v)
        // 超过容量就丢掉最老的点，形成滚动窗口
        if (values.size > capacity) values.removeAt(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return // 还没测量出尺寸，画不了

        // 先画三条等分的横格线
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, grid)
        }

        if (values.size < 2) return // 一个点连不成线，等下一个点

        // ---- 第一步：算纵轴范围 ----
        // 纵轴跟着数据自适应，同时保证一个最小跨度。若直接从 0 起算、最大值取峰值 ×1.15，
        // 功率平稳时曲线会贴死在顶部，下面的填充区被涂成一整块绿，看着像渲染错误。
        var hi = values[0]
        var lo = values[0]
        var sum = 0f
        for (v in values) {
            if (v > hi) hi = v
            if (v < lo) lo = v
            sum += v
        }
        val avg = sum / values.size
        // 最小跨度取「数据范围的 1 倍」和「峰值的 30%」「0.8W」三者中的最大者，
        // 保证几乎没有波动时也能看出一点起伏，而不是一条死线
        val span = maxOf(hi - lo, maxOf(hi * 0.3f, 0.8f))
        val mid = (hi + lo) / 2f
        var top = mid + span * 0.75f
        var bottom = mid - span * 0.75f
        // 下限不要跌破 0，否则会出现负功率这种不存在的区间
        if (bottom < 0f) {
            top -= bottom
            bottom = 0f
        }

        // 把功率值映射成屏幕 y 坐标（屏幕坐标 y 向下增长，所以要翻转）
        fun yOf(v: Float): Float =
            h - ((v - bottom) / (top - bottom)).coerceIn(0f, 1f) * h

        // ---- 第二步：逐点连成折线 ----
        // x 步长按「满容量」算而不是按「当前点数」算，这样刚开始采集时曲线只占右边一小段，
        // 随时间推移自然向左生长，横轴比例始终不变
        val stepX = w / (capacity - 1).toFloat()
        val rightmost = w

        path.reset()
        fillPath.reset()
        for (i in values.indices) {
            // 最新点贴右边，往左生长，像示波器
            val x = rightmost - (values.size - 1 - i) * stepX
            val y = yOf(values[i])
            if (i == 0) {
                // 第一个点：折线起点；填充区还要从底部竖直连上来
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        // 收尾：从最后一个点垂直到右下角，再沿底边闭合，形成实心区域
        val lastX = rightmost
        fillPath.lineTo(lastX, h)
        fillPath.close()

        // 先填后描，折线才不会被填充色盖住边缘
        canvas.drawPath(fillPath, fill)
        canvas.drawPath(path, line)

        // ---- 第三步：平均线 ----
        val avgY = yOf(avg)
        canvas.drawLine(0f, avgY, w, avgY, avgLine)
    }
}
