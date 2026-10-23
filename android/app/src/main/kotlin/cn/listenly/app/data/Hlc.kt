package cn.listenly.app.data

import java.util.concurrent.atomic.AtomicLong

/**
 * Hybrid logical clock: `(unixMillis << 16) | counter`.
 *
 * Always moves forward, even if the wall clock steps back, and absorbs clocks seen from the
 * server so a local edit made after a pull always wins over what was pulled.
 */
class Hlc(initial: Long = 0, private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val last = AtomicLong(initial)

    fun next(): Long = last.updateAndGet { previous ->
        val physical = nowMillis() shl COUNTER_BITS
        if (physical > previous) physical else previous + 1
    }

    fun observe(remote: Long) {
        last.updateAndGet { maxOf(it, remote) }
    }

    companion object {
        const val COUNTER_BITS = 16
        fun millisOf(clock: Long): Long = clock ushr COUNTER_BITS
    }
}
