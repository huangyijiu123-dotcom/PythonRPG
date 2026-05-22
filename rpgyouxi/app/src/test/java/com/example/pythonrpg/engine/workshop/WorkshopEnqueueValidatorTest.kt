package com.example.pythonrpg.engine.workshop

import kotlin.test.*

class WorkshopEnqueueValidatorTest {

    private var isTechUnlockedValue = true
    private var deductReturnValue = true
    private var deductCalled = false
    private var lastDeductedResources: Map<String, Int>? = null

    private val mockProvider = object : WorkshopDependencyProvider {
        override fun deductResources(resources: Map<String, Int>): Boolean {
            deductCalled = true
            lastDeductedResources = resources
            return deductReturnValue
        }

        override fun refundResources(resources: Map<String, Int>) {}

        override fun addToolToWarehouse(workshopBuildingId: Long, toolType: ToolType) {}

        override fun isTechUnlocked(techId: String): Boolean = isTechUnlockedValue
    }

    @BeforeTest
    fun setUp() {
        isTechUnlockedValue = true
        deductReturnValue = true
        deductCalled = false
        lastDeductedResources = null
    }

    @Test
    fun testBlueprintLockedIntercept() {
        val engine = WorkshopEngine(mockProvider)
        engine.registerWorkshop(101L, 1)
        
        // 模拟科技铁器锻造未解锁
        isTechUnlockedValue = false
        
        // 尝试生产 5 把铁斧，预期返回 false 科技被拦截
        val success = engine.queueProduction(101L, ToolType.IRON_AXE, 5)
        assertFalse(success)
        assertFalse(deductCalled)
        assertTrue(engine.getWorkshopState(101L)!!.activeTasks.isEmpty())
    }

    @Test
    fun testResourceDeficitDeductFailed() {
        val engine = WorkshopEngine(mockProvider)
        engine.registerWorkshop(101L, 1)
        
        // 模拟科技已解锁，但主背包库存原材料不够
        isTechUnlockedValue = true
        deductReturnValue = false
        
        // 尝试生产 5 把铁斧 (木材 25, 铁矿 15)，预期扣划拒绝，入队失败
        val success = engine.queueProduction(101L, ToolType.IRON_AXE, 5)
        assertFalse(success)
        assertTrue(deductCalled)
        assertEquals(25, lastDeductedResources?.get("WOOD"))
        assertEquals(15, lastDeductedResources?.get("IRON_ORE"))
        assertTrue(engine.getWorkshopState(101L)!!.activeTasks.isEmpty())
    }

    @Test
    fun testSuccessfulEnqueue() {
        val engine = WorkshopEngine(mockProvider)
        engine.registerWorkshop(101L, 1)
        
        isTechUnlockedValue = true
        deductReturnValue = true
        
        // 合法批量生产 3 把石斧
        val success = engine.queueProduction(101L, ToolType.STONE_AXE, 3)
        assertTrue(success)
        assertTrue(deductCalled)
        assertEquals(15, lastDeductedResources?.get("WOOD"))
        
        val tasks = engine.getWorkshopState(101L)!!.activeTasks
        assertEquals(1, tasks.size)
        val task = tasks[0]
        assertEquals(ToolType.STONE_AXE, task.toolType)
        assertEquals(3, task.totalCount)
        assertEquals(0, task.completedCount)
        assertEquals(0, task.currentProgress)
    }
}
