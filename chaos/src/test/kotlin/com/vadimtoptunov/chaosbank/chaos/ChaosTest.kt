package com.vadimtoptunov.chaosbank.chaos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChaosTest {
    private val catalog = DefectCatalog(
        listOf(DefectSpec("a", "A", "x"), DefectSpec("b", "B", "x"), DefectSpec("c", "C", "y")),
        mapOf("money" to setOf("a", "b")),
    )

    @Test
    fun `clean is always a profile and resolves to nothing`() {
        assertEquals(emptySet(), catalog.resolve(DefectCatalog.CLEAN, emptyList()))
    }

    @Test
    fun `a profile and explicit ids add up`() {
        assertEquals(setOf("a", "b", "c"), catalog.resolve("money", listOf("c")))
    }

    @Test
    fun `unknown ids and profiles are refused, not ignored`() {
        assertFailsWith<UnknownChaosValue> { catalog.resolve(null, listOf("typo")) }
        assertFailsWith<UnknownChaosValue> { catalog.resolve("nope", emptyList()) }
    }

    @Test
    fun `a profile naming an unknown defect fails at construction`() {
        assertFailsWith<IllegalArgumentException> {
            DefectCatalog(listOf(DefectSpec("a", "A", "x")), mapOf("bad" to setOf("a", "zzz")))
        }
    }

    @Test
    fun `sessions are isolated and reset rebuilds only their own state`() {
        var built = 0
        val store = SessionStore(catalog, { built++; mutableListOf<Int>() })
        val one = store.create(null, listOf("a"), seed = 1, network = NetworkCondition.normal)
        val two = store.create(null, emptyList(), seed = 2, network = NetworkCondition.slow)
        one.state.add(42)

        assertTrue(two.state.isEmpty())
        assertEquals(setOf("a"), one.config.defects)
        assertEquals(NetworkCondition.slow, two.config.network)

        val before = one.state
        store.reset(one.id)
        assertNotSame(before, store.get(one.id)!!.state)
        assertTrue(store.get(one.id)!!.state.isEmpty())
        assertEquals(setOf("a"), store.get(one.id)!!.config.defects, "reset keeps the config")
        assertEquals(4, built, "default + two sessions + one reset")
    }

    @Test
    fun `update replaces the defects and keeps the network unless given`() {
        val store = SessionStore(catalog, { Unit })
        val s = store.create("money", emptyList(), 0, NetworkCondition.slow)
        store.update(s.id, null, listOf("c"), null)
        assertEquals(setOf("c"), store.get(s.id)!!.config.defects)
        assertEquals(NetworkCondition.slow, store.get(s.id)!!.config.network)
    }

    @Test
    fun `the default session exists, runs clean and can't be deleted`() {
        val store = SessionStore(catalog, { Unit })
        assertEquals(emptySet(), store.get(SessionStore.DEFAULT)!!.config.defects)
        assertFalse(store.delete(SessionStore.DEFAULT))
    }

    @Test
    fun `idle sessions expire, the default never does`() {
        var now = 0L
        val store = SessionStore(catalog, { Unit }, nowMillis = { now }, ttlMillis = 1_000)
        val old = store.create(null, emptyList(), 0, NetworkCondition.normal)
        now = 5_000
        store.create(null, emptyList(), 0, NetworkCondition.normal)   // creating sweeps expired ones
        assertNull(store.get(old.id))
        assertEquals(SessionStore.DEFAULT, store.get(SessionStore.DEFAULT)!!.id)
    }

    @Test
    fun `header defects add to the session's for one request`() {
        val active = ActiveDefects.of(catalog, ChaosConfig(defects = setOf("a")), " c , ")
        assertTrue(active.isActive("a"))
        assertTrue(active.isActive("c"))
        assertFalse(active.isActive("b"))
        assertFailsWith<UnknownChaosValue> { ActiveDefects.of(catalog, ChaosConfig(), "typo") }
    }
}
