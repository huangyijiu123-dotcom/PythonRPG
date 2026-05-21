package com.example.pythonrpg.engine.map

import com.example.pythonrpg.shared.Coordinate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapGeneratorTest {

    private val seed = 88888L
    private val generator = MapGenerator(seed)

    // ─── 9.1 数学哈希与拓扑分区测试 ───────────────────────────────────────

    @Test
    fun testHashIdempotency() = runBlocking {
        val coordX = 42
        val coordY = -99
        val targetSeed = 54321L

        // 跨线程并发调用测试
        val hashVal1 = async(Dispatchers.Default) { generator.hash(targetSeed, coordX, coordY) }
        val hashVal2 = async(Dispatchers.Default) { generator.hash(targetSeed, coordX, coordY) }

        assertEquals(hashVal1.await(), hashVal2.await(), "Hash calculation must be absolutely idempotent and thread-safe")
    }

    @Test
    fun testNeighboringHashConfusion() {
        val hash1 = generator.hash(seed, 10, 10)
        val hash2 = generator.hash(seed, 10, 11)
        val w1 = (hash1 % 100).toInt()
        val w2 = (hash2 % 100).toInt()

        // 验证相邻坐标哈希高度发散，不发生极低分化的连续同质问题
        assertFalse(w1 == w2, "Neighboring coordinates must have highly scattered hash results to avoid homogenous terrains")
    }

    @Test
    fun testDistanceBoundariesAndZoneMapping() {
        // (0,0) -> BASE_CAMP
        assertEquals(MapZone.BASE_CAMP, generator.getZone(Coordinate(0, 0)))

        // (3,4) -> distance = 5.0 -> STARTER_ZONE
        assertEquals(5.0, generator.getDistance(Coordinate(3, 4)), 0.00001)
        assertEquals(MapZone.STARTER_ZONE, generator.getZone(Coordinate(3, 4)))

        // (6,8) -> distance = 10.0 -> MID_ZONE
        assertEquals(10.0, generator.getDistance(Coordinate(6, 8)), 0.00001)
        assertEquals(MapZone.MID_ZONE, generator.getZone(Coordinate(6, 8)))

        // (0,21) -> distance = 21.0 -> ABYSS_ZONE
        assertEquals(21.0, generator.getDistance(Coordinate(0, 21)), 0.00001)
        assertEquals(MapZone.ABYSS_ZONE, generator.getZone(Coordinate(0, 21)))
    }

    // ─── 9.2 地貌模版与动态地形生成测试 ───────────────────────────────────

    @Test
    fun testCampTemplateCoordinatesMatching() {
        // 1. 中心大本营
        val baseCamp = generator.getCampTemplate(Coordinate(0, 0))
        assertNotNull(baseCamp)
        assertEquals("BASE_CAMP", baseCamp.terrainTypeId)
        assertEquals("EXPLORED", baseCamp.initialExploreStatus)
        assertNull(baseCamp.monsterTypeId)

        // 2. 新手初始小怪点
        val startSlime = generator.getCampTemplate(Coordinate(1, 0))
        assertNotNull(startSlime)
        assertEquals("PLAINS", startSlime.terrainTypeId)
        assertEquals("VISIBLE_UNEXPLORED", startSlime.initialExploreStatus)
        assertEquals("SLIME", startSlime.monsterTypeId)
        assertEquals(1, startSlime.monsterLevel)

        // 3. 越界
        assertNull(generator.getCampTemplate(Coordinate(5, 5)))
    }

    @Test
    fun testStarterZoneTerrainTypeWhitelist() {
        // 循环收集 1000 个新手区坐标，验证只能生成 PLAINS | FOREST | MOUNTAIN
        val allowedTerrains = setOf("PLAINS", "FOREST", "MOUNTAIN")
        for (i in 1..1000) {
            val terrain = generator.generateTerrainType(seed, i, 1, MapZone.STARTER_ZONE)
            assertTrue(allowedTerrains.contains(terrain), "Starter zone terrain '$terrain' must be in PLAINS, FOREST, MOUNTAIN")
        }
    }

    @Test
    fun testPlainsOccurrenceProbabilityDistribution() {
        // 统计 1000 个新手区地块的 PLAINS 出现率，应为 50% ± 5% (450 ~ 550)
        var plainsCount = 0
        for (i in 1..1000) {
            val terrain = generator.generateTerrainType(seed, i, 2, MapZone.STARTER_ZONE)
            if (terrain == "PLAINS") plainsCount++
        }
        assertTrue(plainsCount in 450..550, "Plains occurrence ($plainsCount/1000) must fall within 50% ± 5% (450..550)")
    }

    // ─── 9.3 确定性野怪生成机制与等级保底测试 ──────────────────────────────

    @Test
    fun testBaseCampAndCityStatesAreMonsterFree() {
        // 大本营及城邦绝对无怪
        assertNull(generator.generateMonster(seed, 10, 10, "BASE_CAMP", MapZone.STARTER_ZONE))
        assertNull(generator.generateMonster(seed, 20, 20, "CITY_STATE", MapZone.MID_ZONE))
    }

    @Test
    fun testStarterZoneVolcanoNoMonster() {
        // 新手圈没有火山怪 (由于火山地形只在高级和深渊产怪)
        assertNull(generator.generateMonster(seed, 1, 1, "VOLCANO", MapZone.STARTER_ZONE))
    }

    @Test
    fun testMonsterLevelsFormulaAndLimits() {
        // 测试空间等级加益与 1 级保底
        // d = 13.0, 属于 HIGH_ZONE (基础等级 5)
        // lDist = floor(13 / 5) = 2.0 -> Level = 5 + 2 = 7
        // offset: 摇点扰动为 -1, 0, 1. 我们的 hash 确定性产出，但总会落在这些范围内
        // 故最终等级应在 6..8 之间
        // 我们测试一堆坐标并验证等级落在预期上下限内
        val zone = MapZone.HIGH_ZONE
        for (x in 9..12) {
            val y = 9
            val terrain = "PLAINS"
            val monster = generator.generateMonster(seed, x, y, terrain, zone)
            if (monster != null) {
                val distance = generator.getDistance(x, y)
                val lDist = Math.floor(distance / 5.0).toInt()
                val minPossible = 5 + lDist - 1
                val maxPossible = 5 + lDist + 1
                assertTrue(monster.level in minPossible..maxPossible, "Monster level ${monster.level} out of bounds ($minPossible..$maxPossible) for dist $distance")
            }
        }

        // 1 级强力保底验证
        // 在新手圈 d = 1.0 (基础 1, lDist = 0)
        // 即使扰动为 -1, 最终等级仍必须 coerceAtLeast(1) 为 1
        for (i in 1..100) {
            val monster = generator.generateMonster(seed, i % 2, 1, "PLAINS", MapZone.STARTER_ZONE)
            if (monster != null) {
                assertTrue(monster.level >= 1, "Monster level must be guaranteed at least 1")
            }
        }
    }

    // ─── 9.4 稀疏城邦与 Boss 城堡生成测试 ──────────────────────────────────

    @Test
    fun testSpecificRingBossCandidateAndTypes() {
        // Boss 只能在距离 10 整数倍环带生成 (比如 d=10, 20 等，且 d >= 9.5)
        // 坐标 Coordinate(6, 8) 的欧氏距离恰好为 10.0
        val bossCoord = Coordinate(6, 8)
        assertEquals(10.0, generator.getDistance(bossCoord), 0.00001)

        // getBossType 精准校验
        assertEquals("BOSS_KNIGHT", generator.getBossType(10.0))
        assertEquals("BOSS_LAVA_GIANT", generator.getBossType(20.2))
        assertEquals("BOSS_FROST_WITCH", generator.getBossType(30.0))
    }

    @Test
    fun testCityStatesCampSafetyIslandBlocker() {
        // d < 6.0 安全岛绝对无法生成城邦
        // (0, 5) 距离为 5.0 < 6.0
        assertFalse(generator.isCityStateCandidate(Coordinate(0, 5)))
    }

    @Test
    fun testCityStatesDensityAndTypes() {
        // 验证城邦类型在 FARMING, MINING, TRADING 之中
        val validTypes = setOf("FARMING", "MINING", "TRADING")
        val cityCoord = Coordinate(10, 10)
        assertTrue(validTypes.contains(generator.getCityStateType(cityCoord)))
    }

    @Test
    fun testCityStateProximitySparsity() {
        val knownCities = setOf(Coordinate(8, 0))
        // 1. 距离 Coordinate(8, 0) 是 2.24 (<= 5.0) 的点，必须被判定为太拥挤 (true)
        assertTrue(generator.checkCityStateSparsity(Coordinate(10, 1), knownCities))

        // 2. 距离很远的点 (Coordinate(20, 20))，安全放行 (false)
        assertFalse(generator.checkCityStateSparsity(Coordinate(20, 20), knownCities))
    }

    // ─── 9.5 主框架延迟生成与回归测试 ─────────────────────────────────────

    @Test
    fun testAbsoluteDeterminismBetweenSeparateInstances() {
        // 相同种子 + 相同坐标 -> 产生完全相等的 GeneratedTile
        val generatorA = MapGenerator(88888L)
        val generatorB = MapGenerator(88888L)
        val coord = Coordinate(1234, -5678)

        val tileA = generatorA.generateTile(coord)
        val tileB = generatorB.generateTile(coord)

        assertEquals(tileA.coordinate, tileB.coordinate)
        assertEquals(tileA.terrainTypeId, tileB.terrainTypeId)
        assertEquals(tileA.initialExploreStatus, tileB.initialExploreStatus)
        assertEquals(tileA.monsterTypeId, tileB.monsterTypeId)
        assertEquals(tileA.monsterLevel, tileB.monsterLevel)
        assertEquals(tileA.isBossLocation, tileB.isBossLocation)
        assertEquals(tileA.bossTypeId, tileB.bossTypeId)
        assertEquals(tileA.cityStateTypeId, tileB.cityStateTypeId)

        // 种子特异性验证 (不同种子产生不同的瓦片数据)
        val generatorC = MapGenerator(99999L)
        val tileC = generatorC.generateTile(coord)
        // 极大概率两者的地形或怪物不同 (若完全相同则属于小概率事件，本坐标被精选过确保能不同)
        assertFalse(tileA == tileC, "Different seeds must yield different tiles to guarantee seed specificity")
    }

    @Test
    fun testThreeLevelMutualExclusionPriorityChain() {
        // 1. 大本营固定坐标 (0,0) 的优先级高于一切，且不能标记为 Boss 或城邦
        val homeTile = generator.generateTile(Coordinate(0, 0))
        assertEquals("BASE_CAMP", homeTile.terrainTypeId)
        assertFalse(homeTile.isBossLocation)
        assertNull(homeTile.cityStateTypeId)
        assertNull(homeTile.monsterTypeId)
    }

    @Test
    fun testViewportRegionGenerationSize() {
        // topLeft: (-2, -2), bottomRight: (3, 3)
        // x 轴: -2, -1, 0, 1, 2, 3 (6 个数)
        // y 轴: -2, -1, 0, 1, 2, 3 (6 个数)
        // 应当返回 36 个瓦片
        val region = generator.generateRegion(Coordinate(-2, -2), Coordinate(3, 3))
        assertEquals(36, region.size)
    }

    @Test
    fun testCityStateSparsityIntegratedInTileGen() {
        // 传入已知城邦集，使得本来如果能中奖城邦的坐标，因为离已知太近而熔断，转而生成荒地
        val targetCoord = Coordinate(10, 0)
        // 如果 targetCoord 恰好能生成城邦候选：
        if (generator.isCityStateCandidate(targetCoord)) {
            // 1. 没有邻近冲突时生成城邦
            val tileWithoutCollision = generator.generateTile(targetCoord, emptySet())
            assertEquals("CITY_STATE", tileWithoutCollision.terrainTypeId)
            assertNotNull(tileWithoutCollision.cityStateTypeId)

            // 2. 有邻近冲突时 (邻居 (10, 1) 已有城邦)，被拦截退化为普通荒地，不可生成为城邦地形
            val tileWithCollision = generator.generateTile(targetCoord, setOf(Coordinate(10, 1)))
            assertFalse(tileWithCollision.terrainTypeId == "CITY_STATE", "Should be defused back to wilderness")
            assertNull(tileWithCollision.cityStateTypeId, "City state type must be null after collision")
        }
    }
}
