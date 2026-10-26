package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.ActiveDefects
import com.vadimtoptunov.chaosbank.chaos.ChaosSession
import com.vadimtoptunov.chaosbank.chaos.DefectCatalog
import com.vadimtoptunov.chaosbank.chaos.NetworkCondition
import com.vadimtoptunov.chaosbank.chaos.SessionStore
import com.vadimtoptunov.chaosbank.chaos.UnknownChaosValue
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.math.BigDecimal

const val SESSION_HEADER = "X-Chaos-Session"
const val DEFECTS_HEADER = "X-Chaos-Defects"
const val IDEMPOTENCY_HEADER = "Idempotency-Key"
const val REPLAYED_HEADER = "Idempotent-Replayed"

/** The extra latency of the "Slow" network, as in the apps' `MockBackend`. */
const val SLOW_DELAY_MS = 3_000L

class ApiError(val status: HttpStatusCode, val type: String, val detail: String) : Exception(detail)

val wireJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

/** A request as the handlers see it: the caller's session and the defects in force. */
class Ctx(val session: ChaosSession<BankState>, val chaos: ActiveDefects) {
    val bank: BankState get() = session.state
    val now: Long get() = session.nowSeconds()
}

fun Application.module(
    catalog: DefectCatalog = ServerDefects.catalog,
    sessions: SessionStore<BankState> = SessionStore(catalog, { BankState(it.seed) }),
    slowDelayMs: Long = SLOW_DELAY_MS,
    jwt: Jwt = Jwt(Jwt.randomSecret()),
) {
    install(ContentNegotiation) { json(wireJson) }
    install(CallLogging)

    /** Resolves the caller's session and header defects, then applies the session's network. */
    suspend fun RoutingContext.inSession(block: suspend (Ctx) -> Unit) = guarded {
        val id = call.request.headers[SESSION_HEADER] ?: SessionStore.DEFAULT
        val session = sessions.get(id) ?: throw ApiError(HttpStatusCode.NotFound, "unknownSession", "no chaos session '$id'")
        val chaos = ActiveDefects.of(catalog, session.config, call.request.headers[DEFECTS_HEADER])
        if (session.config.network == NetworkCondition.slow) delay(slowDelayMs)
        block(Ctx(session, chaos))
    }

    /** [inSession] plus a valid access token for that session; hands the handler the claims. */
    suspend fun RoutingContext.signedIn(block: suspend (Ctx, Jwt.Claims) -> Unit) = inSession { ctx ->
        val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
            ?: throw AuthException(AuthError.unauthorized, "send Authorization: Bearer <accessToken>")
        val claims = jwt.verify(token)
        if (claims == null || claims.type != "access" || claims.session != ctx.session.id ||
            ctx.now >= claims.expiresAt || ctx.bank.auth.isRevoked(claims.id)
        ) throw AuthException(AuthError.unauthorized, "the access token is invalid, expired, logged out or from another session")
        block(ctx, claims)
    }

    /**
     * A money write: needs an Idempotency-Key, fails before applying on the "Flaky" network,
     * and replays the first success for a repeated key.
     */
    suspend fun <T> RoutingContext.moneyWrite(
        ctx: Ctx, serializer: KSerializer<T>, write: (T) -> Pair<HttpStatusCode, String>,
    ) {
        val key = call.request.headers[IDEMPOTENCY_HEADER]?.takeIf { it.isNotBlank() }
            ?: throw ApiError(HttpStatusCode.BadRequest, "idempotencyKeyRequired", "money writes need an $IDEMPOTENCY_HEADER header")
        val request = wireJson.decodeFromString(serializer, call.receiveText())
        if (ctx.session.config.network == NetworkCondition.flaky) ctx.bank.failIfFlaky()
        val fingerprint = call.request.local.uri + " " + wireJson.encodeToString(serializer, request)
        val replay = ctx.bank.idempotent(key, fingerprint) {
            val (status, body) = write(request)
            Replay(status.value, body)
        }
        if (replay.replayed) call.response.header(REPLAYED_HEADER, "true")
        call.respondText(replay.body, ContentType.Application.Json, HttpStatusCode.fromValue(replay.status))
    }

    suspend fun RoutingContext.chaosSession(): ChaosSession<BankState> {
        val id = call.parameters["id"]!!
        return sessions.get(id) ?: throw ApiError(HttpStatusCode.NotFound, "unknownSession", "no chaos session '$id'")
    }

    fun tokens(ctx: Ctx, username: String): TokenPairDto {
        val (access, _) = jwt.sign(username, ctx.session.id, "access", ctx.now, AuthRules.ACCESS_TTL_SECONDS)
        val (refresh, refreshClaims) = jwt.sign(username, ctx.session.id, "refresh", ctx.now, AuthRules.REFRESH_TTL_SECONDS)
        ctx.bank.auth.rememberRefresh(refreshClaims)
        return TokenPairDto(access, refresh, "Bearer", AuthRules.ACCESS_TTL_SECONDS)
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        get("/openapi.yaml") {
            call.respondText(resource("/openapi.yaml"), ContentType.parse("application/yaml"))
        }
        get("/docs") { call.respondText(resource("/docs.html"), ContentType.Text.Html) }

        // --- auth ---------------------------------------------------------------

        post("/v1/auth/login") {
            inSession { ctx ->
                val req = body<LoginRequest>()
                val sent = ctx.bank.auth.login(req.username, req.password, ctx.now)
                call.respond(ChallengeDto(sent.challengeId, sent.expiresIn, sent.resendIn))
            }
        }

        post("/v1/auth/otp") {
            inSession { ctx ->
                val req = body<OtpRequest>()
                call.respond(tokens(ctx, ctx.bank.auth.verifyOtp(req.challengeId, req.code, ctx.now)))
            }
        }

        post("/v1/auth/otp/resend") {
            inSession { ctx ->
                val sent = ctx.bank.auth.resend(body<ResendRequest>().challengeId, ctx.now)
                call.respond(ChallengeDto(sent.challengeId, sent.expiresIn, sent.resendIn))
            }
        }

        post("/v1/auth/refresh") {
            inSession { ctx ->
                val claims = jwt.verify(body<RefreshRequest>().refreshToken)?.takeIf { it.session == ctx.session.id }
                    ?: throw AuthException(AuthError.invalidToken, "refresh token is invalid, expired or already used")
                call.respond(tokens(ctx, ctx.bank.auth.useRefresh(claims, ctx.now)))
            }
        }

        post("/v1/auth/logout") {
            signedIn { ctx, claims ->
                ctx.bank.auth.logout(claims)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // --- accounts and history -----------------------------------------------

        get("/v1/accounts") {
            signedIn { ctx, _ -> call.respond(ctx.bank.accounts(ctx.chaos).map { it.toDto() }) }
        }

        get("/v1/accounts/{currency}") {
            signedIn { ctx, _ ->
                val raw = call.parameters["currency"]!!
                val account = Currency.entries.firstOrNull { it.name == raw.uppercase() }?.let { ctx.bank.account(it, ctx.chaos) }
                    ?: throw BankException(BankError.unknownAccount, "no account in '$raw'")
                call.respond(account.toDto())
            }
        }

        get("/v1/transactions") {
            signedIn { ctx, _ ->
                val limit = call.request.queryParameters["limit"]?.let {
                    it.toIntOrNull()?.takeIf { n -> n in 1..100 }
                        ?: throw ApiError(HttpStatusCode.BadRequest, "invalidLimit", "limit must be 1…100")
                }
                val cursor = call.request.queryParameters["cursor"]?.let {
                    it.toIntOrNull()?.takeIf { n -> n >= 0 }
                        ?: throw ApiError(HttpStatusCode.BadRequest, "invalidCursor", "cursor '$it' was not handed out by this API")
                }
                val page = ctx.bank.transactions(limit, cursor, ctx.chaos)
                call.respond(TransactionPageDto(page.items.map { it.toDto() }, page.nextOffset?.toString()))
            }
        }

        // --- money --------------------------------------------------------------

        post("/v1/transfers") {
            signedIn { ctx, _ ->
                moneyWrite(ctx, TransferRequest.serializer()) { req ->
                    val tx = ctx.bank.transfer(currency(req.from), money(req.amount), req.recipient.trim(), ctx.now)
                    HttpStatusCode.Created to wireJson.encodeToString(TransactionDto.serializer(), tx.toDto())
                }
            }
        }

        post("/v1/deposits") {
            signedIn { ctx, _ ->
                moneyWrite(ctx, DepositRequest.serializer()) { req ->
                    val tx = ctx.bank.deposit(currency(req.to), money(req.amount), req.title, ctx.now)
                    HttpStatusCode.Created to wireJson.encodeToString(TransactionDto.serializer(), tx.toDto())
                }
            }
        }

        get("/v1/fx/rates") {
            signedIn { _, _ ->
                call.respond(FxRatesDto("EUR", FxRates.perEUR.mapKeys { it.key.name }.mapValues { it.value.toPlainString() }, FxRates.feeRate.toPlainString()))
            }
        }

        post("/v1/exchanges") {
            signedIn { ctx, _ ->
                moneyWrite(ctx, ExchangeRequest.serializer()) { req ->
                    val fx = ctx.bank.exchange(currency(req.sell), currency(req.get), money(req.amount), ctx.now)
                    HttpStatusCode.Created to wireJson.encodeToString(ExchangeDto.serializer(), fx.toDto())
                }
            }
        }

        // --- markets ------------------------------------------------------------

        get("/v1/assets") { signedIn { ctx, _ -> call.respond(ctx.bank.assets().map { it.toDto() }) } }

        get("/v1/holdings") { signedIn { ctx, _ -> call.respond(ctx.bank.holdings().map { it.toDto() }) } }

        get("/v1/orders") { signedIn { ctx, _ -> call.respond(ctx.bank.orders().map { it.toDto() }) } }

        post("/v1/orders") {
            signedIn { ctx, _ ->
                moneyWrite(ctx, OrderRequest.serializer()) { req ->
                    val side = OrderSide.entries.firstOrNull { it.name == req.side }
                        ?: throw ApiError(HttpStatusCode.BadRequest, "invalidSide", "side is buy or sell")
                    val type = OrderType.entries.firstOrNull { it.name == req.type }
                        ?: throw ApiError(HttpStatusCode.BadRequest, "invalidType", "type is market or limit")
                    val order = ctx.bank.placeOrder(
                        req.symbol.uppercase(), side, type, decimal(req.quantity, "quantity", 8),
                        req.limitPrice?.let { decimal(it, "limitPrice", 2) }, decimal(req.referencePrice, "referencePrice", 2), ctx.now,
                    )
                    HttpStatusCode.Created to wireJson.encodeToString(OrderDto.serializer(), order.toDto())
                }
            }
        }

        // --- chaos control ------------------------------------------------------

        get("/chaos/catalog") {
            call.respond(
                CatalogDto(
                    catalog.specs.map { DefectDto(it.id, it.title, it.area) },
                    catalog.profiles.mapValues { it.value.sorted() },
                ),
            )
        }

        post("/chaos/sessions") {
            guarded {
                val req = body<SessionRequest>()
                val network = req.network?.let(NetworkCondition::parse) ?: NetworkCondition.normal
                call.respond(HttpStatusCode.Created, sessions.create(req.profile, req.defects, req.seed, network).toDto())
            }
        }

        get("/chaos/sessions/{id}") { guarded { call.respond(chaosSession().toDto()) } }

        put("/chaos/sessions/{id}") {
            guarded {
                val req = body<SessionRequest>()
                val id = chaosSession().id
                call.respond(sessions.update(id, req.profile, req.defects, req.network?.let(NetworkCondition::parse))!!.toDto())
            }
        }

        post("/chaos/sessions/{id}/reset") { guarded { call.respond(sessions.reset(chaosSession().id)!!.toDto()) } }

        post("/chaos/sessions/{id}/clock") {
            guarded {
                val req = body<ClockRequest>()
                if (req.advanceSeconds < 0) throw ApiError(HttpStatusCode.BadRequest, "invalidClock", "the clock only moves forward")
                call.respond(sessions.advanceClock(chaosSession().id, req.advanceSeconds)!!.toDto())
            }
        }

        delete("/chaos/sessions/{id}") {
            guarded {
                val id = chaosSession().id
                if (!sessions.delete(id)) throw ApiError(HttpStatusCode.Conflict, "defaultSession", "the default session can't be deleted")
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private suspend inline fun <reified T> RoutingContext.body(): T = wireJson.decodeFromString(call.receiveText())

private fun resource(path: String): String =
    Application::class.java.getResource(path)?.readText() ?: error("missing resource $path")

private fun currency(raw: String): Currency =
    Currency.entries.firstOrNull { it.name == raw.uppercase() } ?: throw BankException(BankError.unknownAccount, "no account in '$raw'")

/** A money amount: a decimal string with at most two decimals. */
private fun money(raw: String): BigDecimal = decimal(raw, "amount", 2)

private fun decimal(raw: String, field: String, maxScale: Int): BigDecimal {
    val value = raw.toBigDecimalOrNull()
    if (value == null || value.scale() > maxScale) throw BankException(BankError.invalidAmount, "$field must be a decimal string with at most $maxScale decimals")
    return value
}

private suspend fun RoutingContext.guarded(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: ApiError) {
        call.respondProblem(e.status, e.type, e.detail)
    } catch (e: BankException) {
        call.respondProblem(HttpStatusCode.fromValue(e.error.status), e.error.name, e.detail)
    } catch (e: AuthException) {
        if (e.error.status == 401) call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
        e.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
        call.respondProblem(HttpStatusCode.fromValue(e.error.status), e.error.name, e.detail)
    } catch (e: IdempotencyConflict) {
        call.respondProblem(HttpStatusCode.UnprocessableEntity, "idempotencyKeyReused", e.message!!)
    } catch (e: UnknownChaosValue) {
        call.respondProblem(HttpStatusCode.BadRequest, "unknownChaosValue", e.message!!)
    } catch (e: SerializationException) {
        call.respondProblem(HttpStatusCode.BadRequest, "invalidBody", e.message ?: "malformed JSON")
    } catch (e: IllegalArgumentException) {
        call.respondProblem(HttpStatusCode.BadRequest, "invalidBody", e.message ?: "malformed request")
    }
}

suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, type: String, detail: String) =
    respondText(
        wireJson.encodeToString(Problem.serializer(), Problem(type, status.description, status.value, detail)),
        ContentType.parse("application/problem+json"),
        status,
    )
