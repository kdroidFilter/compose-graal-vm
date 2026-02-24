# Compose GraalVM

Jetpack Compose Desktop running as a GraalVM native image on macOS.

## Prerequisites

### JDK for development

Any JDK 21+ works for running the app on the JVM.

### Liberica NIK Full for native image

Building a native image **requires [Liberica NIK Full](https://bell-sw.com/liberica-native-image-kit/)** (Native Image Kit). Oracle GraalVM and standard Liberica NIK do **not** work because AWT on macOS needs the full JDK modules that only NIK Full includes.

Install it and set it as your `JAVA_HOME` (or configure it in your IDE/Gradle JVM settings) before building the native image.

> On macOS with Homebrew:
> ```bash
> brew install --cask liberica-native-image-kit-full
> ```

## Build and Run

### Development (JVM)

```bash
./gradlew :composeApp:run
```

### Native image

Make sure Gradle runs with Liberica NIK Full, then:

```bash
./gradlew :composeApp:packageNative
```

The output is a macOS `.app` bundle at:

```
composeApp/build/native/ComposeGraalVM.app
```

Launch it with:

```bash
open composeApp/build/native/ComposeGraalVM.app
```

### Collect reflection metadata (tracing agent)

```bash
./gradlew :composeApp:runWithAgent
```

Interact with the app, then close it. Config files are written to `composeApp/src/main/resources/META-INF/native-image/`.
