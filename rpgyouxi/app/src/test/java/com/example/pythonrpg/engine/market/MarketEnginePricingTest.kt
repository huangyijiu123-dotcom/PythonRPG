package com.example.pythonrpg.engine.market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MarketEnginePricingTest {

    @Test
    fun testZeroSupplyMaximumPrice() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "农耕之城",
            cityType = CityType.FARMING,
            x = 10,
            y = 12,
            inventory = mutableMapOf("FOOD" to 0),
            basePrices = mutableMapOf("FOOD" to 100.0f),
            demandCoefficients = mapOf("FOOD" to 0.5f),
            dailyConsumptionRate = mutableMapOf("FOOD" to 0.05f),
            reputation = 1.0f
        )
        engine.registerCityState(city)

        val priceInfo = engine.getPriceInfo(1L, "FOOD")
        assertTrue(priceInfo != null)
        assertEquals(0.0f, priceInfo.supplyRate)
        assertEquals(150.0f, priceInfo.currentPrice) // 100 * 1.5 * 1.0 * 1.0
    }

    @Test
    fun testFullSupplyZeroPriceClamping() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "农耕之城",
            cityType = CityType.FARMING,
            x = 10,
            y = 12,
            inventory = mutableMapOf("FOOD" to 1000), // Max capacity
            basePrices = mutableMapOf("FOOD" to 100.0f),
            demandCoefficients = mapOf("FOOD" to 0.5f),
            dailyConsumptionRate = mutableMapOf("FOOD" to 0.05f),
            reputation = 1.0f
        )
        engine.registerCityState(city)

        val priceInfo = engine.getPriceInfo(1L, "FOOD")
        assertTrue(priceInfo != null)
        assertEquals(1.0f, priceInfo.supplyRate)
        assertEquals(0.0f, priceInfo.currentPrice) // 100 * 1.5 * 0.0 * 1.0
    }

    @Test
    fun testReputationPenaltyMultiplier() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "农耕之城",
            cityType = CityType.FARMING,
            x = 10,
            y = 12,
            inventory = mutableMapOf("FOOD" to 0),
            basePrices = mutableMapOf("FOOD" to 100.0f),
            demandCoefficients = mapOf("FOOD" to 0.0f), // No demand coefficient
            dailyConsumptionRate = mutableMapOf("FOOD" to 0.05f),
            reputation = -0.1f // Unfavorable reputation
        )
        engine.registerCityState(city)

        val priceInfo = engine.getPriceInfo(1L, "FOOD")
        assertTrue(priceInfo != null)
        assertEquals(90.0f, priceInfo.currentPrice) // 100 * 1.0 * 1.0 * 0.9 = 90.0f
    }

    @Test
    fun testReputationRecoveryAndSuspensionUnlock() {
        val engine = MarketEngine()
        val city = CityStateData(
            id = 1L,
            name = "农耕之城",
            cityType = CityType.FARMING,
            x = 10,
            y = 12,
            inventory = mutableMapOf("FOOD" to 10),
            basePrices = mutableMapOf("FOOD" to 100.0f),
            demandCoefficients = mapOf("FOOD" to 0.0f),
            dailyConsumptionRate = mutableMapOf("FOOD" to 0.05f),
            reputation = -0.31f // Triggers suspension
        )
        engine.registerCityState(city)

        // Trigger suspension evaluation by pricing query
        engine.getPriceInfo(1L, "FOOD")
        assertTrue(city.isSuspended)

        // Recovery 1: reputation goes to -0.29
        engine.processReputationRecovery(1L)
        assertEquals(-0.29f, city.reputation, 1e-4f)
        assertFalse(city.isSuspended) // Automatically unlocked!

        // Recovery 2: reputation goes to -0.27
        engine.processReputationRecovery(1L)
        assertEquals(-0.27f, city.reputation, 1e-4f)
        assertFalse(city.isSuspended)
    }
}
