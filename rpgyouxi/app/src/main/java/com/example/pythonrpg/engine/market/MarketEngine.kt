package com.example.pythonrpg.engine.market

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap

// 城邦类型：农耕城（食物过剩，缺木石）、矿业城（矿石过剩，缺食物）、贸易城（波动）
enum class CityType { FARMING, MINING, TRADING }

// 交易方向
enum class TradeDirection { SELL, BUY }

// 近期交易记录（滑窗判定）
data class TradeRecord(
    val tickId: Long,
    val item: String,
    val amount: Int, // 正数代表玩家卖给城邦，负数代表玩家从城邦买入
    val direction: TradeDirection
)

// 查价快照包
data class PriceInfo(
    val currentPrice: Float,
    val basePrice: Float,
    val supplyRate: Float,
    val demandCoeff: Float
)

// 交易回执
data class TradeResult(
    val success: Boolean,
    val actualAmount: Int,
    val goldEarned: Int,
    val dumpingPenaltyApplied: Boolean,
    val penaltyPriceMultiplier: Float,
    val newReputation: Float,
    val message: String
)

// 城邦中央数据模型
data class CityStateData(
    val id: Long,
    val name: String,
    val cityType: CityType,
    val x: Int,
    val y: Int,
    val inventory: MutableMap<String, Int>,                 // 资源实时库存
    val maxCapacity: Int = 1000,                            // 统一容量上限
    val basePrices: MutableMap<String, Float>,              // 基准价格（可被倾销记忆永久篡改）
    val demandCoefficients: Map<String, Float>,             // 需求系数
    val dailyConsumptionRate: MutableMap<String, Float>,    // 自然消耗率（可被倾销记忆永久篡改）
    var reputation: Float = 1.0f,                           // 信誉：-0.5 到 1.0
    var isSuspended: Boolean = false,                       // 信誉过低挂起暂停交易
    val tradeHistory: MutableList<TradeRecord> = mutableListOf(),
    val dumpingCounts: MutableMap<String, Int> = mutableMapOf() // 对特定物资的累计倾销次数（记忆机制）
)

/**
 * MarketEngine - 城邦经济市场引擎
 */
class MarketEngine {
    private val cityStates = ConcurrentHashMap<Long, CityStateData>()

    /**
     * 注册城邦
     */
    fun registerCityState(city: CityStateData) {
        cityStates[city.id] = city
    }

    /**
     * 获取所有城邦ID
     */
    fun getAllCityIds(): List<Long> {
        return cityStates.keys.toList()
    }

    /**
     * 全量导出为 JSON
     */
    fun exportToJson(): String {
        val safeList = cityStates.values.map { city ->
            synchronized(city) {
                city.copy(
                    inventory = city.inventory.toMutableMap(),
                    basePrices = city.basePrices.toMutableMap(),
                    dailyConsumptionRate = city.dailyConsumptionRate.toMutableMap(),
                    tradeHistory = city.tradeHistory.toMutableList(),
                    dumpingCounts = city.dumpingCounts.toMutableMap()
                )
            }
        }
        return Gson().toJson(safeList)
    }

    /**
     * 从 JSON 全量导入
     */
    fun importFromJson(json: String) {
        cityStates.clear()
        val listType = object : TypeToken<List<CityStateData>>() {}.type
        val list: List<CityStateData> = Gson().fromJson(json, listType)
        for (city in list) {
            registerCityState(city)
        }
    }

    /**
     * 实时获取城邦当前对某物资的实时价格信息
     */
    fun getPriceInfo(cityId: Long, item: String): PriceInfo? {
        val city = cityStates[cityId] ?: return null
        synchronized(city) {
            val basePrice = city.basePrices[item] ?: return null
            val demandCoeff = city.demandCoefficients[item] ?: 0.0f
            val currentInv = city.inventory[item] ?: 0
            val maxCap = city.maxCapacity.coerceAtLeast(1)
            val supplyRate = (currentInv.toFloat() / maxCap).coerceIn(0.0f, 1.0f)

            val rep = city.reputation
            val rMult = if (rep < 0.0f) {
                if (rep < -0.3f) {
                    city.isSuspended = true
                }
                0.9f
            } else {
                1.0f
            }

            val currentPrice = basePrice * (1.0f + demandCoeff) * (1.0f - supplyRate) * rMult
            return PriceInfo(currentPrice, basePrice, supplyRate, demandCoeff)
        }
    }

    /**
     * 信誉自然恢复（外部在每日结算时主动调用一次）
     */
    fun processReputationRecovery(cityId: Long) {
        val city = cityStates[cityId] ?: return
        synchronized(city) {
            if (city.reputation < 1.0f) {
                city.reputation = (city.reputation + 0.02f).coerceAtMost(1.0f)
            }
            if (city.reputation >= -0.3f && city.isSuspended) {
                city.isSuspended = false
            }
        }
    }

