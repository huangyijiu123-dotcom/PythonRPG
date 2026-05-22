package com.example.pythonrpg.engine.combat

import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CombatAutoExecutionAndRegressionTest {

    class MockCombatDependencyProvider(
        val att: Int = 50,
        val def: Int = 10,
        val hp: Int = 100
    ) : CombatDependencyProvider {
        override fun getAdventurerTotalAttack(adventurerId: Long): Int = att
        override fun getAdventurerTotalDefense(adventurerId: Long): Int = def
        override fun getAdventurerHp(adventurerId: Long): Int = hp
        override fun getAdventurerMp(adventurerId: Long): Int = 20
        override fun deductAdventurerMp(adventurerId: Long, amount: Int): Boolean = true
    }

    private val mockMonster = MonsterSnapshot(
        monsterId = 1L,
        typeId = "SLIME",
        isBoss = false,
        level = 2, // 2 级怪物
        maxHp = 50,
        strength = 15,
        agility = 5,
        defense = 10
    )

    @Test
    fun testAutoBattleDamageAndDeterministicMultiplier() {
        val provider = MockCombatDependencyProvider(att = 50, def = 10)
        val engine = CombatEngine(provider)

        // 注入确定性的随机数倍率：锁定为 1.0f
        engine.randomGenerator = object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextDouble(from: Double, until: Double): Double = 1.0
        }

        val sessionId = engine.startBattle(1001L, mockMonster, 1L)
        val session = engine.getSession(sessionId)!!
        engine.setAutoMode(sessionId, true)

        // 自动推进对决一步
        val result = engine.processAutoBattle(sessionId)!!

        // 1. 玩家对怪物伤害核算：(max(1.0, 50 * 0.6 - 10) * 1.0) = 20
        assertEquals(20, result.damageDealt)
        assertEquals(30, session.monsterCurrentHp)

        // 2. 怪物对玩家伤害核算：(max(0.0, 15 - 10 * 0.6) * 1.0) = 9
        assertEquals(9, result.damageTaken)
        assertEquals(91, session.adventurerCurrentHp)
    }

    @Test
    fun testAutoLootHalfTruncation() {
        val provider = MockCombatDependencyProvider()
        val engine = CombatEngine(provider)

        // 1. 模拟 2 级怪物的正常手动胜利掉落
        // 连击为 0，没有稀有掉落加成
        val manualDrops = engine.calculateDrops(mockMonster, isManual = true, comboCount = 0)
        assertEquals(20, manualDrops["GOLD"])
        assertEquals(1, manualDrops["CORE"])

        // 2. 模拟自动对决挂机胜利掉落：物理斩断为 50% 并四舍五入
        // GOLD: round(20 * 0.5) = 10
        // CORE: round(1.0 * 0.5) = 1
        val autoDrops = engine.calculateDrops(mockMonster, isManual = false, comboCount = 0)
        assertEquals(10, autoDrops["GOLD"])
        assertEquals(1, autoDrops["CORE"])
    }

    @Test
    fun testHighComboManualDropBonus() {
        val provider = MockCombatDependencyProvider()
        val engine = CombatEngine(provider)

        // 模拟连击数 5 且 Random 摇点 100% 触发稀有爆率 (nextFloat < 0.2f)
        engine.randomGenerator = object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextFloat(): Float = 0.1f // 触发 < 0.2 爆率阈值
        }

        val manualDrops = engine.calculateDrops(mockMonster, isManual = true, comboCount = 5)
        // 获得高爆额外奖励：GOLD + 1 = 21, CORE + 1 = 2, 还有 RARE_GEM = 1
        assertEquals(21, manualDrops["GOLD"])
        assertEquals(2, manualDrops["CORE"])
        assertEquals(1, manualDrops["RARE_GEM"])
    }

    @Test
    fun testAutoBattleConcurrencyAndHighDefenseNoHeal() = runBlocking {
        // 注册 50 个高防御玩家并发测试，并且保证高防御下怪物对玩家不会发生回血现象
        val provider = MockCombatDependencyProvider(att = 100, def = 9999, hp = 100)
        val engine = CombatEngine(provider)

        val jobs = List(50) { index ->
            launch(Dispatchers.Default) {
                val adventurerId = 2000L + index
                val sessionId = engine.startBattle(adventurerId, mockMonster, 1L)
                engine.setAutoMode(sessionId, true)

                // 推进至死斗结束
                var ended = false
                while (!ended) {
                    val result = engine.processAutoBattle(sessionId)
                    if (result == null || result.sessionEnded) {
                        ended = true
                    } else {
                        // 高防御保底机制验证：即使怪物攻击为 15，玩家防御 9999，反砍伤害绝对不能小于 0 变成加血
                        assertTrue(result.damageTaken >= 0)
                    }
                }
            }
        }

        jobs.joinAll()
        // 全部协程安全结束，且无 ConcurrentModificationException 崩溃！
        assertTrue(engine.getActiveSessionIds().isEmpty())
    }
}
