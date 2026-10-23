package unipatches.iap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.ByteArrayInputStream
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevenueCatAmazonCompatibilityTest {
    private fun factory(registers: Int = 5) = ImmutableMethod(
        "Lcom/revenuecat/purchases/BillingFactory;", "createBilling",
        listOf(ImmutableMethodParameter("Lcom/revenuecat/purchases/Store;", null, null)),
        "Lcom/revenuecat/purchases/common/BillingAbstract;", 0, null, null,
        ImmutableMethodImplementation(registers, emptyList(), emptyList(), emptyList()),
    ).toMutable().apply {
        addInstructionsWithLabels(0, """
            if-eqz p1, :play
            new-instance v0, Lcom/revenuecat/purchases/amazon/AmazonBilling;
            invoke-direct {v0}, Lcom/revenuecat/purchases/amazon/AmazonBilling;-><init>()V
            check-cast v0, Lcom/revenuecat/purchases/common/BillingAbstract;
            return-object v0
            :play
            new-instance v0, Lcom/revenuecat/purchases/google/BillingWrapper;
            invoke-direct {v0}, Lcom/revenuecat/purchases/google/BillingWrapper;-><init>()V
            return-object v0
        """.trimIndent())
    }

    @Test
    fun removesAmazonReferencesAndPreservesPlayDispatch() {
        val method = factory()
        assertTrue(isolateMissingRevenueCatAmazonBranch(method))
        val instructions = method.implementation!!.instructions.toList()
        val references = instructions.mapNotNull { (it as? ReferenceInstruction)?.reference }
        assertFalse(references.any {
            when (it) {
                is TypeReference -> it.type.contains("/amazon/")
                is MethodReference -> it.definingClass.contains("/amazon/")
                else -> false
            }
        })
        assertTrue(references.any { it is MethodReference &&
            it.definingClass == "Lcom/revenuecat/purchases/google/BillingWrapper;" })
        assertTrue(instructions.any { it.opcode == Opcode.THROW })
        // The original store-selection jump must still target the Play allocation.
        val targetOffset = (instructions.first() as OffsetInstruction).codeOffset
        var offset = 0
        val target = instructions.first { instruction ->
            val matches = offset == targetOffset
            offset += instruction.codeUnits
            matches
        }
        assertEquals("Lcom/revenuecat/purchases/google/BillingWrapper;",
            ((target as ReferenceInstruction).reference as TypeReference).type)
        assertEquals(5, method.implementation!!.registerCount)
        assertFalse(isolateMissingRevenueCatAmazonBranch(method))
    }

    @Test
    fun leavesTightFramesUnchanged() {
        val method = factory(registers = 3)
        val before = method.implementation!!.instructions.toList()
        assertFalse(isolateMissingRevenueCatAmazonBranch(method))
        assertEquals(before, method.implementation!!.instructions.toList())
    }

    @Test
    fun leavesUnfamiliarBranchShapesUnchanged() {
        val method = factory()
        method.addInstructionsWithLabels(2, "goto :end\n:end\nnop")
        val before = method.implementation!!.instructions.toList()
        assertFalse(isolateMissingRevenueCatAmazonBranch(method))
        assertEquals(before, method.implementation!!.instructions.toList())
    }
    @Test
    fun survivesDexSerializationWithTheOriginalAmazonCatchHandler() {
        val original = factory()
        original.addInstructions(original.implementation!!.instructions.count(), "move-exception v0\nthrow v0")
        val instructions = original.implementation!!.instructions.toList()
        val constructorStart = instructions.take(2).sumOf { it.codeUnits }
        val handlerStart = instructions.dropLast(2).sumOf { it.codeUnits }
        val method = ImmutableMethod(
            original.definingClass, original.name, original.parameters, original.returnType,
            original.accessFlags, original.annotations, original.hiddenApiRestrictions,
            ImmutableMethodImplementation(5, instructions,
                listOf(ImmutableTryBlock(constructorStart, instructions[2].codeUnits,
                    listOf(ImmutableExceptionHandler("Ljava/lang/NoClassDefFoundError;", handlerStart)))),
                emptyList()),
        ).toMutable()
        assertTrue(isolateMissingRevenueCatAmazonBranch(method))
        val pool = DexPool(Opcodes.getDefault())
        pool.internClass(ImmutableClassDef(method.definingClass, 1, "Ljava/lang/Object;",
            emptyList(), null, emptySet(), emptyList(), listOf(method)))
        val output = MemoryDataStore()
        pool.writeTo(output)
        val dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), ByteArrayInputStream(output.data))
        val emitted = dex.classes.single().methods.single().implementation!!
        assertTrue(emitted.instructions.any { it.opcode == Opcode.THROW })
        for (block in emitted.tryBlocks) {
            assertTrue(block.codeUnitCount > 0)
            assertTrue(block.exceptionHandlers.all { it.handlerCodeAddress >= 0 })
        }
    }

}
