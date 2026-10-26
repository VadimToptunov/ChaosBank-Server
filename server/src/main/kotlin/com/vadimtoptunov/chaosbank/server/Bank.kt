package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.ActiveDefects
import com.vadimtoptunov.chaosbank.chaos.DefectCatalog
import com.vadimtoptunov.chaosbank.chaos.DefectSpec
import com.vadimtoptunov.chaosbank.chaos.SeededRng
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

data class Asset(val symbol: String, val name: String, val kind: AssetKind, val currency: Currency, val basePrice: BigDecimal)

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
    timeout(504),
}

class BankException(val error: BankError, val detail: String) : Exception(detail)

/** Server-side defects. Ids match the apps' where the defect is the same one. */
object ServerDefects {
    const val balanceReadReturnsZero = "balanceReadReturnsZero"
    const val transactionsDupOnFetch = "transactionsDupOnFetch"

    val catalog = DefectCatalog(
        listOf(
            DefectSpec(balanceReadReturnsZero, "Balance read returns zero on error", "Accounts"),
            DefectSpec(transactionsDupOnFetch, "Every transaction fetched twice", "Transactions"),
        ),
    )
}

/**
 * One session's bank: the authoritative state, all money math correct (BigDecimal),
 * checks in the same order as the apps' `MockBackend`. Defects are guarded deviations,
 * one per defect. Every method is synchronized: a session is one consistent world.
 */
class BankState(private val seed: Long = 0) {
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
        return Page(all.subList(from, to).toList(), if (to < all.size) to else null)
    }

    fun assets(): List<Asset> = Seed.assets

    @Synchronized
    fun holdings(): List<Holding> = Seed.assets.mapNotNull { holdings[it.symbol] }

    @Synchronized
    fun orders(): List<Order> = orders.toList()

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
    @Synchronized
    fun idempotent(key: String, fingerprint: String, write: () -> Replay): Replay {
        replays[key]?.let {
            if (it.fingerprint != fingerprint) throw IdempotencyConflict(key)
            return it.copy(replayed = true)
        }
        return write().copy(fingerprint = fingerprint).also { replays[key] = it }
    }

    @Synchronized
    fun transfer(from: Currency, amount: BigDecimal, recipient: String, now: Long): Transaction {
        if (amount.signum() <= 0) throw BankException(BankError.invalidAmount, "amount must be positive")
        val account = accounts[from] ?: throw BankException(BankError.unknownAccount, "no account in $from")
        if (account.balance < amount) throw BankException(BankError.insufficientFunds, "$from balance is ${account.balance.toPlainString()}")
        accounts[from] = account.copy(balance = account.balance - amount)
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
    fun exchange(sell: Currency, get: Currency, amount: BigDecimal, now: Long): Exchange {
        if (sell == get) throw BankException(BankError.sameCurrency, "sell and get are both $sell")
        if (amount.signum() <= 0) throw BankException(BankError.invalidAmount, "amount must be positive")
        val from = accounts[sell] ?: throw BankException(BankError.unknownAccount, "no account in $sell")
        val to = accounts[get] ?: throw BankException(BankError.unknownAccount, "no account in $get")
        if (from.balance < amount) throw BankException(BankError.insufficientFunds, "$sell balance is ${from.balance.toPlainString()}")
        val rate = FxRates.rate(sell, get)
        val fee = amount * FxRates.feeRate
        val credited = ((amount - fee) * rate).setScale(2, RoundingMode.HALF_EVEN)
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

/** A stored response for an idempotent replay: status plus the JSON body as sent. */
data class Replay(val status: Int, val body: String, val fingerprint: String = "", val replayed: Boolean = false)

class IdempotencyConflict(val key: String) : Exception("Idempotency-Key '$key' was used for a different request")
