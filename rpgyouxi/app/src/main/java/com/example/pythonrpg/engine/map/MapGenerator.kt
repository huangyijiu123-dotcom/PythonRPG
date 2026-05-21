package com.example.pythonrpg.engine.map

import com.example.pythonrpg.shared.Coordinate

/**
 * 延迟生成完成的地块快照
 */
public data class GeneratedTile(
    val coordinate: Coordinate,
    val terrainTypeId: String,           // 地形 ID，如 "PLAINS" | "FOREST" | "VOLCANO"
    val initialExploreStatus: String,    // "UNEXPLORED" | "VISIBLE_UNEXPLORED" | "EXPLORED"
    val monsterTypeId: String?,          // null 表示无怪物
    val monsterLevel: Int?,              // 怪物等级
    val isBossLocation: Boolean,         // 是否生成 Boss 城堡
    val bossTypeId: String?,             // Boss 种类 ID
    val cityStateTypeId: String?         // 城邦种类 ID，如 "FARMING" | "MINING" | "TRADING"
)

/**
 * 大本营固定开局坐标地块定义
 */
public data class TemplateTile(
    val terrainTypeId: String,
    val initialExploreStatus: String,
    val monsterTypeId: String?,
    val monsterLevel: Int?
)

/**
 * 欧氏拓扑圈层枚举
 */
public enum class MapZone {
    BASE_CAMP,     // 大本营中心 d = 0
    STARTER_ZONE,  // 新手区 0 < d <= 5
    MID_ZONE,      // 中级环形带 5 < d <= 12
    HIGH_ZONE,     // 高级环形带 12 < d <= 20
    ABYSS_ZONE     // 深渊区 d > 20
}

/**
 * 存放野怪生成结果
 */
public data class GeneratedMonster(
    val monsterTypeId: String,
    val level: Int
)

/**
 * 伪随机确定性地图生成器，基于数学哈希按需延时加载无限地图瓦片
 */
public class MapGenerator(public val seed: Long) {

    /**
     * 高强度确定性伪随机数论哈希函数
     */
    public fun hash(seed: Long, x: Int, y: Int): Long {
        var h = seed xor (x.toLong() * 3421959L) xor (y.toLong() * 9283711L)
        h = (h xor (h ushr 32)) * 0xd6e8feb86659fd93uL.toLong()
        h = (h xor (h ushr 32)) * 0xd6e8feb86659fd93uL.toLong()
        h = h xor (h ushr 32)
        val absVal = Math.abs(h)
        return if (absVal < 0) 0L else absVal
    }

    /**
     * 计算欧氏几何拓扑距离
     */
    public fun getDistance(x: Int, y: Int): Double {
        return Math.sqrt((x.toLong() * x + y.toLong() * y).toDouble())
    }

    public fun getDistance(coordinate: Coordinate): Double {
        return getDistance(coordinate.x, coordinate.y)
    }

    /**
     * 判定欧氏拓扑圈层映射
     */
    public fun getZone(coordinate: Coordinate): MapZone {
        val d = getDistance(coordinate)
        return when {
            d == 0.0 -> MapZone.BASE_CAMP
            d <= 5.0 -> MapZone.STARTER_ZONE
            d <= 12.0 -> MapZone.MID_ZONE
            d <= 20.0 -> MapZone.HIGH_ZONE
            else -> MapZone.ABYSS_ZONE
        }
    }

    /**
     * 获取大本营 5x5 开局黄金区固定硬编码地块模板
     */
    public fun getCampTemplate(coordinate: Coordinate): TemplateTile? {
        return when (coordinate) {
            Coordinate(0, 0) -> TemplateTile("BASE_CAMP", "EXPLORED", null, null)
            Coordinate(0, 1) -> TemplateTile("PLAINS", "EXPLORED", null, null)
            Coordinate(1, 0) -> TemplateTile("PLAINS", "VISIBLE_UNEXPLORED", "SLIME", 1)
            Coordinate(-1, 0) -> TemplateTile("FOREST", "VISIBLE_UNEXPLORED", "SLIME", 1)
            Coordinate(0, -1) -> TemplateTile("PLAINS", "VISIBLE_UNEXPLORED", "SLIME", 1)
            Coordinate(-1, 2) -> TemplateTile("FOREST", "UNEXPLORED", "SLIME", 1)
            Coordinate(1, 2) -> TemplateTile("PLAINS", "UNEXPLORED", null, null)
            Coordinate(-2, 0) -> TemplateTile("MOUNTAIN", "UNEXPLORED", "GOBLIN", 1)
            Coordinate(2, 0) -> TemplateTile("MOUNTAIN", "UNEXPLORED", "GOBLIN", 1)
            Coordinate(1, -1) -> TemplateTile("FOREST", "UNEXPLORED", "GOBLIN", 1)
            Coordinate(-1, -1) -> TemplateTile("FOREST", "UNEXPLORED", "GOBLIN", 1)
            else -> null
        }
    }

