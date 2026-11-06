#!/system/bin/sh
# Magisk late_start service. Bounded initialization, no route/score overrides.
MODDIR=${0%/*}
. "$MODDIR/common.sh"
validate_payload "$MODDIR/modules/r8152.ko" || exit $?

umask 077
STATE='/data/adb/rtl8153-native'
mkdir -p "$STATE" || exit 20
chmod 700 "$STATE"
exec 9>"$STATE/service.lock"
/data/adb/magisk/busybox flock -n 9 || exit 0
[ ! -f "$STATE/startup.log" ] || mv -f "$STATE/startup.log" "$STATE/startup.previous.log"
exec >"$STATE/startup.log" 2>&1
echo "RTL8153 native Ethernet 1.0.2: $(date)"
id
echo "BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)"
echo "KERNEL=$(uname -r)"

i=0
while [ "$(getprop sys.boot_completed)" != 1 ]; do
    [ "$i" -lt 180 ] || { echo 'ERROR: Boot completion timeout.'; exit 21; }
    sleep 1
    i=$((i + 1))
done

i=0
while [ ! -d /sys/module/mii ]; do
    [ "$i" -lt 120 ] || { echo 'ERROR: Original mii dependency did not load.'; exit 22; }
    sleep 1
    i=$((i + 1))
done

if [ ! -d /sys/module/r8152 ] && ! grep -q '^r8152 ' /proc/modules; then
    if ! /system/bin/insmod "$MODDIR/modules/r8152.ko"; then
        # Another loader may have finished between our check and insmod.
        [ -d /sys/module/r8152 ] || grep -q '^r8152 ' /proc/modules || {
            echo 'ERROR: Normal insmod rejected the module.'; exit 23;
        }
    fi
    echo 'DRIVER_LOADED_BY_SERVICE=1'
else
    echo 'DRIVER_ALREADY_LOADED=1'
fi

[ -d /sys/module/r8152 ] || { echo 'ERROR: Driver is not present after loading.'; exit 24; }
# Binder shell commands pass their standard descriptors to system_server.
# Capture both output streams through a pipe: system_server cannot use the
# private Magisk log file directly as its stdout/stderr descriptor.
i=0
while :; do
    wifi_output=$(/system/bin/cmd wifi set-wifi-enabled enabled 2>&1)
    wifi_result=$?
    [ -z "$wifi_output" ] || printf '%s\n' "$wifi_output"
    [ "$wifi_result" = 0 ] && break
    [ "$i" -lt 45 ] || { echo 'ERROR: Wi-Fi service did not become ready.'; exit 25; }
    sleep 1
    i=$((i + 1))
done
wifi_setting=$(/system/bin/settings get global wifi_on 2>&1)
echo "WIFI_ON=$wifi_setting"
echo 'STARTUP_OK=1'
/system/bin/ip -brief address
echo 'Network priority and fallback are managed by Android ConnectivityService.'
