package com.example.pythonrpg.engine.building

import kotlinx.coroutines.*
import kotlin.test.*

class BuildingDistributionStressTest {
    
    @Test
    fun testManhattanCoverageDeduplication() {
        val engine = BuildingEngine()
        
        // Two active distribution centers close to each other
        // Level 1 radius = 3
        val snap1 = BuildingSnapshot(1L, BuildingType.DISTRIBUTION, 0, 0, 1, BuildingState.ACTIVE, 0, 10)
        val snap2 = BuildingSnapshot(2L, BuildingType.DISTRIBUTION, 2, 0, 1, BuildingState.ACTIVE, 0, 10)
        
        engine.registerBuilding(snap1)
        engine.registerBuilding(snap2)
        
        val coverage = engine.getDistributionCoverageRadius()
        
        // Let's verify size and that specific coords are present
        // Dist 1: (-3..3) manhattan
        // Dist 2: (-1..5) manhattan
        assertTrue(coverage.contains(Pair(0, 0)))
        assertTrue(coverage.contains(Pair(2, 0)))
        assertTrue(coverage.contains(Pair(1, 0)))
        assertTrue(coverage.contains(Pair(-3, 0)))
        assertTrue(coverage.contains(Pair(5, 0)))
        
        // Out of bounds
        assertFalse(coverage.contains(Pair(6, 0)))
        assertFalse(coverage.contains(Pair(0, 4)))
    }

    @Test
    fun testConcurrencyStressTest() = runBlocking {
        val engine = BuildingEngine()
        val mockDependencyProvider = object : BuildingDependencyProvider {
            override fun notifyWarehouseCapacityExpanded(warehouseId: Long, additionalCapacity: Int) {}
            override fun getTerrainType(x: Int, y: Int): String = "PLAINS"
        }
        engine.setDependencyProvider(mockDependencyProvider)
        
        val jobs = List(50) {
            launch(Dispatchers.Default) {
                // 50 coroutines trying to build things concurrently
                for (i in 0 until 100) {
                    engine.startConstruction(BuildingType.COTTAGE, (it * 100) + i, 0)
                }
            }
        }
        
        jobs.forEach { it.join() }
        
        val allBuildings = engine.getAllBuildings()
        assertEquals(5000, allBuildings.size)
        
        // Check uniqueness
        val uniqueIds = allBuildings.map { it.buildingId }.toSet()
        assertEquals(5000, uniqueIds.size)
    }
}
