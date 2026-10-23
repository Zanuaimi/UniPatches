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
    // Runtime hooks guard SDK levels in code; upstream-vendored bypass code
    // (org.lsposed.hiddenapibypass) triggers NewApi lint that is safe to skip.
    lint {
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
