package ru.ruscrafting.ecojobs.integration

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.math.BigDecimal
import java.util.UUID

interface JobAuditBridge {
    fun mark(playerId: UUID, jobId: String, amount: BigDecimal): String?
    fun cancel(playerId: UUID, token: String?)
}

object NoopJobAuditBridge : JobAuditBridge {
    override fun mark(playerId: UUID, jobId: String, amount: BigDecimal): String? = null
    override fun cancel(playerId: UUID, token: String?) = Unit
}

/** Optional zero-dependency link to ARC's central audit context tracker. */
class ReflectiveArcAuditBridge private constructor(
    private val markHandle: MethodHandle,
    private val cancelHandle: MethodHandle,
) : JobAuditBridge {
    /** Both handles are resolved once at plugin startup; the payout hot path does no reflective lookup. */
    override fun mark(playerId: UUID, jobId: String, amount: BigDecimal): String? =
        runCatching { markHandle.invokeExact(playerId, jobId, amount.toDouble()) as String? }.getOrNull()

    override fun cancel(playerId: UUID, token: String?) {
        runCatching { cancelHandle.invokeExact(playerId, token) }
    }

    companion object {
        private val MARK_TYPE =
            MethodType.methodType(
                String::class.java,
                UUID::class.java,
                String::class.java,
                Double::class.javaPrimitiveType,
            )
        private val CANCEL_METHOD_TYPE =
            MethodType.methodType(
                Void.TYPE,
                UUID::class.java,
                String::class.java,
            )

        fun discover(className: String = "ru.arc.audit.ExternalEconomyAuditBridge"): JobAuditBridge =
            runCatching {
                val bridge = Class.forName(className)
                val lookup = MethodHandles.publicLookup()
                val rawCancel = lookup.findStatic(bridge, "cancel", CANCEL_METHOD_TYPE)
                ReflectiveArcAuditBridge(
                    markHandle = lookup.findStatic(bridge, "markJobReward", MARK_TYPE),
                    // Kotlin's signature-polymorphic call site expects an Object result. Adapt the
                    // void method once during discovery, never in the payout hot path.
                    cancelHandle =
                        MethodHandles.filterReturnValue(
                            rawCancel,
                            MethodHandles.constant(Any::class.java, null),
                        ),
                )
            }.getOrDefault(NoopJobAuditBridge)
    }
}
