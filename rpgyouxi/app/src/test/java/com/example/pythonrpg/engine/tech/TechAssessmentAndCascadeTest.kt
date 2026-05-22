package com.example.pythonrpg.engine.tech

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TechAssessmentAndCascadeTest {

    private var pledgeCalled = false
    private var refundCalled = false
    private var consumeCalled = false
    private var onUnlockedCalled = false
    private var lastUnlockedTech: String? = null
    private var lastConsumedResources: Map<String, Int>? = null

    private val mockProvider = object : TechDependencyProvider {
        override fun tryPledgeResources(resources: Map<String, Int>): Boolean = true

        override fun consumePledgedResources(resources: Map<String, Int>) {
            consumeCalled = true
            lastConsumedResources = resources
        }

        override fun refundPledgedResources(resources: Map<String, Int>) {
            refundCalled = true
        }

        override fun onTechUnlockedGlobalEvent(techId: String) {
            onUnlockedCalled = true
            lastUnlockedTech = techId
        }
    }

    @BeforeTest
    fun setUp() {
        pledgeCalled = false
        refundCalled = false
        consumeCalled = false
        onUnlockedCalled = false
        lastUnlockedTech = null
        lastConsumedResources = null
    }

    @Test
    fun testAssessmentFailureZeroPenalty() {
        val engine = TechEngine(mockProvider)
        
        // 开启研发
        engine.startResearch("BASIC_MANAGEMENT")
        assertEquals(TechState.RESEARCHING, engine.getTechState("BASIC_MANAGEMENT"))
        
        // 模拟考核失败，预期状态依旧为 RESEARCHING，零扣减零退还
        engine.submitAssessmentResult("BASIC_MANAGEMENT", false)
        assertEquals(TechState.RESEARCHING, engine.getTechState("BASIC_MANAGEMENT"))
        assertFalse(consumeCalled)
        assertFalse(refundCalled)
    }

    @Test
    fun testAssessmentPassedAndCascadeUnlock() {
        val engine = TechEngine(mockProvider)
        
        // 初始时：BASIC_MANAGEMENT 状态为 AVAILABLE
        assertEquals(TechState.AVAILABLE, engine.getTechState("BASIC_MANAGEMENT"))
        
        // 其下属前置锁链：BACKPACK_WEAVING, ADVANCED_BUILDING, ARCHEOLOGY 等均为 LOCKED
        assertEquals(TechState.LOCKED, engine.getTechState("BACKPACK_WEAVING"))
        assertEquals(TechState.LOCKED, engine.getTechState("ADVANCED_BUILDING"))
        assertEquals(TechState.LOCKED, engine.getTechState("ARCHEOLOGY"))
        assertEquals(TechState.LOCKED, engine.getTechState("DISTRIBUTION_CENTER"))
        
        // 开启基础管理学研究并考核通过
        engine.startResearch("BASIC_MANAGEMENT")
        engine.submitAssessmentResult("BASIC_MANAGEMENT", true)
        
        // 断言：基础管理学解锁，所有第一层直接依赖它的科技瞬间跳转为 AVAILABLE
        assertEquals(TechState.UNLOCKED, engine.getTechState("BASIC_MANAGEMENT"))
        assertEquals(TechState.AVAILABLE, engine.getTechState("BACKPACK_WEAVING"))
        assertEquals(TechState.AVAILABLE, engine.getTechState("ADVANCED_BUILDING"))
        assertEquals(TechState.AVAILABLE, engine.getTechState("ARCHEOLOGY"))
        
        // 多前置依赖的分拨中心 DISTRIBUTION_CENTER 仍然是 LOCKED，因为另一个前置 LOGISTICS_DISPATCH 还没开
        assertEquals(TechState.LOCKED, engine.getTechState("DISTRIBUTION_CENTER"))
        assertTrue(consumeCalled)
        assertTrue(onUnlockedCalled)
        assertEquals("BASIC_MANAGEMENT", lastUnlockedTech)
    }

    @Test
    fun testMultithreadedConcurrencyStress() {
        val engine = TechEngine(mockProvider)
        val executor = Executors.newFixedThreadPool(50)
        val latch = CountDownLatch(50)
        
        val techList = listOf(
            "BASIC_MANAGEMENT", "BACKPACK_WEAVING", "WHEELBARROW", 
            "STONE_TOOLS", "IRON_FORGING", "ADVANCED_BUILDING", 
            "WORKSHOP_OPTIMIZATION", "LOGISTICS_DISPATCH", "DISTRIBUTION_CENTER", 
            "RADIATION_EXPANSION", "ADVANCED_EXPLORATION", "ARCHEOLOGY", "CRYPTOGRAPHY"
        )
        
        // 50 个高并发线程轰炸 2000 次以上
        for (i in 0 until 50) {
            executor.submit {
                try {
                    for (j in 0 until 50) {
                        val tech = techList[j % techList.size]
                        
                        // 一半读，一半写
                        if (i % 2 == 0) {
                            engine.getTechState(tech)
                            engine.isTechUnlocked(tech)
                            engine.getTechTreeSnapshot()
                        } else {
                            when (j % 3) {
                                0 -> engine.startResearch(tech)
                                1 -> engine.cancelResearch(tech)
                                2 -> engine.submitAssessmentResult(tech, java.lang.Math.random() < 0.5)
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
        
        assertTrue(finished, "并发轰炸未能在超时内结束，可能死锁！")
        // 验证拓扑快照零报错脏读，状态依然处于合法枚举中
        val snapshot = engine.getTechTreeSnapshot()
        assertEquals(13, snapshot.size)
        for (node in snapshot) {
            assertNotNull(node.state)
        }
    }
}
