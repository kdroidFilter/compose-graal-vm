plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.graalvmNative)
}

val isMac = org.gradle.internal.os.OperatingSystem.current().isMacOsX
val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
val isLinux = org.gradle.internal.os.OperatingSystem.current().isLinux

sourceSets {
    main {
        resources.srcDir(
            when {
                isMac -> "src/main/resources-macos"
                isWindows -> "src/main/resources-windows"
                isLinux -> "src/main/resources-linux"
                else -> throw GradleException("Unsupported OS")
            }
        )
    }
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

compose.desktop {
    application {
        mainClass = "io.github.kdroidfilter.compose_graal_vm.MainKt"
        nativeDistributions {
            modules("jdk.accessibility")
        }
    }
}

val javaHomeDir = provider { org.gradle.internal.jvm.Jvm.current().javaHome.absolutePath }

val nativeImageConfigDir = layout.projectDirectory.dir(
    when {
        isMac -> "src/main/resources-macos/META-INF/native-image"
        isWindows -> "src/main/resources-windows/META-INF/native-image"
        isLinux -> "src/main/resources-linux/META-INF/native-image"
        else -> throw GradleException("Unsupported OS")
    }
)

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
            val march = providers.gradleProperty("nativeMarch").getOrElse("native")
            buildArgs.addAll(
                "-march=$march",
                "-H:+AddAllCharsets",
                "-Djava.awt.headless=false",
                "-Os",
                "-H:-IncludeMethodData",
            )
            if (isMac) {
                buildArgs.add("-H:NativeLinkerOption=${layout.buildDirectory.asFile.get()}/cursor_stub.o")
            }
            if (isWindows) {
                buildArgs.addAll(
                    "-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS",
                    "-H:NativeLinkerOption=/ENTRY:mainCRTStartup",
                )
            }
            resources.autodetect()
        }
    }
}

// ── macOS: compile C stubs ──

if (isMac) {
    tasks.register<Exec>("compileStubs") {
        description = "Compile C stubs for symbols referenced by AWT flat-namespace dylibs"
        group = "build"

        val src = layout.projectDirectory.file("src/main/c/cursor_stub.c")
        val out = layout.buildDirectory.file("cursor_stub.o")

        inputs.file(src)
        outputs.file(out)

        commandLine("clang", "-c", src.asFile.absolutePath, "-o", out.get().asFile.absolutePath)
    }

    tasks.named("nativeCompile") {
        dependsOn("compileStubs")
    }
}

// ── macOS packaging: .app bundle ──

val appBundleDir = layout.buildDirectory.dir("native/ComposeGraalVM.app/Contents")

