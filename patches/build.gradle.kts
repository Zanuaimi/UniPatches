group = "unipatches"

patches {
    about {
        name = "Zanuaimi's UniPatches"
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
    testImplementation("junit:junit:4.13.2")
    // The patcher exposes its execution flow as a Flow, so the real-APK smoke
    // test needs coroutines on the *compile* classpath. The patcher only
    // publishes them as runtime scope.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    patchListGeneratorClasspath(libs.gson)
}

// -PunipatchApk=<apk> points the InApp preflight (RealApkInAppSmokeTest) at
// any game binary, so a bundle can be qualified against a game before it is
// pushed to the device. The shell variable takes precedence when both are set.
val unipatchApk = findProperty("unipatchApk")?.toString()

tasks {
    withType<Test>().configureEach {
        if (!unipatchApk.isNullOrBlank() && System.getenv("UNIPATCHES_SMOKE_APK").isNullOrBlank()) {
            environment("UNIPATCHES_SMOKE_APK", unipatchApk)
        }
    }

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
