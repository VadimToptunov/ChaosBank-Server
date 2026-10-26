package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.ChaosSession
import kotlinx.serialization.Serializable

// Wire shapes. Money travels as a decimal string ("4820.55") so no client parses it
// through a binary double.

@Serializable
data class AccountDto(val name: String, val currency: String, val balance: String)

@Serializable
data class TransactionDto(
    val id: String,
    val title: String,
    val category: String,
    val date: Long,
    val amount: String,
    val currency: String,
)

@Serializable
data class TransactionPageDto(val items: List<TransactionDto>, val nextCursor: String?)

@Serializable
data class SessionRequest(
    val profile: String? = null,
    val defects: List<String> = emptyList(),
    val seed: Long = 0,
    val network: String? = null,
)

@Serializable
data class SessionDto(
    val id: String,
    val profile: String?,
    val defects: List<String>,
    val seed: Long,
    val network: String,
)

@Serializable
data class DefectDto(val id: String, val title: String, val area: String)

@Serializable
data class CatalogDto(val defects: List<DefectDto>, val profiles: Map<String, List<String>>)

/** RFC 9457 problem details. [type] mirrors the apps' `BackendError` names where one fits. */
@Serializable
data class Problem(val type: String, val title: String, val status: Int, val detail: String? = null)

fun Account.toDto() = AccountDto(name, currency.name, balance.toPlainString())

fun Transaction.toDto() = TransactionDto(id, title, category, date, amount.toPlainString(), currency.name)

fun ChaosSession<*>.toDto() = SessionDto(id, config.profile, config.defects.sorted(), config.seed, config.network.name)
