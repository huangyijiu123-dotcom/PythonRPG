package com.example.pythonrpg.engine.entity

import com.example.pythonrpg.shared.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SaveData - 存档数据格式定义
 */
data class SaveData(
    val villagers: List<VillagerSnapshot>,
    val adventurers: List<AdventurerSnapshot>,
    val equipments: List<EquipmentSnapshot>,
    val caravans: List<CaravanSnapshot>,
    val warehouses: List<WarehouseSnapshot>,
    val playerGold: Int
)

/**
 * EntityStateManager - 纯内存的贫血实体数据库，负责集中式管理村民、冒险者、装备、商队和仓库的状态
 */
class EntityStateManager {

    // ── 并发管理容器 ──────────────────────────────────────────────
    private val villagers = ConcurrentHashMap<Long, VillagerSnapshot>()
    private val adventurers = ConcurrentHashMap<Long, AdventurerSnapshot>()
    private val equipments = ConcurrentHashMap<Long, EquipmentSnapshot>()
    private val caravans = ConcurrentHashMap<Long, CaravanSnapshot>()
    private val warehouses = ConcurrentHashMap<Long, WarehouseSnapshot>()
    private val playerGold = AtomicInteger(0)

    // ── 脏变化跟踪状态 ─────────────────────────────────────────────
    private val dirtyVillagers = ConcurrentHashMap.newKeySet<Long>()
    private val dirtyAdventurers = ConcurrentHashMap.newKeySet<Long>()
    private val dirtyCaravans = ConcurrentHashMap.newKeySet<Long>()
    private val dirtyWarehouses = ConcurrentHashMap.newKeySet<Long>()
    private val dirtyEquipments = ConcurrentHashMap.newKeySet<Long>()
    private val goldChanged = AtomicBoolean(false)

    // ── 增量脏广播流 ──────────────────────────────────────────────
    private val _stateDiffFlow = MutableSharedFlow<StateDiff>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val stateDiffFlow: SharedFlow<StateDiff> = _stateDiffFlow.asSharedFlow()

    // ── 跨实体原子事务锁 ───────────────────────────────────────────
    private val transactionMutex = Mutex()

    // ── 基础 CRUD 与全局金币 ───────────────────────────────────────

    fun registerVillager(snapshot: VillagerSnapshot) {
        villagers[snapshot.id] = snapshot
        dirtyVillagers.add(snapshot.id)
    }

    fun getVillager(id: Long): VillagerSnapshot? = villagers[id]

    fun getAllVillagers(): List<VillagerSnapshot> = villagers.values.toList()

    fun removeVillager(id: Long): Boolean {
        val removed = villagers.remove(id) != null
        if (removed) {
            dirtyVillagers.add(id)
        }
        return removed
    }

    fun registerAdventurer(snapshot: AdventurerSnapshot) {
        adventurers[snapshot.id] = snapshot
        dirtyAdventurers.add(snapshot.id)
    }

    fun getAdventurer(id: Long): AdventurerSnapshot? = adventurers[id]

    fun getAllAdventurers(): List<AdventurerSnapshot> = adventurers.values.toList()

    fun removeAdventurer(id: Long): Boolean {
        val removed = adventurers.remove(id) != null
        if (removed) {
            dirtyAdventurers.add(id)
        }
        return removed
    }

    fun registerEquipment(snapshot: EquipmentSnapshot) {
        equipments[snapshot.id] = snapshot
        dirtyEquipments.add(snapshot.id)
    }

    fun getEquipment(id: Long): EquipmentSnapshot? = equipments[id]

    fun getAllEquipments(): List<EquipmentSnapshot> = equipments.values.toList()

    fun removeEquipment(id: Long): Boolean {
        val removed = equipments.remove(id) != null
        if (removed) {
            dirtyEquipments.add(id)
        }
        return removed
    }

    fun registerCaravan(snapshot: CaravanSnapshot) {
        caravans[snapshot.id] = snapshot
        dirtyCaravans.add(snapshot.id)
    }

    fun getCaravan(id: Long): CaravanSnapshot? = caravans[id]

    fun getAllCaravans(): List<CaravanSnapshot> = caravans.values.toList()

    fun removeCaravan(id: Long): Boolean {
        val removed = caravans.remove(id) != null
        if (removed) {
            dirtyCaravans.add(id)
        }
        return removed
    }

    fun registerWarehouse(snapshot: WarehouseSnapshot) {
        warehouses[snapshot.id] = snapshot
        dirtyWarehouses.add(snapshot.id)
    }

    fun getWarehouse(id: Long): WarehouseSnapshot? = warehouses[id]