    /**
     * 快速增加信誉（递交紧急物资等）
     */
    fun boostReputation(cityId: Long, amount: Float) {
        val city = cityStates[cityId] ?: return
        synchronized(city) {
            city.reputation = (city.reputation + amount).coerceIn(-0.5f, 1.0f)
            if (city.reputation >= -0.3f && city.isSuspended) {
                city.isSuspended = false
            }
        }
    }

    /**
     * 预判是否会触发倾销惩罚
     */
    fun willTriggerDumping(cityId: Long, item: String, amount: Int, currentTickId: Long): Boolean {
        val city = cityStates[cityId] ?: return false
        synchronized(city) {
            val maxCap = city.maxCapacity
            
            // 1. 单次倾销阈值校验：超过最大容量的 20%
            if (amount > maxCap * 0.2) {
                return true
            }

            // 2. 滑窗累计阈值校验：5 ticks 内卖出超过 30%
            val historySum = city.tradeHistory
                .filter { it.item == item && it.direction == TradeDirection.SELL && it.tickId >= currentTickId - 5 }
                .sumOf { it.amount }

            if (historySum + amount > maxCap * 0.3) {
                return true
            }

            return false
        }
    }

    /**
     * 清理历史滑动窗口外的交易记录（超过 5 个 Tick 期限）
     */
    fun cleanupTradeHistory(cityId: Long, currentTickId: Long) {
        val city = cityStates[cityId] ?: return
        synchronized(city) {
            city.tradeHistory.removeIf { it.tickId < currentTickId - 5 }
        }
    }

    /**
     * 内部倾销惩罚执行器
     */
    private fun applyDumpingPenalty(city: CityStateData, item: String) {
        // 1. 扣减信誉
        city.reputation = (city.reputation - 0.2f).coerceIn(-0.5f, 1.0f)
        if (city.reputation < -0.3f) {
            city.isSuspended = true
        }

        // 2. 累加特定物资的倾销计数
        val count = (city.dumpingCounts[item] ?: 0) + 1
        city.dumpingCounts[item] = count

        // 3. 永久性就地不可逆篡改城邦记忆
        if (count == 3) {
            city.basePrices[item] = (city.basePrices[item] ?: 0.0f) * 0.9f
        } else if (count == 5) {
            city.dailyConsumptionRate[item] = (city.dailyConsumptionRate[item] ?: 0.05f) * 0.8f
        }
        
        println("🏙️ ${city.name}对${item}涌入感到震惊，开始抵制！倾销惩罚生效。")
    }

    /**
     * 玩家向城邦卖出物资结算
     */
    fun sell(cityId: Long, item: String, amount: Int, currentTickId: Long): TradeResult {
        val city = cityStates[cityId] ?: return TradeResult(
            success = false, actualAmount = 0, goldEarned = 0,
            dumpingPenaltyApplied = false, penaltyPriceMultiplier = 1.0f,
            newReputation = 1.0f, message = "未知城邦"
        )

        synchronized(city) {
            if (city.isSuspended) {
                return TradeResult(
                    success = false, actualAmount = 0, goldEarned = 0,
                    dumpingPenaltyApplied = false, penaltyPriceMultiplier = 1.0f,
                    newReputation = city.reputation, message = "城邦已暂停与大人的一切交易！"
                )
            }

            val currentInv = city.inventory[item] ?: 0
            val actual = Math.min(amount, city.maxCapacity - currentInv)

            if (actual <= 0) {
                return TradeResult(
                    success = false, actualAmount = 0, goldEarned = 0,
                    dumpingPenaltyApplied = false, penaltyPriceMultiplier = 1.0f,
                    newReputation = city.reputation, message = "城邦该物资已满仓，拒绝买入"
                )
            }

            val priceInfo = getPriceInfo(cityId, item) ?: return TradeResult(
                success = false, actualAmount = 0, goldEarned = 0,
                dumpingPenaltyApplied = false, penaltyPriceMultiplier = 1.0f,
                newReputation = city.reputation, message = "物资价格未配置"
            )

            var finalPrice = priceInfo.currentPrice
            var dumpingApplied = false
            var penaltyMultiplier = 1.0f

            if (willTriggerDumping(cityId, item, actual, currentTickId)) {
                finalPrice *= 0.7f
                dumpingApplied = true
                penaltyMultiplier = 0.7f
                applyDumpingPenalty(city, item)
            }

            city.inventory[item] = currentInv + actual
            city.tradeHistory.add(TradeRecord(currentTickId, item, actual, TradeDirection.SELL))

            val goldEarned = (finalPrice * actual).toInt()

            return TradeResult(
                success = true,
                actualAmount = actual,
                goldEarned = goldEarned,
                dumpingPenaltyApplied = dumpingApplied,
                penaltyPriceMultiplier = penaltyMultiplier,
                newReputation = city.reputation,
                message = "交易成功"
            )
        }
    }

