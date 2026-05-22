package com.example.pythonrpg.engine.workshop

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// ── 六大核心工具类型枚举 ──────────────────────────────────────────
enum class ToolType {
    STONE_AXE,       // 🪓 石斧
    IRON_AXE,        // 🪓 铁斧
    STONE_PICKAXE,   // ⛏️ 石镐
    IRON_PICKAXE,    // ⛏️ 铁镐
    BACKPACK,        // 🎒 纤维背包
    WHEELBARROW      // 🛒 木质小推车
}

// ── 工具制造队列任务数据 ──────────────────────────────────────────
data class ProductionTask(
    val taskId: Long,
    val toolType: ToolType,
    val totalCount: Int,               // 本批次要求制造的总数量
    var completedCount: Int,           // 目前已完成出库的数量
    var currentProgress: Int,          // 当前正在制造的一个工具的 Tick 进度
    val requiredTicks: Int             // 制造一个此工具所需的标准总 Tick 耗时
)

// ── 工房运行状态快照 ──────────────────────────────────────────────
data class WorkshopState(
    val buildingId: Long,
    val maxSlots: Int,                             // 并行制造槽位数
    val activeTasks: MutableList<ProductionTask>   // 活跃制造队列（CopyOnWriteArrayList 支持线程安全增删）
)

// ── 工具配方定义 ──────────────────────────────────────────────────
data class WorkshopRecipe(
    val materialCost: Map<String, Int>,            // 材料消耗（如 "WOOD" to 5）
    val requiredTicks: Int,                        // 生产单件标准耗时
    val requiredTechId: String?                    // 前置科技解锁标志，null 代表开局可用
)

// ── 工房外部资源和科技依赖回调接口 ──────────────────────────────────
interface WorkshopDependencyProvider {
    // 扣减全局仓库中的原材料（扣减成功返回 true，不足返回 false）
    fun deductResources(resources: Map<String, Int>): Boolean

    // 当任务被玩家中途取消时，返还未制造部分的 100% 原材料
    fun refundResources(resources: Map<String, Int>)

    // 生产完成一个成品时，反向让物理仓库模块执行加货上架
    fun addToolToWarehouse(workshopBuildingId: Long, toolType: ToolType)

    // 查询科学院是否解锁了特定前置科技
    fun isTechUnlocked(techId: String): Boolean
}

/**
 * WorkshopEngine - 工房生产引擎
 */