    fun getAllWarehouses(): List<WarehouseSnapshot> = warehouses.values.toList()

    fun removeWarehouse(id: Long): Boolean {
        val removed = warehouses.remove(id) != null
        if (removed) {
            dirtyWarehouses.add(id)
        }
        return removed
    }

    /**
     * 金币原子 CAS 更新
     */
    fun updatePlayerGold(delta: Int): Boolean {
        while (true) {
            val current = playerGold.get()
            val next = current + delta
            if (next < 0) {
                return false
            }
            if (playerGold.compareAndSet(current, next)) {
                goldChanged.set(true)
                return true
            }
        }
    }

    fun getPlayerGold(): Int = playerGold.get()

    // ── 局部单体原子更新 ──────────────────────────────────────────

    fun updateVillagerPosition(id: Long, newX: Int, newY: Int): Boolean {
        var success = false
        villagers.computeIfPresent(id) { _, v ->
            success = true
            dirtyVillagers.add(id)
            v.copy(coordinate = Coordinate(newX, newY))
        }
        return success
    }

    fun updateVillagerStatus(id: Long, status: VillagerStatus): Boolean {
        var success = false
        villagers.computeIfPresent(id) { _, v ->
            success = true
            dirtyVillagers.add(id)
            v.copy(status = status)
        }
        return success
    }

    fun updateVillagerJob(id: Long, job: String, targetX: Int?, targetY: Int?): Boolean {
        var success = false
        villagers.computeIfPresent(id) { _, v ->
            success = true
            dirtyVillagers.add(id)
            v.copy(job = job, targetX = targetX, targetY = targetY)
        }
        return success
    }

    fun updateVillagerInjured(id: Long, isInjured: Boolean): Boolean {
        var success = false
        villagers.computeIfPresent(id) { _, v ->
            success = true
            dirtyVillagers.add(id)
            v.copy(isInjured = isInjured)
        }
        return success
    }

    fun updateVillagerEnergy(id: Long, newEnergy: Int): Boolean {
        var success = false
        villagers.computeIfPresent(id) { _, v ->
            success = true
            dirtyVillagers.add(id)
            v.copy(energy = newEnergy.coerceIn(0, 100))
        }
        return success
    }

    fun updateVillagerBackpackItem(id: Long, item: String, amount: Int): Boolean {
        var success = false
        var allowed = true
        villagers.computeIfPresent(id) { _, v ->
            val currentAmount = v.backpack[item] ?: 0
            val finalAmount = currentAmount + amount
            if (finalAmount < 0) {
                allowed = false
                v
            } else {
                success = true
                dirtyVillagers.add(id)
                val newBackpack = v.backpack.toMutableMap()
                if (finalAmount == 0) {
                    newBackpack.remove(item)
                } else {
                    newBackpack[item] = finalAmount
                }
                v.copy(backpack = newBackpack)
            }
        }
        return success && allowed
    }

    fun equipTool(id: Long, toolId: String, durability: Int): Boolean {
        var success = false
        villagers.computeIfPresent(id) { _, v ->
            success = true
            dirtyVillagers.add(id)
            val newTools = v.equippedTools.toMutableMap()
            newTools[toolId] = durability
            v.copy(equippedTools = newTools)
        }
        return success
    }

    fun updateToolDurability(id: Long, toolId: String, newDurability: Int): Boolean {
        var success = false
        villagers.computeIfPresent(id) { _, v ->
            success = true
            dirtyVillagers.add(id)
            val newTools = v.equippedTools.toMutableMap()
            if (newDurability <= 0) {
                newTools.remove(toolId)
            } else {
                newTools[toolId] = newDurability
            }
            v.copy(equippedTools = newTools)
        }
        return success
    }

    fun updateAdventurerPosition(id: Long, newX: Int, newY: Int): Boolean {
        var success = false
        adventurers.computeIfPresent(id) { _, a ->
            success = true
            dirtyAdventurers.add(id)
            a.copy(coordinate = Coordinate(newX, newY))
        }
        return success
    }

    fun updateAdventurerStatus(id: Long, status: AdventurerStatus): Boolean {
        var success = false
        adventurers.computeIfPresent(id) { _, a ->
            success = true
            dirtyAdventurers.add(id)
            a.copy(status = status)
        }
        return success
    }

    fun updateAdventurerEquipmentSlots(id: Long, weaponId: Long?, armorId: Long?): Boolean {
        var success = false
        adventurers.computeIfPresent(id) { _, a ->
            success = true
            dirtyAdventurers.add(id)
            a.copy(weaponEquipmentId = weaponId, armorEquipmentId = armorId)
        }
        return success
    }

