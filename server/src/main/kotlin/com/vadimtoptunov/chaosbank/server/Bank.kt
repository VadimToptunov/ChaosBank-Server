package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.ActiveDefects
import com.vadimtoptunov.chaosbank.chaos.DefectCatalog
import com.vadimtoptunov.chaosbank.chaos.DefectSpec
import java.math.BigDecimal

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
 * One session's bank: the authoritative state, all money math correct (BigDecimal).
 * Defects are guarded deviations, one per defect, as in the apps' `MockBackend`.
 */
class BankState {
    private val accounts = LinkedHashMap(Seed.accounts.associateBy { it.currency })
    private val transactions = Seed.transactions.toMutableList()

    private fun zeroed(a: Account, chaos: ActiveDefects): Account =
        // `balanceReadReturnsZero`: a balance read answers 0 instead of the balance.
        if (chaos.isActive(ServerDefects.balanceReadReturnsZero)) a.copy(balance = BigDecimal.ZERO) else a

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
}

data class Page<T>(val items: List<T>, val nextOffset: Int?)
