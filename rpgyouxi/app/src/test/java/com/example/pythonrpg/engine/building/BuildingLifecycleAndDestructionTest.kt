package com.example.pythonrpg.engine.building

import kotlin.test.*

class BuildingLifecycleAndDestructionTest {

    private var expandedId: Long = -1L
    private var additionalCap: Int = 0

    private val mockDependencyProvider = object : BuildingDependencyProvider {
        override fun notifyWarehouseCapacityExpanded(warehouseId: Long, additionalCapacity: Int) {
            expandedId = warehouseId
            additionalCap = additionalCapacity
        }
        override fun getTerrainType(x: Int, y: Int): String = "PLAINS"
    }

    @Test
    fun testConstructionAndUpgradeLifecycle() {
        val engine = BuildingEngine()
        engine.setDependencyProvider(mockDependencyProvider)
        
        val id = engine.startConstruction(BuildingType.LOCAL_STORAGE, 0, 0)
        assertNotNull(id)
        
        val snap = engine.getBuilding(id!!)!!
        assertEquals(BuildingState.UNDER_CONSTRUCTION, snap.state)
        assertEquals(0, snap.level)
        
        val maxTicks = snap.maxConstructionProgress
        
        // Tick until 1 before completion
        for (i in 0 until maxTicks - 1) {
            engine.processTick()
            assertEquals(BuildingState.UNDER_CONSTRUCTION, snap.state)
        }
        
        // Final tick
        engine.processTick()
        assertEquals(BuildingState.ACTIVE, snap.state)
        assertEquals(1, snap.level)
        
        // LOCAL_STORAGE finished, should notify warehouse capacity (500 * level)
        assertEquals(id, expandedId)
        assertEquals(500, additionalCap)
        
        // Reset observer
        expandedId = -1L
        additionalCap = 0
        
        // Upgrade
        assertTrue(engine.startUpgrade(id))
        assertEquals(BuildingState.UPGRADING, snap.state)
        
        val upgradeMax = snap.maxConstructionProgress * 2 // level 1 + 1 = 2 * base
        for (i in 0 until upgradeMax) {
            engine.processTick()
        }
        
        assertEquals(BuildingState.ACTIVE, snap.state)
        assertEquals(2, snap.level)
        assertEquals(id, expandedId)
        assertEquals(1000, additionalCap)
        
        // Destroy
        engine.destroyBuilding(id)
        assertEquals(BuildingState.DESTROYED, snap.state)
        
        // Tick should not advance destroyed buildings
        engine.processTick()
        assertEquals(BuildingState.DESTROYED, snap.state)
    }
}
