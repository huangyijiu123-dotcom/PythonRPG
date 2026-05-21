package com.example.pythonrpg.engine.policy

import com.example.pythonrpg.shared.PolicyModifiers
import java.util.concurrent.ConcurrentHashMap

class PolicyEngine {
    
    // 维护当前生效的法令集合
    private val activePolicies = ConcurrentHashMap<String, Boolean>()

    fun processTick() {
        // 当前法令为永久生效（直到玩家取消）
        // 未来可以扩充具有时间期限的临时法令
    }

    fun enactPolicy(policyType: String, isActive: Boolean) {
        if (isActive) {
            activePolicies[policyType] = true
        } else {
            activePolicies.remove(policyType)
        }
    }

    fun isPolicyActive(policyType: String): Boolean {
        return activePolicies[policyType] == true
    }

    fun getModifiers(): PolicyModifiers {
        var base = PolicyModifiers()
        
        // 逐个叠加法案的乘数效应
        if (isPolicyActive("FRANTIC_GATHERING")) {
            base = base.copy(
                harvestYieldMultiplier = base.harvestYieldMultiplier * 1.3f,
                energyCostMultiplier = base.energyCostMultiplier * 1.2f
            )
        }
        
        if (isPolicyActive("RATIONING")) {
            base = base.copy(
                foodConsumptionMultiplier = base.foodConsumptionMultiplier * 0.5f,
                energyRestoreMultiplier = base.energyRestoreMultiplier * 0.7f
            )
        }
        
        if (isPolicyActive("FORCED_MARCH")) {
            base = base.copy(
                moveSpeedMultiplier = base.moveSpeedMultiplier * 1.5f,
                energyCostMultiplier = base.energyCostMultiplier * 1.5f,
                caravanSpeedMultiplier = base.caravanSpeedMultiplier * 1.5f
            )
        }

        return base
    }
}
