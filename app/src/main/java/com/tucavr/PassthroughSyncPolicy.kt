package com.tucavr

/**
 * Pure policy rules for synchronizing Passthrough and 3D Virtual Environments.
 * Ensures consistent behavior across VRControlsPresentation, SettingsScreen,
 * VRModalPresentation, and VRActivity (#89).
 */
object PassthroughSyncPolicy {
    /**
     * Determines whether the selected virtual environment requires Passthrough mode.
     */
    fun shouldEnablePassthroughForEnvironment(environmentId: String): Boolean {
        return environmentId == EnvironmentStore.ENV_PASSTHROUGH
    }

    /**
     * Resolves the 3D scene mesh to render. When in Passthrough mode, the VR scene
     * is set to VOID (transparent/black) so the real world passthrough layer is visible.
     */
    fun resolveEffectiveEnvironment(environmentId: String): String {
        return if (environmentId == EnvironmentStore.ENV_PASSTHROUGH) {
            EnvironmentStore.ENV_VOID
        } else {
            environmentId
        }
    }

    /**
     * Resolves whether Passthrough should be active based on the current environment
     * and the persistent FeatureFlags.PASSTHROUGH state.
     */
    fun isPassthroughActive(
        activeEnvironmentId: String,
        isFlagEnabled: Boolean,
    ): Boolean {
        return (activeEnvironmentId == EnvironmentStore.ENV_PASSTHROUGH) || isFlagEnabled
    }
}
