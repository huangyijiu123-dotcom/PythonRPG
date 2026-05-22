package com.example.pythonrpg.engine.market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MarketEngineDumpingTest {

    @Test
    fun testSingleTransactionDumpingTrigger() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "交易都市",
            cityType = CityType.TRADING,
            x = 0,
            y = 0,
            inventory = mutableMapOf("WOOD" to 100),
            maxCapacity = 1000,
            basePrices = mutableMapOf("WOOD" to 50.0f),
            demandCoefficients = mapOf("WOOD" to 0.1f),
            dailyConsumptionRate = mutableMapOf("WOOD" to 0.05f),
            reputation = 1.0f
        )
        engine.registerCityState(city)

        // 单次卖出 201 个，超过 maxCapacity (1000) * 20% = 200
        val triggers = engine.willTriggerDumping(1L, "WOOD", 201, 10L)
        assertTrue(triggers)

        // 单次卖出 200 个，未超过 20%
        val notTriggers = engine.willTriggerDumping(1L, "WOOD", 200, 10L)
        assertFalse(notTriggers)
    }

    @Test
    fun testFiveTickRollingWindowDumpingAndCleanup() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "交易都市",
            cityType = CityType.TRADING,
            x = 0,
            y = 0,
            inventory = mutableMapOf("WOOD" to 100),
            maxCapacity = 1000,
            basePrices = mutableMapOf("WOOD" to 50.0f),
            demandCoefficients = mapOf("WOOD" to 0.1f),
            dailyConsumptionRate = mutableMapOf("WOOD" to 0.05f),
            reputation = 1.0f
        )
        engine.registerCityState(city)

        // 在 tick = 10 时，卖出 150 个 WOOD (写入 tradeHistory)
        city.tradeHistory.add(TradeRecord(10L, "WOOD", 150, TradeDirection.SELL))

        // 在 tick = 12 时，预判卖出 160 个 WOOD (两次累计 310 > 300)
        val triggers = engine.willTriggerDumping(1L, "WOOD", 160, 12L)
        assertTrue(triggers)

        // 清理历史垃圾：在 tick = 16 时，清理超过 5 ticks 的记录 (tick < 16 - 5 = 11)
        // 那么 tick = 10 的记录应该被清掉
        engine.cleanupTradeHistory(1L, 16L)
        
        // 再次预判在 tick = 16 卖出 160 个
        val triggersAfterCleanup = engine.willTriggerDumping(1L, "WOOD", 160, 16L)
        assertFalse(triggersAfterCleanup) // 历史已清理，仅 160 <= 300，不触发倾销
    }

    @Test
    fun testPermanentMemoryPriceAndConsumptionRateCuts() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "交易都市",
            cityType = CityType.TRADING,
            x = 0,
            y = 0,
            inventory = mutableMapOf("STONE" to 100),
            maxCapacity = 1000,
            basePrices = mutableMapOf("STONE" to 100.0f),
            demandCoefficients = mapOf("STONE" to 0.0f),
            dailyConsumptionRate = mutableMapOf("STONE" to 0.10f),
            reputation = 1.0f
        )
        engine.registerCityState(city)

        // 连续触发 3 次倾销惩罚
        // 每次我们卖出 201 个，这会触发单笔倾销
        // 为了使每次 sell 都能成功执行（且不会把容量塞满导致之后拒绝买入，我们可以适度调整当前库存）
        // 第一次倾销卖出 201 (库存变成 301)
        val res1 = engine.sell(1L, "STONE", 201, 10L)
        assertTrue(res1.success)
        assertTrue(res1.dumpingPenaltyApplied)
        assertEquals(1, city.dumpingCounts["STONE"])
        assertEquals(100.0f, city.basePrices["STONE"]) // 还没到 3 次

        // 第二次倾销：在 tick = 11 卖出 201 (库存变成 502)
        val res2 = engine.sell(1L, "STONE", 201, 11L)
        assertTrue(res2.success)
        assertTrue(res2.dumpingPenaltyApplied)
        assertEquals(2, city.dumpingCounts["STONE"])
        assertEquals(100.0f, city.basePrices["STONE"]) // 还没到 3 次

        // 第三次倾销：在 tick = 12 卖出 201 (库存变成 703)
        val res3 = engine.sell(1L, "STONE", 201, 12L)
        assertTrue(res3.success)
        assertTrue(res3.dumpingPenaltyApplied)
        assertEquals(3, city.dumpingCounts["STONE"])
        // 触发第 3 次倾销，基准单价永久打九折：100.0f -> 90.0f
        assertEquals(90.0f, city.basePrices["STONE"]!!, 1e-4f)
        assertEquals(0.10f, city.dailyConsumptionRate["STONE"]!!, 1e-4f) // 消耗率还没降

        // 第四次倾销：在 tick = 13 卖出 201 (由于 capacity 剩余 1000 - 703 = 297, 可以成功成交 201)
        val res4 = engine.sell(1L, "STONE", 201, 13L)
        assertTrue(res4.success)
        assertTrue(res4.dumpingPenaltyApplied)
        assertEquals(4, city.dumpingCounts["STONE"])
        assertEquals(90.0f, city.basePrices["STONE"]!!, 1e-4f)

        // 第五次倾销：为了能成功卖出 201，先把库存扣减一些
        city.inventory["STONE"] = 100
        val res5 = engine.sell(1L, "STONE", 201, 14L)
        assertTrue(res5.success)
        assertTrue(res5.dumpingPenaltyApplied)
        assertEquals(5, city.dumpingCounts["STONE"])
        // 触发第 5 次倾销，日常自然消耗率永久打八折：0.10f -> 0.08f
        assertEquals(0.08f, city.dailyConsumptionRate["STONE"]!!, 1e-4f)
        // 基准价格仍为 90.0f (在 count==3 时修改过了)
        assertEquals(90.0f, city.basePrices["STONE"]!!, 1e-4f)
    }
}
