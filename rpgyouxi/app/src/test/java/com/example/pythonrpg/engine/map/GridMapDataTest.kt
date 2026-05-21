package com.example.pythonrpg.engine.map

import com.example.pythonrpg.shared.Coordinate
import com.example.pythonrpg.shared.ExploreStatus
import com.example.pythonrpg.shared.TileData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.*

class GridMapDataTest {

    @Test
    fun testBasicReadWriteAndClear() {
        val grid = GridMapData()
        assertEquals(0, grid.size())

        val tile = TileData(
            coordinate = Coordinate(0, 0),
            terrainTypeId = "PLAINS",
            exploreStatus = ExploreStatus.UNEXPLORED,
            isBossLocked = false,
            hasMonster = false,
            buildingId = null
        )

        grid.setTile(tile)
        assertEquals(1, grid.size())

        val retrieved = grid.getTile(0, 0)
        assertNotNull(retrieved)
        assertEquals("PLAINS", retrieved.terrainTypeId)
        assertEquals(ExploreStatus.UNEXPLORED, retrieved.exploreStatus)

        grid.clear()
        assertEquals(0, grid.size())
        assertNull(grid.getTile(0, 0))
    }

    @Test
    fun testAtomicPropertyUpdates() {
        val grid = GridMapData()
        val coord = Coordinate(3, 4)
        val tile = TileData(
            coordinate = coord,
            terrainTypeId = "FOREST",
            exploreStatus = ExploreStatus.UNEXPLORED,
            isBossLocked = false,
            hasMonster = false,
            buildingId = null
        )
        grid.setTile(tile)

        // 1. Update explore status
        assertTrue(grid.updateExploreStatus(coord, ExploreStatus.VISIBLE_UNEXPLORED))
        assertEquals(ExploreStatus.VISIBLE_UNEXPLORED, grid.getTile(3, 4)?.exploreStatus)
        // Repeat update should return false
        assertFalse(grid.updateExploreStatus(coord, ExploreStatus.VISIBLE_UNEXPLORED))

        // 2. Update monster presence
        assertTrue(grid.updateMonsterPresence(coord, true))
        assertTrue(grid.getTile(3, 4)?.hasMonster == true)
        assertFalse(grid.updateMonsterPresence(coord, true))

        // 3. Update building
        assertTrue(grid.updateBuilding(coord, 999L))
        assertEquals(999L, grid.getTile(3, 4)?.buildingId)
        assertFalse(grid.updateBuilding(coord, 999L))
    }

    @Test
    fun testCrossUnlockTileCascade() {
        val grid = GridMapData()
        val center = Coordinate(0, 0)

        // Setup a 3x3 region of grid tiles
        for (x in -1..1) {
            for (y in -1..1) {
                grid.setTile(
                    TileData(
                        coordinate = Coordinate(x, y),
                        terrainTypeId = "PLAINS",
                        exploreStatus = if (x == 0 && y == 0) ExploreStatus.VISIBLE_UNEXPLORED else ExploreStatus.UNEXPLORED,
                        isBossLocked = false,
                        hasMonster = false,
                        buildingId = null
                    )
                )
            }
        }

        // Unlock the center VISIBLE_UNEXPLORED tile
        val unlockedNeighbors = grid.unlockTile(center)

        // Central tile must be fully EXPLORED
        assertEquals(ExploreStatus.EXPLORED, grid.getTile(0, 0)?.exploreStatus)

        // Neighbors (up, down, left, right) must cascade-update to VISIBLE_UNEXPLORED
        assertEquals(ExploreStatus.VISIBLE_UNEXPLORED, grid.getTile(0, 1)?.exploreStatus)
        assertEquals(ExploreStatus.VISIBLE_UNEXPLORED, grid.getTile(0, -1)?.exploreStatus)
        assertEquals(ExploreStatus.VISIBLE_UNEXPLORED, grid.getTile(1, 0)?.exploreStatus)
        assertEquals(ExploreStatus.VISIBLE_UNEXPLORED, grid.getTile(-1, 0)?.exploreStatus)

        // Diagonal neighbors must remain UNEXPLORED
        assertEquals(ExploreStatus.UNEXPLORED, grid.getTile(1, 1)?.exploreStatus)
        assertEquals(ExploreStatus.UNEXPLORED, grid.getTile(-1, -1)?.exploreStatus)

        // Unlocked neighbors returned must be exactly 4 coordinates
        assertEquals(4, unlockedNeighbors.size)
        assertTrue(unlockedNeighbors.contains(Coordinate(0, 1)))
        assertTrue(unlockedNeighbors.contains(Coordinate(0, -1)))
        assertTrue(unlockedNeighbors.contains(Coordinate(1, 0)))
        assertTrue(unlockedNeighbors.contains(Coordinate(-1, 0)))
    }

