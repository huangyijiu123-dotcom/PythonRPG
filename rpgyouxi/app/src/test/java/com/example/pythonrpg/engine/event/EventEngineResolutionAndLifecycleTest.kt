package com.example.pythonrpg.engine.event

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventEngineResolutionAndLifecycleTest {
    @Test
    fun testProcessTickAgingAndExpiration() = runBlocking {
        val mockProvider = object : EventConditionProvider {
            override fun getCurrentWeather() = "CLEAR"
            override fun getRandomForestBuilding(): Long? = null
            override fun getRandomFarmBuilding(): Long? = null
            override fun hasUndefeatedBoss() = false
        }
        val engine = EventEngine(mockProvider)
        
        val event = ActiveEvent(
            eventId = 10L,
            type = EventType.FOREST_FIRE,
            targetX = null,
            targetY = null,
            affectedBuildingId = 5L,
            remainingTicks = 2,
            severity = 2
        )
        engine.registerEvent(event)
        
        // 1st tick -> remainingTicks becomes 1
        engine.processTick(1L)
        assertEquals(1, engine.getActiveEvents()[0].remainingTicks)
        
        // 2nd tick -> remainingTicks becomes 0, expires!
        val flowJob = async {
            engine.eventFlow.first()
        }
        yield()
        engine.processTick(2L)
        
        val update = flowJob.await()
        assertEquals("EXPIRED", update.action)
        assertEquals(10L, update.event.eventId)
        assertTrue(engine.getActiveEvents().isEmpty())
    }

    @Test
    fun testBossRiotInfiniteLifespan() {
        val mockProvider = object : EventConditionProvider {
            override fun getCurrentWeather() = "CLEAR"
            override fun getRandomForestBuilding(): Long? = null
            override fun getRandomFarmBuilding(): Long? = null
            override fun hasUndefeatedBoss() = false
        }
        val engine = EventEngine(mockProvider)
        
        val bossRiot = ActiveEvent(
            eventId = 20L,
            type = EventType.BOSS_RIOT,
            targetX = null,
            targetY = null,
            affectedBuildingId = null,
            remainingTicks = -1,
            severity = 5
        )
        engine.registerEvent(bossRiot)
        
        // Process tick several times
        for (i in 1L..10L) {
            engine.processTick(i)
        }
        
        val active = engine.getActiveEvents()
        assertEquals(1, active.size)
        assertEquals(-1, active[0].remainingTicks, "Boss riot must remain -1 tick indefinitely")
    }

    @Test
    fun testResolveEventManualRescue() = runBlocking {
        val mockProvider = object : EventConditionProvider {
            override fun getCurrentWeather() = "CLEAR"
            override fun getRandomForestBuilding(): Long? = null
            override fun getRandomFarmBuilding(): Long? = null
            override fun hasUndefeatedBoss() = false
        }
        val engine = EventEngine(mockProvider)
        
        val event = ActiveEvent(
            eventId = 30L,
            type = EventType.PLAGUE,
            targetX = null,
            targetY = null,
            affectedBuildingId = null,
            remainingTicks = 6,
            severity = 3
        )
        engine.registerEvent(event)
        
        val flowJob = async {
            engine.eventFlow.first()
        }
        yield()
        
        val resolved = engine.resolveEvent(30L)
        assertTrue(resolved)
        
        val update = flowJob.await()
        assertEquals("RESOLVED", update.action)
        assertEquals(30L, update.event.eventId)
        assertTrue(engine.getActiveEvents().isEmpty())
        
        val resolvedAgain = engine.resolveEvent(30L)
        assertFalse(resolvedAgain)
    }
}
