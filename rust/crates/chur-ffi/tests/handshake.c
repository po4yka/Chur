/*
 * The C side of the FFI harness.
 *
 * docs/interop/FFI_CONTRACT.md section 2 says the handshake is what a platform
 * gate checks before a vault opens. This program is that gate, in C, linked
 * against the real static library, so the harness proves the symbols exist
 * with C linkage and the declared types rather than proving that Rust agrees
 * with Rust.
 *
 * Build and run:
 *
 *   cargo build -p chur-ffi --release
 *   cc -I crates/chur-ffi/include crates/chur-ffi/tests/handshake.c \
 *      target/release/libchur_ffi.a -o handshake && ./handshake
 */

#include <stdio.h>
#include <stdlib.h>

#include "chur.h"

static int failures = 0;

static void check(int condition, const char *what) {
    if (condition) {
        printf("  ok    %s\n", what);
    } else {
        printf("  FAIL  %s\n", what);
        failures++;
    }
}

int main(void) {
    printf("chur ABI handshake\n");

    check(chur_abi_version_major() == 1, "major ABI version is 1");
    check(chur_abi_version_minor() == 0, "minor ABI version is 0");

    check(chur_object_format_min() <= chur_object_format_max(),
          "object format range is ordered");
    check(chur_key_slot_format_min() <= chur_key_slot_format_max(),
          "key slot format range is ordered");
    check(chur_object_format_min() == 1 && chur_object_format_max() == 1,
          "object format range is 1..=1");
    check(chur_key_slot_format_min() == 1 && chur_key_slot_format_max() == 1,
          "key slot format range is 1..=1");

    uint64_t capabilities = chur_capabilities();
    check(capabilities == 0, "no capability is declared before its surface exists");
    check((capabilities & ~(CHUR_CAP_DECOY_VAULT | CHUR_CAP_OBJECT_READER |
                            CHUR_CAP_SEQUENTIAL_READER | CHUR_CAP_INTEGRITY_SCAN |
                            CHUR_CAP_BACKUP_PACKAGE | CHUR_CAP_SYNC |
                            CHUR_CAP_CONCURRENT_READS)) == 0,
          "no reserved capability bit is set");

    uint32_t flavor = chur_build_flavor();
    check((flavor & CHUR_FLAVOR_TEST_HOOKS) == 0, "no test hooks are compiled in");
    check(((flavor & CHUR_FLAVOR_RELEASE) != 0) ^
              ((flavor & CHUR_FLAVOR_DEBUG_ASSERTIONS) != 0),
          "the build is release or debug, never both");

    check(chur_status_is_known(CHUR_AUTHENTICATION_FAILED),
          "an allocated status is known");
    check(!chur_status_is_known(CHUR_OK), "success is not an error code");
    check(!chur_status_is_known(42), "an unallocated value is unknown");
    check(!chur_status_is_known(-1), "a negative value is unknown");
    check(!chur_status_is_known(700), "the reserved 700 block is unallocated");

    if (failures != 0) {
        printf("\n%d check(s) failed\n", failures);
        return EXIT_FAILURE;
    }
    printf("\nall checks passed\n");
    return EXIT_SUCCESS;
}
