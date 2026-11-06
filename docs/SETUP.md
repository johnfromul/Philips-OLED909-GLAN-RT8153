# Setup and recovery

## Initial setup

Install the signed release APK and connect an adapter with USB ID `0bda:8153`. Approve the USB-access and VPN dialogs. On TVs offering a default USB-handler checkbox, select this app as the handler for the adapter if you want automatic reconnection.

Open **IP settings**. Choose DHCP, or use an unused address on your own network, preferably reserved in the router. The app accepts a CIDR prefix or a contiguous subnet mask, and comma-separated DNS servers. Never copy an example address without checking your network configuration.

**Fast native transfer (asynchronous USB)** is the recommended data path. After connecting, use **Check internet** to verify DNS and an HTTPS response.

The app uses a local VPN interface. It does not connect to a remote VPN server and does not encrypt local Ethernet traffic by itself. It occupies the device's VPN slot while connected.

## Saved connection state

- **Connect Ethernet** saves the user's requested enabled state.
- **Disconnect** clears it. It remains disabled after reboot until the user enables it again.
- Boot and package-update receivers honor that saved choice.
- An ongoing foreground service uses `START_STICKY` and a reconnect supervisor.
- Missing hardware releases the old TUN/USB connection and waits for the adapter.
- Reconnect attempts use a bounded delay. Old ARP caches and packet queues are cleared before a new session.
- Manual IP settings are retained; DHCP obtains a new lease when reconnecting.

## Persistent USB permission

**Root:** the app and standard approval dialogs do not require root. The system-side persistent permission provisioning below requires an ADB shell with sufficient system privileges; it is not guaranteed to be available on an unrooted TV. Always-on VPN may be configurable without root through system settings.

Android can discard a temporary USB permission when hardware is disconnected or power is lost. The app cannot grant itself a privileged persistent permission. A default-handler selection may restore access automatically, depending on the TV firmware.

On the tested Philips TV, persistent access was provisioned once through ADB using the system `IUsbManager.setDevicePersistentPermission` API for the app's UID, user 0 and the particular USB device. The current attachment was also granted access with `grantDevicePermission`. This was a narrow system-side permission for that app/device pair; the app itself was not granted `MANAGE_USB`.

A different adapter or a fresh installation with a different UID can require separate provisioning. Do not assume that copying the APK copies these system permissions. The repository intentionally does not ship a hard-coded app UID or a tool that silently grants permissions on arbitrary devices.

If access is missing, the service waits and asks you to open the app and approve the adapter.

## Android Always-on VPN

On the tested TV, a synthetic `am crash` test did not recover with `START_STICKY` alone. Enabling Android's **Always-on VPN** for this app provided automatic recovery in that test.

Use the system VPN settings, when available. Keep **Block connections without VPN** disabled so other network connections remain available while USB is absent.

The tested TV exposed the following settings through ADB:

```sh
adb shell settings put secure always_on_vpn_app cz.codex.rtl8153diagnostic
adb shell settings put secure always_on_vpn_lockdown 0
```

A real reboot was used to verify that the setting had been loaded. These commands replace the selected always-on VPN, so check for an existing VPN configuration first. They are system setup commands, not permissions that the APK grants to itself.

The app still honors its own **Disconnect** action. If you want to stop using this app as the system always-on VPN, remove it in the system VPN settings or clear that system configuration separately. Do not enable VPN lockdown for an adapter that may be unplugged unless you specifically intend to block fallback connectivity.

## Standby and limits

A short sleep/wake cycle and physical USB removal/reinsertion were tested. The app does not hold a permanent wake lock to prevent the TV from sleeping. Long deep-standby behavior and every vendor-specific process-killing policy have not been exhaustively tested.

Android **Force stop** is an intentional user action and is not bypassed. Open the app again after using it. Removing VPN consent or denying USB access also requires appropriate user action.

## Languages

The app follows Android's locale configuration: Czech, English, German, Spanish and French. Unsupported language lists fall back to English when no supported language matches. Regional variants use the corresponding language resources.

There is no in-app language selector. Newly generated messages use the current language. Historical log entries retain the language in which they were created. USB identifiers, JSON field names, protocol names and raw OS errors remain technical identifiers.
