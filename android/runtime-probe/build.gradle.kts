plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val shellRoot = rootProject.projectDir.parentFile
val runtimeDir = shellRoot.resolve("build/runtime")
val generatedAssets = layout.buildDirectory.dir("generated/probe-assets")
val probeManifestFile = layout.buildDirectory.file("generated/probe-metadata/probe-manifest.json")

val verifyRuntime by tasks.registering(Exec::class) {
    workingDir(shellRoot)
    commandLine("python3", "tools/verify_runtime_artifacts.py")
    // Intentionally always run: a stale/partial/wrong-ABI libnode must fail closed.
}
val prepareProbeManifest by tasks.registering(Exec::class) {
    dependsOn(verifyRuntime)
    workingDir(shellRoot)
    commandLine("python3", "tools/prepare_probe_manifest.py", "--output", probeManifestFile.get().asFile.absolutePath)
}
val prepareProbeAssets by tasks.registering(Sync::class) {
    dependsOn(prepareProbeManifest)
    from(probeManifestFile)
    from(shellRoot.resolve("runtime/probe")) { into("probe") }
    from(runtimeDir.resolve("manifest.json")) { rename { "runtime-manifest.json" } }
    from(shellRoot.resolve("LICENSE")) { into("licenses"); rename { "AGPL-3.0.txt" } }
    from(shellRoot.resolve("licenses")) { into("licenses") }
    from(runtimeDir.resolve("licenses")) { into("licenses/runtime") }
    into(generatedAssets)
}
android {
    namespace = "dev.stshell.probe"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "dev.stshell.probe" // Diagnostic identity, not final product branding.
        minSdk = 34
        targetSdk = 36
        versionCode = 2
        versionName = "0.2-m1-selftest"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DNODE_ARTIFACT_DIR=${runtimeDir.absolutePath}", "-DANDROID_STL=c++_shared") }
        }
    }
    sourceSets["main"].assets.srcDir(generatedAssets)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        debug { isDebuggable = true }
        release { isMinifyEnabled = false } // This milestone is not a release app.
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = false
        // libnode was already stripped and hashed by build_node.py.
        jniLibs.keepDebugSymbols += "**/libnode.so"
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.named("preBuild") { dependsOn(prepareProbeAssets) }
// CMake may be scheduled before preBuild by IDE/model tasks.
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(verifyRuntime)
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
