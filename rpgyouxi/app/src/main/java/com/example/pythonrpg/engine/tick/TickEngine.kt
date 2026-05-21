package com.example.pythonrpg.engine.tick

import com.example.pythonrpg.shared.TimePeriod
import com.example.pythonrpg.shared.TickEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TickEngine - 游戏心跳引擎，驱动游戏世界的逻辑流转与 UI 刷新
 */
class TickEngine(private val scope: CoroutineScope) {

    // ── 原子状态变量 ──────────────────────────────────────────────
    private val currentTickId = AtomicLong(0L)
    private val intervalMs = AtomicLong(5000L)
    private val isEnginePaused = AtomicBoolean(false)
    private val isLogicPaused = AtomicBoolean(false)

    // ── 协程内部/锁状态 ───────────────────────────────────────────
    private var accumulator: Long = 0L
    private var engineJob: Job? = null
    private val startMutex = Mutex()

    // ── 广播流声明 ────────────────────────────────────────────────
    private val _tickFlow = MutableSharedFlow<TickEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tickFlow: SharedFlow<TickEvent> = _tickFlow.asSharedFlow()

    private val _realTickFlow = MutableSharedFlow<Long>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val realTickFlow: SharedFlow<Long> = _realTickFlow.asSharedFlow()

    /**
     * 启动心跳循环
     * @param initialTick 初始 Tick 计数，默认 0L
     */
    suspend fun start(initialTick: Long = 0L) {
        startMutex.withLock {
            if (engineJob != null) {
                return // 幂等保护，如果已经运行则直接返回
            }

            currentTickId.set(initialTick)
            accumulator = 0L
            isEnginePaused.set(false)
            isLogicPaused.set(false)

            engineJob = scope.launch {
                while (isActive) {
                    delay(50L)
                    if (!isEnginePaused.get()) {
                        _realTickFlow.tryEmit(System.currentTimeMillis())
                        accumulator += 50
                        
                        val currentInterval = intervalMs.get()
                        if (currentInterval > 0) {
                            while (accumulator >= currentInterval) {
                                if (!isLogicPaused.get()) {
                                    val nextTickId = currentTickId.incrementAndGet()
                                    val event = TickEvent(
                                        tickId = nextTickId,
                                        timestamp = System.currentTimeMillis(),
                                        timeOfDay = getPeriod(nextTickId)
                                    )
                                    _tickFlow.tryEmit(event)
                                }
                                accumulator -= currentInterval
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 停止心跳循环
     */
    suspend fun stop() {
        startMutex.withLock {
            engineJob?.cancel()
            engineJob = null
            isEnginePaused.set(false)
            isLogicPaused.set(false)
        }
    }

    /**
     * 暂停引擎 (停止 realTickFlow 和 tickFlow)
     */
    fun pauseEngine() {
        isEnginePaused.set(true)
    }

    /**
     * 恢复引擎
     */
    fun resumeEngine() {
        isEnginePaused.set(false)
    }

    /**
     * 仅暂停逻辑心跳 (继续发送 realTickFlow，但暂停 tickFlow)
     */
    fun pauseLogic() {
        isLogicPaused.set(true)
    }

    /**
     * 恢复逻辑心跳
     */
    fun resumeLogic() {
        isLogicPaused.set(false)
    }

    /**
     * 设置游戏速度 (即逻辑 Tick 的触发间隔)
     * @param newIntervalMs 触发间隔，单位毫秒，必须大于 0
     */
    fun setGameSpeed(newIntervalMs: Long) {
        if (newIntervalMs > 0) {
            intervalMs.set(newIntervalMs)
        }
    }

    fun isEnginePaused(): Boolean = isEnginePaused.get()

    fun isLogicPaused(): Boolean = isLogicPaused.get()

    fun getCurrentTickId(): Long = currentTickId.get()

    /**
     * 获取指定 TickId 所处的时间段
     */
    private fun getPeriod(tickId: Long): TimePeriod {
        val mod = ((tickId % 24) + 24) % 24
        return when {
            mod == 1L -> TimePeriod.MORNING
            mod in 2L..14L -> TimePeriod.DAYTIME
            mod in 15L..16L -> TimePeriod.TWILIGHT
            else -> TimePeriod.NIGHT
        }
    }
}
