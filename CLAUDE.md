# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MobileOpenClaw is an AI Gateway solution for Android that combines a Flutter app with a proot Ubuntu environment. The standalone APK embeds PRoot binaries to run Ubuntu rootfs without root access, providing:
- In-app terminal and web dashboard
- Node.js 22 + OpenClaw gateway
- Optional tools (Go, Homebrew, OpenSSH)
- Node capabilities (camera, location, sensors)

## Development Commands

### Flutter App
```bash
# Install dependencies
cd flutter_app && flutter pub get

# Analyze Dart code
flutter analyze

# Build release APK (all ABIs)
flutter build apk --release

# Build per-ABI APKs
flutter build apk --release --split-per-abi

# Build App Bundle (for Play Store)
flutter build appbundle --release
```

### Node.js CLI
```bash
# Install dependencies
npm install

# Run tests
npm test

# Lint code
npm run lint

# Fix linting issues
npm run lint:fix
```

### APK Build (Full)
```bash
# Build complete APK with PRoot binaries
./scripts/build-apk.sh
```

## Architecture

### Flutter App (`flutter_app/lib/`)
- **app.dart**: App entry point, theming, and provider initialization
- **main.dart**: Minimal entry point
- **providers/**: State management using Provider pattern
  - `gateway_provider.dart`: Gateway connection state
  - `setup_provider.dart`: Installation progress
  - `node_provider.dart`: Node/WebSocket communication
  - `locale_provider.dart`: Localization settings
- **services/**: Platform integration layer
  - `gateway_service.dart`: Dashboard HTTP/WebSocket communication
  - `node_service.dart`: Node CLI interaction via WebSocket
  - `terminal_service.dart`: FlutterPTY for embedded terminal
  - `bootstrap_service.dart`: Ubuntu rootfs setup
  - `native_bridge.dart`: JNI bridge to PRoot
- **screens/**: UI screens (splash, dashboard, settings, etc.)
- **widgets/**: Reusable UI components
- **l10n/**: Localization (zh-CN, zh-Hant, ja)

### Node.js CLI (`lib/`)
- **index.js**: CLI entry point, command dispatcher
- **installer.js**: proot-distro and Ubuntu installation
- **bionic-bypass.js**: Android Bionic libc compatibility

### Asset Bundling
- `assets/bionic_bypass.js`: Bundled into Flutter APK
- `assets/resolv.conf`: DNS configuration
- `assets/ic_launcher.png`: App icon

## Key Patterns

### Provider Hierarchy
```
LocaleProvider → SetupProvider → GatewayProvider → NodeProvider(Proxy)
```

### Gateway Communication
The Flutter app communicates with the Node.js gateway via:
- HTTP: `http://127.0.0.1:18789` (web dashboard)
- WebSocket: `ws://127.0.0.1:18789/api/ws` (terminal, logs)

### Localization
- Use `context.l10n.key` for localized strings
- Supported locales: zh-CN, zh-Hant (TW/HK/MO), ja-JP
- Auto-detected from device locale

### Color Palette
Defined in `AppColors` class (dark/light themes with accent 0xFFDC2626).

## Build Pipeline

GitHub Actions workflow (`.github/workflows/flutter-build.yml`):
1. Fetch PRoot binaries → `scripts/fetch-proot-binaries.sh`
2. Build Flutter APK/AAB
3. Comment on PR with download links
4. Auto-publish release on `main` branch push

## Version Management

- Node.js package: `package.json` version
- Flutter app: `flutter_app/pubspec.yaml` version (format: `x.y.z+build`)
- Update both when releasing

## File Paths to Know

- `flutter_app/android/app/src/main/jniLibs/`: Native PRoot libraries
- `flutter_app/assets/`: Bundled assets
- `scripts/build_release.py`: Release preparation script