package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.WeatherModifiers
import com.example.pythonrpg.shared.PolicyModifiers

/**
 * ITickableSystem - 游戏时钟周期自转驱动系统接口
 */
public interface ITickableSystem {
    public fun processTick(
        tickId: Long,
        weatherModifier: WeatherModifiers,
        policyModifier: PolicyModifiers
    )
}
