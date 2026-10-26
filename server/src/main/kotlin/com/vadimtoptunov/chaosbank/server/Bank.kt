package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.ActiveDefects
import com.vadimtoptunov.chaosbank.chaos.SeededRng
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

enum class Currency { EUR, USD, GBP }

data class Account(val name: String, val currency: Currency, val balance: BigDecimal)

data class Transaction(
    val id: String,
    val title: String,
    val category: String,
    /** Epoch seconds. */
    val date: Long,
    /** Signed: negative = money out. */
    val amount: BigDecimal,
    val currency: Currency,
)

enum class AssetKind { stock, crypto }

data class Asset(
    val symbol: String,
    val name: String,
    val kind: AssetKind,
    val currency: Currency,
    val basePrice: BigDecimal,
    /** Per-tick volatility of the simulated feed. */
    val volatility: BigDecimal,
)

data class Holding(val symbol: String, val quantity: BigDecimal, val avgCost: BigDecimal) {
    val costBasis: BigDecimal get() = quantity * avgCost
}

enum class OrderSide { buy, sell }
enum class OrderType { market, limit }
enum class OrderStatus { pending, filled, rejected }

data class Order(
    val id: String,
    val symbol: String,
    val side: OrderSide,
    val type: OrderType,
    val quantity: BigDecimal,
    val limitPrice: BigDecimal?,
    val referencePrice: BigDecimal,
    val executionPrice: BigDecimal,
    val status: OrderStatus,
    val placedAt: Long,
)

data class Exchange(val transaction: Transaction, val debited: BigDecimal, val credited: BigDecimal, val rate: BigDecimal, val fee: BigDecimal)

/** The apps' `FxRates`: fixed mid-market rates against EUR and a 0.5% fee on the sold amount. */
object FxRates {
    val perEUR: Map<Currency, BigDecimal> = mapOf(
        Currency.EUR to BigDecimal.ONE,
        Currency.USD to BigDecimal("1.08"),
        Currency.GBP to BigDecimal("0.85"),
    )
    val feeRate = BigDecimal("0.005")

    fun rate(from: Currency, to: Currency): BigDecimal = perEUR.getValue(to).divide(perEUR.getValue(from), 10, RoundingMode.HALF_EVEN)

    /** An hour-old quote, for `fxRateStaleOnServer`. */
    private val stalePerEUR: Map<Currency, BigDecimal> = mapOf(
        Currency.EUR to BigDecimal.ONE,
        Currency.USD to BigDecimal("1.06"),
        Currency.GBP to BigDecimal("0.86"),
    )

    fun staleRate(from: Currency, to: Currency): BigDecimal =
        stalePerEUR.getValue(to).divide(stalePerEUR.getValue(from), 10, RoundingMode.HALF_EVEN)
}

/** The apps' `BackendError`, with the HTTP status each maps to. */
enum class BankError(val status: Int) {
    insufficientFunds(422),
    unknownAccount(404),
    invalidAmount(400),
    unknownAsset(404),
    insufficientHolding(422),
    sameCurrency(400),
    invalidLimitPrice(400),
    invalidDocument(400),
    noFace(400),
    timeout(504),
}

class BankException(val error: BankError, val detail: String) : Exception(detail)

/**
 * One session's bank: the authoritative state, all money math correct (BigDecimal),
 * checks in the same order as the apps' `MockBackend`. Defects are guarded deviations,
 * one per defect. Every method is synchronized: a session is one consistent world.
 */
class BankState(private val seed: Long = 0, startMillis: Long = System.currentTimeMillis()) {
    private val accounts = LinkedHashMap(Seed.accounts.associateBy { it.currency })
    private val transactions = Seed.transactions.toMutableList()
    private val assets = Seed.assets.associateBy { it.symbol }
    private val holdings = LinkedHashMap(Seed.holdings.associateBy { it.symbol })
    private val orders = mutableListOf<Order>()
    private val replays = HashMap<String, Replay>()
    private var sequence = 0
    private var flakySeq = 0L
    private val cashCurrency = Currency.USD

    val auth = AuthState()
    val kyc = KycState()
    val feed = PriceFeed(seed, startMillis)

    private fun nextId(prefix: String): String { sequence += 1; return "$prefix-$sequence" }

