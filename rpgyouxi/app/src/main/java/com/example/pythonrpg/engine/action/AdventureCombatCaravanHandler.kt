package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.PlayerCommand

/**
 * AdventureCombatCaravanHandler - 处理商队、冒险者及战斗相关的指令
 */
public class AdventureCombatCaravanHandler : CommandHandler {

    override fun canHandle(command: PlayerCommand): Boolean {
        return command is PlayerCommand.DispatchAdventurer ||
               command is PlayerCommand.RecallAdventurer ||
               command is PlayerCommand.AssignCaravanTarget ||
               command is PlayerCommand.StartCaravan ||
               command is PlayerCommand.RecallCaravan ||
               command is PlayerCommand.TradeWithCityState
    }

    override fun handle(command: PlayerCommand) {
        when (command) {
            is PlayerCommand.DispatchAdventurer -> {
            }
            is PlayerCommand.RecallAdventurer -> {
            }
            is PlayerCommand.AssignCaravanTarget -> {
            }
            is PlayerCommand.StartCaravan -> {
            }
            is PlayerCommand.RecallCaravan -> {
            }
            is PlayerCommand.TradeWithCityState -> {
            }
            else -> {}
        }
    }
}
