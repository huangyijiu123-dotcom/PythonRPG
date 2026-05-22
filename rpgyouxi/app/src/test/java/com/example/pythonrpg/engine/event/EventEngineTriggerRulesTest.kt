package com.example.pythonrpg.engine.event

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventEngineTriggerRulesTest {
    @Test
    fun testForestFireTriggeredOnDrought() {
        var currentWeather = "DROUGHT"
        var forestBuilding: Long? = 55L
        
        val provider = object : EventConditionProvider {
            override fun getCurrentWeather() = currentWeather
            override fun getRandomForestBuilding() = forestBuilding
            override fun getRandomFarmBuilding(): Long? = null
            override fun hasUndefeatedBoss() = false
        }
        
        val engine = EventEngine(provider)
        
        // Assert no forest fire if weather is CLEAR
        currentWeather = "CLEAR"
        for (i in 1..100) {
            engine.tryTriggerEvents()
        }
        assertTrue(engine.getActiveEvents().none { it.type == EventType.FOREST_FIRE })
        
        // Assert no forest fire if no forest building
        currentWeather = "DROUGHT"
        forestBuilding = null
        for (i in 1..100) {
            engine.tryTriggerEvents()
        }
        assertTrue(engine.getActiveEvents().none { it.type == EventType.FOREST_FIRE })
        
        // Assert triggered when DROUGHT and forest building is present
        forestBuilding = 55L
        var triggered = false
        for (i in 1..200) {
            engine.tryTriggerEvents()
            if (engine.getActiveEvents().any { it.type == EventType.FOREST_FIRE }) {
                triggered = true
                break
            }
        }
        assertTrue(triggered, "Forest fire should be triggered eventually on drought with lumber camp present")
    }

    @Test
    fun testColdSnapDeduplication() {
        val provider = object : EventConditionProvider {
            override fun getCurrentWeather() = "COLD_WAVE"
            override fun getRandomForestBuilding(): Long? = null
            override fun getRandomFarmBuilding(): Long? = null
            override fun hasUndefeatedBoss() = false
        }
        val engine = EventEngine(provider)
        
        for (i in 1..200) {
            engine.tryTriggerEvents()
        }
        val coldSnaps = engine.getActiveEvents().filter { it.type == EventType.COLD_SNAP }
        assertTrue(coldSnaps.isNotEmpty())
        assertEquals(1, coldSnaps.size, "Cold snap must strictly have deduplication - only 1 active at a time")
    }

    @Test
    fun testBossRiotDeduplicationAndInfiniteLifespan() {
        val provider = object : EventConditionProvider {
            override fun getCurrentWeather() = "CLEAR"
            override fun getRandomForestBuilding(): Long? = null
            override fun getRandomFarmBuilding(): Long? = null
            override fun hasUndefeatedBoss() = true
        }
        val engine = EventEngine(provider)
        
        for (i in 1..200) {
            engine.tryTriggerEvents()
        }
        val riots = engine.getActiveEvents().filter { it.type == EventType.BOSS_RIOT }
        assertTrue(riots.isNotEmpty())
        assertEquals(1, riots.size, "Boss riot must strictly have deduplication")
        assertEquals(-1, riots[0].remainingTicks, "Boss riot must have infinite duration (-1 remainingTicks)")
    }
}
