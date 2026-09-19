package ru.ruscrafting.ecojobs.integration

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.net.URLClassLoader
import java.util.UUID

class ArcTelemetryOptionalTest : FreeSpec({
    "does not load the optional ARC API when ARC is absent" {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.server.pluginManager.isPluginEnabled("ARC") shouldBe false
            val productName = "ru.ruscrafting.ecojobs.integration.ArcProductTelemetry"
            val workName = "ru.ruscrafting.ecojobs.integration.ArcJobWorkTelemetry"
            val targetNames = setOf(productName, workName)
            val source = ArcProductTelemetry::class.java.protectionDomain.codeSource.location
            var apiLoads = 0
            object : URLClassLoader(arrayOf(source), ArcProductTelemetry::class.java.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name.startsWith("ru.arc.paper.api.")) {
                        apiLoads++
                        throw ClassNotFoundException(name)
                    }
                    if (targetNames.any(name::startsWith)) {
                        return (findLoadedClass(name) ?: findClass(name)).also {
                            if (resolve) resolveClass(it)
                        }
                    }
                    return super.loadClass(name, resolve)
                }
            }.use { isolated ->
                val playerId = UUID.randomUUID()
                val operationId = UUID.randomUUID()
                val productType = isolated.loadClass(productName)
                val product = productType.getField("INSTANCE").get(null)
                productType.getMethod("purchased", UUID::class.java, UUID::class.java)
                    .invoke(product, playerId, operationId)
                productType.getMethod("activated", UUID::class.java, UUID::class.java)
                    .invoke(product, playerId, operationId)
                productType.getMethod("workBlocked", UUID::class.java, String::class.java)
                    .invoke(product, playerId, "afk")

                val workType = isolated.loadClass(workName)
                val work = workType.getField("INSTANCE").get(null)
                workType.getMethod("record", UUID::class.java, String::class.java)
                    .invoke(work, playerId, "miner") shouldBe false
                workType.getMethod("breakPlayer", UUID::class.java).invoke(work, playerId)

                apiLoads shouldBe 0
            }
        }
    }
})