open class WorkshopEngine(
    private var dependencyProvider: WorkshopDependencyProvider? = null
) {
    // 全局自增任务 ID 发生器
    private val taskIdGenerator = AtomicLong(1L)

    // 并发安全的全球工房底仓状态字典
    private val workshops = ConcurrentHashMap<Long, WorkshopState>()

    // 六大工具的只读硬编码静态配置
    val recipes: Map<ToolType, WorkshopRecipe> = mapOf(
        ToolType.STONE_AXE to WorkshopRecipe(
            materialCost = mapOf("WOOD" to 5),
            requiredTicks = 3,
            requiredTechId = "STONE_TOOLS"
        ),
        ToolType.IRON_AXE to WorkshopRecipe(
            materialCost = mapOf("WOOD" to 5, "IRON_ORE" to 3),
            requiredTicks = 5,
            requiredTechId = "IRON_FORGING"
        ),
        ToolType.STONE_PICKAXE to WorkshopRecipe(
            materialCost = mapOf("WOOD" to 5),
            requiredTicks = 3,
            requiredTechId = "STONE_TOOLS"
        ),
        ToolType.IRON_PICKAXE to WorkshopRecipe(
            materialCost = mapOf("WOOD" to 5, "IRON_ORE" to 3),
            requiredTicks = 5,
            requiredTechId = "IRON_FORGING"
        ),
        ToolType.BACKPACK to WorkshopRecipe(
            materialCost = mapOf("FIBER" to 10),
            requiredTicks = 2,
            requiredTechId = "BACKPACK_WEAVING"
        ),
        ToolType.WHEELBARROW to WorkshopRecipe(
            materialCost = mapOf("WOOD" to 20),
            requiredTicks = 4,
            requiredTechId = "WHEELBARROW"
        )
    )

    /**
     * 设置依赖提供者
     */
    fun setDependencyProvider(provider: WorkshopDependencyProvider) {
        this.dependencyProvider = provider
    }

    /**
     * 重置所有工房状态（供测试与重载使用）
     */
    fun clear() {
        workshops.clear()
        taskIdGenerator.set(1L)
    }

    /**
     * 注册新的工坊至引擎
     */
    fun registerWorkshop(buildingId: Long, maxSlots: Int) {
        workshops[buildingId] = WorkshopState(
            buildingId = buildingId,
            maxSlots = maxSlots,
            activeTasks = CopyOnWriteArrayList()
        )
    }

    /**
     * 读取特定工坊的实时多槽位运转快照
     */
    fun getWorkshopState(workshopId: Long): WorkshopState? {
        return workshops[workshopId]
    }

    /**
     * 往指定工坊排队添加批量工具制造任务（包含前置科技校验和多材料扣减拦截）
     */
    open fun queueProduction(workshopId: Long, toolType: String, count: Int): Boolean {
        // 兼容原有的 String 签名，转换成 Enum 后处理
        val type = try {
            ToolType.valueOf(toolType)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return queueProduction(workshopId, type, count)
    }

    /**
     * 往指定工坊排队添加批量工具制造任务（强类型）
     */
    fun queueProduction(workshopId: Long, toolType: ToolType, count: Int): Boolean {
        if (count <= 0) return false
        
        val state = workshops[workshopId] ?: return false
        val recipe = recipes[toolType] ?: return false
        val provider = dependencyProvider ?: return false

        // 1. 前置科技图纸拦截
        if (recipe.requiredTechId != null) {
            val isUnlocked = provider.isTechUnlocked(recipe.requiredTechId)
            if (!isUnlocked) {
                return false // 图纸被锁，拦截
            }
        }

        // 2. 批量原材料原子扣划
        val totalCost = recipe.materialCost.mapValues { (_, qty) -> qty * count }
        val success = provider.deductResources(totalCost)
        if (!success) {
            return false // 资源不足，拒绝入队
        }

        // 3. 构造并录入活跃排队队列
        val taskId = taskIdGenerator.getAndIncrement()
        val task = ProductionTask(
            taskId = taskId,
            toolType = toolType,
            totalCount = count,
            completedCount = 0,
            currentProgress = 0,
            requiredTicks = recipe.requiredTicks
        )
        state.activeTasks.add(task)
        return true
    }

    /**
     * 外部总心跳驱动推进器，根据各工房最大制造并行槽位数滚动活跃任务进度，并触发反向出库交付
     */
    open fun processTick() {
        for (state in workshops.values) {
            val tasks = state.activeTasks
            if (tasks.isEmpty()) continue

            // 槽位并行限幅滚动：有且仅能处理前 maxSlots 个活跃任务
            val runningTasks = tasks.take(state.maxSlots)
            val provider = dependencyProvider

            for (task in runningTasks) {
                task.currentProgress += 1
                
                // 单件交付出库判定
                if (task.currentProgress >= task.requiredTicks) {
                    provider?.addToolToWarehouse(state.buildingId, task.toolType)
                    task.completedCount += 1
                    task.currentProgress = 0
                }
            }

            // 批次大完工注销：线程安全过滤已完成的任务
            tasks.removeIf { it.completedCount >= it.totalCount }
        }
    }

    /**
     * 取消指定工坊的任务的未开始部分，折算返还 100% 材料并移出队列
     */
    fun cancelProduction(workshopId: Long, taskId: Long) {
        val state = workshops[workshopId] ?: return
        val task = state.activeTasks.firstOrNull { it.taskId == taskId } ?: return
        val provider = dependencyProvider

        // 1. 折旧返料公式计算：未开始制造套数 = totalCount - completedCount
        val unstartedCount = task.totalCount - task.completedCount
        if (unstartedCount > 0) {
            val recipe = recipes[task.toolType] ?: return
            val refundCost = recipe.materialCost.mapValues { (_, qty) -> qty * unstartedCount }
            provider?.refundResources(refundCost)
        }

        // 2. 物理从活跃任务中注销剔除
        state.activeTasks.remove(task)
    }
}