    /**
     * 根据圈层权重，伪随机分配地形
     */
    public fun generateTerrainType(seed: Long, x: Int, y: Int, zone: MapZone): String {
        val w = (hash(seed, x, y) % 100).toInt()
        return when (zone) {
            MapZone.BASE_CAMP -> "BASE_CAMP"
            MapZone.STARTER_ZONE -> when {
                w < 50 -> "PLAINS"
                w < 90 -> "FOREST"
                else -> "MOUNTAIN"
            }
            MapZone.MID_ZONE -> when {
                w < 30 -> "PLAINS"
                w < 60 -> "FOREST"
                w < 80 -> "MOUNTAIN"
                w < 90 -> "SWAMP"
                else -> "RUINS"
            }
            MapZone.HIGH_ZONE -> when {
                w < 20 -> "FOREST"
                w < 45 -> "MOUNTAIN"
                w < 60 -> "SWAMP"
                w < 70 -> "RUINS"
                w < 85 -> "VOLCANO"
                else -> "TUNDRA"
            }
            MapZone.ABYSS_ZONE -> when {
                w < 20 -> "MOUNTAIN"
                w < 30 -> "SWAMP"
                w < 45 -> "RUINS"
                w < 70 -> "VOLCANO"
                else -> "TUNDRA"
            }
        }
    }

    /**
     * 空间距离等级公式与野怪确定性产出算法
     */
    public fun generateMonster(seed: Long, x: Int, y: Int, terrainTypeId: String, zone: MapZone): GeneratedMonster? {
        if (terrainTypeId == "BASE_CAMP" || terrainTypeId == "CITY_STATE") {
            return null
        }

        // 刷怪概率阈值核算
        val spawnRoll = hash(seed + 2L, x, y) % 100
        val threshold = when (zone) {
            MapZone.STARTER_ZONE -> 60
            MapZone.MID_ZONE -> 70
            MapZone.HIGH_ZONE -> 80
            MapZone.ABYSS_ZONE -> 90
            MapZone.BASE_CAMP -> 0 // 大本营绝对无怪
        }

        if (spawnRoll >= threshold) {
            return null
        }

        // 根据地形与圈层矩阵，决定野怪种类
        val monsterType = when (terrainTypeId) {
            "PLAINS" -> when (zone) {
                MapZone.STARTER_ZONE -> "SLIME"
                MapZone.MID_ZONE -> "GOBLIN"
                MapZone.HIGH_ZONE -> "BANDIT"
                MapZone.ABYSS_ZONE -> "DARK_KNIGHT"
                else -> "SLIME"
            }
            "FOREST" -> when (zone) {
                MapZone.STARTER_ZONE -> "GOBLIN"
                MapZone.MID_ZONE -> "WOLF"
                MapZone.HIGH_ZONE -> "WOLF"
                MapZone.ABYSS_ZONE -> "DARK_KNIGHT"
                else -> "GOBLIN"
            }
            "MOUNTAIN" -> when (zone) {
                MapZone.STARTER_ZONE -> "GOBLIN"
                MapZone.MID_ZONE -> "WOLF"
                MapZone.HIGH_ZONE -> "SKELETON"
                MapZone.ABYSS_ZONE -> "DARK_KNIGHT"
                else -> "GOBLIN"
            }
            "SWAMP" -> when (zone) {
                MapZone.STARTER_ZONE -> "SLIME"
                MapZone.MID_ZONE -> "SKELETON"
                MapZone.HIGH_ZONE -> "SKELETON"
                MapZone.ABYSS_ZONE -> "DARK_KNIGHT"
                else -> "SLIME"
            }
            "RUINS" -> when (zone) {
                MapZone.STARTER_ZONE -> "SKELETON"
                MapZone.MID_ZONE -> "SKELETON"
                MapZone.HIGH_ZONE -> "BANDIT"
                MapZone.ABYSS_ZONE -> "DARK_KNIGHT"
                else -> "SKELETON"
            }
            "VOLCANO" -> if (zone == MapZone.HIGH_ZONE || zone == MapZone.ABYSS_ZONE) "LAVA_GIANT" else null
            "TUNDRA" -> if (zone == MapZone.HIGH_ZONE || zone == MapZone.ABYSS_ZONE) "FROST_WITCH" else null
            else -> null
        } ?: return null

        // 空间距离等级公式
        val baseLevel = when (zone) {
            MapZone.STARTER_ZONE -> 1
            MapZone.MID_ZONE -> 3
            MapZone.HIGH_ZONE -> 5
            MapZone.ABYSS_ZONE -> 7
            else -> 1
        }
        val distance = getDistance(x, y)
        val lDist = Math.floor(distance / 5.0).toInt()
        val offsetRoll = hash(seed + 3L, x, y) % 3
        val lOffset = (offsetRoll - 1).toInt() // -1, 0, 1

        val finalLevel = (baseLevel + lDist + lOffset).coerceAtLeast(1)

        return GeneratedMonster(monsterType, finalLevel)
    }

    /**
     * 判断是否属于特定环带的 Boss 候选点
     */
    public fun isBossCandidate(coordinate: Coordinate): Boolean {
        val d = getDistance(coordinate)
        if (d < 9.5) return false
        if (Math.floor(d).toInt() % 10 != 0) return false
        return (hash(seed + 10L, coordinate.x, coordinate.y) % 100).toInt() < 20
    }

