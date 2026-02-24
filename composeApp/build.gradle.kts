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
            )
            resources.autodetect()
        }
    }
}

// Copy AWT dylibs and add rpath after native compilation
tasks.register<Copy>("copyAwtDylibs") {
    description = "Copy AWT dylibs next to the native image"
    group = "build"
    dependsOn("nativeCompile")

    from("${javaHomeDir.get()}/lib") {
        include(
            "libawt.dylib", "libawt_lwawt.dylib", "libfontmanager.dylib",
            "libfreetype.dylib", "libjava.dylib", "libjavajpeg.dylib",
            "libjawt.dylib", "liblcms.dylib", "libmlib_image.dylib",
            "libosxapp.dylib", "libosxui.dylib", "libsplashscreen.dylib",
        )
    }
    from("${javaHomeDir.get()}/lib/server") {
        include("libjvm.dylib")
    }
    into(layout.buildDirectory.dir("native/nativeCompile"))
}

tasks.register<Copy>("copyJawtToLib") {
    description = "Copy libjawt.dylib to lib/ subdir for Skiko"
    group = "build"
    dependsOn("nativeCompile")

    from("${javaHomeDir.get()}/lib") {
        include("libjawt.dylib")
    }
    into(layout.buildDirectory.dir("native/nativeCompile/lib"))
}

tasks.register<Exec>("fixRpath") {
    description = "Add @executable_path rpath to native image"
    group = "build"
    dependsOn("nativeCompile")

    val binary = layout.buildDirectory.file("native/nativeCompile/compose-graal-vm")
    commandLine("install_name_tool", "-add_rpath", "@executable_path/.", binary.get().asFile.absolutePath)
    isIgnoreExitValue = true
}

tasks.register("packageNative") {
    description = "Build native image and package with required dylibs"
    group = "build"
    dependsOn("copyAwtDylibs", "copyJawtToLib", "fixRpath")
}
