package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.PlayerCommand
import com.example.pythonrpg.engine.building.BuildingEngine
import com.example.pythonrpg.engine.building.BuildingType

/**
 * EconomyBuildPolicyHandler - 处理经济、建筑、科技相关的指令
 */
public class EconomyBuildPolicyHandler(
    private val buildingEngine: BuildingEngine? = null
) : CommandHandler {
    
    override fun canHandle(command: PlayerCommand): Boolean {
        return command is PlayerCommand.BuildBuilding ||
               command is PlayerCommand.UpgradeBuilding ||
               command is PlayerCommand.RecruitAdventurer ||
               command is PlayerCommand.StartResearch
    }

    override fun handle(command: PlayerCommand) {
        when (command) {
            is PlayerCommand.BuildBuilding -> {
                try {
                    val type = BuildingType.valueOf(command.buildingType)
                    buildingEngine?.startConstruction(type, command.x, command.y)
                } catch (e: IllegalArgumentException) {
                    // 无效建筑类型，忽略
                }
            }
            is PlayerCommand.UpgradeBuilding -> {
                val snap = buildingEngine?.getBuildingAt(command.x, command.y)
                if (snap != null) {
                    buildingEngine?.startUpgrade(snap.buildingId)
                }
            }
            is PlayerCommand.RecruitAdventurer -> {
                // 处理冒险者招募
            }
            is PlayerCommand.StartResearch -> {
                // 处理科技研究
            }
            else -> {}
        }
    }
}
