group = "unipatches"

patches {
    about {
        name = "Zanuaimi's Morphe UniPatches"
        description = "Curated universal APK target level patches with selected community patches and original UniPatches patches."
        source = "https://github.com/Zanuaimi/UniPatches"
        author = "Zanuaimi"
        contact = "https://github.com/Zanuaimi"
        website = "https://github.com/Zanuaimi/UniPatches"
        license = "GPLv3"
    }
}

// Gson is used by the patch-time preset importer/exporter and by the
// generatePatchesList task. It is a patch-builder dependency, not an APK
// runtime dependency; the extension does not use it.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    implementation(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
