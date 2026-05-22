package com.example.pythonrpg.engine.workshop

import kotlin.test.*

class WorkshopProgressAndDeliveryTest {

    private var deliveryCalled = false
    private var lastDeliveredType: ToolType? = null
    private var lastDeliveredWorkshopId: Long? = null

    private val mockProvider = object : WorkshopDependencyProvider {
        override fun deductResources(resources: Map<String, Int>): Boolean = true

        override fun refundResources(resources: Map<String, Int>) {}

        override fun addToolToWarehouse(workshopBuildingId: Long, toolType: ToolType) {
            deliveryCalled = true
            lastDeliveredType = toolType
            lastDeliveredWorkshopId = workshopBuildingId
        }

        override fun isTechUnlocked(techId: String): Boolean = true
    }

    @BeforeTest
    fun setUp() {
        deliveryCalled = false
        lastDeliveredType = null
        lastDeliveredWorkshopId = null
    }

    @Test
    fun testMultiSlotProgressionLimits() {
        val engine = WorkshopEngine(mockProvider)
        
        // 注册 maxSlots = 2 拥有两个制造槽的工坊 201L
        engine.registerWorkshop(201L, 2)
        
        // 排入三个石斧任务，每个耗时 3 Ticks
        engine.queueProduction(201L, ToolType.STONE_AXE, 1) // 任务 A
        engine.queueProduction(201L, ToolType.STONE_AXE, 1) // 任务 B
        engine.queueProduction(201L, ToolType.STONE_AXE, 1) // 任务 C
        
        // 时针推进 1 Tick 进度
        engine.processTick()
        
        val tasks = engine.getWorkshopState(201L)!!.activeTasks
        assertEquals(3, tasks.size)
        // 最前方任务 A 和 B 的进度应该自增变为 1，说明双槽推进成立
        assertEquals(1, tasks[0].currentProgress)
        assertEquals(1, tasks[1].currentProgress)
        // 挂起的任务 C 的进度必须保持为 0，不能被滚动
        assertEquals(0, tasks[2].currentProgress)
    }

    @Test
    fun testSingleItemDeliveryAndCompletion() {
        val engine = WorkshopEngine(mockProvider)
        
        // 注册单槽位工坊 202L，批量生产 2 个纤维背包（单个耗时 2 ticks）
        engine.registerWorkshop(202L, 1)
        engine.queueProduction(202L, ToolType.BACKPACK, 2)
        
        val state = engine.getWorkshopState(202L)!!
        val task = state.activeTasks[0]
        assertEquals(0, task.completedCount)
        assertEquals(0, task.currentProgress)

        // 推进第 1 次 Tick
        engine.processTick()
        assertEquals(1, task.currentProgress)
        assertFalse(deliveryCalled)

        // 推进第 2 次 Tick，刚好满足 2 ticks，触发成品出库
        engine.processTick()
        assertTrue(deliveryCalled)
        assertEquals(ToolType.BACKPACK, lastDeliveredType)
        assertEquals(202L, lastDeliveredWorkshopId)
        
        // 完成数自增，进度重置归零，且任务未消失
        assertEquals(1, task.completedCount)
        assertEquals(0, task.currentProgress)
        assertEquals(1, state.activeTasks.size)

        // 重置 deliveryCalled 并再次推进 2 ticks 制造第二个背包
        deliveryCalled = false
        engine.processTick()
        engine.processTick()
        
        // 验证第二只包交付，大完工自动将任务注销物理扫除
        assertTrue(deliveryCalled)
        assertTrue(state.activeTasks.isEmpty())
    }
}
