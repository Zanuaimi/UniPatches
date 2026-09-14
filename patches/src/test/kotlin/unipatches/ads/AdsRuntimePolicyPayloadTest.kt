package unipatches.ads

import helpers.ads.*
import org.junit.Assert.assertEquals
import org.junit.Test

class AdsRuntimePolicyPayloadTest {
    private fun settings(
        noAds: Boolean = true,
        rewarded: Boolean = false,
        rewards: Boolean = true,
        hosts: Boolean = true,
    ) = AdsPatchSettings(
        noAdsEnabled = noAds,
        blockInterstitials = true,
        blockBanners = true,
        blockAppOpen = true,
        blockMRec = true,
        blockRewarded = rewarded,
        blockNative = true,
        rewardsEnabled = rewards,
        skipRewardedAds = true,
        instantReward = true,
        fakeAdAvailability = true,
        hostsEnabled = hosts,
        wildcardHosts = true,
    )

    private fun selection(
        policy: Boolean = true,
        noAds: Boolean = false,
        rewards: Boolean = false,
        hosts: Boolean = false,
    ) = AdsRuntimeSelection(policy, noAds, rewards, hosts)

    @Test
    fun plannerResolvesIndependentStaticAndRuntimePaths() {
        val plan = AdsPatchPlanner.resolve(
            settings(),
            selection(noAds = true, rewards = true, hosts = false),
            AdsSdkCoverage(),
        )
        assertEquals(AdsPatchMode.RUNTIME, plan.noAds.mode)
        assertEquals(AdsPatchMode.RUNTIME, plan.rewards.mode)
        assertEquals(AdsPatchMode.STATIC, plan.hosts.mode)
        assertEquals(true, plan.noAds.runtimeEligible)
        assertEquals(true, plan.rewards.runtimeEligible)
        assertEquals(false, plan.hosts.runtimeEligible)
        assertEquals(AdsPatchExecutionMode.HYBRID, plan.executionMode)
        assertEquals(3, plan.runtimeModuleMask)
    }

    @Test
    fun plannerResolvesAllRuntimePathsIndependently() {
        val plan = AdsPatchPlanner.resolve(
            settings(),
            selection(noAds = true, rewards = true, hosts = true),
            AdsSdkCoverage(),
        )
        assertEquals(AdsPatchMode.RUNTIME, plan.noAds.mode)
        assertEquals(AdsPatchMode.RUNTIME, plan.rewards.mode)
        assertEquals(AdsPatchMode.RUNTIME, plan.hosts.mode)
        assertEquals(AdsPatchExecutionMode.RUNTIME, plan.executionMode)
        assertEquals(7, plan.runtimeModuleMask)
    }

    @Test
    fun plannerResolvesNoAdsRuntimeAndRewardsStaticAsHybrid() {
        val plan = AdsPatchPlanner.resolve(
            settings(),
            selection(noAds = true, rewards = false, hosts = false),
            AdsSdkCoverage(),
        )
        assertEquals(AdsPatchMode.RUNTIME, plan.noAds.mode)
        assertEquals(AdsPatchMode.STATIC, plan.rewards.mode)
        assertEquals(AdsPatchExecutionMode.HYBRID, plan.executionMode)
        assertEquals(1, plan.runtimeModuleMask)
    }

    @Test
    fun plannerResolvesNoAdsStaticAndRewardsRuntimeAsHybrid() {
        val plan = AdsPatchPlanner.resolve(
            settings(),
            selection(noAds = false, rewards = true, hosts = false),
            AdsSdkCoverage(),
        )
        assertEquals(AdsPatchMode.STATIC, plan.noAds.mode)
        assertEquals(AdsPatchMode.RUNTIME, plan.rewards.mode)
        assertEquals(AdsPatchExecutionMode.HYBRID, plan.executionMode)
        assertEquals(2, plan.runtimeModuleMask)
    }

