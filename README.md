# Common for Android

An Android chat client for the Common Network. Ask the network a question and
see which machine answered it. This app does not download models, register a
node, or donate device resources.

## Build

Open this directory in Android Studio, or build the `app` module with Gradle.
The project uses JDK 17 and Android SDK 35.

## Network access

The app uses the Common Network gateway by default and sends OpenAI-compatible
streaming chat requests to `/v1/chat/completions`. Change the gateway in Settings
to use another compatible gateway. If a gateway requires contributor access,
you can provide an existing node token in Settings; the token is held in memory
only and is sent only to that gateway. This app does not create node tokens or
join the network as a contributor.

Questions and conversation context are sent to the selected gateway to get an
answer. Do not send information you do not want shared with the gateway and the
machine answering the request.
