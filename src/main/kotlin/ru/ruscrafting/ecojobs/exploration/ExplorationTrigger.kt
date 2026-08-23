package ru.ruscrafting.ecojobs.exploration

import com.willfp.libreforge.toDispatcher
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.impl.TriggerGroupCustom
import org.bukkit.Bukkit
import org.bukkit.entity.Player

fun interface ExplorationTrigger {
    fun dispatch(player: Player, rank: Int, experienceFactor: Double, moneyFactor: Double)
}

class LibreforgeExplorationTrigger : ExplorationTrigger {
    override fun dispatch(player: Player, rank: Int, experienceFactor: Double, moneyFactor: Double) {
        check(Bukkit.isPrimaryThread()) { "chunk discovery triggers must be dispatched on the server thread" }
        require(rank in 1..MySqlDiscoveryLedger.MAX_DISCOVERERS) { "invalid chunk discovery rank" }
        require(experienceFactor > 0.0 && experienceFactor <= 1.0) { "invalid discovery experience factor" }
        require(moneyFactor > 0.0 && moneyFactor <= 1.0) { "invalid discovery money factor" }
        val dispatcher = player.toDispatcher()
        val data = TriggerData(
            dispatcher = dispatcher,
            player = player,
            location = player.location,
            value = experienceFactor,
            altValue = moneyFactor,
        )
        TriggerGroupCustom.create(TRIGGER_ID).dispatch(dispatcher, data)
    }

    companion object {
        const val TRIGGER_ID = "arcecojobs_discover_chunk"
        const val CONFIG_TRIGGER_ID = "custom_$TRIGGER_ID"
    }
}
