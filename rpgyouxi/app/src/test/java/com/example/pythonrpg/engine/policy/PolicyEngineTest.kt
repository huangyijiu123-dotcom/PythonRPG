package com.example.pythonrpg.engine.policy

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.test.*

// ── 测试用 Mock 依赖 ───────────────────────────────────────────
private fun makeProvider(maxActive: Int = 2, goldAlwaysOk: Boolean = true): PolicyDependencyProvider {
    return object : PolicyDependencyProvider {
        private var gold = 999999
        override fun deductGold(amount: Int): Boolean {
            return if (goldAlwaysOk && gold >= amount) { gold -= amount; true } else false
        }
        override fun getMaxActivePolicies(): Int = maxActive
    }
}

private fun makeNoGoldProvider(maxActive: Int = 2): PolicyDependencyProvider {
    return object : PolicyDependencyProvider {
        override fun deductGold(amount: Int): Boolean = false
        override fun getMaxActivePolicies(): Int = maxActive
    }
}

class PolicyEngineTest {

    // ── MDU 16.1-TEST: 静态配置与容器预装载测试 ──────────────

    @Test
    fun testRationingConfigAccuracy() {
        val engine = PolicyEngine(makeProvider())
        val config = engine.getPolicyConfig(PolicyType.RATIONING)!!
        assertEquals(8, config.durationTicks)
        assertEquals(30, config.goldCost)
        assertEquals(0.5f, config.modifiers.foodConsumptionMultiplier, 0.001f)
    }

    @Test
    fun testScorchedEarthConfigAccuracy() {
        val engine = PolicyEngine(makeProvider())
        val config = engine.getPolicyConfig(PolicyType.SCORCHED_EARTH)!!
        assertEquals(60, config.goldCost)
        assertEquals(2, config.cooldownTicks)
        assertEquals(1.5f, config.modifiers.buildingDefenseMultiplier, 0.001f)
    }

    @Test
    fun testAllPoliciesPreloadedAsInactive() {
        val engine = PolicyEngine(makeProvider())
        val all = engine.getAllPolicies()
        assertEquals(6, all.size)
        all.forEach { snap ->
            assertEquals(PolicyState.INACTIVE, snap.state, "法令 ${snap.type} 初始应为 INACTIVE")
            assertEquals(0, snap.remainingTicks, "法令 ${snap.type} 初始计时器应为 0")
        }
    }

    // ── MDU 16.2-TEST: 颁布上限、废除、冷却抢跑测试 ─────────

    @Test
    fun testEnactPolicySuccessfully() {
        val engine = PolicyEngine(makeProvider(maxActive = 2))
        assertTrue(engine.enactPolicy(PolicyType.RATIONING, true))
        val snap = engine.getAllPolicies().first { it.type == PolicyType.RATIONING }
        assertEquals(PolicyState.ACTIVE, snap.state)
        assertEquals(8, snap.remainingTicks)
    }

    @Test
    fun testEnactOverMaxActiveBlocked() {
        val engine = PolicyEngine(makeProvider(maxActive = 2))
        assertTrue(engine.enactPolicy(PolicyType.RATIONING, true))
        assertTrue(engine.enactPolicy(PolicyType.MOBILIZATION, true))
        // 第三个应被拦截
        assertFalse(engine.enactPolicy(PolicyType.OPEN_TRADE, true))
        val snap = engine.getAllPolicies().first { it.type == PolicyType.OPEN_TRADE }
        assertEquals(PolicyState.INACTIVE, snap.state)
    }

    @Test
    fun testEnactFailsWhenNoGold() {
        val engine = PolicyEngine(makeNoGoldProvider())
        assertFalse(engine.enactPolicy(PolicyType.RATIONING, true))
        val snap = engine.getAllPolicies().first { it.type == PolicyType.RATIONING }
        assertEquals(PolicyState.INACTIVE, snap.state)
    }

    @Test
    fun testRepealEntersCooldown() {
        val engine = PolicyEngine(makeProvider())
        engine.enactPolicy(PolicyType.RATIONING, true)
        // 主动废除
        assertTrue(engine.enactPolicy(PolicyType.RATIONING, false))
        val snap = engine.getAllPolicies().first { it.type == PolicyType.RATIONING }
        assertEquals(PolicyState.COOLDOWN, snap.state)
        assertEquals(2, snap.remainingTicks)  // 冷却时长 2
    }

    @Test
    fun testCooldownBlocksReactivation() {
        val engine = PolicyEngine(makeProvider())
        engine.enactPolicy(PolicyType.RATIONING, true)
        engine.enactPolicy(PolicyType.RATIONING, false)
        // 冷却期间不允许再次激活
        assertFalse(engine.enactPolicy(PolicyType.RATIONING, true))
    }

