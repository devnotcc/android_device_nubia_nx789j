#!/bin/bash
# Sync TWRP twrp-16.0 minimal manifest + reminon NX789J tree onto the USB VHDX.
export PATH="$HOME/bin:$PATH"
T=/mnt/los23/twrp
mountpoint -q /mnt/los23 || { echo "### /mnt/los23 not mounted"; exit 1; }
mkdir -p "$T" && cd "$T"
if [ ! -d .repo ]; then
  repo init --depth=1 -u https://github.com/TWRP-Test/platform_manifest_twrp_aosp.git -b twrp-16.0 --git-lfs || exit 1
fi
mkdir -p .repo/local_manifests
cat > .repo/local_manifests/nx789j.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <remote name="nx789j-gh" fetch="https://github.com/" />
  <project name="reminon/twrp_device_nubia_nx789j" path="device/nubia/NX789J" remote="nx789j-gh" revision="twrp-16.0" clone-depth="1" />
</manifest>
XML
for i in 1 2 3; do
  echo "=== sync attempt $i $(date -u +%H:%M:%SZ)"
  repo sync -c -j8 --force-sync --no-clone-bundle --no-tags --optimized-fetch && { echo "### TWRP SYNC OK $(date -u +%H:%M:%SZ)"; du -sh "$T"; exit 0; }
  sleep 30
done
echo "### TWRP SYNC FAILED"; exit 1
