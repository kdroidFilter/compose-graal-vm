plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.graalvmNative)
    application
}

application {
    mainClass.set("io.github.kdroidfilter.compose_graal_vm.MainKt")
}

dependencies {
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    testImplementation(libs.kotlin.test)
}

val javaHomeDir = provider { org.gradle.internal.jvm.Jvm.current().javaHome.absolutePath }

val nativeImageConfigDir = layout.projectDirectory.dir("src/main/resources/META-INF/native-image")
val appBundleDir = layout.buildDirectory.dir("native/ComposeGraalVM.app/Contents")

tasks.register<JavaExec>("runWithAgent") {
    description = "Run the app with the native-image-agent to collect reflection metadata"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.kdroidfilter.compose_graal_vm.MainKt")
    jvmArgs(
        "-agentlib:native-image-agent=config-output-dir=${nativeImageConfigDir.asFile.absolutePath}"
    )
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.BELLSOFT)
    })
}

graalvmNative {
    toolchainDetection.set(false)
    binaries {
        named("main") {
            mainClass.set("io.github.kdroidfilter.compose_graal_vm.MainKt")
            imageName.set("compose-graal-vm")
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                vendor.set(JvmVendorSpec.BELLSOFT)
            })
            buildArgs.addAll(
                "-march=native",
                "-H:+AddAllCharsets",
                "-Djava.awt.headless=false",
                "-Os",
                "-H:-IncludeMethodData",
            )
            resources.autodetect()
        }
    }
}

// Package native image as a macOS .app bundle
tasks.register<Copy>("copyBinaryToApp") {
    description = "Copy native binary into .app bundle"
    group = "build"
    dependsOn("nativeCompile")

    from(layout.buildDirectory.file("native/nativeCompile/compose-graal-vm"))
    into(appBundleDir.map { it.dir("MacOS") })
}

tasks.register<Copy>("copyAwtDylibs") {
    description = "Copy AWT dylibs into .app bundle"
    group = "build"
    dependsOn("nativeCompile")

    from("${javaHomeDir.get()}/lib") {
        include(
            "libawt.dylib", "libawt_lwawt.dylib", "libfontmanager.dylib",
            "libfreetype.dylib", "libjava.dylib", "libjavajpeg.dylib",
            "libjawt.dylib", "liblcms.dylib", "libmlib_image.dylib",
            "libosxapp.dylib", "libsplashscreen.dylib",
        )
    }
    from("${javaHomeDir.get()}/lib/server") {
        include("libjvm.dylib")
    }
    into(appBundleDir.map { it.dir("MacOS") })
}

tasks.register<Copy>("copyJawtToLib") {
    description = "Copy libjawt.dylib to lib/ subdir for Skiko"
    group = "build"
    dependsOn("nativeCompile")

    from("${javaHomeDir.get()}/lib") {
        include("libjawt.dylib")
    }
    into(appBundleDir.map { it.dir("MacOS/lib") })
}

tasks.register<Exec>("stripDylibs") {
    description = "Strip debug symbols from dylibs to reduce size"
    group = "build"
    dependsOn("copyAwtDylibs")

    val macosDir = appBundleDir.map { it.dir("MacOS") }
    commandLine("bash", "-c", "strip -x ${macosDir.get().asFile.absolutePath}/*.dylib")
}

tasks.register<Exec>("codesignDylibs") {
    description = "Re-sign dylibs after stripping (ad-hoc)"
    group = "build"
    dependsOn("stripDylibs")

    val macosDir = appBundleDir.map { it.dir("MacOS") }
    commandLine("bash", "-c", "codesign --force --sign - ${macosDir.get().asFile.absolutePath}/*.dylib")
}

tasks.register<Exec>("fixRpath") {
    description = "Add @executable_path rpath to native image"
    group = "build"
    dependsOn("copyBinaryToApp")

    val binary = appBundleDir.map { it.file("MacOS/compose-graal-vm") }
    commandLine("install_name_tool", "-add_rpath", "@executable_path/.", binary.get().asFile.absolutePath)
    isIgnoreExitValue = true
}

tasks.register<Copy>("copyInfoPlist") {
    description = "Copy Info.plist into .app bundle"
    group = "build"

    from(layout.projectDirectory.file("src/main/packaging/Info.plist"))
    into(appBundleDir)
}

tasks.register("packageNative") {
    description = "Build native image and package as macOS .app bundle"
    group = "build"
    dependsOn("copyBinaryToApp", "copyAwtDylibs", "copyJawtToLib", "stripDylibs", "codesignDylibs", "fixRpath", "copyInfoPlist")
}
