package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.SeededRng
import java.math.BigDecimal
import java.math.RoundingMode

enum class TickDirection { up, down, flat }

data class Quote(
    val symbol: String,
    val price: BigDecimal,
    val dayOpen: BigDecimal,
    val dayHigh: BigDecimal,
    val dayLow: BigDecimal,
    val direction: TickDirection,
)

/** One tick of the feed: [seq] counts ticks from the session's start (0 = the opening prices). */
data class PriceTick(val seq: Long, val quotes: List<Quote>)

/**
 * The apps' simulated feed (`PriceFeed`), step for step: a seeded random walk, one step
 * every [TICK_MS]. The same seed walks the same prices as the app's own feed.
 *
 * The feed is computed lazily from the clock: asking at time t catches up to the tick t
 * falls in, so an idle session costs nothing.
 */
class PriceFeed(seed: Long, private val startMillis: Long) {
    private val rng = SeededRng(seed + 0x5A5A_5A5AL)
    private var seq = 0L
    private val quotes = LinkedHashMap<String, Quote>().apply {
        Seed.assets.forEach { put(it.symbol, Quote(it.symbol, it.basePrice, it.basePrice, it.basePrice, it.basePrice, TickDirection.flat)) }
    }
    private val history = ArrayDeque<PriceTick>()

    /** Ticks after [after] up to [nowMillis], oldest first (the latest only, if far behind). */
    @Synchronized
    fun ticksSince(after: Long, nowMillis: Long): List<PriceTick> {
        catchUp(nowMillis)
        return history.filter { it.seq > after }
    }

    @Synchronized
    fun current(nowMillis: Long): PriceTick {
        catchUp(nowMillis)
        return history.lastOrNull() ?: snapshot()
    }

    @Synchronized
    fun price(symbol: String, nowMillis: Long): BigDecimal? = current(nowMillis).quotes.firstOrNull { it.symbol == symbol }?.price

    private fun catchUp(nowMillis: Long) {
        val target = ((nowMillis - startMillis) / TICK_MS).coerceAtLeast(0)
        while (seq < target) {
            step()
            history.addLast(snapshot())
            if (history.size > KEPT_TICKS) history.removeFirst()
        }
        if (history.isEmpty()) history.addLast(snapshot())
    }

    private fun snapshot() = PriceTick(seq, quotes.values.toList())

    private fun step() {
        val floor = BigDecimal("0.01")
        for (a in Seed.assets) {
            val q = quotes.getValue(a.symbol)
            val r = BigDecimal(rng.nextInRange(-1.0, 1.0))
            var newPrice = (q.price + q.price * a.volatility * r).setScale(2, RoundingMode.HALF_EVEN)
            if (newPrice < floor) newPrice = floor
            val dir = when {
                newPrice > q.price -> TickDirection.up
                newPrice < q.price -> TickDirection.down
                else -> TickDirection.flat
            }
            quotes[a.symbol] = q.copy(
                price = newPrice, direction = dir,
                dayHigh = maxOf(newPrice, q.dayHigh), dayLow = minOf(newPrice, q.dayLow),
            )
        }
        seq += 1
    }

    companion object {
        /** The apps' tick, 700 ms. */
        const val TICK_MS = 700L
        private const val KEPT_TICKS = 64
    }
}
