package com.example.pythonrpg.engine.tech

import java.util.concurrent.ConcurrentHashMap

// ── 四大科研运转状态 ──────────────────────────────────────────
enum class TechState {
    LOCKED,      // 未解锁且前置依赖不满足，封锁中
    AVAILABLE,   // 前置科技已解锁完毕，已备齐条件，可点击/开始研究
    RESEARCHING, // 研究考核中（已暂存质押对应物资，等待沙盒考核）
    UNLOCKED     // 代码考核通过，正式解锁并激活全局特权
}

// ── 科技树节点快照实体 ─────────────────────────────────────────
data class TechNode(
    val techId: String,
    val cost: Map<String, Int>,               // 研发所需物资（如 "WOOD" to 50）
    val prerequisites: List<String>,          // 前置依赖科技 ID 树链
    var state: TechState
)

// ── 科技外部资源和全局通知回调接口 ────────────────────────────────
interface TechDependencyProvider {
    // 从玩家仓库冻结/扣除对应的研发物料（质押暂存，若资源不足返回 false）
    fun tryPledgeResources(resources: Map<String, Int>): Boolean

    // 考核最终通过时，将之前已暂存质押的物资彻底抹去扣除
    fun consumePledgedResources(resources: Map<String, Int>)

    // 玩家取消研究、或放弃任务时，将之前暂存质押的物资原封不动归还至大仓库
    fun refundPledgedResources(resources: Map<String, Int>)

    // 某项科技正式解锁完工时触发全局通告，让其他系统（如村民管理/建筑系统）刷新上限
    fun onTechUnlockedGlobalEvent(techId: String)
}

/**
 * TechEngine - 科技树管理引擎
 */
