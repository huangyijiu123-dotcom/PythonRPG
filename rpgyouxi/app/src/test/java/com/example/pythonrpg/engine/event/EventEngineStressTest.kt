package com.example.pythonrpg.engine.event

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventEngineStressTest {
    @Test
    fun testConcurrencyAndStress() = runBlocking {
        val provider = object : EventConditionProvider {
            override fun getCurrentWeather() = "COLD_WAVE"
            override fun getRandomForestBuilding(): Long? = null
            override fun getRandomFarmBuilding(): Long? = null
            override fun hasUndefeatedBoss() = false
        }
        val engine = EventEngine(provider)

        // Launch 100 coroutines in parallel performing registers, ticks, and resolves
        val jobs = List(100) { id ->
            launch(Dispatchers.Default) {
                val myEvent = ActiveEvent(
                    eventId = id.toLong() + 1000L,
                    type = EventType.FOREST_FIRE,
                    targetX = null,
                    targetY = null,
                    affectedBuildingId = null,
                    remainingTicks = 5,
                    severity = 1
                )
                engine.registerEvent(myEvent)
                
                engine.processTick(id.toLong())
                
                engine.resolveEvent(id.toLong() + 1000L)
            }
        }
        
        jobs.forEach { it.join() }
        
        val coldSnaps = engine.getActiveEvents().filter { it.type == EventType.COLD_SNAP }
        assertTrue(coldSnaps.size <= 1, "There should be at most 1 active COLD_SNAP event under concurrent stress")
    }
}
