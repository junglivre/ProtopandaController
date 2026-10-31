# Changelog

All notable changes to this project are documented in this file.

## [1.1.0] - 2026-10-31

### Added

- Optional read-only BLE GATT clock characteristic for ProtoPanda. It provides
  Unix epoch seconds and the local UTC offset when the receiver reads it.

### Changed

- Bumped the Android app version to `1.1.0`.

## [1.0.0] - 2026-09-29

### Added

- Battery optimization exemption request, alongside the Bluetooth permission
  flow. The system prompt only opens automatically on the first launch. From
  the second launch onward, if still not exempted, the app shows a warning
  dialog instead of redirecting to the system prompt again.

### Changed

- Simplified the in-app version label to `v1.0.0`.

## [0.1.0] - 2026-09-13

### Added

- Initial public release of Protopanda Controller.
- BLE peripheral controller compatible with the Protopanda platform.
- Android adaptive launcher icons and Play Store icon asset.
- Configurable GATT UUIDs, with a restart after saving.
- Settings and About screen with repository link.
- Portuguese (Brazil) README.
- Credits page with GitHub profile links and avatars.
