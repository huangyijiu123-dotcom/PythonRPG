# AI Coding Prompt: MDU 05_ActionProcessor (核心行动处理器)

你是一个资深 Kotlin 游戏引擎专家。你的任务是实现 `ActionProcessor` 类、`VillagerAutomationSystem` 自转状态机类以及它们的单元测试。这个模块是游戏引擎的「大脑」，负责接收玩家/脚本指令、驱动所有时运转系统的自转、运行最复杂的村民自转状态机、整合天气和法令的影响，并广播所有的游戏事件。

---

## 1. 外部依赖与接口契约
> ⚠️ 注意：以下类型必须直接使用我们统一在包 `com.example.pythonrpg.shared` 中的声明，不要重新声明它们：
```kotlin
package com.example.pythonrpg.shared

data class Coordinate(val x: Int, val y: Int)
// PlayerCommand (MDU 18.0) 
// GameEvent (MDU 18.0)
// WeatherModifiers (MDU 18.0)
// PolicyModifiers (MDU 18.0)
```

### 1.1 基础系统与处理器抽象接口
```kotlin
// 游戏周期自转驱动系统接口（村民自动化等系统继承自它）
interface ITickableSystem {
    fun processTick(tickId: Long, weatherModifier: WeatherModifiers, policyModifier: PolicyModifiers)
}

// 玩家指令处理器接口
interface CommandHandler {
    fun canHandle(command: PlayerCommand): Boolean
    fun handle(command: PlayerCommand)
}
```

---

## 2. 核心类契约 & 内部状态

请声明 `class ActionProcessor` 并拥有以下状态与并发队列：
- **指令缓冲队列**：
  `private val commandQueue = ConcurrentLinkedQueue<PlayerCommand>()`
- **只读广播流（背压策略使用 `DROP_OLDEST`，发射必须使用非阻塞的 `tryEmit`）**：
  - `private val _eventFlow = MutableSharedFlow<List<GameEvent>>(replay = 0, extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)`
  - `val eventFlow: SharedFlow<List<GameEvent>> = _eventFlow.asSharedFlow()`
- **自转系统与处理器容器**（使用并发线程安全集合）：
  - `private val commandHandlers = CopyOnWriteArrayList<CommandHandler>()`
  - `private val tickableSystems = CopyOnWriteArrayList<ITickableSystem>()`
- **事件暂存队列**：
  `private val pendingEvents = ConcurrentLinkedQueue<GameEvent>()`
- **生命周期挂载**：
  - `private var processorJob: Job? = null`

### 2.1 核心分发 API
- `fun enqueueCommand(command: PlayerCommand) { commandQueue.add(command) }`
- `fun postEvent(event: GameEvent) { pendingEvents.add(event) }`
- `fun registerHandler(handler: CommandHandler) { commandHandlers.add(handler) }`
- `fun registerSystem(system: ITickableSystem) { tickableSystems.add(system) }`

---

## 3. 中央指令/心跳驱动大循环 (`processTickStep`)

请实现以下驱动方法（由 `GameLoopCoordinator` 订阅 TickFlow 触发调用，或在 start 自动监听中触发）：
```kotlin
suspend fun processTickStep(
    tickId: Long, 
    weatherModifiers: WeatherModifiers = WeatherModifiers(), 
    policyModifiers: PolicyModifiers = PolicyModifiers()
)
```

**执行次序（必须按此严格步骤，保证原子结算）**：
1. **消化指令总线（解耦红线：绝对禁止 $when(command)$ 条件分支硬编码匹配，必须遍历处理器处理）**：
   - 循环弹出并消费 `commandQueue` 中的所有指令。对每一个弹出的指令，遍历 `commandHandlers`，匹配 `canHandle(cmd) == true` 的处理器，并执行该处理器的 `handle(cmd)`。
2. **驱动世界自转**：
   - 遍历所有注册的 `tickableSystems`，执行 `processTick(tickId, weatherModifiers, policyModifiers)`。
3. **批量分发并重置 Tick 周期事件**：
   - 将 `pendingEvents` 中暂存的所有事件收集为一个 `List<GameEvent>`。
   - 若列表不为空，则使用 `_eventFlow.tryEmit(eventList)` 非阻塞广播。
   - 彻底清空 `pendingEvents`。
   - **最后必须发出一个 `GameEvent.TickProcessed(tickId, eventList.size)` 作为 Tick 结束锚点事件**，以单独 List 发送。

### 3.2 自动监听生命周期 API
- `fun start(tickFlow: SharedFlow<TickEvent>, scope: CoroutineScope)`
  - 启动协程 `scope.launch` 监听 `tickFlow` 流。每当收到 `TickEvent(tickId, ...)` 时，调用 `processTickStep(tickId)`。支持幂等启动，用 `processorJob` 管理。
