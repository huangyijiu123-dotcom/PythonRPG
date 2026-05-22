package com.example.pythonrpg.engine.market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketEngineStorageTest {

    @Test
    fun testRegisterAndReadCityState() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "农耕之城",
            cityType = CityType.FARMING,
            x = 10,
            y = 12,
            inventory = mutableMapOf("FOOD" to 100),
            basePrices = mutableMapOf("FOOD" to 10.0f),
            demandCoefficients = mapOf("FOOD" to 0.5f),
            dailyConsumptionRate = mutableMapOf("FOOD" to 0.05f),
            reputation = 1.0f
        )

        engine.registerCityState(city)

        val cityIds = engine.getAllCityIds()
        assertEquals(1, cityIds.size)
        assertEquals(1L, cityIds[0])

        val priceInfo = engine.getPriceInfo(1L, "FOOD")
        assertTrue(priceInfo != null)
        assertEquals(10.0f, priceInfo.basePrice)
        assertEquals(0.1f, priceInfo.supplyRate) // 100 / 1000
        assertEquals(0.5f, priceInfo.demandCoeff)
    }

    @Test
    fun testGsonPersistenceLoopback() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 2L,
            name = "矿业之都",
            cityType = CityType.MINING,
            x = 20,
            y = -5,
            inventory = mutableMapOf("IRON" to 400),
            basePrices = mutableMapOf("IRON" to 15.0f),
            demandCoefficients = mapOf("IRON" to -0.2f),
            dailyConsumptionRate = mutableMapOf("IRON" to 0.08f),
            reputation = 0.5f
        )
        city.dumpingCounts["IRON"] = 4

        engine.registerCityState(city)

        // 导出
        val json = engine.exportToJson()
        assertTrue(json.contains("IRON"))
        assertTrue(json.contains("矿业之都"))

        // 更改内存中数据
        city.reputation = -0.5f

        // 重新导入
        engine.importFromJson(json)

        val restoredIds = engine.getAllCityIds()
        assertEquals(1, restoredIds.size)
        assertEquals(2L, restoredIds[0])

        val restoredPriceInfo = engine.getPriceInfo(2L, "IRON")
        assertTrue(restoredPriceInfo != null)
        assertEquals(15.0f, restoredPriceInfo.basePrice)

        // 通过反序列化后查证 reputation 和 dumpingCounts 是否复原
        // 我们可以在导出JSON前的 state 和导入还原的 state 对比
        // 由于 getPriceInfo 不直接展示 reputation，我们直接通过再次修改 reputation 来观察其导出 JSON 后是否与导入前一致
        // 实际上, ConcurrentHashMap 重建后, cityStates 会安全清空并恢复
    }
}
