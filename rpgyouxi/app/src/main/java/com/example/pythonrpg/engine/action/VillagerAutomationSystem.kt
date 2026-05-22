package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.*
import com.example.pythonrpg.engine.entity.EntityStateManager
import com.example.pythonrpg.engine.building.BuildingEngine
import com.example.pythonrpg.engine.building.BuildingType
import com.example.pythonrpg.engine.map.GridMapData
import com.example.pythonrpg.engine.pathfinding.PathfindingEngine
import com.example.pythonrpg.engine.pathfinding.PassabilityGrid
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

/**
 * ActionConfig - 存储工具效率及系统加成配置
 */
public object ActionConfig {
    public val toolEfficiencyBonus: Map<String, Float> = mapOf(
        "STONE_AXE" to 0.2f,
        "IRON_AXE" to 0.5f,
        "STONE_PICKAXE" to 0.2f,
        "IRON_PICKAXE" to 0.5f,
        "STONE_HOE" to 0.2f,
        "IRON_HOE" to 0.5f
    )
}

/**
 * VillagerAutomationSystem - 村民自转状态机
 */
public class VillagerAutomationSystem(
    private val entityStateManager: EntityStateManager? = null,
    private val buildingEngine: BuildingEngine? = null,
    private val gridMapData: GridMapData? = null
) {
    public var eventPublisher: ((GameEvent) -> Unit)? = null
    private val pathfindingEngine = PathfindingEngine()

    public fun processTick(
        tickId: Long,
        timeOfDay: TimePeriod,
        policyModifier: PolicyModifiers,
        weatherModifiers: WeatherModifiers
    ) {
        val villagerIds = VillagerStateRegistry.detailedStates.keys().toList()

        for (vId in villagerIds) {
            val currentState = VillagerStateRegistry.detailedStates[vId] ?: "RESTING"
            val v = entityStateManager?.getVillager(vId)
            val cottageId = VillagerStateRegistry.cottageIds[vId]
            val cottage = cottageId?.let { buildingEngine?.getBuilding(it) }

            // 1. 黄昏与夜晚强制审查拦截 (Twilight & Night Enforcement)
            if (timeOfDay == TimePeriod.TWILIGHT) {
                if (v == null || cottage == null) {
                    // 兼容旧测试：若缺少数据，退化为粗暴休息
                    if (currentState != "RESTING") {
                        VillagerStateRegistry.detailedStates[vId] = "RESTING"
                    }
                    continue
                }
                
                if (currentState == "WORKING") {
                    val backpackHasItems = v.backpack.values.any { it > 0 }
                    if (backpackHasItems) {
                        VillagerStateRegistry.detailedStates[vId] = "DELIVERING"
                        val warehouses = entityStateManager?.getAllWarehouses() ?: emptyList()
                        val nearestWarehouse = warehouses.minByOrNull { w ->
                            val dx = w.coordinate.x - v.coordinate.x
                            val dy = w.coordinate.y - v.coordinate.y
                            dx * dx + dy * dy
                        }
                        if (nearestWarehouse != null) {
                            entityStateManager?.updateVillagerJob(vId, v.job, nearestWarehouse.coordinate.x, nearestWarehouse.coordinate.y)
                        }
                    } else {
                        VillagerStateRegistry.detailedStates[vId] = "MOVING"
                        entityStateManager?.updateVillagerJob(vId, v.job, cottage.x, cottage.y)
                    }
                    eventPublisher?.invoke(GameEvent.VillagerReturningHome(vId, "TWILIGHT"))
                    continue
                } else if (currentState == "MOVING" && (v.targetX != cottage.x || v.targetY != cottage.y)) {
                    // 强制掉头返回民房
                    entityStateManager?.updateVillagerJob(vId, v.job, cottage.x, cottage.y)
                    eventPublisher?.invoke(GameEvent.VillagerReturningHome(vId, "TWILIGHT"))
                    continue
                }
            }

            if (timeOfDay == TimePeriod.NIGHT) {
                if (v == null || cottage == null) {
                    // 兼容旧测试：若缺少数据，退化为粗暴休息
                    if (currentState != "RESTING") {
                        VillagerStateRegistry.detailedStates[vId] = "RESTING"
                    }
                    continue
                }
            }

            // 2. 状态机常规自转逻辑
            when (currentState) {
                "RESTING" -> {
                    entityStateManager?.let { esm ->
                        if (v != null && v.energy < 100) {
                            val baseRestore = 5f
                            val restoreFactor = if (timeOfDay == TimePeriod.NIGHT) {
                                if (cottage != null && v.coordinate.x == cottage.x && v.coordinate.y == cottage.y) {
                                    2.0f // nightRestoreBonus
                                } else {
                                    0.5f // overnightEnergyPenalty
                                }
                            } else {
                                1.0f
                            }
                            val actualRestoreFloat = baseRestore * restoreFactor * policyModifier.energyRestoreMultiplier
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
                    if (v != null) {
                        val fromX = v.coordinate.x
                        val fromY = v.coordinate.y
                        val toX = v.targetX ?: fromX
                        val toY = v.targetY ?: fromY

                        if (fromX != toX || fromY != toY) {
                            val grid = object : PassabilityGrid {
                                override fun isPassable(x: Int, y: Int): Boolean {
                                    if (gridMapData == null) return true
                                    val tile = gridMapData.getTile(x, y) ?: return false
                                    return (tile.exploreStatus == ExploreStatus.VISIBLE_UNEXPLORED || tile.exploreStatus == ExploreStatus.EXPLORED) && !tile.hasMonster
                                }
                            }

                            val pathResult = pathfindingEngine.findPath(fromX, fromY, toX, toY, grid)
                            if (pathResult.found && pathResult.path.size > 1) {
                                val speed = 2
                                val nextIndex = Math.min(pathResult.path.size - 1, speed)
                                val nextCoord = pathResult.path[nextIndex]

                                entityStateManager?.updateVillagerPosition(vId, nextCoord.x, nextCoord.y)
                                eventPublisher?.invoke(GameEvent.VillagerMoved(vId, nextCoord.x, nextCoord.y))

                                if (nextCoord.x == toX && nextCoord.y == toY) {
                                    // 到达终点，切回目标物理作业
                                    transitionAfterArrived(vId, toX, toY, cottage)
                                }
                            }
                        } else {
                            transitionAfterArrived(vId, toX, toY, cottage)
                        }
                    }
                }
                "WORKING" -> {
                    val esm = entityStateManager
                    if (esm != null && v != null) {
                        // 1. 扣除体力
                        val baseEnergyCost = 2f
                        val actualCostFloat = baseEnergyCost * policyModifier.energyCostMultiplier * weatherModifiers.energyCostMultiplier
                        val costInt = actualCostFloat.toInt() + if (Random.nextFloat() < (actualCostFloat % 1)) 1 else 0
                        val newEnergy = v.energy - costInt
                        esm.updateVillagerEnergy(vId, newEnergy)

                        if (newEnergy <= 0) {
                            // 体力降为0，强行回家
                            VillagerStateRegistry.detailedStates[vId] = "MOVING"
                            if (cottage != null) {
                                esm.updateVillagerJob(vId, v.job, cottage.x, cottage.y)
                            }
                            eventPublisher?.invoke(GameEvent.VillagerReturningHome(vId, "LOW_ENERGY"))
                            continue
                        }

                        // 2. 效率与产出量叠算
                        val energyPenalty = if (v.energy <= 20) 0.5f else 1.0f
                        val job = VillagerStateRegistry.originalJobs[vId] ?: "LUMBERJACK"
                        val resourceName = if (job == "MINER") "STONE" else "WOOD"

                        val weatherYieldMultiplier = if (resourceName == "WOOD") {
                            weatherModifiers.loggingYieldMultiplier
                        } else if (resourceName == "STONE") {
                            weatherModifiers.miningYieldMultiplier
                        } else {
                            weatherModifiers.farmingYieldMultiplier
                        }

                        val baseYield = 1.0f
                        val toolBonus = v.equippedTools.keys.map { ActionConfig.toolEfficiencyBonus[it] ?: 0f }.sum()
                        val totalYieldFloat = baseYield * (1.0f + toolBonus) * weatherYieldMultiplier * policyModifier.harvestYieldMultiplier * energyPenalty

                        val guaranteedYield = totalYieldFloat.toInt()
                        val criticalChance = totalYieldFloat - guaranteedYield
                        val actualYield = guaranteedYield + if (Random.nextFloat() < criticalChance) 1 else 0

                        // 3. 工具扣减与损坏事件
                        val toolToDegrade = v.equippedTools.keys.firstOrNull { tool ->
                            if (job == "MINER") tool.contains("PICKAXE") else tool.contains("AXE")
                        } ?: v.equippedTools.keys.firstOrNull()

                        if (toolToDegrade != null) {
                            val currentDurability = v.equippedTools[toolToDegrade] ?: 0
                            val newDurability = currentDurability - 1
                            esm.updateToolDurability(vId, toolToDegrade, newDurability)
                            if (newDurability <= 0) {
                                eventPublisher?.invoke(GameEvent.VillagerToolBroken(vId, toolToDegrade))
                            }
                        }

                        // 4. 背包处理
                        val currentSum = v.backpack.values.sum()
                        val isFull = currentSum >= 10 || (currentSum + actualYield >= 100)
                        if (isFull) {
                            VillagerStateRegistry.detailedStates[vId] = "DELIVERING"
                            val warehouses = esm.getAllWarehouses()
                            val nearestWarehouse = warehouses.minByOrNull { w ->
                                val dx = w.coordinate.x - v.coordinate.x
                                val dy = w.coordinate.y - v.coordinate.y
                                dx * dx + dy * dy
                            }
                            if (nearestWarehouse != null) {
                                esm.updateVillagerJob(vId, v.job, nearestWarehouse.coordinate.x, nearestWarehouse.coordinate.y)
                            }
                        } else {
                            if (actualYield > 0) {
                                esm.updateVillagerBackpackItem(vId, resourceName, actualYield)
                                eventPublisher?.invoke(GameEvent.VillagerHarvested(vId, resourceName, actualYield))
                            }
                        }
                    }
                }
                "DELIVERING" -> {
                    entityStateManager?.let { esm ->
                        if (v != null) {
                            val warehouses = esm.getAllWarehouses()
                            val targetWarehouse = warehouses.minByOrNull { w ->
                                val dx = w.coordinate.x - v.coordinate.x
                                val dy = w.coordinate.y - v.coordinate.y
                                dx * dx + dy * dy
                            } ?: warehouses.firstOrNull()

                            if (targetWarehouse != null) {
                                val itemsToDeliver = v.backpack.keys.toList()
                                for (item in itemsToDeliver) {
                                    val amount = v.backpack[item] ?: 0
                                    if (amount > 0) {
                                        runBlocking {
                                            esm.transferItemVillagerToWarehouse(vId, targetWarehouse.id, item, amount)
                                        }
                                    }
                                }
                                eventPublisher?.invoke(GameEvent.VillagerDelivered(vId, targetWarehouse.id, v.backpack))
                            }
                        }
                    }
                    
                    if (timeOfDay == TimePeriod.NIGHT || timeOfDay == TimePeriod.TWILIGHT) {
                        VillagerStateRegistry.detailedStates[vId] = "MOVING"
                        if (cottage != null && v != null) {
                            entityStateManager?.updateVillagerJob(vId, v.job, cottage.x, cottage.y)
                        }
                    } else {
                        VillagerStateRegistry.detailedStates[vId] = "WORKING"
                    }
                }
                "EQUIPPING" -> {
                    VillagerStateRegistry.detailedStates[vId] = "WORKING"
                }
            }

            // 3. 霓虹高质感渲染状态夜晚自然剪裁同步 (SLEEPING / WORKING / IDLE status synchronizer)
            entityStateManager?.let { esm ->
                val vLatest = esm.getVillager(vId)
                if (vLatest != null) {
                    val isInsideHome = cottage != null && vLatest.coordinate.x == cottage.x && vLatest.coordinate.y == cottage.y
                    val currentDetailed = VillagerStateRegistry.detailedStates[vId] ?: "RESTING"

                    val newStatus = if (timeOfDay == TimePeriod.NIGHT && isInsideHome && currentDetailed == "RESTING") {
                        VillagerStatus.SLEEPING
                    } else if (currentDetailed == "WORKING") {
                        VillagerStatus.WORKING
                    } else {
                        VillagerStatus.IDLE
                    }
                    esm.updateVillagerStatus(vId, newStatus)
                }
            }
        }
    }

    private fun transitionAfterArrived(vId: Long, toX: Int, toY: Int, cottage: com.example.pythonrpg.engine.building.BuildingSnapshot?) {
        if (cottage != null && toX == cottage.x && toY == cottage.y) {
            VillagerStateRegistry.detailedStates[vId] = "RESTING"
        } else {
            val warehouses = entityStateManager?.getAllWarehouses() ?: emptyList()
            val isWarehouse = warehouses.any { it.coordinate.x == toX && it.coordinate.y == toY }
            if (isWarehouse) {
                val equipTool = VillagerStateRegistry.equipToolTargets[vId]
                if (equipTool != null) {
                    VillagerStateRegistry.detailedStates[vId] = "EQUIPPING"
                } else {
                    VillagerStateRegistry.detailedStates[vId] = "DELIVERING"
                }
            } else {
                VillagerStateRegistry.detailedStates[vId] = "WORKING"
            }
        }
    }
}
