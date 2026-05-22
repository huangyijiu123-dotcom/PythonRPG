package com.example.pythonrpg.engine.script

import com.example.pythonrpg.engine.tick.TickEngine
import com.example.pythonrpg.engine.building.BuildingEngine
import com.example.pythonrpg.engine.entity.EntityStateManager
import com.example.pythonrpg.engine.map.GridMapData
import com.example.pythonrpg.engine.event.EventEngine
import com.example.pythonrpg.engine.event.EventConditionProvider
import com.example.pythonrpg.engine.market.MarketEngine
import com.example.pythonrpg.engine.tech.TechEngine
import com.example.pythonrpg.engine.action.ActionProcessor
import com.example.pythonrpg.shared.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PythonCommandGenerationTest {
    private fun createSetup(): Pair<PythonScriptBridge, EntityStateManager> {
        val scope = CoroutineScope(Dispatchers.Default)
        val tickEngine = TickEngine(scope)
        val entityState = EntityStateManager()
        val buildingEngine = BuildingEngine()
        val gridMapData = GridMapData()
        val eventProvider = object : EventConditionProvider {
            override fun getCurrentWeather() = "CLEAR"
            override fun getRandomForestBuilding(): Long? = null
            override fun getRandomFarmBuilding(): Long? = null
            override fun hasUndefeatedBoss() = false
        }
        val eventEngine = EventEngine(eventProvider)
        val techEngine = TechEngine()
        val marketEngine = MarketEngine()
        val actionProcessor = ActionProcessor()
        
        val bridge = PythonScriptBridge(
            tickEngine, entityState, buildingEngine, gridMapData, eventEngine, techEngine, marketEngine, actionProcessor, scope
        )
        return Pair(bridge, entityState)
    }

    @Test
    fun testCommandTranslationAndNearestEquipTool() {
        val (bridge, entityState) = createSetup()
        
        val villager = VillagerSnapshot(
            id = 10L,
            name = "Jack",
            coordinate = Coordinate(5, 5),
            status = VillagerStatus.IDLE,
            job = "NONE",
            targetX = null,
            targetY = null,
            isInjured = false,
            energy = 100,
            backpack = emptyMap(),
            equippedTools = emptyMap()
        )
        entityState.registerVillager(villager)
        
        val wh1 = WarehouseSnapshot(
            id = 101L,
            coordinate = Coordinate(6, 6),
            capacity = 100,
            inventory = mapOf("AXE" to 0)
        )
        val wh2 = WarehouseSnapshot(
            id = 102L,
            coordinate = Coordinate(8, 8),
            capacity = 100,
            inventory = mapOf("AXE" to 3)
        )
        entityState.registerWarehouse(wh1)
        entityState.registerWarehouse(wh2)
        
        val script = """
            v = territory.get_all_villagers()[0]
            v.assign_job('LUMBERJACK', 3, 3)
            equipped = v.equip_tool('AXE')
            if not equipped:
                raise ValueError('Equip tool failed!')
        """.trimIndent()
        
        val res = bridge.executeScript(script)
        assertEquals("SUCCESS", res.status)
        assertEquals(2, res.commands.size)
        
        val cmd1 = res.commands[0] as PlayerCommand.AssignJob
        assertEquals(10L, cmd1.villagerId)
        assertEquals("LUMBERJACK", cmd1.job)
        assertEquals(3, cmd1.targetX)
        assertEquals(3, cmd1.targetY)
        
        val cmd2 = res.commands[1] as PlayerCommand.EquipTool
        assertEquals(10L, cmd2.villagerId)
        assertEquals("AXE", cmd2.toolId)
        assertEquals(102L, cmd2.warehouseId)
    }

    @Test
    fun testCommandBufferingFloodProtection() {
        val (bridge, entityState) = createSetup()
        
        val villager = VillagerSnapshot(
            id = 1L,
            name = "Jack",
            coordinate = Coordinate(5, 5),
            status = VillagerStatus.IDLE,
            job = "NONE",
            targetX = null,
            targetY = null,
            isInjured = false,
            energy = 100,
            backpack = emptyMap(),
            equippedTools = emptyMap()
        )
        entityState.registerVillager(villager)
        
        val script = """
            v = territory.get_all_villagers()[0]
            for i in range(300):
                v.assign_job('MINER', 1, 1)
        """.trimIndent()
        
        val res = bridge.executeScript(script)
        assertEquals("SUCCESS", res.status)
        assertEquals(200, res.commands.size)
        assertTrue(res.errorMessage != null)
        assertTrue(res.errorMessage!!.contains("村长警告") || res.errorMessage!!.contains("200"))
    }
}
