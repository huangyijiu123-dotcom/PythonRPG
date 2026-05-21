package com.example.pythonrpg.engine.tick

import com.example.pythonrpg.shared.TimePeriod
import com.example.pythonrpg.shared.TickEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TickEngineTest {

    @Test
    fun testInitialDefaultBehavior() = runTest {
        val engine = TickEngine(this)
        assertEquals(0L, engine.getCurrentTickId())
        assertFalse(engine.isEnginePaused())
        assertFalse(engine.isLogicPaused())
    }

    @Test
    fun testLogicClockAndPeriodClassification() = runTest {
        val engine = TickEngine(this)
        engine.setGameSpeed(100L) // 缩短间隔到 100ms 方便测试

        val receivedEvents = mutableListOf<TickEvent>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.tickFlow.collect {
                receivedEvents.add(it)
            }
        }

        engine.start(0L)

        // 推进时间 2400ms，应当触发 24 个 tick
        advanceTimeBy(2450L)

        assertEquals(24, receivedEvents.size)

        // 验证每个 tick 的时间段划分
        for (event in receivedEvents) {
            val tickId = event.tickId
            val mod = tickId % 24
            val expectedPeriod = when {
                mod == 1L -> TimePeriod.MORNING
                mod in 2L..14L -> TimePeriod.DAYTIME
                mod in 15L..16L -> TimePeriod.TWILIGHT
                else -> TimePeriod.NIGHT
            }
            assertEquals(expectedPeriod, event.timeOfDay, "Tick $tickId period mismatch")
        }

        collectJob.cancel()
        engine.stop()
    }

    @Test
    fun testPauseAndResumeEngine() = runTest {
        val engine = TickEngine(this)
        engine.setGameSpeed(100L)

        val logicTicks = mutableListOf<TickEvent>()
        val realTicks = mutableListOf<Long>()

        val collectLogicJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.tickFlow.collect { logicTicks.add(it) }
        }
        val collectRealJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.realTickFlow.collect { realTicks.add(it) }
        }

        engine.start(0L)

        // 推进 250ms -> 应触发 2 个逻辑 tick (100ms, 200ms) 和 5 个高频 real tick (50ms, 100ms, 150ms, 200ms, 250ms)
        advanceTimeBy(260L)
        val initialLogicCount = logicTicks.size
        val initialRealCount = realTicks.size
        assertTrue(initialLogicCount >= 2)
        assertTrue(initialRealCount >= 5)

        // 暂停引擎
        engine.pauseEngine()
        assertTrue(engine.isEnginePaused())

        // 推进 300ms -> real tick 和 logic tick 均不应增加
        advanceTimeBy(300L)
        assertEquals(initialLogicCount, logicTicks.size)
        assertEquals(initialRealCount, realTicks.size)

        // 恢复引擎
        engine.resumeEngine()
        assertFalse(engine.isEnginePaused())

        // 推进 200ms -> 应继续推进
        advanceTimeBy(210L)
        assertTrue(logicTicks.size > initialLogicCount)
        assertTrue(realTicks.size > initialRealCount)

        collectLogicJob.cancel()
        collectRealJob.cancel()
        engine.stop()
    }

    @Test
    fun testPauseAndResumeLogicOnly() = runTest {
        val engine = TickEngine(this)
        engine.setGameSpeed(100L)

        val logicTicks = mutableListOf<TickEvent>()
        val realTicks = mutableListOf<Long>()

        val collectLogicJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.tickFlow.collect { logicTicks.add(it) }
        }
        val collectRealJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.realTickFlow.collect { realTicks.add(it) }
        }

        engine.start(0L)

        advanceTimeBy(210L)
        val initialLogicCount = logicTicks.size
        val initialRealCount = realTicks.size
        assertTrue(initialLogicCount >= 2)
        assertTrue(initialRealCount >= 4)

        // 仅暂停逻辑心跳
        engine.pauseLogic()
        assertTrue(engine.isLogicPaused())

        // 推进 300ms -> 逻辑心跳不应增加，但高频 UI 心跳应该增加
        advanceTimeBy(310L)
        assertEquals(initialLogicCount, logicTicks.size)
        assertTrue(realTicks.size > initialRealCount)

        // 恢复逻辑心跳
        engine.resumeLogic()
        assertFalse(engine.isLogicPaused())

        // 推进 200ms -> 逻辑心跳继续增加
        advanceTimeBy(210L)
        assertTrue(logicTicks.size > initialLogicCount)

        collectLogicJob.cancel()
        collectRealJob.cancel()
        engine.stop()
    }

    @Test
    fun testConcurrentStartPreventionAndReentrancy() = runTest {
        val engine = TickEngine(this)
        engine.setGameSpeed(100L)

        val logicTicks = mutableListOf<TickEvent>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.tickFlow.collect { logicTicks.add(it) }
        }

        // 连续并发调用 start
        coroutineScope {
            launch { engine.start(0L) }
            launch { engine.start(100L) } // 另一个试图以 100L 重启，但应被幂等拦截
        }

        advanceTimeBy(210L)
        
        // 如果第二个 start 没有拦截，tick 可能会从 100 开始。由于被拦截了，应该还是从 0 推进到 2
        assertTrue(logicTicks.all { it.tickId < 10 }, "Reentrancy should be blocked, but ticks got: ${logicTicks.map { it.tickId }}")

        collectJob.cancel()
        engine.stop()
    }
}
