package com.example.pythonrpg.engine.script

import com.example.pythonrpg.engine.tick.TickEngine
import com.example.pythonrpg.engine.building.BuildingEngine
import com.example.pythonrpg.engine.entity.EntityStateManager
import com.example.pythonrpg.engine.map.GridMapData
import com.example.pythonrpg.engine.event.EventEngine
import com.example.pythonrpg.engine.event.EventConditionProvider
import com.example.pythonrpg.engine.market.MarketEngine
import com.example.pythonrpg.engine.tech.TechEngine
import com.example.pythonrpg.engine.tech.TechState
import com.example.pythonrpg.engine.action.ActionProcessor
import com.example.pythonrpg.shared.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PythonGlobalContextTest {
    private fun createSetup(): Pair<PythonScriptBridge, TechEngine> {
        val scope = CoroutineScope(Dispatchers.Default)
        val tickEngine = TickEngine(scope)
        val entityState = EntityStateManager()
        val buildingEngine = BuildingEngine()
        val gridMapData = GridMapData()
        
        gridMapData.setTile(TileData(Coordinate(3, 3), "FOREST", ExploreStatus.EXPLORED, false, false, null))
        
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
        return Pair(bridge, techEngine)
    }

    @Test
    fun testMemoryPersistenceBetweenRuns() {
        val (bridge, _) = createSetup()
        
        val script1 = "territory.memory['count'] = 42"
        val res1 = bridge.executeScript(script1)
        assertEquals("SUCCESS", res1.status)
        
        val script2 = """
            c = territory.memory.get('count')
            if c != 42:
                raise ValueError('memory did not persist!')
        """.trimIndent()
        val res2 = bridge.executeScript(script2)
        assertEquals("SUCCESS", res2.status)
    }

    @Test
    fun testAdvancedExplorationTechnologyLock() {
        val (bridge, techEngine) = createSetup()
        
        val script = "tile = map.get_tile(3, 3)"
        val res1 = bridge.executeScript(script)
        assertEquals("RUNTIME_ERROR", res1.status)
        assertTrue(res1.errorMessage?.contains("ADVANCED_EXPLORATION") == true || res1.errorMessage?.contains("SecurityException") == true)
        
        val node = techEngine.getTechTreeSnapshot().first { it.techId == "ADVANCED_EXPLORATION" }
        node.state = TechState.UNLOCKED
        
        val res2 = bridge.executeScript(script)
        assertEquals("SUCCESS", res2.status)
    }
}
