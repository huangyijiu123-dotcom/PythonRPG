package com.example.pythonrpg.engine.forge

import com.example.pythonrpg.shared.EquipmentSnapshot
import com.example.pythonrpg.shared.EquipmentClass
import kotlin.test.*
import java.util.concurrent.ConcurrentHashMap

class ForgeAndDismantleProcessTest {

    @Test
    fun testQueueForgeAndSingleSlotProgress() {
        val database = ConcurrentHashMap<Long, EquipmentSnapshot>()
        var deductedCosts: Map<String, Int>? = null
        
        val mockProvider = object : ForgeDependencyProvider {
            override fun deductResources(resources: Map<String, Int>): Boolean {
                deductedCosts = resources
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
        
        // 使用符合测试要求的自定义配方：攻击 20，满耐久 200，所需 ticks 5
        val customTemplates = mapOf(
            "IRON_SWORD" to EquipmentRecipe(
                equipmentClass = "WEAPON",
                woodCost = 15,
                stoneCost = 0,
                ironCost = 8,
                goldCost = 0,
                obsidianCost = 0,
                maxDurability = 200,
                baseStat = 20,
                requiredTicks = 5
            )
        )
        
        val engine = ForgeEngine(mockProvider, customTemplates)
        
        // 排单生产铁剑
        assertTrue(engine.queueForge("IRON_SWORD"))
        assertEquals(1, engine.getActiveTasks().size)
        
        // 验证扣料字典
        assertNotNull(deductedCosts)
        assertEquals(15, deductedCosts!!["WOOD"])
        assertEquals(8, deductedCosts!!["IRON_ORE"])
        
        // 连续调用 5 次 processTick()
        for (i in 1..5) {
            engine.processTick()
        }
        
        // 第 5 次心跳完工交付，且任务队列清空
        assertTrue(engine.getActiveTasks().isEmpty())
        
        // 验证装备上架
        assertEquals(1, database.size)
        val sword = database.values.first()
        assertEquals(1001L, sword.id)
        assertEquals("IRON_SWORD", sword.templateId)
        assertEquals(EquipmentClass.WEAPON, sword.equipmentClass)
        assertEquals(0, sword.level)
        assertEquals(20, sword.baseAttack)
        assertEquals(20, sword.baseStat)
        assertEquals(20, sword.currentStat)
        assertEquals(200, sword.durability)
        assertEquals(200, sword.maxDurability)
    }

    @Test
    fun testDismantleEquipmentRefundFloor() {
        val database = ConcurrentHashMap<Long, EquipmentSnapshot>()
        var refundedResources: Map<String, Int>? = null
        var removeCalledId: Long? = null
        
        val mockProvider = object : ForgeDependencyProvider {
            override fun deductResources(resources: Map<String, Int>): Boolean = true
            override fun addResourcesToWarehouse(resources: Map<String, Int>) {
                refundedResources = resources
            }
            override fun addEquipmentToWarehouse(equipment: EquipmentSnapshot) {
                database[equipment.id] = equipment
            }
            override fun getEquipment(equipmentId: Long): EquipmentSnapshot? = database[equipmentId]
            override fun updateEquipment(equipment: EquipmentSnapshot) {
                database[equipment.id] = equipment
            }
            override fun removeEquipment(equipmentId: Long) {
                removeCalledId = equipmentId
                database.remove(equipmentId)
            }
        }
        
        // 自定义制造配方用于测试拆解：铁矿 15，木材 10
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
        
        // 注册一个 888L 出厂铁剑
        val initialSword = EquipmentSnapshot(
            id = 888L,
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
        database[888L] = initialSword
        
        // 执行拆解
        assertTrue(engine.dismantleEquipment(888L))
        
        // 验证物理删除
        assertEquals(888L, removeCalledId)
        assertTrue(database.isEmpty())
        
        // 验证退料资源，公式为 Math.floor(cost * 0.5).toInt()
        assertNotNull(refundedResources)
        assertEquals(7, refundedResources!!["IRON_ORE"]) // floor(15 * 0.5) = 7
        assertEquals(5, refundedResources!!["WOOD"])     // floor(10 * 0.5) = 5
    }
}
