#!/system/bin/sh
EXPECTED_KERNEL='5.15.192-android14-11-00092-g9a68f81c71fb-dirty'
EXPECTED_SHA='3cbee4f4217b6393297cacf38b6ca3c8a777d414aa07682c5636bc1eca0a22fc'

validate_payload() {
    [ "$(id -u)" = 0 ] || { echo 'ERROR: Magisk root is required.'; return 10; }
    [ "$(uname -r)" = "$EXPECTED_KERNEL" ] || { echo 'ERROR: Kernel differs from the verified release; refusing to load.'; return 11; }
    [ -f "$1" ] || { echo 'ERROR: Driver file missing.'; return 12; }
    actual=$(sha256sum "$1" | cut -d ' ' -f 1)
    [ "$actual" = "$EXPECTED_SHA" ] || { echo 'ERROR: Driver SHA-256 mismatch; refusing to load.'; return 13; }
}
