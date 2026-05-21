package com.example.pythonrpg.engine.coordinator

import com.example.pythonrpg.engine.tick.TickEngine
import com.example.pythonrpg.engine.weather.WeatherEngine
import com.example.pythonrpg.engine.policy.PolicyEngine
import com.example.pythonrpg.engine.building.BuildingEngine
import com.example.pythonrpg.engine.workshop.WorkshopEngine
import com.example.pythonrpg.engine.forge.ForgeEngine
import com.example.pythonrpg.engine.event.EventEngine
import com.example.pythonrpg.engine.market.MarketEngine
import com.example.pythonrpg.engine.action.ActionProcessor
import com.example.pythonrpg.engine.entity.EntityStateManager
import com.example.pythonrpg.engine.map.GridMapData
import com.example.pythonrpg.shared.TickEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CoordinatorLifecycleTest {

    private val tickEngine = mockk<TickEngine>(relaxed = true)
    private val weatherEngine = mockk<WeatherEngine>(relaxed = true)
    private val policyEngine = mockk<PolicyEngine>(relaxed = true)
    private val buildingEngine = mockk<BuildingEngine>(relaxed = true)
    private val workshopEngine = mockk<WorkshopEngine>(relaxed = true)
    private val forgeEngine = mockk<ForgeEngine>(relaxed = true)
    private val eventEngine = mockk<EventEngine>(relaxed = true)
    private val marketEngine = mockk<MarketEngine>(relaxed = true)
    private val actionProcessor = mockk<ActionProcessor>(relaxed = true)
    private val entityState = mockk<EntityStateManager>(relaxed = true)
    private val mapData = mockk<GridMapData>(relaxed = true)

    private val testFlow = MutableSharedFlow<TickEvent>()

    init {
        every { tickEngine.tickFlow } returns testFlow
    }

    @Test
    fun testIdempotentStartLoop() = runTest {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        // 连续调用三次 startLoop()
        coordinator.startLoop()
        val firstJob = coordinator.getActiveJob()
        assertNotNull(firstJob)

        coordinator.startLoop()
        val secondJob = coordinator.getActiveJob()
        assertSame(firstJob, secondJob, "Job reference should be identical (idempotence)")

        coordinator.startLoop()
        val thirdJob = coordinator.getActiveJob()
        assertSame(firstJob, thirdJob, "Job reference should be identical (idempotence)")

        coordinator.stopLoop()
    }

    @Test
    fun testSmoothLifecycleExit() = runTest {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        coordinator.startLoop()
        val job = coordinator.getActiveJob()
        assertNotNull(job)
        assertTrue(job.isActive)

        coordinator.stopLoop()
        assertNull(coordinator.getActiveJob(), "Active job reference should be reset to null")
        assertTrue(job.isCancelled, "The collected job should be physically cancelled")
    }

    @Test
    fun testStopAndRestart() = runTest {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        // Start -> Stop
        coordinator.startLoop()
        assertNotNull(coordinator.getActiveJob())
        coordinator.stopLoop()
        assertNull(coordinator.getActiveJob())

        // Restart
        coordinator.startLoop()
        assertNotNull(coordinator.getActiveJob())
        coordinator.stopLoop()
    }

    @Test
    fun testCASConcurrencyPrevention() = runBlocking {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        val jobs = mutableListOf<Deferred<Unit>>()
        // 10个并发协程同时调用 startLoop
        repeat(10) {
            jobs.add(async(Dispatchers.Default) {
                coordinator.startLoop()
            })
        }
        jobs.awaitAll()

        val activeJob = coordinator.getActiveJob()
        assertNotNull(activeJob)
        assertTrue(activeJob.isActive)

        coordinator.stopLoop()
    }
}
