package com.example.pythonrpg.engine.tech

import kotlin.test.*

class TechPledgeAndCancelTest {

    private var pledgeCalled = false
    private var refundCalled = false
    private var pledgeReturnValue = true
    private var lastPledgedResources: Map<String, Int>? = null
    private var lastRefundedResources: Map<String, Int>? = null

    private val mockProvider = object : TechDependencyProvider {
        override fun tryPledgeResources(resources: Map<String, Int>): Boolean {
            pledgeCalled = true
            lastPledgedResources = resources
            return pledgeReturnValue
        }

        override fun consumePledgedResources(resources: Map<String, Int>) {}

        override fun refundPledgedResources(resources: Map<String, Int>) {
            refundCalled = true
            lastRefundedResources = resources
        }

        override fun onTechUnlockedGlobalEvent(techId: String) {}
    }

    @BeforeTest
    fun setUp() {
        pledgeCalled = false
        refundCalled = false
        pledgeReturnValue = true
        lastPledgedResources = null
        lastRefundedResources = null
    }

    @Test
    fun testOutOfOrderResearchBlock() {
        val engine = TechEngine(mockProvider)
        
        // WHEELBARROW 的前置 BACKPACK_WEAVING 未解锁且初态为 LOCKED
        val wbarrowState = engine.getTechState("WHEELBARROW")
        assertEquals(TechState.LOCKED, wbarrowState)
        
        // 强行启动，预期拦截并返回 false，且没有发生材料质押
        val success = engine.startResearch("WHEELBARROW")
        assertFalse(success)
        assertFalse(pledgeCalled)
        assertEquals(TechState.LOCKED, engine.getTechState("WHEELBARROW"))
    }

    @Test
    fun testCancelResearchAndRefund() {
        val engine = TechEngine(mockProvider)
        
        // BASIC_MANAGEMENT 是 AVAILABLE
        assertEquals(TechState.AVAILABLE, engine.getTechState("BASIC_MANAGEMENT"))
        
        // 启动研究，模拟材料充足（tryPledgeResources 返回 true）
        val startSuccess = engine.startResearch("BASIC_MANAGEMENT")
        assertTrue(startSuccess)
        assertTrue(pledgeCalled)
        assertEquals(TechState.RESEARCHING, engine.getTechState("BASIC_MANAGEMENT"))
        
        // 取消研究项目，预期状态回滚为 AVAILABLE 且触发退还物资
        engine.cancelResearch("BASIC_MANAGEMENT")
        assertEquals(TechState.AVAILABLE, engine.getTechState("BASIC_MANAGEMENT"))
        assertTrue(refundCalled)
        assertEquals(emptyMap<String, Int>(), lastRefundedResources)
    }

    @Test
    fun testPledgeFailedBlocksResearch() {
        val engine = TechEngine(mockProvider)
        pledgeReturnValue = false // 模拟资源不足

        // 启动研究，预期因资源不足返回 false，且状态保持 AVAILABLE
        val success = engine.startResearch("BASIC_MANAGEMENT")
        assertFalse(success)
        assertTrue(pledgeCalled)
        assertEquals(TechState.AVAILABLE, engine.getTechState("BASIC_MANAGEMENT"))
    }
}
