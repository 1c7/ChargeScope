package app.chargescope

// ChargeSampler.kt —— 数据层：从系统读出电池电流，推算电压，算出功率和剩余时间。
// 全应用只有这一个文件接触系统电池接口，界面层只消费 Reading，不关心数值怎么来的。

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Locale
import kotlin.math.abs

/**
 * 一次采样的完整结果。
 *
 * 字段全部可空：这台机器上有些数据拿不到，拿不到就是 null，界面显示 "--"，
 * 不用 0 顶替，避免把「读不到」伪装成「是 0」。
 */
data class Reading(
    /** 进电池的功率，单位 W。电流或电压任一缺失时为 null */
    val powerW: Double?,
    /** 电池电流，单位 A，已取绝对值 */
    val currentA: Double?,
    /** 电池电压，单位 V。可能是推算值，见 [ChargeSampler.read] */
    val voltageV: Double?,
    /** 电量百分比，0–100 */
    val percent: Int?,
    /** 电池温度，单位 ℃ */
    val tempC: Double?,
    /** 是否正在充电：插着电源且状态为充电中 */
    val charging: Boolean,
    /** 是否已充满 */
    val full: Boolean,
    /** 预计还需多少分钟充满，仅充电过程中才有值 */
    val remainMin: Int?
)

/**
 * 电池采样器。
 *
 * 无状态单例，每次调用都实时读系统接口，不做缓存——充电功率变化很快，
 * 缓存会让界面显示滞后的数字。
 */
object ChargeSampler {

    /** 电池总能量，单位 Wh。本机 5700mAh 双电芯约 22Wh，仅用于估算剩余时间 */
    private const val BATTERY_WH = 22.0

    /**
     * 读一次当前状态。
     *
     * 电流取自 BatteryManager（精确值），电压系统可能不提供，此时按电量查表推算
     * （误差约 3%，见 [estimateVoltage]）。任何一项失败都退化成 null，不向界面抛异常。
     *
     * @param context 任意 Context，用于取系统服务和读粘性广播
     */
    fun read(context: Context): Reading {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        // ACTION_BATTERY_CHANGED 是粘性广播，传 null 的 receiver 可以同步取到最近一次的值
        val info: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // 状态、供电来源、电量、温度、电压：都从同一个广播里取，保证是同一次采样的快照
        val status = info?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = info?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val level = info?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = info?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val rawTemp = info?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val rawV = info?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1

        // 电量是 level/scale 的比例，scale 通常为 100，但不保证，所以按比例算
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        // 温度单位是 0.1℃，除以 10 得摄氏度
        val tempC = if (rawTemp > 0) rawTemp / 10.0 else null

        // 电压只采信合理区间（3.0–5.0V，单节锂电池的工作范围）。
        // 这台 OPPO 返回的是 4 这个垃圾值，落在区间外，于是走推算分支。
        val voltage = if (rawV in 3000..5000) rawV / 1000.0 else estimateVoltage(percent)

        // getIntProperty 对「本机不存在的属性 ID」会抛 SecurityException（需要 BATTERY_STATS
        // 系统权限），所以必须逐个捕获，不能裸调。捕获后按「无数据」处理。
        val rawNow = try {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        } catch (e: Throwable) {
            Int.MIN_VALUE
        }

        // 单位陷阱：Android 文档写 µA，但联发科平台实际给 mA（本机 -1318 表示 1.318A）。
        // 按数值幅度判单位而不是猜平台：正常充电电流不可能超过 100A，
        // 所以 ≥100000 只能是 µA，否则只能是 mA。
        val current = if (rawNow == 0 || rawNow == Int.MIN_VALUE) null
        else abs(rawNow) / if (abs(rawNow) >= 100_000) 1_000_000.0 else 1_000.0

        val power = if (voltage != null && current != null) voltage * current else null

        // 判定充电不能只看电流符号：拔线后仍有放电电流，那也会被乘成一个「功率」。
        // 必须以插件状态和充电状态为准。
        val charging = plugged != 0 && status == BatteryManager.BATTERY_STATUS_CHARGING
        val full = status == BatteryManager.BATTERY_STATUS_FULL || percent == 100

        // 剩余时间：剩余能量 ÷ 当前功率。纯线性估算，把当前功率当成未来恒定值，
        // 因此实际功率会随电量下降，高电量时这个数偏乐观。上下限截断避免出现 0 或天文数字。
        val remain = if (charging && power != null && power > 0.5 && percent != null && percent < 100) {
            val whLeft = (100 - percent) / 100.0 * BATTERY_WH
            (whLeft / power * 60).toInt().coerceIn(1, 999)
        } else null

        return Reading(power, current, voltage, percent, tempC, charging, full, remain)
    }

    /**
     * 按电量推算电压：系统不给电压时的兜底方案。
     *
     * 依据是锂电池的「电量—开路电压」曲线基本固定，用 11 个采样点做线性插值。
     * 误差约 3%：充电过程中有极化压升，推算值会略高于真实端电压。
     *
     * @param percent 电量百分比，为 null 时无法推算，返回 null
     */
    private fun estimateVoltage(percent: Int?): Double? {
        if (percent == null) return null
        // 索引 0–10 分别对应 0%、10%、…、100% 电量下的电压
        val t = doubleArrayOf(3.55, 3.72, 3.82, 3.90, 3.97, 4.04, 4.12, 4.20, 4.30, 4.38, 4.46)
        val p = percent.coerceIn(0, 100) / 10.0   // 换算成「第几个区间」，如 62% → 6.2
        val i = p.toInt().coerceIn(0, 9)          // 落在哪两个采样点之间
        // 在 t[i] 和 t[i+1] 之间按小数部分线性插值
        return t[i] * (1 - (p - i)) + t[i + 1] * (p - i)
    }

    /** 把瓦数格式化成一位小数，null 显示 "--" */
    fun fmt1(v: Double?): String = v?.let { String.format(Locale.US, "%.1f", it) } ?: "--"

    /** 把数值格式化成两位小数，null 显示 "--" */
    fun fmt2(v: Double?): String = v?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
}
