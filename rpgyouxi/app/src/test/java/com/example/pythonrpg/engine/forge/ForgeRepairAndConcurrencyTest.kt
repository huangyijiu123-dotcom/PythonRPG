package com.example.pythonrpg.engine.forge

import com.example.pythonrpg.shared.EquipmentSnapshot
import com.example.pythonrpg.shared.EquipmentClass
import kotlin.test.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

class ForgeRepairAndConcurrencyTest {

    @Test
    fun testRepairHalfDamagedEquipment() {
        val database = ConcurrentHashMap<Long, EquipmentSnapshot>()
        var deductedCosts: Map<String, Int>? = null
        
        val mockProvider = object : ForgeDependencyProvider {
            override fun deductResources(resources: Map<String, Int>): Boolean {
                deductedCosts = resources
                return true
            }
            override fun addResourcesToWarehouse(resources: Map<String, Int>) {}
            override fun addEquipmentToWarehouse(equipment: EquipmentSnapshot) {}
            override fun getEquipment(equipmentId: Long): EquipmentSnapshot? = database[equipmentId]
            override fun updateEquipment(equipment: EquipmentSnapshot) {
                database[equipment.id] = equipment
            }
            override fun removeEquipment(equipmentId: Long) {}
        }
        
        // 自定义配方以满足测试数据：铁矿 15
        val customTemplates = mapOf(
            "IRON_SWORD" to EquipmentRecipe(
                equipmentClass = "WEAPON",
                woodCost = 10,
                stoneCost = 0,
                ironCost = 15,
                goldCost = 0,
                obsidianCost = 0,
                maxDurability = 200,
                baseStat = 20,
                requiredTicks = 5
            )
        )
        
        val engine = ForgeEngine(mockProvider, customTemplates)
        
        // 注册一个耐久 100/200 的磨损 50% 铁剑
        val sword = EquipmentSnapshot(
            id = 777L,
            templateId = "IRON_SWORD",
            equipmentClass = EquipmentClass.WEAPON,
            level = 0,
            durability = 100,
            maxDurability = 200,
            baseAttack = 20,
            baseDefense = 0,
            baseStat = 20,
            currentStat = 20,
            ownerId = null
        )
        database[777L] = sword
        
        // 执行修理
        assertTrue(engine.repairEquipment(777L))
        
        // 验证扣除的铁矿为 ceil(15 * 0.5) = 8 块铁矿石
        assertNotNull(deductedCosts)
        assertEquals(8, deductedCosts!!["IRON_ORE"])
        
        // 验证耐久恢复
        val repairedSword = database[777L]!!
        assertEquals(200, repairedSword.durability)
    }

    @Test
    fun testRepairPerfectDurabilityBypass() {
        val database = ConcurrentHashMap<Long, EquipmentSnapshot>()
        
        val mockProvider = object : ForgeDependencyProvider {
            override fun deductResources(resources: Map<String, Int>): Boolean {
                throw AssertionError("Should not deduct resources when durability is perfect!")
            }
            override fun addResourcesToWarehouse(resources: Map<String, Int>) {}
            override fun addEquipmentToWarehouse(equipment: EquipmentSnapshot) {}
            override fun getEquipment(equipmentId: Long): EquipmentSnapshot? = database[equipmentId]
            override fun updateEquipment(equipment: EquipmentSnapshot) {}
            override fun removeEquipment(equipmentId: Long) {}
        }
        
        val engine = ForgeEngine(mockProvider)
        
        // 注册一个耐久全满 200/200 的铁剑
        val sword = EquipmentSnapshot(
            id = 777L,
            templateId = "IRON_SWORD",
            equipmentClass = EquipmentClass.WEAPON,
            level = 0,
            durability = 200,
            maxDurability = 200,
            baseAttack = 20,
            baseDefense = 0,
            baseStat = 20,
            currentStat = 20,
            ownerId = null
        )
        database[777L] = sword
        
        // 执行修理应直接返回 true 且不扣资源
        assertTrue(engine.repairEquipment(777L))
    }

