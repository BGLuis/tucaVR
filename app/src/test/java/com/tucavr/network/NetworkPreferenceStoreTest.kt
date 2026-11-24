package com.tucavr.network

import com.tucavr.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPreferenceStoreTest {
    @Test
    fun `defaults to 10 seconds and accepts valid values`() {
        val prefs = FakeSharedPreferences()
        val store = NetworkPreferenceStore(prefs)

        assertEquals(10, store.getConnectTimeoutSeconds())

        for (secs in NetworkPreferenceStore.VALID_TIMEOUT_SECONDS) {
            store.setConnectTimeoutSeconds(secs)
            assertEquals(secs, NetworkPreferenceStore(prefs).getConnectTimeoutSeconds())
        }
    }

    @Test
    fun `invalid timeouts sanitize to default and reset restores default`() {
        val prefs = FakeSharedPreferences()
        val store = NetworkPreferenceStore(prefs)

        store.setConnectTimeoutSeconds(999)
        assertEquals(NetworkPreferenceStore.DEFAULT_TIMEOUT_SECONDS, store.getConnectTimeoutSeconds())

        store.setConnectTimeoutSeconds(0)
        assertEquals(NetworkPreferenceStore.DEFAULT_TIMEOUT_SECONDS, store.getConnectTimeoutSeconds())

        store.setConnectTimeoutSeconds(30)
        assertEquals(30, store.getConnectTimeoutSeconds())

        store.reset()
        assertEquals(NetworkPreferenceStore.DEFAULT_TIMEOUT_SECONDS, store.getConnectTimeoutSeconds())
    }
}
