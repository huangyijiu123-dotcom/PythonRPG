package com.example.pythonrpg.engine.workshop

import kotlin.test.*

class WorkshopRecipeAndStateTest {

    @Test
    fun testRecipePrecisionAndTech() {
        val engine = WorkshopEngine()
        
        // 石斧 (STONE_AXE)：木材 5，科技 "STONE_TOOLS"
        val stoneAxeRecipe = engine.recipes[ToolType.STONE_AXE]
        assertNotNull(stoneAxeRecipe)
        assertEquals(5, stoneAxeRecipe.materialCost["WOOD"])
        assertEquals("STONE_TOOLS", stoneAxeRecipe.requiredTechId)
        assertEquals(3, stoneAxeRecipe.requiredTicks)

        // 铁镐 (IRON_PICKAXE)：木材 5，铁矿 3，科技 "IRON_FORGING"
        val ironPickaxeRecipe = engine.recipes[ToolType.IRON_PICKAXE]
        assertNotNull(ironPickaxeRecipe)
        assertEquals(5, ironPickaxeRecipe.materialCost["WOOD"])
        assertEquals(3, ironPickaxeRecipe.materialCost["IRON_ORE"])
        assertEquals("IRON_FORGING", ironPickaxeRecipe.requiredTechId)
        assertEquals(5, ironPickaxeRecipe.requiredTicks)
    }

    @Test
    fun testRegistrationAndStateDefense() {
        val engine = WorkshopEngine()
        
        // 尝试读取未注册工坊
        assertNull(engine.getWorkshopState(9999L))
        
        // 注册工坊 501L
        engine.registerWorkshop(501L, 2)
        val state = engine.getWorkshopState(501L)
        assertNotNull(state)
        assertEquals(501L, state.buildingId)
        assertEquals(2, state.maxSlots)
        assertTrue(state.activeTasks.isEmpty())
    }
}