    fun updateAdventurerHp(id: Long, newHp: Int): Boolean {
        var success = false
        adventurers.computeIfPresent(id) { _, a ->
            success = true
            dirtyAdventurers.add(id)
            a.copy(hp = newHp.coerceIn(0, a.maxHp))
        }
        return success
    }

    fun updateAdventurerMp(id: Long, newMp: Int): Boolean {
        var success = false
        adventurers.computeIfPresent(id) { _, a ->
            success = true
            dirtyAdventurers.add(id)
            a.copy(mp = newMp.coerceIn(0, 100))
        }
        return success
    }

    fun updateAdventurerFatigue(id: Long, newFatigue: Int): Boolean {
        var success = false
        adventurers.computeIfPresent(id) { _, a ->
            success = true
            dirtyAdventurers.add(id)
            a.copy(fatigue = newFatigue.coerceIn(0, 100))
        }
        return success
    }

    fun updateEquipmentLevel(id: Long, newLevel: Int): Boolean {
        var success = false
        equipments.computeIfPresent(id) { _, eq ->
            success = true
            dirtyEquipments.add(id)
            eq.copy(level = newLevel.coerceIn(0, 10))
        }
        return success
    }

    fun updateEquipmentDurability(id: Long, newDurability: Int): Boolean {
        var success = false
        equipments.computeIfPresent(id) { _, eq ->
            success = true
            dirtyEquipments.add(id)
            eq.copy(durability = newDurability.coerceIn(0, eq.maxDurability))
        }
        return success
    }

    fun updateEquipmentOwner(id: Long, newOwnerId: Long?): Boolean {
        var success = false
        equipments.computeIfPresent(id) { _, eq ->
            success = true
            dirtyEquipments.add(id)
            eq.copy(ownerId = newOwnerId)
        }
        return success
    }

    fun updateCaravanPosition(id: Long, newX: Int, newY: Int): Boolean {
        var success = false
        caravans.computeIfPresent(id) { _, c ->
            success = true
            dirtyCaravans.add(id)
            c.copy(coordinate = Coordinate(newX, newY))
        }
        return success
    }

    fun updateCaravanStatus(id: Long, status: CaravanStatus): Boolean {
        var success = false
        caravans.computeIfPresent(id) { _, c ->
            success = true
            dirtyCaravans.add(id)
            c.copy(status = status)
        }
        return success
    }

    fun updateCaravanTarget(id: Long, targetX: Int?, targetY: Int?): Boolean {
        var success = false
        caravans.computeIfPresent(id) { _, c ->
            success = true
            dirtyCaravans.add(id)
            c.copy(targetX = targetX, targetY = targetY)
        }
        return success
    }

    fun updateCaravanCargo(id: Long, item: String, amount: Int): Boolean {
        var success = false
        var allowed = true
        caravans.computeIfPresent(id) { _, c ->
            val currentAmount = c.cargo[item] ?: 0
            val finalAmount = currentAmount + amount
            if (finalAmount < 0) {
                allowed = false
                c
            } else {
                success = true
                dirtyCaravans.add(id)
                val newCargo = c.cargo.toMutableMap()
                if (finalAmount == 0) {
                    newCargo.remove(item)
                } else {
                    newCargo[item] = finalAmount
                }
                c.copy(cargo = newCargo)
            }
        }
        return success && allowed
    }

    fun expandWarehouseCapacity(id: Long, additionalCapacity: Int): Boolean {
        if (additionalCapacity <= 0) return false
        var success = false
        warehouses.computeIfPresent(id) { _, w ->
            success = true
            dirtyWarehouses.add(id)
            w.copy(capacity = w.capacity + additionalCapacity)
        }
        return success
    }

    fun updateWarehouseInventory(id: Long, item: String, amount: Int): Boolean {
        var success = false
        var allowed = true
        warehouses.computeIfPresent(id) { _, w ->
            val currentAmount = w.inventory[item] ?: 0
            val finalAmount = currentAmount + amount
            if (finalAmount < 0) {
                allowed = false
                w
            } else {
                val currentTotal = w.inventory.values.sum()
                val totalAfterChange = currentTotal - currentAmount + finalAmount
                if (totalAfterChange > w.capacity) {
                    allowed = false
                    w
                } else {
                    success = true
                    dirtyWarehouses.add(id)
                    val newInventory = w.inventory.toMutableMap()
                    if (finalAmount == 0) {
                        newInventory.remove(item)
                    } else {
                        newInventory[item] = finalAmount
                    }
                    w.copy(inventory = newInventory)
                }
            }
        }
        return success && allowed
    }

