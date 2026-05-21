package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.PlayerCommand

/**
 * CommandHandler - 玩家/脚本指令处理器抽象接口
 */
public interface CommandHandler {
    public fun canHandle(command: PlayerCommand): Boolean
    public fun handle(command: PlayerCommand)
}
