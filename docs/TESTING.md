# Validation

## Tested setup

- Philips **65OLED909/12**, Android 14.
- Philips firmware **`TPM231WW_R.201.100.146.220`**, read from `ro.odm.tpvision.product.swver`.
- Kernel `5.15.180-android14-11-00042-g766efc243647-dirty`.
- RTL8153 USB Ethernet adapters, including an i-tec USB 3.0 adapter reported working by the user.
- App version **0.6.1**, version code **21**.

The kernel release above was read from the running TV with `uname -r`. These values describe the tested installation; they are not claims about the newest available firmware. Other firmware/kernel combinations have not been verified.

No firmware modification or root access was used.

## Automated checks

- Ten descriptor/device-filter checks.
- DHCP discovery/OFFER validation, packet bounds and 342 truncation cases.
- Static IPv4 validation, subnet routing, ARP and DHCP ACK parsing/truncation cases.
- Translation key parity and formatting-placeholder consistency for 187 resources in five language sets.
- Language-independent connection-state checks.
- APK alignment, signature validation, matching original release certificate and ZIP integrity.

The portable build script was tested on macOS with the verified release native libraries. The original native libraries were built with the NDK; the full cross-platform NDK path in the public build script has not been exercised on every supported build host.

## On-device language checks for 0.6.1

**1,683 compiled-resource comparisons passed with zero failures**:

- `cs-CZ`, `en-US`, `de-DE`, `es-ES`, `fr-FR`.
- `es-MX` and `fr-CA` regional fallback.
- `it-IT` to English fallback.
- `it-IT,fr-FR` to the supported French preference.

Main screens were visually checked in all five languages. App-only locale overrides were removed after testing; the app was left following the OS locale. Connection status updated correctly, and the USB connection remained available.

## Recovery checks introduced in 0.6.0

| Scenario | Result |
|---|---|
| Real reboot with connection enabled | Automatic service start, USB reconnection, DNS and HTTPS 204 |
| Synthetic process crash without system always-on | `START_STICKY` alone did not recover in that test |
| Synthetic process crash with system always-on | New process, automatic connection, DNS and HTTPS 204 |
| Short standby/wake | Sleep state confirmed; USB ADB connection worked after waking |
| Physical USB unplug/replug after about 15 seconds | Old session released, waiting state, automatic reconnection and HTTPS 204 |
| Manual Disconnect followed by real reboot | No active forwarding service and no TUN connection |
| Update to 0.6.1 | Automatic reconnection with settings retained and HTTPS 204 |

The native ARMv7 and ARM64 libraries are unchanged between 0.5.6, 0.6.0 and 0.6.1.

Long deep standby, prolonged DHCP renewal and all possible firmware-specific lifecycle conditions are not covered by these short tests. Recovery may also need persistent USB permission and system always-on configuration; see [SETUP.md](SETUP.md).

## Performance observations

An early optimized native version reached approximately 469 Mb/s download and 584 Mb/s upload in a short HTTP LAN test. Later 0.5.6 measurements in Kodi reached approximately 393 Mb/s LAN download with four connections and 484 Mb/s upload. The user subsequently reported an internet download of approximately 320 Mb/s with another RTL8153 adapter.

These observations used different test clients and conditions and are **not** controlled adapter comparisons or guaranteed limits. Version 0.6.1 adds localization; it has not been given a new independent throughput benchmark. Its native transfer libraries are unchanged.

The TV's internal RJ45 path measured approximately 93–94 Mb/s both with automatic negotiation and after manually selecting 1 Gb/s on the switch. Firmware/device-tree analysis selected the internal PHY path; no simple `force-100m` switch limiting an otherwise confirmed gigabit PHY was found. This is context for using USB Ethernet, not a claim that the app modifies the built-in port.

Raw private network captures, local addresses, device identifiers and screenshots are not included in the public repository.


## Root-free v0.6.1 on firmware 228.214 — verified 6 November 2026

The v0.6.1 userspace USB/ECM + VPN approach also worked on Philips 65OLED909/12 with firmware **`TPM231WW_R.201.100.228.214`** and kernel **`5.15.192-android14-11-00092-g9a68f81c71fb-dirty`**. The app needs ordinary Android USB and VPN consent, with no root permission granted to the app and no loaded `r8152` kernel module during the successful test.

After physically unplugging and reconnecting the USB adapter, DHCP assigned an address, the VPN tunnel was established, DNS over the VPN succeeded, and the app's HTTPS connectivity check returned **HTTP 204**. This confirms functional connectivity, not maximum speed, long-term stability or automatic recovery after reboot.

**When switching from the native Magisk driver:** stop using the native driver, unplug the adapter from the TV's USB port for at least 10 seconds, then reconnect it before starting the root-free app. Disconnecting only the Ethernet cable is insufficient. In our test, switching directly from `r8152` left the adapter unable to receive ECM traffic; a software USB reset did not fix it, but removing USB power did. If using only the root-free app, installing Magisk or obtaining root is not part of its setup. Removing an already loaded native driver was a privileged test-preparation step.

Test precision: the successful test used a locally rebuilt diagnostic version of the v0.6.1 sources with added Java USB transfer logging and the original release's unchanged native libraries. It was not a separate successful run of the byte-identical published APK. The evidence supports compatibility of the v0.6.1 implementation; the original release APK itself was not re-tested after the physical unplug/replug. The diagnostic APK was removed after testing. The previous kernel 5.15.180 / firmware 146.220 remains the original release's directly tested configuration.
