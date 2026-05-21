package com.example.pythonrpg.engine.market

/**
 * MarketEngine - 城邦经济市场引擎 (Phase 1 骨架Stub)
 */
class MarketEngine {
    open fun processTick() {}
    open fun calculatePrice(item: String, amount: Int, isBuy: Boolean): Int = amount * 10
}
