package com.example.pythonrpg.engine.building

import kotlin.test.*

class BuildingPipelineValidationTest {
    
    private val mockDependencyProvider = object : BuildingDependencyProvider {
        override fun notifyWarehouseCapacityExpanded(warehouseId: Long, additionalCapacity: Int) {}
        override fun getTerrainType(x: Int, y: Int): String {
            return if (x == 1 && y == 1) "FOREST" else "WATER"
        }
    }

    @Test
    fun testStartConstructionTerrainValidation() {
        val engine = BuildingEngine()
        engine.setDependencyProvider(mockDependencyProvider)
        
        // COTTAGE only allows PLAINS, so even in FOREST it fails
        val cottageId = engine.startConstruction(BuildingType.COTTAGE, 1, 1)
        assertNull(cottageId, "Cottage should not be built in FOREST")
        
        // LUMBER_CAMP allows FOREST
        val lumberId = engine.startConstruction(BuildingType.LUMBER_CAMP, 1, 1)
        assertNotNull(lumberId, "Lumber camp should be built in FOREST")
        
        // Try on WATER
        val waterId = engine.startConstruction(BuildingType.LUMBER_CAMP, 2, 2)
        assertNull(waterId, "Lumber camp should not be built in WATER")
    }
    
    @Test
    fun testStartConstructionOverlapValidation() {
        val engine = BuildingEngine()
        engine.setDependencyProvider(mockDependencyProvider)
        
        val lumberId = engine.startConstruction(BuildingType.LUMBER_CAMP, 1, 1)
        assertNotNull(lumberId)
        
        // Try to build on top of it
        val overlapId = engine.startConstruction(BuildingType.LUMBER_CAMP, 1, 1)
        assertNull(overlapId, "Should not allow overlap")
        
        // Destroy and build again
        engine.destroyBuilding(lumberId!!)
        val newId = engine.startConstruction(BuildingType.LUMBER_CAMP, 1, 1)
        assertNotNull(newId, "Should allow building on DESTROYED ruins")
    }

    @Test
    fun testMaxLevelValidation() {
        val engine = BuildingEngine()
        val snap = BuildingSnapshot(
            buildingId = 1L,
            type = BuildingType.LUMBER_CAMP,
            x = 1, y = 1,
            level = 5,
            state = BuildingState.ACTIVE,
            constructionProgress = 0,
            maxConstructionProgress = 4
        )
        engine.registerBuilding(snap)
        
        val canUpgrade = engine.startUpgrade(1L)
        assertFalse(canUpgrade, "Should not upgrade past max level 5")
    }
}
