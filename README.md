# Common Mobile

Common Mobile is a shared Kotlin chat client for Android and iOS. Its interface,
chat state, gateway validation, and streaming network client live in the
Kotlin Multiplatform `shared` module. Android also has an explicit local-model
contribution flow backed by a pinned llama.cpp runtime.

Ordinary chat sends OpenAI-compatible streaming requests to the Common Network
gateway at `/v1/chat/completions`. You can change the gateway in Settings. An
existing chat node token is held in memory only and sent only to that gateway.

Android users can separately opt in under Settings → Contribute this Android
device. The app asks the gateway to assign an eligible catalogue model, displays
the model and runtime requirements for review, downloads only its pinned GGUF
source, checks its SHA-256 digest, loads it locally, and registers an outbound
`android_ws` worker. Contributor node credentials are encrypted with Android
Keystore. The app opens no inbound port and sends the token only in the WebSocket
Authorization header. Stop contribution deregisters the node.

Contribution runs only while Common Mobile is active. Android may suspend or
terminate the app; the UI does not promise unrestricted background service. On
pause, the app closes the worker connection and releases the model from memory.
When the app returns to the foreground, it reloads the pinned model and
reconnects. iOS contribution is not implemented by this app.

For ordinary chat, questions and conversation context are sent to the selected
gateway and the machine answering the request. When contributing, routed
requests are sent by the gateway to this device's selected local model. Do not
send information you do not want shared with the gateway or an answering
contributor.

## Build Android

Open the project in Android Studio or run `./gradlew :app:assembleDebug` (use
`gradlew.bat` on Windows). It uses JDK 17, Android SDK 35, NDK 27.2.12479018,
and CMake 3.22.1. The first native build fetches the pinned llama.cpp b11555
source and builds CPU inference for `arm64-v8a` and `x86_64`; allow network
access and sufficient disk space for the native build.

The runtime accepts the catalogue's Bonsai 2 `Q2_0` packing only when the
model's chat template exposes the non-thinking switch, and Bonsai 8B only in
`Q2_0_g64`. Do not loosen those checks or advertise other model capabilities
without validating the runtime. llama.cpp's MIT license is included at
`app/src/main/assets/licenses/llama.cpp.txt`.

The gateway must deploy the mobile-worker protocol and publish model format,
platform eligibility, and runtime requirements in its catalogue and assignment
responses. If those fields are absent, Android deliberately refuses to
register.

## Build iOS

On macOS with Xcode installed, open `iosApp/iosApp.xcodeproj` and build the
`iosApp` scheme for an iOS simulator or device. The Xcode build invokes the
shared module's Gradle framework task before linking.

The shared module supports iOS device (`iosArm64`), Apple Silicon simulator
(`iosSimulatorArm64`), and Intel simulator (`iosX64`) targets.

## Tests

Run `./gradlew :shared:allTests` (or `gradlew.bat :shared:allTests` on Windows)
to execute the shared gateway-validation and contributor model-policy tests.
The native runtime and the complete worker path still require Android-device
validation with the catalogue's actual model files before release.
