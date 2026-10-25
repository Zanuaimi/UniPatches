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
    implementation(libs.xz)
    testImplementation("junit:junit:4.13.2")
    // The patcher exposes its execution flow as a Flow, so the real-APK smoke
    // test needs coroutines on the *compile* classpath. The patcher only
    // publishes them as runtime scope.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    // Compile real-APK tests against the patcher's already-installed runtime APIs.
    testCompileOnly(files(configurations.runtimeClasspath))
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

    // Applies a named subset of this bundle to one APK and writes an unsigned,
    // fully re-zipped output. Alignment and signing stay external, so the same
    // zipalign -> apksigner chain used by the other local mods produces the
    // installable file.
    //
    //   ./gradlew :patches:patchLocalApk -Papk=in.apk -Pout=out.apk -Ppatches=inapp,verify
    register<JavaExec>("patchLocalApk") {
        group = "unipatches"
        description = "Patch a local APK: -Papk=<in.apk> -Pout=<out.apk> [-Ppatches=inapp,verify] [-Poptions=verifyVerbose=true]"

        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("tools.PatchApkToolKt")
        // A 150 MB APK plus its decoded dex lives well past the default heap.
        maxHeapSize = "6g"

        doFirst {
            val apk = System.getenv("UNIPATCH_APK")?.takeIf { it.isNotBlank() }
                ?: (project.findProperty("apk") as? String)?.takeIf { it.isNotBlank() }
                ?: throw GradleException("pass -Papk=<path to the input APK> or set UNIPATCH_APK")
            val out = System.getenv("UNIPATCH_OUT")?.takeIf { it.isNotBlank() }
                ?: (project.findProperty("out") as? String)?.takeIf { it.isNotBlank() }
                ?: throw GradleException("pass -Pout=<path to the output APK> or set UNIPATCH_OUT")
            val selected = System.getenv("UNIPATCH_PATCHES")?.takeIf { it.isNotBlank() }
                ?: (project.findProperty("patches") as? String)?.takeIf { it.isNotBlank() }
                ?: "inapp,verify"
            val options = System.getenv("UNIPATCH_OPTIONS")?.takeIf { it.isNotBlank() }
                ?: (project.findProperty("options") as? String)?.takeIf { it.isNotBlank() }
                ?: ""
            logger.lifecycle("patchLocalApk: $apk -> $out [$selected]${if (options.isEmpty()) "" else " options=$options"}")
            args(apk, out, selected, options)
        }
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
