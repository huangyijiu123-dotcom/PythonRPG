package com.example.pythonrpg.engine.entity

import com.example.pythonrpg.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EntityStateManagerTest {

    @Test
    fun testBaseCRUDAndGoldCAS() = runTest {
        val manager = EntityStateManager()
        
        // 验证金币初始值为 0
        assertEquals(0, manager.getPlayerGold())

        // 验证增加金币
        assertTrue(manager.updatePlayerGold(100))
        assertEquals(100, manager.getPlayerGold())

        // 验证扣减金币 (正常)
        assertTrue(manager.updatePlayerGold(-40))
        assertEquals(60, manager.getPlayerGold())

        // 验证扣减金币 (超扣)
        assertFalse(manager.updatePlayerGold(-70)) // 应该返回 false，且金币依然是 60
        assertEquals(60, manager.getPlayerGold())

        // 验证村民 CRUD
        val villager = VillagerSnapshot(
            id = 1L,
            name = "Jack",
            coordinate = Coordinate(0, 0),
            status = VillagerStatus.IDLE,
            job = "LUMBERJACK",
            targetX = null,
            targetY = null,
            isInjured = false,
            energy = 100,
            backpack = mapOf("WOOD" to 10),
            equippedTools = mapOf("STONE_AXE" to 50)
        )
        
        manager.registerVillager(villager)
        assertEquals(villager, manager.getVillager(1L))
        assertEquals(1, manager.getAllVillagers().size)

        // 验证移除
        assertTrue(manager.removeVillager(1L))
        assertNull(manager.getVillager(1L))
        assertEquals(0, manager.getAllVillagers().size)
    }

    @Test
    fun testSilentTruncation() = runTest {
        val manager = EntityStateManager()

        val villager = VillagerSnapshot(
            id = 1L,
            name = "Jack",
            coordinate = Coordinate(0, 0),
            status = VillagerStatus.IDLE,
            job = "LUMBERJACK",
            targetX = null,
            targetY = null,
            isInjured = false,
            energy = 80,
            backpack = emptyMap(),
            equippedTools = emptyMap()
        )
        manager.registerVillager(villager)

        // 80 修改为 999 截断为 100
        assertTrue(manager.updateVillagerEnergy(1L, 999))
        assertEquals(100, manager.getVillager(1L)?.energy)

        // 修改为 -50 截断为 0
        assertTrue(manager.updateVillagerEnergy(1L, -50))
        assertEquals(0, manager.getVillager(1L)?.energy)

        // 冒险者 hp 截断
        val adventurer = AdventurerSnapshot(
            id = 2L,
            name = "Hero",
            coordinate = Coordinate(1, 1),
            status = AdventurerStatus.IDLE,
            hp = 80,
            maxHp = 150,
            mp = 50,
            fatigue = 20,
            weaponEquipmentId = null,
            armorEquipmentId = null
        )
        manager.registerAdventurer(adventurer)

        // hp 200 截断为 maxHp (150)
        assertTrue(manager.updateAdventurerHp(2L, 200))
        assertEquals(150, manager.getAdventurer(2L)?.hp)

        // hp -10 截断为 0
        assertTrue(manager.updateAdventurerHp(2L, -10))
        assertEquals(0, manager.getAdventurer(2L)?.hp)

        // mp 120 截断为 100, mp -20 截断为 0
        assertTrue(manager.updateAdventurerMp(2L, 120))
        assertEquals(100, manager.getAdventurer(2L)?.mp)
        assertTrue(manager.updateAdventurerMp(2L, -20))
        assertEquals(0, manager.getAdventurer(2L)?.mp)

        // fatigue 120 截断为 100, fatigue -50 截断为 0
        assertTrue(manager.updateAdventurerFatigue(2L, 120))
        assertEquals(100, manager.getAdventurer(2L)?.fatigue)
        assertTrue(manager.updateAdventurerFatigue(2L, -50))
        assertEquals(0, manager.getAdventurer(2L)?.fatigue)
    }

    @Test
    fun testHighConcurrencySlotSafeUpdates() = runBlocking {
        val manager = EntityStateManager()
        val villager = VillagerSnapshot(
            id = 1L,
            name = "Jack",
            coordinate = Coordinate(0, 0),
            status = VillagerStatus.IDLE,
            job = "LUMBERJACK",
            targetX = null,
            targetY = null,
            isInjured = false,
            energy = 100,
            backpack = mapOf("WOOD" to 100),
            equippedTools = emptyMap()
        )
        manager.registerVillager(villager)

        // 开 50 个协程各增加 2 个木头，50 个协程各扣减 2 个木头
        val jobs = mutableListOf<Job>()
        repeat(50) {
            jobs.add(launch {
                manager.updateVillagerBackpackItem(1L, "WOOD", 2)
            })
            jobs.add(launch {
                manager.updateVillagerBackpackItem(1L, "WOOD", -2)
            })
        }
        jobs.joinAll()

        // 最终木头数应当仍然为 100
        assertEquals(100, manager.getVillager(1L)?.backpack?.get("WOOD"))

        // 扣减全部 100 个木头，验证数量为 0 时键被彻底移出
        assertTrue(manager.updateVillagerBackpackItem(1L, "WOOD", -100))
        assertFalse(manager.getVillager(1L)?.backpack?.containsKey("WOOD") ?: true)
    }

    @Test
    fun testWarehouseCapacityAndRollback() = runTest {
        val manager = EntityStateManager()
        val warehouse = WarehouseSnapshot(
            id = 1L,
            coordinate = Coordinate(0, 0),
            capacity = 100,
            inventory = mapOf("WOOD" to 95)
        )
        manager.registerWarehouse(warehouse)

        // 尝试加入 10 个木头，会导致超过 capacity 100，应当被拦截并返回 false，且数据不改动
        assertFalse(manager.updateWarehouseInventory(1L, "WOOD", 10))
        assertEquals(95, manager.getWarehouse(1L)?.inventory?.get("WOOD"))

        // 尝试减少 100 个木头，会导致小于 0，应当被拦截并返回 false，且数据不改动
        assertFalse(manager.updateWarehouseInventory(1L, "WOOD", -100))
        assertEquals(95, manager.getWarehouse(1L)?.inventory?.get("WOOD"))

        // 正常增加 5 个木头
        assertTrue(manager.updateWarehouseInventory(1L, "WOOD", 5))
        assertEquals(100, manager.getWarehouse(1L)?.inventory?.get("WOOD"))
    }

    @Test
    fun testToolDurabilityRemoval() = runTest {
        val manager = EntityStateManager()
        val villager = VillagerSnapshot(
            id = 1L,
            name = "Jack",
            coordinate = Coordinate(0, 0),
            status = VillagerStatus.IDLE,
            job = "LUMBERJACK",
            targetX = null,
            targetY = null,
            isInjured = false,
            energy = 100,
            backpack = emptyMap(),
            equippedTools = mapOf("STONE_AXE" to 10)
        )
        manager.registerVillager(villager)

        // 减少 5 点耐久
        assertTrue(manager.updateToolDurability(1L, "STONE_AXE", 5))
        assertEquals(5, manager.getVillager(1L)?.equippedTools?.get("STONE_AXE"))

        // 归零或小于 0 耐久，应彻底移出
        assertTrue(manager.updateToolDurability(1L, "STONE_AXE", 0))
        assertFalse(manager.getVillager(1L)?.equippedTools?.containsKey("STONE_AXE") ?: true)
    }

    @Test
    fun testCrossEntityTransferAndConcurrency() = runBlocking {
        val manager = EntityStateManager()
        
        val villager = VillagerSnapshot(
            id = 1L,
            name = "Jack",
            coordinate = Coordinate(0, 0),
            status = VillagerStatus.IDLE,
            job = "LUMBERJACK",
            targetX = null,
            targetY = null,
            isInjured = false,
            energy = 100,
            backpack = mapOf("WOOD" to 500),
            equippedTools = emptyMap()
        )
        val warehouse = WarehouseSnapshot(
            id = 2L,
            coordinate = Coordinate(0, 0),
            capacity = 1000,
            inventory = emptyMap()
        )
        manager.registerVillager(villager)
        manager.registerWarehouse(warehouse)

        // 验证无效转移：村民物资不足
        assertFalse(manager.transferItemVillagerToWarehouse(1L, 2L, "WOOD", 600))
        assertEquals(500, manager.getVillager(1L)?.backpack?.get("WOOD"))
        assertEquals(0, manager.getWarehouse(2L)?.inventory?.get("WOOD") ?: 0)

        // 验证无效转移：仓库超容量
        val tinyWarehouse = WarehouseSnapshot(
            id = 3L,
            coordinate = Coordinate(0, 0),
            capacity = 50,
            inventory = emptyMap()
        )
        manager.registerWarehouse(tinyWarehouse)
        assertFalse(manager.transferItemVillagerToWarehouse(1L, 3L, "WOOD", 60))
        assertEquals(500, manager.getVillager(1L)?.backpack?.get("WOOD"))
        assertEquals(0, manager.getWarehouse(3L)?.inventory?.get("WOOD") ?: 0)

        // 高并发转移测试：100 个协程各转移 5 个木头到仓库
        val jobs = mutableListOf<Job>()
        repeat(100) {
            jobs.add(launch {
                manager.transferItemVillagerToWarehouse(1L, 2L, "WOOD", 5)
            })
        }
        jobs.joinAll()

        // 最终村民剩 0 个木头，仓库有 500 个木头
        assertEquals(0, manager.getVillager(1L)?.backpack?.get("WOOD") ?: 0)
        assertEquals(500, manager.getWarehouse(2L)?.inventory?.get("WOOD"))
    }

    @Test
    fun testIncrementalDirtyFlow() = runBlocking {
        val manager = EntityStateManager()
        
        // 订阅 stateDiffFlow
        val receivedDiffs = mutableListOf<StateDiff>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            manager.stateDiffFlow.collect { receivedDiffs.add(it) }
        }

        // 注册村民，金币更新
        val villager = VillagerSnapshot(
            id = 1L,
            name = "Jack",
            coordinate = Coordinate(0, 0),
            status = VillagerStatus.IDLE,
            job = "LUMBERJACK",
            targetX = null,
            targetY = null,
            isInjured = false,
            energy = 100,
            backpack = emptyMap(),
            equippedTools = emptyMap()
        )
        manager.registerVillager(villager)
        manager.updatePlayerGold(50)

        // 广播差异
        manager.emitStateDiff()

        assertEquals(1, receivedDiffs.size)
        val diff = receivedDiffs.first()
        assertEquals(listOf(1L), diff.villagers)
        assertTrue(diff.goldChanged)

        // 再次广播无变化，应不广播且脏标记已重置
        manager.emitStateDiff()
        assertEquals(1, receivedDiffs.size)

        collectJob.cancel()
    }

    @Test
    fun testArchiveExportAndImportLoop() = runTest {
        val manager = EntityStateManager()

        val villager = VillagerSnapshot(
            id = 1L,
            name = "Jack",
            coordinate = Coordinate(0, 0),
            status = VillagerStatus.IDLE,
            job = "LUMBERJACK",
            targetX = null,
            targetY = null,
            isInjured = false,
            energy = 100,
            backpack = mapOf("WOOD" to 10),
            equippedTools = mapOf("STONE_AXE" to 50)
        )
        manager.registerVillager(villager)
        manager.updatePlayerGold(500)

        // 导出 Json
        val json = manager.exportStateToJson()
        assertTrue(json.contains("Jack"))
        assertTrue(json.contains("500"))

        // 清空并重新加载
        val newManager = EntityStateManager()
        newManager.loadStateFromJson(json)

        assertEquals(500, newManager.getPlayerGold())
        assertEquals(villager, newManager.getVillager(1L))
    }
}
