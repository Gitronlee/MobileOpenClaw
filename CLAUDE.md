# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MobileOpenClaw is an Android AI Gateway application that runs OpenClaw (Node.js-based AI Gateway) inside a proot Ubuntu environment on non-rooted Android devices. The app is built with Flutter for the UI and integrates native Android services via Kotlin.

## Build Commands

```bash
# Build APK (simple)
cd /root/githubCode/MobileOpenClaw && bash scripts/build-apk.sh

# Build with release script (recommended)
python3 scripts/build_release.py --non-interactive

# Build specific artifacts
python3 scripts/build_release.py --version 1.8.6 --skip-split-apk --skip-aab

# Lint and test
cd flutter_app && flutter analyze
```

## Architecture

### Flutter App Structure (`flutter_app/`)

```
lib/
├── main.dart                    # App entry point
├── app.dart                     # Theme configuration, providers, routing
├── constants.dart               # App constants (channels, URLs)
├── models/                      # Data models (AI providers, gateway state, node state)
├── providers/                   # ChangeNotifier providers (locale, setup, gateway, node)
├── screens/                     # UI screens (dashboard, logs, settings, onboarding)
├── services/                    # Business logic services
│   ├── native_bridge.dart       # Flutter-Android MethodChannel/EventChannel bridge
│   ├── gateway_service.dart     # Gateway lifecycle and health checks
│   ├── terminal_service.dart    # PTY terminal sessions
│   ├── node_service.dart        # Node process management
│   ├── preferences_service.dart # SharedPreferences wrapper
│   ├── bootstrap_service.dart   # Ubuntu rootfs setup
│   └── capabilities/            # Device capability handlers (camera, location, sensors)
└── widgets/                     # Reusable UI components
```

### Native Layer (`flutter_app/android/`)

Kotlin services that run as Android foreground services:
- `MainActivity.kt` - MethodChannel/EventChannel hub, sensor access
- `GatewayService.kt` - Runs Node.js gateway process
- `NodeForegroundService.kt` - Background node process
- `TerminalSessionService.kt` - PTY terminal via flutter_pty
- `SshForegroundService.kt` - SSH server
- `SetupService.kt` - Ubuntu rootfs extraction
- `BootstrapManager.kt` - Rootfs, PRoot, DNS, package management
- `ProcessManager.kt` - PRoot command execution

### Node.js Backend (`lib/`)

Used by the CLI variant (Termux):
- `index.js` - Main CLI entry (`openclawx` command)
- `installer.js` - proot-distro and Ubuntu setup
- `bionic-bypass.js` - Android Bionic libc workaround

## Communication Patterns

**Flutter → Android:**
- `NativeBridge` class uses `MethodChannel` for single-shot calls
- `EventChannel` streams gateway logs from `GatewayService.logSink`

**State Management:**
- `Provider` package with `ChangeNotifier`
- `GatewayProvider` → `NodeProxyProvider` dependency chain
- Stream-based state updates via `stateStream`

## Key Technical Details

- **PRoot**: Binary distributed in `android/app/src/main/jniLibs/` (arm64-v8a, armeabi-v7a, x86_64)
- **Node.js**: Runs inside Ubuntu proot with Bionic bypass (os.networkInterfaces patch)
- **Dashboard**: Web UI at `http://127.0.0.1:18789`
- **i18n**: `l10n/app_localizations.dart` with ZH-CN, ZH-TW, JA support
- **Assets**: Bionic bypass script and DNS config bundled in `assets/`

## Release Process

1. Update version in `flutter_app/pubspec.yaml` (`version: x.x.x+N`)
2. Run `python3 scripts/build_release.py --non-interactive`
3. Outputs go to `release/v{version}/` (APK, AAB, release notes)
4. Update `CHANGELOG.md`