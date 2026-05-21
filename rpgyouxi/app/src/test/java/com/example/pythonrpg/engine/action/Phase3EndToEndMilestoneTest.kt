package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.*
import com.example.pythonrpg.engine.entity.EntityStateManager
import com.example.pythonrpg.engine.building.BuildingEngine
import com.example.pythonrpg.engine.building.BuildingDependencyProvider
import com.example.pythonrpg.engine.building.BuildingState
import kotlin.test.*

class Phase3EndToEndMilestoneTest {

    @BeforeTest
    fun setup() {
        VillagerStateRegistry.clear()
    }

    @Test
    fun testCoreGameplayLoopMilestone() {
        // 1. 初始化核心系统
        val entityState = EntityStateManager()
        val buildingEngine = BuildingEngine()
        
        buildingEngine.setDependencyProvider(object : BuildingDependencyProvider {
            override fun notifyWarehouseCapacityExpanded(warehouseId: Long, additionalCapacity: Int) {
                entityState.expandWarehouseCapacity(warehouseId, additionalCapacity)
            }
            override fun getTerrainType(x: Int, y: Int): String {
                return if (x == 5 && y == 5) "FOREST" else "PLAINS"
            }
        })
        
        val actionProcessor = ActionProcessor()
        val economyHandler = EconomyBuildPolicyHandler(buildingEngine)
        val villagerHandler = VillagerCommandHandler()
        val automationSystem = VillagerAutomationSystem(entityState)
        
        actionProcessor.registerHandler(economyHandler)
        actionProcessor.registerHandler(villagerHandler)
        actionProcessor.setAutomationSystem(automationSystem)
        
        // 2. 初始世界状态配置
        val villagerId = 1L
        entityState.registerVillager(VillagerSnapshot(
            id = villagerId, name = "Bob", coordinate = Coordinate(0,0),
            status = VillagerStatus.IDLE, job = "NONE", targetX = null, targetY = null,
            isInjured = false, energy = 100, backpack = emptyMap(), equippedTools = emptyMap()
        ))
        
        val warehouseId = 99L
        entityState.registerWarehouse(WarehouseSnapshot(
            id = warehouseId, coordinate = Coordinate(0,0), capacity = 1000, inventory = emptyMap()
        ))
        
        // 3. 发出建造伐木场指令
        println("=> [系统] 收到玩家指令：在 (5, 5) 坐标建造 'LUMBER_CAMP' (伐木场)")
        actionProcessor.queueCommand(PlayerCommand.BuildBuilding(5, 5, "LUMBER_CAMP"))
        actionProcessor.processTick(TickEvent(1L, System.currentTimeMillis(), TimePeriod.DAYTIME), PolicyModifiers(), com.example.pythonrpg.shared.WeatherModifiers())
        
        // 验证伐木场开始建造
        val lumberCamp = buildingEngine.getBuildingAt(5, 5)
        assertNotNull(lumberCamp)
        println("=> [系统] 建筑已放置。当前状态: ${lumberCamp!!.state}")
        assertEquals(BuildingState.UNDER_CONSTRUCTION, lumberCamp!!.state)
        
        // 跳过建造时间 (Lumber camp baseConstructionTicks = 4)
        for (i in 0 until 4) {
            buildingEngine.processTick()
        }
        println("=> [建筑引擎] 经历 4 个时钟周期后，伐木场完工！当前状态: ${lumberCamp.state}")
        assertEquals(BuildingState.ACTIVE, lumberCamp.state)
        
        // 4. 发出指派村民工作指令
        println("=> [系统] 收到玩家指令：指派村民 (ID: 1) 前往 (5, 5) 执行 'LUMBERJACK' (伐木工) 任务")
        actionProcessor.queueCommand(PlayerCommand.AssignJob(villagerId, "LUMBERJACK", 5, 5))
        actionProcessor.processTick(TickEvent(2L, System.currentTimeMillis(), TimePeriod.DAYTIME), PolicyModifiers(), com.example.pythonrpg.shared.WeatherModifiers())
        
        // 验证村民意图转入 WORKING
        println("=> [自转引擎] 村民意图状态跃迁: IDLE -> ${VillagerStateRegistry.detailedStates[villagerId]}")
        assertEquals("WORKING", VillagerStateRegistry.detailedStates[villagerId])
        assertEquals("LUMBERJACK", VillagerStateRegistry.originalJobs[villagerId])
        
        // 5. 模拟村民连续砍树直到背包满 (容量阈值 10)
        // 第一刀在指派工作的同一 Tick 已经砍下了（积攒了 1 根）
        // 所以只需要再经历 9 个 Tick，背包就会达到 10
        println("=> [自转引擎] 模拟世界时间流逝 9 个周期...")
        for (i in 1..9) {
            actionProcessor.processTick(TickEvent(2L + i, System.currentTimeMillis(), TimePeriod.DAYTIME), PolicyModifiers(), com.example.pythonrpg.shared.WeatherModifiers())
        }
        
        var v = entityState.getVillager(villagerId)!!
        println("=> [实体缓存] 村民当前体力: ${v.energy}/100，背包木材数量: ${v.backpack["WOOD"]}")
        assertEquals(10, v.backpack["WOOD"])
        assertEquals("WORKING", VillagerStateRegistry.detailedStates[villagerId])
        
        // 第 11 次 Tick，由于背包已满 10，状态跳转为 DELIVERING
        actionProcessor.processTick(TickEvent(13L, System.currentTimeMillis(), TimePeriod.DAYTIME), PolicyModifiers(), com.example.pythonrpg.shared.WeatherModifiers())
        println("=> [自转引擎] 背包达到阈值，村民状态跃迁: WORKING -> ${VillagerStateRegistry.detailedStates[villagerId]}")
        assertEquals("DELIVERING", VillagerStateRegistry.detailedStates[villagerId])
        
        // 第 12 次 Tick，执行卸货，并将物品转移到仓库，随后恢复 WORKING 状态
        actionProcessor.processTick(TickEvent(14L, System.currentTimeMillis(), TimePeriod.DAYTIME), PolicyModifiers(), com.example.pythonrpg.shared.WeatherModifiers())
        println("=> [自转引擎] 已抵达仓库卸货。村民状态跃迁: DELIVERING -> ${VillagerStateRegistry.detailedStates[villagerId]}")
        assertEquals("WORKING", VillagerStateRegistry.detailedStates[villagerId])
        
        // 6. 验证端到端断言：仓库里收到了 10 根木头，村民背包清空
        v = entityState.getVillager(villagerId)!!
        println("=> [断言验证] 村民背包剩余木材: ${v.backpack["WOOD"] ?: 0} 根")
        assertEquals(0, v.backpack["WOOD"] ?: 0)
        
        val warehouse = entityState.getWarehouse(warehouseId)!!
        println("=> [断言验证] 编号 99L 的物理仓库入账情况，木材总数: ${warehouse.inventory["WOOD"]} 根")
        assertEquals(10, warehouse.inventory["WOOD"])
        println("=> [测试结束] ✅ 完整游戏核心大循环里程碑 (建筑->派遣->采集->爆仓->物流入库) 验证全部绿灯通过！")
    }
}