    private fun zeroed(a: Account, chaos: ActiveDefects): Account =
        // `balanceReadReturnsZero`: a balance read answers 0 instead of the balance.
        if (chaos.isActive(ServerDefects.balanceReadReturnsZero)) a.copy(balance = BigDecimal.ZERO) else a

    // Reads ------------------------------------------------------------------

    @Synchronized
    fun accounts(chaos: ActiveDefects): List<Account> =
        Currency.entries.mapNotNull { accounts[it] }.map { zeroed(it, chaos) }

    @Synchronized
    fun account(currency: Currency, chaos: ActiveDefects): Account? = accounts[currency]?.let { zeroed(it, chaos) }

    /** Newest first. [cursor] is the offset a previous page handed out; null = from the top. */
    @Synchronized
    fun transactions(limit: Int?, cursor: Int?, chaos: ActiveDefects): Page<Transaction> {
        // `transactionsDupOnFetch`: every row comes back twice.
        val all = if (chaos.isActive(ServerDefects.transactionsDupOnFetch)) transactions.flatMap { listOf(it, it) } else transactions
        val from = (cursor ?: 0).coerceIn(0, all.size)
        val to = if (limit == null) all.size else minOf(all.size, from + limit)
        // `paginationCursorSkips`: the next cursor is one past the page end, so a row is lost per page.
        val next = if (to < all.size) (if (chaos.isActive(ServerDefects.paginationCursorSkips)) to + 1 else to) else null
        return Page(all.subList(from, to).toList(), next)
    }

    fun assets(): List<Asset> = Seed.assets

    @Synchronized
    fun holdings(chaos: ActiveDefects): List<Holding> {
        // `staleHoldingsAfterOrder`: serve the seed snapshot instead of the live portfolio.
        val source = if (chaos.isActive(ServerDefects.staleHoldingsAfterOrder)) Seed.holdings.associateBy { it.symbol } else holdings
        return Seed.assets.mapNotNull { source[it.symbol] }
    }

    @Synchronized
    fun orders(): List<Order> = orders.toList()

    // Sync playground ----------------------------------------------------------
    // The apps' reliability cluster: a shared counter with an atomic increment
    // (correct) plus separate read and write calls (for the lost-update race).

    private var syncCounter = 0

    @Synchronized fun syncValue(): Int = syncCounter
    @Synchronized fun syncSet(value: Int): Int { syncCounter = value; return syncCounter }
    @Synchronized fun syncIncrement(): Int { syncCounter += 1; return syncCounter }
    @Synchronized fun syncReset(): Int { syncCounter = 0; return syncCounter }

    // Writes -----------------------------------------------------------------

    /**
     * The "Flaky" network: a write fails before it is applied, half the time, on a
     * seeded sequence so a run reproduces. Same draw as the apps' `failIfFlaky`.
     */
    @Synchronized
    fun failIfFlaky() {
        if (SeededRng(seed + flakySeq++).nextDouble() < 0.5) throw BankException(BankError.timeout, "the write timed out and was not applied")
    }

    /**
     * Runs a money write once per [key]. A repeat with the same key and request returns
     * the first response without touching money; the same key on a different request is
     * refused. Only successes are remembered, as in the apps' `processedKeys`.
     */
    /** Money writes run one at a time per session (check the key, apply, remember the result). */
    private val writes = Mutex()

    suspend fun idempotent(
        key: String, fingerprint: String, chaos: ActiveDefects, after: AfterWrite = AfterWrite.answer,
        write: suspend () -> Replay,
    ): Replay =
        // `lostUpdateNoLock`: writes no longer take the lock, so concurrent ones interleave.
        if (chaos.isActive(ServerDefects.lostUpdateNoLock)) once(key, fingerprint, chaos, after, write)
        else writes.withLock { once(key, fingerprint, chaos, after, write) }

