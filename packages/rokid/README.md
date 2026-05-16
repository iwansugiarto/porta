# Porta Rokid Bridge

Android companion app that bridges [Rokid AI Glasses](https://global.rokid.com/) with your local [Porta](../../README.md) proxy, giving you a wearable AI interface to [Antigravity](https://antigravity.google/).

## Architecture

```
Rokid Glasses ←→ [BT/CXR] ←→ Phone Bridge App ←→ [WebSocket] ←→ Porta Proxy ←→ Antigravity LS
  Camera                         AI Bridge                           :3170
  Mic + Speaker                  STT/TTS
  Display (HUD)                  CXR Manager
```

## Features

- **Voice input** — Long-press temple to speak, STT transcription sent to Antigravity
- **HUD streaming** — AI responses stream token-by-token to the glasses display
- **Camera capture** — Take photos through glasses camera for visual context
- **Approval handling** — Approve/reject commands via glasses gestures
- **Session management** — Switch between Porta conversations
- **Wake-on-message** — Display wakes automatically when responses arrive

## Prerequisites

- Android Studio Ladybug (2024.2) or later
- JDK 21
- Android SDK API 35
- Rokid Developer credentials (optional for emulator testing)

## Quick Start

```bash
# 1. Open in Android Studio
# File → Open → select packages/rokid/

# 2. Configure connection
# Edit app/src/main/res/values/config.xml with your Porta proxy address

# 3. Build & Install
./gradlew :app:installDebug

# 4. For glasses app (requires Rokid device)
./gradlew :glasses:installDebug
```

## Project Structure

```
packages/rokid/
├── app/                  # 📱 Phone bridge app (main hub)
│   └── src/main/java/
│       └── com/porta/rokid/
│           ├── MainActivity.kt
│           ├── data/              # Data layer
│           │   └── PortaClient.kt # WebSocket client for Porta proxy
│           ├── service/           # Services
│           │   ├── bridge/        # CXR bridge manager
│           │   └── voice/         # STT/TTS services
│           ├── ui/                # Compose UI
│           │   ├── screens/       # App screens
│           │   └── theme/         # Material theme
│           └── viewmodel/         # ViewModels
├── glasses/              # 👓 Glasses HUD app
│   └── src/main/java/
│       └── com/porta/rokid/glasses/
├── shared/               # 📦 Shared protocol library
│   └── src/main/java/
│       └── com/porta/rokid/shared/
│           └── protocol/          # Message types, connection state
└── gradle/               # Gradle config
```

## Development Without Glasses

You can develop and test the bridge app without physical Rokid glasses:

1. The app includes a **debug mode** where the Porta WebSocket connection works standalone
2. Voice input falls back to the phone's built-in microphone
3. AI responses display in the phone app UI instead of the glasses HUD

## Configuration

Set your Porta proxy address in the app settings or via `config.xml`:

```xml
<string name="porta_default_host">192.168.1.23</string>
<integer name="porta_default_port">3170</integer>
```

## License

[MIT](../../LICENSE) — same as the main Porta project.
