# Changelog

## 0.6.1 — 2026-10-23

- Added Czech, English, German, Spanish and French Android resources with automatic OS-language selection.
- Added English fallback and regional locale support.
- Localized UI controls, IP settings, validation messages, connection status, notifications and new diagnostic messages.
- Removed connection-state decisions based on displayed language prefixes.
- Added text autosizing for longer translated button labels.
- Verified 1,683 compiled-resource comparisons and visually checked all five main screens on the TV.
- Retained the existing native transfer libraries and automatic-recovery behavior.

## 0.6.0

- Saved the requested connection state across service recreation and reboot.
- Added boot/update recovery, foreground sticky service handling and a USB reconnect supervisor.
- Added USB attachment handling and recovery status messages.
- Verified reboot, synthetic crash with system always-on VPN, short sleep/wake, physical USB replug and manual-stop persistence.
- Documented one-time persistent USB permission and system always-on setup on the tested TV.

## 0.5.6

- Optimized asynchronous native USB RX scheduling and buffer refill behavior.
- Established the native transfer-library baseline retained by 0.6.x.
