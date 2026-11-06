# Philips OLED909 GLAN RT8153

**USB Gigabit Ethernet for Philips OLED909 with a Realtek RTL8153 adapter: a root-free VPN app or a native Ethernet driver for rooted TVs.**

This project was developed and tested on a **Philips 65OLED909 running Android 14**. It provides two ways to use USB Ethernet when the TV firmware does not include a working RTL8153 kernel driver. Despite the requested repository name `RT8153`, the supported chipset is **RTL8153**.

**[Download the root-free app v0.6.1](https://github.com/johnfromul/Philips-OLED909-GLAN-RT8153/releases/tag/v0.6.1)** · [Native Magisk releases](magisk/README.md) · [Build instructions](BUILDING.md) · [Setup and recovery](docs/SETUP.md) · [Testing](docs/TESTING.md)

## Choose a connection method and root requirements

- **Root-free VPN app:** the app and its USB Ethernet connection do not require root. Android USB and VPN approvals are required; optional persistent USB permission setup may require elevated privileges. See [setup](docs/SETUP.md).
- **Native Ethernet with Magisk:** root and Magisk are required to install and automatically load a kernel driver. Apps then use Android Ethernet without needing root. Versions 1.0.2 and 1.0.3 target different exact firmware/kernel combinations. See [Magisk compatibility, installation and validation](magisk/README.md).

## Native Ethernet with Magisk (root required)

The Magisk module loads the `r8152` kernel driver for the RTL8153 adapter after Android starts. Android recognizes the adapter as a real Ethernet interface and manages its network configuration. This method does not occupy the Android VPN slot. Magisk enables Wi-Fi as a fallback; a change between networks does not preserve existing connections.

**Root and Magisk are required** to install, enable or disable the module and load the driver. The root diagnostic commands below also require `su`. Once the driver is running, ordinary applications use Ethernet without root. The root-free VPN app described later on this page remains available as a separate method; do not connect both methods to the same adapter at once.

### Native driver downloads and compatibility

Both releases were tested on a Philips 65OLED909/12 with USB ID `0bda:8153`. Each Magisk package checks the **complete kernel release string** and driver SHA-256 before loading. The firmware number alone is not sufficient to establish compatibility.

| Download | Tested Philips firmware | Exact kernel release | Verification |
|---|---|---|---|
| [Magisk 1.0.2](https://github.com/johnfromul/Philips-OLED909-GLAN-RT8153/releases/tag/magisk-v1.0.2) | `TPM231WW_R.201.100.146.220` | `5.15.180-android14-11-00042-g766efc243647-dirty` | Automatic startup after reboot and Wi-Fi fallback tested. |
| [Magisk 1.0.3](https://github.com/johnfromul/Philips-OLED909-GLAN-RT8153/releases/tag/magisk-v1.0.3) | `TPM231WW_R.201.100.228.214` | `5.15.192-android14-11-00092-g9a68f81c71fb-dirty` | Manual driver load and Ethernet tested; automatic startup after reboot **not yet verified**. |

Each release contains a Magisk ZIP, the standalone unmodified Google GKI `r8152.ko` for inspection, and SHA-256 checksums. On 1.0.3, all 125 imported symbol versions matched the TV kernel and its original `mii` module; Android validated Ethernet and the physical link reported 1000 Mb/s full duplex. Link speed is not an application throughput measurement. Both tested drivers reported the optional `rtl_nic/rtl8153a-4.fw` patch missing; the adapter worked without it.

### Install and check the native driver

1. Confirm the TV firmware and check its exact kernel with `adb shell uname -r`. Download the matching Magisk release above. Keep a recovery method available before installing kernel modules.
2. With working Magisk root, install the release ZIP in the Magisk app and restart the TV. Connect the RTL8153 adapter and Ethernet cable, then configure Ethernet as needed.
3. Check the startup log and connection. These commands **require root**:

   ```sh
   adb shell 'su -c "cat /data/adb/rtl8153-native/startup.log"'
   adb shell 'su -c "sh /data/adb/modules/rtl8153_native_ethernet/status.sh"'
   ```

   Look for `STARTUP_OK=1`, a loaded driver, an active Ethernet interface and a link. If necessary, disable the module in Magisk and restart the TV.

See [Magisk setup, driver provenance and GPL-2.0 license](magisk/README.md) for details. The module does not alter system partitions or force a kernel to accept an incompatible driver.

## Root-free VPN app

### Root-free v0.6.1 on firmware 228.214 — verified 6 November 2026

The v0.6.1 userspace USB/ECM + VPN approach also worked on Philips 65OLED909/12 with firmware **`TPM231WW_R.201.100.228.214`** and kernel **`5.15.192-android14-11-00092-g9a68f81c71fb-dirty`**. The app needs ordinary Android USB and VPN consent, with no root permission granted to the app and no loaded `r8152` kernel module during the successful test.

After physically unplugging and reconnecting the USB adapter, DHCP assigned an address, the VPN tunnel was established, DNS over the VPN succeeded, and the app's HTTPS connectivity check returned **HTTP 204**. This confirms functional connectivity, not maximum speed, long-term stability or automatic recovery after reboot.

**When switching from the native Magisk driver:** stop using the native driver, unplug the adapter from the TV's USB port for at least 10 seconds, then reconnect it before starting the root-free app. Disconnecting only the Ethernet cable is insufficient. In our test, switching directly from `r8152` left the adapter unable to receive ECM traffic; a software USB reset did not fix it, but removing USB power did. If using only the root-free app, installing Magisk or obtaining root is not part of its setup. Removing an already loaded native driver was a privileged test-preparation step.

Test precision: the successful test used a locally rebuilt diagnostic version of the v0.6.1 sources with added Java USB transfer logging and the original release's unchanged native libraries. It was not a separate successful run of the byte-identical published APK. The evidence supports compatibility of the v0.6.1 implementation; the original release APK itself was not re-tested after the physical unplug/replug. The diagnostic APK was removed after testing. The previous kernel 5.15.180 / firmware 146.220 remains the original release's directly tested configuration.


### How the app works

```text
Android applications ↔ Android VpnService / TUN ↔ userspace ECM bridge ↔ RTL8153 USB adapter ↔ Ethernet
```

The app uses Android's USB Host API to access the adapter and creates a local IPv4 connection through `VpnService`. The native C++ data path uses asynchronous USB requests. It does **not** install a kernel driver, modify the TV firmware, require root, or send traffic to a remote VPN provider. The local VPN interface itself does not encrypt Ethernet traffic.

### App features

- RTL8153 CDC ECM device support for USB ID **`0bda:8153`**.
- ARMv7 and ARM64 native libraries; Android 8.0 / API 26 or newer.
- Automatic DHCP or manual IPv4 configuration, including gateway and DNS.
- Local ARP handling, address-conflict checks and DHCP lease renewal.
- Three native transfer threads, 256 asynchronous USB RX requests and 128 TX buffers.
- Foreground service with saved connection intent, reconnect attempts, boot recovery and USB reattach handling.
- **Czech, English, German, Spanish and French**, selected from Android's language configuration. English is the fallback.
- USB diagnostics, connection status, DNS/HTTPS checks and exportable logs.

### App quick start

1. Download and install the signed APK from the [app release](https://github.com/johnfromul/Philips-OLED909-GLAN-RT8153/releases/tag/v0.6.1).
2. Connect a compatible RTL8153 adapter and an Ethernet cable.
3. Open the app and approve the Android USB and VPN dialogs.
4. Open **IP settings** and select DHCP or enter a valid manual configuration. Keep **Fast native transfer** enabled.
5. Select **Connect Ethernet**, then **Check internet**.

The connection uses one Android VPN slot. While connected, eligible IPv4 application traffic is routed through USB Ethernet. Wi-Fi may stay connected as a fallback.

For reliable unattended recovery, see [Setup and recovery](docs/SETUP.md). A boot receiver alone cannot restore a USB permission that Android has discarded.

### App: tested TV software

| Component | Tested version |
|---|---|
| TV | Philips 65OLED909/12 |
| Operating system | Android 14 |
| Philips firmware | `TPM231WW_R.201.100.146.220` |
| Linux kernel | `5.15.180-android14-11-00042-g766efc243647-dirty` |

These are the versions read from the actual TV during testing, not the latest firmware available for download. These versions describe the root-free app testing. The separately tested Magisk driver versions have their own [compatibility table](magisk/README.md). See [app validation details](docs/TESTING.md).

### App compatibility and limits

- Hardware testing was performed on a Philips 65OLED909. Other Android TVs may work, but have not been verified.
- The adapter must expose a compatible **CDC ECM** configuration. Matching the marketing name or chipset family is not enough.
- RTL8156, RTL8157 and ASIX adapters are not supported by this release.
- IPv4 only. IPv6 is blocked while the local VPN is active. Multicast forwarding is not implemented.
- This is not a system Ethernet driver: it does not turn the USB adapter into a native Android `eth` interface.
- Other VPN apps cannot use the same VPN slot simultaneously.
- Gigabit Ethernet capability does not guarantee gigabit application throughput. USB hardware, CPU load, TUN overhead and the server affect performance.
- Android **Force stop** is respected. The app cannot guarantee recovery against every vendor-specific power-management policy.

The tested internal TV Ethernet path delivered approximately **94 Mb/s**. USB results were substantially higher; one user-reported internet test reached approximately **320 Mb/s**. These are setup-specific observations, not a throughput guarantee. See [testing details](docs/TESTING.md).

### App automatic recovery

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
| `docs/` | English app setup and validation documentation |
| `magisk/` | Native Ethernet module scripts, compatibility, installation and driver license |
| `licenses/` | Retained Android SDK/NDK third-party notices |

The release APK is signed with the original project development certificate. The private signing key is **not** published. A locally generated signing key will not produce an in-place update of the official APK.

## Reporting problems

Include the TV model, Android version, adapter USB VID/PID, app version and relevant error messages. Review exported logs before sharing them: they can contain local IP addresses, device paths and hardware information. Do not attach private signing keys or credentials.

This is an independent project and is not affiliated with Philips, TP Vision, Realtek or i-tec.