    // ── 跨实体防数据撕裂原子事务 ────────────────────────────────────────

    suspend fun transferItemVillagerToWarehouse(
        villagerId: Long,
        warehouseId: Long,
        item: String,
        amount: Int
    ): Boolean {
        if (amount <= 0) return false
        transactionMutex.withLock {
            val v = villagers[villagerId] ?: return false
            val w = warehouses[warehouseId] ?: return false

            val currentBagAmount = v.backpack[item] ?: 0
            if (currentBagAmount < amount) return false

            val currentWarehouseTotal = w.inventory.values.sum()
            if (currentWarehouseTotal + amount > w.capacity) return false

            // 原子双写
            updateVillagerBackpackItem(villagerId, item, -amount)
            updateWarehouseInventory(warehouseId, item, amount)
            return true
        }
    }

    suspend fun transferItemCaravanToWarehouse(
        caravanId: Long,
        warehouseId: Long,
        item: String,
        amount: Int
    ): Boolean {
        if (amount <= 0) return false
        transactionMutex.withLock {
            val c = caravans[caravanId] ?: return false
            val w = warehouses[warehouseId] ?: return false

            val currentCargoAmount = c.cargo[item] ?: 0
            if (currentCargoAmount < amount) return false

            val currentWarehouseTotal = w.inventory.values.sum()
            if (currentWarehouseTotal + amount > w.capacity) return false

            // 原子双写
            updateCaravanCargo(caravanId, item, -amount)
            updateWarehouseInventory(warehouseId, item, amount)
            return true
        }
    }

    suspend fun transferItemWarehouseToCaravan(
        warehouseId: Long,
        caravanId: Long,
        item: String,
        amount: Int
    ): Boolean {
        if (amount <= 0) return false
        transactionMutex.withLock {
            val w = warehouses[warehouseId] ?: return false
            val c = caravans[caravanId] ?: return false

            val currentWarehouseAmount = w.inventory[item] ?: 0
            if (currentWarehouseAmount < amount) return false

            val currentCaravanTotal = c.cargo.values.sum()
            if (currentCaravanTotal + amount > c.capacity) return false

            // 原子双写
            updateWarehouseInventory(warehouseId, item, -amount)
            updateCaravanCargo(caravanId, item, amount)
            return true
        }
    }

    // ── 增量脏流广播与 Gson 存档引擎 ───────────────────────────────

    fun emitStateDiff() {
        val diff = StateDiff(
            villagers = dirtyVillagers.toList(),
            adventurers = dirtyAdventurers.toList(),
            caravans = dirtyCaravans.toList(),
            warehouses = dirtyWarehouses.toList(),
            equipments = dirtyEquipments.toList(),
            goldChanged = goldChanged.get()
        )
        if (diff.villagers.isNotEmpty() ||
            diff.adventurers.isNotEmpty() ||
            diff.caravans.isNotEmpty() ||
            diff.warehouses.isNotEmpty() ||
            diff.equipments.isNotEmpty() ||
            diff.goldChanged
        ) {
            _stateDiffFlow.tryEmit(diff)
            dirtyVillagers.clear()
            dirtyAdventurers.clear()
            dirtyCaravans.clear()
            dirtyWarehouses.clear()
            dirtyEquipments.clear()
            goldChanged.set(false)
        }
    }

    fun exportStateToJson(): String {
        val data = SaveData(
            villagers = villagers.values.toList(),
            adventurers = adventurers.values.toList(),
            equipments = equipments.values.toList(),
            caravans = caravans.values.toList(),
            warehouses = warehouses.values.toList(),
            playerGold = playerGold.get()
        )
        val gson = GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(data)
    }

    fun loadStateFromJson(json: String) {
        val gson = Gson()
        val data = gson.fromJson(json, SaveData::class.java)

        villagers.clear()
        adventurers.clear()
        equipments.clear()
        caravans.clear()
        warehouses.clear()

        dirtyVillagers.clear()
        dirtyAdventurers.clear()
        dirtyCaravans.clear()
        dirtyWarehouses.clear()
        dirtyEquipments.clear()
        goldChanged.set(false)

        data.villagers.forEach { villagers[it.id] = it }
        data.adventurers.forEach { adventurers[it.id] = it }
        data.equipments.forEach { equipments[it.id] = it }
        data.caravans.forEach { caravans[it.id] = it }
        data.warehouses.forEach { warehouses[it.id] = it }
        playerGold.set(data.playerGold)
    }
}