    private suspend fun once(
        key: String, fingerprint: String, chaos: ActiveDefects, after: AfterWrite, write: suspend () -> Replay,
    ): Replay {
        // `retryDuplicate`: the ledger of used keys is never consulted, so a retry pays again.
        if (!chaos.isActive(ServerDefects.retryDuplicate)) {
            synchronized(this) { replays[key] }?.let {
                if (it.fingerprint != fingerprint) throw IdempotencyConflict(key)
                return it.copy(replayed = true)
            }
        }
        val result = write().copy(fingerprint = fingerprint)
        when (after) {
            AfterWrite.answer -> synchronized(this) { replays[key] = result }
            // `timeoutAsSuccess`: applied and remembered, but the caller is told it timed out.
            AfterWrite.rememberThenTimeOut -> {
                synchronized(this) { replays[key] = result }
                throw BankException(BankError.timeout, "the write timed out")
            }
            // `orderFilledTwiceOnRetry`: applied but not remembered, so a retry with the same key applies again.
            AfterWrite.forgetThenTimeOut -> throw BankException(BankError.timeout, "the write timed out")
        }
        return result
    }

    suspend fun transfer(from: Currency, amount: BigDecimal, recipient: String, now: Long, chaos: ActiveDefects): Transaction {
        // `lostUpdateNoLock`: read the balance, let other requests in, then write it back.
        if (chaos.isActive(ServerDefects.lostUpdateNoLock)) {
            val seen = synchronized(this) { validateTransfer(from, amount, chaos) }
            delay(LOST_UPDATE_WINDOW_MS)
            return synchronized(this) { recordTransfer(from, amount, recipient, now, seen.balance - debited(amount, chaos)) }
        }
        return synchronized(this) {
            val account = validateTransfer(from, amount, chaos)
            recordTransfer(from, amount, recipient, now, account.balance - debited(amount, chaos))
        }
    }

    private fun validateTransfer(from: Currency, amount: BigDecimal, chaos: ActiveDefects): Account {
        // `negativeAmountCredits`: only zero is refused, so a negative amount gets through and credits.
        val refused = if (chaos.isActive(ServerDefects.negativeAmountCredits)) amount.signum() == 0 else amount.signum() <= 0
        if (refused) throw BankException(BankError.invalidAmount, "amount must be positive")
        val account = accounts[from] ?: throw BankException(BankError.unknownAccount, "no account in $from")
        if (account.balance < amount) throw BankException(BankError.insufficientFunds, "$from balance is ${account.balance.toPlainString()}")
        return account
    }

    /** `serverRoundsDown`: the debit drops the cents. */
    private fun debited(amount: BigDecimal, chaos: ActiveDefects): BigDecimal =
        if (chaos.isActive(ServerDefects.serverRoundsDown)) amount.setScale(0, RoundingMode.DOWN) else amount

    private fun recordTransfer(from: Currency, amount: BigDecimal, recipient: String, now: Long, newBalance: BigDecimal): Transaction {
        accounts[from] = accounts.getValue(from).copy(balance = newBalance)
        val title = if (recipient.isEmpty()) "Transfer" else "Transfer to $recipient"
        return Transaction(nextId("tx"), title, "Transfer", now, amount.negate(), from).also { transactions.add(0, it) }
    }

    @Synchronized
    fun deposit(to: Currency, amount: BigDecimal, title: String, now: Long): Transaction {
        if (amount.signum() <= 0) throw BankException(BankError.invalidAmount, "amount must be positive")
        val account = accounts[to] ?: throw BankException(BankError.unknownAccount, "no account in $to")
        accounts[to] = account.copy(balance = account.balance + amount)
        return Transaction(nextId("dep"), title, "Top-up", now, amount, to).also { transactions.add(0, it) }
    }

    /** Sells [amount] of [sell]; the server prices it: (amount − 0.5% fee) × rate, to the cent. */
    @Synchronized
    fun exchange(sell: Currency, get: Currency, amount: BigDecimal, now: Long, chaos: ActiveDefects): Exchange {
        if (sell == get) throw BankException(BankError.sameCurrency, "sell and get are both $sell")
        if (amount.signum() <= 0) throw BankException(BankError.invalidAmount, "amount must be positive")
        val from = accounts[sell] ?: throw BankException(BankError.unknownAccount, "no account in $sell")
        val to = accounts[get] ?: throw BankException(BankError.unknownAccount, "no account in $get")
        if (from.balance < amount) throw BankException(BankError.insufficientFunds, "$sell balance is ${from.balance.toPlainString()}")
        val rate = FxRates.rate(sell, get)
        val fee = amount * FxRates.feeRate
        // `fxRateStaleOnServer`: credit at an hour-old rate while reporting the current one.
        val applied = if (chaos.isActive(ServerDefects.fxRateStaleOnServer)) FxRates.staleRate(sell, get) else rate
        val credited = ((amount - fee) * applied).setScale(2, RoundingMode.HALF_EVEN)
        accounts[sell] = from.copy(balance = from.balance - amount)
        accounts[get] = to.copy(balance = to.balance + credited)
        val tx = Transaction(nextId("fx"), "Exchange $sell → $get", "Exchange", now, credited, get).also { transactions.add(0, it) }
        return Exchange(tx, amount, credited, rate, fee.setScale(2, RoundingMode.HALF_EVEN))
    }

