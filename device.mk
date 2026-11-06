#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

DEVICE_PATH := device/nubia/nx789j

# Add common definitions for Qualcomm
$(call inherit-product, hardware/qcom-caf/common/common.mk)

# Virtual A/B with compression + XOR.
# Stock: ro.virtual_ab.enabled / .compression.enabled / .compression.xor.enabled /
#        .userspace.snapshots.enabled / .io_uring.enabled / .batch_writes = true
$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota/launch_with_vendor_ramdisk.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota/compression_with_xor.mk)

# Enforce generic ramdisk allow list
$(call inherit-product, $(SRC_TARGET_DIR)/product/generic_ramdisk.mk)

# Dalvik heap — 12/16 GB device, 1216x2688 panel
$(call inherit-product, frameworks/native/build/phone-xhdpi-6144-dalvik-heap.mk)

# API levels. Vendor is SDK 35 / FCM 202404; the platform builds at SDK 36.
PRODUCT_SHIPPING_API_LEVEL := 35
PRODUCT_ENABLE_UFFD_GC := true
PRODUCT_SET_DEBUGFS_RESTRICTIONS := true

# VINTF: framework compatibility matrix for the vendor's own HALs.
#
# The stock VINTF fragments we ship declare 45 vendor.qti.* / vendor.zte.* AIDL
# instances that no AOSP framework matrix mentions, which makes check_vintf_all
# report INCOMPATIBLE. All are optional=true - the framework must tolerate a
# missing vendor service rather than require it.
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE := $(DEVICE_PATH)/framework_compatibility_matrix.xml

# The prebuilt kernel is our GKI build with CONFIG_FHANDLE=y (systemd needs
# open_by_handle_at for the Debian install), which the 202404 kernel matrix
# lists as "n". That requirement is only enforced on OTA; the kernel boots
# Android fine, so skip the kernel-config half of the build-time check.
PRODUCT_OTA_ENFORCE_VINTF_KERNEL_REQUIREMENTS := false

# Dynamic partitions / super.
#
# BoardConfig.mk declares BOARD_SUPER_PARTITION_SIZE, the
# qti_dynamic_partitions group and all 7 partitions, but that is only the
# BOARD half. Without this PRODUCT flag the build emits no partition_size
# for the dynamic images AND does not set PRODUCT_USE_DYNAMIC_PARTITION_SIZE,
# so build_image.py aborts with "KeyError: 'partition_size'" on vendor,
# product and system_ext. It also gates building super.img at all.
PRODUCT_USE_DYNAMIC_PARTITIONS := true

# A/B — partition list verbatim from ro.vendor.build.ab_ota_partitions
AB_OTA_UPDATER := true
AB_OTA_PARTITIONS += \
    boot \
    init_boot \
    vendor_boot \
    dtbo \
    recovery \
    odm \
    product \
    system \
    system_dlkm \
    system_ext \
    vendor \
    vendor_dlkm \
    vbmeta \
    vbmeta_system

PRODUCT_PACKAGES += \
    android.hardware.boot-service.default_recovery \
    otapreopt_script \
    update_engine \
    update_engine_sideload \
    update_verifier

# Overlays
DEVICE_PACKAGE_OVERLAYS += $(DEVICE_PATH)/overlay
PRODUCT_PACKAGES += \
    NubiaCarrierConfigResTarget \
    NubiaFrameworksResTarget \
    NubiaSettingsResTarget \
    NubiaSystemUIResTarget \
    NubiaTelephonyResTarget

# IMS (VoLTE/VoWiFi): Qualcomm ImsService + QtiTelephony from the stock system_ext,
# see qti-telephony/, plus their open-source libraries from vendor/codeaurora/telephony.
PRODUCT_PACKAGES += \
    ims \
    qcrilmsgtunnel \
    QtiTelephony \
    QtiTelephonyService \
    qti_telephony_system_packages_config.xml \
    telephony_system-ext_privapp-permissions-qti.xml

