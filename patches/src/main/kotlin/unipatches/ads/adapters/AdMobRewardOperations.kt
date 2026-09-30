package unipatches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.ads.*
import helpers.bytecode.*
import java.util.logging.Logger

private data class AdMobCallSite(
    val index: Int,
    val listenerRegister: Int,
    val originalInvoke: String,
)

private data class AdMobMethodMatch(
    val classType: String,
    val methodName: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val callSites: List<AdMobCallSite>,
)

internal fun BytecodePatchContext.applyLegacyAdMobRewardedStrategy(logger: Logger, useAdMob: Boolean, instantReward: Boolean?) {
    if (!useAdMob || (instantReward != true && !adsFreeRewardsRuntimeGuardEnabled)) return
    // AdMob RewardedAd is from GMS (not in app dex), so patch call sites instead of definition
    var patchedCallSites = 0
    classDefForEach { classDef ->
        val tl = classDef.type.lowercase()
        if (tl.contains("okhttp") || tl.contains("androidx") || tl.contains("com/google/android/gms/ads/rewarded")) return@classDefForEach
        val matches = classDef.methods.mapNotNull { method ->
            val callSites = mutableListOf<AdMobCallSite>()
            method.implementation?.instructions?.forEachIndexed { index, insn ->
                val ref = (insn as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference
                    ?: return@forEachIndexed
                if (ref.definingClass != "Lcom/google/android/gms/ads/rewarded/RewardedAd;" || ref.name != "show" || ref.returnType != "V") return@forEachIndexed
                if (ref.parameterTypes.size != 2 || ref.parameterTypes[1] != "Lcom/google/android/gms/ads/OnUserEarnedRewardListener;") return@forEachIndexed
                val listenerRegister = when (insn) {
                        is com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c -> {
                            // invoke-virtual {v0, v1, v2}, RewardedAd.show
                            // v0 = this (RewardedAd), v1 = Activity, v2 = listener
                            // Need to parse: for 35c, registerCount, registers C/D/E etc.
                            // For show with 3 regs (this, activity, listener), C=this, D=activity, E=listener
                            if (insn.registerCount < 3) return@forEachIndexed
                            insn.registerE
                        }
                        is com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc -> {
                            // invoke-virtual/range {v0..v2}
                            val start = insn.startRegister
                            start + 2
                        }
                        else -> return@forEachIndexed
                    }
                    val originalInvoke = when (insn) {
                        is com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c ->
                            "invoke-virtual {v${insn.registerC}, v${insn.registerD}, v${insn.registerE}}, Lcom/google/android/gms/ads/rewarded/RewardedAd;->show(Landroid/app/Activity;Lcom/google/android/gms/ads/OnUserEarnedRewardListener;)V"
                        is com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc ->
                            "invoke-virtual/range {v${insn.startRegister} .. v${insn.startRegister + 2}}, Lcom/google/android/gms/ads/rewarded/RewardedAd;->show(Landroid/app/Activity;Lcom/google/android/gms/ads/OnUserEarnedRewardListener;)V"
                        else -> return@forEachIndexed
                    }
                    callSites += AdMobCallSite(index, listenerRegister, originalInvoke)
            }
            if (callSites.isEmpty()) null else AdMobMethodMatch(
                classType = classDef.type,
                methodName = method.name,
                returnType = method.returnType,
                parameterTypes = method.parameterTypes.map { it.toString() },
                callSites = callSites,
            )
        }
        if (matches.isEmpty()) return@classDefForEach
        val mutableClass = try { mutableClassDefBy(classDef) } catch (_: Exception) { return@classDefForEach }
        matches.forEach { match ->
            val method = mutableClass.methods.firstOrNull {
                it.name == match.methodName &&
                    it.returnType == match.returnType &&
                    it.parameterTypes.map { parameter -> parameter.toString() } == match.parameterTypes
            } ?: return@forEach
            match.callSites.asReversed().forEach { callSite ->
                // Found call site: RewardedAd.show(Activity, OnUserEarnedRewardListener)
                // Replace it with a conditional reward callback or the original show call.
                try {
                    method.replaceInstruction(callSite.index, """
                        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldGrantReward()Z
                        move-result v0
                        if-eqz v0, :morphe_admob_check_skip_${callSite.index}
                        if-eqz v${callSite.listenerRegister}, :morphe_admob_check_skip_${callSite.index}
                        const/4 v0, 0x0
                        invoke-interface {v${callSite.listenerRegister}, v0}, Lcom/google/android/gms/ads/OnUserEarnedRewardListener;->onUserEarnedReward(Lcom/google/android/gms/ads/rewarded/RewardItem;)V
                        :morphe_admob_check_skip_${callSite.index}
                        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldSkipRewarded()Z
                        move-result v0
                        if-eqz v0, :morphe_admob_original_${callSite.index}
                        goto :morphe_admob_done_${callSite.index}
                        :morphe_admob_original_${callSite.index}
                        ${callSite.originalInvoke}
                        :morphe_admob_done_${callSite.index}
                    """.trimIndent())
                    patchedCallSites++
                } catch (_: Exception) {}
            }
        }
    }
    if (patchedCallSites > 0) {
        logger.info("Ads Free Rewards: AdMob patch - patched $patchedCallSites call site(s)")
    } else {
        logger.info("Ads Free Rewards: AdMob show call sites not found - skipping")
    }
}
