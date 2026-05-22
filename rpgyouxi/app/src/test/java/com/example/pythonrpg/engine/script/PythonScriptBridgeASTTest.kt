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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PythonScriptBridgeASTTest {
    private fun createBridge(): PythonScriptBridge {
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
        return PythonScriptBridge(
            tickEngine, entityState, buildingEngine, gridMapData, eventEngine, techEngine, marketEngine, actionProcessor, scope
        )
    }

    @Test
    fun testASTBlockingImport() {
        val bridge = createBridge()
        val script = "import os\nprint('hello')"
        val res = bridge.executeScript(script)
        assertEquals("AST_VIOLATION", res.status)
        assertTrue(res.errorMessage?.contains("import") == true)
    }

    @Test
    fun testASTBlockingExecEval() {
        val bridge = createBridge()
        val script = "eval('1+1')"
        val res = bridge.executeScript(script)
        assertEquals("AST_VIOLATION", res.status)
        assertTrue(res.errorMessage?.contains("eval") == true)
    }

    @Test
    fun testASTBlockingNakedExcept() {
        val bridge = createBridge()
        val script = "try:\n    x = 1/0\nexcept:\n    pass"
        val res = bridge.executeScript(script)
        assertEquals("AST_VIOLATION", res.status)
        assertTrue(res.errorMessage?.contains("except") == true)
    }

    @Test
    fun testASTBlockingWhileTrueDeadlock() {
        val bridge = createBridge()
        val script = "while True:\n    print('deadlock')"
        val res = bridge.executeScript(script)
        assertEquals("AST_VIOLATION", res.status)
        assertTrue(res.errorMessage?.contains("while") == true)
    }

    @Test
    fun testTimeoutProcessKill() {
        val bridge = createBridge()
        val script = """
            x = 0
            while x < 1000000000000:
                x = x + 1
        """.trimIndent()
        val res = bridge.executeScript(script)
        assertEquals("TIMEOUT", res.status)
    }
}
