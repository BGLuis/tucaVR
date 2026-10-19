package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying Passthrough and 3D Environment synchronization policies (#89).
 */
class PassthroughSyncTest {
    @Test
    fun `shouldEnablePassthroughForEnvironment returns true only for passthrough environment`() {
        assertTrue(PassthroughSyncPolicy.shouldEnablePassthroughForEnvironment(EnvironmentStore.ENV_PASSTHROUGH))

        assertFalse(PassthroughSyncPolicy.shouldEnablePassthroughForEnvironment(EnvironmentStore.ENV_VOID))
        assertFalse(PassthroughSyncPolicy.shouldEnablePassthroughForEnvironment(EnvironmentStore.ENV_CINEMA))
        assertFalse(PassthroughSyncPolicy.shouldEnablePassthroughForEnvironment(EnvironmentStore.ENV_SPACE))
        assertFalse(PassthroughSyncPolicy.shouldEnablePassthroughForEnvironment(EnvironmentStore.ENV_LIVING_ROOM))
        assertFalse(PassthroughSyncPolicy.shouldEnablePassthroughForEnvironment("unknown_env"))
    }

    @Test
    fun `resolveEffectiveEnvironment maps passthrough to void mesh and keeps others intact`() {
        assertEquals(EnvironmentStore.ENV_VOID, PassthroughSyncPolicy.resolveEffectiveEnvironment(EnvironmentStore.ENV_PASSTHROUGH))
        assertEquals(EnvironmentStore.ENV_CINEMA, PassthroughSyncPolicy.resolveEffectiveEnvironment(EnvironmentStore.ENV_CINEMA))
        assertEquals(EnvironmentStore.ENV_SPACE, PassthroughSyncPolicy.resolveEffectiveEnvironment(EnvironmentStore.ENV_SPACE))
        assertEquals(EnvironmentStore.ENV_VOID, PassthroughSyncPolicy.resolveEffectiveEnvironment(EnvironmentStore.ENV_VOID))
    }

    @Test
    fun `isPassthroughActive considers both active environment and feature flag`() {
        // Active when environment is passthrough, regardless of flag
        assertTrue(PassthroughSyncPolicy.isPassthroughActive(EnvironmentStore.ENV_PASSTHROUGH, isFlagEnabled = false))
        assertTrue(PassthroughSyncPolicy.isPassthroughActive(EnvironmentStore.ENV_PASSTHROUGH, isFlagEnabled = true))

        // Active when flag is enabled in another environment
        assertTrue(PassthroughSyncPolicy.isPassthroughActive(EnvironmentStore.ENV_CINEMA, isFlagEnabled = true))
        assertTrue(PassthroughSyncPolicy.isPassthroughActive(EnvironmentStore.ENV_VOID, isFlagEnabled = true))

        // Inactive when not passthrough environment and flag is false
        assertFalse(PassthroughSyncPolicy.isPassthroughActive(EnvironmentStore.ENV_CINEMA, isFlagEnabled = false))
        assertFalse(PassthroughSyncPolicy.isPassthroughActive(EnvironmentStore.ENV_VOID, isFlagEnabled = false))
    }
}
