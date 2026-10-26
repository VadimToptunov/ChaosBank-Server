package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.DefectCatalog
import com.vadimtoptunov.chaosbank.chaos.DefectSpec

/**
 * Server-side defects. Each is one guarded deviation in otherwise correct code. Ids match
 * the apps' where it is the same defect (`retryDuplicate`, `timeoutAsSuccess`, …), so an
 * app running against the server forwards it here and it behaves as on the embedded mock.
 */
object ServerDefects {
    // State
    const val balanceReadReturnsZero = "balanceReadReturnsZero"
    const val transactionsDupOnFetch = "transactionsDupOnFetch"
    const val staleHoldingsAfterOrder = "staleHoldingsAfterOrder"
    const val lostUpdateNoLock = "lostUpdateNoLock"
    const val paginationCursorSkips = "paginationCursorSkips"

    // Network
    const val retryDuplicate = "retryDuplicate"
    const val timeoutAsSuccess = "timeoutAsSuccess"
    const val orderFilledTwiceOnRetry = "orderFilledTwiceOnRetry"

    // Money
    const val serverRoundsDown = "serverRoundsDown"
    const val negativeAmountCredits = "negativeAmountCredits"
    const val fxRateStaleOnServer = "fxRateStaleOnServer"

    // Contract
    const val contractFieldRenamed = "contractFieldRenamed"

    // Realtime
    const val wsPricesOutOfOrder = "wsPricesOutOfOrder"

    // KYC (the apps' ids)
    const val kycAcceptsExpiredDocument = "kycAcceptsExpiredDocument"
    const val kycNameMismatchAccepted = "kycNameMismatchAccepted"
    const val kycSelfieMismatchAccepted = "kycSelfieMismatchAccepted"
    const val kycStuckPending = "kycStuckPending"

    private fun d(id: String, title: String, area: String, category: String, severity: String, requirement: String, task: String) =
        DefectSpec(id, title, area, category, severity, requirement, task)

    val catalog = DefectCatalog(
        listOf(
            d(balanceReadReturnsZero, "Balance read returns zero on error", "Accounts", "State", "major",
                "A balance read returns the balance; an error is an error, never a zero.",
                "Show that GET /v1/accounts reports a zero balance for funded accounts."),
            d(transactionsDupOnFetch, "Every transaction fetched twice", "Transactions", "State", "major",
                "The history returns each transaction once.",
                "Show that the history contains every row twice."),
            d(staleHoldingsAfterOrder, "Holdings don't reflect a filled order", "Portfolio", "State", "major",
                "After a filled order, GET /v1/holdings shows the new quantity.",
                "Place a buy and show that the holdings still show the old quantity."),
            d(lostUpdateNoLock, "Concurrent transfers lose updates", "Transfer", "State", "critical",
                "Concurrent transfers each debit the account; the balance drops by their sum.",
                "Send transfers in parallel and show that the balance drops by less than their sum."),
            d(paginationCursorSkips, "Pagination skips a row at every page edge", "Transactions", "State", "major",
                "Following nextCursor visits every transaction exactly once.",
                "Page through the history and show that rows are missing at page boundaries."),
            d(retryDuplicate, "Retry with the same Idempotency-Key pays again", "Transfer", "Network", "critical",
                "A repeat with the same Idempotency-Key replays the first result and moves no money.",
                "Repeat a transfer with the same key and show a second debit."),
            d(timeoutAsSuccess, "Transfer applied, then answered with a timeout", "Transfer", "Network", "critical",
                "A transfer that answers 504 was not applied; a retry with the same key is safe.",
                "Show that a transfer answering 504 has moved the money anyway."),
            d(orderFilledTwiceOnRetry, "Order fills, times out, and fills again on retry", "Orders", "Network", "critical",
                "An order answering 504 was not filled, so a retry fills it once.",
                "Retry an order that answered 504 with the same key and show two fills."),
            d(serverRoundsDown, "Server debits the amount rounded down to whole units", "Transfer", "Money", "critical",
                "A transfer debits exactly the amount, to the cent.",
                "Transfer an amount with cents and show the balance dropped by less."),
            d(negativeAmountCredits, "Negative transfer accepted and credits the account", "Transfer", "Money", "critical",
                "A transfer of zero or less is refused with invalidAmount.",
                "Bypass the app's validation and show that a negative transfer adds money."),
            d(fxRateStaleOnServer, "Exchange credited at a stale rate", "Exchange", "Money", "major",
                "An exchange credits (amount − fee) × the rate it reports, to the cent.",
                "Show that the credited amount doesn't match the reported rate."),
            d(contractFieldRenamed, "One endpoint renames balance to amount", "Accounts", "Contract", "major",
                "Every response matches openapi.yaml.",
                "Find the endpoint whose response breaks the published contract."),
            d(wsPricesOutOfOrder, "Price stream delivers ticks out of order", "Markets", "Realtime", "major",
                "The price stream delivers ticks in seq order; a client never shows an older price after a newer one.",
                "Show that the stream's seq numbers go backwards."),
            d(kycAcceptsExpiredDocument, "Expired document passes KYC", "KYC", "Validation", "critical",
                "A document past its expiry date is rejected.",
                "Submit an expired document and show it gets verified."),
            d(kycNameMismatchAccepted, "Someone else's document passes KYC", "KYC", "Validation", "critical",
                "A document whose name isn't the account holder's is rejected.",
                "Submit a document with another name and show it gets verified."),
            d(kycSelfieMismatchAccepted, "A non-matching face passes KYC", "KYC", "Validation", "critical",
                "A selfie that doesn't match the document is rejected.",
                "Submit a mismatching selfie and show it gets verified."),
            d(kycStuckPending, "Approved KYC stays pending", "KYC", "State", "major",
                "A check that passes ends verified.",
                "Submit a valid document and show the status never leaves pending."),
        ),
        mapOf(
            "money" to setOf(serverRoundsDown, negativeAmountCredits, fxRateStaleOnServer),
            "state" to setOf(balanceReadReturnsZero, transactionsDupOnFetch, staleHoldingsAfterOrder, lostUpdateNoLock, paginationCursorSkips),
            "network" to setOf(retryDuplicate, timeoutAsSuccess, orderFilledTwiceOnRetry),
            "contract" to setOf(contractFieldRenamed),
            "realtime" to setOf(wsPricesOutOfOrder),
            "kyc" to setOf(kycAcceptsExpiredDocument, kycNameMismatchAccepted, kycSelfieMismatchAccepted, kycStuckPending),
        ).let { it + ("all" to it.values.flatten().toSet()) },
    )
}
