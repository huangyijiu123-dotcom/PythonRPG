package com.example.pythonrpg.engine.market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MarketEngineExecutionTest {

    @Test
    fun testSellCapacityClamping() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "农耕城邦",
            cityType = CityType.FARMING,
            x = 10,
            y = 10,
            inventory = mutableMapOf("WOOD" to 995), // 差 5 个爆满
            maxCapacity = 1000,
            basePrices = mutableMapOf("WOOD" to 10.0f),
            demandCoefficients = mapOf("WOOD" to 0.0f),
            dailyConsumptionRate = mutableMapOf("WOOD" to 0.05f),
            reputation = 1.0f
        )
        engine.registerCityState(city)

        // 尝试卖出 20 个 WOOD
        val result = engine.sell(1L, "WOOD", 20, 10L)
        assertTrue(result.success)
        assertEquals(5, result.actualAmount) // 应该被物理截断为 5
        assertEquals(1000, city.inventory["WOOD"]) // 刚好爆满

        // 再次尝试卖出，直接被拒绝
        val resultFull = engine.sell(1L, "WOOD", 5, 11L)
        assertFalse(resultFull.success)
        assertEquals(0, resultFull.actualAmount)
    }

    @Test
    fun testBuyInventoryClamping() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "农耕城邦",
            cityType = CityType.FARMING,
            x = 10,
            y = 10,
            inventory = mutableMapOf("FOOD" to 3), // 仅剩 3 个
            maxCapacity = 1000,
            basePrices = mutableMapOf("FOOD" to 10.0f),
            demandCoefficients = mapOf("FOOD" to 0.0f),
            dailyConsumptionRate = mutableMapOf("FOOD" to 0.05f),
            reputation = 1.0f
        )
        engine.registerCityState(city)

        // 尝试买入 10 个 FOOD
        val result = engine.buy(1L, "FOOD", 10, 10L)
        assertTrue(result.success)
        assertEquals(3, result.actualAmount) // 应该被物理截断为 3
        assertEquals(0, city.inventory["FOOD"]) // 被买光
    }

    @Test
    fun testDumpingPricePenaltyAndReputationDeduction() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "贸易都市",
            cityType = CityType.TRADING,
            x = 0,
            y = 0,
            inventory = mutableMapOf("WOOD" to 100),
            maxCapacity = 1000,
            basePrices = mutableMapOf("WOOD" to 100.0f),
            demandCoefficients = mapOf("WOOD" to 0.0f),
            dailyConsumptionRate = mutableMapOf("WOOD" to 0.05f),
            reputation = 1.0f
        )
        engine.registerCityState(city)

        // 卖出 250 个 WOOD，单笔超过容量 20% (200)，触发倾销
        val result = engine.sell(1L, "WOOD", 250, 10L)
        assertTrue(result.success)
        assertTrue(result.dumpingPenaltyApplied)
        assertEquals(0.7f, result.penaltyPriceMultiplier, 1e-4f)
        assertEquals(0.8f, result.newReputation, 1e-4f) // 信誉从 1.0f 降至 0.8f
        assertEquals(0.8f, city.reputation, 1e-4f)
    }

    @Test
    fun testSuspendedTradingBlockage() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "贸易都市",
            cityType = CityType.TRADING,
            x = 0,
            y = 0,
            inventory = mutableMapOf("WOOD" to 100),
            maxCapacity = 1000,
            basePrices = mutableMapOf("WOOD" to 100.0f),
            demandCoefficients = mapOf("WOOD" to 0.0f),
            dailyConsumptionRate = mutableMapOf("WOOD" to 0.05f),
            reputation = -0.4f, // 此时已经低于 -0.3f
            isSuspended = true  // 标记挂起
        )
        engine.registerCityState(city)

        // 尝试卖出，直接返回失败
        val sellRes = engine.sell(1L, "WOOD", 10, 10L)
        assertFalse(sellRes.success)
        assertEquals(0, sellRes.actualAmount)
        assertEquals(100, city.inventory["WOOD"])

        // 尝试买入，直接返回失败
        val buyRes = engine.buy(1L, "WOOD", 10, 10L)
        assertFalse(buyRes.success)
        assertEquals(0, buyRes.actualAmount)
        assertEquals(100, city.inventory["WOOD"])
    }
}
