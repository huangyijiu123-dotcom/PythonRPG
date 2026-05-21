package com.example.pythonrpg.engine.weather

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.test.*

class WeatherEngineTest {

    // ── MDU 10.1-TEST: 天气系数矩阵精度测试 ───────────────────

    @Test
    fun testClearWeatherDefaultModifiers() {
        val engine = WeatherEngine()
        // 开局默认晴天
        val mods = engine.getModifiers()
        assertEquals(1.0f, mods.moveSpeedMultiplier, 0.001f)
        assertEquals(0, mods.fogVisionReduction)
    }

    @Test
    fun testRainModifiersAccuracy() {
        val engine = WeatherEngine()
        engine.forceSetWeather(WeatherType.RAIN, 5)
        val mods = engine.getModifiers()
        // 暴雨：伐木 +1.5，采矿 *0.7，农田 *0.8，移速 *0.5
        assertEquals(1.5f, mods.loggingYieldMultiplier, 0.001f)
        assertEquals(0.7f, mods.miningYieldMultiplier, 0.001f)
        assertEquals(0.8f, mods.farmingYieldMultiplier, 0.001f)
        assertEquals(0.5f, mods.moveSpeedMultiplier, 0.001f)
    }

    @Test
    fun testDroughtModifiersAccuracy() {
        val engine = WeatherEngine()
        engine.forceSetWeather(WeatherType.DROUGHT, 5)
        val mods = engine.getModifiers()
        // 干旱：农田暴跌 0.3，采矿升至 1.2
        assertEquals(0.3f, mods.farmingYieldMultiplier, 0.001f)
        assertEquals(1.2f, mods.miningYieldMultiplier, 0.001f)
    }

    @Test
    fun testColdWaveModifiers() {
        val engine = WeatherEngine()
        engine.forceSetWeather(WeatherType.COLD_WAVE, 5)
        val mods = engine.getModifiers()
        assertEquals(0.8f, mods.outdoorYieldMultiplier, 0.001f)
        assertEquals(1.5f, mods.energyCostMultiplier, 0.001f)
        assertEquals(1.2f, mods.foodConsumptionMultiplier, 0.001f)
    }

    @Test
    fun testSandstormModifiers() {
        val engine = WeatherEngine()
        engine.forceSetWeather(WeatherType.SANDSTORM, 5)
        val mods = engine.getModifiers()
        assertEquals(0.6f, mods.moveSpeedMultiplier, 0.001f)
        assertEquals(0.85f, mods.outdoorYieldMultiplier, 0.001f)
        assertEquals(2, mods.fogVisionReduction)
    }

    // ── MDU 10.2-TEST: 时钟递减与异色换庄测试 ────────────────

    @Test
    fun testTickCountdownDecrement() {
        val engine = WeatherEngine()
        engine.forceSetWeather(WeatherType.CLEAR, 5)
        engine.processTick()
        val state = engine.getCurrentWeather()
        assertEquals(WeatherType.CLEAR, state.current)
        assertEquals(4, state.ticksRemaining)
    }

    @Test
    fun testWeatherTransitionNoRepeat() {
        val engine = WeatherEngine()
        // 阴天剩余 1 tick，再 processTick 将到期换庄
        engine.forceSetWeather(WeatherType.OVERCAST, 1)
        engine.processTick()
        val state = engine.getCurrentWeather()
        // 断言 1：天气已不再是 OVERCAST（异色换庄）
        assertNotEquals(WeatherType.OVERCAST, state.current)
        // 断言 2：新持续时长在 [8,16]
        assertTrue(state.ticksRemaining in 8..16)
    }

    @Test
    fun testLongRunningStability() {
        val engine = WeatherEngine()
        engine.forceSetWeather(WeatherType.RAIN, 10)
        // 连续 100 次 processTick，验证无崩溃，持续时长始终为正整数
        repeat(100) {
            engine.processTick()
            val state = engine.getCurrentWeather()
            assertTrue(state.ticksRemaining > 0)
            assertNotNull(state.current)
        }
    }

    // ── MDU 10.3-TEST: 强制覆盖与广播去重测试 ────────────────

    @Test
    fun testForceSetWeatherApplied() {
        val engine = WeatherEngine()
        engine.forceSetWeather(WeatherType.COLD_WAVE, 24)
        val state = engine.getCurrentWeather()
        assertEquals(WeatherType.COLD_WAVE, state.current)
        assertEquals(24, state.ticksRemaining)
        // 寒潮系数立即生效
        assertEquals(1.5f, engine.getModifiers().energyCostMultiplier, 0.001f)
    }

    @Test
    fun testForceSetWeatherInvalidDurationThrows() {
        val engine = WeatherEngine()
        assertFailsWith<IllegalArgumentException> {
            engine.forceSetWeather(WeatherType.RAIN, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            engine.forceSetWeather(WeatherType.RAIN, -1)
        }
    }

    @Test
    fun testBroadcastDeduplicationSameWeather() = runTest {
        val engine = WeatherEngine()
        engine.forceSetWeather(WeatherType.OVERCAST, 10)
        val received = mutableListOf<WeatherType>()
        val job = launch { engine.weatherEventFlow.collect { received.add(it) } }
        // 强制设置同色天气，不应广播
        engine.forceSetWeather(WeatherType.OVERCAST, 5)
        delay(50)
        assertEquals(0, received.size, "同色天气不应触发广播")
        // 切换异色天气，应广播
        engine.forceSetWeather(WeatherType.RAIN, 5)
        delay(50)
        assertEquals(1, received.size)
        assertEquals(WeatherType.RAIN, received[0])
        job.cancel()
    }

    // ── MDU 10.4-TEST: 全系数防穿透安全断言 ─────────────────

    @Test
    fun testAllWeatherModifiersInBounds() {
        val engine = WeatherEngine()
        WeatherType.values().forEach { type ->
            engine.forceSetWeather(type, 5)
            val mods = engine.getModifiers()
            // 所有倍率须在 (0,3] 之间
            listOf(
                mods.moveSpeedMultiplier,
                mods.outdoorYieldMultiplier,
                mods.loggingYieldMultiplier,
                mods.farmingYieldMultiplier,
                mods.miningYieldMultiplier,
                mods.energyCostMultiplier,
                mods.foodConsumptionMultiplier
            ).forEach { value ->
                assertFalse(value.isNaN(), "不允许出现 NaN（天气：$type）")
                assertFalse(value.isInfinite(), "不允许出现 Infinity（天气：$type）")
                assertTrue(value >= 0.1f && value <= 3.0f, "系数 $value 超出合法范围（天气：$type）")
            }
            // 视野缩减格数须在 [0,4]
            assertTrue(mods.fogVisionReduction in 0..4, "视野缩减 ${mods.fogVisionReduction} 超出合法范围（天气：$type）")
        }
    }
}
