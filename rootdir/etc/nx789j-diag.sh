#!/system/bin/sh
# NX789J recovery diagnostics -> raw logdump partition.
#
# WHY: this port has no working USB in recovery yet, and recovery's on-screen
# "View recovery logs" shows only /tmp/recovery.log - it never contains the
# KERNEL log. So there is currently no way to see dmesg, which module list
# actually loaded, or whether a USB device controller exists at all.
#
# logdump is 512 MiB of scratch (LUN4, sector 699259, ext4 and "formattable"
# in fstab.qcom, not a security partition). Writing raw text over it loses
# nothing, and it can be read straight back over EDL with fh_loader, which is
# tooling this port already has.
sleep 20
OUT=/dev/block/by-name/logdump
MISC=/dev/block/by-name/misc
{
    echo "### NX789J RECOVERY DIAG BEGIN"
    echo "### uname"
    uname -a
    echo "### module count"
    wc -l < /proc/modules
    echo "### which load list (107=modules.load, 304=modules.load.recovery)"
    echo "### modules"
    cat /proc/modules
    echo "### /sys/class/udc"
    ls -la /sys/class/udc/ 2>&1
    echo "### gadget UDC value"
    cat /config/usb_gadget/g1/UDC 2>&1
    echo "### gadget dir"
    ls -la /config/usb_gadget/g1/ 2>&1
    echo "### gadget configs/b.1"
    ls -la /config/usb_gadget/g1/configs/b.1/ 2>&1
    echo "### gadget functions"
    ls -la /config/usb_gadget/g1/functions/ 2>&1
    echo "### usb props"
    getprop | grep -i usb
    echo "### MISC / BCB (init writes its failure reason here via reboot_into_recovery)"
    dd if=$MISC bs=1 count=2048 2>/dev/null | od -c | head -40
    echo "--- printable strings in the BCB ---"
    dd if=$MISC bs=1 count=2048 2>/dev/null | strings 2>/dev/null ||         dd if=$MISC bs=1 count=2048 2>/dev/null | tr -c '[ -~]' '\n' | grep -E '.{4,}'
    echo "### END MISC"
    echo "### PSTORE (kernel log of the PREVIOUS boot - survives a warm reboot)"
    ls -la /sys/fs/pstore/ 2>&1
    for f in /sys/fs/pstore/*; do
        echo "--- $f ---"
        cat "$f" 2>&1
    done
    echo "### END PSTORE"
    echo "### dmesg"
    dmesg
    echo "### NX789J RECOVERY DIAG END"
} > $OUT 2>&1
sync

# Re-arm the BCB so the NEXT failed system boot lands back here automatically.
#
# WHY: ramoops lives in reserved DRAM. It survives a WARM reboot (a failed boot
# rebooting itself) but a cold power cycle erases it - and entering EDL IS a cold
# cycle. So "boot, fail, go to EDL, read" can never capture the failed boot, and
# the key combo does not reliably catch it either. Instead: leave "boot-recovery"
# in the bootloader control block, so a failed boot warm-reboots straight back
# into recovery with pstore intact, and this script then dumps it.
#
# Recovery clears the BCB at startup (and again when ADB is enabled), so write it
# repeatedly rather than once - ~7 minutes of coverage, which is plenty of time
# to pick "Reboot system now".
#
# Only the 13-byte command string is written, at offset 0. Recovery has just
# zeroed that field, so the rest of the command buffer stays zero and the string
# is properly terminated. Slot metadata lives elsewhere entirely (verified: the
# BCB region and bootloader_control at offset 2048 are zero in the stock dump).
#
# TO STOP returning to recovery: run  bcb.bat /clear  from EDL.
i=0
while [ $i -lt 200 ]; do
    printf 'boot-recovery' > $MISC 2>/dev/null
    sync
    i=$((i+1))
    sleep 2
done
