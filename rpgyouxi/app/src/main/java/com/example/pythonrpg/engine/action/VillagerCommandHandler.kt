package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.PlayerCommand

/**
 * VillagerCommandHandler - 处理与村民意图相关的所有指令
 */
public class VillagerCommandHandler : CommandHandler {
    
    override fun canHandle(command: PlayerCommand): Boolean {
        return command is PlayerCommand.AssignJob ||
               command is PlayerCommand.ReturnHome ||
               command is PlayerCommand.EquipTool ||
               command is PlayerCommand.RecruitVillager
    }

    override fun handle(command: PlayerCommand) {
        when (command) {
            is PlayerCommand.AssignJob -> {
                // 设置详细状态与初始岗位
                VillagerStateRegistry.detailedStates[command.villagerId] = "WORKING"
                VillagerStateRegistry.originalJobs[command.villagerId] = command.job
            }
            is PlayerCommand.ReturnHome -> {
                VillagerStateRegistry.detailedStates[command.villagerId] = "RESTING"
            }
            is PlayerCommand.EquipTool -> {
                VillagerStateRegistry.detailedStates[command.villagerId] = "EQUIPPING"
                VillagerStateRegistry.equipToolTargets[command.villagerId] = command.toolId
            }
            is PlayerCommand.RecruitVillager -> {
                // 此处主要记录招募意图，实体生成可能交由底层的 EntityStateManager 同步落盘
            }
            else -> {}
        }
    }
}
