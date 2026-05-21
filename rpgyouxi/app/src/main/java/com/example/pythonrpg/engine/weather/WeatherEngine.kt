package com.example.pythonrpg.engine.weather

import com.example.pythonrpg.shared.WeatherModifiers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicReference

// ── 六大天气枚举（按说明书 10.1 严格定义）──────────────────────
enum class WeatherType {
    CLEAR,      // ☀️ 晴天
    OVERCAST,   // ☁️ 阴天
    RAIN,       // 🌧️ 暴雨
    DROUGHT,    // 🏜️ 干旱
    SANDSTORM,  // 🌬️ 沙尘暴
    COLD_WAVE   // ❄️ 寒潮
}

// ── 天气实时运行状态快照 ──────────────────────────────────────
data class WeatherState(
    val current: WeatherType,
    val ticksRemaining: Int
)

// ── 天气引擎 ──────────────────────────────────────────────────
class WeatherEngine {

    // 原子状态容器，开局默认晴天持续 10 ticks
    private val state = AtomicReference(WeatherState(WeatherType.CLEAR, 10))

    // 全局变化广播流（异色才触发，防冗余）
    private val _weatherEventFlow = MutableSharedFlow<WeatherType>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val weatherEventFlow: SharedFlow<WeatherType> = _weatherEventFlow

    // ── 硬编码静态系数矩阵（按说明书 10.1 精确数值）──────────
    private val modifierMatrix: Map<WeatherType, WeatherModifiers> = mapOf(
        WeatherType.CLEAR to WeatherModifiers(
            moveSpeedMultiplier      = 1.0f,
            outdoorYieldMultiplier   = 1.0f,
            loggingYieldMultiplier   = 1.0f,
            farmingYieldMultiplier   = 1.0f,
            miningYieldMultiplier    = 1.0f,
            energyCostMultiplier     = 1.0f,
            foodConsumptionMultiplier = 1.0f,
            fogVisionReduction       = 0
        ),
        WeatherType.OVERCAST to WeatherModifiers(
            outdoorYieldMultiplier   = 0.9f,
            fogVisionReduction       = 0
        ),
        WeatherType.RAIN to WeatherModifiers(
            loggingYieldMultiplier   = 1.5f,
            miningYieldMultiplier    = 0.7f,
            farmingYieldMultiplier   = 0.8f,
            moveSpeedMultiplier      = 0.5f,
            fogVisionReduction       = 0
        ),
        WeatherType.DROUGHT to WeatherModifiers(
            farmingYieldMultiplier   = 0.3f,
            miningYieldMultiplier    = 1.2f,
            fogVisionReduction       = 0
        ),
        WeatherType.SANDSTORM to WeatherModifiers(
            moveSpeedMultiplier      = 0.6f,
            outdoorYieldMultiplier   = 0.85f,
            fogVisionReduction       = 2
        ),
        WeatherType.COLD_WAVE to WeatherModifiers(
            outdoorYieldMultiplier   = 0.8f,
            energyCostMultiplier     = 1.5f,
            foodConsumptionMultiplier = 1.2f,
            fogVisionReduction       = 0
        )
    )

    // ── 数据读取 API ──────────────────────────────────────────
    fun getCurrentWeather(): WeatherState = state.get()

    fun getModifiers(): WeatherModifiers {
        val current = state.get().current
        return modifierMatrix[current] ?: WeatherModifiers()
    }

    // ── Tick 步进：CAS 原子倒计时 + 异色换庄 ─────────────────
    fun processTick() {
        while (true) {
            val currentVal = state.get()
            val nextVal: WeatherState

            if (currentVal.ticksRemaining > 1) {
                // 倒计时未到期，自减一格
                nextVal = currentVal.copy(ticksRemaining = currentVal.ticksRemaining - 1)
                if (state.compareAndSet(currentVal, nextVal)) break
            } else {
                // 倒计时到期，异色换庄（禁止连庄同一天气）
                val candidatePool = WeatherType.values().filter { it != currentVal.current }
                val newWeather = candidatePool.random()
                val nextDuration = (8..16).random()
                nextVal = WeatherState(newWeather, nextDuration)
                if (state.compareAndSet(currentVal, nextVal)) {
                    // 异色广播
                    _weatherEventFlow.tryEmit(newWeather)
                    break
                }
            }
        }
    }

    // ── 强制覆盖（GM / 剧情 / 测试）─────────────────────────
    fun forceSetWeather(type: WeatherType, durationTicks: Int) {
        require(durationTicks >= 1) { "持续时钟必须大于零！" }
        val oldVal = state.get()
        val newVal = WeatherState(type, durationTicks)
        state.set(newVal)
        // 异色哨兵：只有真正换了天气才广播
        if (type != oldVal.current) {
            _weatherEventFlow.tryEmit(type)
        }
    }
}
