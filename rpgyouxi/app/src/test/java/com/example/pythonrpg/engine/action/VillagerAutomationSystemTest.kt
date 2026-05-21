package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.Coordinate
import com.example.pythonrpg.shared.PolicyModifiers
import com.example.pythonrpg.shared.TimePeriod
import com.example.pythonrpg.shared.VillagerSnapshot
import com.example.pythonrpg.shared.VillagerStatus
import com.example.pythonrpg.engine.entity.EntityStateManager
import kotlin.test.*

class VillagerAutomationSystemTest {
    
    @BeforeTest
    fun setup() {
        VillagerStateRegistry.clear()
    }

    @Test
    fun testTwilightResting() {
        val system = VillagerAutomationSystem()
        VillagerStateRegistry.detailedStates[1L] = "WORKING"
        
        system.processTick(1L, TimePeriod.TWILIGHT, PolicyModifiers(), com.example.pythonrpg.shared.WeatherModifiers())
        
        assertEquals("RESTING", VillagerStateRegistry.detailedStates[1L])
    }

    @Test
    fun testWorkingResourceGatheringAndDelivering() {
        val esm = EntityStateManager()
        val system = VillagerAutomationSystem(esm)
        
        val villager = VillagerSnapshot(
            id = 1L, name = "V1", coordinate = Coordinate(0,0),
            status = VillagerStatus.WORKING, job = "LUMBERJACK",
            targetX = null, targetY = null, isInjured = false,
            energy = 100, backpack = mapOf("WOOD" to 9), equippedTools = emptyMap()
        )
        esm.registerVillager(villager)
        
        VillagerStateRegistry.detailedStates[1L] = "WORKING"
        VillagerStateRegistry.originalJobs[1L] = "LUMBERJACK"
        
        // Process tick 1: energy drops to 98, wood increases to 10 (still WORKING)
        system.processTick(1L, TimePeriod.DAYTIME, PolicyModifiers(), com.example.pythonrpg.shared.WeatherModifiers())
        
        assertEquals("WORKING", VillagerStateRegistry.detailedStates[1L])
        var updated = esm.getVillager(1L)!!
        assertEquals(98, updated.energy)
        assertEquals(10, updated.backpack["WOOD"])
        
        // Process tick 2: sees 10 wood, transitions to DELIVERING
        system.processTick(2L, TimePeriod.DAYTIME, PolicyModifiers(), com.example.pythonrpg.shared.WeatherModifiers())
        assertEquals("DELIVERING", VillagerStateRegistry.detailedStates[1L])
    }
}