PRODUCT_PACKAGES += \
    extphonelib \
    extphonelib.xml \
    ims-ext-common \
    ims_ext_common.xml \
    qti-telephony-hidl-wrapper \
    qti_telephony_hidl_wrapper.xml \
    qti-telephony-utils \
    qti_telephony_utils.xml

# AIDL audio HAL vendor extension: carries vendor parameters (call_state/vsid for VoLTE,
# BT offload reconfig, ...) from audioserver to the HAL; needs
# ro.audio.ihaladaptervendorextension_enabled (system_ext.prop). Stock had both.
PRODUCT_PACKAGES += \
    qtiaudiohalvendorextn

# DTS sound (stock com.dts.dtsxultra): drives the vendor DTS effect on the speakers,
# see dts/. Needs android.media.audiofx.DtsAudio (patches/evox/frameworks_base_dtsaudio.diff).
PRODUCT_PACKAGES += \
    DtsUltraMagic

# Stock RedMagic camera (com.android.camera), see nubiacamera/.
PRODUCT_PACKAGES += \
    NubiaCamera

# QTI QESDK system service (IQesdSys) that the stock vendor qesdk-secmanager polls for,
# see qesdk/.
PRODUCT_PACKAGES += \
    FrameworksUtilsSysService

# Under-display fingerprint handshake (replaces ZTE FingerprintSensorUi_ZTE and
# the services.jar UdfpsDecoupleController hooks) - see NubiaUdfps/.
PRODUCT_PACKAGES += \
    NubiaUdfps \
    init.nubia.udfps.rc

# RedMagic side slider, cooling fan and performance modes: settings app and
# controller service, see NubiaParts/. Needs the InputManagerService slider hook
# in frameworks/base (patches/evox/frameworks_base_nubia_game_switch.diff). The
# thermal profiles and the rc implement the performance modes.
PRODUCT_PACKAGES += \
    NubiaParts \
    init.nubia.parts.rc \
    nubia-linux-status \
    nubia-thermal-engine-performance.conf \
    nubia-thermal-engine-extreme.conf \
    nubia-audio-policy-volumes.xml \
    nubia-thermal-engine-ultra.conf

# Google apps: MindTheGapps, Android 16 branch "baklava", synced to vendor/gapps
# (gitlab.com/MindTheGapps/vendor_gapps, commit f8cdcffc, 2026-06-12).
$(call inherit-product-if-exists, vendor/gapps/arm64/arm64-vendor.mk)

# Permissions / feature declarations that match the stock vendor manifest
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.bluetooth.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth.xml \
    frameworks/native/data/etc/android.hardware.bluetooth_le.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth_le.xml \
    frameworks/native/data/etc/android.hardware.camera.flash-autofocus.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.flash-autofocus.xml \
    frameworks/native/data/etc/android.hardware.camera.full.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.full.xml \
    frameworks/native/data/etc/android.hardware.camera.raw.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.raw.xml \
    frameworks/native/data/etc/android.hardware.fingerprint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.fingerprint.xml \
    frameworks/native/data/etc/android.hardware.nfc.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.nfc.xml \
    frameworks/native/data/etc/android.hardware.nfc.hce.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.nfc.hce.xml \
    frameworks/native/data/etc/android.hardware.opengles.aep.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.opengles.aep.xml \
    frameworks/native/data/etc/android.hardware.sensor.accelerometer.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.accelerometer.xml \
    frameworks/native/data/etc/android.hardware.sensor.gyroscope.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.gyroscope.xml \
    frameworks/native/data/etc/android.hardware.sensor.light.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.light.xml \
    frameworks/native/data/etc/android.hardware.sensor.proximity.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.proximity.xml \
    frameworks/native/data/etc/android.hardware.sensor.stepcounter.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.stepcounter.xml \
    frameworks/native/data/etc/android.hardware.telephony.gsm.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.telephony.gsm.xml \
    frameworks/native/data/etc/android.hardware.telephony.ims.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.telephony.ims.xml \
    frameworks/native/data/etc/android.hardware.usb.accessory.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.accessory.xml \
    frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.host.xml \
    frameworks/native/data/etc/android.hardware.vulkan.level-1.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.level-1.xml \
    frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml \
    frameworks/native/data/etc/android.hardware.wifi.direct.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.direct.xml \
    frameworks/native/data/etc/android.hardware.wifi.passpoint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.passpoint.xml \
    frameworks/native/data/etc/android.software.midi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.midi.xml \
    frameworks/native/data/etc/handheld_core_hardware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/handheld_core_hardware.xml

