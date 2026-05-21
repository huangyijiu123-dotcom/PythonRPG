package com.example.pythonrpg.engine.building

import kotlin.test.*

class BuildingConfigAndStorageTest {
    @Test
    fun testConfigLoading() {
        val engine = BuildingEngine()
        val configs = engine.buildingConfigs
        assertNotNull(configs[BuildingType.COTTAGE])
        assertEquals(5, configs[BuildingType.COTTAGE]!!.maxLevel)
        assertTrue(configs[BuildingType.LUMBER_CAMP]!!.allowedTerrains.contains("FOREST"))
    }

    @Test
    fun testRegisterAndGetBuilding() {
        val engine = BuildingEngine()
        val snap = BuildingSnapshot(
            buildingId = 99L,
            type = BuildingType.MINE,
            x = 10,
            y = 20,
            level = 1,
            state = BuildingState.ACTIVE,
            constructionProgress = 0,
            maxConstructionProgress = 5
        )
        engine.registerBuilding(snap)
        
        val retrieved = engine.getBuilding(99L)
        assertNotNull(retrieved)
        assertEquals(BuildingType.MINE, retrieved!!.type)
        
        val atCoord = engine.getBuildingAt(10, 20)
        assertNotNull(atCoord)
        assertEquals(99L, atCoord!!.buildingId)

        val byType = engine.getBuildingsByType(BuildingType.MINE)
        assertEquals(1, byType.size)
        assertEquals(99L, byType[0].buildingId)
        
        engine.clear()
        assertNull(engine.getBuilding(99L))
    }
}
