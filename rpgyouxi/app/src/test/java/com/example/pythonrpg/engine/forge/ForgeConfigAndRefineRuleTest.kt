package com.example.pythonrpg.engine.forge

import kotlin.test.*

class ForgeConfigAndRefineRuleTest {

    @Test
    fun testEquipmentRecipeAccuracy() {
        val engine = ForgeEngine()
        
        // 铁剑配方校验
        val swordRecipe = engine.templates["IRON_SWORD"] ?: throw AssertionError("IRON_SWORD recipe not found")
        assertEquals("WEAPON", swordRecipe.equipmentClass)
        assertEquals(100, swordRecipe.maxDurability)
        assertEquals(10, swordRecipe.baseStat)
        
        // 铁甲配方校验
        val armorRecipe = engine.templates["IRON_ARMOR"] ?: throw AssertionError("IRON_ARMOR recipe not found")
        assertEquals(10, armorRecipe.woodCost)
        assertEquals(10, armorRecipe.ironCost)
        assertEquals(6, armorRecipe.requiredTicks)
    }

    @Test
    fun testRefineRuleAccuracy() {
        val engine = ForgeEngine()
        
        // 当前等级 2 (目标升 +3)
        val rule2 = engine.getRefineRule(2)
        assertEquals(1.0, rule2.successChance, 1e-9)
        assertEquals(0, rule2.downgradePenalty)
        assertEquals(10, rule2.woodCost)
        assertEquals(5, rule2.stoneCost)
        assertEquals(0, rule2.ironCost)
        assertEquals(0, rule2.goldCost)
        assertEquals(0, rule2.obsidianCost)
        
        // 当前等级 4 (目标升 +5)
        val rule4 = engine.getRefineRule(4)
        assertEquals(0.7, rule4.successChance, 1e-9)
        assertEquals(1, rule4.downgradePenalty)
        assertEquals(0, rule4.woodCost)
        assertEquals(0, rule4.stoneCost)
        assertEquals(8, rule4.ironCost)
        assertEquals(20, rule4.goldCost)
        assertEquals(0, rule4.obsidianCost)
        
        // 当前等级 9 (目标升 +10)
        val rule9 = engine.getRefineRule(9)
        assertEquals(0.25, rule9.successChance, 1e-9)
        assertEquals(2, rule9.downgradePenalty)
        assertEquals(0, rule9.woodCost)
        assertEquals(0, rule9.stoneCost)
        assertEquals(0, rule9.ironCost)
        assertEquals(200, rule9.goldCost)
        assertEquals(5, rule9.obsidianCost)
    }
}
