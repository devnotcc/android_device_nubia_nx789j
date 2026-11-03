#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Evolution X (bka) flags. Plain LineageOS ignores all of these. They must be
# set BEFORE common_full_phone.mk is inherited: vendor/lineage/config/evolution.mk
# and common.mk read them with ?= / ifeq at inherit time.
#  - FP override OFF: keep the stock nubia BuildFingerprint below (the one the
#    KSU + TrickyStore + PIF stack was validated with) instead of EvoX's Pixel
#    "stallion" spoof; with both set the winning value is not well defined.
#  - Screen size for the boot animation (BoardConfig.mk is read too late).
#  - No Pixel Quick Tap hardware.
TARGET_ENABLE_FP_OVERRIDE := false
TARGET_SCREEN_WIDTH := 1216
TARGET_SCREEN_HEIGHT := 2688
TARGET_SUPPORTS_QUICK_TAP := false

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit from nx789j device
$(call inherit-product, device/nubia/nx789j/device.mk)

# Inherit some common Lineage stuff.
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

PRODUCT_NAME := lineage_nx789j
PRODUCT_DEVICE := nx789j
PRODUCT_MANUFACTURER := nubia
PRODUCT_BRAND := nubia
PRODUCT_MODEL := NX789J

PRODUCT_GMS_CLIENTID_BASE := android-zte

# Stock identity, verbatim from the dump:
#   system : nubia/NX789J-EEA/NX789J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260206.180332:user/release-keys
#   vendor : nubia/NX789J-EEA/NX789J:15/AQ3A.240812.002/20260206.112216:user/release-keys
#   ro.build.description=qssi_64-user 16 BQ2A.250705.001-BP2A.250605.031.A3 20260206.180332 release-keys
PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="qssi_64-user 16 BQ2A.250705.001-BP2A.250605.031.A3 20260206.180332 release-keys" \
    BuildFingerprint=nubia/NX789J-EEA/NX789J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260206.180332:user/release-keys \
    DeviceName=NX789J \
    DeviceProduct=NX789J-EEA \
    SystemDevice=NX789J \
    SystemName=NX789J-EEA

# ---------------------------------------------------------------------------
# adb: enabled at boot, authorisation REQUIRED (ro.adb.secure=1, Lineage default).
# Build 34 dropped the bring-up "ro.adb.secure=0" (any computer got adb without a
# prompt). The development PC stays trusted through its public key in
# security/adb_keys, read by adbd via /adb_keys -> /product/etc/security/adb_keys.
# PRODUCT_ADB_KEYS is not usable here: build/soong/etc/adb_keys.go skips the
# install unless the build is debuggable, and this one is not (see below).
#
# History of the bring-up setting:
# vendor/lineage/config/common.mk:43 sets PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG
# := true, so LineageOS userdebug builds write ro.debuggable=0 into
# system/build.prop and keep ro.adb.secure=1; adb is meant to be switched on
# from Developer options. Build 16's dump showed exactly that on-device:
#   ro.build.type=userdebug  ro.debuggable=0  ro.adb.secure=1
#   persist.sys.usb.config=none  init.svc.adbd=stopped
# During bring-up there is no UI to reach Developer options, so adb could never
# start even if the USB gadget works. A value set directly in the product
# makefile wins over the one inherited from common_full_phone.mk.
# ro.adb.secure / usb config go in PRODUCT_PRODUCT_PROPERTIES: Lineage already
# emits ro.adb.secure=1 into system_ext/etc/build.prop, and /product is loaded
# after /system_ext, so the later value takes effect.
# NOTE: "PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG := false" was tried here and does
# NOTHING. build/soong reads it through build/make/common/json.mk:25
#   add_json_bool = ... $(if $(strip $(2)),true,false)
# so the STRING "false" is non-empty and becomes true (verified in
# out/soong/soong.lineage_nx789j.extra.variables). Setting it empty does not work
# either: it is a single-value product var, so an empty value falls back to
# vendor/lineage's inherited "true". Net effect on this build: ro.debuggable=0,
# i.e. no "adb root" - but adbd itself only needs the two properties below.
PRODUCT_PRODUCT_PROPERTIES += \
    persist.sys.usb.config=adb

# Local builds only: security/ is not in the public tree, and a public build must not trust any
# computer without the on-screen prompt.
ifneq ($(wildcard device/nubia/nx789j/security/adb_keys),)
PRODUCT_COPY_FILES += \
    device/nubia/nx789j/security/adb_keys:$(TARGET_COPY_OUT_PRODUCT)/etc/security/adb_keys
endif
