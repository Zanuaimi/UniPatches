package unipatches.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import helpers.bytecode.fireRewardedAdCallbacks

class MaxRuntimeRewardsTest {
    @Test
    fun runtimeMaxShowGuardUsesPolicyAndKeepsOriginalFallback() {
        val guard = maxRuntimeShowGuard(
            skipCallbacks = "return-void",
            instantCallbacks = "return-void",
            requestSetup = "const-string v3, \"unit\"\ninvoke-static {v3}, Lunipatch/overlaycore/AdsRuntimePolicy;->beginInstantReward(Ljava/lang/String;)V",
            requestRegister = "v3",
            originalLabel = "max_show_original",
        )

        assertTrue(guard.contains("AdsRuntimePolicy;->shouldSkipRewarded()Z"))
        assertTrue(guard.contains("AdsRuntimePolicy;->shouldGrantReward()Z"))
        assertTrue(guard.contains("AdsRuntimePolicy;->beginInstantReward(Ljava/lang/String;)V"))
        assertTrue(guard.contains("AdsRuntimePolicy;->armInstantReward(Ljava/lang/String;)V"))
        assertTrue(guard.contains(":max_show_original"))
        assertTrue(guard.contains("return-void"))
    }

    @Test
    fun runtimeMaxShowGuardDoesNotWriteParameters() {
        val guard = maxRuntimeShowGuard(
            skipCallbacks = "invoke-static {p0}, Lexample/Callbacks;->run(Ljava/lang/Object;)V",
            instantCallbacks = "return-void",
            requestSetup = "const-string v3, \"unit\"",
            requestRegister = "v3",
            originalLabel = "max_show_original",
        )

        assertFalse(guard.contains("const/4 p"))
        assertFalse(guard.contains("move-object p"))
        assertFalse(guard.contains("new-instance p"))
    }

    @Test
    fun nativeRewardCallbacksResolveTheInheritedImplementationListener() {
        val callbacks = fireRewardedAdCallbacks()

        assertTrue(callbacks.contains("Lcom/applovin/impl/mediation/ads/MaxFullscreenAdImpl;"))
        assertTrue(callbacks.contains("Ljava/lang/Class;->getSuperclass()Ljava/lang/Class;"))
        assertTrue(callbacks.contains("MaxRewardedAdListener;->onUserRewarded"))
        assertFalse(callbacks.contains("MaxUnityAdManager;->forwardUnityEvent"))
    }
}
