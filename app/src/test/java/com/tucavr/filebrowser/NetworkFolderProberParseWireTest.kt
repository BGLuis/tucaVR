package com.tucavr.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkFolderProberParseWireTest {

    @Test
    fun parsesHasMediaTrueAndCompletedFullyTrue() {
        assertEquals(true to true, NetworkFolderProber.parseWire("1\t1"))
    }

    @Test
    fun parsesHasMediaFalseAndCompletedFullyTrue() {
        assertEquals(false to true, NetworkFolderProber.parseWire("0\t1"))
    }

    @Test
    fun parsesTimedOutAssumesHasMedia() {
        assertEquals(true to false, NetworkFolderProber.parseWire("1\t0"))
    }

    @Test
    fun returnsNullForErrorWire() {
        assertNull(NetworkFolderProber.parseWire("ERROR:falha de rede"))
    }

    @Test
    fun returnsNullForNullOrMalformedWire() {
        assertNull(NetworkFolderProber.parseWire(null))
        assertNull(NetworkFolderProber.parseWire(""))
        assertNull(NetworkFolderProber.parseWire("garbage"))
    }
}
