package com.example.pythonrpg.engine.combat

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CombatTimeoutFleeTest {

    class MockCombatDependencyProvider : CombatDependencyProvider {
        override fun getAdventurerTotalAttack(adventurerId: Long): Int = 50
        override fun getAdventurerTotalDefense(adventurerId: Long): Int = 10
        override fun getAdventurerHp(adventurerId: Long): Int = 100
        override fun getAdventurerMp(adventurerId: Long): Int = 20
        override fun deductAdventurerMp(adventurerId: Long, amount: Int): Boolean = true
    }

    private val mockMonster = MonsterSnapshot(
        monsterId = 1L,
        typeId = "GOBLIN",
        isBoss = false,
        level = 2,
        maxHp = 50,
        strength = 10,
        agility = 5,
        defense = 5
    )

    @Test
    fun testConsecutiveTimeoutsAndAutoEscape() = runBlocking {
        val provider = MockCombatDependencyProvider()
        val engine = CombatEngine(provider)

        val sessionId = engine.startBattle(1001L, mockMonster, 1L)
        val session = engine.getSession(sessionId)!!

        // 制造一些连击，超时后连击应该归零
        session.comboCount = 5

        // 1. 第一次超时
        val res1 = engine.reportTimeout(sessionId)
        assertFalse(res1.sessionEnded)
        assertEquals(1, session.consecutiveTimeoutCount)
        assertEquals(0, session.comboCount) // 连击熔断归零
        // 伤害：max(1, 10 * 0.3 - 10 * 0.3) = 1
        assertEquals(1, res1.damageTaken)
        assertEquals(99, session.adventurerCurrentHp)

        // 2. 第二次超时
        val res2 = engine.reportTimeout(sessionId)
        assertFalse(res2.sessionEnded)
        assertEquals(2, session.consecutiveTimeoutCount)
        assertEquals(1, res2.damageTaken)
        assertEquals(98, session.adventurerCurrentHp)

        // 先开启异步订阅
        val flowEventDeferred = async {
            engine.battleEndFlow.first()
        }
        kotlinx.coroutines.yield()

        // 3. 第三次超时 -> 触发 forced TIMEOUT_ESCAPE
        val res3 = engine.reportTimeout(sessionId)
        assertTrue(res3.sessionEnded)
        assertEquals("TIMEOUT_ESCAPE", res3.endReason)
        assertEquals(1, res3.damageTaken)
        assertEquals(97, session.adventurerCurrentHp)

        // 验证内存物理注销
        assertNull(engine.getSession(sessionId))

        // 验证广播事件：超时逃脱没有任何经验与掉落
        val flowEvent = withTimeout(1000) {
            flowEventDeferred.await()
        }
        assertEquals(sessionId, flowEvent.sessionId)
        assertEquals(BattleEndReason.TIMEOUT_ESCAPE, flowEvent.reason)
        assertEquals(0, flowEvent.expGained)
        assertTrue(flowEvent.drops.isEmpty())
    }

    @Test
    fun testActiveFleeImmediatelyCutsSession() = runBlocking {
        val provider = MockCombatDependencyProvider()
        val engine = CombatEngine(provider)

        val sessionId = engine.startBattle(1001L, mockMonster, 1L)

        // 主动逃跑
        val event = engine.fleeBattle(sessionId)
        assertEquals(BattleEndReason.FLEE, event.reason)
        assertEquals(0, event.expGained)
        assertTrue(event.drops.isEmpty())

        // 查证战局立刻物理切除
        assertNull(engine.getSession(sessionId))
    }
}
