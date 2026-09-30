extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "unipatch.extension"
    // InAppRuntimePolicy builds a Handler on the main looper in its static
    // initializer; without stubbed defaults the mockable android.jar throws
    // "Stub!" and every test touching that class dies in class loading.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
