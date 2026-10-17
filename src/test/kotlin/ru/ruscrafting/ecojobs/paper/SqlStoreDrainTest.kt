package ru.ruscrafting.ecojobs.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.sql.SqlExecutor
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ecojobs.earnings.MySqlHourlyEarningsStore
import ru.ruscrafting.ecojobs.exploration.MySqlDiscoveryLedger
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

class SqlStoreDrainTest : FreeSpec({
    listOf<Pair<String, (SqlRuntime) -> AutoCloseable>>(
        "discovery" to ::MySqlDiscoveryLedger,
        "hourly earnings" to ::MySqlHourlyEarningsStore,
    ).forEach { (name, createStore) ->
        "$name retains the borrowed lease until accepted JDBC cleanup actually finishes" {
            val sqlWorker = BoundedAsyncExecutor("ecojobs-test-sql")
            val cleanupWorker = BoundedAsyncExecutor("ecojobs-test-cleanup")
            val enteredCleanup = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            val closeStarted = CountDownLatch(1)
            val leaseReleased = AtomicBoolean()
            val connection = mockk<Connection>(relaxed = true) {
                every { close() } answers {
                    enteredCleanup.countDown()
                    check(releaseCleanup.await(5, TimeUnit.SECONDS))
                }
            }
            val dataSource = mockk<DataSource> { every { this@mockk.connection } returns connection }
            val runtime = SqlRuntime.borrow(
                dataSource, sqlWorker, sqlWorker, AutoCloseable { leaseReleased.set(true) }, { },
            )
            val store = createStore(runtime)
            val accepted = runtime.executor.read { Unit }
            try {
                enteredCleanup.await(5, TimeUnit.SECONDS) shouldBe true
                val closing = cleanupWorker.submit { closeStarted.countDown(); store.close() }
                closeStarted.await(5, TimeUnit.SECONDS) shouldBe true
                shouldThrow<TimeoutException> { closing.get(100, TimeUnit.MILLISECONDS) }
                leaseReleased.get() shouldBe false
                releaseCleanup.countDown()
                accepted.get(5, TimeUnit.SECONDS)
                closing.get(5, TimeUnit.SECONDS)
                leaseReleased.get() shouldBe true
            } finally {
                releaseCleanup.countDown()
                cleanupWorker.closeAsync().get(5, TimeUnit.SECONDS)
                runtime.close()
                sqlWorker.closeAsync().get(5, TimeUnit.SECONDS)
            }
        }

        "$name attempts lease release after a failed drain and retains both failures" {
            val drainFailure = IllegalStateException("accepted SQL drain failed")
            val releaseFailure = IllegalStateException("lease release failed")
            val sqlExecutor = mockk<SqlExecutor> {
                every { closeAsync() } returns CompletableFuture.failedFuture(drainFailure)
            }
            val leaseReleased = AtomicBoolean()
            val runtime = mockk<SqlRuntime> {
                every { executor } returns sqlExecutor
                every { close() } answers {
                    leaseReleased.set(true)
                    throw releaseFailure
                }
            }
            val failure = shouldThrow<CompletionException> { createStore(runtime).close() }
            failure.cause shouldBe drainFailure
            failure.suppressed.toList() shouldBe listOf(releaseFailure)
            leaseReleased.get() shouldBe true
        }
    }
})
