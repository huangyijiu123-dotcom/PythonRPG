package com.example.pythonrpg.engine.tech

import kotlin.test.*

class TechConfigAndInitTest {

    @Test
    fun testBaseAvailableTechOnStart() {
        val engine = TechEngine()
        
        // 验证无条件免费科技 BASIC_MANAGEMENT 初始为 AVAILABLE 状态
        val state = engine.getTechState("BASIC_MANAGEMENT")
        assertEquals(TechState.AVAILABLE, state)
        assertTrue(engine.getTechTreeSnapshot().isNotEmpty())
    }

    @Test
    fun testConfigAccuracyAndPrerequisites() {
        val engine = TechEngine()
        
        // 铁器锻造 IRON_FORGING：前置为 STONE_TOOLS，造价为 300 铁矿，初态为 LOCKED
        val snapshot = engine.getTechTreeSnapshot()
        val ironForgingNode = snapshot.firstOrNull { it.techId == "IRON_FORGING" }
        assertNotNull(ironForgingNode)
        assertEquals(TechState.LOCKED, ironForgingNode.state)
        assertEquals(listOf("STONE_TOOLS"), ironForgingNode.prerequisites)
        assertEquals(300, ironForgingNode.cost["IRON_ORE"])

        // 密码学 CRYPTOGRAPHY：前置为 ARCHEOLOGY，造价为 500 金币
        val cryptoNode = snapshot.firstOrNull { it.techId == "CRYPTOGRAPHY" }
        assertNotNull(cryptoNode)
        assertEquals(TechState.LOCKED, cryptoNode.state)
        assertEquals(listOf("ARCHEOLOGY"), cryptoNode.prerequisites)
        assertEquals(500, cryptoNode.cost["GOLD"])
    }

    @Test
    fun testTreeSnapshotSize() {
        val engine = TechEngine()
        
        // 验证 13 个科技节点一个不能少，保障沙盒评估面板无遗漏
        val tree = engine.getTechTreeSnapshot()
        assertEquals(13, tree.size)
    }
}
