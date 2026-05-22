package com.example.pythonrpg.engine.workshop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkshopCancellationStressTest {

    private var refundCalled = false
    private var lastRefundedResources: Map<String, Int>? = null

    private val mockProvider = object : WorkshopDependencyProvider {
        override fun deductResources(resources: Map<String, Int>): Boolean = true

        override fun refundResources(resources: Map<String, Int>) {
            refundCalled = true
            lastRefundedResources = resources
        }

        override fun addToolToWarehouse(workshopBuildingId: Long, toolType: ToolType) {}

        override fun isTechUnlocked(techId: String): Boolean = true
    }

    @BeforeTest
    fun setUp() {
        refundCalled = false
        lastRefundedResources = null
    }

    @Test
    fun testCancellationRefundRefinement() {
        val engine = WorkshopEngine(mockProvider)
        engine.registerWorkshop(301L, 1)
        
        // 排产制造 5 辆小推车 (单价 20 木头)
        engine.queueProduction(301L, ToolType.WHEELBARROW, 5)
        
        val state = engine.getWorkshopState(301L)!!
        val task = state.activeTasks[0]
        val taskId = task.taskId
        
        // 时针推进，完成 2 辆，第 3 辆开工中 (completedCount = 2, currentProgress = 2)
        // 纤维推车单件耗时 4 ticks，推进 10 ticks (4+4+2)
        for (i in 0 until 10) {
            engine.processTick()
        }
        
        assertEquals(2, task.completedCount)
        assertEquals(2, task.currentProgress)
        
        // 取消剩余所有未完成任务，折算退料：(5 - 2) * 20 = 60 根木头。
        engine.cancelProduction(301L, taskId)
        
        assertTrue(refundCalled)
        assertEquals(60, lastRefundedResources?.get("WOOD"))
        assertTrue(state.activeTasks.isEmpty()) // 队列已被彻底放空撕毁
    }

    @Test
    fun testExtremeConcurrencySafety() {
        val engine = WorkshopEngine(mockProvider)
        // 预注册 5 座工房
        for (id in 501L..505L) {
            engine.registerWorkshop(id, 2)
        }
        
        val executor = Executors.newFixedThreadPool(50)
        val latch = CountDownLatch(50)
        
        // 50 个高并发协程 Workers 并发轰炸
        for (i in 0 until 50) {
            executor.submit {
                try {
                    for (j in 0 until 50) {
                        val workshopId = 501L + (j % 5)
                        
                        when (j % 3) {
                            0 -> {
                                engine.queueProduction(workshopId, ToolType.STONE_AXE, 2)
                                engine.queueProduction(workshopId, ToolType.BACKPACK, 3)
                            }
                            1 -> {
                                engine.processTick()
                            }
                            2 -> {
                                val state = engine.getWorkshopState(workshopId)
                                val taskId = state?.activeTasks?.firstOrNull()?.taskId
                                if (taskId != null) {
                                    engine.cancelProduction(workshopId, taskId)
                                }
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        
        val finished = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        
        assertTrue(finished, "工坊并发轰炸卡死，可能产生死锁！")
        
        // 验证活跃队列状态自洽
        for (id in 501L..505L) {
            val state = engine.getWorkshopState(id)
            assertNotNull(state)
            for (task in state.activeTasks) {
                assertNotNull(task.toolType)
                assertTrue(task.completedCount >= 0)
            }
        }
    }
}
