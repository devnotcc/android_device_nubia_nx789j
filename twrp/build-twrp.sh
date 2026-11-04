#!/bin/bash
# Build TWRP recovery.img (reminon twrp-16.0 tree, unmodified) on the USB VHDX.
export PATH="$HOME/bin:$PATH"
# NO ccache: the twrp-16.0 AOSP build sandbox mounts everything but out/
# read-only, so ccache failed writing ~/.cache/ccache/tmp ("Read-only file
# system"). LineageOS/EvoX allow it; plain AOSP does not.
unset USE_CCACHE CCACHE_EXEC
export ALLOW_MISSING_DEPENDENCIES=true
T=/mnt/los23/twrp
OUTDIR=/mnt/c/zte/backup/out/twrp-$(date +%Y%m%d)
mountpoint -q /mnt/los23 || { echo "### /mnt/los23 not mounted"; exit 1; }
cd "$T" || exit 1
echo "device tree: $(git -C device/nubia/NX789J log -1 --format='%h %cs %s')"
source build/envsetup.sh >/dev/null
echo "=== lunch $(date -u +%H:%M:%SZ)"
# NOT "lunch | tail": a pipe runs lunch in a subshell and its exports are lost.
# bp2a (Android 16), NOT the ap3a the tree's COMMON_LUNCH_CHOICES lists: ap3a
# made header-abi-diff compare libbinder_ndk against the Android 15 reference
# dumps (platform/35, vndk/202404) and fail with "EXTENDING CHANGES", because the
# twrp-16.0 sources are Android 16. The ABI check is a platform-dev lint with no
# effect on the recovery image, so it is skipped as well.
export SKIP_ABI_CHECKS=true
mkdir -p out
lunch twrp_NX789J-bp2a-eng > out/lunch.log 2>&1; tail -5 out/lunch.log
[ "$TARGET_PRODUCT" = twrp_NX789J ] || { echo "### LUNCH FAILED"; grep -h COMMON_LUNCH -A3 device/nubia/NX789J/AndroidProducts.mk; exit 1; }
echo "  TARGET_PRODUCT=$TARGET_PRODUCT TARGET_RELEASE=$TARGET_RELEASE TARGET_BUILD_VARIANT=$TARGET_BUILD_VARIANT"
echo "=== build start $(date -u +%H:%M:%SZ)"
mkdir -p out
m recoveryimage -j12 2>&1 | tee out/lastbuild.log | grep -vE '^\[ *[0-9]+% [0-9]+/[0-9]+\]' | tail -60
rc=${PIPESTATUS[0]}
echo "=== build end rc=$rc $(date -u +%H:%M:%SZ)"
IMG=out/target/product/NX789J/recovery.img
[ $rc = 0 ] && [ -f "$IMG" ] || { echo "### TWRP BUILD FAILED"; exit 1; }
mkdir -p "$OUTDIR"
cp -f "$IMG" "$OUTDIR/recovery.img"
sha256sum "$OUTDIR/recovery.img" | tee "$OUTDIR/recovery.img.sha256"
ls -l "$OUTDIR/recovery.img"
echo "  partition limit 104857600 bytes; image $(stat -c%s "$OUTDIR/recovery.img")"
echo "### TWRP BUILD OK -> $OUTDIR"
