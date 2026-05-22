package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.*
import com.example.pythonrpg.engine.entity.EntityStateManager
import com.example.pythonrpg.engine.building.BuildingEngine
import com.example.pythonrpg.engine.building.BuildingSnapshot
import com.example.pythonrpg.engine.building.BuildingType
import com.example.pythonrpg.engine.building.BuildingState
import kotlin.test.*

class VillagerAutomationSystemTwilightAndNightTest {

    private lateinit var esm: EntityStateManager
    private lateinit var buildingEngine: BuildingEngine
    private lateinit var system: VillagerAutomationSystem
    private lateinit var actionProcessor: ActionProcessor

    @BeforeTest
    fun setup() {
        VillagerStateRegistry.clear()
        esm = EntityStateManager()
        buildingEngine = BuildingEngine()
        system = VillagerAutomationSystem(esm, buildingEngine, null)
        actionProcessor = ActionProcessor()
        actionProcessor.setAutomationSystem(system)
    }

    @Test
    fun testTwilightWorkingWithResourcesRedirectsToWarehouse() {
        // Register a cottage for the villager
        val cottage = BuildingSnapshot(
            buildingId = 10L, type = BuildingType.COTTAGE, x = 0, y = 0,
            level = 1, state = BuildingState.ACTIVE, constructionProgress = 10, maxConstructionProgress = 10
        )
        buildingEngine.registerBuilding(cottage)
        VillagerStateRegistry.cottageIds[1L] = 10L

        // Register a warehouse
        val warehouse = WarehouseSnapshot(
            id = 20L, coordinate = Coordinate(5, 5), capacity = 1000,
            inventory = emptyMap()
        )
        esm.registerWarehouse(warehouse)

        // Register the villager (backpack has resources)
        val villager = VillagerSnapshot(
            id = 1L, name = "V1", coordinate = Coordinate(3, 3),
            status = VillagerStatus.WORKING, job = "LUMBERJACK",
            targetX = null, targetY = null, isInjured = false,
            energy = 80, backpack = mapOf("WOOD" to 5), equippedTools = emptyMap()
        )
        esm.registerVillager(villager)

        VillagerStateRegistry.detailedStates[1L] = "WORKING"
        VillagerStateRegistry.originalJobs[1L] = "LUMBERJACK"

        // Execute twilight tick via actionProcessor
        val tickEvent = TickEvent(tickId = 1L, timestamp = 1000L, timeOfDay = TimePeriod.TWILIGHT)
        actionProcessor.processTick(tickEvent, PolicyModifiers(), WeatherModifiers())

        // Assert they are in DELIVERING state targeting the nearest warehouse (5,5)
        assertEquals("DELIVERING", VillagerStateRegistry.detailedStates[1L])
        val updated = esm.getVillager(1L)!!
        assertEquals(5, updated.targetX)
        assertEquals(5, updated.targetY)

        // Assert VillagerReturningHome event was published
        val events = actionProcessor.pollEvents()
        assertTrue(events.any { it is GameEvent.VillagerReturningHome && it.villagerId == 1L && it.reason == "TWILIGHT" })
    }

    @Test
    fun testTwilightWorkingWithoutResourcesRedirectsToCottage() {
        // Register a cottage
        val cottage = BuildingSnapshot(
            buildingId = 10L, type = BuildingType.COTTAGE, x = 1, y = 1,
            level = 1, state = BuildingState.ACTIVE, constructionProgress = 10, maxConstructionProgress = 10
        )
        buildingEngine.registerBuilding(cottage)
        VillagerStateRegistry.cottageIds[1L] = 10L

        // Register the villager (empty backpack)
        val villager = VillagerSnapshot(
            id = 1L, name = "V1", coordinate = Coordinate(3, 3),
            status = VillagerStatus.WORKING, job = "LUMBERJACK",
            targetX = null, targetY = null, isInjured = false,
            energy = 80, backpack = emptyMap(), equippedTools = emptyMap()
        )
        esm.registerVillager(villager)

        VillagerStateRegistry.detailedStates[1L] = "WORKING"
        VillagerStateRegistry.originalJobs[1L] = "LUMBERJACK"

        val tickEvent = TickEvent(tickId = 1L, timestamp = 1000L, timeOfDay = TimePeriod.TWILIGHT)
        actionProcessor.processTick(tickEvent, PolicyModifiers(), WeatherModifiers())

        // Assert they are in MOVING state targeting their cottage (1,1)
        assertEquals("MOVING", VillagerStateRegistry.detailedStates[1L])
        val updated = esm.getVillager(1L)!!
        assertEquals(1, updated.targetX)
        assertEquals(1, updated.targetY)

        val events = actionProcessor.pollEvents()
        assertTrue(events.any { it is GameEvent.VillagerReturningHome && it.villagerId == 1L && it.reason == "TWILIGHT" })
    }

