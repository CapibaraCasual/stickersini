package io.github.capibaracasual.stickersini.webp

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Corre [block] en su propio hilo daemon con un límite de tiempo. Una
 * llamada JNI bloqueada no se puede interrumpir de verdad desde Kotlin: si
 * [block] excede [timeoutMs] esta función deja de esperarla y reporta
 * timeout, pero el hilo nativo puede seguir corriendo en segundo plano por
 * su cuenta hasta que termine solo.
 *
 * Compartido entre [WebpAnimEncoderPerformanceTest] y
 * [WebpEncodeMethodBenchmarkTest] para no duplicar esta red de seguridad.
 */
internal class TimedAttemptRunner(threadNamePrefix: String) {

    data class Result(val bytes: ByteArray?, val elapsedMs: Long, val timedOut: Boolean)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, threadNamePrefix).apply { isDaemon = true }
    }

    fun run(timeoutMs: Long, block: () -> ByteArray): Result {
        val start = System.nanoTime()
        val future = executor.submit<ByteArray> { block() }
        return try {
            val bytes = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            Result(bytes, elapsedMs(start), timedOut = false)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            Result(null, elapsedMs(start), timedOut = true)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
}
