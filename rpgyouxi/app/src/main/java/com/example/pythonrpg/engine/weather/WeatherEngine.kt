package com.example.pythonrpg.engine.weather

import com.example.pythonrpg.shared.WeatherModifiers
import kotlin.random.Random

enum class WeatherType {
    CLEAR, RAIN, SNOW, FOG, STORM
}

class WeatherEngine(private val random: Random = Random(System.currentTimeMillis())) {
    
    var currentWeather: WeatherType = WeatherType.CLEAR
        private set
        
    var ticksRemaining: Int = 0
        private set

    init {
        rollWeather()
    }

    fun processTick() {
        if (ticksRemaining > 0) {
            ticksRemaining--
        }
        if (ticksRemaining <= 0) {
            rollWeather()
        }
    }

    private fun rollWeather() {
        val roll = random.nextInt(100)
        currentWeather = when {
            roll < 50 -> WeatherType.CLEAR
            roll < 70 -> WeatherType.RAIN
            roll < 80 -> WeatherType.SNOW
            roll < 90 -> WeatherType.FOG
            else -> WeatherType.STORM
        }
        ticksRemaining = random.nextInt(12) + 12 // 持续 12~23 个 tick
    }

    // 作弊接口，便于测试与控制
    fun forceWeather(weatherType: WeatherType, duration: Int) {
        currentWeather = weatherType
        ticksRemaining = duration
    }

    fun getModifiers(): WeatherModifiers {
        return when (currentWeather) {
            WeatherType.CLEAR -> WeatherModifiers()
            WeatherType.RAIN -> WeatherModifiers(
                farmingYieldMultiplier = 1.5f,
                moveSpeedMultiplier = 0.8f
            )
            WeatherType.SNOW -> WeatherModifiers(
                energyCostMultiplier = 1.5f,
                moveSpeedMultiplier = 0.7f
            )
            WeatherType.FOG -> WeatherModifiers(
                fogVisionReduction = 2,
                moveSpeedMultiplier = 0.9f
            )
            WeatherType.STORM -> WeatherModifiers(
                loggingYieldMultiplier = 0.5f,
                miningYieldMultiplier = 0.5f,
                energyCostMultiplier = 2.0f,
                moveSpeedMultiplier = 0.5f
            )
        }
    }
}