    @Test
    fun plannerDisablesPathWithoutMasterAndUsesStaticFallbackWithoutRuntimeModule() {
        val plan = AdsPatchPlanner.resolve(
            settings(noAds = false),
            selection(noAds = true, rewards = false, hosts = false),
            AdsSdkCoverage(),
        )
        assertEquals(AdsPatchMode.DISABLED, plan.noAds.mode)
        assertEquals(false, plan.noAds.runtimeEligible)
        assertEquals(AdsPatchMode.STATIC, plan.rewards.mode)
        assertEquals(0, plan.runtimeModuleMask)

        val static = AdsPatchPlanner.resolve(
            settings(),
            selection(policy = false, noAds = true, rewards = true),
            AdsSdkCoverage(),
        )
        assertEquals(AdsPatchMode.STATIC, static.noAds.mode)
        assertEquals(AdsPatchMode.STATIC, static.rewards.mode)
        assertEquals(0, static.runtimeModuleMask)
    }

    @Test
    fun allMastersDisabledProduceNoPatchModesOrModules() {
        val plan = AdsPatchPlanner.resolve(
            settings(noAds = false, rewards = false, hosts = false),
            selection(noAds = true, rewards = true, hosts = true),
            AdsSdkCoverage(max = false, adMob = false, unity = false),
        )
        assertEquals(AdsPatchMode.DISABLED, plan.noAds.mode)
        assertEquals(AdsPatchMode.DISABLED, plan.rewards.mode)
        assertEquals(AdsPatchMode.DISABLED, plan.hosts.mode)
        // Hosts is the explicit exception: it may be visible while its master keeps it off.
        assertEquals(4, plan.runtimeModuleMask)
        assertEquals(AdsPatchExecutionMode.DISABLED, plan.executionMode)
    }

    @Test
    fun runtimePolicyWithNoSelectedModuleUsesStaticPaths() {
        val plan = AdsPatchPlanner.resolve(settings(), selection(), AdsSdkCoverage())
        assertEquals(AdsPatchMode.STATIC, plan.noAds.mode)
        assertEquals(AdsPatchMode.STATIC, plan.rewards.mode)
        assertEquals(AdsPatchMode.STATIC, plan.hosts.mode)
        assertEquals(false, plan.runtimePolicyEnabled)
        assertEquals(0, plan.runtimeModuleMask)
    }

    @Test
    fun hostsRuntimeStartsEnabledWhenItsMasterIsEnabled() {
        val plan = AdsPatchPlanner.resolve(settings(hosts = true), selection(hosts = true), AdsSdkCoverage())
        assertEquals(AdsPatchMode.RUNTIME, plan.hosts.mode)
        assertEquals(true, plan.hosts.initialEnabled)
        assertEquals(4, plan.runtimeModuleMask)
    }

    @Test
    fun noSdkCoverageDoesNotChangeResolvedModes() {
        val plan = AdsPatchPlanner.resolve(
            settings(),
            selection(noAds = true, rewards = true, hosts = true),
            AdsSdkCoverage(
                max = false, adMob = false, unity = false, ironSource = false,
                appLovin = false, vungle = false, meta = false, pangle = false,
                huawei = false, yandex = false, startApp = false, moPub = false,
                chartboost = false, inMobi = false, mintegral = false,
            ),
        )
        assertEquals(AdsPatchMode.RUNTIME, plan.noAds.mode)
        assertEquals(AdsPatchMode.RUNTIME, plan.rewards.mode)
        assertEquals(AdsPatchMode.RUNTIME, plan.hosts.mode)
        assertEquals(7, plan.runtimeModuleMask)
    }

    @Test
    fun hostsModuleCanBeVisibleButStartsDisabledWhenItsMasterIsOff() {
        val plan = AdsPatchPlanner.resolve(
            settings(hosts = false),
            selection(hosts = true),
            AdsSdkCoverage(),
        )
        assertEquals(AdsPatchMode.DISABLED, plan.hosts.mode)
        assertEquals(false, plan.hosts.initialEnabled)
        assertEquals(4, plan.runtimeModuleMask)
    }

    @Test
    fun staticRewardedBlockingDisablesConflictingRewardsPath() {
        val plan = AdsPatchPlanner.resolve(
            settings(rewarded = true),
            selection(noAds = false, rewards = true),
            AdsSdkCoverage(),
        )
        assertEquals(AdsPatchMode.STATIC, plan.noAds.mode)
        assertEquals(AdsPatchMode.DISABLED, plan.rewards.mode)
        assertEquals(0, plan.runtimeModuleMask)
    }

