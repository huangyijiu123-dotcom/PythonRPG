package com.example.pythonrpg.engine.forge

import com.example.pythonrpg.shared.EquipmentSnapshot
import com.example.pythonrpg.shared.EquipmentClass
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// 1. 实时锻造任务快照
data class ForgeTask(
    val taskId: Long,
    val equipmentTemplateId: String,       // 目标装备模板 ID（如 "IRON_SWORD"）
    var currentProgress: Int,              // 当前制造进度 Tick
    val requiredTicks: Int                 // 制造此装备所需总 Tick
)

// 2. 装备制造基础配方配置类
data class EquipmentRecipe(
    val equipmentClass: String,            // 装备大类："WEAPON" 武器 或 "ARMOR" 防具
    val woodCost: Int,                     // 制造木材消耗
    val stoneCost: Int,                    // 制造石材消耗
    val ironCost: Int,                     // 制造铁矿消耗
    val goldCost: Int,                     // 制造金币消耗
    val obsidianCost: Int,                 // 制造黑曜石消耗
    val maxDurability: Int,                // 出厂最大耐久度
    val baseStat: Int,                     // 基础属性（武器对应攻击，防具对应防御）
    val requiredTicks: Int                 // 基础制造耗时 Tick
)

// 3. 单级精炼强化规则类
data class RefineRule(
    val successChance: Double,             // 强化成功率（0.0 ~ 1.0）
    val downgradePenalty: Int,             // 失败降级惩罚（降几级）
    val woodCost: Int,                     // 强化所需木材消耗
    val stoneCost: Int,                    // 强化所需石材消耗
    val ironCost: Int,                     // 强化所需铁矿消耗
    val goldCost: Int,                     // 强化所需金币消耗
    val obsidianCost: Int                  // 强化所需黑曜石消耗
)

// 铁匠铺外部数据库与仓库管理回调接口
interface ForgeDependencyProvider {
    // 从仓库划扣相应的锻造/强化原料（原料足额扣减返回 true，不足返回 false）
    fun deductResources(resources: Map<String, Int>): Boolean

    // 装备拆解或失败归还时，将原材料物理加回至主物资库
    fun addResourcesToWarehouse(resources: Map<String, Int>)

    // 制造完工出厂的一瞬间，将新装备以快照形式上架到玩家装备库
    fun addEquipmentToWarehouse(equipment: EquipmentSnapshot)

    // 从全局数据库中精准提取某件装备的实时属性快照
    fun getEquipment(equipmentId: Long): EquipmentSnapshot?

    // 强化/修理完工后，物理重写更新数据库中的装备镜像
    fun updateEquipment(equipment: EquipmentSnapshot)

    // 装备遭到物理熔炼拆解时，彻底从全局数据库中注销擦除该装备
    fun removeEquipment(equipmentId: Long)
}

/**
 * ForgeEngine - 锻造强化引擎
 */
