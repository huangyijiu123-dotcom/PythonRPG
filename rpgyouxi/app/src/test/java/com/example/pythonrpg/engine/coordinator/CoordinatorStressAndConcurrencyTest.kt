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
import com.example.pythonrpg.shared.TimePeriod
import com.example.pythonrpg.shared.TickEvent
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CoordinatorStressAndConcurrencyTest {

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
    fun testMultiCrashImmunity() = runTest {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        // 设定多重崩坏条件
        every { buildingEngine.processTick() } throws NullPointerException("NPE in building")
        every { marketEngine.processTick() } throws IllegalStateException("ISE in market")

        coordinator.startLoop()
        runCurrent()

        // 触发第一个 tick
        testFlow.emit(TickEvent(1L, System.currentTimeMillis(), TimePeriod.MORNING))
        runCurrent()

        // 验证崩溃前和崩溃后的组件依然被依次调用了（虽然有崩溃，但由于在 collect 内，同一个大循环还是捕获到了异常并隔离了）
        // 注意：在同一个 try-catch 块中，如果中间 buildingEngine 崩溃了，整个 try-catch 块会中断本轮，跳转到 catch，
        // 那么在该 try 块中 buildingEngine 之后的模块在这一 tick 就不会被执行。
        // 这是预期的！而在下一 tick 时，我们依然可以从头开始重新执行！
        verify(exactly = 1) { weatherEngine.processTick() }
        verify(exactly = 1) { policyEngine.processTick() }
        verify(exactly = 1) { buildingEngine.processTick() }
        // 这一 tick 由于 buildingEngine 崩溃，后面的组件不会在此轮执行
        verify(exactly = 0) { marketEngine.processTick() }

        // 重置 mock 记录
        clearMocks(weatherEngine, policyEngine, buildingEngine, marketEngine)
        // 恢复正常
        every { buildingEngine.processTick() } returns Unit
        every { marketEngine.processTick() } returns Unit

        // 触发第二个 tick 验证大循环依然正常运转，新一轮拓扑更新从最顶部开启
        testFlow.emit(TickEvent(2L, System.currentTimeMillis(), TimePeriod.DAYTIME))
        runCurrent()

        verify(exactly = 1) { weatherEngine.processTick() }
        verify(exactly = 1) { policyEngine.processTick() }
        verify(exactly = 1) { buildingEngine.processTick() }
        verify(exactly = 1) { marketEngine.processTick() }

        coordinator.stopLoop()
    }

    @Test
    fun testHundredTicksNonBlockingStress() = runTest {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        coordinator.startLoop()

        // 密集向 tickFlow 发射 100 次 Tick 心跳
        val emitJob = launch {
            repeat(100) { i ->
                testFlow.emit(TickEvent(i.toLong(), System.currentTimeMillis(), TimePeriod.MORNING))
            }
        }

        // 同时前台由另一个协程并发启停 Coordinator 轰炸
        val bombJob = launch {
            repeat(10) {
                coordinator.stopLoop()
                coordinator.startLoop()
                delay(1L)
            }
        }

        joinAll(emitJob, bombJob)
        runCurrent()

        // 验证最后一次 stop 之后，能够正常 stop，且无挂起死锁，Job 引用干净
        coordinator.stopLoop()
        assertNull(coordinator.getActiveJob())
    }

    @Test
    fun testCrashRecoveryBetweenTicks() = runTest {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        // 设定崩溃
        every { buildingEngine.processTick() } throws RuntimeException("BOOM")

        coordinator.startLoop()
        runCurrent()

        // 触发第一个 tick -> 崩溃捕获
        testFlow.emit(TickEvent(1L, System.currentTimeMillis(), TimePeriod.MORNING))
        runCurrent()

        // 移除崩溃，恢复正常
        every { buildingEngine.processTick() } returns Unit

        // 触发第二个 tick -> 恢复执行
        testFlow.emit(TickEvent(2L, System.currentTimeMillis(), TimePeriod.DAYTIME))
        runCurrent()

        verify(exactly = 2) { buildingEngine.processTick() }

        coordinator.stopLoop()
    }

    @Test
    fun testHighFrequencyStartStopLeakFree() = runTest {
        val coordinator = GameLoopCoordinator(
            tickEngine, weatherEngine, policyEngine, buildingEngine,
            workshopEngine, forgeEngine, eventEngine, marketEngine,
            actionProcessor, entityState, mapData, this
        )

        // 循环 50 次 start/stop
        repeat(50) {
            coordinator.startLoop()
            assertNotNull(coordinator.getActiveJob())
            coordinator.stopLoop()
            assertNull(coordinator.getActiveJob(), "Active job must be null immediately after stop")
        }
    }
}
