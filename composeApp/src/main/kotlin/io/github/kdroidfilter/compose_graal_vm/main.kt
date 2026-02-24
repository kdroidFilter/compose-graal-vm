package io.github.kdroidfilter.compose_graal_vm

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File

private val isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null

fun main() {
    if (isNativeImage) {
        // Force Metal L&F to avoid platform-specific L&F loading native modules unsupported in native image
        System.setProperty("swing.defaultlaf", "javax.swing.plaf.metal.MetalLookAndFeel")

        // Set java.home to the executable's directory so Skiko can find jawt (lib/ on macOS, bin/ on Windows)
        val execDir = File(ProcessHandle.current().info().command().orElse("")).parentFile?.absolutePath ?: "."
        System.setProperty("java.home", execDir)
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "compose_graal_vm",
        ) {
            App()
        }
    }
}
