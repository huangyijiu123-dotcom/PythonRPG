package com.example.pythonrpg.engine.pathfinding

import com.example.pythonrpg.shared.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * 供测试快速构建地图的辅助工具类（实现 O(1) 通行度校验）
 */
class StringGrid(val rows: List<String>) : PassabilityGrid {
    private val height = rows.size
    private val width = if (height > 0) rows[0].length else 0

    override fun isPassable(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return rows[y][x] == '.'
    }
}

class PathfindingEngineTest {

    private val engine = PathfindingEngine()

    // ─── 4.1 几何与数据结构测试 ───────────────────────────────────────

    @Test
    fun testManhattanDistance() {
        // 同一点
        assertEquals(0, engine.manhattanDistance(Coordinate(5, 5), Coordinate(5, 5)))
        // 第一象限
        assertEquals(7, engine.manhattanDistance(Coordinate(0, 0), Coordinate(3, 4)))
        // 跨负数象限
        assertEquals(10, engine.manhattanDistance(Coordinate(-2, -3), Coordinate(2, 3)))
    }

    @Test
    fun testEuclideanDistance() {
        // 勾股数 3, 4, 5
        assertEquals(5.0, engine.euclideanDistance(Coordinate(0, 0), Coordinate(3, 4)), 0.00001)
        // 跨负数象限的 3, 4, 5
        assertEquals(5.0, engine.euclideanDistance(Coordinate(-1, -1), Coordinate(2, 3)), 0.00001)
    }

    @Test
    fun testNodeSortingPriority() {
        val comparator = engine.nodeComparator
        // Node A: g=10, h=5 -> f=15
        val nodeA = PathfindingEngine.Node(0, 0, 10, 5, null)
        // Node B: g=5, h=8 -> f=13
        val nodeB = PathfindingEngine.Node(0, 0, 5, 8, null)

        // f=13 应排在 f=15 之前，即 nodeB.f < nodeA.f，compare 应该返回负值
        assertTrue(comparator.compare(nodeB, nodeA) < 0)
        assertTrue(comparator.compare(nodeA, nodeB) > 0)
    }

    @Test
    fun testNodeSortingStability() {
        val comparator = engine.nodeComparator
        // Node A: g=5, h=5 -> f=10
        val nodeA = PathfindingEngine.Node(0, 0, 5, 5, null)
        // Node B: g=3, h=7 -> f=10
        val nodeB = PathfindingEngine.Node(0, 0, 3, 7, null)

        // f 值相等时，排序结果为 0
        assertEquals(0, comparator.compare(nodeA, nodeB))
    }

    // ─── 4.2 核心 A* 寻路算法与边界拦截测试 ─────────────────────────────

    @Test
    fun testStartEqualsEndShortCircuit() {
        val grid = StringGrid(listOf("."))
        val result = engine.findPath(0, 0, 0, 0, grid)
        assertTrue(result.found)
        assertEquals(0, result.totalSteps)
        assertEquals(1, result.path.size)
        assertEquals(Coordinate(0, 0), result.path[0])
        assertEquals("START_EQUALS_END", result.reason)
    }

    @Test
    fun testStartNotPassableInterception() {
        val grid = StringGrid(listOf(
            "#.",
            ".."
        ))
        val result = engine.findPath(0, 0, 1, 1, grid)
        assertFalse(result.found)
        assertEquals(0, result.totalSteps)
        assertTrue(result.path.isEmpty())
        assertEquals("START_NOT_PASSABLE", result.reason)
    }

    @Test
    fun testDestNotPassableInterception() {
        val grid = StringGrid(listOf(
            ".#",
            ".."
        ))
        val result = engine.findPath(0, 0, 1, 0, grid)
        assertFalse(result.found)
        assertEquals(0, result.totalSteps)
        assertTrue(result.path.isEmpty())
        assertEquals("DEST_NOT_PASSABLE", result.reason)
    }

    @Test
    fun testStandardOpenGridPath() {
        // 5x5 的无阻通行地图
        val grid = StringGrid(listOf(
            ".....",
            ".....",
            ".....",
            ".....",
            "....."
        ))
        val result = engine.findPath(0, 0, 4, 4, grid)
        assertTrue(result.found)
        // 四方向正交曼哈顿距离：|0-4| + |0-4| = 8
        assertEquals(8, result.totalSteps)
        assertEquals(9, result.path.size)
        assertEquals(Coordinate(0, 0), result.path.first())
        assertEquals(Coordinate(4, 4), result.path.last())
        assertEquals("PATH_FOUND", result.reason)
    }

