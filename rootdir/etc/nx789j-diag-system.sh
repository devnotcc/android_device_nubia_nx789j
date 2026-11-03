#!/vendor/bin/sh
# NX789J SYSTEM-BOOT diagnostics -> raw rawdump partition.
#
# WHY rawdump and not logdump: logdump IS in fstab.qcom, so Android mounts it -
# writing raw to a mounted filesystem would corrupt it and race the kernel.
# rawdump (LUN0, sector 4219688, 512 MiB) appears in no fstab, so Android never
# touches it. It sits between metadata (ends 4219688) and userdata (starts
# 4350760) and nothing this port flashes writes to it.
#
# WHY A LOOP: the system bootloops, so there is no "end of boot" at which to
# sample. Instead re-dump every 5 s and overwrite; whatever was written last
# before the reboot is what we read back over EDL. ~3 minutes of coverage.
# /vendor/bin/sh starts with a PATH that does not include /system/bin, so
# "logcat" resolved to nothing and the logcat section came back empty in builds
# 8 and 9 with only "logcat: inaccessible or not found". Everything interesting
# now (keystore2 and qseecomd failing to register) logs to logcat, not dmesg.
#
# WHY THE FILTER AND THE BIGGER BUFFER: with the PATH fixed, build 10 finally
# captured logcat - and it was useless. SELinux is permissive, so every syscall
# this script makes logs an avc denial under u:r:vendor_qti_init_shell:s0, and
# "ps -A" plus "getprop" every 5 s produced thousands of them. "-t 4000" then
# returned 4000 lines spanning the last FIVE SECONDS; the interesting early-boot
# window had been pushed out of the ring by this script's own noise. The kernel
# even reported "audit: backlog limit exceeded ... audit_lost=1043".
# So: grow the buffers first, and drop our own domain on the way out.
export PATH=/system/bin:/system/xbin:/vendor/bin:/vendor/xbin
OUT=/dev/block/by-name/rawdump
logcat -b all -G 16M 2>/dev/null
# One-off SNAPSHOTS at fixed offsets, written once and never overwritten.
# Build 16's dump showed why: after 10 minutes the 16 MB logcat ring had rolled
# past surfaceflinger's and the composer's startup lines, so their failure had
# to be inferred from symptoms. The rolling dump below is capped at 16 MiB so it
# can never reach these. readsys reads the first 32 MiB of rawdump.
#   offset 16 MiB: first pass with uptime >= 40 s
#   offset 24 MiB: first pass with uptime >= 120 s
snapshot() {
    {
        echo "### NX789J SNAPSHOT $2  uptime=$(cat /proc/uptime)"
        echo "### processes"
        ps -A 2>&1
        echo "### key props"
        getprop | grep -iE 'boot_completed|sys.boot|init.svc|bootanim|system_server' 2>&1
        echo "### registered binder services"
        /system/bin/service list 2>&1
        echo "### logcat"
        /system/bin/logcat -b all -d 2>&1 | grep -v vendor_qti_init_shell
        echo "### dmesg"
        dmesg
        echo "### NX789J SNAPSHOT END $2"
    } 2>&1 | head -c 8000000 | dd of=$OUT bs=1048576 seek=$1 2>/dev/null
    sync
}
snap40=
snap120=
i=0
# 120 x ~5 s = ~10 min. Was 40 (~3.5 min) until build 15: once /data mounted, the
# boot got far enough that later stages could outlast the capture window.
while [ $i -lt 120 ]; do
    {
        echo "### NX789J SYSTEM DIAG BEGIN  iteration=$i  uptime=$(cat /proc/uptime)"
        echo "### mounts"
        cat /proc/mounts
        echo "### by-name"
        ls -la /dev/block/by-name/ 2>&1
        echo "### key props"
        getprop | grep -iE 'boot_completed|sys.boot|init.svc|vold|selinux|crypt|usb' 2>&1
        echo "### dmesg"
        dmesg
        echo "### trustlets present?"
        ls -la /vendor/firmware_mnt/ 2>&1
        ls -la /vendor/firmware_mnt/image/ 2>&1 | head -40
        echo "### /data state"
        ls -lad /data 2>&1
        ls -la /data 2>&1 | head -10
        echo "### processes"
        ps -A 2>&1
        echo "### all properties"
        getprop 2>&1
        echo "### tee state"
        ls -la /dev/qseecom /dev/smcinvoke /dev/smcinvoke_dlkm 2>&1
        echo "### loaded modules: $(wc -l < /proc/modules)"
        cat /proc/modules 2>&1
        echo "### registered binder services"
        /system/bin/service list 2>&1
        echo "### logcat (own avc spam filtered out - see header)"
        /system/bin/logcat -b all -d 2>&1 | grep -v vendor_qti_init_shell
        echo "### NX789J SYSTEM DIAG END iteration=$i"
    } 2>&1 | head -c 16000000 > $OUT
    sync
    up=$(cut -d. -f1 /proc/uptime)
    if [ -z "$snap40" ] && [ "$up" -ge 40 ]; then snap40=1; snapshot 16 early-40s; fi
    if [ -z "$snap120" ] && [ "$up" -ge 120 ]; then snap120=1; snapshot 24 early-120s; fi
    i=$((i+1))
    sleep 5
done
