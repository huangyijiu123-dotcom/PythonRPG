package com.example.pythonrpg.engine.action

import com.example.pythonrpg.engine.entity.EntityStateManager
import com.example.pythonrpg.engine.policy.PolicyEngine
import com.example.pythonrpg.engine.weather.WeatherEngine
import com.example.pythonrpg.engine.weather.WeatherType
import com.example.pythonrpg.shared.*
import kotlin.test.*

class Phase4EndToEndMilestoneTest {

    @BeforeTest
    fun setup() {
        VillagerStateRegistry.clear()
    }

    @Test
    fun testWeatherAndPolicyMultipliers() {
        // 1. 初始化引擎
        val weatherEngine = WeatherEngine()
        val policyEngine = PolicyEngine()
        val entityState = EntityStateManager()

        val automationSystem = VillagerAutomationSystem(entityState)
        val actionProcessor = ActionProcessor()
        actionProcessor.setAutomationSystem(automationSystem)
        actionProcessor.registerHandler(VillagerCommandHandler())

        // 2. 准备数据
        val wId = 99L
        entityState.registerWarehouse(WarehouseSnapshot(
            id = wId, coordinate = Coordinate(0,0), capacity = 1000, inventory = emptyMap()
        ))
        val vId = 1L
        entityState.registerVillager(VillagerSnapshot(
            id = vId, name = "Alice", coordinate = Coordinate(0,0),
            status = VillagerStatus.IDLE, job = "NONE", targetX = null, targetY = null,
            isInjured = false, energy = 100, backpack = emptyMap(), equippedTools = emptyMap()
        ))

        // 分配工作
        actionProcessor.queueCommand(PlayerCommand.AssignJob(vId, "LUMBERJACK", 1, 1))

        // 设置极端天气和法令
        weatherEngine.forceWeather(WeatherType.STORM, 100) 
        policyEngine.enactPolicy("FRANTIC_GATHERING", true) 

        // 预期系数: 
        // yield: loggingYield = 0.5, harvestYield = 1.3 -> totalYield = 0.65
        // energy: STORM energyCost = 2.0, FRANTIC energyCost = 1.2 -> 2.0 * 1.2 * 2(base) = 4.8

        // 模拟运行 1 个 Tick
        val tick1 = TickEvent(1L, System.currentTimeMillis(), TimePeriod.DAYTIME)
        actionProcessor.processTick(tick1, policyEngine.getModifiers(), weatherEngine.getModifiers())

        val v1 = entityState.getVillager(vId)!!
        println("Tick 1 Energy: ${v1.energy}, Backpack: ${v1.backpack}")
        
        // 能量应该大约减少了 4 还是 5 
        assertTrue(v1.energy <= 96) 

        // 再跑10个Tick
        for (i in 2L..11L) {
            val t = TickEvent(i, System.currentTimeMillis(), TimePeriod.DAYTIME)
            actionProcessor.processTick(t, policyEngine.getModifiers(), weatherEngine.getModifiers())
        }

        val vFinal = entityState.getVillager(vId)!!
        println("Final Energy: ${vFinal.energy}, Backpack: ${vFinal.backpack}")
    }
}
