package com.example.pythonrpg.shared

// ── 坐标 ──────────────────────────────────────────────────────
data class Coordinate(val x: Int, val y: Int)

// ── 时间段 & 心跳事件 ──────────────────────────────────────────
enum class TimePeriod { MORNING, DAYTIME, TWILIGHT, NIGHT }

data class TickEvent(
    val tickId: Long,
    val timestamp: Long,
    val timeOfDay: TimePeriod
)

// ── 天气修正（8字段完整版）────────────────────────────────────
data class WeatherModifiers(
    val moveSpeedMultiplier: Float = 1.0f,
    val outdoorYieldMultiplier: Float = 1.0f,
    val loggingYieldMultiplier: Float = 1.0f,
    val farmingYieldMultiplier: Float = 1.0f,
    val miningYieldMultiplier: Float = 1.0f,
    val energyCostMultiplier: Float = 1.0f,
    val foodConsumptionMultiplier: Float = 1.0f,
    val fogVisionReduction: Int = 0
)

// ── 法令修正（11字段完整版）──────────────────────────────────
data class PolicyModifiers(
    val foodConsumptionMultiplier: Float = 1.0f,
    val energyRestoreMultiplier: Float = 1.0f,
    val combatAttackMultiplier: Float = 1.0f,
    val harvestYieldMultiplier: Float = 1.0f,
    val caravanSpeedMultiplier: Float = 1.0f,
    val tradeProfitMultiplier: Float = 1.0f,
    val energyCostMultiplier: Float = 1.0f,
    val upgradeCostMultiplier: Float = 1.0f,
    val techCostMultiplier: Float = 1.0f,
    val buildingDefenseMultiplier: Float = 1.0f,
    val moveSpeedMultiplier: Float = 1.0f
)

// ── 装备（统一版，解决 id/equipmentId & enum/String 冲突）────
enum class EquipmentClass { WEAPON, ARMOR }

data class EquipmentSnapshot(
    val id: Long,
    val templateId: String,
    val equipmentClass: EquipmentClass,
    val level: Int,
    val durability: Int,
    val maxDurability: Int,
    val baseAttack: Int,
    val baseDefense: Int,
    val baseStat: Int,
    var currentStat: Int,
    val ownerId: Long?
)

// ── 实体状态数据库相关模型 ─────────────────────────────────────
enum class VillagerStatus { IDLE, WORKING, SLEEPING }
enum class AdventurerStatus { IDLE, ADVENTURING, COMBAT, RESTING, FIGHTING }
enum class CaravanStatus { IDLE, TRAVELING, TRADING, RAIDED, TRANSPORTING }

data class VillagerSnapshot(
    val id: Long,
    val name: String,
    val coordinate: Coordinate,
    val status: VillagerStatus,
    val job: String,                // 职业，如 "LUMBERJACK"、"MINER"
    val targetX: Int?,
    val targetY: Int?,
    val isInjured: Boolean,
    val energy: Int,                // 0..100
    val backpack: Map<String, Int>, // 物品ID -> 数量
    val equippedTools: Map<String, Int> // 工具ID -> 耐久度
)

data class AdventurerSnapshot(
    val id: Long,
    val name: String,
    val coordinate: Coordinate,
    val status: AdventurerStatus,
    val hp: Int,                    // 0..maxHp
    val maxHp: Int,
    val mp: Int,                    // 0..100
    val fatigue: Int,               // 0..100
    val weaponEquipmentId: Long?,
    val armorEquipmentId: Long?
)

data class CaravanSnapshot(
    val id: Long,
    val name: String,
    val coordinate: Coordinate,
    val status: CaravanStatus,
    val targetX: Int?,
    val targetY: Int?,
    val capacity: Int,
    val cargo: Map<String, Int>
)

data class WarehouseSnapshot(
    val id: Long,
    val coordinate: Coordinate,
    val capacity: Int,
    val inventory: Map<String, Int>
)

data class StateDiff(
    val villagers: List<Long>,
    val adventurers: List<Long>,
    val caravans: List<Long>,
    val warehouses: List<Long>,
    val equipments: List<Long>,
    val goldChanged: Boolean
)

