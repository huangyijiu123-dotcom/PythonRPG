package com.example.pythonrpg.engine.event

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventEngineLifecycleContainerTest {
    @Test
    fun testRegisterEventAndBroadcast() = runBlocking {
        val mockProvider = object : EventConditionProvider {
            override fun getCurrentWeather() = "CLEAR"
            override fun getRandomForestBuilding(): Long? = null
            override fun getRandomFarmBuilding(): Long? = null
            override fun hasUndefeatedBoss() = false
        }
        val engine = EventEngine(mockProvider)
        
        val event = ActiveEvent(
            eventId = 42L,
            type = EventType.FOREST_FIRE,
            targetX = 3,
            targetY = 4,
            affectedBuildingId = 100L,
            remainingTicks = 4,
            severity = 2
        )
        
        // Capture first flow emit
        val flowJob = async {
            engine.eventFlow.first()
        }
        yield()
        
        engine.registerEvent(event)
        
        val update = flowJob.await()
        assertEquals("SPAWNED", update.action)
        assertEquals(42L, update.event.eventId)
        assertEquals(EventType.FOREST_FIRE, update.event.type)
        assertEquals(3, update.event.targetX)
        assertEquals(4, update.event.targetY)
        assertEquals(100L, update.event.affectedBuildingId)
        assertEquals(4, update.event.remainingTicks)
        assertEquals(2, update.event.severity)
        
        val active = engine.getActiveEvents()
        assertEquals(1, active.size)
        assertEquals(42L, active[0].eventId)
    }
}
