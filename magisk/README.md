# Native RTL8153 Ethernet (Magisk)

This is a separate alternative to the root-free VPN app. **Root with Magisk is required** for installing the module, loading the kernel driver, automatic startup and root diagnostic commands. ADB is optional; `su` is required for the diagnostic commands below. Once loaded, Android applications use native Ethernet normally without requesting root themselves. The existing VPN app does not require root, but its optional persistent USB access setup may require elevated privileges; see [setup](../docs/SETUP.md).

| Magisk release | Tested Philips firmware | Exact TV kernel | Driver SHA-256 |
|---|---|---|---|
| [1.0.2](https://github.com/johnfromul/Philips-OLED909-GLAN-RT8153/releases/tag/magisk-v1.0.2) | `TPM231WW_R.201.100.146.220` | `5.15.180-android14-11-00042-g766efc243647-dirty` | `ef65d0d9d2d8ad70145ced02fa0b9cdbc9ad12468269c83633e9ccec8eaf9d3c` |
| [1.0.3](https://github.com/johnfromul/Philips-OLED909-GLAN-RT8153/releases/tag/magisk-v1.0.3) | `TPM231WW_R.201.100.228.214` | `5.15.192-android14-11-00092-g9a68f81c71fb-dirty` | `3cbee4f4217b6393297cacf38b6ca3c8a777d414aa07682c5636bc1eca0a22fc` |

Tested on Philips 65OLED909/12 with USB ID `0bda:8153`. Each package validates the exact kernel string and module hash and refuses to load on a different kernel. Firmware labels alone do not establish compatibility. Kernel module loading can affect system stability. Keep a working recovery method before installation.

## Installation and verification

1. Verify the TV firmware and `adb shell uname -r`; choose the exact matching release.
2. With Magisk root already working, install that release's ZIP from the Magisk app and restart the TV. Connect the adapter and Ethernet cable. Configure Ethernet as needed.
3. Check with root: `adb shell 'su -c "cat /data/adb/rtl8153-native/startup.log"'` and `adb shell 'su -c "sh /data/adb/modules/rtl8153_native_ethernet/status.sh"'`. Look for `STARTUP_OK=1`, a loaded driver, link and Android Ethernet. Use the Magisk app to disable the module and restart if necessary.

Do not use the VPN app and native driver simultaneously for the same adapter. The driver uses real Android Ethernet rather than a VPN interface. Wi-Fi is enabled as a fallback; failover does not preserve existing connections. Neither module installs the optional `rtl_nic/rtl8153a-4.fw` patch; the tested adapter worked despite the missing patch message.

1.0.2 was tested after reboot and for Wi-Fi fallback. 1.0.3 was tested by a normal `insmod`, with all 125 imported symbol versions matching the TV kernel and original `mii` module. Android validated Ethernet and the link reported 1000 Mb/s full duplex. Its Magisk service passed a manual run with the driver already loaded; **automatic loading after reboot has not yet been tested**. Link speed is not measured application throughput.

## Driver provenance and license

Both `r8152.ko` files are unchanged Google Android GKI build artifacts, GPL-2.0; the driver source and full corresponding kernel build sources are available at the following tags. Full license: [COPYING-GPL-2.0](COPYING-GPL-2.0).

- 1.0.2: [build 13512086](https://ci.android.com/builds/submitted/13512086/kernel_aarch64/latest/raw/system_dlkm_staging_archive.tar.gz), [source tag android14-5.15-2025-05_r1](https://android.googlesource.com/kernel/common/+/refs/tags/android14-5.15-2025-05_r1/).
- 1.0.3: [build 16017554](https://ci.android.com/builds/submitted/16017554/kernel_aarch64/latest/raw/system_dlkm_staging_archive.tar.gz), [source tag android14-5.15-2025-10_r5](https://android.googlesource.com/kernel/common/+/refs/tags/android14-5.15-2025-10_r5/).

The folders here contain Magisk scripts; the release ZIPs contain the corresponding unmodified binary driver as well.
