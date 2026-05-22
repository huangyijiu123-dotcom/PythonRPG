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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PythonScriptBridgeLifecycleTest {
    private fun createSetup(): Triple<PythonScriptBridge, TickEngine, ActionProcessor> {
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
        return Triple(bridge, tickEngine, actionProcessor)
    }

    @Test
    fun testVillageTranslatorFriendlyExceptionsAndHelpRecommendation() {
        val (bridge, _, _) = createSetup()
        
        // 1. NameError translation test
        val scriptNameError = "print(undefined_variable)"
        val res1 = bridge.executeScript(scriptNameError)
        assertEquals("RUNTIME_ERROR", res1.status)
        assertTrue(res1.errorMessage?.contains("村长") == true)
        assertTrue(res1.errorMessage?.contains("undefined_variable") == true)
        
        // 2. KeyError translation test
        val scriptKeyError = "d = {}\nprint(d['missing'])"
        val res2 = bridge.executeScript(scriptKeyError)
        assertEquals("RUNTIME_ERROR", res2.status)
        assertTrue(res2.errorMessage?.contains("村长") == true)
        assertTrue(res2.errorMessage?.contains("字典里没有这个键") == true)
        
        // 3. Trigger 3 consecutive ZeroDivisionErrors to get help recommendation!
        val scriptZero = "x = 1/0"
        
        val r1 = bridge.executeScript(scriptZero)
        assertEquals("RUNTIME_ERROR", r1.status)
        assertFalse(r1.errorMessage?.contains("帮助文档") == true)
        
        val r2 = bridge.executeScript(scriptZero)
        assertEquals("RUNTIME_ERROR", r2.status)
        assertFalse(r2.errorMessage?.contains("帮助文档") == true)
        
        val r3 = bridge.executeScript(scriptZero)
        assertEquals("RUNTIME_ERROR", r3.status)
        assertTrue(r3.errorMessage?.contains("帮助文档") == true)
        assertTrue(r3.errorMessage?.contains("3") == true)
    }

    @Test
    fun testStrategyAutostartQuietScheduling() = runBlocking {
        val (bridge, tickEngine, actionProcessor) = createSetup()
        
        val strategyScript = "territory.build(10, 10, 'COTTAGE')"
        bridge.registerStrategy(TimePeriod.NIGHT, strategyScript)
        
        var found = false
        val handler = object : com.example.pythonrpg.engine.action.CommandHandler {
            override fun canHandle(command: PlayerCommand): Boolean = true
            override fun handle(command: PlayerCommand) {
                if (command is PlayerCommand.BuildBuilding && command.buildingType == "COTTAGE") {
                    found = true
                }
            }
        }
        actionProcessor.registerHandler(handler)
        
        tickEngine.setGameSpeed(50L)
        tickEngine.start(initialTick = 16L) // Starts at 16 (TWILIGHT)
        
        // Wait for TickEngine to tick once to 17 (NIGHT)
        kotlinx.coroutines.delay(200L)
        
        actionProcessor.processPendingCommands()
        
        assertTrue(found, "NIGHT strategy build COTTAGE command must be auto-triggered on TimePeriod change")
        tickEngine.stop()
    }
}
