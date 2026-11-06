ui_print '- Checking the exact Philips kernel and driver identity'
. "$MODPATH/common.sh"
validate_payload "$MODPATH/modules/r8152.ko" || abort 'Kernel or module identity check failed.'
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/status.sh" 0 0 0755
ui_print '- Driver will load after boot; saved Wi-Fi stays enabled as fallback'
ui_print '- No system mounts, SELinux changes, forced loads or manual routes'
