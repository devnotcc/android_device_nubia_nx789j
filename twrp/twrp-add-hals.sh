#!/bin/bash
D=/mnt/los23/twrp/device/nubia/NX789J/recovery/root/system/etc/vintf/manifest
mkdir -p "$D"
cp /mnt/c/zte/backup/tools/evox/twrp-recovery-hals.xml "$D/nx789j-recovery-hals.xml"
sed -i 's/\r$//' "$D/nx789j-recovery-hals.xml"
ls -la "$D"; grep -c '<hal' "$D/nx789j-recovery-hals.xml"
ls -la /nx789j-recovery-hals.xml 2>/dev/null && echo "(stray file at / from the failed attempt)"
