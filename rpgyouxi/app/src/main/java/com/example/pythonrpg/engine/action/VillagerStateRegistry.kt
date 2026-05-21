package com.example.pythonrpg.engine.action

import java.util.concurrent.ConcurrentHashMap

/**
 * VillagerStateRegistry - 全局并发安全的村民意图与高级状态注册表
 * 专门用于在不修改只读数据库模型的前提下，在指令处理器与自转系统之间传递并持久化村民的物理执行意图。
 */
public object VillagerStateRegistry {
    // 存储村民的详细自转状态 (如 "RESTING", "MOVING", "WORKING", "DELIVERING", "EQUIPPING")
    public val detailedStates: ConcurrentHashMap<Long, String> = ConcurrentHashMap<Long, String>()
    
    // 存储村民物理宿民房的 ID
    public val cottageIds: ConcurrentHashMap<Long, Long> = ConcurrentHashMap<Long, Long>()
    
    // 临时存储村民前往仓库换装的目标工具模板 ID (如 "STONE_AXE", "IRON_AXE")
    public val equipToolTargets: ConcurrentHashMap<Long, String> = ConcurrentHashMap<Long, String>()
    
    // 存储村民工作时的原始岗位名称，用于从 DELIVERING/EQUIPPING 恢复到 WORKING 状态
    public val originalJobs: ConcurrentHashMap<Long, String> = ConcurrentHashMap<Long, String>()

    /**
     * 清空所有状态（通常仅在重载存档或测试清理时调用）
     */
    public fun clear() {
        detailedStates.clear()
        cottageIds.clear()
        equipToolTargets.clear()
        originalJobs.clear()
    }
}
