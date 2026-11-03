# device/nubia/nx789j — LineageOS 23.2 device tree (first draft)

Nubia RedMagic **NX789J** (RedMagic 10 Pro, Global/EEA), board `sun`, SM8750.

Drafted while the LineageOS source was syncing. **Nothing here has been compiled yet.**
Every value is sourced from the EDL dump
`ZTEToolBoxParBak_2026.09.12_07.24.41.349` and cross-checked against
`LineageOS/android_device_oneplus_sm8750-common` (same SoC, officially supported);
see `NX789J_STEP1-2_DUMP_REPORT.md` for the derivations.

## What is here

| File | State |
|---|---|
| `BoardConfig.mk` | drafted, every value sourced; **unverified against a build** |
| `device.mk` | drafted; package list is a first guess |
| `lineage_nx789j.mk` | drafted |
| `AndroidProducts.mk`, `Android.bp`, `board-info.txt`, `lineage.dependencies` | drafted |
| `proprietary-files.txt` | **1112 entries**, generated (see below) |
| `overlay/NubiaFrameworksResTarget` | refresh rate, fingerprint, telephony, battery |
| `overlay/NubiaSystemUIResTarget` | `pixel_pitch` 59.2 (the UDFPS icon fix) |
| `properties/{vendor,odm,system_ext}.prop` | drafted from stock props |
| `rootdir/etc/fstab.qcom` | **byte-identical copy of the stock fstab** out of `vendor_a.img` |
| `config.fs` | QTI vendor AIDs, taken from sm8750-common |
| `extract-files.py` / `setup-makefiles.py` | drafted |
| `prebuilts/` | **done** — kernel, dtb.img, dtbo.img, module load lists (53 MB); see `prebuilts/README.md` |
| `sepolicy/` | **EMPTY — still to do** |

## How `proprietary-files.txt` was generated

Two sections:

1. **994 entries** — the intersection of `sm8750-common`'s blob list with a full
   listing of our `vendor` + `odm` (3,833 files). These are QTI platform blobs
   known to be needed on this SoC that exist at the identical path in our dump.
2. **118 entries** — the ZTE/Nubia-specific set with no OnePlus counterpart.

52 `vendor/overlay/*` theme-stub APKs were dropped: they are 8,474-byte
manifest-only RROs with no `resources.arsc` at all, so they carry nothing.

Both sections still need pruning against a real build.

## Things the blob list revealed

- **Cameras: four sensors**, internal project codename **`aston`** —
  `ov50e40_aston`, `ov50d40_aston`, `ov16a1q_aston`, `ov02f10_aston`, plus 13
  `com.zte.node.*` CHI components. The GSI exposing only 2 camera IDs is a CHI
  logical-camera config problem, and this is the blob set behind it.
- **RGB logo**: Awinic AW22xxx — `vendor/firmware/aw22xxx_fw.bin` plus
  `nubia_all_rgb_{red,green,blue}.bin`.
- **SAR: two AW96103 sensors** — `aw9620x_{fw,reg,bt}_{0,1}.bin` and six
  `pakala_aw96103_{0,1}_sar_register_{a,b,c}.json`. So `aw9620x.ko` is SAR, and
  the shoulder triggers are `gpio_keys_nubia` (plain GPIO keys) as the DTBO showed.
- **Haptics: AAC RichTap** — `vendor.aac.hardware.richtap.vibrator-V2-ndk.so`,
  `awinic_haptic_params.bin`, `haptic_ram.bin`, 16 `acdbdata/haptics_data/*.pcm`
  waveforms, `odm/etc/aac_richtap.config`. This is the stack behind breakage #4
  (vibrator frequency profile returning NaN).
- **Speakers**: Awinic AW882xx smart amp — `aw882xx_acf.bin`, `vendor/bin/aw882xx_cali`.

## Verified constraint: system_ext, product and odm MUST be EROFS

The stock fstab (`vendor/etc/fstab.qcom`, byte-identical to the copy in the
vendor_boot ramdisk at `first_stage_ramdisk/fstab.qcom`) gives `/system` both an
erofs and an ext4 line, but `system_ext`, `product` and `odm` get **only** an
erofs line. Checked directly against the extracted file:

```
system       erofs=1 ext4=1
system_ext   erofs=1 ext4=0   <-- EROFS ONLY
product      erofs=1 ext4=0   <-- EROFS ONLY
odm          erofs=1 ext4=0   <-- EROFS ONLY
```

Since this port rides the stock vendor_boot (and therefore the stock fstab),
building those three as ext4 would leave `first_stage_mount` with no matching
entry and the device would not boot. `BoardConfig.mk` sets them to erofs.

## Still to do

1. ~~`prebuilts/`~~ — **done**. kernel (36.6 MB, validated arm64 Image, GKI
   6.6.92-android15-8), dtb.img (4.49 MB, FDT magic OK), dtbo.img (14.03 MB,
   trimmed off the stock AVB footer), and the four module load lists.
   The `.ko` files themselves still need to come from `extract-files.py`.
2. **`sepolicy/`** — empty. Expect a large denial set; the stock-vendor ride
   means permissive first (as on the NX809J).
3. **Strip the odm precompiled sepolicy.** The stock odm ships
   `etc/selinux/precompiled_sepolicy` (1,561,938 B) + three `.sha256` stamps.
   Framework and vendor are built separately here, so that hash can never match
   and `init` dies with "Failed to initialize property area". Must be removed
   when the odm image is assembled.
4. **Build at `bp4a`, not `bp2a`** — both give SDK 36, only `bp4a` carries
   LineageOS release aconfig flag values.
5. Run `extract-files.py` against the dump to populate `vendor/nubia/nx789j`.
6. Decide the device codename: this tree uses `nx789j`, but the camera blobs
   show Nubia's internal project name is **`aston`**, which may be the better
   codename if this is ever submitted upstream.

## Unverified / assumptions to check first

- `TARGET_BOOTLOADER_BOARD_NAME := sun` copied from the OnePlus tree; not
  confirmed against our own bootloader.
- AVB key paths and rollback index locations are LineageOS test keys, copied
  from the OnePlus pattern — not yet matched to our vbmeta layout.
- The `PRODUCT_PACKAGES` list in `device.mk` is a first guess; it has not been
  reconciled against the 124 HALs the stock vendor manifest declares.
- Battery capacity in the overlay (7050 mAh) is from public specs, not the dump.
