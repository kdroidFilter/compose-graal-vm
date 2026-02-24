# compose-graal-vm

## Project Overview

This project runs Jetpack Compose Desktop as a GraalVM native image. It's a Kotlin JVM application using Compose Desktop, compiled to a native macOS binary using Liberica NIK (Native Image Kit) Full.

## Java Versions

All builds use `JAVA_HOME` from the host environment. For native image builds, `JAVA_HOME` must point to Liberica NIK Full.

## Build and Run

### Development (JVM)
```bash
./gradlew :composeApp:run
```

### Native Image
```bash
./gradlew :composeApp:packageNative
```

Then run:
```bash
./composeApp/build/native/nativeCompile/compose-graal-vm
```

### Collect Reflection Metadata (tracing agent)
```bash
./gradlew :composeApp:runWithAgent
```
Interact with the app then close it. Config files are written to `src/main/resources/META-INF/native-image/`.

## Code Style

- Follow Kotlin conventions and idiomatic style
- Comments must be in English
- Keep code clean and pragmatic—avoid over-engineering
- Use proper architecture but prioritize simplicity

## Key Directories

- `/composeApp/src/main/kotlin` — Application source code
- `/composeApp/src/main/resources/META-INF/native-image` — GraalVM agent-generated configs

## GraalVM Considerations

- AWT requires Liberica NIK Full (not Oracle GraalVM) on macOS
- AWT dylibs must be shipped alongside the native binary (automated by `packageNative`)
- Metal L&F is forced via `swing.defaultlaf` to avoid AquaLookAndFeel's osxui loading issue
- `java.home` is set to executable dir at runtime for Skiko's libjawt lookup

## Git Conventions

- No `Co-Authored-By` or AI attribution in commits/PRs
- Keep commit messages clear and focused on the "why"
