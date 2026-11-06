#!/system/bin/sh
EXPECTED_KERNEL='5.15.180-android14-11-00042-g766efc243647-dirty'
EXPECTED_SHA='ef65d0d9d2d8ad70145ced02fa0b9cdbc9ad12468269c83633e9ccec8eaf9d3c'

validate_payload() {
    [ "$(id -u)" = 0 ] || { echo 'ERROR: Magisk root is required.'; return 10; }
    [ "$(uname -r)" = "$EXPECTED_KERNEL" ] || { echo 'ERROR: Kernel differs from the verified release; refusing to load.'; return 11; }
    [ -f "$1" ] || { echo 'ERROR: Driver file missing.'; return 12; }
    actual=$(sha256sum "$1" | cut -d ' ' -f 1)
    [ "$actual" = "$EXPECTED_SHA" ] || { echo 'ERROR: Driver SHA-256 mismatch; refusing to load.'; return 13; }
}