- `fun stop()`: 取消并清理监听 Job。

---

## 4. 村民自转状态机系统 (`VillagerAutomationSystem`)

请声明 `class VillagerAutomationSystem` 并实现 `ITickableSystem` 接口。它必须与 `EntityStateManager`、`PathfindingEngine`、`GridMapData` 以及 `ActionProcessor` (用于 postEvent) 关联。

在 `processTick` 时，对每个村民执行如下状态流转计算（默认移动速度：2 格/Tick；耐久磨损率：每次采集扣 1 耐久；体力基础扣除：10；体力基础恢复：10）：

1. **`status == RESTING` (民房休息)**：
   - 体力恢复：`energy += 10 * energyRestoreMultiplier`。如果是在 `NIGHT` 时段，体力恢复倍率翻倍（乘以 2.0 恢复加成）。
   - 体力达到 100 时静默封顶。除非有新的工作指派，否则继续停留在 RESTING 状态。

2. **`status == MOVING` (前往工作点或仓库)**：
   - 使用 `PathfindingEngine.findPath`。格子的 `PassabilityGrid.isPassable` 返回条件为：已探索且格子上没有未清理的怪物（由 `GridMapData` 判定）。
   - 提取路径中的下 2 个坐标点作为步进，更新村民位置并抛出 `VillagerMoved` 事件。
   - **终点抵达**：到达目标点后，进入 `WORKING`（若在工作点）或 `DELIVERING`（若在仓库）。

3. **`status == WORKING` (采集作业)**：
   - **体力消耗**：`energy -= 10 * weather.energyCostMultiplier * policy.energyCostMultiplier`。
   - **产出计算**：`Amount = 5 * (1.0f + 工具耐久加成倍率) * policy.harvestYieldMultiplier`（工具若损坏或无工具则加成倍率为 0.0f；体力值 $\le 20$ 时产出减半）。
   - **工具磨损**：装备的采集工具扣 1 耐久。若耐久归零，则从村民身上移除，并抛出 `VillagerToolBroken` 事件。
   - **背包塞入**：产出物存入村民背包。若背包满限（容量达到 20 个），强制状态变更为 `DELIVERING`，导航目标设为最近的可用仓库。若体力值降为 0，强制变更为 `MOVING` 返回民房，抛出 `VillagerReturningHome(id, "LOW_ENERGY")`。

4. **`status == DELIVERING` (交付资源)**：
   - 导航到物理距离最近的仓库。
   - 到达仓库后，调用 `entityState.transferItemVillagerToWarehouse` 转移背包所有物资。若仓库爆满超额则原地等待。
   - **交付成功后**：若不是 `NIGHT` 且不是 `TWILIGHT`，状态切回 `MOVING` 返回原工作点。若是黄昏或夜晚，切回 `MOVING` 返回民房。

5. **黄昏夜晚的作息强力规约**：
   - **黄昏强制规约 (`timeOfDay == TWILIGHT`)**：`WORKING` 村民有资源强制转 `DELIVERING` 回仓，无资源强制 `MOVING` 回民房，抛出 `VillagerReturningHome(id, "TWILIGHT")`；`MOVING`（前往工作点）村民强力调头返回民房。
   - **夜晚露宿惩罚 (`timeOfDay == NIGHT`)**：若在下一 Tick 村民仍未回到民房，其体力恢复系数乘以 `0.5`（流落野外疲劳削减）。

---

## 5. 单元测试要求：ActionProcessorDispatcherTest.kt

编写高覆盖度的黑盒测试用例：
1. **解耦分发**：注册 Fake 指令与处理器，推进 Tick，验证 Fake 处理器正确触发且 ActionProcessor 内部无 direct conditional matching（捍卫开闭原则）。
2. **多线程防 ConcurrentModification 并发修改测试**：启动 50 个并发协程连续入队 1000 个指令，推进 Tick 结算，验证无任何并发读写崩溃且队列最终清空。
3. **村民 Working 满载交付自转测试**：背包接近上限的 WORKING 村民，在 1 次自转 Tick 结算后，状态自动切换为 `DELIVERING`，且目标导航指向了最近的仓库。
4. **黄昏掉头强制规约测试**：`TWILIGHT` 黄昏时段下，正在前往工作点的村民，状态和目标被强行改为回家，且向事件队列抛出了 `VillagerReturningHome(id, "TWILIGHT")`。
5. **在外野宿体力惩罚测试**：`NIGHT` 夜晚时段下，不在家村民体力恢复速率降至 50%。

---

## 6. 输出规范
- **只输出 Kotlin 代码**，分成 `ActionProcessor.kt` 和 `ActionProcessorDispatcherTest.kt`。
- 完全通过公开接口测试，禁止反射。
- 遵循 Kotlin 官方代码风格，加入完整的 visibility 修饰符。
