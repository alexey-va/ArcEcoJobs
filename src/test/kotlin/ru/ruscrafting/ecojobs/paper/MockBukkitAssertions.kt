package ru.ruscrafting.ecojobs.paper

/**
 * The pinned public arc-core-paper-testing 2.0.3 predates the canonical
 * failOnUnsupportedMockBukkitOperation helper. Keep unsupported MockBukkit
 * calls red instead of letting JUnit report an unexecuted scenario as skipped.
 */
internal inline fun <T> requireSupportedMockBukkit(block: () -> T): T = try {
    block()
} catch (failure: Throwable) {
    val unsupported = generateSequence(failure as Throwable?) { it.cause }
        .firstOrNull { it.javaClass.name == MOCK_BUKKIT_UNIMPLEMENTED_OPERATION }
    if (unsupported != null) {
        throw AssertionError("MockBukkit scenario reached an unsupported Paper API operation").apply {
            initCause(unsupported)
        }
    }
    throw failure
}

private const val MOCK_BUKKIT_UNIMPLEMENTED_OPERATION =
    "org.mockbukkit.mockbukkit.exception.UnimplementedOperationException"
