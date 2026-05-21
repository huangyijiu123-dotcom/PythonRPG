package com.example.pythonrpg.engine.action

import com.example.pythonrpg.shared.GameEvent
import com.example.pythonrpg.shared.PlayerCommand
import com.example.pythonrpg.shared.TickEvent
import com.example.pythonrpg.shared.TimePeriod
import com.example.pythonrpg.shared.PolicyModifiers
import kotlin.test.*

class ActionProcessorDispatcherTest {

    @Test
    fun testDispatcherAndEventPublishing() {
        val processor = ActionProcessor()
        
        var handledCommandCount = 0
        val dummyHandler = object : CommandHandler {
            override fun canHandle(command: PlayerCommand) = true
            override fun handle(command: PlayerCommand) {
                handledCommandCount++
            }
        }
        processor.registerHandler(dummyHandler)
        
        processor.queueCommand(PlayerCommand.RecruitVillager)
        processor.queueCommand(PlayerCommand.RecruitAdventurer)
        
        val tickEvent = TickEvent(1L, System.currentTimeMillis(), TimePeriod.DAYTIME)
        processor.processTick(tickEvent, PolicyModifiers(), com.example.pythonrpg.shared.WeatherModifiers())
        
        assertEquals(2, handledCommandCount)
        
        processor.publishEvent(GameEvent.VillagerLowEnergy(1L, 10))
        val events = processor.pollEvents()
        assertEquals(1, events.size)
        assertTrue(events[0] is GameEvent.VillagerLowEnergy)
    }
}
