# AI Coding Prompt: MDU 04_EntityStateManager (实体状态数据库)

你是一个资深 Kotlin 游戏引擎专家。你的任务是实现 `EntityStateManager` 类及其所有的单元测试。这是一个纯内存的贫血实体数据库，负责集中式管理村民、冒险者、装备、商队和仓库的状态，处理单体状态原子增删改查、数值静默截断、跨实体防撕裂资源转移事务、基于脏 ID 的增量变化流广播以及基于 Gson 的全量序列化存档。

---

## 1. 外部依赖统一模型
> ⚠️ 注意：以下类型必须直接使用我们统一在包 `com.example.pythonrpg.shared` 中的声明，不要重新声明它们：
```kotlin
package com.example.pythonrpg.shared

data class Coordinate(val x: Int, val y: Int)

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

enum class VillagerStatus { IDLE, WORKING, SLEEPING }
enum class AdventurerStatus { IDLE, ADVENTURING, COMBAT, RESTING }
enum class CaravanStatus { IDLE, TRAVELING, TRADING }

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
```

---

## 2. 核心类契约 & 内部状态

请声明 `class EntityStateManager` 并包含以下属性和并发管理容器（全部使用 `ConcurrentHashMap`，金币使用 `AtomicInteger`）：
- `private val villagers = ConcurrentHashMap<Long, VillagerSnapshot>()`
- `private val adventurers = ConcurrentHashMap<Long, AdventurerSnapshot>()`
- `private val equipments = ConcurrentHashMap<Long, EquipmentSnapshot>()`
- `private val caravans = ConcurrentHashMap<Long, CaravanSnapshot>()`
- `private val warehouses = ConcurrentHashMap<Long, WarehouseSnapshot>()`
- `private val playerGold = AtomicInteger(0)`

### 2.1 脏变化跟踪状态
- `private val dirtyVillagers = ConcurrentHashMap.newKeySet<Long>()`
- `private val dirtyAdventurers = ConcurrentHashMap.newKeySet<Long>()`
- `private val dirtyCaravans = ConcurrentHashMap.newKeySet<Long>()`
- `private val dirtyWarehouses = ConcurrentHashMap.newKeySet<Long>()`
- `private val dirtyEquipments = ConcurrentHashMap.newKeySet<Long>()`
- `private val goldChanged = AtomicBoolean(false)`

### 2.2 增量脏广播流
- `private val _stateDiffFlow = MutableSharedFlow<StateDiff>(replay = 0, extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)`
- `val stateDiffFlow: SharedFlow<StateDiff> = _stateDiffFlow.asSharedFlow()`

### 2.3 互斥管理原语
- `private val transactionMutex = Mutex()` *(用于防数据撕裂跨实体转移事务的排他协程锁)*

---

## 3. 详细方法行为与更新契约

> ⚠️ 所有单体写操作判定成功后，**必须将实体 ID 标记脏**（加入对应的 `dirty` 集合）。
> ⚠️ 所有单体状态的修改**严禁先 get 出来 copy 完再 put 塞回**。必须在并发容器的 `computeIfPresent` 槽锁内原子更新。

### 3.1 基础 CRUD 与全局金币
- **CRUD 接口**：对村民、冒险者、装备、商队、仓库提供标准的 `registerX(snapshot)`（插入或更新）、`getX(id): snapshot?`、`getAllX(): List<snapshot>`（防御性拷贝 `values.toList()`）、`removeX(id): Boolean`（注销，成功时标记脏并返回 true）。
- **金币原子 CAS 更新**：`updatePlayerGold(delta: Int): Boolean`。使用 Atomic CAS 循环更新：如果 `delta < 0` 且扣减后导致金币余额小于 0，回滚并立即返回 `false`；否则更新金币值，标记 `goldChanged.set(true)`，返回 `true`。获取金币使用 `getPlayerGold(): Int`。

### 3.2 局部单体原子更新与静默数值截断
> 数值更新必须使用 `coerceIn` 精准且静默地截断到合法区间，对于 Map 属性修改必须 `toMutableMap()`，若累加后数量为 0 则彻底移除该物品/工具的键，不能遗漏。
- **村民更新**：
  - `updateVillagerPosition(id, newX, newY): Boolean`
  - `updateVillagerStatus(id, status): Boolean`
  - `updateVillagerJob(id, job, targetX, targetY): Boolean`
  - `updateVillagerInjured(id, isInjured): Boolean`
  - `updateVillagerEnergy(id, newEnergy): Boolean` -> 静默截断在 `0..100`。
  - `updateVillagerBackpackItem(id, item, amount): Boolean` -> amount 可正可负，背包增加或扣除；如果扣减后数量 < 0，返回 `false` 且原状回滚；如果数量累加后为 0，彻底从 `backpack` Map 移除该物品。
  - `equipTool(id, toolId, durability): Boolean` -> 将工具加入到村民的 `equippedTools` Map 中。
  - `updateToolDurability(id, toolId, newDurability): Boolean` -> 更新工具耐久；如果耐久 <= 0，彻底从 `equippedTools` Map 中移除该工具。
- **冒险者更新**：
  - `updateAdventurerPosition(id, newX, newY): Boolean`
  - `updateAdventurerStatus(id, status): Boolean`
  - `updateAdventurerEquipmentSlots(id, weaponId, armorId): Boolean`
  - `updateAdventurerHp(id, newHp): Boolean` -> hp 静默截断在 `0..maxHp`。
  - `updateAdventurerMp(id, newMp): Boolean` -> mp 静默截断在 `0..100`。
  - `updateAdventurerFatigue(id, newFatigue): Boolean` -> fatigue 静默截断在 `0..100`。
