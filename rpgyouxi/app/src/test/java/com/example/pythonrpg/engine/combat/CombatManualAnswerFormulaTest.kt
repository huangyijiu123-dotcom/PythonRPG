package com.example.pythonrpg.engine.combat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CombatManualAnswerFormulaTest {

    class MockCombatDependencyProvider(
        var attack: Int = 10,
        var defense: Int = 10
    ) : CombatDependencyProvider {
        override fun getAdventurerTotalAttack(adventurerId: Long): Int = attack
        override fun getAdventurerTotalDefense(adventurerId: Long): Int = defense
        override fun getAdventurerHp(adventurerId: Long): Int = 100
        override fun getAdventurerMp(adventurerId: Long): Int = 20
        override fun deductAdventurerMp(adventurerId: Long, amount: Int): Boolean = true
    }

    private fun createMonster(defense: Int = 5, strength: Int = 15, maxHp: Int = 50): MonsterSnapshot {
        return MonsterSnapshot(
            monsterId = 1L,
            typeId = "SLIME",
            isBoss = false,
            level = 1,
            maxHp = maxHp,
            strength = strength,
            agility = 5,
            defense = defense
        )
    }

    @Test
    fun testCorrectAnswerDamageCombosAndClamping() {
        val provider = MockCombatDependencyProvider(attack = 10)
        val engine = CombatEngine(provider)

        // 注册小怪，防御高达 9999，血量 100
        val monster = createMonster(defense = 9999, maxHp = 100)
        val sessionId = engine.startBattle(1001L, monster, 1L)
        val session = engine.getSession(sessionId)!!

        // 1. 答对第 1 题：combo = 1, combo multiplier = 1.0x
        val res1 = engine.submitAnswer(sessionId, isCorrect = true)
        assertEquals(1, res1.newComboCount)
        // 攻击极低，触发 1 点伤害物理保底
        assertEquals(1, res1.damageDealt)
        assertFalse(res1.isCritical)
        assertEquals(99, session.monsterCurrentHp)

        // 2. 制造连击达到 2 (再答对 1 题，combo = 2)
        engine.submitAnswer(sessionId, isCorrect = true)
        assertEquals(2, session.comboCount)

        // 3. 答对第 3 题：combo = 3, combo multiplier = 1.5x
        // 基础保底为 1, 1.5 * 1 = 1.5 -> toInt() = 1
        val res3 = engine.submitAnswer(sessionId, isCorrect = true)
        assertEquals(3, res3.newComboCount)
        assertEquals(1, res3.damageDealt)
        assertFalse(res3.isCritical)

        // 4. 制造连击达到 4
        engine.submitAnswer(sessionId, isCorrect = true)
        assertEquals(4, session.comboCount)

        // 5. 答对第 5 题：combo = 5, combo multiplier = 2.0x (代码暴击)
        // 基础保底为 1, 2.0 * 1 = 2
        val res5 = engine.submitAnswer(sessionId, isCorrect = true)
        assertEquals(5, res5.newComboCount)
        assertEquals(2, res5.damageDealt)
        assertTrue(res5.isCritical) // >= 5 触发暴击
    }

    @Test
    fun testIncorrectAnswerComboResetAndDefenseClamping() {
        // 设定冒险者全装防御极高 9999
        val provider = MockCombatDependencyProvider(defense = 9999)
        val engine = CombatEngine(provider)

        // 怪物力量 10
        val monster = createMonster(strength = 10)
        val sessionId = engine.startBattle(1001L, monster, 1L)
        val session = engine.getSession(sessionId)!!

        // 手动制造 4 连击
        session.comboCount = 4

        // 答错题
        val result = engine.submitAnswer(sessionId, isCorrect = false)

        // 断言：连击数归零
        assertEquals(0, result.newComboCount)
        assertEquals(0, session.comboCount)

        // 断言：怪物力量低且玩家防御超高，但依然触发 1 点反击伤害保底
        assertEquals(1, result.damageTaken)
        assertEquals(99, session.adventurerCurrentHp)
    }

    @Test
    fun testMonsterDeathSessionTerminated() {
        val provider = MockCombatDependencyProvider(attack = 20)
        val engine = CombatEngine(provider)

        // 怪物血量为 2
        val monster = createMonster(defense = 5, maxHp = 2)
        val sessionId = engine.startBattle(1001L, monster, 1L)

        // 答对题：最终伤害 Math.max(1, 20 - 5) = 15，怪物被打死
        val result = engine.submitAnswer(sessionId, isCorrect = true)
        
        // 断言：战斗结束且从会话池安全移出
        assertTrue(result.sessionEnded)
        assertEquals("MONSTER_DEAD", result.endReason)
        assertNull(engine.getSession(sessionId))
    }
}