// ── 玩家指令 ───────────────────────────────────────────────────
sealed class PlayerCommand {
    data class AssignJob(val villagerId: Long, val job: String, val targetX: Int, val targetY: Int) : PlayerCommand()
    data class ReturnHome(val villagerId: Long) : PlayerCommand()
    data class EquipTool(val villagerId: Long, val toolId: String, val warehouseId: Long) : PlayerCommand()
    data class DispatchAdventurer(val adventurerId: Long, val targetX: Int, val targetY: Int) : PlayerCommand()
    data class RecallAdventurer(val adventurerId: Long) : PlayerCommand()
    data class AssignCaravanTarget(val caravanId: Long, val targetX: Int, val targetY: Int) : PlayerCommand()
    data class StartCaravan(val caravanId: Long) : PlayerCommand()
    data class RecallCaravan(val caravanId: Long) : PlayerCommand()
    data class TradeWithCityState(val caravanId: Long, val cityId: Long, val isBuy: Boolean, val item: String, val amount: Int) : PlayerCommand()
    data class BuildBuilding(val x: Int, val y: Int, val buildingType: String) : PlayerCommand()
    data class UpgradeBuilding(val x: Int, val y: Int) : PlayerCommand()
    object RecruitVillager : PlayerCommand()
    object RecruitAdventurer : PlayerCommand()
    data class ResolveEvent(val eventId: Long) : PlayerCommand()
    data class QueueProduction(val workshopId: Long, val toolType: String, val count: Int) : PlayerCommand()
    data class ForgeEquipment(val templateId: String) : PlayerCommand()
    data class UpgradeEquipment(val equipmentId: Long) : PlayerCommand()
    data class RepairEquipment(val equipmentId: Long) : PlayerCommand()
    data class DismantleEquipment(val equipmentId: Long) : PlayerCommand()
    data class RepairAllEquipment(val adventurerId: Long) : PlayerCommand()
    data class StartResearch(val techId: String) : PlayerCommand()
    data class EnactPolicy(val policyType: String, val isActive: Boolean) : PlayerCommand()
}

// ── 游戏事件 ───────────────────────────────────────────────────
sealed class GameEvent {
    data class VillagerMoved(val villagerId: Long, val toX: Int, val toY: Int) : GameEvent()
    data class VillagerHarvested(val villagerId: Long, val item: String, val amount: Int) : GameEvent()
    data class VillagerDelivered(val villagerId: Long, val warehouseId: Long, val items: Map<String, Int>) : GameEvent()
    data class VillagerToolBroken(val villagerId: Long, val toolId: String) : GameEvent()
    data class VillagerLowEnergy(val villagerId: Long, val energy: Int) : GameEvent()
    data class VillagerReturningHome(val villagerId: Long, val reason: String) : GameEvent()
    data class AdventurerMoved(val adventurerId: Long, val toX: Int, val toY: Int) : GameEvent()
    data class AdventurerReachedTarget(val adventurerId: Long, val x: Int, val y: Int) : GameEvent()
    data class CombatTriggered(val adventurerId: Long, val monsterTypeId: String, val atX: Int, val atY: Int) : GameEvent()
    data class CaravanMoved(val caravanId: Long, val toX: Int, val toY: Int) : GameEvent()
    data class CaravanTradeResult(val caravanId: Long, val cityId: Long, val isBuy: Boolean, val item: String, val amount: Int, val goldDelta: Int) : GameEvent()
    data class WarehouseFull(val warehouseId: Long, val fillRatio: Float) : GameEvent()
    data class TickProcessed(val tickId: Long, val eventCount: Int) : GameEvent()
}

// ── 地图探索状态与地块数据 ──────────────────────────────────────
enum class ExploreStatus {
    UNEXPLORED,          // 完全未知，不可交互
    VISIBLE_UNEXPLORED,  // 地形可见，但未解锁
    EXPLORED             // 已完全解锁，可建造/派遣
}

data class TileData(
    val coordinate: Coordinate,
    val terrainTypeId: String,              // 地形标志，如 "FOREST"、"PLAINS"、"MOUNTAIN"
    val exploreStatus: ExploreStatus,       // 探索状态
    val isBossLocked: Boolean,              // 是否被 Boss 迷雾锁定
    val hasMonster: Boolean,                // 是否有怪物
    val buildingId: Long?,                  // 建筑 ID（null 表示无建筑）
    val customAttributes: Map<String, Any> = emptyMap() // 预留扩展字典
)
