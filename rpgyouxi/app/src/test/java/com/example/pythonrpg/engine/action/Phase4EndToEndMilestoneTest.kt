package com.example.pythonrpg.engine.action

import com.example.pythonrpg.engine.entity.EntityStateManager
import com.example.pythonrpg.engine.policy.PolicyEngine
import com.example.pythonrpg.engine.policy.PolicyDependencyProvider
import com.example.pythonrpg.engine.policy.PolicyType
import com.example.pythonrpg.engine.weather.WeatherEngine
import com.example.pythonrpg.engine.weather.WeatherType
import com.example.pythonrpg.shared.*
import kotlin.test.*

class Phase4EndToEndMilestoneTest {

    private fun makeProvider(maxActive: Int = 3): PolicyDependencyProvider =
        object : PolicyDependencyProvider {
            private var gold = 999999
            override fun deductGold(amount: Int): Boolean {
                return if (gold >= amount) { gold -= amount; true } else false
            }
            override fun getMaxActivePolicies(): Int = maxActive
        }

    @BeforeTest
    fun setup() {
        VillagerStateRegistry.clear()
    }

    @Test
    fun testWeatherAndPolicyMultipliers() {
        // 1. 初始化引擎
        val weatherEngine = WeatherEngine()
        val policyEngine = PolicyEngine(makeProvider())
        val entityState = EntityStateManager()

        val automationSystem = VillagerAutomationSystem(entityState)
        val actionProcessor = ActionProcessor()
        actionProcessor.setAutomationSystem(automationSystem)
        actionProcessor.registerHandler(VillagerCommandHandler())

        // 2. 准备数据
        val wId = 99L
        entityState.registerWarehouse(
            WarehouseSnapshot(id = wId, coordinate = Coordinate(0, 0), capacity = 1000, inventory = emptyMap())
        )
        val vId = 1L
        entityState.registerVillager(
            VillagerSnapshot(
                id = vId, name = "Alice", coordinate = Coordinate(0, 0),
                status = VillagerStatus.IDLE, job = "NONE", targetX = null, targetY = null,
                isInjured = false, energy = 100, backpack = emptyMap(), equippedTools = emptyMap()
            )
        )

        // 分配工作
        actionProcessor.queueCommand(PlayerCommand.AssignJob(vId, "LUMBERJACK", 1, 1))

        // 3. 设置极端天气：COLD_WAVE（energyCost * 1.5）
        //    法令：FRANTIC_GATHERING（harvestYield * 1.3, energyCost * 2.0）
        weatherEngine.forceSetWeather(WeatherType.COLD_WAVE, 100)
        policyEngine.enactPolicy(PolicyType.FRANTIC_GATHERING, true)

        // 预期：energyCost 叠乘 = 1.5 (weather) * 2.0 (policy) * base_2 = 6.0 per tick

        // 4. 模拟 1 个 Tick
        val tick1 = TickEvent(1L, System.currentTimeMillis(), TimePeriod.DAYTIME)
        actionProcessor.processTick(tick1, policyEngine.getModifiers(), weatherEngine.getModifiers())

        val v1 = entityState.getVillager(vId)!!
        println("Tick 1 Energy: ${v1.energy}, Backpack: ${v1.backpack}")
        // 体力应该大量消耗（极端天气 + 狂热采集）
        assertTrue(v1.energy < 100, "体力应已减少")

        // 5. 再跑 10 个 Tick
        for (i in 2L..11L) {
            val t = TickEvent(i, System.currentTimeMillis(), TimePeriod.DAYTIME)
            actionProcessor.processTick(t, policyEngine.getModifiers(), weatherEngine.getModifiers())
        }

        val vFinal = entityState.getVillager(vId)!!
        println("Final Energy: ${vFinal.energy}, Backpack: ${vFinal.backpack}")
        // 经过11个Tick，体力应大量消耗
        assertTrue(vFinal.energy < 60, "经过11个极端Tick后体力应大幅减少")
    }

    @Test
    fun testDroughtReducesFarmingYield() {
        val weatherEngine = WeatherEngine()
        // 干旱：farming * 0.3
        weatherEngine.forceSetWeather(WeatherType.DROUGHT, 10)
        val mods = weatherEngine.getModifiers()
        assertEquals(0.3f, mods.farmingYieldMultiplier, 0.001f)
        assertEquals(1.2f, mods.miningYieldMultiplier, 0.001f)
    }

    @Test
    fun testPolicyFold_MobilizationPlusFrantic() {
        val policyEngine = PolicyEngine(makeProvider())
        // MOBILIZATION: harvestYield * 0.7；FRANTIC_GATHERING: harvestYield * 1.3
        policyEngine.enactPolicy(PolicyType.MOBILIZATION, true)
        policyEngine.enactPolicy(PolicyType.FRANTIC_GATHERING, true)
        val mods = policyEngine.getModifiers()
        // 1.0 * 0.7 * 1.3 = 0.91
        assertEquals(0.91f, mods.harvestYieldMultiplier, 0.001f)
    }
}
