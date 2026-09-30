package unipatches.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import helpers.bytecode.fireRewardedAdCallbacks
import helpers.bytecode.fireRewardedAdImmediateCallbacks
import unipatches.ads.hasResolvedLabels

class MaxRuntimeRewardsTest {
    @Test
    fun runtimeMaxShowGuardUsesPolicyAndKeepsOriginalFallback() {
        val guard = maxRuntimeShowGuard(
            skipCallbacks = "return-void",
            instantCallbacks = "return-void",
            requestSetup = "const-string v7, \"unit\"\ninvoke-static {v7}, Lunipatch/overlaycore/AdsRuntimePolicy;->beginInstantReward(Ljava/lang/String;)V",
            requestRegister = "v7",
            originalLabel = "max_show",
        )

        assertTrue(guard.contains("AdsRuntimePolicy;->shouldSkipRewarded()Z"))
        assertTrue(guard.contains("AdsRuntimePolicy;->shouldGrantReward()Z"))
        assertTrue(guard.contains("AdsRuntimePolicy;->beginInstantReward(Ljava/lang/String;)V"))
        assertTrue(guard.contains("AdsRuntimePolicy;->armInstantReward(Ljava/lang/String;)V"))
        assertTrue(guard.contains("if-eqz v7"))
        assertTrue(guard.contains(":unipatch_ads_max_show_original"))
        assertTrue(guard.contains("return-void"))
        assertTrue(hasResolvedLabels(guard))
    }

    @Test
    fun runtimeMaxShowGuardDoesNotDuplicateCallbackLabels() {
        val guard = maxRuntimeShowGuard(
            skipCallbacks = "invoke-static {p0}, Lunipatch/overlaycore/MaxRuntimeBridge;->dispatchNativeSyntheticReward(Ljava/lang/Object;)V",
            instantCallbacks = "invoke-static {p0}, Lunipatch/overlaycore/MaxRuntimeBridge;->dispatchNativeImmediateReward(Ljava/lang/Object;)V",
            requestSetup = "const-string v7, \"unit\"",
            requestRegister = "v7",
            originalLabel = "max_show",
        )

        val declarations = Regex("(?m)^:([^\\s]+)$").findAll(guard).map { it.groupValues[1] }.toList()
        assertTrue(declarations.size == declarations.toSet().size)
        assertTrue(hasResolvedLabels(guard))
        assertTrue(guard.contains("MaxRuntimeBridge;->dispatchNativeImmediateReward(Ljava/lang/Object;)V"))
    }

    @Test
    fun runtimeMaxShowGuardDoesNotWriteParameters() {
        val guard = maxRuntimeShowGuard(
            skipCallbacks = "invoke-static {p0}, Lexample/Callbacks;->run(Ljava/lang/Object;)V",
            instantCallbacks = "return-void",
            requestSetup = "const-string v7, \"unit\"",
            requestRegister = "v7",
            originalLabel = "max_show_original",
        )

        assertFalse(guard.contains("const/4 p"))
        assertFalse(guard.contains("move-object p"))
        assertFalse(guard.contains("new-instance p"))
    }

    @Test
    fun nativeRewardCallbacksResolveTheInheritedImplementationListener() {
        val callbacks = fireRewardedAdCallbacks()
        val immediateCallbacks = fireRewardedAdImmediateCallbacks()

        assertTrue(callbacks.contains("Lcom/applovin/impl/mediation/ads/MaxFullscreenAdImpl;"))
        assertTrue(callbacks.contains("Ljava/lang/Class;->getSuperclass()Ljava/lang/Class;"))
        assertTrue(callbacks.contains("invoke-virtual {v6}, Ljava/lang/Class;->getSuperclass()Ljava/lang/Class;"))
        assertTrue(callbacks.contains("MaxRewardedAdListener;->onUserRewarded"))
        assertFalse(callbacks.contains("MaxUnityAdManager;->forwardUnityEvent"))
        assertTrue(immediateCallbacks.contains(":callback_done"))
        assertTrue(immediateCallbacks.contains(":implementation_next"))
        assertTrue(immediateCallbacks.contains("goto :callback_done"))
        assertTrue(immediateCallbacks.contains("MaxRewardedAdListener;->onUserRewarded"))
        assertFalse(immediateCallbacks.contains("onRewardedVideoCompleted"))
    }
}
