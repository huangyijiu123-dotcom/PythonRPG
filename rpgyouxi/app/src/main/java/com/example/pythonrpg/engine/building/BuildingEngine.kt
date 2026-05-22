package com.example.pythonrpg.engine.building

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * BuildingEngine - 建筑引擎，管理建筑的生命周期、建造、升级、战毁及曼哈顿辐射物流网络
 */
public class BuildingEngine(
    private var dependencyProvider: BuildingDependencyProvider? = null
) {
    // 唯一自增 ID 发生器
    private val buildingIdGenerator = AtomicLong(1L)

    // 并发安全的建筑物底仓
    private val buildings = ConcurrentHashMap<Long, BuildingSnapshot>()

    // 十六大建筑的只读静态配置表
    public val buildingConfigs: Map<BuildingType, BuildingTypeConfig> = mapOf(
        BuildingType.ROYAL_CASTLE to BuildingTypeConfig(
            allowedTerrains = setOf("PLAINS"),
            woodCost = 0,
            stoneCost = 0,
            maxLevel = 1,
            baseConstructionTicks = 10
        ),
        BuildingType.COTTAGE to BuildingTypeConfig(
            allowedTerrains = setOf("PLAINS"),
            woodCost = 30,
            stoneCost = 0,
            maxLevel = 5,
            baseConstructionTicks = 3
        ),
        BuildingType.LUMBER_CAMP to BuildingTypeConfig(
            allowedTerrains = setOf("FOREST"),
            woodCost = 50,
            stoneCost = 0,
            maxLevel = 5,
            baseConstructionTicks = 4
        ),
        BuildingType.QUARRY to BuildingTypeConfig(
            allowedTerrains = setOf("MOUNTAIN"),
            woodCost = 30,
            stoneCost = 20,
            maxLevel = 5,
            baseConstructionTicks = 4
        ),
        BuildingType.MINE to BuildingTypeConfig(
            allowedTerrains = setOf("MOUNTAIN"),
            woodCost = 30,
            stoneCost = 30,
            maxLevel = 5,
            baseConstructionTicks = 4
        ),
        BuildingType.FARM to BuildingTypeConfig(
            allowedTerrains = setOf("PLAINS"),
            woodCost = 20,
            stoneCost = 0,
            maxLevel = 5,
            baseConstructionTicks = 4
        ),
        BuildingType.ALCHEMY_LAB to BuildingTypeConfig(
            allowedTerrains = setOf("SWAMP"),
            woodCost = 40,
            stoneCost = 30,
            maxLevel = 5,
            baseConstructionTicks = 10
        ),
        BuildingType.LOCAL_STORAGE to BuildingTypeConfig(
            allowedTerrains = setOf("PLAINS", "FOREST", "MOUNTAIN", "TUNDRA", "SWAMP", "VOLCANO"),
            woodCost = 0,
            stoneCost = 50,
            maxLevel = 5,
            baseConstructionTicks = 10
        ),
        BuildingType.DISTRIBUTION to BuildingTypeConfig(
            allowedTerrains = setOf("PLAINS", "FOREST", "MOUNTAIN", "TUNDRA", "SWAMP", "VOLCANO"),
            woodCost = 0,
            stoneCost = 200,
            maxLevel = 5,
            baseConstructionTicks = 10
        ),
        BuildingType.CARAVAN_POST to BuildingTypeConfig(
            allowedTerrains = setOf("PLAINS"),
            woodCost = 80,
            stoneCost = 40,
            maxLevel = 5,
            baseConstructionTicks = 10
        ),
        BuildingType.BLACKSMITH to BuildingTypeConfig(
            allowedTerrains = setOf("PLAINS"),
            woodCost = 50,
            stoneCost = 80,
            maxLevel = 5,
            baseConstructionTicks = 10
        ),
        BuildingType.TAVERN to BuildingTypeConfig(
            allowedTerrains = setOf("PLAINS"),
            woodCost = 60,
            stoneCost = 40,
            maxLevel = 5,
            baseConstructionTicks = 10
        ),
        BuildingType.ACADEMY to BuildingTypeConfig(
            allowedTerrains = setOf("PLAINS", "FOREST", "MOUNTAIN", "TUNDRA", "SWAMP", "VOLCANO"),
            woodCost = 100,
            stoneCost = 150,
            maxLevel = 5,
            baseConstructionTicks = 10
        ),
        BuildingType.WORKSHOP to BuildingTypeConfig(
            allowedTerrains = setOf("PLAINS", "FOREST", "MOUNTAIN", "TUNDRA", "SWAMP", "VOLCANO"),
            woodCost = 80,
            stoneCost = 100,
            maxLevel = 5,
            baseConstructionTicks = 10
        ),
        BuildingType.ICE_CELLAR to BuildingTypeConfig(
            allowedTerrains = setOf("TUNDRA"),
            woodCost = 0,
            stoneCost = 100,
            maxLevel = 3,
            baseConstructionTicks = 6
        ),
        BuildingType.FURNACE to BuildingTypeConfig(
            allowedTerrains = setOf("VOLCANO"),
            woodCost = 0,
            stoneCost = 150,
            maxLevel = 5,
            baseConstructionTicks = 10
        )
    )

    /**
     * 设置依赖提供者
     */
    public fun setDependencyProvider(provider: BuildingDependencyProvider) {
        this.dependencyProvider = provider
    }

    /**
     * 获取依赖提供者
     */
    public fun getDependencyProvider(): BuildingDependencyProvider? = dependencyProvider

    /**
     * 根据坐标寻找现有建筑
     */
    public fun getBuildingAt(x: Int, y: Int): BuildingSnapshot? {
        return buildings.values.firstOrNull { it.x == x && it.y == y }
    }

    /**
     * 根据类型筛选建筑列表
     */
    public fun getBuildingsByType(type: BuildingType): List<BuildingSnapshot> {
        return buildings.values.filter { it.type == type }
    }

    /**
     * 获取指定 ID 的建筑
     */
    public fun getBuilding(buildingId: Long): BuildingSnapshot? = buildings[buildingId]

    /**
     * 获取所有建筑快照的只读副本列表
     */
    public fun getAllBuildings(): List<BuildingSnapshot> = buildings.values.toList()

    /**
     * 手动注入建筑数据（主要用于测试框架及快速配置）
     */
    public fun registerBuilding(snap: BuildingSnapshot) {
        buildings[snap.buildingId] = snap
    }

    /**
     * 清空全部建筑（一般在重载存档时调用）
     */
    public fun clear() {
        buildings.clear()
        buildingIdGenerator.set(1L)
    }

    /**
     * 启动新建筑的建造
     *
     * @return 新建的建筑 ID。若因地形不符或已有重叠建筑拦截失败，则返回 null
     */
    public fun startConstruction(type: BuildingType, x: Int, y: Int): Long? {
        // 1. 重叠物理校验：如果该坐标存在非毁坏状态的建筑，则禁止重复建造
        val existing = getBuildingAt(x, y)
        if (existing != null && existing.state != BuildingState.DESTROYED) {
            return null
        }

        // 2. 地形匹配合法性校验
        val provider = dependencyProvider ?: return null
        val terrain = provider.getTerrainType(x, y)
        val config = buildingConfigs[type] ?: return null
        if (terrain !in config.allowedTerrains) {
            return null // 地形合法性硬拦截
        }

        // 3. 构建存盘快照
        val newId = buildingIdGenerator.getAndIncrement()
        val snapshot = BuildingSnapshot(
            buildingId = newId,
            type = type,
            x = x,
            y = y,
            level = 0, // 初始等级为 0，建造完工后晋升为 1 级
            state = BuildingState.UNDER_CONSTRUCTION,
            constructionProgress = 0,
            maxConstructionProgress = config.baseConstructionTicks
        )

        buildings[newId] = snapshot
        return newId
    }

    /**
     * 开始升级现有建筑
     *
     * @return 若未满级且状态合法允许升级返回 true，否则拦截返回 false
     */
    public fun startUpgrade(buildingId: Long): Boolean {
        val snap = buildings[buildingId] ?: return false

        // 1. 只有 ACTIVE 状态的正常运作建筑可以发起升级
        if (snap.state != BuildingState.ACTIVE) {
            return false
        }

        val config = buildingConfigs[snap.type] ?: return false

        // 2. 等级溢出拦截（最高满级上限校验）
        if (snap.level >= config.maxLevel) {
            return false
        }

        // 3. 重置并激活升级计时器
        // 升级所需 ticks 计算公式：基础 ticks * (当前等级 + 1)
        val upgradeTicks = config.baseConstructionTicks * (snap.level + 1)
        snap.state = BuildingState.UPGRADING
        snap.constructionProgress = 0
        snap.maxConstructionProgress = upgradeTicks
        return true
    }

    /**
     * 完成升级进度数据写入
     */
    public fun setUpgradeMetrics(buildingId: Long, progress: Int, maxProgress: Int, state: BuildingState) {
        val snap = buildings[buildingId] ?: return
        snap.state = state
        snap.constructionProgress = progress
        snap.maxConstructionProgress = maxProgress
    }

    /**
     * 时序进度自增推进
     * 每次调用推进 1 Tick 进度，消化处于建造或升级阶段的建筑，并在完工一瞬同步回调
     */
    public fun processTick() {
        val snapCopies = buildings.values.toList() // 避免 ConcurrentModificationException
        for (snap in snapCopies) {
            if (snap.state == BuildingState.ACTIVE || snap.state == BuildingState.DESTROYED) {
                continue
            }

            // 进度滚动
            snap.constructionProgress += 1

            // 完工跃迁判定
            if (snap.constructionProgress >= snap.maxConstructionProgress) {
                val oldState = snap.state
                snap.state = BuildingState.ACTIVE
                snap.constructionProgress = 0

                if (oldState == BuildingState.UNDER_CONSTRUCTION) {
                    snap.level = 1
                } else if (oldState == BuildingState.UPGRADING) {
                    snap.level += 1
                }

                // 仓库完工反向同步扩容通知回调
                if (snap.type == BuildingType.LOCAL_STORAGE ||
                    snap.type == BuildingType.DISTRIBUTION ||
                    snap.type == BuildingType.ROYAL_CASTLE
                ) {
                    val provider = dependencyProvider
                    if (provider != null) {
                        val additionalCapacity = 500 * snap.level
                        provider.notifyWarehouseCapacityExpanded(snap.buildingId, additionalCapacity)
                    }
                }
            }
        }
    }

    /**
     * 标记建筑物彻底被战损毁坏
     * 状态变更为 DESTROYED，保留残余残骸坐标不予物理抹除
     */
    public fun destroyBuilding(buildingId: Long) {
        val snap = buildings[buildingId] ?: return
        snap.state = BuildingState.DESTROYED
    }

    /**
     * 计算并汇总全图所有活跃物流分拨中心（DISTRIBUTION）的去重曼哈顿覆盖网络坐标集合
     *
     * 半径计算公式：radius = level * 3
     * 判定公式：|x1 - x2| + |y1 - y2| <= radius
     */
    public fun getDistributionCoverageRadius(): Set<Pair<Int, Int>> {
        val result = mutableSetOf<Pair<Int, Int>>()
        val activeDistributors = buildings.values.filter {
            it.type == BuildingType.DISTRIBUTION && it.state == BuildingState.ACTIVE
        }

        for (dist in activeDistributors) {
            val radius = dist.level * 3
            val cx = dist.x
            val cy = dist.y

            // 使用 Bounding Box 包络盒计算，最大限度节省 CPU 消耗
            for (x in (cx - radius)..(cx + radius)) {
                for (y in (cy - radius)..(cy + radius)) {
                    if (abs(cx - x) + abs(cy - y) <= radius) {
                        result.add(Pair(x, y))
                    }
                }
            }
        }
        return result
    }
}
