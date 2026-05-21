# AI Coding Prompt: MDU 03_PathfindingEngine (寻路引擎)

你是一个资深 Kotlin 游戏引擎专家。你的任务是实现 `PathfindingEngine` 类以及它的单元测试。这是一个纯粹的通用网格寻路算法库（禁止引入任何具体的游戏业务逻辑名词），负责计算两点间的几何距离（曼哈顿与欧几里得），并使用高防 GC 的 4 方向 A* 算法，并包含最大步数熔断机制。

---

## 1. 外部依赖统一模型
> ⚠️ 注意：以下类型必须直接使用我们统一在包 `com.example.pythonrpg.shared` 中的声明，不要重新声明它们：
```kotlin
package com.example.pythonrpg.shared

data class Coordinate(val x: Int, val y: Int)
```

---

## 2. 核心数据契约 & 接口定义

```kotlin
// 寻路结果数据类
data class PathResult(
    val found: Boolean,           // 是否找到了可行路径
    val path: List<Coordinate>,   // 从起点到终点的完整坐标列表（含起点和终点）
    val totalSteps: Int,          // 移动步数 (path.size - 1)
    val reason: String            // 寻路未通过时的原因描述
)

// 通行校验快照接口
interface PassabilityGrid {
    fun isPassable(x: Int, y: Int): Boolean
}
```

---

## 3. 类定义与核心算法实现

请实现 `class PathfindingEngine` 包含以下方法：

### 3.1 测距工具函数 (必须直接执行数学计算，零对象分配，无 GC)
- `fun manhattanDistance(from: Coordinate, to: Coordinate): Int`
  - 公式：`|from.x - to.x| + |from.y - to.y|`
- `fun euclideanDistance(from: Coordinate, to: Coordinate): Double`
  - 公式：`√((from.x - to.x)² + (from.y - to.y)²)`

### 3.2 内部 Node 类与排序器
- 在类内部或私有声明：
  ```kotlin
  private class Node(
      val x: Int,
      val y: Int,
      val g: Int,        // 起点到当前节点的实际代价
      val h: Int,        // 当前节点到终点的预估启发式代价
      val parent: Node?  // 逆向回溯 parent 指针
  ) {
      val f: Int get() = g + h
  }
  ```
- 定义排序比较器（`f` 值越小优先级越高）：
  `private val nodeComparator = Comparator<Node> { n1, n2 -> n1.f.compareTo(n2.f) }`

### 3.3 A* 寻路核心算法 (`findPath`)
- **方法签名**：
  ```kotlin
  fun findPath(
      fromX: Int, fromY: Int,
      toX: Int, toY: Int,
      grid: PassabilityGrid,
      maxSteps: Int = 500
  ): PathResult
  ```
- **前置边界拦截（短路规则）**：
  - 若 `fromX == toX && fromY == toY` -> 返回 `PathResult(found = true, path = listOf(Coordinate(fromX, fromY)), totalSteps = 0, reason = "START_EQUALS_END")`。
  - 若 `!grid.isPassable(fromX, fromY)` -> 返回 `PathResult(found = false, path = emptyList(), totalSteps = 0, reason = "START_NOT_PASSABLE")`。
  - 若 `!grid.isPassable(toX, toY)` -> 返回 `PathResult(found = false, path = emptyList(), totalSteps = 0, reason = "DEST_NOT_PASSABLE")`。
- **主搜索循环**：
  - 使用 `PriorityQueue<Node>(nodeComparator)` 作为 `openSet`。
  - 使用 `HashSet<Long>()` 作为 `closedSet`（存储 Long 类型的坐标 Key，`pack(x, y) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)`。严禁在 closedSet 中直接保存 Coordinate 对象，防范 GC 炸弹）。
  - 将起点包装成 Node 放入 `openSet`。
  - 初始化步数计算 `var stepsCount = 0`。
  - 循环弹出 `openSet` 最优节点：
    - `stepsCount++`。若 `stepsCount > maxSteps`，立即熔断，返回 `PathResult(found = false, path = emptyList(), totalSteps = stepsCount, reason = "STEP_LIMIT_EXCEEDED")`。
    - 若 `closedSet` 已包含当前节点，则 `continue`。
    - 将当前坐标放入 `closedSet`。
    - **终点抵达校验**：若当前坐标 `x == toX && y == toY`，沿着 `parent` 指针逆向回溯，生成完整 `Coordinate` 路径列表并反转（路径列表必须包含起点和终点），返回 `PathResult(found = true, path = pathList, totalSteps = pathList.size - 1, reason = "PATH_FOUND")`。
    - **4方向展开（上下左右）**：
      - 对每一个合法且 `grid.isPassable` 的邻居：
        - 计算 `newG = current.g + 1`，`h` 使用曼哈顿距离。
        - 校验邻居若不在 `closedSet` 中，则构造邻居 Node 并放入 `openSet`。
  - 若队列耗尽仍未找到路径，返回 `PathResult(found = false, path = emptyList(), totalSteps = stepsCount, reason = "NO_PATH")`。

---

## 4. 单元测试要求：PathfindingEngineTest.kt

编写高覆盖度的纯几何单元测试，包含以下测试用例：
1. **测距校验**：测试曼哈顿距离与欧几里得距离计算的精度（勾股定理与负数象限校验）。
2. **Node 排序**：验证 `nodeComparator` 对同/不同 `f` 值的排序正确性与稳定性。
3. **短路与边界测试**：起点等于终点、起点或终点不可通行时，立即秒退并返回对应 reason。
4. **标准空旷网格**：无阻碍 5x5 地图，起点 (0,0) 到 (4,4) 路径及步数精确。
5. **不可达迷宫（死路拦截）**：起点被一圈障碍封死，验证返回 `NO_PATH` 且不发生卡死。
6. **强力步数熔断**：无阻碍 10x10 地图，跨度为16格，设置 `maxSteps = 5`，断言寻路因 `STEP_LIMIT_EXCEEDED` 极速返回。

---

## 5. 输出规范
- **只输出 Kotlin 代码**，分成 `PathfindingEngine.kt` 和 `PathfindingEngineTest.kt`。
- 不要使用任何 Java 反射技术，完全通过公开接口测试。
- 遵循 Kotlin 官方代码风格，加入完整的 visibility 修饰符。
