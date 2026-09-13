# Protopanda Controller

<p align="center">
  <img src="https://github.com/mockthebear/proto-panda/raw/main/doc/logoprotopanda.png" alt="Protopanda logo" width="220">
</p>

**English** | [Português](README.pt-BR.md)

Android controller that works as a Bluetooth Low Energy (BLE) peripheral for
Protopanda platforms. It sends the phone's motion data and the on-screen
button state using the protocol expected by the receiver firmware.

## Releases

[Download the latest release](https://github.com/junglivre/ProtopandaController/releases/latest).

Versioned releases are published on GitHub, but you can also [build the app
yourself](#build-and-run).

## Features

- BLE peripheral for the Protopanda platform.
- Motion controls using the accelerometer and gyroscope.
- D-pad-style touch controls.
- Configurable GATT UUIDs.
- Automatic app shutdown when Bluetooth is turned off.

## Requirements

- Android 5.0 (API level 21) or newer. The app targets Android 17 (API level 37).
- A phone whose Bluetooth chipset supports BLE peripheral and advertising mode.
- An accelerometer and gyroscope for motion controls.
- Android Studio or JDK 17 to build from source.

BLE support alone is not enough: the phone must support advertising in peripheral
mode. Without the motion sensors, the controller can still open but cannot
provide motion input.

## Build and run

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Open the repository in Android Studio to run it on a device. Grant the requested
Bluetooth permissions at first launch.

For a local optimized release build, run `./gradlew assembleRelease`. The
release variant enables R8 code shrinking and resource shrinking. Add a local
`keystore.properties` file only when you need a signed build; Git ignores it.

## Bluetooth behavior

The app starts the BLE peripheral after Bluetooth is enabled and permissions are
granted. Turning Bluetooth off stops the service and terminates the app. The
close button in the upper bar does the same. Android does not allow ordinary
apps to turn off Bluetooth themselves, so the close button leaves the Bluetooth
radio unchanged.

## BLE identity and protocol

The default identity matches Protopanda platforms. In **Settings**, you
can change the service UUID, read/write characteristic UUID, and notification
characteristic UUID. Saving disconnects the current receiver and restarts the
BLE peripheral. The app does not advertise or change the phone Bluetooth name.

| Item | Value |
|---|---|
| Advertised device name | Not included |
| Service UUID | `d4d31337-c4c1-c2c3-b4b3-b2b1a4a3a2a1` |
| Read/write characteristic | `d4d3fafb-c4c1-c2c3-b4b3-b2b1a4a3a2a1` |
| Notification characteristic | `d4d3afaf-c4c1-c2c3-b4b3-b2b1a4a3a2a1` |
| Notification interval | 50 ms |

The payload is 23 bytes: accelerometer, gyroscope, temperature placeholder,
the receiver-assigned controller ID, and eight button states. Motion values are
scaled to match the LSM6DS3 configuration used by the firmware.

## About and credits

Protopanda Controller is an open-source BLE controller built for the Protopanda platform, shaped by the community around the project.

Source code: [junglivre/ProtopandaController](https://github.com/junglivre/ProtopandaController).

- [GooDDu](https://github.com/GooDDu) — first version of the app.
- [mockthebear](https://github.com/mockthebear) — creator of Protopanda and contributor to app improvements.
- [junglivre](https://github.com/junglivre) — app improvements and Google Play release.
