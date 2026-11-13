package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Test

class HdrLuminanceStoreTest {
    @Test
    fun `a saved value is what the next session reads`() {
        val prefs = FakeSharedPreferences()
        HdrLuminanceStore(prefs).set(1.4f)

        assertEquals(1.4f, HdrLuminanceStore(prefs).get(), 0.0001f)
    }

    @Test
    fun `a first run reads the neutral luminance`() {
        assertEquals(1.0f, HdrLuminanceStore(FakeSharedPreferences()).get(), 0.0001f)
    }

    @Test
    fun `values outside the range are clamped on write`() {
        val prefs = FakeSharedPreferences()
        val store = HdrLuminanceStore(prefs)

        store.set(9f)
        assertEquals(HdrLuminanceStore.MAX, store.get(), 0.0001f)

        store.set(0.01f)
        assertEquals(HdrLuminanceStore.MIN, store.get(), 0.0001f)
    }
}
