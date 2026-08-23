package ru.ruscrafting.ecojobs.exploration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ExplorationTriggerBytecodeTest : StringSpec({
    "discovery dispatch avoids libreforge's relocated Kotlin default constructor" {
        val resource = "/${LibreforgeExplorationTrigger::class.java.name.replace('.', '/')}.class"
        val bytecode = requireNotNull(LibreforgeExplorationTrigger::class.java.getResourceAsStream(resource)) {
            "missing compiled exploration trigger"
        }.use { it.readAllBytes().toString(Charsets.ISO_8859_1) }

        val stableConstructor =
            "(Lcom/willfp/libreforge/Dispatcher;Lorg/bukkit/entity/Player;" +
            "Lorg/bukkit/entity/LivingEntity;Lorg/bukkit/block/Block;Lorg/bukkit/event/Event;" +
            "Lorg/bukkit/Location;Lorg/bukkit/entity/Projectile;Lorg/bukkit/util/Vector;" +
            "Lorg/bukkit/inventory/ItemStack;Ljava/lang/String;DD)V"
        val relocatedDefaultConstructor =
            stableConstructor.removeSuffix(")V") + "I" +
            "Lkotlin/jvm/internal/DefaultConstructorMarker;)V"

        bytecode shouldContain stableConstructor
        bytecode shouldNotContain relocatedDefaultConstructor
    }
})
