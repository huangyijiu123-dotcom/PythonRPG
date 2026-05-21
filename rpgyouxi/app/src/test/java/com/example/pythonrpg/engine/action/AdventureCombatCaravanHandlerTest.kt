package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.PlayerCommand
import kotlin.test.*

class AdventureCombatCaravanHandlerTest {
    @Test
    fun testCanHandleRouting() {
        val handler = AdventureCombatCaravanHandler()
        assertTrue(handler.canHandle(PlayerCommand.DispatchAdventurer(1L, 0, 0)))
        assertTrue(handler.canHandle(PlayerCommand.RecallAdventurer(1L)))
        assertTrue(handler.canHandle(PlayerCommand.AssignCaravanTarget(2L, 10, 10)))
        assertTrue(handler.canHandle(PlayerCommand.StartCaravan(2L)))
        assertTrue(handler.canHandle(PlayerCommand.RecallCaravan(2L)))
        assertTrue(handler.canHandle(PlayerCommand.TradeWithCityState(2L, 99L, true, "WOOD", 50)))
        
        assertFalse(handler.canHandle(PlayerCommand.RecruitVillager))
    }
}
