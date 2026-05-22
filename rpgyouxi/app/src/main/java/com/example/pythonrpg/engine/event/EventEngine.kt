package com.example.pythonrpg.engine.event

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// 1. 六大突发事件类型
enum class EventType {
    FOREST_FIRE,        // 🔥 森林火灾（暴晒干旱引发）
    FLOOD,              // 🌊 洪水泛滥（暴雨积水引发）
    PLAGUE,             // 🦠 致命瘟疫（民房人口过密引发）
    BOSS_RIOT,          // ⚔️ Boss 暴动（发现 Boss 长期未剿引发）
    COLD_SNAP,          // 🧊 极寒寒流（与寒潮天候关联）
    BUILDING_COLLAPSE   // 🏚️ 低级建筑坍塌（年久失修，触发即结算）
}

// 2. 活跃事件状态类
data class ActiveEvent(
    val eventId: Long,
    val type: EventType,
    val targetX: Int?,
    val targetY: Int?,
    val affectedBuildingId: Long?,     // 受影响的具体建筑 ID
    var remainingTicks: Int,           // 剩余持续 Tick 寿命（-1 代表无限，直到被清理）
    val severity: Int                  // 严重烈度
)

// 3. 事件广播事件更新包
data class ActiveEventUpdate(
    val event: ActiveEvent,
    val action: String                 // "SPAWNED" | "UPDATED" | "EXPIRED" | "RESOLVED"
)

// 4. 环境条件获取依赖提供者（解耦核心引擎与具体建筑/天气系统）
interface EventConditionProvider {
    fun getCurrentWeather(): String
    fun getRandomForestBuilding(): Long?   // 随机获取一个森林或伐木建筑 ID，用于引火
    fun getRandomFarmBuilding(): Long?     // 随机获取一个农田或草原建筑 ID，用于灌洪
    fun hasUndefeatedBoss(): Boolean       // 判定地图上是否存在已发现但未打败的 Boss
}

/**
 * EventEngine - 灾难及突发事件发生引擎
 */
class EventEngine(private val conditionProvider: EventConditionProvider) {

    // 线程安全的状态容器
    private val activeEvents = ConcurrentHashMap<Long, ActiveEvent>()

    // 原子自增事件 ID 发生器
    private val eventIdGenerator = AtomicLong(1L)

    // 核心广播事件通道 (eventFlow)
    private val _eventFlow = MutableSharedFlow<ActiveEventUpdate>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<ActiveEventUpdate> = _eventFlow.asSharedFlow()

    /**
     * 获取当前所有的活跃事件列表
     */
    fun getActiveEvents(): List<ActiveEvent> = activeEvents.values.toList()

    /**
     * 将事件录入 activeEvents 并触发 SPAWNED 广播
     */
    fun registerEvent(event: ActiveEvent) {
        activeEvents[event.eventId] = event
        _eventFlow.tryEmit(ActiveEventUpdate(event, "SPAWNED"))
    }

    /**
     * 概率触发天灾事件
     */
    fun tryTriggerEvents() {
        val weather = conditionProvider.getCurrentWeather()

        // 1. FOREST_FIRE (森林火灾)
        if (weather == "DROUGHT") {
            val targetB = conditionProvider.getRandomForestBuilding()
            if (targetB != null && Random.nextInt(100) < 10) {
                val event = ActiveEvent(
                    eventId = eventIdGenerator.getAndIncrement(),
                    type = EventType.FOREST_FIRE,
                    targetX = null,
                    targetY = null,
                    affectedBuildingId = targetB,
                    remainingTicks = 4,
                    severity = 2
                )
                registerEvent(event)
            }
        }

        // 2. FLOOD (洪水泛滥)
        if (weather == "RAIN") {
            val targetF = conditionProvider.getRandomFarmBuilding()
            if (targetF != null && Random.nextInt(100) < 10) {
                val event = ActiveEvent(
                    eventId = eventIdGenerator.getAndIncrement(),
                    type = EventType.FLOOD,
                    targetX = null,
                    targetY = null,
                    affectedBuildingId = targetF,
                    remainingTicks = 4,
                    severity = 2
                )
                registerEvent(event)
            }
        }

        // 3. COLD_SNAP (极寒寒流)
        if (weather == "COLD_WAVE") {
            if (activeEvents.values.none { it.type == EventType.COLD_SNAP }) {
                if (Random.nextInt(100) < 20) {
                    val event = ActiveEvent(
                        eventId = eventIdGenerator.getAndIncrement(),
                        type = EventType.COLD_SNAP,
                        targetX = null,
                        targetY = null,
                        affectedBuildingId = null,
                        remainingTicks = 6,
                        severity = 3
                    )
                    registerEvent(event)
                }
            }
        }

        // 4. BOSS_RIOT (Boss 暴动)
        if (conditionProvider.hasUndefeatedBoss()) {
            if (activeEvents.values.none { it.type == EventType.BOSS_RIOT }) {
                if (Random.nextInt(100) < 5) {
                    val event = ActiveEvent(
                        eventId = eventIdGenerator.getAndIncrement(),
                        type = EventType.BOSS_RIOT,
                        targetX = null,
                        targetY = null,
                        affectedBuildingId = null,
                        remainingTicks = -1,
                        severity = 5
                    )
                    registerEvent(event)
                }
            }
        }
    }

    /**
     * 外部中央时钟在每 Tick 推进时，主动调用此接口推进事件自运转
     */
    fun processTick(tickId: Long = 0L) {
        // 步骤 A：环境概率生成检测
        tryTriggerEvents()

        // 步骤 B：遍历老化扣减
        val currentEvents = activeEvents.values.toList()
        for (event in currentEvents) {
            if (event.remainingTicks > 0) { // 排除 -1 级无限事件
                event.remainingTicks -= 1
                if (event.remainingTicks <= 0) {
                    activeEvents.remove(event.eventId)
                    _eventFlow.tryEmit(ActiveEventUpdate(event, "EXPIRED"))
                }
            }
        }
    }

    /**
     * 手动抢险消除事件接口
     */
    fun resolveEvent(eventId: Long): Boolean {
        val event = activeEvents[eventId]
        return if (event != null) {
            activeEvents.remove(eventId)
            _eventFlow.tryEmit(ActiveEventUpdate(event, "RESOLVED"))
            true
        } else {
            false
        }
    }
}
