package com.yaz.store.data.catalog

// JUnit 4 asserts (https://github.com/junit-team/junit4/wiki/Assertions) over OwnUpdate: which package is Store's. Store lists itself like any
// app (GrapheneOS' App Store, https://github.com/GrapheneOS/AppStore, core/PackageStates.kt keeps its own package in its lists).
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnUpdateTest {

    // https://github.com/junit-team/junit4/wiki/Assertions
    @Test
    fun `Store and its debug build are its own packages, no other`() {
        assertTrue(OwnUpdate.isOwn("com.yaz.store"))
        assertTrue(OwnUpdate.isOwn("com.yaz.store.debug"))
        assertFalse(OwnUpdate.isOwn("com.yaz.dialer"))
        assertFalse(OwnUpdate.isOwn("com.yaz.storefront"))
    }
}
