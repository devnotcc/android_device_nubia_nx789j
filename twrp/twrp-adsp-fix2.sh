#!/bin/bash
set -e
T=/mnt/los23/twrp/device/nubia/NX789J/recovery/root
RC=$T/init.recovery.qcom.rc
cat > $T/system/bin/nx789j-boot-adsp.sh <<'SH'
#!/system/bin/sh
# NX789J TWRP: boot the ADSP so qti_battery_charger (pmic_glink) can create
# /sys/class/power_supply/battery. The kernel's early direct load fails
# (firmware partition not mounted yet) and the old /sys/kernel/boot_adsp node
# does not exist on this 6.6 kernel. Look the remoteproc up by name and retry
# until it is running. Log: /tmp/nx789j-adsp.log
L=/tmp/nx789j-adsp.log
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    for r in /sys/class/remoteproc/remoteproc*; do
        case "$(cat $r/name 2>/dev/null)" in
            *remoteproc-adsp)
                s=$(cat $r/state)
                echo "try $i: $r state=$s fw=$(ls /vendor/firmware_mnt/image/adsp.mdt 2>&1)" >> $L
                [ "$s" = running ] && { echo "ADSP running" >> $L; exit 0; }
                echo start > $r/state 2>> $L || echo "start write failed" >> $L
                ;;
        esac
    done
    sleep 2
done
echo "gave up" >> $L
SH
chmod 0755 $T/system/bin/nx789j-boot-adsp.sh
python3 - "$RC" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old="""    exec - root root -- /system/bin/sh /system/bin/nx789j-boot-adsp.sh
    wait /sys/class/power_supply/battery 15
    start vendor.health-default
"""
new="""    start vendor.nx789j-adsp
    wait /sys/class/power_supply/battery 30
    start vendor.health-default
"""
assert old in s, "pattern not found"
s=s.replace(old,new)
s += """
# Oneshot ADSP starter (see /system/bin/nx789j-boot-adsp.sh). An inline
# "exec" from the twrp.modules.loaded trigger never ran on this recovery;
# a oneshot service with the recovery seclabel (as vendor.qti.vibrator above) does.
service vendor.nx789j-adsp /system/bin/sh /system/bin/nx789j-boot-adsp.sh
    user root
    group root
    oneshot
    disabled
    seclabel u:r:recovery:s0
"""
open(p,'w').write(s)
PY
grep -n "nx789j-adsp\|power_supply/battery" $RC
