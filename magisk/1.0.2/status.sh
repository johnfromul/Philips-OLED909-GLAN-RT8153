#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
echo "KERNEL=$(uname -r)"
echo "WIFI_ON=$(settings get global wifi_on)"
echo "BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)"
if [ -d /sys/module/r8152 ]; then echo 'R8152_LOADED=1'; else echo 'R8152_LOADED=0'; fi
for n in /sys/class/net/*; do
    driver=$(readlink -f "$n/device/driver" 2>/dev/null || true)
    [ "$driver" = /sys/bus/usb/drivers/r8152 ] || continue
    echo "USB_ETHERNET=$(basename "$n")"
    for property in carrier speed duplex operstate; do
        printf '%s=' "$property"
        cat "$n/$property" 2>/dev/null || true
    done
done
ip -brief address
dumpsys connectivity | grep -E 'Active default network|NetworkAgentInfo.*Ethernet|NetworkAgentInfo.*WIFI'