    @Test
    fun testRegionQueryAndFiltering() {
        val grid = GridMapData()
        for (x in 0..5) {
            for (y in 0..5) {
                grid.setTile(
                    TileData(
                        coordinate = Coordinate(x, y),
                        terrainTypeId = if ((x + y) % 2 == 0) "PLAINS" else "FOREST",
                        exploreStatus = ExploreStatus.EXPLORED,
                        isBossLocked = false,
                        hasMonster = x == y,
                        buildingId = null
                    )
                )
            }
        }

        // Get region from (1, 1) to (3, 3) (total 3x3 = 9 tiles)
        val region = grid.getTilesInRegion(1, 3, 1, 3)
        assertEquals(9, region.size)

        // Filter tiles with monsters
        val monsters = grid.filterTiles { it.hasMonster }
        // Should have x=y tiles: (0,0), (1,1), (2,2), (3,3), (4,4), (5,5) -> 6 tiles
        assertEquals(6, monsters.size)
    }

    @Test
    fun testGsonSerializationAndNumericalPolymorphism() {
        val grid = GridMapData()
        val customAttributes = mapOf(
            "gold" to 500,
            "wood" to 20,
            "ratio" to 0.75f
        )
        val tile = TileData(
            coordinate = Coordinate(5, 5),
            terrainTypeId = "SWAMP",
            exploreStatus = ExploreStatus.EXPLORED,
            isBossLocked = false,
            hasMonster = false,
            buildingId = 12345L,
            customAttributes = customAttributes
        )
        grid.setTile(tile)

        // Export
        val json = grid.exportToJson()
        assertNotNull(json)
        assertTrue(json.contains("SWAMP"))

        // Import to a new grid
        val newGrid = GridMapData()
        newGrid.importFromJson(json)

        assertEquals(1, newGrid.size())
        val restored = newGrid.getTile(5, 5)
        assertNotNull(restored)
        assertEquals("SWAMP", restored.terrainTypeId)
        assertEquals(12345L, restored.buildingId)

        // Check customAttributes numbers (Gson parses numbers as Doubles by default)
        val restoredGold = restored.customAttributes["gold"]
        assertNotNull(restoredGold)
        assertTrue(restoredGold is Number)
        // Safely parse it as an Int
        assertEquals(500, (restoredGold as Number).toInt())

        val restoredWood = restored.customAttributes["wood"]
        assertEquals(20, (restoredWood as Number).toInt())

        val restoredRatio = restored.customAttributes["ratio"]
        assertEquals(0.75f, (restoredRatio as Number).toFloat())
    }

    @Test
    fun testConcurrentBatchUpdates() = runBlocking {
        val grid = GridMapData()
        val list1 = (0..9).map {
            TileData(Coordinate(it, 0), "PLAINS", ExploreStatus.UNEXPLORED, false, false, null)
        }
        val list2 = (0..9).map {
            TileData(Coordinate(it, 1), "FOREST", ExploreStatus.UNEXPLORED, false, false, null)
        }

        // Run batch updates concurrently using dispatchers
        val job1 = async(Dispatchers.Default) { grid.batchUpdateTiles(list1) }
        val job2 = async(Dispatchers.Default) { grid.batchUpdateTiles(list2) }

        job1.await()
        job2.await()

        assertEquals(20, grid.size())
        assertEquals("PLAINS", grid.getTile(5, 0)?.terrainTypeId)
        assertEquals("FOREST", grid.getTile(5, 1)?.terrainTypeId)
    }

    @Test
    fun testDirtyTilesFlowAndBackpressure() = runTest {
        val grid = GridMapData()
        val coord1 = Coordinate(1, 1)
        val coord2 = Coordinate(2, 2)

        grid.setTile(TileData(coord1, "PLAINS", ExploreStatus.UNEXPLORED, false, false, null))
        grid.setTile(TileData(coord2, "FOREST", ExploreStatus.UNEXPLORED, false, false, null))

        // In runTest, we launch subscriber first
        val emittedList = mutableListOf<List<Coordinate>>()
        val collectJob = launch {
            grid.dirtyTilesFlow.collect {
                emittedList.add(it)
            }
        }
        // Let flow subscriber register
        testScheduler.runCurrent()

        // Emit
        grid.emitDirtyTiles()
        testScheduler.runCurrent()

        assertEquals(1, emittedList.size)
        val coords = emittedList[0]
        assertEquals(2, coords.size)
        assertTrue(coords.contains(coord1))
        assertTrue(coords.contains(coord2))

        // High frequency check (replay/buffer-overflow DROP_OLDEST check)
        repeat(50) { i ->
            grid.setTile(TileData(Coordinate(i, 5), "PLAINS", ExploreStatus.UNEXPLORED, false, false, null))
        }
        grid.emitDirtyTiles()
        testScheduler.runCurrent()

        // Clean up subscription
        collectJob.cancel()
    }
}
