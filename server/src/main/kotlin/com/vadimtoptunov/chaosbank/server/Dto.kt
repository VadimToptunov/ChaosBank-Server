package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.ChaosSession
import kotlinx.serialization.Serializable

// Wire shapes. Money travels as a decimal string ("4820.55") both ways, so no client
// parses it through a binary double. Shapes here and in openapi.yaml change together:
// the contract test checks every documented response against the spec.

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
data class TransferRequest(val from: String, val amount: String, val recipient: String = "", val note: String = "")

@Serializable
data class DepositRequest(val to: String, val amount: String, val title: String = "Add money")

@Serializable
data class ExchangeRequest(val sell: String, val get: String, val amount: String)

@Serializable
data class ExchangeDto(val transaction: TransactionDto, val debited: String, val credited: String, val rate: String, val fee: String)

@Serializable
data class FxRatesDto(val base: String, val rates: Map<String, String>, val feeRate: String)

@Serializable
data class AssetDto(val symbol: String, val name: String, val kind: String, val currency: String, val price: String)

@Serializable
data class HoldingDto(val symbol: String, val quantity: String, val avgCost: String)

@Serializable
data class OrderRequest(
    val symbol: String,
    val side: String,
    val type: String = "market",
    val quantity: String,
    val limitPrice: String? = null,
    val referencePrice: String,
)

@Serializable
data class OrderDto(
    val id: String,
    val symbol: String,
    val side: String,
    val type: String,
    val quantity: String,
    val limitPrice: String?,
    val referencePrice: String,
    val executionPrice: String,
    val status: String,
    val placedAt: Long,
)

@Serializable
data class CounterDto(val value: Int)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class OtpRequest(val challengeId: String, val code: String)

@Serializable
data class ResendRequest(val challengeId: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class ChallengeDto(val challengeId: String, val expiresIn: Long, val resendIn: Long)

@Serializable
data class TokenPairDto(val accessToken: String, val refreshToken: String, val tokenType: String, val expiresIn: Long)

@Serializable
data class SessionRequest(
    val profile: String? = null,
    val defects: List<String> = emptyList(),
    val seed: Long = 0,
    val network: String? = null,
)

@Serializable
data class ClockRequest(val advanceSeconds: Long)

@Serializable
data class SessionDto(
    val id: String,
    val profile: String?,
    val defects: List<String>,
    val seed: Long,
    val network: String,
    val now: Long,
)

@Serializable
data class DefectDto(
    val id: String,
    val title: String,
    val area: String,
    val category: String,
    val severity: String,
    val requirement: String,
    val task: String,
)

@Serializable
data class CatalogDto(val defects: List<DefectDto>, val profiles: Map<String, List<String>>)

/** RFC 9457 problem details. [type] mirrors the apps' `BackendError` names where one fits. */
@Serializable
data class Problem(val type: String, val title: String, val status: Int, val detail: String? = null)

fun Account.toDto() = AccountDto(name, currency.name, balance.toPlainString())

fun Transaction.toDto() = TransactionDto(id, title, category, date, amount.toPlainString(), currency.name)

fun Exchange.toDto() = ExchangeDto(transaction.toDto(), debited.toPlainString(), credited.toPlainString(), rate.toPlainString(), fee.toPlainString())

fun Asset.toDto() = AssetDto(symbol, name, kind.name, currency.name, basePrice.toPlainString())

fun Holding.toDto() = HoldingDto(symbol, quantity.toPlainString(), avgCost.toPlainString())

fun Order.toDto() = OrderDto(
    id, symbol, side.name, type.name, quantity.toPlainString(), limitPrice?.toPlainString(),
    referencePrice.toPlainString(), executionPrice.toPlainString(), status.name, placedAt,
)

fun ChaosSession<*>.toDto() =
    SessionDto(id, config.profile, config.defects.sorted(), config.seed, config.network.name, nowSeconds())
