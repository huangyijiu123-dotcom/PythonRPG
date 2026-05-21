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
import com.example.pythonrpg.shared.PolicyModifiers
import com.example.pythonrpg.shared.TimePeriod
import com.example.pythonrpg.shared.TickEvent
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CoordinatorStrictPipelineTest {

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
    fun testStrictTenModulePipelineOrder() = runTest {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        val modifiers = PolicyModifiers(combatAttackMultiplier = 1.5f)
        every { policyEngine.getModifiers() } returns modifiers

        coordinator.startLoop()
        runCurrent()

        // 触发一个 tick
        testFlow.emit(TickEvent(1L, System.currentTimeMillis(), TimePeriod.MORNING))
        runCurrent()

        // 使用 MockK's verifyOrder 强验证 10 个模块的绝对串行拓扑执行顺序
        verifyOrder {
            weatherEngine.processTick()
            policyEngine.processTick()
            buildingEngine.processTick()
            workshopEngine.processTick()
            forgeEngine.processTick()
            eventEngine.processTick()
            marketEngine.processTick()
            policyEngine.getModifiers()
            actionProcessor.processTick(any(), modifiers, com.example.pythonrpg.shared.WeatherModifiers())
            entityState.emitStateDiff()
            mapData.emitDirtyTiles()
        }

        coordinator.stopLoop()
    }

    @Test
    fun testExceptionIsolationAndFlowSurvival() = runTest {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        // 故意让 buildingEngine 触发崩溃
        every { buildingEngine.processTick() } throws RuntimeException("Subsystem Explosion")

        coordinator.startLoop()
        runCurrent()

        // 触发第一个 tick
        testFlow.emit(TickEvent(1L, System.currentTimeMillis(), TimePeriod.MORNING))
        runCurrent()

        // 验证虽然崩溃，但其他模块仍执行（至崩溃前的模块被调用了，而防爆网捕获了异常且没有向外泄露）
        verify(exactly = 1) { weatherEngine.processTick() }
        verify(exactly = 1) { policyEngine.processTick() }

        // 重置 mock 记录
        clearMocks(weatherEngine, policyEngine, buildingEngine)
        // 移除崩溃
        every { buildingEngine.processTick() } returns Unit

        // 触发第二个 tick 验证 Flow 依然活着，并未因为异常断流
        testFlow.emit(TickEvent(2L, System.currentTimeMillis(), TimePeriod.DAYTIME))
        runCurrent()

        verify(exactly = 1) { weatherEngine.processTick() }
        verify(exactly = 1) { policyEngine.processTick() }
        verify(exactly = 1) { buildingEngine.processTick() }

        coordinator.stopLoop()
    }

    @Test
    fun testActionProcessorReceivesLatestModifiers() = runTest {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        val customModifiers = PolicyModifiers(foodConsumptionMultiplier = 0.8f)
        every { policyEngine.getModifiers() } returns customModifiers

        coordinator.startLoop()
        runCurrent()

        testFlow.emit(TickEvent(1L, System.currentTimeMillis(), TimePeriod.MORNING))
        runCurrent()

        verify(exactly = 1) { actionProcessor.processTick(any(), customModifiers, any()) }

        coordinator.stopLoop()
    }

    @Test
    fun testJobRemainsActiveAfterException() = runTest {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        every { buildingEngine.processTick() } throws RuntimeException("Subsystem Crash")

        coordinator.startLoop()
        runCurrent()
        val job = coordinator.getActiveJob()
        assertNotNull(job)
        assertTrue(job.isActive)

        testFlow.emit(TickEvent(1L, System.currentTimeMillis(), TimePeriod.MORNING))
        runCurrent()

        // 捕获异常后，验证 Job 依然存活，没有被干掉
        assertTrue(job.isActive, "Coroutine job must remain active despite subsystem exception")

        coordinator.stopLoop()
    }
}