    /**
     * 玩家向城邦买入物资结算
     */
    fun buy(cityId: Long, item: String, amount: Int, currentTickId: Long): TradeResult {
        val city = cityStates[cityId] ?: return TradeResult(
            success = false, actualAmount = 0, goldEarned = 0,
            dumpingPenaltyApplied = false, penaltyPriceMultiplier = 1.0f,
            newReputation = 1.0f, message = "未知城邦"
        )

        synchronized(city) {
            if (city.isSuspended) {
                return TradeResult(
                    success = false, actualAmount = 0, goldEarned = 0,
                    dumpingPenaltyApplied = false, penaltyPriceMultiplier = 1.0f,
                    newReputation = city.reputation, message = "城邦已暂停与大人的一切交易！"
                )
            }

            val currentInv = city.inventory[item] ?: 0
            val actual = Math.min(amount, currentInv)

            if (actual <= 0) {
                return TradeResult(
                    success = false, actualAmount = 0, goldEarned = 0,
                    dumpingPenaltyApplied = false, penaltyPriceMultiplier = 1.0f,
                    newReputation = city.reputation, message = "城邦该物资已经无货，拒绝卖出"
                )
            }

            // 买方不享受降价惩罚，且信誉修正不影响买入原价（即信誉修正系数为 1.0f 结算）
            val basePrice = city.basePrices[item] ?: return TradeResult(
                success = false, actualAmount = 0, goldEarned = 0,
                dumpingPenaltyApplied = false, penaltyPriceMultiplier = 1.0f,
                newReputation = city.reputation, message = "价格配置不存在"
            )
            val demandCoeff = city.demandCoefficients[item] ?: 0.0f
            val maxCap = city.maxCapacity.coerceAtLeast(1)
            val supplyRate = (currentInv.toFloat() / maxCap).coerceIn(0.0f, 1.0f)

            // 依据信誉惩罚隔离原则，买入价格不扣减 0.9（即 R_mult 为 1.0）
            val buyPrice = basePrice * (1.0f + demandCoeff) * (1.0f - supplyRate)

            city.inventory[item] = currentInv - actual
            city.tradeHistory.add(TradeRecord(currentTickId, item, -actual, TradeDirection.BUY))

            val goldCost = -(buyPrice * actual).toInt()

            return TradeResult(
                success = true,
                actualAmount = actual,
                goldEarned = goldCost,
                dumpingPenaltyApplied = false,
                penaltyPriceMultiplier = 1.0f,
                newReputation = city.reputation,
                message = "交易成功"
            )
        }
    }

    /**
     * 每日自然消耗结算（外部在游戏每日结束时主动调用）
     */
    fun processDailyConsumption(cityId: Long) {
        val city = cityStates[cityId] ?: return
        synchronized(city) {
            for ((item, currentInv) in city.inventory) {
                val rate = city.dailyConsumptionRate[item] ?: 0.05f
                val consumed = Math.round(currentInv * rate).toInt()
                val newInv = (currentInv - consumed).coerceIn(0, city.maxCapacity)
                city.inventory[item] = newInv
            }
        }
    }

    /**
     * 预测 N 天后的物价（基于自然消耗率动态推算，不计玩家买卖）
     */
    fun forecastPrice(cityId: Long, item: String, days: Int): Float {
        val city = cityStates[cityId] ?: return 0.0f
        synchronized(city) {
            val basePrice = city.basePrices[item] ?: return 0.0f
            val demandCoeff = city.demandCoefficients[item] ?: 0.0f
            val currentInv = city.inventory[item] ?: 0
            val maxCap = city.maxCapacity.coerceAtLeast(1)
            val rate = city.dailyConsumptionRate[item] ?: 0.05f

            val invProjected = currentInv * Math.pow((1.0 - rate).toDouble(), days.toDouble())
            val sProjected = (invProjected.toFloat() / maxCap).coerceIn(0.0f, 1.0f)

            // 预测时不计倾销，且设定信誉因子为 1.0f
            return basePrice * (1.0f + demandCoeff) * (1.0f - sProjected)
        }
    }

    /**
     * 对接中央游戏主循环每 Tick 时时钟清理与日结驱动
     */
    fun processTick(tickId: Long = 0L) {
        for (city in cityStates.values) {
            cleanupTradeHistory(city.id, tickId)
        }
        if (tickId % 24L == 0L) {
            for (city in cityStates.values) {
                processDailyConsumption(city.id)
                processReputationRecovery(city.id)
            }
        }
    }
}