# IR — ro.hardware.consumerir=zte, driver zte_ir.ko, HAL android.hardware.ir-service
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.consumerir.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.consumerir.xml

# Fingerprint — Goodix G7 / gf95xx UDFPS.
# NOTE: the real HAL binary is vendor.zte.fingerprint.ztecmdaidl-service, NOT the
# AOSP-named android.hardware.biometrics.fingerprint.aidl-service that
# init.fingerprint.gf95xx.rc references (that file does not exist on the device).
PRODUCT_PACKAGES += \
    android.hardware.biometrics.fingerprint@2.1.vendor

# Health
PRODUCT_PACKAGES += \
    android.hardware.health-service.qti \
    android.hardware.health-service.qti_recovery

# RRO / Lineage
PRODUCT_PACKAGES += \
    LineageParts

# Soong namespaces.
#
# This port rides the stock vendor partition for every QTI HAL, so it imports no
# QTI source namespace at all:
#   hardware/qcom-caf/wlan, vendor/qcom/opensource/dataservices
#     -> provide libcld80211 / librmnetctl, but building them pulls
#        qti_kernel_headers -> generated_kernel_includes, which needs a kernel
#        source tree at kernel/nubia/nx789j that we do not have (prebuilt GKI).
#   hardware/qcom-caf/sm8750
#     -> importing it makes its display/audio/ipa SOURCE modules reachable.
#        Those hit the same kernel-header wall, and the composer source module
#        additionally fails with "depends on multiple versions of the same
#        aidl_interface: graphics.composer3 V3 + V4" regardless of our prebuilt.
# Everything they would have provided is shipped as a blob instead.
PRODUCT_SOONG_NAMESPACES += \
    $(DEVICE_PATH)

# Init
PRODUCT_PACKAGES += \
    fstab.qcom \
    fstab.qcom.vendor_ramdisk \
    init.nx789j.rc \
    init.nx789j.system_ext.rc

# vndservicemanager - the service manager for /dev/vndbinder.
#
# build/make/target/product/base_vendor.mk only adds it under
# PRODUCT_PACKAGES_SHIPPING_API_LEVEL_29, i.e. for devices that LAUNCHED on
# API <= 29. PRODUCT_SHIPPING_API_LEVEL is 35 here, so it was silently dropped.
# QTI vendor HALs still use vndbinder. Build 16 (2026-09-13), with the display
# stack otherwise up (composer3 + allocator registered, surfaceflinger running):
#   vendor.qti.hardware.display.composer-service:
#     W libbinder.ProcessState: Not able to get context object on /dev/vndbinder.
#     E BackendUnifiedServiceManager: Waiting 1s on context object(s) on /dev/vndbinder
#   system_server:
#     W Watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler on main
#       thread (main) for 65s
#     at android.view.SurfaceControl.nativeGetCompositionDataspaces(Native Method)
#     at com.android.server.display.DisplayManagerService.<init>
# composer waits on vndbinder -> surfaceflinger never answers -> system_server
# blocks in DisplayManagerService and is killed; sys.system_server.start_count=9.
# Stock ships /vendor/bin/vndservicemanager and its rc.
PRODUCT_PACKAGES += \
    vndservicemanager \
    vndservice

# ueventd.rc - device-node owners and modes for every vendor driver. Without
# it /dev/smcinvoke is 0600 root:root and qseecomd cannot get a TEE client
# environment. See the header of rootdir/etc/ueventd.qcom.rc.
PRODUCT_COPY_FILES += $(DEVICE_PATH)/rootdir/etc/ueventd.qcom.rc:$(TARGET_COPY_OUT_VENDOR)/etc/ueventd.rc

