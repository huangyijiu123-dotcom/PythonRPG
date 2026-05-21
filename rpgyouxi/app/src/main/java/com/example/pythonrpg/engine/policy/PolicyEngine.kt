package com.example.pythonrpg.engine.policy

import com.example.pythonrpg.shared.PolicyModifiers
import java.util.concurrent.ConcurrentHashMap

// ── 六大执政法令枚举（按说明书 16.1 严格定义）───────────────────
enum class PolicyType {
    RATIONING,          // 🍞 口粮配给制
    MOBILIZATION,       // ⚔️ 全民皆兵
    OPEN_TRADE,         // 📈 商业开放
    FRANTIC_GATHERING,  // ⛏️ 狂热采集
    TECH_LEAP,          // 🔬 科技跃进
    SCORCHED_EARTH      // 🛡️ 坚壁清野
}

// ── 三大法令运转状态 ──────────────────────────────────────────
enum class PolicyState {
    INACTIVE,  // 未颁布
    ACTIVE,    // 正式颁布生效中
    COOLDOWN   // 废除或到期后的冷静冷却期
}

// ── 实时法令运行快照 ──────────────────────────────────────────
data class PolicySnapshot(
    val type: PolicyType,
    var state: PolicyState,
    var remainingTicks: Int  // ACTIVE 时为剩余生效 Tick，COOLDOWN 时为剩余冷却 Tick
)

// ── 单个法令静态配置 ──────────────────────────────────────────
data class PolicyConfig(
    val durationTicks: Int,
    val goldCost: Int,
    val cooldownTicks: Int,
    val modifiers: PolicyModifiers
)

// ── 外部依赖注入接口（金币扣减 & 激活上限查询）────────────────
interface PolicyDependencyProvider {
    fun deductGold(amount: Int): Boolean
    fun getMaxActivePolicies(): Int
}

// ── 法令引擎 ──────────────────────────────────────────────────
class PolicyEngine(private val dependencyProvider: PolicyDependencyProvider) {

    // 硬编码 6 大法令静态配置矩阵
    internal val configs: Map<PolicyType, PolicyConfig> = mapOf(
        PolicyType.RATIONING to PolicyConfig(
            durationTicks = 8, goldCost = 30, cooldownTicks = 2,
            modifiers = PolicyModifiers(
                foodConsumptionMultiplier = 0.5f,
                energyRestoreMultiplier   = 0.7f
            )
        ),
        PolicyType.MOBILIZATION to PolicyConfig(
            durationTicks = 6, goldCost = 50, cooldownTicks = 2,
            modifiers = PolicyModifiers(
                combatAttackMultiplier = 1.2f,
                harvestYieldMultiplier = 0.7f
            )
        ),
        PolicyType.OPEN_TRADE to PolicyConfig(
            durationTicks = 10, goldCost = 80, cooldownTicks = 2,
            modifiers = PolicyModifiers(
                caravanSpeedMultiplier = 1.5f,
                tradeProfitMultiplier  = 0.85f
            )
        ),
        PolicyType.FRANTIC_GATHERING to PolicyConfig(
            durationTicks = 6, goldCost = 40, cooldownTicks = 2,
            modifiers = PolicyModifiers(
                harvestYieldMultiplier = 1.3f,
                energyCostMultiplier   = 2.0f
            )
        ),
        PolicyType.TECH_LEAP to PolicyConfig(
            durationTicks = 12, goldCost = 100, cooldownTicks = 2,
            modifiers = PolicyModifiers(
                upgradeCostMultiplier = 0.8f,
                techCostMultiplier    = 0.85f
            )
        ),
        PolicyType.SCORCHED_EARTH to PolicyConfig(
            durationTicks = 8, goldCost = 60, cooldownTicks = 2,
            modifiers = PolicyModifiers(
                buildingDefenseMultiplier = 1.5f,
                moveSpeedMultiplier       = 0.8f
            )
        )
    )

    // 并发安全法令实时容器
    private val policies = ConcurrentHashMap<PolicyType, PolicySnapshot>()

    init {
        // 6 种法令全部以 INACTIVE 态、remainingTicks=0 预先装填
        PolicyType.values().forEach { type ->
            policies[type] = PolicySnapshot(type, PolicyState.INACTIVE, 0)
        }
    }

