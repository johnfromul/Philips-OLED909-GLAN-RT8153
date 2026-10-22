# v0.6.1 — Multilanguage USB Ethernet with automatic recovery

Root-free Realtek RTL8153 USB Ethernet for Android TV, tested on a Philips 65OLED909 running Android 14.

## Tested TV software

- Philips 65OLED909/12, Android 14.
- Firmware: `TPM231WW_R.201.100.146.220`.
- Kernel: `5.15.180-android14-11-00042-g766efc243647-dirty`.

These versions were read from the actual test TV. Other firmware/kernel combinations have not been verified.

## Highlights

- Czech, English, German, Spanish and French, selected automatically from Android's language configuration.
- English fallback, including tested regional language matching.
- Saved connection intent and reconnect handling after reboot, service recreation and USB reattachment.
- Manual Disconnect remains disabled across a reboot.
- DHCP or manual IPv4 configuration; native asynchronous USB transfer for ARMv7 and ARM64.

## Installation

Install `RTL8153-USB-Ethernet-0.6.1.apk`, connect a compatible `0bda:8153` CDC ECM adapter, approve USB/VPN access, configure IP settings and select Connect Ethernet.

The APK is signed with the original project development certificate and can update the earlier official project builds. Private signing keys are not included.

## Recovery requirements

Reliable recovery on the tested TV required a persistent USB permission for the particular adapter and Android Always-on VPN for this app, with VPN lockdown disabled. Those system settings are not automatically copied by installing an APK on a different TV. See `docs/SETUP.md` before relying on unattended recovery.

## Validation and limits

All 1,683 on-device language-resource comparisons passed. Packet/configuration tests passed. Recovery testing covered a real reboot, synthetic process crash with system always-on VPN, short sleep/wake and physical USB unplug/replug.

IPv4 only; no multicast forwarding. Uses the Android VPN slot. No support for RTL8156/RTL8157 or ASIX adapters. Long deep standby has not been exhaustively tested, and Android Force stop is respected. No new throughput benchmark was performed for 0.6.1; its native transfer libraries are unchanged from 0.5.6.

## Downloads

- **APK:** ready-to-install signed app.
- **Source ZIP:** complete public source tree, build script, tests and English documentation.
- **Release bundle ZIP:** APK, source ZIP, English documentation and checksums.
- **SHA256SUMS.txt:** integrity checksums for the release assets.
