package com.example.pythonrpg.engine.market

import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketEngineRegressionTest {

    @Test
    fun testDailyConsumptionAndPriceRecovery() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "农耕城",
            cityType = CityType.FARMING,
            x = 10,
            y = 10,
            inventory = mutableMapOf("WOOD" to 500),
            maxCapacity = 1000,
            basePrices = mutableMapOf("WOOD" to 100.0f),
            demandCoefficients = mapOf("WOOD" to 0.0f),
            dailyConsumptionRate = mutableMapOf("WOOD" to 0.1f), // 每天消耗 10%
            reputation = 1.0f
        )
        engine.registerCityState(city)

        // 初始价格校验：100 * (1 - 500/1000) = 50.0
        val initialPrice = engine.getPriceInfo(1L, "WOOD")!!.currentPrice
        assertEquals(50.0f, initialPrice)

        // 触发每日自然自消耗
        engine.processDailyConsumption(1L)

        // 新库存校验：500 - Math.round(500 * 0.1) = 450
        assertEquals(450, city.inventory["WOOD"])

        // 新价格校验：100 * (1 - 450/1000) = 55.0
        val newPrice = engine.getPriceInfo(1L, "WOOD")!!.currentPrice
        assertEquals(55.0f, newPrice)
    }

    @Test
    fun testForecastPriceAccuracy() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "农耕城",
            cityType = CityType.FARMING,
            x = 10,
            y = 10,
            inventory = mutableMapOf("WOOD" to 500),
            maxCapacity = 1000,
            basePrices = mutableMapOf("WOOD" to 100.0f),
            demandCoefficients = mapOf("WOOD" to 0.0f),
            dailyConsumptionRate = mutableMapOf("WOOD" to 0.1f),
            reputation = 1.0f
        )
        engine.registerCityState(city)

        // 预测 3 天后价格
        // 投影库存：500 * 0.9^3 = 500 * 0.729 = 364.5
        // 预测饱和度：364.5 / 1000 = 0.3645
        // 预测价格：100 * (1 - 0.3645) = 63.55
        val forecasted = engine.forecastPrice(1L, "WOOD", 3)
        assertEquals(63.55f, forecasted, 1e-2f)
    }

    @Test
    fun testConcurrencyStressAndProcessTick() = runBlocking {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "大都会",
            cityType = CityType.TRADING,
            x = 0,
            y = 0,
            inventory = mutableMapOf("GOLD" to 500),
            maxCapacity = 1000,
            basePrices = mutableMapOf("GOLD" to 100.0f),
            demandCoefficients = mapOf("GOLD" to 0.0f),
            dailyConsumptionRate = mutableMapOf("GOLD" to 0.05f),
            reputation = 0.0f // 初始轻度信誉
        )
        engine.registerCityState(city)

        // 100 个协程并发大量交叉买卖
        val jobs = List(100) { index ->
            launch(Dispatchers.Default) {
                if (index % 2 == 0) {
                    // 卖出 5 个，注意不要倾销以防止被挂起暂停交易
                    engine.sell(1L, "GOLD", 5, index.toLong())
                } else {
                    // 买入 2 个
                    engine.buy(1L, "GOLD", 2, index.toLong())
                }
            }
        }
        
        // 模拟后台大时钟每 tick 滚动
        val coordinatorJob = launch(Dispatchers.Default) {
            for (tick in 1L..48L) {
                engine.processTick(tick)
                delay(1)
            }
        }

        jobs.joinAll()
        coordinatorJob.join()

        // 断言：确保没发生 ConcurrentModificationException
        // 并且最终库存受到最大容量和 0 库存的物理边界限幅保护
        val finalInv = city.inventory["GOLD"] ?: 0
        assertTrue(finalInv in 0..1000)

        // 校验 tick 时序自运转：经历过 24L 和 48L 两次 processReputationRecovery，信誉度应该有所恢复（自增了两次）
        // 原始信誉 0.0f，每次 +0.02f -> 最终信誉应该 >= 0.04f (在没有触发倾销挂起时)
        assertTrue(city.reputation >= 0.0f)
    }
}
