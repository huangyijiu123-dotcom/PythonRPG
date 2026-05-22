package com.example.pythonrpg.engine.combat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class CombatMpSkillAndCountdownTest {

    class MockCombatDependencyProvider(
        var adventurerMp: Int = 20,
        var deductSuccess: Boolean = true,
        var deductCalledWith: Int? = null
    ) : CombatDependencyProvider {
        override fun getAdventurerTotalAttack(adventurerId: Long): Int = 50
        override fun getAdventurerTotalDefense(adventurerId: Long): Int = 10
        override fun getAdventurerHp(adventurerId: Long): Int = 100
        override fun getAdventurerMp(adventurerId: Long): Int = adventurerMp
        
        override fun deductAdventurerMp(adventurerId: Long, amount: Int): Boolean {
            deductCalledWith = amount
            if (adventurerMp >= amount && deductSuccess) {
                adventurerMp -= amount
                return true
            }
            return false
        }
    }

    private fun createMonster(agility: Int, isBoss: Boolean = false): MonsterSnapshot {
        return MonsterSnapshot(
            monsterId = 1L,
            typeId = "MONSTER",
            isBoss = isBoss,
            level = 1,
            maxHp = 50,
            strength = 10,
            agility = agility,
            defense = 5
        )
    }

    @Test
    fun testMonsterAgilityCountdownSeconds() {
        val provider = MockCombatDependencyProvider()
        val engine = CombatEngine(provider)

        // 1. 敏捷为 0 的怪兽：6.0 + 10.0 / (1.0 + 0) = 16.0f
        val id1 = engine.startBattle(1001L, createMonster(agility = 0), 1L)
        assertEquals(16.0f, engine.getCountdownSeconds(id1), 1e-4f)

        // 2. 敏捷为 5 的怪兽：6.0 + 10.0 / (1.0 + 5) = 7.66667f
        val id2 = engine.startBattle(1002L, createMonster(agility = 5), 1L)
        assertEquals(7.6667f, engine.getCountdownSeconds(id2), 1e-3f)
    }

    @Test
    fun testBossAutoCorrectImmunity() {
        val provider = MockCombatDependencyProvider(adventurerMp = 100)
        val engine = CombatEngine(provider)

        // 启动一场 Boss 战
        val bossSessionId = engine.startBattle(1001L, createMonster(agility = 5, isBoss = true), 1L)

        // 玩家尝试对 Boss 战释放自动答对奥义
        val result = engine.useMpSkill(bossSessionId, MpSkill.AUTO_CORRECT, currentMp = 100)
        
        // 断言拦截拒绝
        assertFalse(result.first)
        assertEquals(0, result.second)
        // 蓝量提供者应该未被扣减
        assertEquals(100, provider.adventurerMp)
        assertTrue(provider.deductCalledWith == null)
    }

    @Test
    fun testMpSkillDeductAndBalanceEnforcement() {
        val provider = MockCombatDependencyProvider(adventurerMp = 10)
        val engine = CombatEngine(provider)

        val sessionId = engine.startBattle(1001L, createMonster(agility = 5, isBoss = false), 1L)

        // 1. 蓝量不足拦截：AUTO_CORRECT 需 15 MP，但玩家仅有 10 MP
        val failedResult = engine.useMpSkill(sessionId, MpSkill.AUTO_CORRECT, currentMp = 10)
        assertFalse(failedResult.first)
        assertEquals(0, failedResult.second)
        assertEquals(10, provider.adventurerMp)

        // 2. 蓝量充足释放：EXTEND_TIME 需 5 MP，玩家有 10 MP
        val successResult = engine.useMpSkill(sessionId, MpSkill.EXTEND_TIME, currentMp = 10)
        assertTrue(successResult.first)
        assertEquals(5, successResult.second)
        // 验证 provider 扣减
        assertEquals(5, provider.adventurerMp)
        assertEquals(5, provider.deductCalledWith)
    }
}