    @Test
    fun testHighConcurrencyStress() {
        val database = ConcurrentHashMap<Long, EquipmentSnapshot>()
        
        val mockProvider = object : ForgeDependencyProvider {
            override fun deductResources(resources: Map<String, Int>): Boolean {
                // 模拟资源充裕，总是能够成功扣减
                return true
            }
            override fun addResourcesToWarehouse(resources: Map<String, Int>) {}
            override fun addEquipmentToWarehouse(equipment: EquipmentSnapshot) {
                database[equipment.id] = equipment
            }
            override fun getEquipment(equipmentId: Long): EquipmentSnapshot? = database[equipmentId]
            override fun updateEquipment(equipment: EquipmentSnapshot) {
                database[equipment.id] = equipment
            }
            override fun removeEquipment(equipmentId: Long) {
                database.remove(equipmentId)
            }
        }
        
        val engine = ForgeEngine(mockProvider)
        
        // 1. 初始化 10 件装备
        for (i in 1..10) {
            val id = 2000L + i
            val eq = EquipmentSnapshot(
                id = id,
                templateId = if (i % 2 == 0) "IRON_SWORD" else "IRON_ARMOR",
                equipmentClass = if (i % 2 == 0) EquipmentClass.WEAPON else EquipmentClass.ARMOR,
                level = 0,
                durability = 80,
                maxDurability = 100,
                baseAttack = 10,
                baseDefense = 10,
                baseStat = 10,
                currentStat = 10,
                ownerId = null
            )
            database[id] = eq
        }
        
        val numThreads = 50
        val numRounds = 2000
        val executor = Executors.newFixedThreadPool(numThreads)
        val startLatch = CountDownLatch(1)
        val endLatch = CountDownLatch(numThreads)
        
        // 启动 50 个高并发任务轰炸
        for (t in 0 until numThreads) {
            executor.submit {
                try {
                    startLatch.await() // 等待所有线程就绪，然后同时开火
                    
                    val rand = ThreadLocalRandom.current()
                    for (r in 0 until numRounds / numThreads) {
                        val keys = database.keys.toList()
                        val randomEqId = if (keys.isNotEmpty()) keys[rand.nextInt(keys.size)] else null
                        
                        // 分组扮演不同的高并发 Workers 疯狂轰炸
                        when (t % 4) {
                            0 -> {
                                // 10 个 Workers 疯狂生产与排单推进
                                engine.queueForge("IRON_SWORD")
                                engine.processTick()
                            }
                            1 -> {
                                // 20 个 Workers 疯狂升级和强化
                                if (randomEqId != null) {
                                    if (rand.nextBoolean()) {
                                        engine.upgradeEquipment(randomEqId)
                                    } else {
                                        engine.upgradeTo(randomEqId, rand.nextInt(1, 11))
                                    }
                                }
                            }
                            2 -> {
                                // 10 个 Workers 疯狂折旧打磨修理
                                if (randomEqId != null) {
                                    // 模拟磨损
                                    database[randomEqId]?.let { eq ->
                                        if (eq.durability > 10) {
                                            database[randomEqId] = eq.copy(durability = eq.durability - 5)
                                        }
                                    }
                                    engine.repairEquipment(randomEqId)
                                }
                            }
                            3 -> {
                                // 10 个 Workers 疯狂拆解
                                if (randomEqId != null && database.size > 5) {
                                    engine.dismantleEquipment(randomEqId)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    fail("Concurrent worker failed with exception: ${e.message}")
                } finally {
                    endLatch.countDown()
                }
            }
        }
        
        // 枪声响起，同时开火
        startLatch.countDown()
        
        // 等待压测全部完成
        endLatch.await()
        executor.shutdown()
        
        // 验证最终状态仍然稳健、无死锁、无 ConcurrentModificationException
        assertTrue(database.isNotEmpty(), "Database should not be empty, since production also runs")
        for ((id, eq) in database) {
            assertNotNull(eq)
            assertEquals(id, eq.id)
            assertTrue(eq.level in 0..10)
            assertTrue(eq.durability in 0..eq.maxDurability)
        }
    }
}
