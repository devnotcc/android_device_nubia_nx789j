# Device tree for the nubia REDMAGIC 10 Pro (NX789J)

| | |
|---|---|
| Device | nubia REDMAGIC 10 Pro, NX789J (Global/EEA) |
| SoC | Qualcomm SM8750 (Snapdragon 8 Elite), board `sun` |
| Stock base | RedMagicOS 11.0.4 (vendor AQ3A.240812.002) |
| ROMs | Evolution X 16 (`bka`), LineageOS 23.2 |
| Kernel | prebuilt GKI 6.6.92 + KernelSU-Next + SUSFS, source: [android_kernel_nubia_nx789j](https://github.com/devnotcc/android_kernel_nubia_nx789j) |
| Blobs | [android_vendor_nubia_nx789j](https://github.com/devnotcc/android_vendor_nubia_nx789j) |
| Installer | [nx789j-installer](https://github.com/devnotcc/nx789j-installer) (prebuilt builds) |

## Building Evolution X

```bash
repo init -u https://github.com/Evolution-X/manifest -b bka --git-lfs
# for the exact revisions of the published build:
curl -o .repo/manifests/pinned.xml https://raw.githubusercontent.com/devnotcc/android_device_nubia_nx789j/bka/patches/evox/evox-manifest-pinned.xml
repo init -m pinned.xml
repo sync -c -j8
git clone https://github.com/devnotcc/android_device_nubia_nx789j device/nubia/nx789j
git clone https://github.com/devnotcc/android_vendor_nubia_nx789j vendor/nubia/nx789j
```

Apply the ROM changes in `patches/evox/` (each `<project>.diff` is a `git diff` against the
revision in the pinned manifest; `_` stands for `/`):

```bash
for p in build/soong device/lineage/sepolicy frameworks/base frameworks/native \
         packages/modules/Bluetooth vendor/apn; do
  git -C $p apply "$PWD/device/nubia/nx789j/patches/evox/$(echo $p | tr / _).diff"
done
```

Two stock apps are too large for GitHub and are not in this repository. Copy them out of a
stock RedMagicOS 11 system image before building:

| Put here | From stock |
|---|---|
| `nubiacamera/proprietary/NubiaCamera.apk` | `system/priv-app/NubiaCamera/NubiaCamera.apk` |
| `dts/proprietary/DtsUltraMagic.apk` | `system/app/DtsUltraMagic_MFV/DtsUltraMagic_MFV.apk` |

Then:

```bash
. build/envsetup.sh
lunch lineage_nx789j-bp4a-user
m evolution
```

`extract-files.py` regenerates `vendor/nubia/nx789j` from a stock dump if you'd rather not use the
vendor repository.

## What's in here

- `NubiaParts/`: settings for the shoulder triggers, game slider, fan and performance modes.
- `NubiaUdfps/`: under-display fingerprint (HBM handshake through `zteCmd` and `lcd_hbm`).
- `dts/`, `nubiacamera/`: the stock DTS speaker effect and camera, wired into the ROM.
- `qesdk/`, `qti-telephony/`: Qualcomm IMS/VoLTE and the services it needs.
- `overlay/`: refresh rates, fingerprint position, battery, carrier config.
- `sepolicy/`, `rootdir/`, `properties/`: the usual.
- `prebuilts/`: the kernel `Image` (built from the kernel repository above; it is the same
  binary the installer flashes), the stock `dtb`/`dtbo` and the vendor module load lists.
- `patches/evox/`: every change made to Evolution X source outside this tree, plus the pinned
  manifest of the published build.

`BRINGUP_NOTES.md` is the original bring-up write-up and explains where the board values came
from.

## Notes

- Local builds can pre-authorise a computer for adb by putting its public key in
  `security/adb_keys` (not tracked). Public builds must not.
- The bootloader is unlocked through an exploit, not fastboot; see the installer for flashing.

## License

Apache-2.0 (see [LICENSE](LICENSE)), except the stock binaries in `prebuilts/` and
`*/proprietary/`, which keep ZTE's and Qualcomm's terms. The kernel `Image` is GPL-2.0; its
source is linked above.
