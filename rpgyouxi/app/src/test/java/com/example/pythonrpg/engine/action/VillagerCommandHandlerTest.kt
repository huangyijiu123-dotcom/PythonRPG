package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.PlayerCommand
import kotlin.test.*

class VillagerCommandHandlerTest {
    
    @BeforeTest
    fun setup() {
        VillagerStateRegistry.clear()
    }

    @Test
    fun testAssignJob() {
        val handler = VillagerCommandHandler()
        assertTrue(handler.canHandle(PlayerCommand.AssignJob(1L, "LUMBERJACK", 10, 10)))
        
        handler.handle(PlayerCommand.AssignJob(1L, "LUMBERJACK", 10, 10))
        
        assertEquals("WORKING", VillagerStateRegistry.detailedStates[1L])
        assertEquals("LUMBERJACK", VillagerStateRegistry.originalJobs[1L])
    }

    @Test
    fun testReturnHome() {
        val handler = VillagerCommandHandler()
        handler.handle(PlayerCommand.ReturnHome(2L))
        assertEquals("RESTING", VillagerStateRegistry.detailedStates[2L])
    }

    @Test
    fun testEquipTool() {
        val handler = VillagerCommandHandler()
        handler.handle(PlayerCommand.EquipTool(3L, "IRON_AXE", 99L))
        assertEquals("EQUIPPING", VillagerStateRegistry.detailedStates[3L])
        assertEquals("IRON_AXE", VillagerStateRegistry.equipToolTargets[3L])
    }
}
