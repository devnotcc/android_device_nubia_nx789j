#!/system/bin/sh
# Tells NubiaParts whether Debian (the nx789j-linux install, /data/nx-linux) can be started.
# Run by init as root (service nubia_linux_status, init.nubia.parts.rc) in its own read-only
# domain (sepolicy/private/nx789j_parts.te): the app runs as system and cannot see this
# root-only directory itself.
#
#   sys.nubia.linux_status = ready     image, launcher and boot hook in place, hook ran before
#                          = nohook    files there, but the boot hook has never run (no KernelSU
#                                      boot scripts?), so a restart would come back to Android
#                          = missing   not installed
#   sys.nubia.linux_mode   = what the boot hook will do next boot: android | linux | linux-once
B=/data/nx-linux
st=missing
if [ -f $B/rootfs.ext4 ] && [ -f $B/nx-linux.sh ] && [ -f $B/nx-linux-boot.sh ]; then
	# the hook (/data/adb/post-fs-data.d/nx-linux-boot.sh) writes this trace on every boot
	if [ -f $B/boot-trace.log ]; then st=ready; else st=nohook; fi
fi
mode=android
[ -f $B/bootmode ] && read -r mode < $B/bootmode
case "$mode" in linux | linux-once) ;; *) mode=android ;; esac
setprop sys.nubia.linux_mode "$mode"
setprop sys.nubia.linux_status "$st"