    // ── MDU 16.3-TEST: 时序老化与系数叠乘测试 ────────────────

    @Test
    fun testPolicyExpiresAndEntersCooldown() {
        val engine = PolicyEngine(makeProvider())
        engine.enactPolicy(PolicyType.RATIONING, true)
        // RATIONING 持续 8 ticks
        repeat(8) { engine.processTick() }
        val snap = engine.getAllPolicies().first { it.type == PolicyType.RATIONING }
        assertEquals(PolicyState.COOLDOWN, snap.state)
        assertEquals(2, snap.remainingTicks)
    }

    @Test
    fun testCooldownExpiresAndReturnsToInactive() {
        val engine = PolicyEngine(makeProvider())
        engine.enactPolicy(PolicyType.RATIONING, true)
        repeat(8) { engine.processTick() }  // 进入 COOLDOWN（2 ticks）
        repeat(2) { engine.processTick() }  // 冷却期满
        val snap = engine.getAllPolicies().first { it.type == PolicyType.RATIONING }
        assertEquals(PolicyState.INACTIVE, snap.state)
        assertEquals(0, snap.remainingTicks)
    }

    @Test
    fun testMultiplePolicyModifiersMultiplication() {
        val engine = PolicyEngine(makeProvider(maxActive = 3))
        // MOBILIZATION: harvestYield * 0.7；FRANTIC_GATHERING: harvestYield * 1.3
        engine.enactPolicy(PolicyType.MOBILIZATION, true)
        engine.enactPolicy(PolicyType.FRANTIC_GATHERING, true)
        val mods = engine.getModifiers()
        // 叠乘：1.0 * 0.7 * 1.3 = 0.91
        assertEquals(0.91f, mods.harvestYieldMultiplier, 0.001f)
    }

    @Test
    fun testFranticGatheringEnergyMultiplier() {
        val engine = PolicyEngine(makeProvider())
        engine.enactPolicy(PolicyType.FRANTIC_GATHERING, true)
        val mods = engine.getModifiers()
        // 说明书：FRANTIC_GATHERING energyCostMultiplier = 2.0f
        assertEquals(2.0f, mods.energyCostMultiplier, 0.001f)
        assertEquals(1.3f, mods.harvestYieldMultiplier, 0.001f)
    }

    @Test
    fun testNoActivePoliciesReturnsDefaults() {
        val engine = PolicyEngine(makeProvider())
        val mods = engine.getModifiers()
        assertEquals(1.0f, mods.foodConsumptionMultiplier, 0.001f)
        assertEquals(1.0f, mods.harvestYieldMultiplier, 0.001f)
        assertEquals(1.0f, mods.moveSpeedMultiplier, 0.001f)
    }

    // ── MDU 16.4-TEST: 全生命周期闭环测试 ────────────────────

    @Test
    fun testFullPolicyLifecycle() {
        val engine = PolicyEngine(makeProvider())
        // 初始 INACTIVE
        assertEquals(PolicyState.INACTIVE, engine.getAllPolicies().first { it.type == PolicyType.RATIONING }.state)
        // 颁布
        assertTrue(engine.enactPolicy(PolicyType.RATIONING, true))
        val snap = engine.getAllPolicies().first { it.type == PolicyType.RATIONING }
        assertEquals(PolicyState.ACTIVE, snap.state)
        assertEquals(8, snap.remainingTicks)
        // 老化 8 次
        repeat(8) { engine.processTick() }
        assertEquals(PolicyState.COOLDOWN, snap.state)
        assertEquals(2, snap.remainingTicks)
        // 冷却 2 次
        repeat(2) { engine.processTick() }
        assertEquals(PolicyState.INACTIVE, snap.state)
        assertEquals(0, snap.remainingTicks)
    }

    @Test
    fun testConcurrentProcessTickNoConcurrentModificationException() = runTest {
        val engine = PolicyEngine(makeProvider(maxActive = 3))
        engine.enactPolicy(PolicyType.RATIONING, true)
        engine.enactPolicy(PolicyType.MOBILIZATION, true)

        val errors = mutableListOf<Throwable>()
        val jobs = List(50) {
            launch {
                try {
                    repeat(50) { engine.processTick() }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        } + List(20) {
            launch {
                try {
                    repeat(50) { engine.getModifiers() }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        jobs.forEach { it.join() }
        assertTrue(errors.isEmpty(), "高并发下不应出现异常：$errors")
    }
}
