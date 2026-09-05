package ru.ruscrafting.ecojobs.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen

/** Native dialog rendering is unavailable in MockBukkit; tests capture the same screen model. */
interface JobsDialogDisplay : AutoCloseable {
    fun show(player: Player, screen: PaperDialogScreen)
    fun close(player: Player)
}

internal class NativeJobsDialogDisplay(plugin: Plugin) : JobsDialogDisplay {
    private val runtime = PaperDialogRuntime(plugin)
    override fun show(player: Player, screen: PaperDialogScreen) = runtime.open(player, screen)
    override fun close(player: Player) = player.closeDialog()
    override fun close() = runtime.close()
}
