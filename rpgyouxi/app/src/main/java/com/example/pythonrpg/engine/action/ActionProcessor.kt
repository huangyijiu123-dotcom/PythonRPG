package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.GameEvent
import com.example.pythonrpg.shared.PlayerCommand
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ActionProcessor - 命令事件总线
 * 负责收集、派发、和处理所有的玩家指令，并缓冲游戏内部产生的事件广播
 */
public class ActionProcessor {
    private val commandHandlers = mutableListOf<CommandHandler>()
    private val commandQueue = ConcurrentLinkedQueue<PlayerCommand>()
    private val eventQueue = ConcurrentLinkedQueue<GameEvent>()

    /**
     * 注册命令处理器
     */
    public fun registerHandler(handler: CommandHandler) {
        commandHandlers.add(handler)
    }

    /**
     * 将指令放入缓冲队列
     */
    public fun queueCommand(command: PlayerCommand) {
        commandQueue.add(command)
    }

    /**
     * 将游戏事件发布到事件总线
     */
    public fun publishEvent(event: GameEvent) {
        eventQueue.add(event)
    }
    
    /**
     * 提取当前所有积压的事件，并清空队列
     */
    public fun pollEvents(): List<GameEvent> {
        val events = mutableListOf<GameEvent>()
        while (true) {
            val event = eventQueue.poll() ?: break
            events.add(event)
        }
        return events
    }

    private var automationSystem: VillagerAutomationSystem? = null

    public fun setAutomationSystem(system: VillagerAutomationSystem) {
        this.automationSystem = system
        system.eventPublisher = this::publishEvent
    }

    public fun processTick(
        tickEvent: com.example.pythonrpg.shared.TickEvent, 
        policyModifiers: com.example.pythonrpg.shared.PolicyModifiers,
        weatherModifiers: com.example.pythonrpg.shared.WeatherModifiers
    ) {
        processPendingCommands()
        automationSystem?.processTick(tickEvent.tickId, tickEvent.timeOfDay, policyModifiers, weatherModifiers)
    }

    /**
     * 顺序处理当前积累的所有指令
     * 遵守 No God-Class Branching Rule: 不使用 when(command)，而是交由 Handler 判断
     */
    public fun processPendingCommands() {
        while (true) {
            val cmd = commandQueue.poll() ?: break
            for (handler in commandHandlers) {
                if (handler.canHandle(cmd)) {
                    handler.handle(cmd)
                    break
                }
            }
        }
    }
}
