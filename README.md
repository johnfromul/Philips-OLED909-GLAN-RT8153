# Philips OLED909 GLAN RT8153

**Root-free USB Gigabit Ethernet for Android TV using a Realtek RTL8153 adapter.**

This app was developed and tested on a **Philips 65OLED909 running Android 14**. It provides an alternative USB Ethernet path when the TV firmware does not include a working RTL8153 kernel driver. Despite the requested repository name `RT8153`, the supported chipset is **RTL8153**.

**[Download the latest release](https://github.com/johnfromul/Philips-OLED909-GLAN-RT8153/releases/latest)** · [Build instructions](BUILDING.md) · [Setup and recovery](docs/SETUP.md) · [Testing](docs/TESTING.md)

## How it works

```text
Android applications ↔ Android VpnService / TUN ↔ userspace ECM bridge ↔ RTL8153 USB adapter ↔ Ethernet
```

The app uses Android's USB Host API to access the adapter and creates a local IPv4 connection through `VpnService`. The native C++ data path uses asynchronous USB requests. It does **not** install a kernel driver, modify the TV firmware, require root, or send traffic to a remote VPN provider. The local VPN interface itself does not encrypt Ethernet traffic.

## Features

- RTL8153 CDC ECM device support for USB ID **`0bda:8153`**.
- ARMv7 and ARM64 native libraries; Android 8.0 / API 26 or newer.
- Automatic DHCP or manual IPv4 configuration, including gateway and DNS.
- Local ARP handling, address-conflict checks and DHCP lease renewal.
- Three native transfer threads, 256 asynchronous USB RX requests and 128 TX buffers.
- Foreground service with saved connection intent, reconnect attempts, boot recovery and USB reattach handling.
- **Czech, English, German, Spanish and French**, selected from Android's language configuration. English is the fallback.
- USB diagnostics, connection status, DNS/HTTPS checks and exportable logs.

## Quick start

1. Download and install the signed APK from [Releases](https://github.com/johnfromul/Philips-OLED909-GLAN-RT8153/releases).
2. Connect a compatible RTL8153 adapter and an Ethernet cable.
3. Open the app and approve the Android USB and VPN dialogs.
4. Open **IP settings** and select DHCP or enter a valid manual configuration. Keep **Fast native transfer** enabled.
5. Select **Connect Ethernet**, then **Check internet**.

The connection uses one Android VPN slot. While connected, eligible IPv4 application traffic is routed through USB Ethernet. Wi-Fi may stay connected as a fallback.

For reliable unattended recovery, see [Setup and recovery](docs/SETUP.md). A boot receiver alone cannot restore a USB permission that Android has discarded.

## Tested TV software

| Component | Tested version |
|---|---|
| TV | Philips 65OLED909/12 |
| Operating system | Android 14 |
| Philips firmware | `TPM231WW_R.201.100.146.220` |
| Linux kernel | `5.15.180-android14-11-00042-g766efc243647-dirty` |

These are the versions read from the actual TV during testing, not the latest firmware available for download. Other firmware/kernel combinations have not been verified. See [validation details](docs/TESTING.md).

## Compatibility and limits

- Hardware testing was performed on a Philips 65OLED909. Other Android TVs may work, but have not been verified.
- The adapter must expose a compatible **CDC ECM** configuration. Matching the marketing name or chipset family is not enough.
- RTL8156, RTL8157 and ASIX adapters are not supported by this release.
- IPv4 only. IPv6 is blocked while the local VPN is active. Multicast forwarding is not implemented.
- This is not a system Ethernet driver: it does not turn the USB adapter into a native Android `eth` interface.
- Other VPN apps cannot use the same VPN slot simultaneously.
- Gigabit Ethernet capability does not guarantee gigabit application throughput. USB hardware, CPU load, TUN overhead and the server affect performance.
- Android **Force stop** is respected. The app cannot guarantee recovery against every vendor-specific power-management policy.

The tested internal TV Ethernet path delivered approximately **94 Mb/s**. USB results were substantially higher; one user-reported internet test reached approximately **320 Mb/s**. These are setup-specific observations, not a throughput guarantee. See [testing details](docs/TESTING.md).

## Automatic recovery

When you select **Connect Ethernet**, the app saves the requested connected state. It attempts to reconnect after boot, service recreation or USB reattachment. **Disconnect** disables that saved request, including across a reboot.

On the tested TV, reliable recovery required both:

1. Persistent USB access for the particular adapter and app UID.
2. Android **Always-on VPN** for this app, with **Block connections without VPN / lockdown disabled**.

Those system settings are not embedded in the APK and are not automatically granted on another TV. The app itself has not been given privileged USB-management permissions.

## Repository layout

| Path | Contents |
|---|---|
| `src/` | Java UI, USB control, network configuration and recovery service |
| `native/ecmfast.cpp` | Asynchronous USB/TUN C++ data path |
| `res/` | Android resources and five language sets |
| `AndroidManifest.xml` | App components, permissions and version |
| `build.py` | Portable JDK / Android SDK / NDK build script |
| `tests/` | Java packet tests, resource checks and on-device locale checker |
| `docs/` | English setup and validation documentation |
| `licenses/` | Retained Android SDK/NDK third-party notices |

The release APK is signed with the original project development certificate. The private signing key is **not** published. A locally generated signing key will not produce an in-place update of the official APK.

## Reporting problems

Include the TV model, Android version, adapter USB VID/PID, app version and relevant error messages. Review exported logs before sharing them: they can contain local IP addresses, device paths and hardware information. Do not attach private signing keys or credentials.

This is an independent project and is not affiliated with Philips, TP Vision, Realtek or i-tec.
