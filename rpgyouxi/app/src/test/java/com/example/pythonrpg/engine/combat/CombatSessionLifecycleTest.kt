package com.example.pythonrpg.engine.combat

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CombatSessionLifecycleTest {

    // 辅助 Mock 属性提供者
    class MockCombatDependencyProvider : CombatDependencyProvider {
        override fun getAdventurerTotalAttack(adventurerId: Long): Int = 50
        override fun getAdventurerTotalDefense(adventurerId: Long): Int = 10
        override fun getAdventurerHp(adventurerId: Long): Int = 100
        override fun getAdventurerMp(adventurerId: Long): Int = 20
        override fun deductAdventurerMp(adventurerId: Long, amount: Int): Boolean = true
    }

    private val mockMonster = MonsterSnapshot(
        monsterId = 1L,
        typeId = "SLIME",
        isBoss = false,
        level = 1,
        maxHp = 50,
        strength = 15,
        agility = 5,
        defense = 5
    )

    @Test
    fun testCombatDoubleOpenPrevention() {
        val provider = MockCombatDependencyProvider()
        val engine = CombatEngine(provider)

        // 首次开启战斗成功
        val sessionId = engine.startBattle(adventurerId = 1001L, monster = mockMonster, startedTick = 1L)
        assertTrue(sessionId > 0)

        // 企图为同一个冒险者开启第二个战斗会话，强硬拦截
        assertFailsWith<IllegalStateException> {
            engine.startBattle(adventurerId = 1001L, monster = mockMonster, startedTick = 1L)
        }
    }

    @Test
    fun testNormalBattleSessionLifecycleAndRecovery() = runBlocking {
        val provider = MockCombatDependencyProvider()
        val engine = CombatEngine(provider)

        // 启动战斗
        val sessionId = engine.startBattle(adventurerId = 1002L, monster = mockMonster, startedTick = 1L)
        val session = engine.getSession(sessionId)
        
        // 断言会话创建成功
        assertTrue(session != null)
        assertEquals(1002L, session.adventurerId)
        assertEquals(100, session.adventurerCurrentHp)
        assertEquals(50, session.monsterCurrentHp)

        // 先开启异步订阅
        val flowEventDeferred = async {
            engine.battleEndFlow.first()
        }
        kotlinx.coroutines.yield()

        // 主动逃跑终结战斗会话，触发回收
        val endEvent = engine.fleeBattle(sessionId)
        assertEquals(BattleEndReason.FLEE, endEvent.reason)
        assertEquals(0, endEvent.expGained)
        assertTrue(endEvent.drops.isEmpty())

        // 验证内存物理注销
        assertNull(engine.getSession(sessionId))
        assertTrue(engine.getActiveSessionIds().isEmpty())

        // 验证广播 SharedFlow 正常捕获终结事件
        val flowEvent = withTimeout(1000) {
            flowEventDeferred.await()
        }
        assertEquals(sessionId, flowEvent.sessionId)
        assertEquals(BattleEndReason.FLEE, flowEvent.reason)
    }
}
