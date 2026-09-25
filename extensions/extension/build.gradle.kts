extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "unipatch.extension"
    // Runtime hooks guard SDK levels in code; upstream-vendored bypass code
    // (org.lsposed.hiddenapibypass) triggers NewApi lint that is safe to skip.
    lint {
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
