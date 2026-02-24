# Compose GraalVM

A demo of Compose Desktop compiled to a GraalVM native image on macOS, Windows and Linux.

## Prerequisites

### JDK for development

Any JDK 21+ works for running the app on the JVM (`./gradlew :composeApp:run`).

### Liberica NIK Full for native image

Building a native image **requires [Liberica NIK Full](https://bell-sw.com/liberica-native-image-kit/)** (Native Image Kit). Oracle GraalVM and standard Liberica NIK do **not** include the AWT/Swing modules needed by Compose Desktop.

Download links (JDK 25):

| Platform | Download |
|---|---|
| macOS aarch64 | [bellsoft-liberica-vm-full-openjdk25-macos-aarch64.tar.gz](https://download.bell-sw.com/vm/25.0.2/bellsoft-liberica-vm-full-openjdk25.0.2+13-25.0.2+1-macos-aarch64.tar.gz) |
| Windows x64 | [bellsoft-liberica-vm-full-openjdk25-windows-amd64.zip](https://download.bell-sw.com/vm/25.0.2/bellsoft-liberica-vm-full-openjdk25.0.2+13-25.0.2+1-windows-amd64.zip) |
| Linux x64 | [bellsoft-liberica-vm-full-openjdk25-linux-amd64.tar.gz](https://download.bell-sw.com/vm/25.0.2/bellsoft-liberica-vm-full-openjdk25.0.2+13-25.0.2+1-linux-amd64.tar.gz) |

Set `JAVA_HOME` to the extracted directory before building the native image.


### Linux dependencies

Building on Linux requires `patchelf` to set the RPATH on the native binary:

```bash
sudo apt install patchelf
```

## Build and Run

### Development (JVM)

```bash
./gradlew :composeApp:run
```

### Native image

Make sure `JAVA_HOME` points to Liberica NIK Full, then:

```bash
./gradlew :composeApp:packageNative
```

Output per platform:

| Platform | Output path |
|---|---|
| macOS | `composeApp/build/native/ComposeGraalVM.app` |
| Windows | `composeApp/build/native/compose-graal-vm/` |
| Linux | `composeApp/build/native/compose-graal-vm/` |

### macOS security warning ("damaged")

Binaries downloaded from the internet receive macOS's quarantine attribute. Because the app is not notarized with an Apple Developer certificate, Gatekeeper will refuse to open it and report it as "damaged".

Remove the quarantine attribute after extracting the zip:

```bash
xattr -cr ComposeGraalVM.app
```

Then double-click the app normally. This is expected behaviour for unsigned apps distributed outside the Mac App Store.

### Collect reflection metadata (tracing agent)

```bash
./gradlew :composeApp:runWithAgent
```

Interact with the app, then close it. Config files are written to the platform-specific `resources-{macos,windows,linux}/META-INF/native-image/` directory.