    // ── 快照拉取 API ──────────────────────────────────────────
    fun getAllPolicies(): List<PolicySnapshot> = policies.values.toList()

    fun getPolicyConfig(type: PolicyType): PolicyConfig? = configs[type]

    // ── 法令颁布 / 废除 ───────────────────────────────────────
    fun enactPolicy(type: PolicyType, isActive: Boolean): Boolean {
        val snap = policies[type] ?: return false

        return if (isActive) {
            // 步骤 B：冷却及重复激活审查
            if (snap.state != PolicyState.INACTIVE) return false

            // 步骤 C：执政上限检查
            val activeCount = policies.values.count { it.state == PolicyState.ACTIVE }
            if (activeCount >= dependencyProvider.getMaxActivePolicies()) return false

            // 步骤 D：金币扣减核销
            val config = configs[type] ?: return false
            if (!dependencyProvider.deductGold(config.goldCost)) return false

            // 步骤 E：流转录入
            snap.state = PolicyState.ACTIVE
            snap.remainingTicks = config.durationTicks
            true
        } else {
            // 废除：只有 ACTIVE 的法令才能被主动撤下
            if (snap.state != PolicyState.ACTIVE) return false
            val config = configs[type] ?: return false
            snap.state = PolicyState.COOLDOWN
            snap.remainingTicks = config.cooldownTicks
            true
        }
    }

    // ── Tick 步进：倒计时老化 + 状态流转 ─────────────────────
    fun processTick() {
        // 遍历副本，防止 ConcurrentModificationException
        policies.values.toList().forEach { snap ->
            when (snap.state) {
                PolicyState.ACTIVE -> {
                    snap.remainingTicks -= 1
                    if (snap.remainingTicks <= 0) {
                        val config = configs[snap.type] ?: return@forEach
                        snap.state = PolicyState.COOLDOWN
                        snap.remainingTicks = config.cooldownTicks
                    }
                }
                PolicyState.COOLDOWN -> {
                    snap.remainingTicks -= 1
                    if (snap.remainingTicks <= 0) {
                        snap.state = PolicyState.INACTIVE
                        snap.remainingTicks = 0
                    }
                }
                PolicyState.INACTIVE -> { /* 无需处理 */ }
            }
        }
    }

    // ── 系数级联叠乘 ─────────────────────────────────────────
    fun getModifiers(): PolicyModifiers {
        val activeSnaps = policies.values.filter { it.state == PolicyState.ACTIVE }
        return activeSnaps.fold(PolicyModifiers()) { acc, snap ->
            val cfg = configs[snap.type] ?: return@fold acc
            acc.copy(
                foodConsumptionMultiplier  = acc.foodConsumptionMultiplier  * cfg.modifiers.foodConsumptionMultiplier,
                energyRestoreMultiplier    = acc.energyRestoreMultiplier    * cfg.modifiers.energyRestoreMultiplier,
                combatAttackMultiplier     = acc.combatAttackMultiplier     * cfg.modifiers.combatAttackMultiplier,
                harvestYieldMultiplier     = acc.harvestYieldMultiplier     * cfg.modifiers.harvestYieldMultiplier,
                caravanSpeedMultiplier     = acc.caravanSpeedMultiplier     * cfg.modifiers.caravanSpeedMultiplier,
                tradeProfitMultiplier      = acc.tradeProfitMultiplier      * cfg.modifiers.tradeProfitMultiplier,
                energyCostMultiplier       = acc.energyCostMultiplier       * cfg.modifiers.energyCostMultiplier,
                upgradeCostMultiplier      = acc.upgradeCostMultiplier      * cfg.modifiers.upgradeCostMultiplier,
                techCostMultiplier         = acc.techCostMultiplier         * cfg.modifiers.techCostMultiplier,
                buildingDefenseMultiplier  = acc.buildingDefenseMultiplier  * cfg.modifiers.buildingDefenseMultiplier,
                moveSpeedMultiplier        = acc.moveSpeedMultiplier        * cfg.modifiers.moveSpeedMultiplier
            )
        }
    }
}
