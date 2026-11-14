package com.tucavr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LutStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `first run has no active lut`() {
        val prefs = FakeSharedPreferences()
        val store = LutStore(prefs, tempFolder.root)

        assertFalse(store.hasActiveLut())
        assertNull(store.getActiveLutName())
        assertNull(store.getActiveLutBytes())
    }

    @Test
    fun `saved lut persists name and bytes across store instances`() {
        val prefs = FakeSharedPreferences()
        val dir = tempFolder.root
        val store1 = LutStore(prefs, dir)

        val bytes = "LUT_3D_SIZE 2\n0.0 0.0 0.0\n".toByteArray(Charsets.UTF_8)
        store1.saveLut("WarmGrade", null, bytes)

        assertTrue(store1.hasActiveLut())
        assertEquals("WarmGrade", store1.getActiveLutName())
        assertArrayEquals(bytes, store1.getActiveLutBytes())

        // Re-read with a new store instance pointing to same storage
        val store2 = LutStore(prefs, dir)
        assertTrue(store2.hasActiveLut())
        assertEquals("WarmGrade", store2.getActiveLutName())
        assertArrayEquals(bytes, store2.getActiveLutBytes())
    }

    @Test
    fun `clear deletes cached file and resets preferences`() {
        val prefs = FakeSharedPreferences()
        val dir = tempFolder.root
        val store = LutStore(prefs, dir)

        val bytes = byteArrayOf(1, 2, 3)
        store.saveLut("CoolGrade", null, bytes)
        assertTrue(store.hasActiveLut())

        store.clear()
        assertFalse(store.hasActiveLut())
        assertNull(store.getActiveLutName())
        assertNull(store.getActiveLutBytes())
    }

    @Test
    fun `missing cached file makes store report inactive even if pref was true`() {
        val prefs = FakeSharedPreferences()
        val dir = tempFolder.root
        val store = LutStore(prefs, dir)

        store.saveLut("BrokenFile", null, byteArrayOf(1, 2))
        assertTrue(store.hasActiveLut())

        // Delete the cached file behind the scenes
        tempFolder.root.listFiles()?.forEach { it.delete() }

        assertFalse(store.hasActiveLut())
        assertNull(store.getActiveLutName())
        assertNull(store.getActiveLutBytes())
    }
}
