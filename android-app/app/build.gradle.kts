plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val shellRoot = rootProject.projectDir.parentFile
val runtimeDir = shellRoot.resolve("build/runtime")
val staged = shellRoot.resolve("build/app-inputs")
val generatedBridge = layout.buildDirectory.dir("generated/bridge")
val prepareApp by tasks.registering(Exec::class) {
    workingDir(shellRoot)
    commandLine("python3", "tools/app/prepare.py")
}
val copyBridge by tasks.registering(Sync::class) {
    from(shellRoot.resolve("android/runtime-probe/src/main/java/dev/stshell/probe/NativeNode.kt"))
    into(generatedBridge)
}
android {
    namespace = "dev.stshell.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "dev.stshell.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-foreground-experiment"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DNODE_ARTIFACT_DIR=${runtimeDir.absolutePath}", "-DANDROID_STL=c++_shared") } }
    }
    sourceSets["main"].assets.srcDir(staged.resolve("assets"))
    sourceSets["main"].java.srcDir(generatedBridge)
    testOptions.unitTests.all { it.systemProperty("st.app.assets", staged.resolve("assets").absolutePath) }
    externalNativeBuild {
        cmake {
            // Reuse the exact M1 bridge without editing or copying its C++.
            path = shellRoot.resolve("android/runtime-probe/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidResources { noCompress += "zip" }
    buildTypes { debug { isDebuggable = true }; release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs.useLegacyPackaging = false; jniLibs.keepDebugSymbols += "**/libnode.so" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.named("preBuild") { dependsOn(prepareApp, copyBridge) }
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(prepareApp)
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
