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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * GameLoopCoordinator - 全局心跳大循环协调器（唯一订阅 TickEngine.tickFlow 的 Collector）
 */
class GameLoopCoordinator(
    private val tickEngine: TickEngine,
    private val weatherEngine: WeatherEngine,
    private val policyEngine: PolicyEngine,
    private val buildingEngine: BuildingEngine,
    private val workshopEngine: WorkshopEngine,
    private val forgeEngine: ForgeEngine,
    private val eventEngine: EventEngine,
    private val marketEngine: MarketEngine,
    private val actionProcessor: ActionProcessor,
    private val entityState: EntityStateManager,
    private val mapData: GridMapData,
    private val coroutineScope: CoroutineScope
) {
    // 活跃协程 Job 并发管理句柄
    private val activeJob = AtomicReference<Job?>()

    /**
     * 启动全局大循环协程，长期订阅 tickFlow
     */
    fun startLoop() {
        // 双重启动防御：静默拦截，防止重入
        if (activeJob.get() != null) {
            return
        }

        // 申请独立子协程 Job
        val newJob = coroutineScope.launch {
            tickEngine.tickFlow.collect { tickEvent ->
                // 撑起防爆网隔离边界
                try {
                    // 层级 1：环境更新
                    weatherEngine.processTick()
                    policyEngine.processTick()

                    // 层级 2：长期进度推进
                    buildingEngine.processTick()
                    workshopEngine.processTick()
                    forgeEngine.processTick()

                    // 层级 3：动态事件与经济流转
                    eventEngine.processTick()
                    marketEngine.processTick()

                    // 层级 4：行为决策与结算
                    val curPolicyModifiers = policyEngine.getModifiers()
                    val curWeatherModifiers = weatherEngine.getModifiers()
                    actionProcessor.processTick(tickEvent, curPolicyModifiers, curWeatherModifiers)

                    // 层级 5：状态广播同步
                    entityState.emitStateDiff()
                    mapData.emitDirtyTiles()

                } catch (t: Throwable) {
                    println("[COORD_ERROR] Subsystem crash isolated: ${t.message}")
                }
            }
        }

        // 原子赋值：若 CAS 竞争失败，则立即取消该 newJob
        if (!activeJob.compareAndSet(null, newJob)) {
            newJob.cancel()
        }
    }

    /**
     * 停止大循环协程，注销订阅并平滑退场
     */
    fun stopLoop() {
        val job = activeJob.getAndSet(null)
        if (job != null) {
            job.cancel()
        }
    }

    /**
     * 获取当前活跃的 Job 句柄（仅供单元测试和状态检查使用）
     */
    fun getActiveJob(): Job? = activeJob.get()
}
