# AI Coding Prompt: MDU 02_GridMapData (地图格子数据库)

你是一个资深 Kotlin 游戏引擎专家。你的任务是实现 `GridMapData` 类及其单元测试。这是一个纯粹的内存型空间网格数据库，负责存储地块信息、处理探索迷雾解锁、区域查询、批量事务写入、以及使用 Gson 进行序列化存档。

---

## 1. 外部依赖统一模型
> ⚠️ 注意：以下类型必须直接使用我们统一在包 `com.example.pythonrpg.shared` 中的声明，不要重新声明它们：
```kotlin
package com.example.pythonrpg.shared

data class Coordinate(val x: Int, val y: Int)

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
    val customAttributes: Map<String, Any>  // 预留扩展字典（如资源储量等）
)
```

---

## 2. 核心类契约 & 内部存储

请声明 `class GridMapData` 并拥有以下属性与状态：
- **核心底层存储**：
  `private val tileMap = ConcurrentHashMap<Long, TileData>()`
  *(设计约束：Key 必须使用 Long 类型的压缩坐标，禁止直接使用 Coordinate 对象作为键，防止 GC 压力)*
- **高性能坐标压缩**：
  `private fun packToLong(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)`
- **脏坐标标记队列与广播流**：
  - `private val dirtyQueue = ConcurrentLinkedQueue<Coordinate>()`
  - `private val _dirtyTilesFlow = MutableSharedFlow<List<Coordinate>>(replay = 0, extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)`
  - `val dirtyTilesFlow: SharedFlow<List<Coordinate>> = _dirtyTilesFlow.asSharedFlow()`
- **事务互斥锁**：
  `private val batchMutex = Mutex()`

---

## 3. 详细方法行为约束

1. **基础读写**：
   - `setTile(tile: TileData)`: 压缩坐标存入 `tileMap`，并将坐标加入 `dirtyQueue`。
   - `getTile(x: Int, y: Int): TileData?`: 压缩坐标，查询并返回对应地块（不存在则返回 `null`）。
   - `size(): Int`: 直接返回 `tileMap.size`（非阻塞，不需要加锁）。
   - `clear()`: 清空 `tileMap` 与 `dirtyQueue`。

2. **局部属性原子更新（必须使用 `computeIfPresent` 保证槽位原子安全性，且更新成功后必须将坐标加入 `dirtyQueue`）**：
   - `updateExploreStatus(coordinate: Coordinate, status: ExploreStatus): Boolean`
   - `updateMonsterPresence(coordinate: Coordinate, hasMonster: Boolean): Boolean`
   - `updateBuilding(coordinate: Coordinate, buildingId: Long?): Boolean`
   - `unlockTile(coordinate: Coordinate): List<Coordinate>`:
     - 校验目标坐标地块，若其 `exploreStatus != ExploreStatus.VISIBLE_UNEXPLORED`，直接返回空列表。
     - 将目标地块的 `exploreStatus` 更改为 `EXPLORED`。
     - 遍历十字邻居（上下左右4个方向）。如果邻居存在于地图中，且邻居的 `exploreStatus == ExploreStatus.UNEXPLORED`，则将邻居更改为 `VISIBLE_UNEXPLORED`。
     - 目标坐标和所有被更改的邻居坐标必须加入 `dirtyQueue`。
     - 返回所有被更新成 `VISIBLE_UNEXPLORED` 的邻居坐标列表。

3. **零分配区域查询与条件过滤**：
   - `getTilesInRegion(minX: Int, maxX: Int, minY: Int, maxY: Int): List<TileData>`: 
     - 返回该矩形区域（闭区间）内所有存在的地块列表副本。
   - `filterTiles(predicate: (TileData) -> Boolean): List<TileData>`:
     - 过滤地图中所有满足条件的地块。返回防御性拷贝列表。

4. **批量事务更新**：
   - `suspend fun batchUpdateTiles(tiles: List<TileData>)`:
     - 在 `batchMutex.withLock` 内，将所有地块塞入 `tileMap`，并将坐标逐个塞入 `dirtyQueue`。

5. **序列化与脏流发射**：
   - `emitDirtyTiles()`: 非阻塞原子取出 `dirtyQueue` 中积压的所有坐标，如果有，通过 `_dirtyTilesFlow.tryEmit(list)` 发射。
   - `exportToJson(): String`: 使用 **Gson**（禁止 kotlinx.serialization，以兼容 customAttributes 多态 Map 结构）将 `tileMap.values.toList()` 导出为 JSON 字符串。
   - `importFromJson(json: String)`: 清空 `tileMap` 和 `dirtyQueue`，解析 JSON 并注入 `tileMap`。
   - **⚠️ Gson 数值类型读取规范**：Gson 反序列化 `Map<String, Any>` 时会将数值还原为 `Double`。全项目统一使用 `(customAttributes["key"] as Number).toInt()` 等安全转型。

---

## 4. 单元测试要求：GridMapDataTest.kt

编写高覆盖度的黑盒单元测试，包含以下测试用例：
1. **基础单点读写与清空**：`setTile` 后 `size() == 1`，读取属性一致；`clear()` 后为 0。
2. **原子槽位更新与十字迷雾解锁**：验证 `unlockTile` 成功将中心格子变为 `EXPLORED`，且上下左右若原为 `UNEXPLORED`，则全部级联更新为 `VISIBLE_UNEXPLORED` 并返回。
3. **区域零分配查询与过滤**。
4. **批量写入锁防护**：多协程并发事务写入，验证数据一致性，无数据断裂。
5. **Gson 持久化往返与数值多态解析安全测试**：验证 customAttributes 中存储的 Int（如 `mapOf("gold" to 500)`）在 JSON 导出再恢复后，能够通过 `(value as Number).toInt()` 安全读取，无类型转换异常。
6. **脏流发射与背压不挂起**：订阅 `dirtyTilesFlow`，修改格子，`emitDirtyTiles()` 后收到脏坐标；高频发射时，背压 `DROP_OLDEST` 确保不挂起协程。

---

## 5. 输出规范
- **只输出 Kotlin 代码**，分成 `GridMapData.kt` 和 `GridMapDataTest.kt`。
- 完全通过公开接口测试，禁止反射。
- 遵循 Kotlin 官方代码风格，加入完整的 visibility 修饰符。
