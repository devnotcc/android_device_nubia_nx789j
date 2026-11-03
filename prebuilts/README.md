# prebuilts — boot chain, extracted from the stock NX789J dump

Source: `ZTEToolBoxParBak_2026.09.12_07.24.41.349` (RedMagicOS 11.0.4, slot A).
Produced by `scratchpad/make_prebuilts.py`; every artifact was validated after
extraction, not just copied.

| File | Bytes | Source | Validation |
|---|---:|---|---|
| `kernel` | 36,596,224 | `boot_a.img`, header v4, payload after the 1584-byte header padded to 4096 | arm64 `Image` magic `ARM\x64` present at 0x38; embedded version string reads `Linux version 6.6.92-android15-8-g3637f4904cf5-ab13944661-4k` |
| `dtb/nx789j.dtb` | 4,488,075 | `vendor_boot_a.img` dtb section (after header + vendor_ramdisk, page-aligned) | FDT magic `0xd00dfeed` at offset 0; 10 FDT blobs (Sun / SunP / Kera / Tuna SoC DTs) |
| `dtbo.img` | 14,028,740 | `dtbo_a.img`, trimmed to the declared `total_size` | DTBO magic `0xd7b7ab1e`; 37 entries; last entry ends at exactly 14,028,740 |

## Why dtbo.img is trimmed

The `dtbo` partition is 25,165,824 bytes but the DTBO table header declares
`total_size = 14,028,740`, and the last table entry ends at exactly that offset.
The remaining 11,137,084 bytes are not padding-only: they contain **233 nonzero
bytes**, starting with the magic `AVB0` at absolute offset 14,028,800 — i.e. the
stock AVB footer for the partition. There is no second DTBO table and no further
FDT blob in the tail (searched for both magics: not found).

So the trim keeps every device-tree overlay and discards only ZTE's AVB
signature, which LineageOS regenerates anyway.

## Module load lists

| File | Lines | Notes |
|---|---:|---|
| `modules/vendor_ramdisk.load` | 107 | first-stage set from the vendor_boot ramdisk |
| `modules/vendor_ramdisk.load.recovery` | 399 | full recovery set |
| `modules/vendor_ramdisk.blocklist` | 62 | stock `modules.blocklist` |
| `modules/vendor_dlkm.load` | 420 | second-stage set from `vendor_dlkm` |

`system_dlkm/lib/modules/modules.load` exists on the device but is **genuinely
zero bytes**, so there is no file for it here. That is the stock state, not an
extraction failure — the file was read successfully and is empty.

## Not here yet

The `.ko` files themselves (308 in the vendor_boot ramdisk, 281 in `vendor_dlkm`,
~100 in `system_dlkm`, roughly 70 MB total). Those belong in `vendor/nubia/nx789j`
via `extract-files.py`, not in the device tree.

## Why the DTB lives in a subdirectory

`build/make/core/Makefile` builds `$(PRODUCT_OUT)/dtb.img` with

```
$(INSTALLED_DTBIMAGE_TARGET) : $(sort $(wildcard $(BOARD_PREBUILT_DTBIMAGE_DIR)/*.dtb))
```

i.e. it globs `*.dtb` out of a **directory** and concatenates them. A single
prebuilt `dtb.img` file is not a thing the build knows how to consume, so the
blob is stored as `dtb/nx789j.dtb` and `BoardConfig.mk` sets
`BOARD_PREBUILT_DTBIMAGE_DIR`. `boot`, `vendor_boot` and `recovery` all depend on
`INSTALLED_DTBIMAGE_TARGET`, so getting this wrong fails the build at ninja time
with "dtb.img ... missing and no known rule to make it".
