package com.example.pythonrpg.engine.forge

import com.example.pythonrpg.shared.EquipmentSnapshot
import com.example.pythonrpg.shared.EquipmentClass
import kotlin.test.*
import java.util.concurrent.ConcurrentHashMap

class ForgeRefinementUpgradeTest {

    @Test
    fun testUpgradeFailureNoPenaltyLevel3To4() {
        val database = ConcurrentHashMap<Long, EquipmentSnapshot>()
        
        val mockProvider = object : ForgeDependencyProvider {
            override fun deductResources(resources: Map<String, Int>): Boolean = true
            override fun addResourcesToWarehouse(resources: Map<String, Int>) {}
            override fun addEquipmentToWarehouse(equipment: EquipmentSnapshot) {}
            override fun getEquipment(equipmentId: Long): EquipmentSnapshot? = database[equipmentId]
            override fun updateEquipment(equipment: EquipmentSnapshot) {
                database[equipment.id] = equipment
            }
            override fun removeEquipment(equipmentId: Long) {}
        }
        
        // 注册一柄 +3 级、20 基础攻击的铁剑，攻击为 20 * (1.0 + 3 * 0.1) = 26
        val sword = EquipmentSnapshot(
            id = 777L,
            templateId = "IRON_SWORD",
            equipmentClass = EquipmentClass.WEAPON,
            level = 3,
            durability = 100,
            maxDurability = 100,
            baseAttack = 20,
            baseDefense = 0,
            baseStat = 20,
            currentStat = 26,
            ownerId = null
        )
        database[777L] = sword
        
        // 摇号生成器返回 1.0 (百分之百失败)
        val engine = ForgeEngine(mockProvider, randomGenerator = { 1.0 })
        
        // 尝试强化
        assertFalse(engine.upgradeEquipment(777L))
        
        // 验证等级和属性保持不变（+3 升级失败无惩罚）
        val updatedSword = database[777L]!!
        assertEquals(3, updatedSword.level)
        assertEquals(26, updatedSword.currentStat)
    }

    @Test
    fun testUpgradeFailureWithPenaltyLevel4To5() {
        val database = ConcurrentHashMap<Long, EquipmentSnapshot>()
        
        val mockProvider = object : ForgeDependencyProvider {
            override fun deductResources(resources: Map<String, Int>): Boolean = true
            override fun addResourcesToWarehouse(resources: Map<String, Int>) {}
            override fun addEquipmentToWarehouse(equipment: EquipmentSnapshot) {}
            override fun getEquipment(equipmentId: Long): EquipmentSnapshot? = database[equipmentId]
            override fun updateEquipment(equipment: EquipmentSnapshot) {
                database[equipment.id] = equipment
            }
            override fun removeEquipment(equipmentId: Long) {}
        }
        
        // 注册一柄 +4 级、20 基础攻击的铁剑，攻击为 20 * (1.0 + 4 * 0.1) = 28
        val sword = EquipmentSnapshot(
            id = 777L,
            templateId = "IRON_SWORD",
            equipmentClass = EquipmentClass.WEAPON,
            level = 4,
            durability = 100,
            maxDurability = 100,
            baseAttack = 20,
            baseDefense = 0,
            baseStat = 20,
            currentStat = 28,
            ownerId = null
        )
        database[777L] = sword
        
        // 摇号生成器返回 1.0 (百分之百失败)
        val engine = ForgeEngine(mockProvider, randomGenerator = { 1.0 })
        
        // 尝试强化
        assertFalse(engine.upgradeEquipment(777L))
        
        // 验证等级降级至 +3，属性回滚至 26
        val updatedSword = database[777L]!!
        assertEquals(3, updatedSword.level)
        assertEquals(26, updatedSword.currentStat)
    }

    @Test
    fun testUpgradeToResourceExhaustionStop() {
        val database = ConcurrentHashMap<Long, EquipmentSnapshot>()
        var deductCallsCount = 0
        
        val mockProvider = object : ForgeDependencyProvider {
            override fun deductResources(resources: Map<String, Int>): Boolean {
                deductCallsCount++
                // 仅允许第一次扣除成功，第二次资源枯竭
                return deductCallsCount == 1
            }
            override fun addResourcesToWarehouse(resources: Map<String, Int>) {}
            override fun addEquipmentToWarehouse(equipment: EquipmentSnapshot) {}
            override fun getEquipment(equipmentId: Long): EquipmentSnapshot? = database[equipmentId]
            override fun updateEquipment(equipment: EquipmentSnapshot) {
                database[equipment.id] = equipment
            }
            override fun removeEquipment(equipmentId: Long) {}
        }
        
        val eqId = 555L
        // 注册一柄 +0 级、10 基础攻击的铁剑
        val sword = EquipmentSnapshot(
            id = eqId,
            templateId = "IRON_SWORD",
            equipmentClass = EquipmentClass.WEAPON,
            level = 0,
            durability = 100,
            maxDurability = 100,
            baseAttack = 10,
            baseDefense = 0,
            baseStat = 10,
            currentStat = 10,
            ownerId = null
        )
        database[eqId] = sword
        
        // 摇号生成器返回 0.0 (百分之百成功)
        val engine = ForgeEngine(mockProvider, randomGenerator = { 0.0 })
        
        // 自动安全强化直奔 +10
        val finalLevel = engine.upgradeTo(eqId, 10)
        
        // 第一次可以通过（升至 +1），第二次枯竭。断言最终等级为 1
        assertEquals(1, finalLevel)
        assertEquals(1, database[eqId]!!.level)
        assertEquals(2, deductCallsCount) // 第一次成功，第二次失败，总共调用 2 次扣除
    }
}
