package com.example.pythonrpg.engine.pathfinding

import com.example.pythonrpg.shared.Coordinate
import java.util.PriorityQueue

/**
 * 通用网格通行快照接口
 */
public interface PassabilityGrid {
    public fun isPassable(x: Int, y: Int): Boolean
}

/**
 * 寻路结果数据类
 */
public data class PathResult(
    val found: Boolean,           // 是否找到了可行路径
    val path: List<Coordinate>,   // 从起点到终点的完整坐标列表（含起点和终点）
    val totalSteps: Int,          // 移动步数 (path.size - 1)
    val reason: String            // 寻路状态/未通过时的原因描述
)

/**
 * 高性能通用 A* 寻路引擎
 */
public class PathfindingEngine {

    /**
     * 高性能私有辅助节点类
     */
    /**
     * 高性能内部辅助节点类
     */
    internal class Node(
        val x: Int,
        val y: Int,
        val g: Int,        // 起点到当前节点的实际代价
        val h: Int,        // 当前节点到终点的预估启发式代价
        val parent: Node?  // 逆向回溯 parent 指针
    ) {
        val f: Int get() = g + h
    }

    /**
     * PriorityQueue 排序比较器（f值越小越优先）
     */
    internal val nodeComparator = Comparator<Node> { n1, n2 ->
        n1.f.compareTo(n2.f)
    }

    /**
     * 坐标压缩：将 2D 坐标打包为单一 Long 值，防止垃圾回收（GC）开销
     */
    private fun pack(x: Int, y: Int): Long {
        return (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    }

    /**
     * 曼哈顿距离（四方向正交测距）
     */
    public fun manhattanDistance(from: Coordinate, to: Coordinate): Int {
        return kotlin.math.abs(from.x - to.x) + kotlin.math.abs(from.y - to.y)
    }

    /**
     * 欧几里得距离（直线几何测距）
     */
    public fun euclideanDistance(from: Coordinate, to: Coordinate): Double {
        val dx = (from.x - to.x).toDouble()
        val dy = (from.y - to.y).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * 高性能 A* 寻路核心实现
     *
     * @param fromX 起点 X
     * @param fromY 起点 Y
     * @param toX 终点 X
     * @param toY 终点 Y
     * @param grid 地图通行性网格快照
     * @param maxSteps 最大搜索步数，防止超大范围扫描导致卡死
     * @param maxOpenNodes 最大待处理节点堆积数，防止内存溢出（openSet 防爆）
     */
    public fun findPath(
        fromX: Int, fromY: Int,
        toX: Int, toY: Int,
        grid: PassabilityGrid,
        maxSteps: Int = 500,
        maxOpenNodes: Int = 2000
    ): PathResult {
        // 1. 起点等于终点，短路直接返回
        if (fromX == toX && fromY == toY) {
            return PathResult(
                found = true,
                path = listOf(Coordinate(fromX, fromY)),
                totalSteps = 0,
                reason = "START_EQUALS_END"
            )
        }

        // 2. 起点不可通行，拦截返回
        if (!grid.isPassable(fromX, fromY)) {
            return PathResult(
                found = false,
                path = emptyList(),
                totalSteps = 0,
                reason = "START_NOT_PASSABLE"
            )
        }

        // 3. 终点不可通行，拦截返回
        if (!grid.isPassable(toX, toY)) {
            return PathResult(
                found = false,
                path = emptyList(),
                totalSteps = 0,
                reason = "DEST_NOT_PASSABLE"
            )
        }

        // 初始化寻路容器
        val openSet = PriorityQueue<Node>(nodeComparator)
        val closedSet = HashSet<Long>()

        // 初始代价与起点压栈
        val initH = kotlin.math.abs(fromX - toX) + kotlin.math.abs(fromY - toY)
        val startNode = Node(fromX, fromY, 0, initH, null)
        openSet.offer(startNode)

        var stepsCount = 0

        while (openSet.isNotEmpty()) {
            // openSet 内存上限熔断
            if (openSet.size > maxOpenNodes) {
                return PathResult(
                    found = false,
                    path = emptyList(),
                    totalSteps = stepsCount,
                    reason = "OPEN_SET_OVERFLOW"
                )
            }

            // 步数溢出熔断检查
            stepsCount++
            if (stepsCount > maxSteps) {
                return PathResult(
                    found = false,
                    path = emptyList(),
                    totalSteps = stepsCount,
                    reason = "STEP_LIMIT_EXCEEDED"
                )
            }

            // 弹出当前最优节点
            val current = openSet.poll()
            val currentKey = pack(current.x, current.y)

            // 已在 closedSet 中则跳过
            if (closedSet.contains(currentKey)) {
                continue
            }
            closedSet.add(currentKey)

            // 抵达终点判定与路径回溯
            if (current.x == toX && current.y == toY) {
                val pathList = mutableListOf<Coordinate>()
                var currNode: Node? = current
                while (currNode != null) {
                    pathList.add(Coordinate(currNode.x, currNode.y))
                    currNode = currNode.parent
                }
                pathList.reverse()

                return PathResult(
                    found = true,
                    path = pathList,
                    totalSteps = pathList.size - 1,
                    reason = "PATH_FOUND"
                )
            }

            // 扩展子节点（仅限四方向正交步进）
            val directions = listOf(
                Pair(0, 1), Pair(0, -1),
                Pair(1, 0), Pair(-1, 0)
            )

            for ((dx, dy) in directions) {
                val nx = current.x + dx
                val ny = current.y + dy

                // 通行性校验
                if (!grid.isPassable(nx, ny)) {
                    continue
                }

                // closedSet 去重校验
                val neighborKey = pack(nx, ny)
                if (closedSet.contains(neighborKey)) {
                    continue
                }

                // 构造子节点并入队
                val newG = current.g + 1
                val h = kotlin.math.abs(nx - toX) + kotlin.math.abs(ny - toY)
                val neighborNode = Node(nx, ny, newG, h, current)
                openSet.offer(neighborNode)
            }
        }

        // 队列耗尽仍未找到路径
        return PathResult(
            found = false,
            path = emptyList(),
            totalSteps = stepsCount,
            reason = "NO_PATH"
        )
    }
}