    @Test
    fun moduleMasksUseExpectedBits() {
        assertEquals(1, buildAdsRuntimeModuleMask(true, true, false, false))
        assertEquals(2, buildAdsRuntimeModuleMask(true, false, true, false))
        assertEquals(3, buildAdsRuntimeModuleMask(true, true, true, false))
        assertEquals(4, buildAdsRuntimeModuleMask(true, false, false, true))
        assertEquals(7, buildAdsRuntimeModuleMask(true, true, true, true))
    }

    @Test
    fun disabledRuntimeControlsProduceNoModuleMask() {
        assertEquals(0, buildAdsRuntimeModuleMask(false, true, true, true))
        assertEquals(0, buildAdsRuntimeModuleMask(true, false, false, false))
    }

    @Test
    fun blockedFormatMaskUsesIndividualCheckboxesWithoutPresetOverrides() {
        assertEquals(0, buildAdsBlockedFormatsMask(false, false, false, false, false, false))
        assertEquals(16, buildAdsBlockedFormatsMask(false, false, false, false, true, false))
        assertEquals(47, buildAdsBlockedFormatsMask(true, true, true, true, false, true))
        assertEquals(63, buildAdsBlockedFormatsMask(true, true, true, true, true, true))
    }

    @Test
    fun runtimePolicyRequiresAtLeastOneModule() {
        assertEquals(false, isAdsRuntimePolicyActive(true, false, false, false))
        assertEquals(true, isAdsRuntimePolicyActive(true, false, true, false))
        assertEquals(false, isAdsRuntimePolicyActive(false, true, true, true))
    }

    @Test
    fun genericReadinessFallbackDoesNotMatchBillingClasses() {
        assertEquals(false, isAdsDefiningClass(
            "Lcom/unity3d/services/store/gpbl/bridges/billingclient/common/BillingClientBridgeCommon;",
            null,
        ))
        assertEquals(true, isAdsDefiningClass("Lcom/unity3d/ads/Advertisement;", null))
        assertEquals(false, isAdsDefiningClass(
            "Lcom/google/android/exoplayer2/source/ads/ServerSideAdInsertionMediaSource\$SampleStreamImpl;",
            null,
        ))
        assertEquals(false, hasKnownAdsReference(
            "Landroidx/media3/exoplayer/source/ads/ServerSideAdInsertionMediaSource\$SampleStreamImpl;->isReady()Z",
        ))
        assertEquals(false, isKnownAdsClass(
            "Lcom/mbridge/msdk/playercommon/exoplayer2/source/SingleSampleMediaPeriod\$SampleStreamImpl;",
        ))
        assertEquals(false, hasKnownAdsReference(
            "Lcom/mbridge/msdk/playercommon/exoplayer2/source/SingleSampleMediaPeriod\$SampleStreamImpl;->isReady()Z",
        ))
        assertEquals(false, isKnownAdsClass(
            "Lcom/unity3d/services/store/gpbl/bridges/billingclient/common/BillingClientBridgeCommon;",
        ))
        assertEquals(true, hasKnownAdsReference(
            "Lcom/google/android/gms/ads/AdView;->isLoading()Z",
        ))
    }

    @Test
    fun targetedFallbackRequiresTheExactDefiningClass() {
        assertEquals(true, isAdsFallbackCandidate(
            classType = "Lcom/applovin/mediation/ads/MaxRewardedAd;",
            target = "Lcom/applovin/mediation/ads/MaxRewardedAd;",
            hasAdsReference = false,
        ))
        assertEquals(false, isAdsFallbackCandidate(
            classType = "Lcom/applovin/mediation/ads/MaxInterstitialAd;",
            target = "Lcom/applovin/mediation/ads/MaxRewardedAd;",
            hasAdsReference = true,
        ))
        assertEquals(true, isAdsFallbackCandidate(
            classType = "Lcom/custom/ObfuscatedAd;",
            target = null,
            hasAdsReference = true,
        ))
    }

    @Test
    fun disabledRewardsAddonDoesNotEnableRuntimeRewards() {
        assertEquals(false, isAdsRuntimeRewardsEnabled(true, false))
        assertEquals(false, isAdsRuntimeRewardsEnabled(false, true))
        assertEquals(true, isAdsRuntimeRewardsEnabled(true, true))
        assertEquals(false, isAdsRuntimeModuleEnabled(true, false, true))
        assertEquals(true, isAdsRuntimeModuleEnabled(true, true, true))
    }

