package com.vadimtoptunov.chaosbank.chaos

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A simulated network environment, as in the apps' dev menu. Not a defect.
 * `offline` stays client-side: a server that answers can't be offline.
 */
enum class NetworkCondition {
    normal, slow, flaky;

    companion object {
        fun parse(raw: String): NetworkCondition =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: throw UnknownChaosValue("network", raw)
    }
}

/** What a session runs with. [defects] is already resolved (profile + explicit ids). */
data class ChaosConfig(
    val defects: Set<String> = emptySet(),
    val profile: String? = null,
    val seed: Long = 0,
    val network: NetworkCondition = NetworkCondition.normal,
)

/** One tester's or test's isolated world: its own config and its own data ([state]). */
class ChaosSession<S>(val id: String, config: ChaosConfig, state: S, private val nowMillis: () -> Long) {
    @Volatile var config: ChaosConfig = config; internal set
    @Volatile var state: S = state; internal set
    @Volatile var lastSeenMillis: Long = 0; internal set

    /** How far this session's clock runs ahead of the wall clock (see [SessionStore.advanceClock]). */
    @Volatile var clockOffsetSeconds: Long = 0; internal set

    /** The session's "now", in epoch seconds: OTP, token and KYC expiry all read this. */
    fun nowSeconds(): Long = nowMillis() / 1000 + clockOffsetSeconds
}

/**
 * Sessions keyed by id, each with state built by [newState] from its config. The
 * [DEFAULT] session always exists and runs clean unless changed, so a server poked by
 * hand (curl, Postman) behaves like the apps' embedded mock.
 */
class SessionStore<S>(
    private val catalog: DefectCatalog,
    private val newState: (ChaosConfig) -> S,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 24 * 60 * 60 * 1000L,
) {
    private val sessions = ConcurrentHashMap<String, ChaosSession<S>>()

    init { sessions[DEFAULT] = ChaosSession(DEFAULT, ChaosConfig(), newState(ChaosConfig()), nowMillis) }

    fun create(profile: String?, defects: Collection<String>, seed: Long, network: NetworkCondition): ChaosSession<S> {
        evictExpired()
        val config = ChaosConfig(catalog.resolve(profile, defects), profile, seed, network)
        val session = ChaosSession(UUID.randomUUID().toString(), config, newState(config), nowMillis)
        session.lastSeenMillis = nowMillis()
        sessions[session.id] = session
        return session
    }

    /** The session for [id], or null; a hit counts as activity for the TTL. */
    fun get(id: String): ChaosSession<S>? = sessions[id]?.also { it.lastSeenMillis = nowMillis() }

    /** Changes defects / network on a live session. Data is kept; [reset] rebuilds it. */
    fun update(
        id: String,
        profile: String?,
        defects: Collection<String>,
        network: NetworkCondition?,
    ): ChaosSession<S>? {
        val session = get(id) ?: return null
        val current = session.config
        session.config = current.copy(
            defects = catalog.resolve(profile, defects),
            profile = profile,
            network = network ?: current.network,
        )
        return session
    }

    /** Back to the seed data and the wall clock, keeping the config. */
    fun reset(id: String): ChaosSession<S>? {
        val session = get(id) ?: return null
        session.state = newState(session.config)
        session.clockOffsetSeconds = 0
        return session
    }

    /** Moves the session's clock forward, so expiry can be tested without waiting. */
    fun advanceClock(id: String, seconds: Long): ChaosSession<S>? {
        require(seconds >= 0) { "the clock only moves forward" }
        val session = get(id) ?: return null
        session.clockOffsetSeconds += seconds
        return session
    }

    fun delete(id: String): Boolean = id != DEFAULT && sessions.remove(id) != null

    private fun evictExpired() {
        val cutoff = nowMillis() - ttlMillis
        sessions.values.removeIf { it.id != DEFAULT && it.lastSeenMillis < cutoff }
    }

    companion object {
        const val DEFAULT = "default"
    }
}

/**
 * The defects in force for one request: its session's plus any named in the
 * `X-Chaos-Defects` header, so a stateless API test can switch one on for one call.
 */
class ActiveDefects(private val ids: Set<String>) {
    fun isActive(id: String): Boolean = id in ids

    companion object {
        fun of(catalog: DefectCatalog, session: ChaosConfig, header: String?): ActiveDefects {
            val extra = header?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            return ActiveDefects(session.defects + catalog.resolve(null, extra))
        }
    }
}