class TechEngine(
    private var dependencyProvider: TechDependencyProvider? = null
) {
    // 并发安全的科技树节点容器字典
    private val nodes = ConcurrentHashMap<String, TechNode>()

    init {
        reset()
    }

    /**
     * 设置依赖提供者
     */
    fun setDependencyProvider(provider: TechDependencyProvider) {
        this.dependencyProvider = provider
    }

    /**
     * 重置科技树至开局初态
     */
    fun reset() {
        nodes.clear()
        
        // 13 个科技静态配置预装载
        nodes["BASIC_MANAGEMENT"] = TechNode(
            techId = "BASIC_MANAGEMENT",
            cost = emptyMap(),
            prerequisites = emptyList(),
            state = TechState.AVAILABLE // 初始免费无前置，开局可用
        )
        nodes["BACKPACK_WEAVING"] = TechNode(
            techId = "BACKPACK_WEAVING",
            cost = mapOf("WOOD" to 50),
            prerequisites = listOf("BASIC_MANAGEMENT"),
            state = TechState.LOCKED
        )
        nodes["WHEELBARROW"] = TechNode(
            techId = "WHEELBARROW",
            cost = mapOf("WOOD" to 200),
            prerequisites = listOf("BACKPACK_WEAVING"),
            state = TechState.LOCKED
        )
        nodes["STONE_TOOLS"] = TechNode(
            techId = "STONE_TOOLS",
            cost = mapOf("WOOD" to 150),
            prerequisites = listOf("BASIC_MANAGEMENT"),
            state = TechState.LOCKED
        )
        nodes["IRON_FORGING"] = TechNode(
            techId = "IRON_FORGING",
            cost = mapOf("IRON_ORE" to 300),
            prerequisites = listOf("STONE_TOOLS"),
            state = TechState.LOCKED
        )
        nodes["ADVANCED_BUILDING"] = TechNode(
            techId = "ADVANCED_BUILDING",
            cost = mapOf("STONE" to 500),
            prerequisites = listOf("BASIC_MANAGEMENT"),
            state = TechState.LOCKED
        )
        nodes["WORKSHOP_OPTIMIZATION"] = TechNode(
            techId = "WORKSHOP_OPTIMIZATION",
            cost = mapOf("STONE" to 300),
            prerequisites = listOf("STONE_TOOLS"),
            state = TechState.LOCKED
        )
        nodes["LOGISTICS_DISPATCH"] = TechNode(
            techId = "LOGISTICS_DISPATCH",
            cost = mapOf("GOLD" to 200),
            prerequisites = listOf("WHEELBARROW"),
            state = TechState.LOCKED
        )
        nodes["DISTRIBUTION_CENTER"] = TechNode(
            techId = "DISTRIBUTION_CENTER",
            cost = mapOf("STONE" to 300, "GOLD" to 200),
            prerequisites = listOf("LOGISTICS_DISPATCH", "ADVANCED_BUILDING"),
            state = TechState.LOCKED
        )
        nodes["RADIATION_EXPANSION"] = TechNode(
            techId = "RADIATION_EXPANSION",
            cost = mapOf("GOLD" to 500),
            prerequisites = listOf("DISTRIBUTION_CENTER"),
            state = TechState.LOCKED
        )
        nodes["ADVANCED_EXPLORATION"] = TechNode(
            techId = "ADVANCED_EXPLORATION",
            cost = mapOf("GOLD" to 300),
            prerequisites = listOf("DISTRIBUTION_CENTER"),
            state = TechState.LOCKED
        )
        nodes["ARCHEOLOGY"] = TechNode(
            techId = "ARCHEOLOGY",
            cost = mapOf("RELIC" to 5, "GOLD" to 100),
            prerequisites = listOf("BASIC_MANAGEMENT"),
            state = TechState.LOCKED
        )
        nodes["CRYPTOGRAPHY"] = TechNode(
            techId = "CRYPTOGRAPHY",
            cost = mapOf("GOLD" to 500),
            prerequisites = listOf("ARCHEOLOGY"),
            state = TechState.LOCKED
        )
    }

    /**
     * 查询特定科技当前的研究状态
     */
    fun getTechState(techId: String): TechState {
        return nodes[techId]?.state ?: TechState.LOCKED
    }

    /**
     * 查询某项科技是否已正式解锁
     */
    fun isTechUnlocked(techId: String): Boolean {
        return getTechState(techId) == TechState.UNLOCKED
    }

    /**
     * 获取当前科技树全部节点的快照副本
     */
    fun getTechTreeSnapshot(): List<TechNode> {
        return nodes.values.toList()
    }

    /**
     * 尝试对指定科技启动研究（含前置依赖状态校验与原子资源质押）
     */
    fun startResearch(techId: String): Boolean {
        val node = nodes[techId] ?: return false
        
        // 非法越轨红线：只有 AVAILABLE 状态下允许启动研究
        if (node.state != TechState.AVAILABLE) {
            return false
        }

        val provider = dependencyProvider ?: return false
        
        // 尝试原子质押
        val pledged = provider.tryPledgeResources(node.cost)
        if (!pledged) {
            return false // 资源不足，拦截
        }

        node.state = TechState.RESEARCHING
        return true
    }

    /**
     * 放弃/取消当前处于 RESEARCHING 中的研究项目，安全归还质押物资
     */
    fun cancelResearch(techId: String) {
        val node = nodes[techId] ?: return
        
        // 只有在研项目允许撤销
        if (node.state != TechState.RESEARCHING) {
            return
        }

        node.state = TechState.AVAILABLE
        dependencyProvider?.refundPledgedResources(node.cost)
    }

    /**
     * 提交沙盒对当前在研科技的代码考核结果
     */
    fun submitAssessmentResult(techId: String, isPassed: Boolean) {
        val node = nodes[techId] ?: return
        
        // 在研状态审查：未在研节点禁止强行伪造通过
        if (node.state != TechState.RESEARCHING) {
            return
        }

        if (isPassed) {
            node.state = TechState.UNLOCKED
            val provider = dependencyProvider
            if (provider != null) {
                provider.consumePledgedResources(node.cost)
                provider.onTechUnlockedGlobalEvent(techId)
            }

            // 级联依赖传导算法：顺着树脉络解锁后续 AVAILABLE 科技
            var changed: Boolean
            do {
                changed = false
                for (n in nodes.values) {
                    if (n.state == TechState.LOCKED) {
                        val allPrerequisitesUnlocked = n.prerequisites.all { prereqId ->
                            nodes[prereqId]?.state == TechState.UNLOCKED
                        }
                        if (allPrerequisitesUnlocked) {
                            n.state = TechState.AVAILABLE
                            changed = true
                        }
                    }
                }
            } while (changed)
        } else {
            // 失败零惩罚：不退不扣，静默保持原样
        }
    }
}
