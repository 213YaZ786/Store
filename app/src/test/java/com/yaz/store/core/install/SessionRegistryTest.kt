package com.yaz.store.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from App Manager's PackageInstallerSessionRegistryTest (GPL-3.0-or-later):
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/test/java/io/github/muntashirakon/AppManager/apk/installer/PackageInstallerSessionRegistryTest.java
// (cleanupAbandonsOnlyLiveSessionsFromAnOlderProcess, failedAbandonmentRemainsTrackedForTheNextCleanup), with the
// preferences replaced by a map, and the cases added here: another session of the app (the update's) is never touched.
class SessionRegistryTest {

    private class MapStore(val records: MutableMap<String, String> = mutableMapOf()) : SessionStore {
        override fun all(): Map<String, String> = records.toMap()
        override fun put(key: String, value: String) { records[key] = value }
        override fun remove(key: String) { records.remove(key) }
    }

    private fun key(id: Int) = SessionRegistry.KEY_PREFIX + id

    @Test
    fun `cleanup abandons only live sessions of an older process`() {
        val store = MapStore(mutableMapOf(key(41) to "old-process", key(42) to "old-process", key(43) to "this-process"))
        val registry = SessionRegistry(store, token = "this-process")
        val abandoned = mutableSetOf<Int>()

        registry.abandonOrphans(liveIds = { setOf(41, 43) }, abandon = { abandoned.add(it) })

        assertEquals(setOf(41), abandoned)
        assertFalse(store.records.containsKey(key(41)))
        assertFalse(store.records.containsKey(key(42))) // gone from the installer: only the record is dropped
        assertTrue(store.records.containsKey(key(43)))
    }

    @Test
    fun `a failed abandonment stays tracked for the next cleanup`() {
        val store = MapStore(mutableMapOf(key(51) to "old-process"))
        var attempts = 0
        SessionRegistry(store, token = "now").abandonOrphans(liveIds = { setOf(51) }, abandon = {
            attempts++
            throw IllegalStateException("still active")
        })
        assertEquals(1, attempts)
        assertTrue(store.records.containsKey(key(51)))
    }

    @Test
    fun `a session the registry never recorded is left alone`() {
        // The app's own update session (core/update) is in getMySessions() but not in the registry.
        val store = MapStore(mutableMapOf(key(7) to "old-process"))
        val abandoned = mutableListOf<Int>()
        SessionRegistry(store, token = "now").abandonOrphans(liveIds = { setOf(7, 99) }, abandon = { abandoned.add(it) })
        assertEquals(listOf(7), abandoned)
    }

    @Test
    fun `recording and forgetting`() {
        val store = MapStore()
        val registry = SessionRegistry(store, token = "now")
        registry.record(12)
        registry.record(-1)
        assertEquals(mapOf(key(12) to "now"), store.records)
        registry.forget(-1)
        registry.forget(12)
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `an empty registry does not even ask the installer`() {
        var asked = false
        SessionRegistry(MapStore(), token = "now").abandonOrphans(liveIds = { asked = true; emptySet() }, abandon = { })
        assertFalse(asked)
    }

    @Test
    fun `when the installer cannot be asked nothing changes`() {
        val store = MapStore(mutableMapOf(key(5) to "old-process"))
        SessionRegistry(store, token = "now").abandonOrphans(liveIds = { throw IllegalStateException("no installer") }, abandon = { })
        assertTrue(store.records.containsKey(key(5)))
    }

    @Test
    fun `a record that is not a session id is dropped, one that is not ours is ignored`() {
        val store = MapStore(mutableMapOf(key(0).dropLast(1) + "x" to "old-process", "other" to "old-process"))
        SessionRegistry(store, token = "now").abandonOrphans(liveIds = { setOf(1) }, abandon = { })
        assertEquals(mapOf("other" to "old-process"), store.records)
    }
}
