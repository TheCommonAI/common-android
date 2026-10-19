# Common Mobile

Common Mobile is a shared Kotlin chat client for Android and iOS. Its interface,
chat state, gateway validation, and streaming network client live in the
Kotlin Multiplatform `shared` module; the platform apps provide only startup and
settings persistence.

The app sends OpenAI-compatible streaming chat requests to the Common Network
gateway at `/v1/chat/completions`. You can change the gateway in Settings. If a
gateway requires contributor access, you can provide an existing node token;
the token is held in memory only and sent only to that gateway. The app does
not download models, register a node, or donate device resources.

Questions and conversation context are sent to the selected gateway and the
machine answering the request. Do not send information you do not want shared
with the gateway and that machine.

## Build Android

Open the project in Android Studio or run `./gradlew :app:assembleDebug` (use
`gradlew.bat` on Windows). It uses
JDK 17 and Android SDK 35.

## Build iOS

On macOS with Xcode installed, open `iosApp/iosApp.xcodeproj` and build the
`iosApp` scheme for an iOS simulator or device. The Xcode build invokes the
shared module's Gradle framework task before linking.

The shared module supports iOS device (`iosArm64`), Apple Silicon simulator
(`iosSimulatorArm64`), and Intel simulator (`iosX64`) targets.

## Tests

Run `./gradlew :shared:allTests` (or `gradlew.bat :shared:allTests` on Windows)
to execute the shared gateway-validation tests.
