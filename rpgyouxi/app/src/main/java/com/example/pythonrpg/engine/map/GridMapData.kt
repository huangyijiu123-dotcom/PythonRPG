package com.example.pythonrpg.engine.map

import com.example.pythonrpg.shared.Coordinate
import com.example.pythonrpg.shared.ExploreStatus
import com.example.pythonrpg.shared.TileData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GridMapData - 空间网格数据库，负责存储地块数据、迷雾解锁、区域查询、并发写入控制与序列化
 */
class GridMapData {

    // 核心底层存储：Key使用压缩坐标，防止频繁生成 Coordinate 导致 GC 压力
    private val tileMap = ConcurrentHashMap<Long, TileData>()

    // 脏地块标记队列与广播流
    private val dirtyQueue = ConcurrentLinkedQueue<Coordinate>()
    private val _dirtyTilesFlow = MutableSharedFlow<List<Coordinate>>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val dirtyTilesFlow: SharedFlow<List<Coordinate>> = _dirtyTilesFlow.asSharedFlow()

    // 事务互斥锁
    private val batchMutex = Mutex()

    private val gson = Gson()

    /**
     * 高性能坐标压缩：将 2D 坐标打包为单一 Long 值
     */
    private fun packToLong(x: Int, y: Int): Long {
        return (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    }

    /**
     * 设置/写入地块数据
     */
    fun setTile(tile: TileData) {
        val key = packToLong(tile.coordinate.x, tile.coordinate.y)
        tileMap[key] = tile
        dirtyQueue.add(tile.coordinate)
    }

    /**
     * 获取指定地块
     */
    fun getTile(x: Int, y: Int): TileData? {
        val key = packToLong(x, y)
        return tileMap[key]
    }

    /**
     * 网格大小
     */
    fun size(): Int = tileMap.size

    /**
     * 清空数据
     */
    fun clear() {
        tileMap.clear()
        dirtyQueue.clear()
    }

    /**
     * 原子更新探索状态
     */
    fun updateExploreStatus(coordinate: Coordinate, status: ExploreStatus): Boolean {
        val key = packToLong(coordinate.x, coordinate.y)
        var updated = false
        tileMap.computeIfPresent(key) { _, current ->
            if (current.exploreStatus != status) {
                updated = true
                dirtyQueue.add(coordinate)
                current.copy(exploreStatus = status)
            } else {
                current
            }
        }
        return updated
    }

    /**
     * 原子更新怪物存在性
     */
    fun updateMonsterPresence(coordinate: Coordinate, hasMonster: Boolean): Boolean {
        val key = packToLong(coordinate.x, coordinate.y)
        var updated = false
        tileMap.computeIfPresent(key) { _, current ->
            if (current.hasMonster != hasMonster) {
                updated = true
                dirtyQueue.add(coordinate)
                current.copy(hasMonster = hasMonster)
            } else {
                current
            }
        }
        return updated
    }

    /**
     * 原子更新建筑ID
     */
    fun updateBuilding(coordinate: Coordinate, buildingId: Long?): Boolean {
        val key = packToLong(coordinate.x, coordinate.y)
        var updated = false
        tileMap.computeIfPresent(key) { _, current ->
            if (current.buildingId != buildingId) {
                updated = true
                dirtyQueue.add(coordinate)
                current.copy(buildingId = buildingId)
            } else {
                current
            }
        }
        return updated
    }

    /**
     * 解锁地块（十字级联迷雾解锁）
     */
    fun unlockTile(coordinate: Coordinate): List<Coordinate> {
        val targetKey = packToLong(coordinate.x, coordinate.y)
        val targetTile = tileMap[targetKey] ?: return emptyList()

        if (targetTile.exploreStatus != ExploreStatus.VISIBLE_UNEXPLORED) {
            return emptyList()
        }

        val changedTiles = mutableListOf<Coordinate>()

        // 1. 将目标本身解锁为 EXPLORED
        tileMap.computeIfPresent(targetKey) { _, current ->
            changedTiles.add(coordinate)
            dirtyQueue.add(coordinate)
            current.copy(exploreStatus = ExploreStatus.EXPLORED)
        }

        // 2. 遍历十字邻居（上下左右）
        val neighbors = listOf(
            Coordinate(coordinate.x, coordinate.y - 1), // 上
            Coordinate(coordinate.x, coordinate.y + 1), // 下
            Coordinate(coordinate.x - 1, coordinate.y), // 左
            Coordinate(coordinate.x + 1, coordinate.y)  // 右
        )

        for (neighbor in neighbors) {
            val nKey = packToLong(neighbor.x, neighbor.y)
            tileMap.computeIfPresent(nKey) { _, current ->
                if (current.exploreStatus == ExploreStatus.UNEXPLORED) {
                    changedTiles.add(neighbor)
                    dirtyQueue.add(neighbor)
                    current.copy(exploreStatus = ExploreStatus.VISIBLE_UNEXPLORED)
                } else {
                    current
                }
            }
        }

        // 返回所有被更新为 VISIBLE_UNEXPLORED 的邻居地块坐标（排除了中心点自身）
        return changedTiles.filter { it != coordinate }
    }

    /**
     * 返回矩形区域内的地块副本（闭区间）
     */
    fun getTilesInRegion(minX: Int, maxX: Int, minY: Int, maxY: Int): List<TileData> {
        val result = mutableListOf<TileData>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                getTile(x, y)?.let { result.add(it) }
            }
        }
        return result
    }

    /**
     * 过滤地块（防御性拷贝）
     */
    fun filterTiles(predicate: (TileData) -> Boolean): List<TileData> {
        return tileMap.values.filter(predicate)
    }

    /**
     * 批量事务更新（互斥锁）
     */
    suspend fun batchUpdateTiles(tiles: List<TileData>) {
        batchMutex.withLock {
            for (tile in tiles) {
                setTile(tile)
            }
        }
    }

    /**
     * 非阻塞原子取出脏队列中积压的所有坐标并进行流发射
     */
    fun emitDirtyTiles() {
        val list = mutableListOf<Coordinate>()
        while (true) {
            val coord = dirtyQueue.poll() ?: break
            list.add(coord)
        }
        if (list.isNotEmpty()) {
            _dirtyTilesFlow.tryEmit(list.distinct())
        }
    }

    /**
     * 导出为 JSON 字符串 (使用 Gson)
     */
    fun exportToJson(): String {
        val tilesList = tileMap.values.toList()
        return gson.toJson(tilesList)
    }

    /**
     * 从 JSON 还原网格数据
     */
    fun importFromJson(json: String) {
        clear()
        val type = object : TypeToken<List<TileData>>() {}.type
        val tilesList: List<TileData> = gson.fromJson(json, type) ?: return
        for (tile in tilesList) {
            val key = packToLong(tile.coordinate.x, tile.coordinate.y)
            tileMap[key] = tile
        }
    }
}
