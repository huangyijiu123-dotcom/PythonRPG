package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.PolicyModifiers
import com.example.pythonrpg.shared.WeatherModifiers
import com.example.pythonrpg.shared.TimePeriod
import com.example.pythonrpg.engine.entity.EntityStateManager
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

/**
 * VillagerAutomationSystem - 村民自转状态机
 */
public class VillagerAutomationSystem(
    private val entityStateManager: EntityStateManager? = null
) {
    public fun processTick(
        tickId: Long,
        timeOfDay: TimePeriod,
        policyModifier: PolicyModifiers,
        weatherModifiers: WeatherModifiers
    ) {
        val villagerIds = VillagerStateRegistry.detailedStates.keys().toList()

        for (vId in villagerIds) {
            val currentState = VillagerStateRegistry.detailedStates[vId] ?: "RESTING"

            // 昼夜作息规约拦截 (Twilight/Night 强制休息)
            if (timeOfDay == TimePeriod.NIGHT || timeOfDay == TimePeriod.TWILIGHT) {
                if (currentState != "RESTING") {
                    // 如果不在休息，强行转入休息状态，并保留原始工作意图
                    VillagerStateRegistry.detailedStates[vId] = "RESTING"
                }
                continue
            }

            when (currentState) {
                "RESTING" -> {
                    // 恢复体力 (受法令影响)
                    entityStateManager?.let { esm ->
                        val v = esm.getVillager(vId)
                        if (v != null && v.energy < 100) {
                            val baseRestore = 5f
                            val actualRestoreFloat = baseRestore * policyModifier.energyRestoreMultiplier
                            val restoreInt = actualRestoreFloat.toInt() + if (Random.nextFloat() < (actualRestoreFloat % 1)) 1 else 0
                            esm.updateVillagerEnergy(vId, Math.min(100, v.energy + restoreInt))
                        }
                    }

                    // 白天且有原定工作，自动恢复工作
                    if (timeOfDay == TimePeriod.MORNING || timeOfDay == TimePeriod.DAYTIME) {
                        val job = VillagerStateRegistry.originalJobs[vId]
                        if (job != null) {
                            VillagerStateRegistry.detailedStates[vId] = "WORKING"
                        }
                    }
                }
                "MOVING" -> {
                    // 向 target 移动
                    // 移动速度计算，可受 weatherModifiers.moveSpeedMultiplier 和 policyModifier.moveSpeedMultiplier 影响
                }
                "WORKING" -> {
                    entityStateManager?.let { esm ->
                        val v = esm.getVillager(vId)
                        if (v != null) {
                            // 1. 扣除体力
                            val baseEnergyCost = 2f
                            val actualCostFloat = baseEnergyCost * policyModifier.energyCostMultiplier * weatherModifiers.energyCostMultiplier
                            val costInt = actualCostFloat.toInt() + if (Random.nextFloat() < (actualCostFloat % 1)) 1 else 0
                            esm.updateVillagerEnergy(vId, v.energy - costInt)
                            
                            // 2. 产出资源计算
                            val job = VillagerStateRegistry.originalJobs[vId] ?: "LUMBERJACK"
                            val resourceName = if (job == "MINER") "STONE" else "WOOD"

                            // 组合 Buff 系数
                            val weatherYieldMultiplier = if (resourceName == "WOOD") {
                                weatherModifiers.loggingYieldMultiplier
                            } else if (resourceName == "STONE") {
                                weatherModifiers.miningYieldMultiplier
                            } else {
                                weatherModifiers.farmingYieldMultiplier
                            }

                            val baseYield = 1f
                            val totalYieldFloat = baseYield * weatherYieldMultiplier * policyModifier.harvestYieldMultiplier
                            
                            // 概率暴击掉落算法 (Option 3)
                            val guaranteedYield = totalYieldFloat.toInt()
                            val criticalChance = totalYieldFloat - guaranteedYield
                            val actualYield = guaranteedYield + if (Random.nextFloat() < criticalChance) 1 else 0

                            val currentAmount = v.backpack[resourceName] ?: 0
                            if (currentAmount >= 10) {
                                // 背包满了，跳转状态去仓库交付
                                VillagerStateRegistry.detailedStates[vId] = "DELIVERING"
                            } else {
                                if (actualYield > 0) {
                                    esm.updateVillagerBackpackItem(vId, resourceName, actualYield)
                                }
                            }
                        }
                    }
                }
                "DELIVERING" -> {
                    // 移动到仓库，并卸货，然后恢复到 originalJob
                    entityStateManager?.let { esm ->
                        val v = esm.getVillager(vId)
                        if (v != null) {
                            val itemsToDeliver = v.backpack.keys.toList()
                            for (item in itemsToDeliver) {
                                val amount = v.backpack[item] ?: 0
                                if (amount > 0) {
                                    val warehouses = esm.getAllWarehouses()
                                    if (warehouses.isNotEmpty()) {
                                        val wId = warehouses.first().id
                                        runBlocking {
                                            esm.transferItemVillagerToWarehouse(vId, wId, item, amount)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    VillagerStateRegistry.detailedStates[vId] = "WORKING"
                }
                "EQUIPPING" -> {
                    // 更换装备并恢复
                    VillagerStateRegistry.detailedStates[vId] = "WORKING"
                }
            }
        }
    }
}