    /**
     * Fills at once: a market order at the client's [referencePrice] (the price it
     * showed), a limit order at its limit, as in the apps' mock. Server-side pricing
     * arrives with the price feed in stage 5.
     */
    @Synchronized
    fun placeOrder(
        symbol: String, side: OrderSide, type: OrderType, quantity: BigDecimal,
        limitPrice: BigDecimal?, referencePrice: BigDecimal, now: Long,
    ): Order {
        assets[symbol] ?: throw BankException(BankError.unknownAsset, "no asset '$symbol'")
        if (quantity.signum() <= 0) throw BankException(BankError.invalidAmount, "quantity must be positive")
        if (referencePrice.signum() <= 0) throw BankException(BankError.invalidAmount, "referencePrice must be positive")
        if (type == OrderType.limit && (limitPrice == null || limitPrice.signum() <= 0))
            throw BankException(BankError.invalidLimitPrice, "a limit order needs a positive limitPrice")
        val executionPrice = if (type == OrderType.limit) limitPrice!! else referencePrice
        val cash = accounts[cashCurrency] ?: throw BankException(BankError.unknownAccount, "no account in $cashCurrency")
        val total = quantity * executionPrice

        when (side) {
            OrderSide.buy -> {
                if (cash.balance < total) throw BankException(BankError.insufficientFunds, "USD balance is ${cash.balance.toPlainString()}")
                accounts[cashCurrency] = cash.copy(balance = cash.balance - total)
                val existing = holdings[symbol]
                holdings[symbol] = if (existing == null) Holding(symbol, quantity, executionPrice) else {
                    val newQty = existing.quantity + quantity
                    existing.copy(quantity = newQty, avgCost = (existing.costBasis + total).divide(newQty, 10, RoundingMode.HALF_EVEN))
                }
            }
            OrderSide.sell -> {
                val existing = holdings[symbol]
                if (existing == null || existing.quantity < quantity)
                    throw BankException(BankError.insufficientHolding, "holding ${existing?.quantity?.toPlainString() ?: "0"} $symbol")
                accounts[cashCurrency] = cash.copy(balance = cash.balance + total)
                val newQty = existing.quantity - quantity
                if (newQty.signum() == 0) holdings.remove(symbol) else holdings[symbol] = existing.copy(quantity = newQty)
            }
        }

        val order = Order(
            UUID.randomUUID().toString(), symbol, side, type, quantity,
            if (type == OrderType.limit) limitPrice else null, referencePrice, executionPrice, OrderStatus.filled, now,
        )
        orders.add(0, order)
        val verb = if (side == OrderSide.buy) "Buy" else "Sell"
        val signed = if (side == OrderSide.buy) total.negate() else total
        transactions.add(0, Transaction(nextId("ord"), "$verb $symbol", "Trade", now, signed, cashCurrency))
        return order
    }
}

data class Page<T>(val items: List<T>, val nextOffset: Int?)

/** What a money write does once applied: answer, or (for two defects) time out. */
enum class AfterWrite { answer, rememberThenTimeOut, forgetThenTimeOut }

/** How long `lostUpdateNoLock` leaves between reading and writing a balance. */
const val LOST_UPDATE_WINDOW_MS = 40L

/** A stored response for an idempotent replay: status plus the JSON body as sent. */
data class Replay(val status: Int, val body: String, val fingerprint: String = "", val replayed: Boolean = false)

class IdempotencyConflict(val key: String) : Exception("Idempotency-Key '$key' was used for a different request")