open class ForgeEngine(
    private var dependencyProvider: ForgeDependencyProvider? = null,
    val templates: Map<String, EquipmentRecipe> = defaultTemplates,
    private val randomGenerator: () -> Double = { Math.random() }
) {
    // 全局自增任务 ID 发生器
    private val taskIdGenerator = AtomicLong(1L)
    
    // 全局自增装备 ID 发生器
    private val equipmentIdGenerator = AtomicLong(1001L)
    
    // 并发安全的锻造任务队列
    private val activeTasks = CopyOnWriteArrayList<ForgeTask>()

    companion object {
        val defaultTemplates = mapOf(
            "IRON_SWORD" to EquipmentRecipe(
                equipmentClass = "WEAPON",
                woodCost = 15,
                stoneCost = 0,
                ironCost = 8,
                goldCost = 0,
                obsidianCost = 0,
                maxDurability = 100,
                baseStat = 10,
                requiredTicks = 5
            ),
            "IRON_ARMOR" to EquipmentRecipe(
                equipmentClass = "ARMOR",
                woodCost = 10,
                stoneCost = 0,
                ironCost = 10,
                goldCost = 0,
                obsidianCost = 0,
                maxDurability = 120,
                baseStat = 8,
                requiredTicks = 6
            )
        )
    }

    /**
     * 设置依赖提供者
     */
    fun setDependencyProvider(provider: ForgeDependencyProvider) {
        this.dependencyProvider = provider
    }

    /**
     * 获取当前活跃任务队列副本
     */
    fun getActiveTasks(): List<ForgeTask> = activeTasks

    /**
     * 重置自增生成器及任务队列（供测试使用）
     */
    fun clear() {
        activeTasks.clear()
        taskIdGenerator.set(1L)
        equipmentIdGenerator.set(1001L)
    }

    /**
     * 将指定装备模板放入铁匠铺生产排单队列（扣料成功返回 true，缺料返回 false）
     */
    open fun queueForge(templateId: String): Boolean {
        val recipe = templates[templateId] ?: return false
        val provider = dependencyProvider ?: return false
        
        val costs = mapOf(
            "WOOD" to recipe.woodCost,
            "STONE" to recipe.stoneCost,
            "IRON_ORE" to recipe.ironCost,
            "GOLD" to recipe.goldCost,
            "OBSIDIAN" to recipe.obsidianCost
        )
        
        val success = provider.deductResources(costs)
        if (!success) {
            return false
        }
        
        val taskId = taskIdGenerator.getAndIncrement()
        val task = ForgeTask(
            taskId = taskId,
            equipmentTemplateId = templateId,
            currentProgress = 0,
            requiredTicks = recipe.requiredTicks
        )
        activeTasks.add(task)
        return true
    }

    /**
     * 兼容原 Stub 签名的 forge 方法
     */
    open fun forge(templateId: String): Boolean = queueForge(templateId)

    /**
     * 外部统一驱动的心跳推进器，每一 Tick 推进排单队列。
     * 单槽位限幅单工推进：仅对 activeTasks 最前排第一个任务进行进度推进
     */
    open fun processTick() {
        if (activeTasks.isEmpty()) return
        
        val task = activeTasks.first()
        task.currentProgress += 1
        
        if (task.currentProgress >= task.requiredTicks) {
            val recipe = templates[task.equipmentTemplateId] ?: return
            val eqId = equipmentIdGenerator.getAndIncrement()
            
            val eqClass = if (recipe.equipmentClass == "WEAPON") EquipmentClass.WEAPON else EquipmentClass.ARMOR
            val baseAttack = if (eqClass == EquipmentClass.WEAPON) recipe.baseStat else 0
            val baseDefense = if (eqClass == EquipmentClass.ARMOR) recipe.baseStat else 0
            
            val eq = EquipmentSnapshot(
                id = eqId,
                templateId = task.equipmentTemplateId,
                equipmentClass = eqClass,
                level = 0,
                durability = recipe.maxDurability,
                maxDurability = recipe.maxDurability,
                baseAttack = baseAttack,
                baseDefense = baseDefense,
                baseStat = recipe.baseStat,
                currentStat = recipe.baseStat,
                ownerId = null
            )
            
            dependencyProvider?.addEquipmentToWarehouse(eq)
            activeTasks.removeAt(0)
        }
    }

    /**
     * 将指定装备送入熔炉物理拆解（返还 50% 制造基础材料，并注销该装备）
     */
    open fun dismantleEquipment(equipmentId: Long): Boolean {
        val eq = dependencyProvider?.getEquipment(equipmentId) ?: return false
        val recipe = templates[eq.templateId] ?: return false
        
        val refunds = mutableMapOf<String, Int>()
        if (recipe.woodCost > 0) refunds["WOOD"] = Math.floor(recipe.woodCost * 0.5).toInt()
        if (recipe.stoneCost > 0) refunds["STONE"] = Math.floor(recipe.stoneCost * 0.5).toInt()
        if (recipe.ironCost > 0) refunds["IRON_ORE"] = Math.floor(recipe.ironCost * 0.5).toInt()
        if (recipe.goldCost > 0) refunds["GOLD"] = Math.floor(recipe.goldCost * 0.5).toInt()
        if (recipe.obsidianCost > 0) refunds["OBSIDIAN"] = Math.floor(recipe.obsidianCost * 0.5).toInt()
        
        dependencyProvider?.addResourcesToWarehouse(refunds)
        dependencyProvider?.removeEquipment(equipmentId)
        return true
    }

    /**
     * 获取当前强化等级对应的强化规则包
     */
    fun getRefineRule(currentLevel: Int): RefineRule {
        return when (currentLevel) {
            in 0..2 -> RefineRule(1.0, 0, woodCost = 10, stoneCost = 5, ironCost = 0, goldCost = 0, obsidianCost = 0)
            in 3..5 -> RefineRule(0.7, 1, woodCost = 0, stoneCost = 0, ironCost = 8, goldCost = 20, obsidianCost = 0)
            in 6..8 -> RefineRule(0.4, 1, woodCost = 0, stoneCost = 0, ironCost = 15, goldCost = 50, obsidianCost = 0)
            9 -> RefineRule(0.25, 2, woodCost = 0, stoneCost = 0, ironCost = 0, goldCost = 200, obsidianCost = 5)
            else -> RefineRule(0.0, 0, woodCost = 0, stoneCost = 0, ironCost = 0, goldCost = 0, obsidianCost = 0)
        }
    }

    /**
     * 强化指定的装备（扣减该等级所需资源，然后概率决定升级或惩罚降级）
     */
    open fun upgradeEquipment(equipmentId: Long): Boolean {
        val eq = dependencyProvider?.getEquipment(equipmentId) ?: return false
        if (eq.level >= 10) return false
        
        val rule = getRefineRule(eq.level)
        val costs = mapOf(
            "WOOD" to rule.woodCost,
            "STONE" to rule.stoneCost,
            "IRON_ORE" to rule.ironCost,
            "GOLD" to rule.goldCost,
            "OBSIDIAN" to rule.obsidianCost
        )
        
        val success = dependencyProvider?.deductResources(costs) ?: false
        if (!success) {
            return false
        }
        
        val roll = randomGenerator()
        if (roll < rule.successChance) {
            val newLvl = eq.level + 1
            val newStat = (eq.baseStat.toDouble() * (1.0 + newLvl * 0.1) + 1e-9).toInt()
            val newEq = eq.copy(level = newLvl, currentStat = newStat)
            dependencyProvider?.updateEquipment(newEq)
            return true
        } else {
            val newLvl = if (eq.level >= 3) Math.max(3, eq.level - rule.downgradePenalty) else Math.max(0, eq.level - rule.downgradePenalty)
            val newStat = (eq.baseStat.toDouble() * (1.0 + newLvl * 0.1) + 1e-9).toInt()
            val newEq = eq.copy(level = newLvl, currentStat = newStat)
            dependencyProvider?.updateEquipment(newEq)
            return false
        }
    }

    /**
     * 自动连续安全强化：由玩家或脚本调用，连续强化直到达到目标等级、材料不足或达到最高等级 10
     */
    fun upgradeTo(equipmentId: Long, targetLevel: Int): Int {
        while (true) {
            val currentLvl = dependencyProvider?.getEquipment(equipmentId)?.level ?: return 0
            if (currentLvl >= targetLevel || currentLvl >= 10) {
                break
            }
            val canContinue = upgradeEquipment(equipmentId)
            val latestLvl = dependencyProvider?.getEquipment(equipmentId)?.level ?: break
            if (!canContinue && latestLvl >= currentLvl) {
                break
            }
        }
        return dependencyProvider?.getEquipment(equipmentId)?.level ?: 0
    }

    /**
     * 装备打磨维修：消耗一定比例的“铁矿石”原料，将装备的 durability 耐久恢复至 maxDurability 最大耐久值
     */
    open fun repairEquipment(equipmentId: Long): Boolean {
        val eq = dependencyProvider?.getEquipment(equipmentId) ?: return false
        if (eq.durability >= eq.maxDurability) {
            return true
        }
        
        val recipe = templates[eq.templateId] ?: return false
        val lossRate = 1.0 - (eq.durability.toDouble() / eq.maxDurability.toDouble())
        val requiredIron = Math.ceil(recipe.ironCost.toDouble() * lossRate).toInt()
        val finalRequiredIron = Math.max(1, requiredIron)
        
        val costs = mapOf("IRON_ORE" to finalRequiredIron)
        val success = dependencyProvider?.deductResources(costs) ?: false
        if (!success) {
            return false
        }
        
        val newEq = eq.copy(durability = eq.maxDurability)
        dependencyProvider?.updateEquipment(newEq)
        return true
    }

    /**
     * 兼容原 Stub 签名的 repairAllEquipment 方法
     */
    open fun repairAllEquipment(adventurerId: Long): Boolean = true
}
