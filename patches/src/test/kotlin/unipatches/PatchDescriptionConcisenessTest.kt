package unipatches

import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PatchDescriptionConcisenessTest {
    @Test
    fun publishedPatchDescriptionsStayConcise() {
        val catalog = JsonParser.parseReader(File("../patches-list.json").reader()).asJsonObject
        val limits = mapOf(
            "Ads Block Patch ( Experimental, Enhanced, Overlay Support, UniManager Support )" to 1900,
            "Bypass Emulator Detection" to 500,
            "Bypass Forced Updates (Experimental)" to 220,
            "Control Embedded Auth / Stores Patch ( Enhanced )" to 900,
            "Custom App Display Patch (Experimental, Enhanced)" to 1000,
            "Custom App Output Patch (Experimental, Enhanced)" to 1300,
            "Disable Forced Online Checks (Experimental)" to 440,
            "Embed Frida Gadget ( Advanced )" to 390,
            "Hill Climb Racing Example Overlay Addon" to 450,
            "Legacy App Compatibility Patch ( Experimental, Enhanced )" to 1150,
            "PairIP Bypass Patch (Experimental, Enhanced)" to 1700,
            "Permission Guard Patch ( Experimental, Overlay Support, UniManager Support )" to 800,
            "Universal Overlay Patch v2.6.1 ( Experimental, UniManager Support )" to 2200,
        )

        val patches = catalog.getAsJsonArray("patches").associateBy { it.asJsonObject.get("name").asString }
        limits.forEach { (name, limit) ->
            val description = patches.getValue(name).asJsonObject.get("description").asString
            assertTrue("$name description is ${description.length} chars; limit is $limit", description.length <= limit)
        }
    }
}
