#!/usr/bin/env bash
#
# Build and run the native JTTY codec tests on a POSIX host (Linux/macOS),
# no device/emulator needed. Companion to run_jtty_host_tests.ps1 (Windows);
# both compile the jttyaf_glue/test_jtty_*.c suites against the clean-room
# codec in cpp/jtty_lib and exit 0 only when every suite passes.
#
# Usage:
#   run_jtty_host_tests.sh                       # build + run all suites
#   run_jtty_host_tests.sh [path/to/260807_134110.wav]
#                                                # (or JTTY_SAMPLE_WAV=...; the
#                                                # sample suite SKIPs if absent)
#   CC=gcc run_jtty_host_tests.sh                # override the compiler

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cpp="$(dirname "$here")"
lib="$cpp/jtty_lib"
CC="${CC:-clang}"

lib_srcs=(
    "$lib/jtty_spec.c"
    "$lib/jtty_crc.c"
    "$lib/jtty_fec.c"
    "$lib/jtty_call28.c"
    "$lib/jtty_source.c"
    "$lib/jtty_modulate.c"
    "$lib/jtty_rx.c"
)

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cflags=(-std=c11 -O2 -Wall -Wextra -Wno-unused-function -I "$lib")

# -lm is needed on Linux (glibc keeps libm separate); it doesn't exist for an
# MSVC-target clang, so drop it under Git Bash / MSYS on Windows.
ldlibs=(-lm)
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ldlibs=() ;;
esac

sample_args=()
for a in "$@"; do sample_args+=("$a"); done

"$CC" "${cflags[@]}" "$here/jtty_cli.c" "${lib_srcs[@]}" "${ldlibs[@]}" -o "$tmp/jtty_cli"

suites=(
    test_jtty_source
    test_jtty_channel
    test_jtty_modem
    test_jtty_sample
)

for suite in "${suites[@]}"; do
    out="$tmp/$suite"
    "$CC" "${cflags[@]}" "$here/$suite.c" "${lib_srcs[@]}" "${ldlibs[@]}" -o "$out"
    if [ "$suite" = "test_jtty_sample" ]; then
        "$out" "${sample_args[@]}"
    else
        "$out"
    fi
done

echo "all JTTY host test suites passed"