    @Test
    fun testTwilightMovingDivertsToCottage() {
        // Register a cottage
        val cottage = BuildingSnapshot(
            buildingId = 10L, type = BuildingType.COTTAGE, x = 1, y = 1,
            level = 1, state = BuildingState.ACTIVE, constructionProgress = 10, maxConstructionProgress = 10
        )
        buildingEngine.registerBuilding(cottage)
        VillagerStateRegistry.cottageIds[1L] = 10L

        // Register the villager moving to a work site
        val villager = VillagerSnapshot(
            id = 1L, name = "V1", coordinate = Coordinate(3, 3),
            status = VillagerStatus.IDLE, job = "LUMBERJACK",
            targetX = 10, targetY = 10, isInjured = false,
            energy = 80, backpack = emptyMap(), equippedTools = emptyMap()
        )
        esm.registerVillager(villager)

        VillagerStateRegistry.detailedStates[1L] = "MOVING"
        VillagerStateRegistry.originalJobs[1L] = "LUMBERJACK"

        val tickEvent = TickEvent(tickId = 1L, timestamp = 1000L, timeOfDay = TimePeriod.TWILIGHT)
        actionProcessor.processTick(tickEvent, PolicyModifiers(), WeatherModifiers())

        // Assert they are still MOVING but target is forced back to cottage (1,1)
        assertEquals("MOVING", VillagerStateRegistry.detailedStates[1L])
        val updated = esm.getVillager(1L)!!
        assertEquals(1, updated.targetX)
        assertEquals(1, updated.targetY)

        val events = actionProcessor.pollEvents()
        assertTrue(events.any { it is GameEvent.VillagerReturningHome && it.villagerId == 1L && it.reason == "TWILIGHT" })
    }

    @Test
    fun testNightOutsideEnergyPenalty() {
        // Cottage at (0,0)
        val cottage = BuildingSnapshot(
            buildingId = 10L, type = BuildingType.COTTAGE, x = 0, y = 0,
            level = 1, state = BuildingState.ACTIVE, constructionProgress = 10, maxConstructionProgress = 10
        )
        buildingEngine.registerBuilding(cottage)
        VillagerStateRegistry.cottageIds[1L] = 10L

        // Villager resting at (5,5) - outside!
        val villager = VillagerSnapshot(
            id = 1L, name = "V1", coordinate = Coordinate(5, 5),
            status = VillagerStatus.IDLE, job = "LUMBERJACK",
            targetX = null, targetY = null, isInjured = false,
            energy = 50, backpack = emptyMap(), equippedTools = emptyMap()
        )
        esm.registerVillager(villager)

        VillagerStateRegistry.detailedStates[1L] = "RESTING"

        val tickEvent = TickEvent(tickId = 1L, timestamp = 1000L, timeOfDay = TimePeriod.NIGHT)
        actionProcessor.processTick(tickEvent, PolicyModifiers(), WeatherModifiers())

        // Base restore is 5. Penalty factor is 0.5. Energy restore = 5 * 0.5 = 2.5, which rounds/randoms to 2 or 3.
        val updated = esm.getVillager(1L)!!
        assertTrue(updated.energy in 52..53)
        assertEquals(VillagerStatus.IDLE, updated.status) // Not sleeping because outside
    }

    @Test
    fun testNightInsideEnergyBonusAndSleepingStatusSynchronizer() {
        // Cottage at (2,2)
        val cottage = BuildingSnapshot(
            buildingId = 10L, type = BuildingType.COTTAGE, x = 2, y = 2,
            level = 1, state = BuildingState.ACTIVE, constructionProgress = 10, maxConstructionProgress = 10
        )
        buildingEngine.registerBuilding(cottage)
        VillagerStateRegistry.cottageIds[1L] = 10L

        // Villager resting inside (2,2)
        val villager = VillagerSnapshot(
            id = 1L, name = "V1", coordinate = Coordinate(2, 2),
            status = VillagerStatus.IDLE, job = "LUMBERJACK",
            targetX = null, targetY = null, isInjured = false,
            energy = 50, backpack = emptyMap(), equippedTools = emptyMap()
        )
        esm.registerVillager(villager)

        VillagerStateRegistry.detailedStates[1L] = "RESTING"

        val tickEvent = TickEvent(tickId = 1L, timestamp = 1000L, timeOfDay = TimePeriod.NIGHT)
        actionProcessor.processTick(tickEvent, PolicyModifiers(), WeatherModifiers())

        // Base restore is 5. Night bonus factor inside is 2.0. Energy restore = 5 * 2 = 10.
        val updated = esm.getVillager(1L)!!
        assertEquals(60, updated.energy)
        // Night inside resting must sync status to SLEEPING
        assertEquals(VillagerStatus.SLEEPING, updated.status)
    }
}
