package com.example.pythonrpg.engine.weather

import kotlin.test.*

class WeatherEngineTest {

    @Test
    fun testForceWeather() {
        val engine = WeatherEngine()
        engine.forceWeather(WeatherType.STORM, 5)
        assertEquals(WeatherType.STORM, engine.currentWeather)
        assertEquals(5, engine.ticksRemaining)
        
        val mods = engine.getModifiers()
        assertEquals(0.5f, mods.loggingYieldMultiplier)
        assertEquals(2.0f, mods.energyCostMultiplier)
    }

    @Test
    fun testWeatherTickCycle() {
        val engine = WeatherEngine()
        engine.forceWeather(WeatherType.RAIN, 2)
        
        // Tick 1
        engine.processTick()
        assertEquals(1, engine.ticksRemaining)
        assertEquals(WeatherType.RAIN, engine.currentWeather)
        
        // Tick 2 (weather should expire and roll new weather)
        engine.processTick()
        // new weather is rolled, so ticksRemaining will be 12~23
        assertTrue(engine.ticksRemaining >= 12)
    }
}