    @Test
    fun testIsolatedIslandNoPath() {
        // 让我们设计一个绝对封闭的孤岛：
        // 用墙围绕整个 (2,2)：其邻居是 (2,1), (2,3), (1,2), (3,2)
        // 所以我们让这四个邻居全是墙 '#'，而 (2,2) 是 '.'
        val absoluteIsland = StringGrid(listOf(
            ".....",
            "..#..",
            ".#.#.",
            "..#..",
            "....."
        ))
        // 起点为 (0,0)，终点为 (2,2)
        // 邻居 (2,1) 对应 rows[1][2] = '#'
        // 邻居 (1,2) 对应 rows[2][1] = '#'
        // 邻居 (3,2) 对应 rows[3][2] = '#'
        // 邻居 (2,3) 对应 rows[2][3] = '#'
        // 终点 (2,2) 对应 rows[2][2] = '.'
        val result = engine.findPath(0, 0, 2, 2, absoluteIsland)
        assertFalse(result.found)
        assertEquals("NO_PATH", result.reason)
    }

    @Test
    fun testMaxStepsFusing() {
        // 10x10 的无阻通畅地图，起点 (0,0) 到终点 (8,8)
        val grid = StringGrid(List(10) { ".........." })
        // 限制最大步数 5，但理论曼哈顿跨度为 16，肯定超标
        val result = engine.findPath(0, 0, 8, 8, grid, maxSteps = 5)
        assertFalse(result.found)
        assertEquals("STEP_LIMIT_EXCEEDED", result.reason)
    }

    // ─── 4.3 超大搜索空间安全剪枝与极限性能验证 ─────────────────────────

    @Test
    fun testUshapedMazeDetour() {
        // U 型迷宫开口向左
        // y=0: ........
        // y=1: ..##....  -> x=2,3 是墙
        // y=2: ...#....  -> x=3 是墙
        // y=3: ..##....  -> x=2,3 是墙
        // y=4: ........
        val maze = StringGrid(listOf(
            "........",
            "..##....",
            "...#....",
            "..##....",
            "........"
        ))

        // 起点 (2,2)，终点 (5,2)
        val result = engine.findPath(2, 2, 5, 2, maze)
        assertTrue(result.found)
        assertEquals("PATH_FOUND", result.reason)

        // 验证没有穿墙 (2,2) -> (3,2) -> (4,2)
        // 正常合理绕行路径： (2,2) -> (1,2) -> (1,1) -> (1,0) -> (2,0) -> (3,0) -> (4,0) -> (5,0) -> (5,1) -> (5,2)
        // 步数肯定大于 3（直线穿墙步数），证明算法完美绕行
        assertTrue(result.totalSteps > 3)

        // 验证路径中每个坐标确实是通行的，且连续相邻
        var prev: Coordinate? = null
        for (coord in result.path) {
            assertTrue(maze.isPassable(coord.x, coord.y), "Path point $coord must be passable")
            if (prev != null) {
                val dx = kotlin.math.abs(coord.x - prev.x)
                val dy = kotlin.math.abs(coord.y - prev.y)
                assertEquals(1, dx + dy, "Path must only step orthogonally")
            }
            prev = coord
        }
    }

    @Test
    fun testMaxOpenNodesOverflowFusing() {
        // 50x50 的空旷地图
        val grid = StringGrid(List(50) { ".".repeat(50) })
        // 极低 openSet 待探上限，必爆
        val result = engine.findPath(0, 0, 49, 49, grid, maxOpenNodes = 10)
        assertFalse(result.found)
        assertEquals("OPEN_SET_OVERFLOW", result.reason)
    }

    @Test
    fun testDualLimitIndependentFusing() {
        // 构建中等复杂度地图 10x10
        val grid = StringGrid(List(10) { ".........." })
        // 设定极低的 maxSteps = 5，而 maxOpenNodes 非常宽绰
        val result = engine.findPath(0, 0, 8, 8, grid, maxSteps = 5, maxOpenNodes = 2000)
        assertFalse(result.found)
        assertEquals("STEP_LIMIT_EXCEEDED", result.reason)
    }

    @Test
    fun test100x100PerformanceRegression() {
        // 初始化 100x100 的全空旷网格
        val grid = StringGrid(List(100) { ".".repeat(100) })

        // JVM 预热，连续跑 100 次寻路
        repeat(100) {
            engine.findPath(0, 0, 99, 99, grid, maxSteps = 10000)
        }

        // 第 100 次计时测试
        val start = System.nanoTime()
        val result = engine.findPath(0, 0, 99, 99, grid, maxSteps = 10000)
        val durationMs = (System.nanoTime() - start) / 1_000_000.0

        assertTrue(result.found)
        assertEquals(198, result.totalSteps) // 99 + 99 = 198
        assertTrue(durationMs < 10.0, "Performance failure: 100x100 pathfinding took $durationMs ms (limit < 10ms)")
    }
}