# init.qcom.rc - the import hook for everything under /vendor/etc/init/hw/.
# Without it init.qti.kernel.rc is never parsed and the 281 vendor_dlkm kernel
# modules are never loaded, which takes down the whole TEE stack. See the long
# comment at the top of rootdir/etc/init.qcom.rc.
PRODUCT_COPY_FILES += $(DEVICE_PATH)/rootdir/etc/init.qcom.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.qcom.rc
# Stock init.target.rc, verbatim - imported by init.qcom.rc (see its header).
PRODUCT_COPY_FILES += $(DEVICE_PATH)/rootdir/etc/init.target.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.target.rc

# NOTE: do not PRODUCT_COPY_FILES the fstab to
# $(TARGET_COPY_OUT_RECOVERY)/root/system/etc/recovery.fstab - BoardConfig.mk
# already sets TARGET_RECOVERY_FSTAB, and the build system installs it from
# there. Doing both makes kati fail with "overriding commands for target
# .../recovery/root/system/etc/recovery.fstab".

# Recovery USB - see rootdir/etc/init.recovery.qcom.rc for why this single
# property is all recovery needs. bootable/recovery/etc/init.rc imports
# /init.recovery.$(ro.hardware).rc, so it must land at the recovery ramdisk root.
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/etc/init.recovery.qcom.rc:$(TARGET_COPY_OUT_RECOVERY)/root/init.recovery.qcom.rc \
    $(DEVICE_PATH)/rootdir/etc/nx789j-diag.sh:$(TARGET_COPY_OUT_RECOVERY)/root/nx789j-diag.sh

# System-boot diagnostics (rootdir/etc/nx789j-diag.rc + nx789j-diag-system.sh)
# were removed in build 34: Android boots reliably, and the script rewrote the
# rawdump partition every 5 s for 10 minutes after every boot. The files stay in
# rootdir/etc for the next bring-up emergency; re-add the two copy lines to use.

# Expose aux cameras (ultrawide etc.) to Aperture - see rootdir/etc/nx789j-camera.rc.
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/etc/nx789j-camera.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/nx789j-camera.rc \
    $(DEVICE_PATH)/rootdir/etc/nx789j-fingerprint.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/nx789j-fingerprint.rc

# Mount points for fstab.qcom that live on the READ-ONLY vendor image.
#
# /vendor is erofs, so init cannot mkdir a mount point there at runtime - it has
# to exist in the image. Stock's vendor ships these as empty directories.
#
# hardware/qcom-caf/common already defines them with LineageOS's "mkdir" Soong
# module type; they are simply not in PRODUCT_PACKAGES by default. Adding them
# here is all that is needed - do NOT reach for PRODUCT_COPY_FILES (Soong's
# filesystem creator rejects vendor/firmware_mnt) or install_symlink (collides
# with these same modules).
PRODUCT_PACKAGES +=     vendor_firmware_mnt_mountpoint     vendor_dsp_mountpoint     vendor_bt_firmware_mountpoint


# Display AIDL -ndk libraries: shipped as blobs with renamed modules.
# See the long note in proprietary-files.txt. They cannot be installed from
# here: vendor.qti.hardware.display.*-ndk[.vendor] is not a make module and not
# even a ninja target, so PRODUCT_PACKAGES rejects every spelling of it
# ("includes non-existent modules in PRODUCT_PACKAGES"). Soong references the
# .vendor variant in LOCAL_SHARED_LIBRARIES but never exports it as installable,
# and a prebuilt's shared_libs does not create an install dependency.

# config.fs (QTI vendor AIDs)
TARGET_FS_CONFIG_GEN += $(DEVICE_PATH)/config.fs

# Inherit the proprietary blobs
$(call inherit-product-if-exists, vendor/nubia/nx789j/nx789j-vendor.mk)