- **装备更新**：
  - `updateEquipmentLevel(id, newLevel): Boolean` -> 强化等级静默截断在 `0..10`。
  - `updateEquipmentDurability(id, newDurability): Boolean` -> 耐久度静默截断在 `0..maxDurability`。
  - `updateEquipmentOwner(id, newOwnerId): Boolean`
- **商队更新**：
  - `updateCaravanPosition(id, newX, newY): Boolean`
  - `updateCaravanStatus(id, status): Boolean`
  - `updateCaravanTarget(id, targetX, targetY): Boolean`
  - `updateCaravanCargo(id, item, amount): Boolean` -> 增减商队货物；扣减后 < 0 返回 `false`；累加为 0 时移出 `cargo` Map。
- **仓库更新**：
  - `expandWarehouseCapacity(id, additionalCapacity): Boolean` -> additionalCapacity 必须为正数，否则返回 `false`。
  - `updateWarehouseInventory(id, item, amount): Boolean` -> 库存原子修改；扣减后 < 0 返回 `false`；累加后为 0 移出 Map；总库存累加后若超过 `capacity`，返回 `false` 且不修改原状态。

### 3.3 跨实体防数据撕裂原子事务 (全程使用 `suspend` 且用 `transactionMutex.withLock` 彻底排他保护)
1. **村民背包到仓库转移**：`suspend fun transferItemVillagerToWarehouse(villagerId: Long, warehouseId: Long, item: String, amount: Int): Boolean`
   - `amount <= 0` 直接返回 `false`。
   - 锁内校验：村民、仓库是否存在；村民 backpack 中 item 数量是否 `>= amount`；仓库当前总库存累加 amount 后是否 `<= capacity`。
   - 校验通过后，原子双写：扣减村民背包（为 0 移出），累加仓库库存，将两个实体的 ID 分别标记为脏。
2. **商队货物到仓库转移**：`suspend fun transferItemCaravanToWarehouse(caravanId: Long, warehouseId: Long, item: String, amount: Int): Boolean`
   - 逻辑与村民转仓库完全相同，转移成功后标记脏。
3. **仓库到商队货物转移**：`suspend fun transferItemWarehouseToCaravan(warehouseId: Long, caravanId: Long, item: String, amount: Int): Boolean`
   - 锁内校验：仓库、商队是否存在；仓库库存中 item 数量是否 `>= amount`；商队当前总负重累加 amount 后是否 `<= caravan.capacity`。
   - 校验通过后，原子双写：扣减仓库库存（为 0 移出），累加商队货物，两端标记为脏。

### 3.4 增量脏流广播与 Gson 存档引擎
- `emitStateDiff()`: 将脏集合打包为 `StateDiff`（如果至少有一个实体 ID 被更新或金币被修改），使用非阻塞 `_stateDiffFlow.tryEmit` 发送，并将脏 ID 缓存集和金币脏状态重置。
- `exportStateToJson(): String`: 定义 `SaveData` 数据类（包含村民、冒险者、装备、商队、仓库的 values 列表，以及玩家金币整数）。使用 **Gson**（禁止 kotlinx.serialization，防止 Python 跨平台互操作时类型擦除）将当前数据导出为 JSON 字符串。
- `loadStateFromJson(json: String)`: 清空所有底层 Map 及脏标记集合。将 JSON 反序列化回 `SaveData` 对象并完整注入各容器，重置全局金币值。

---

## 4. 单元测试要求：EntityStateManagerTest.kt

编写完整的单元测试类（包含并发测试，使用 `runTest`）：
1. **基础 CRUD 与金币 CAS 逻辑**：检验增删改查；验证 updatePlayerGold 在 delta 为负且超限时能够正确拦截并返回 false，且正常时 CAS 循环更新正确。
2. **数值静默截断**：体力 80 修改为 999 自动截断为 100；hp 80 （maxHp=150）修改为 200 自动截断为 150；fatigue 20 修改为 -50 自动截断为 0。
3. **高并发槽位安全更新**：开 50 个协程增加物品数量，50 个协程扣除物品数量，验证 `computeIfPresent` 槽锁确保最终结果绝对一致，且当物品为 0 时键自动被移出。
4. **仓库容量与扣减溢出回滚**：验证向已达容量上限的仓库强行存入资源或超额扣除资源时，操作返回 `false` 且仓库数据被完整保留（不产生修改）。
5. **工具耐久归零移除**。
6. **跨实体转移防撕裂与回滚**：
   - 验证村民/仓库不存在时、村民背包物资不足时、或目标仓库超容时，`transferItemVillagerToWarehouse` 返回 `false` 且两端数据无变化。
   - 并发 100 个协程各从村民转移 5 个木头到仓库，验证在 transactionMutex 全局事务锁下金币、物资无丢失，最终数据绝对一致。
7. **脏数据增量流发射与背压**：验证 `stateDiffFlow` 流的 tryEmit 不挂起，以及 `emitStateDiff()` 行为在更新后返回包含更新 ID 列表的 StateDiff，之后清空。
8. **存档导出与导入回环**：验证复杂数据结构（含 customAttributes）导入再导出后能够完整保留精度与字典内部的 Number 值（使用 Number 安全转型读取）。

---

## 5. 输出规范
- **只输出 Kotlin 代码**，分成 `EntityStateManager.kt` 和 `EntityStateManagerTest.kt`。
- 不要使用任何 Java 反射技术，完全通过公开接口测试。
- 遵循 Kotlin 官方代码风格，加入完整的 visibility 修饰符。
