package com.example.pythonrpg.engine.workshop

/**
 * WorkshopEngine - 工坊生产引擎 (Phase 1 骨架Stub)
 */
class WorkshopEngine {
    open fun processTick() {}
    open fun enqueue(workshopId: Long, toolType: String, count: Int): Boolean = true
}
