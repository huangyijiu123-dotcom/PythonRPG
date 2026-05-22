package com.example.pythonrpg.engine.script

import com.example.pythonrpg.shared.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PythonProxySecurityTest {
    @Test
    fun testProxyPropertyReadOnlyReflection() {
        val proxyClasses = listOf(
            WarehouseProxy::class,
            AdventurerProxy::class,
            EventProxy::class,
            TileProxy::class,
            VillagerProxy::class,
            CaravanProxy::class,
            BuildingProxy::class
        )
        for (klass in proxyClasses) {
            for (method in klass.java.methods) {
                // If a property is 'var', Kotlin generates a public setter 'setPropName(value)'
                val isSetter = method.name.startsWith("set") && method.parameterCount == 1
                assertFalse(
                    isSetter,
                    "Setter method ${method.name} found on ${klass.java.simpleName}. All proxy properties must strictly be read-only (val) to prevent JVM write tampering!"
                )
            }
        }
    }

    @Test
    fun testSnakeCasePropertiesAndFillLevel() {
        val adventurerSnap = AdventurerSnapshot(
            id = 1L,
            name = "Hero",
            coordinate = Coordinate(5, 5),
            status = AdventurerStatus.IDLE,
            hp = 80,
            maxHp = 100,
            mp = 50,
            fatigue = 10,
            weaponEquipmentId = null,
            armorEquipmentId = null
        )
        val advProxy = AdventurerProxy(adventurerSnap)
        assertEquals(100, advProxy.max_hp)

        val warehouseSnap = WarehouseSnapshot(
            id = 200L,
            coordinate = Coordinate(1, 1),
            capacity = 1000,
            inventory = mapOf("WOOD" to 300, "STONE" to 200)
        )
        val whProxy = WarehouseProxy(warehouseSnap)
        assertEquals(0.5f, whProxy.fill_level)
    }
}
