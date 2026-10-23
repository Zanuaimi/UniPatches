package unipatches.nativelib

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeLibrarySupportTest {
    @Test
    fun mapsAarch64ElfToArm64V8a() {
        val file = tempElf(bits = 64, machine = 183)
        assertEquals("arm64-v8a", detectElfAbi(file))
    }

    @Test
    fun mapsArm32ElfToArmeabiV7a() {
        val file = tempElf(bits = 32, machine = 40)
        assertEquals("armeabi-v7a", detectElfAbi(file))
    }

    @Test
    fun mapsX86AndX8664() {
        assertEquals("x86", detectElfAbi(tempElf(bits = 32, machine = 3)))
        assertEquals("x86_64", detectElfAbi(tempElf(bits = 64, machine = 62)))
    }

    @Test
    fun rejectsNonElfAndWrongClass() {
        val text = File.createTempFile("unipatch-not-elf-", ".so").apply {
            writeText("not an elf")
            deleteOnExit()
        }
        assertNull(detectElfAbi(text))
        assertNull(detectElfAbi(tempElf(bits = 32, machine = 183)))
    }

    @Test
    fun rejectsExecutablesAndBigEndianFiles() {
        assertNull(detectElfAbi(tempElf(bits = 64, machine = 183, type = 2)))
        assertNull(detectElfAbi(tempElf(bits = 64, machine = 183, littleEndian = false)))
    }

    @Test
    fun derivesLoadNameFromFileName() {
        assertEquals("Tool", resolveLibraryName(File("libTool.so"), null))
        assertEquals("Tool", resolveLibraryName(File("Tool.so"), null))
        assertEquals("native_hook", resolveLibraryName(File("libnative_hook.so"), null))
        assertEquals("Tool", resolveLibraryName(File("libTool.so"), " Tool "))
    }

    @Test
    fun rejectsPathAndPrefixOverrides() {
        assertThrows(PatchException::class.java) {
            resolveLibraryName(File("libTool.so"), "libTool")
        }
        assertThrows(PatchException::class.java) {
            resolveLibraryName(File("libTool.so"), "libTool.so")
        }
        assertThrows(PatchException::class.java) {
            resolveLibraryName(File("libTool.so"), "../Tool")
        }
    }

    @Test
    fun buildsAbiEntryName() {
        assertEquals("lib/arm64-v8a/libTool.so", libraryEntryName("arm64-v8a", "Tool"))
        assertThrows(IllegalArgumentException::class.java) {
            libraryEntryName("mips", "Tool")
        }
    }

    @Test
    fun identifiesApplicationAndActivityOnCreate() {
        assertTrue(isStartupOnCreate(applicationOnCreate()))
        assertTrue(isStartupOnCreate(activityOnCreate()))
        assertTrue(isStartupOnCreate(applicationOnCreate(), requireBundle = false))
        assertFalse(isStartupOnCreate(activityOnCreate(), requireBundle = false))
        assertTrue(isStartupOnCreate(activityOnCreate(), requireBundle = true))
        assertFalse(isStartupOnCreate(applicationOnCreate(), requireBundle = true))
        assertFalse(isStartupOnCreate(staticOnCreate()))
        assertFalse(isStartupOnCreate(abstractOnCreate()))
    }

    @Test
    fun injectsLoadBeforeApplicationOnCreateBody() {
        val method = applicationOnCreate()
        val owner = owner(method)
        val target = owner.methods.single()

        assertTrue(injectNativeLibraryLoad(owner, target, "Tool"))

        val injected = owner.methods.single()
        val instructions = injected.implementation!!.instructions.toList()
        // Application.onCreate has no dead local at v0, but cloneMutable's prologue
        // copies the real p0 (new v1) into v0 before the original body runs.
        assertEquals(Opcode.CONST_STRING, instructions.first().opcode)
        assertLoadCall(instructions, expectedRegister = 0)
        val restore = instructions[2] as TwoRegisterInstruction
        assertEquals(Opcode.MOVE_OBJECT_FROM16, restore.opcode)
        assertEquals(0, restore.registerA)
        assertEquals(1, restore.registerB)
        assertEquals(Opcode.RETURN_VOID, instructions.last().opcode)
    }

    @Test
    fun injectsLoadIntoActivityOnCreateWithBundle() {
        val method = activityOnCreate()
        val owner = owner(method)
        val target = owner.methods.single()

        assertTrue(injectNativeLibraryLoad(owner, target, "Tool"))

        val instructions = owner.methods.single().implementation!!.instructions.toList()
        assertEquals(Opcode.CONST_STRING, instructions.first().opcode)
        assertLoadCall(instructions, expectedRegister = 0)
        val restore = instructions[2] as TwoRegisterInstruction
        assertEquals(Opcode.MOVE_OBJECT_FROM16, restore.opcode)
        assertEquals(0, restore.registerA)
        assertEquals(2, restore.registerB)
    }

    @Test
    fun injectsIntoMethodWithManyRegisters() {
        val method = activityOnCreate(registerCount = 30)
        val owner = owner(method)
        val target = owner.methods.single()

        assertTrue(injectNativeLibraryLoad(owner, target, "Tool"))

        val instructions = owner.methods.single().implementation!!.instructions.toList()
        // v0 is below p0 (v28), so it is a dead local and needs no save/restore.
        // The load call must still use v0: invoke-static has a 4-bit register field.
        assertEquals(Opcode.CONST_STRING, instructions.first().opcode)
        assertLoadCall(instructions, expectedRegister = 0)
    }

    @Test
    fun replacesVirtualMethodViewWhenInitializedBeforeInjection() {
        val method = activityOnCreate()
        val owner = owner(method)
        val original = owner.virtualMethods.single()

        assertTrue(injectNativeLibraryLoad(owner, method, "Tool"))

        val replacement = owner.virtualMethods.single()
        assertEquals(1, owner.virtualMethods.size)
        assertNotSame(original, replacement)
        assertTrue(injectNativeLibraryLoad(owner, replacement, "Tool"))
    }

    @Test
    fun repeatedInjectionIsIdempotent() {
        val owner = owner(applicationOnCreate())
        val target = owner.methods.single()

        assertTrue(injectNativeLibraryLoad(owner, target, "Tool"))
        assertTrue(injectNativeLibraryLoad(owner, owner.methods.single(), "Tool"))

        val loadCalls = owner.methods.single().implementation!!.instructions.count { instruction ->
            val target = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            instruction.opcode == Opcode.INVOKE_STATIC &&
                target?.definingClass == "Ljava/lang/System;" &&
                target.name == "loadLibrary"
        }
        assertEquals(1, loadCalls)
    }

    private fun assertLoadCall(instructions: List<Instruction>, expectedRegister: Int) {
        val constIndex = instructions.indexOfFirst { it.opcode == Opcode.CONST_STRING }
        assertTrue("no const-string instruction", constIndex >= 0)
        val constString = instructions[constIndex]
        assertEquals(expectedRegister, (constString as OneRegisterInstruction).registerA)
        assertEquals(
            "Tool",
            ((constString as ReferenceInstruction).reference as StringReference).string,
        )

        val load = instructions[constIndex + 1]
        assertEquals(Opcode.INVOKE_STATIC, load.opcode)
        val target = (load as ReferenceInstruction).reference as MethodReference
        assertEquals("Ljava/lang/System;", target.definingClass)
        assertEquals("loadLibrary", target.name)
        assertEquals(listOf("Ljava/lang/String;"), target.parameterTypes.map { it.toString() })
        assertEquals("V", target.returnType)
    }

    private fun owner(method: MutableMethod): MutableClass = MutableClass(
        ImmutableClassDef(
            method.definingClass,
            AccessFlags.PUBLIC.value,
            "Landroid/app/Application;",
            emptyList(),
            null,
            emptySet(),
            emptyList(),
            listOf(method),
        ),
    )

    private fun applicationOnCreate(): MutableMethod = ImmutableMethod(
        "Lcom/example/App;",
        "onCreate",
        emptyList(),
        "V",
        AccessFlags.PUBLIC.value,
        null,
        null,
        ImmutableMethodImplementation(
            1,
            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
            emptyList(),
            emptyList(),
        ),
    ).toMutable()

    private fun activityOnCreate(registerCount: Int = 2): MutableMethod = ImmutableMethod(
        "Lcom/example/MainActivity;",
        "onCreate",
        listOf(ImmutableMethodParameter("Landroid/os/Bundle;", null, null)),
        "V",
        AccessFlags.PROTECTED.value,
        null,
        null,
        ImmutableMethodImplementation(
            registerCount,
            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
            emptyList(),
            emptyList(),
        ),
    ).toMutable()

    private fun staticOnCreate(): MutableMethod = ImmutableMethod(
        "Lcom/example/App;",
        "onCreate",
        emptyList(),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        ImmutableMethodImplementation(0, emptyList(), emptyList(), emptyList()),
    ).toMutable()

    private fun abstractOnCreate(): MutableMethod = ImmutableMethod(
        "Lcom/example/App;",
        "onCreate",
        emptyList(),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value,
        null,
        null,
        null,
    ).toMutable()

    private fun tempElf(bits: Int, machine: Int, type: Int = 3, littleEndian: Boolean = true): File {
        val file = File.createTempFile("unipatch-elf-", ".so")
        file.writeBytes(elfHeader(bits, machine, type, littleEndian))
        file.deleteOnExit()
        return file
    }

    private fun elfHeader(
        bits: Int,
        machine: Int,
        type: Int,
        littleEndian: Boolean,
    ): ByteArray {
        val header = ByteArray(64)
        header[0] = 0x7f.toByte()
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = if (bits == 64) 2 else 1
        header[5] = if (littleEndian) 1 else 2
        header[6] = 1
        writeLe16(header, 16, type)
        writeLe16(header, 18, machine)
        return header
    }

    private fun writeLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }
}