    @Test
    fun sdkCoverageKeepsOtherSdkSelectionsIndependent() {
        val coverage = AdsSdkCoverage(
            startApp = false,
            moPub = true,
            chartboost = false,
            inMobi = true,
            mintegral = false,
        )

        assertEquals(false, coverage.startApp)
        assertEquals(true, coverage.moPub)
        assertEquals(false, coverage.chartboost)
        assertEquals(true, coverage.inMobi)
        assertEquals(false, coverage.mintegral)
    }

    @Test
    fun everyNoAdsAdapterExposesSeparateStaticAndRuntimeContract() {
        assertEquals(true, NoAdsSdkAdapter::class.java.isAssignableFrom(UnityNoAdsAdapter::class.java))
        assertEquals(true, NoAdsSdkAdapter::class.java.isAssignableFrom(AdMobNoAdsAdapter::class.java))
        assertEquals(true, NoAdsSdkAdapter::class.java.isAssignableFrom(IronSourceNoAdsAdapter::class.java))
        assertEquals(true, NoAdsSdkAdapter::class.java.isAssignableFrom(OtherNoAdsAdapter::class.java))
        assertEquals(true, NoAdsSdkAdapter::class.java.isAssignableFrom(MaxNoAdsAdapterSet::class.java))
    }

    @Test
    fun unityV4SharedShowIsNeverPermanentlyPatchedInRuntimeMode() {
        assertEquals(false, shouldPatchUnityAdsV4Permanently(true, true, true, true))
        assertEquals(false, shouldPatchUnityAdsV4Permanently(true, true, true, false))
        assertEquals(true, shouldPatchUnityAdsV4Permanently(false, true, true, true))
        assertEquals(false, shouldPatchUnityAdsV4Permanently(false, false, true, true))
    }

    @Test
    fun injectedBooleanGuardsAlwaysReturnABoolean() {
        assertEquals("const/4 v0, 0x0\nreturn v0", booleanReturnInstructions(false))
        assertEquals("const/4 v0, 0x1\nreturn v0", booleanReturnInstructions(true))
    }

    @Test
    fun availabilityGuardsRequireASeparateLocalRegister() {
        assertEquals(false, hasSafeLocalRegister(2, 2))
        assertEquals(true, hasSafeLocalRegister(3, 2))
    }

    @Test
    fun runtimeRewardsDoesNotUseParameterMutatingMaxUnityWrapperHooks() {
        // Runtime MAX must use the native adapter or leave the Unity bridge untouched until a
        // parameter-preserving callback implementation exists.
        assertEquals(true, shouldSkipMaxUnityWrapperForRuntime(true))
        assertEquals(false, shouldSkipMaxUnityWrapperForRuntime(false))
    }

    @Test
    fun maxRuntimeCoverageRemainsDisabledForEitherRuntimePath() {
        assertEquals(true, shouldSkipMaxRuntimeCoverage(true, false, true))
        assertEquals(true, shouldSkipMaxRuntimeCoverage(false, true, true))
    }

    @Test
    fun runtimeNoAdsSkipsUnplannedFingerprintsInsteadOfMakingStaticEdits() {
        assertEquals(true, shouldSkipUnplannedRuntimePatch(true, false))
        assertEquals(false, shouldSkipUnplannedRuntimePatch(true, true))
        assertEquals(false, shouldSkipUnplannedRuntimePatch(false, false))
    }

    @Test
    fun maxRuntimeCoverageIsSkippedUntilItsHooksAreRequestScoped() {
        assertEquals(true, shouldSkipMaxRuntimeCoverage(true, false, true))
        assertEquals(true, shouldSkipMaxRuntimeCoverage(false, true, true))
        assertEquals(false, shouldSkipMaxRuntimeCoverage(false, false, true))
        assertEquals(false, shouldSkipMaxRuntimeCoverage(true, true, false))
    }

