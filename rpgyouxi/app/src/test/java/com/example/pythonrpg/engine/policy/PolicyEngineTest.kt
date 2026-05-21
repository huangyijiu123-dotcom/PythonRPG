package com.example.pythonrpg.engine.policy

import kotlin.test.*

class PolicyEngineTest {

    @Test
    fun testPolicyEnactAndRevoke() {
        val engine = PolicyEngine()
        
        assertFalse(engine.isPolicyActive("FRANTIC_GATHERING"))
        
        engine.enactPolicy("FRANTIC_GATHERING", true)
        assertTrue(engine.isPolicyActive("FRANTIC_GATHERING"))
        
        engine.enactPolicy("FRANTIC_GATHERING", false)
        assertFalse(engine.isPolicyActive("FRANTIC_GATHERING"))
    }

    @Test
    fun testModifiersAggregation() {
        val engine = PolicyEngine()
        engine.enactPolicy("FRANTIC_GATHERING", true) // harvest * 1.3, energyCost * 1.2
        engine.enactPolicy("FORCED_MARCH", true)      // moveSpeed * 1.5, energyCost * 1.5, caravanSpeed * 1.5
        
        val mods = engine.getModifiers()
        
        // Assert combined effects
        assertEquals(1.3f, mods.harvestYieldMultiplier)
        assertEquals(1.5f, mods.moveSpeedMultiplier)
        // 1.2 * 1.5 = 1.8
        assertEquals(1.8f, mods.energyCostMultiplier, 0.001f)
    }
}
