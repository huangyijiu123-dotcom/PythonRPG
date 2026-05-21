package com.example.pythonrpg.engine.combat

/**
 * AutoBattleResult - 自动战斗单步结算结果
 */
public data class AutoBattleResult(
    val adventurerHpDelta: Int,    // 冒险者生命值变化量（通常为负，代表受到伤害）
    val isFinished: Boolean        // 战斗是否已经结束
)

/**
 * CombatEngine - 挂机自动战斗引擎 Stub (以供 Phase 3 编译)
 */
public class CombatEngine {
    open fun processTick() {}
    
    open fun processAutoBattle(sessionId: Long): AutoBattleResult {
        return AutoBattleResult(adventurerHpDelta = -5, isFinished = false)
    }
}