    @Test
    fun runtimeNoAdsCoordinatorHonorsCoverageBeforeBuildingFingerprintPlan() {
        val noMax = RuntimeNoAdsCoordinator(
            sdkCoverage = AdsSdkCoverage(max = false, adMob = false, unity = false),
            blockAdsRuntime = true,
            rewardsRuntime = true,
        ).fingerprintCategories()
        assertEquals(false, noMax.containsKey(ShowRewardedAdFingerprint))
        assertEquals(false, noMax.containsKey(MaxRewardedAdIsReadyFingerprint))
        assertEquals(false, noMax.containsKey(AdMobRewardedShowFingerprint))
        assertEquals(false, noMax.containsKey(UnityRewardedAdShowFingerprint))

        val rewardsOnly = RuntimeNoAdsCoordinator(
            sdkCoverage = AdsSdkCoverage(max = true, adMob = false, unity = false),
            blockAdsRuntime = false,
            rewardsRuntime = true,
        ).fingerprintCategories()
        assertEquals(false, rewardsOnly.containsKey(MaxRewardedAdIsReadyFingerprint))
        assertEquals(false, rewardsOnly.containsKey(MaxRewardedAdShowAdFingerprint))
        assertEquals(true, rewardsOnly.isEmpty())
    }

    @Test
    fun typedGuardsValidateReturnTypesLocalsAndParameterWrites() {
        assertEquals(false, isTypedGuardSafe("V", AdsGuardReturnType.VOID, 0, "return-void"))
        assertEquals(true, isTypedGuardSafe("Ljava/lang/Object;", AdsGuardReturnType.OBJECT, 1, "return-object v0"))
        assertEquals(true, isTypedGuardSafe("I", AdsGuardReturnType.INT, 1, "return v0"))
        assertEquals(false, isTypedGuardSafe("Z", AdsGuardReturnType.VOID, 1, "return-void"))
        assertEquals(false, isTypedGuardSafe("Z", AdsGuardReturnType.BOOLEAN, 0, "const/4 v0, 0x1\nreturn v0"))
        assertEquals(false, isTypedGuardSafe("V", AdsGuardReturnType.VOID, 1, "const/4 p0, 0x0\nreturn-void"))
        assertEquals(false, isTypedGuardSafe("V", AdsGuardReturnType.VOID, 1, "move-object/from16 p0, v0\nreturn-void"))
        assertEquals(false, isTypedGuardSafe("V", AdsGuardReturnType.VOID, 1, "move-wide p1, v0\nreturn-void"))
    }

    @Test
    fun injectedLabelsAreNamespacedToAvoidTargetMethodCollisions() {
        val body = "if-eqz v0, :cond_0\n:cond_0\nsget-object v0, Lcom/example/Ads${'$'}State;->READY:Lcom/example/Ads${'$'}State;\nreturn-void"
        val namespaced = uniquifyInjectedLabels(body, "Lcom/example/Target;->show")
        assertEquals(false, namespaced.contains(":cond_0"))
        assertEquals(true, namespaced.contains("return-void"))
        assertEquals(true, namespaced.contains("Lcom/example/Ads${'$'}State;->READY:Lcom/example/Ads${'$'}State;"))
        assertEquals(2, Regex(":unipatch_ads_[A-Za-z0-9_]+_cond_0").findAll(namespaced).count())
    }

    @Test
    fun skipRewardedIsIndependentFromInstantReward() {
        val previous = adsFreeRewardsRuntimeGuardEnabled
        try {
            adsFreeRewardsRuntimeGuardEnabled = true
            val guard = guardedInstantReward("return-void", "morphe_reward_original")
            assertEquals(true, guard.indexOf("shouldSkipRewarded") < guard.indexOf("shouldGrantReward"))
            assertEquals(true, guard.contains("return-void"))
            assertEquals(true, guard.contains(":morphe_reward_original"))
        } finally {
            adsFreeRewardsRuntimeGuardEnabled = previous
        }
    }

    @Test
    fun rewardsAddonSerializesModuleBitTwo() {
        assertEquals("1|2|0|0|1|1|0|0||0", serializeAdsRuntimePolicy(2, 0, false, true, true, false, false, emptyList()))
    }

    @Test
    fun hostListSerializationIsStableAndSorted() {
        assertEquals("1|7|16|1|1|1|1|1|a.example,z.example|1", serializeAdsRuntimePolicy(7, 16, true, true, true, true, true, listOf("z.example", "a.example")))
    }

    @Test
    fun hostCapabilityIsSerializedSeparatelyFromInitialState() {
        assertEquals(
            "1|4|0|0|0|0|0|1||0",
            serializeAdsRuntimePolicy(4, 0, false, false, false, false, true, emptyList(), hostsAllowedEnabled = false),
        )
    }
}