    /**
     * 根据欧氏几何距离，判定特定环带 Boss 城堡内的 Boss 种类
     */
    public fun getBossType(distance: Double): String {
        return when {
            distance in 9.5..10.5 -> "BOSS_KNIGHT"
            distance in 19.5..20.5 -> "BOSS_LAVA_GIANT"
            distance >= 29.5 -> "BOSS_FROST_WITCH"
            else -> "BOSS_KNIGHT" // fallback
        }
    }

    /**
     * 判定是否符合贸易城邦生成权
     */
    public fun isCityStateCandidate(coordinate: Coordinate): Boolean {
        val d = getDistance(coordinate)
        if (d < 6.0) return false

        val roll = (hash(seed + 4L, coordinate.x, coordinate.y) % 100).toInt()
        val threshold = if (d <= 15.0) 8 else 5
        return roll < threshold
    }

    /**
     * 根据数论哈希，决定城邦分类偏好
     */
    public fun getCityStateType(coordinate: Coordinate): String {
        val typeRoll = (hash(seed + 5L, coordinate.x, coordinate.y) % 3).toInt()
        return when (typeRoll) {
            0 -> "FARMING"
            1 -> "MINING"
            else -> "TRADING"
        }
    }

    /**
     * 稀疏去重碰撞检测：5.0 距离物理邻近内是否已经存在城邦
     */
    public fun checkCityStateSparsity(coordinate: Coordinate, knownCityCoordinates: Set<Coordinate>): Boolean {
        for (known in knownCityCoordinates) {
            val dx = (coordinate.x - known.x).toDouble()
            val dy = (coordinate.y - known.y).toDouble()
            val dist = Math.sqrt(dx * dx + dy * dy)
            if (dist <= 5.0) {
                return true // 太拥挤，熔断拦截
            }
        }
        return false // 安全放行
    }

    /**
     * 拼装主瓦片延迟生成动作，应用互斥排他优先级链条
     */
    public fun generateTile(coordinate: Coordinate, knownCityCoordinates: Set<Coordinate> = emptySet()): GeneratedTile {
        // 优先级 A：大本营固定模板覆盖
        val template = getCampTemplate(coordinate)
        if (template != null) {
            return GeneratedTile(
                coordinate = coordinate,
                terrainTypeId = template.terrainTypeId,
                initialExploreStatus = template.initialExploreStatus,
                monsterTypeId = template.monsterTypeId,
                monsterLevel = template.monsterLevel,
                isBossLocation = false,
                bossTypeId = null,
                cityStateTypeId = null
            )
        }

        // 优先级 B：特定环带 Boss 城堡
        val zone = getZone(coordinate)
        if (isBossCandidate(coordinate)) {
            val terrain = generateTerrainType(seed, coordinate.x, coordinate.y, zone)
            val distance = getDistance(coordinate)
            return GeneratedTile(
                coordinate = coordinate,
                terrainTypeId = terrain,
                initialExploreStatus = "UNEXPLORED",
                monsterTypeId = null, // Boss 核心要塞绝不生成普通野怪
                monsterLevel = null,
                isBossLocation = true,
                bossTypeId = getBossType(distance),
                cityStateTypeId = null
            )
        }

        // 优先级 C：贸易城邦稀疏装配
        if (isCityStateCandidate(coordinate) && !checkCityStateSparsity(coordinate, knownCityCoordinates)) {
            return GeneratedTile(
                coordinate = coordinate,
                terrainTypeId = "CITY_STATE", // 地貌变更为 CITY_STATE
                initialExploreStatus = "UNEXPLORED",
                monsterTypeId = null,
                monsterLevel = null,
                isBossLocation = false,
                bossTypeId = null,
                cityStateTypeId = getCityStateType(coordinate)
            )
        }

        // 优先级 D：普通旷野结算
        val terrain = generateTerrainType(seed, coordinate.x, coordinate.y, zone)
        val monster = generateMonster(seed, coordinate.x, coordinate.y, terrain, zone)

        return GeneratedTile(
            coordinate = coordinate,
            terrainTypeId = terrain,
            initialExploreStatus = "UNEXPLORED",
            monsterTypeId = monster?.monsterTypeId,
            monsterLevel = monster?.level,
            isBossLocation = false,
            bossTypeId = null,
            cityStateTypeId = null
        )
    }

    /**
     * 批量按需滑动加载指定矩形视口视窗内的数据
     */
    public fun generateRegion(
        topLeft: Coordinate,
        bottomRight: Coordinate,
        knownCityCoordinates: Set<Coordinate> = emptySet()
    ): List<GeneratedTile> {
        val result = mutableListOf<GeneratedTile>()
        for (x in topLeft.x..bottomRight.x) {
            for (y in topLeft.y..bottomRight.y) {
                result.add(generateTile(Coordinate(x, y), knownCityCoordinates))
            }
        }
        return result
    }
}
