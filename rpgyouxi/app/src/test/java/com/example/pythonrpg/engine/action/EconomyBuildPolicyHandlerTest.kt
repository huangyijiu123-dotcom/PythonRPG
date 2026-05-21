package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.PlayerCommand
import com.example.pythonrpg.engine.building.BuildingEngine
import kotlin.test.*

class EconomyBuildPolicyHandlerTest {
    @Test
    fun testBuildBuildingRouting() {
        val engine = BuildingEngine()
        val handler = EconomyBuildPolicyHandler(engine)
        
        assertTrue(handler.canHandle(PlayerCommand.BuildBuilding(0, 0, "COTTAGE")))
        assertTrue(handler.canHandle(PlayerCommand.UpgradeBuilding(1, 1)))
        assertTrue(handler.canHandle(PlayerCommand.RecruitAdventurer))
        assertTrue(handler.canHandle(PlayerCommand.StartResearch("TECH_1")))
        
        handler.handle(PlayerCommand.BuildBuilding(1, 1, "LUMBER_CAMP")) 
        // No dependency provider, will fail silently or just not build, but we check if it passes routing safely.
    }
}
