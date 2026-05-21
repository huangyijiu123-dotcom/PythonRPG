package com.example.pythonrpg.engine.building

/**
 * BuildingType - 十六大核心建筑类型
 */
public enum class BuildingType {
    ROYAL_CASTLE,   // 🏰 大本营王城
    COTTAGE,        // 🏠 村民民房
    LUMBER_CAMP,    // 🪓 森林伐木场
    QUARRY,         // ⛏️ 采石场
    MINE,           // ⛏️ 采矿场
    FARM,           // 🌾 农产农田
    ALCHEMY_LAB,    // 🧪 炼金工坊
    LOCAL_STORAGE,  // 📦 地方物资仓库
    DISTRIBUTION,   // 🏛️ 物流分拨中心
    CARAVAN_POST,   // 🏪 商队驿站
    BLACKSMITH,     // 🔨 装备铁匠铺
    TAVERN,         // 🍺 英雄酒馆
    ACADEMY,        // 🏛️ 科学院
    WORKSHOP,       // 🏭 生产工房
    ICE_CELLAR,     // 🧊 冰窖深冷仓库
    FURNACE         // 🔥 高温熔炉
}

/**
 * BuildingState - 四大核心运行状态
 */
public enum class BuildingState {
    UNDER_CONSTRUCTION,  // 建造中
    ACTIVE,              // 正常运作
    UPGRADING,           // 升级中
    DESTROYED            // 毁坏残骸
}

/**
 * BuildingTypeConfig - 静态建筑配置规则
 */
public data class BuildingTypeConfig(
    val allowedTerrains: Set<String>,      // 允许放置的合法地形集合
    val woodCost: Int,                     // 基础建造木材消耗
    val stoneCost: Int,                    // 基础建造石材消耗
    val maxLevel: Int,                     // 最大等级上限
    val baseConstructionTicks: Int         // 基础建造所需时长 Tick
)

/**
 * BuildingSnapshot - 建筑状态实时数据快照
 */
public data class BuildingSnapshot(
    val buildingId: Long,
    val type: BuildingType,
    val x: Int,
    val y: Int,
    var level: Int,
    var state: BuildingState,
    var constructionProgress: Int,         // 当前进度
    var maxConstructionProgress: Int,      // 达标所需总进度 Tick
    val customData: MutableMap<String, Int> = mutableMapOf() // 存储特定加成数据
)

/**
 * BuildingDependencyProvider - 建筑外部依赖回调接口
 */
public interface BuildingDependencyProvider {
    /**
     * 仓库/分拨中心/大本营升级到 ACTIVE 时，反向扩容底层的背包上限
     */
    public fun notifyWarehouseCapacityExpanded(warehouseId: Long, additionalCapacity: Int)
    
    /**
     * 查询指定坐标格子的地形 ID，供地形拦截器判别放置合法性
     */
    public fun getTerrainType(x: Int, y: Int): String
}