if (isMac) {
    tasks.register<Delete>("cleanAppBundle") {
        description = "Remove stale .app bundle before rebuilding to avoid leftover strip temp files"
        group = "build"
        mustRunAfter("nativeCompile")
        delete(layout.buildDirectory.dir("native/ComposeGraalVM.app"))
    }

    tasks.register<Copy>("copyBinaryToApp") {
        description = "Copy native binary into .app bundle"
        group = "build"
        dependsOn("nativeCompile", "cleanAppBundle")

        from(layout.buildDirectory.file("native/nativeCompile/compose-graal-vm"))
        into(appBundleDir.map { it.dir("MacOS") })
    }

    tasks.register<Copy>("copyAwtDylibs") {
        description = "Copy AWT dylibs into .app bundle"
        group = "build"
        dependsOn("nativeCompile", "cleanAppBundle")

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
        dependsOn("nativeCompile", "cleanAppBundle")

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

    tasks.register<Exec>("codesignBundle") {
        description = "Ad-hoc sign the entire .app bundle so Gatekeeper shows a consistent signature"
        group = "build"
        dependsOn("codesignDylibs", "copyBinaryToApp", "fixRpath", "copyInfoPlist")

        val bundleDir = layout.buildDirectory.dir("native/ComposeGraalVM.app")
        commandLine("codesign", "--force", "--deep", "--sign", "-", bundleDir.get().asFile.absolutePath)
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
        dependsOn("cleanAppBundle")

        from(layout.projectDirectory.file("src/main/packaging/Info.plist"))
        into(appBundleDir)
    }

    tasks.register("packageNative") {
        description = "Build native image and package as macOS .app bundle"
        group = "build"
        dependsOn("copyBinaryToApp", "copyAwtDylibs", "copyJawtToLib", "stripDylibs", "codesignDylibs", "codesignBundle", "fixRpath", "copyInfoPlist")
    }
}

// ── Windows packaging: flat directory with DLLs ──

if (isWindows) {
    val windowsOutputDir = layout.buildDirectory.dir("native/compose-graal-vm")

    tasks.register<Copy>("copyBinaryToOutput") {
        description = "Copy native binary into output directory"
        group = "build"
        dependsOn("nativeCompile")

        from(layout.buildDirectory.file("native/nativeCompile/compose-graal-vm.exe"))
        into(windowsOutputDir)
    }

    tasks.register<Copy>("copyAwtDlls") {
        description = "Copy AWT DLLs into output directory"
        group = "build"
        dependsOn("nativeCompile")

        from("${javaHomeDir.get()}/bin") {
            include(
                "awt.dll", "java.dll", "javajpeg.dll", "fontmanager.dll",
                "freetype.dll", "lcms.dll", "mlib_image.dll", "splashscreen.dll",
            )
        }
        into(windowsOutputDir)
    }

    tasks.register<Copy>("copyJvmDll") {
        description = "Copy jvm.dll into output directory"
        group = "build"
        dependsOn("nativeCompile")

        from("${javaHomeDir.get()}/bin/server") {
            include("jvm.dll")
        }
        into(windowsOutputDir)
    }

    tasks.register<Copy>("copyJawtToBin") {
        description = "Copy jawt.dll to bin/ subdir for Skiko"
        group = "build"
        dependsOn("nativeCompile")

        from("${javaHomeDir.get()}/bin") {
            include("jawt.dll")
        }
        into(windowsOutputDir.map { it.dir("bin") })
    }

    // Also copy jawt.dll into nativeCompile/bin/ so the exe works directly from the build output
    tasks.register<Copy>("copyJawtToNativeCompileBin") {
        description = "Copy jawt.dll to nativeCompile/bin/ for Skiko"
        group = "build"
        dependsOn("nativeCompile")

        from(layout.buildDirectory.file("native/nativeCompile/jawt.dll"))
        into(layout.buildDirectory.dir("native/nativeCompile/bin"))
    }

    tasks.named("nativeCompile") {
        finalizedBy("copyJawtToNativeCompileBin")
    }

    tasks.register("packageNative") {
        description = "Build native image and package with DLLs"
        group = "build"
        dependsOn("copyBinaryToOutput", "copyAwtDlls", "copyJvmDll", "copyJawtToBin")
    }
}

// ── Linux packaging: flat directory with .so ──

if (isLinux) {
    val linuxOutputDir = layout.buildDirectory.dir("native/compose-graal-vm")

    tasks.register<Copy>("copyBinaryToOutput") {
        description = "Copy native binary into output directory"
        group = "build"
        dependsOn("nativeCompile")

        from(layout.buildDirectory.file("native/nativeCompile/compose-graal-vm"))
        into(linuxOutputDir)
    }

    tasks.register<Copy>("copyAwtSoLibs") {
        description = "Copy AWT .so libs into output directory"
        group = "build"
        dependsOn("nativeCompile")

        from("${javaHomeDir.get()}/lib") {
            include(
                "libawt.so", "libawt_headless.so", "libawt_xawt.so", "libfontmanager.so",
                "libjava.so", "libjavajpeg.so", "libjawt.so", "liblcms.so",
                "libmlib_image.so", "libsplashscreen.so",
            )
        }
        into(linuxOutputDir)
    }

    tasks.register<Copy>("copyJvmSo") {
        description = "Copy libjvm.so into output directory"
        group = "build"
        dependsOn("nativeCompile")

        from("${javaHomeDir.get()}/lib/server") {
            include("libjvm.so")
        }
        into(linuxOutputDir)
    }

    tasks.register<Copy>("copyJawtToLib") {
        description = "Copy libjawt.so to lib/ subdir for Skiko"
        group = "build"
        dependsOn("nativeCompile")

        from("${javaHomeDir.get()}/lib") {
            include("libjawt.so")
        }
        into(linuxOutputDir.map { it.dir("lib") })
    }

    tasks.register<Copy>("copyJawtToNativeCompileLib") {
        description = "Copy libjawt.so to nativeCompile/lib/ for Skiko"
        group = "build"
        dependsOn("nativeCompile")

        from("${javaHomeDir.get()}/lib") {
            include("libjawt.so")
        }
        into(layout.buildDirectory.dir("native/nativeCompile/lib"))
    }

    tasks.named("nativeCompile") {
        finalizedBy("copyJawtToNativeCompileLib")
    }

    tasks.register<Exec>("fixRpath") {
        description = "Set RPATH to \$ORIGIN so the binary finds .so libs next to it"
        group = "build"
        dependsOn("copyBinaryToOutput")

        val binary = linuxOutputDir.map { it.file("compose-graal-vm") }
        commandLine("patchelf", "--set-rpath", "\$ORIGIN", binary.get().asFile.absolutePath)
    }

    tasks.register<Exec>("stripSoLibs") {
        description = "Strip debug symbols from .so libs to reduce size"
        group = "build"
        dependsOn("copyAwtSoLibs", "copyJvmSo")

        val outputDir = linuxOutputDir.get().asFile.absolutePath
        commandLine("bash", "-c", "strip --strip-debug ${outputDir}/*.so")
    }

    tasks.register("packageNative") {
        description = "Build native image and package with .so libs"
        group = "build"
        dependsOn("copyBinaryToOutput", "copyAwtSoLibs", "copyJvmSo", "copyJawtToLib", "fixRpath", "stripSoLibs")
    }
}
