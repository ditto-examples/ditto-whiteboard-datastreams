#!/usr/bin/env python3
"""Fail when a packaged 64-bit ELF library is not ready for 16 KiB pages."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile

PT_LOAD = 1
SIXTEEN_KB_DEVICE_ABIS = ("arm64-v8a", "x86_64")


def load_alignments(data: bytes) -> list[int]:
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    elf_class = data[4]
    byte_order = data[5]
    endian = "<" if byte_order == 1 else ">" if byte_order == 2 else None
    if endian is None:
        raise ValueError("unknown ELF byte order")

    if elf_class == 1:
        ph_offset = struct.unpack_from(f"{endian}I", data, 28)[0]
        ph_entry_size = struct.unpack_from(f"{endian}H", data, 42)[0]
        ph_count = struct.unpack_from(f"{endian}H", data, 44)[0]
        align_offset, align_format = 28, "I"
    elif elf_class == 2:
        ph_offset = struct.unpack_from(f"{endian}Q", data, 32)[0]
        ph_entry_size = struct.unpack_from(f"{endian}H", data, 54)[0]
        ph_count = struct.unpack_from(f"{endian}H", data, 56)[0]
        align_offset, align_format = 48, "Q"
    else:
        raise ValueError("unknown ELF class")

    alignments: list[int] = []
    for index in range(ph_count):
        entry = ph_offset + index * ph_entry_size
        segment_type = struct.unpack_from(f"{endian}I", data, entry)[0]
        if segment_type == PT_LOAD:
            alignments.append(
                struct.unpack_from(f"{endian}{align_format}", data, entry + align_offset)[0]
            )
    return alignments


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", help="APK or AAB to inspect")
    parser.add_argument("--minimum", type=int, default=16 * 1024)
    parser.add_argument(
        "--all-abis",
        action="store_true",
        help="also apply the requested minimum to legacy 32-bit ABIs",
    )
    args = parser.parse_args()

    failures: list[str] = []
    inspected = 0
    with zipfile.ZipFile(args.bundle) as archive:
        for name in sorted(item for item in archive.namelist() if item.endswith(".so")):
            if not args.all_abis and not any(
                f"/{abi}/" in f"/{name}" for abi in SIXTEEN_KB_DEVICE_ABIS
            ):
                continue
            inspected += 1
            try:
                alignments = load_alignments(archive.read(name))
            except (ValueError, struct.error) as error:
                failures.append(f"{name}: {error}")
                continue
            below = [alignment for alignment in alignments if alignment < args.minimum]
            if below:
                failures.append(
                    f"{name}: LOAD alignment(s) {', '.join(hex(value) for value in below)}"
                )

    if inspected == 0:
        failures.append("bundle contains no native libraries for the selected ABIs")
    if failures:
        print("Native alignment verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    abi_scope = "all ABIs" if args.all_abis else ", ".join(SIXTEEN_KB_DEVICE_ABIS)
    print(
        f"Verified {inspected} native libraries for {abi_scope} at "
        f">= {args.minimum} byte LOAD alignment"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
