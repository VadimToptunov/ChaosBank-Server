package com.vadimtoptunov.chaosbank.server

import java.math.BigDecimal

/**
 * The starting data, copied value for value from the apps' `SeedData` (ChaosBank-iOS
 * `SeedData.swift`, ChaosBank-Android `SeedData.kt`). A clean session must answer exactly
 * what the embedded mock does, so change these only together with the apps.
 */
object Seed {
    /** Fixed reference "now" (epoch seconds), so dates are deterministic. */
    const val REFERENCE_DATE: Long = 1_752_000_000L

    private fun daysAgo(d: Int, hours: Int = 0): Long = REFERENCE_DATE - (d.toLong() * 86_400 + hours.toLong() * 3_600)

    val accounts: List<Account> = listOf(
        Account("EUR Main", Currency.EUR, BigDecimal("4820.55")),
        Account("USD Savings", Currency.USD, BigDecimal("12750.00")),
        Account("GBP Travel", Currency.GBP, BigDecimal("640.20")),
    )

    val assets: List<Asset> = listOf(
        Asset("AAPL", "Apple Inc.", AssetKind.stock, Currency.USD, BigDecimal("189.50")),
        Asset("MSFT", "Microsoft Corp.", AssetKind.stock, Currency.USD, BigDecimal("415.20")),
        Asset("NVDA", "NVIDIA Corp.", AssetKind.stock, Currency.USD, BigDecimal("875.30")),
        Asset("TSLA", "Tesla Inc.", AssetKind.stock, Currency.USD, BigDecimal("242.10")),
        Asset("BTC", "Bitcoin", AssetKind.crypto, Currency.USD, BigDecimal("64200.00")),
        Asset("ETH", "Ethereum", AssetKind.crypto, Currency.USD, BigDecimal("3120.00")),
    )

    val holdings: List<Holding> = listOf(
        Holding("AAPL", BigDecimal("12"), BigDecimal("150.00")),
        Holding("TSLA", BigDecimal("8"), BigDecimal("280.00")),
        Holding("ETH", BigDecimal("3.5"), BigDecimal("2400.00")),
    )

    val transactions: List<Transaction> = listOf(
        tx("t01", "Salary — Acme Corp", "Income", 0, "3200.00", Currency.EUR),
        tx("t02", "Grocery Store", "Groceries", 1, "-64.30", Currency.EUR),
        tx("t03", "Transfer to Alex", "Transfer", 1, "-120.00", Currency.EUR),
        tx("t04", "Coffee Roasters", "Dining", 2, "-4.80", Currency.EUR),
        tx("t05", "Refund — Zalando", "Shopping", 2, "39.99", Currency.EUR),
        tx("t06", "Electricity Bill", "Utilities", 3, "-88.10", Currency.EUR),
        tx("t07", "Exchange EUR → USD", "Exchange", 3, "-500.00", Currency.EUR),
        tx("t08", "Freelance Payout", "Income", 4, "740.00", Currency.USD),
        tx("t09", "App Store", "Digital", 4, "-9.99", Currency.EUR),
        tx("t10", "Restaurant Bella", "Dining", 5, "-52.40", Currency.EUR),
        tx("t11", "Pharmacy", "Health", 6, "-18.75", Currency.EUR),
        tx("t12", "Transfer from Mia", "Transfer", 6, "85.00", Currency.EUR),
        tx("t13", "Gym Membership", "Health", 7, "-29.90", Currency.EUR),
        tx("t14", "Fuel Station", "Transport", 8, "-61.20", Currency.EUR),
        tx("t15", "Book Depository", "Shopping", 9, "-23.45", Currency.EUR),
        tx("t16", "Interest", "Income", 10, "12.06", Currency.USD),
    ).sortedByDescending { it.date }

    private fun tx(id: String, title: String, category: String, daysAgo: Int, amount: String, currency: Currency): Transaction {
        val hour = (id.takeLast(2).toIntOrNull() ?: 0) % 12
        return Transaction(id, title, category, daysAgo(daysAgo, hour), BigDecimal(amount), currency)
    }
}
