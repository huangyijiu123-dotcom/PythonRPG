# AI Coding Prompt: MDU 01_TickEngine (时钟引擎)

你是一个资深 Kotlin 游戏引擎专家。你的任务是实现 `TickEngine` 类以及它的单元测试。这个模块是游戏的心跳中枢，负责驱动游戏世界的自转和 UI 的刷新。

---

## 1. 外部依赖统一模型
> ⚠️ 注意：以下类型必须直接使用我们统一在包 `com.example.pythonrpg.shared` 中的声明，不要重新声明它们：
```kotlin
package com.example.pythonrpg.shared

enum class TimePeriod {
    MORNING,    // tickId % 24 == 1
    DAYTIME,    // tickId % 24 in 2..14
    TWILIGHT,   // tickId % 24 in 15..16
    NIGHT       // tickId % 24 in 17..23 或 == 0
}

data class TickEvent(
    val tickId: Long,
    val timestamp: Long,
    val timeOfDay: TimePeriod
)
```

---

## 2. 核心类契约 & API 签名

### 2.1 类定义与内部状态
请声明 `class TickEngine(private val scope: CoroutineScope)` 并拥有以下属性与状态：
- **原子状态变量**（必须使用 `AtomicLong`/`AtomicBoolean`，由 `java.util.concurrent.atomic` 提供）：
  - `currentTickId`: `AtomicLong` (默认 0L)
  - `intervalMs`: `AtomicLong` (默认 5000L)
  - `isEnginePaused`: `AtomicBoolean` (默认 false，引擎全停)
  - `isLogicPaused`: `AtomicBoolean` (默认 false，晨会冻结)
- **协程内部状态**：
  - `accumulator`: `private var accumulator: Long = 0L` (记录自上一逻辑 Tick 以来累积的毫秒数)
  - `engineJob`: `private var engineJob: Job? = null` (协程任务句柄)
  - `startMutex`: `private val startMutex = Mutex()` (确保启动幂等与线程安全的互斥锁)

### 2.2 广播流声明（必须配置 `DROP_OLDEST` 策略，防止背压卡死时钟）
- `tickFlow`: `val tickFlow: SharedFlow<TickEvent>` 逻辑变速时钟流（`replay = 0`, `extraBufferCapacity = 1`, `onBufferOverflow = BufferOverflow.DROP_OLDEST`）。
- `realTickFlow`: `val realTickFlow: SharedFlow<Long>` UI高频 50ms 固定心跳流（策略同上）。

---

## 3. 详细方法行为约束

1. **`start(initialTick: Long = 0)`**
   - 使用 `startMutex.withLock` 包裹。
   - 若引擎已启动（`engineJob != null`），则直接返回（幂等）。
   - 初始化：`currentTickId` 设为 `initialTick`，`accumulator` 重置为 `0L`，`isEnginePaused` 和 `isLogicPaused` 设为 `false`。
   - 启动协程：在 `scope` 中启动循环，固定每 `50ms` 进行一次 `delay(50L)`，并累加。
     - 若 `!isEnginePaused.get()`：
       - 向 `realTickFlow` 尝试发射当前时间戳：`_realTickFlow.tryEmit(System.currentTimeMillis())`。
       - `accumulator += 50`。
       - 当 `accumulator >= intervalMs.get()` 时：
         - 若 `!isLogicPaused.get()`：
           - `currentTickId` 自增 1。
           - 发射 `TickEvent(currentTickId.get(), System.currentTimeMillis(), getPeriod(currentTickId.get()))` 到 `tickFlow`。
         - **扣减累积器**：`accumulator -= intervalMs.get()`（保留溢出余额，禁止直接清零）。

2. **`stop()`**
   - 使用 `startMutex.withLock` 保护。
   - 取消 `engineJob`，并将 `engineJob` 设为 `null`。
   - 强制重置 `isEnginePaused` 和 `isLogicPaused` 为 `false`。

3. **暂停控制 API**：
   - `pauseEngine()`: 设置 `isEnginePaused = true`。不改动 `isLogicPaused`。
   - `resumeEngine()`: 设置 `isEnginePaused = false`。不改动 `isLogicPaused`。
   - `pauseLogic()`: 设置 `isLogicPaused = true`。不改动 `isEnginePaused`。
   - `resumeLogic()`: 设置 `isLogicPaused = false`。不改动 `isEnginePaused`。

4. **属性获取与设置**：
   - `setGameSpeed(newIntervalMs: Long)`: 当 `newIntervalMs > 0` 时修改 `intervalMs`。其余值忽略。
   - `isEnginePaused(): Boolean`
   - `isLogicPaused(): Boolean`
   - `getCurrentTickId(): Long`

---

## 4. 单元测试要求：TickEngineTest.kt

编写高覆盖度的黑盒单元测试，包含以下测试用例：
1. **初始默认行为**：新建实例后，`currentTickId` 为 0L，`isEnginePaused` 为 false，`isLogicPaused` 为 false。
2. **逻辑时钟变速心跳与时段判定**：推进虚拟时间，验证 `accumulator` 精准累加并按 `intervalMs` 触发 `tickFlow` 发射，且 `TimePeriod` 判定符合划分：
   - `tickId % 24 == 1` -> `MORNING`
   - `tickId % 24 in 2..14` -> `DAYTIME`
   - `tickId % 24 in 15..16` -> `TWILIGHT`
   - `tickId % 24 in 17..23` 或 `0` -> `NIGHT`
3. **手动暂停与晨会逻辑暂停行为**：
   - 验证 `pauseEngine` 会同时暂停高频 UI 心跳和逻辑心跳。
   - 验证 `pauseLogic` 仅暂停逻辑心跳，但高频 UI 心跳继续发射。
4. **并发防脑裂与重入防护**：连续并发调用 `start` 只有单个协程心跳存活。

---

## 5. 输出规范
- **只输出 Kotlin 代码**，分成 `TickEngine.kt` 和 `TickEngineTest.kt`。
- 不要使用任何 Java 反射技术，完全通过公开接口测试。
- 遵循 Kotlin 官方代码风格，加入完整的 visibility 修饰符。
