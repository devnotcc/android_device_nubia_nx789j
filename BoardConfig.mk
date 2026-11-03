#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Device tree for Nubia RedMagic NX789J (board "sun", SM8750).
#
# Every value below is taken from the EDL dump at
#   ZTEToolBoxParBak_2026.09.12_07.24.41.349
# and cross-checked against LineageOS/android_device_oneplus_sm8750-common
# (same SoC, officially supported) — see NX789J_STEP1-2_DUMP_REPORT.md.
#

DEVICE_PATH := device/nubia/nx789j

# -----------------------------------------------------------------------------
# Platform
#   ro.board.platform=sun ; ro.board.api_level=202404 ; ro.product.first_api_level=35
# -----------------------------------------------------------------------------
TARGET_BOARD_PLATFORM := sun
TARGET_BOOTLOADER_BOARD_NAME := sun
TARGET_NO_BOOTLOADER := true

# Vendor is Android 15 / SDK 35 under an Android 16 / SDK 36 system (QSSI).
# Framework must be >= vendor, so the platform builds at SDK 36 and this stays at 35.
BOARD_SHIPPING_API_LEVEL := 35

# VINTF.
#
# Stock ships no vendor/etc/vintf/manifest.xml - it uses manifest_sun.xml via
# ro.boot.product.vendor.sku. checkvintf runs here with an empty vendor sku and
# so demands the unsuffixed name, failing the build with
#   ERROR: Cannot fetch vendor manifest.
# DEVICE_MANIFEST_FILE installs ours at that path.
DEVICE_MANIFEST_FILE := $(DEVICE_PATH)/manifest.xml

# IQesdSys served by FrameworksUtilsSysService (qesdk/), as on stock system_ext.
SYSTEM_EXT_MANIFEST_FILES += $(DEVICE_PATH)/qesdk/vendor.qti.qesdsys.service.xml

# NOTE: do not try to set BOARD_SEPOLICY_VERS here. build/make/core/config.mk:881
# forces it to PLATFORM_SEPOLICY_VERSION and marks it .KATI_READONLY, so a device
# value is silently discarded. 202504 (the platform value) is also the CORRECT one:
# the vendor sepolicy is compiled in-tree against this platform, so it is this
# platform's policy version. Stock says 202404 only because ZTE built their vendor
# against Android 15. The blobs are binaries and carry no policy version of their
# own - only the manifest target-level (202404) reflects what the HALs are.
# NOTE: do NOT set BOARD_API_LEVEL here — board_config.mk rejects it
# ("must not be set manually"). Stock reports ro.board.api_level=202404 and
# ro.board.first_api_level=202404; the build derives the equivalent itself.

# -----------------------------------------------------------------------------
# Architecture — arm64 only. Stock has ro.vendor.product.cpu.abilist32 empty.
# -----------------------------------------------------------------------------
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-2a-dotprod
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_ABI2 :=
TARGET_CPU_VARIANT := generic
TARGET_CPU_VARIANT_RUNTIME := cortex-a76
TARGET_SUPPORTS_32_BIT_APPS := false
TARGET_SUPPORTS_64_BIT_APPS := true

# -----------------------------------------------------------------------------
# Bootloader / kernel
#   boot_a: header v4, kernel 36,596,224 B, ramdisk 0
#   init_boot_a: header v4, ramdisk 2,481,980 B
#   vendor_boot_a: header v4, page_size 4096, vendor_ramdisk 8,596,737 B,
#                  dtb 4,488,075 B, bootconfig 232 B
#   Linux version 6.6.92-android15-8-g3637f4904cf5-ab13944661-4k  (GKI, 4K pages)
# -----------------------------------------------------------------------------
BOARD_USES_GENERIC_KERNEL_IMAGE := true
BOARD_KERNEL_IMAGE_NAME := Image
BOARD_KERNEL_BASE := 0x00000000
BOARD_KERNEL_PAGESIZE := 4096
BOARD_RAMDISK_OFFSET := 0x01000000
BOARD_DTB_OFFSET := 0x01f00000
BOARD_KERNEL_TAGS_OFFSET := 0x00000100
BOARD_BOOT_HEADER_VERSION := 4
# init_boot_a.img is also header v4 (kernel_size 0, ramdisk 2,481,980 B).
# Soong's fsgen requires this explicitly: without it the generated
# *_generated_init_boot_image module fails with "header_version: must be set".
BOARD_INIT_BOOT_HEADER_VERSION := 4

# THIS IS WHAT KEPT THE DEVICE FROM BOOTING (builds 1-7).
#
# BOARD_INIT_BOOT_HEADER_VERSION above only feeds a Soong JSON variable
# (build/make/core/soong_config.mk: BoardInitBootHeaderVersion). It does NOT
# reach the make rule that actually builds init_boot.img, which is
#   build/make/core/Makefile:1582
#     $(MKBOOTIMG) $(INTERNAL_INIT_BOOT_IMAGE_ARGS) \
#                  $(INTERNAL_MKBOOTIMG_VERSION_ARGS) \
#                  $(BOARD_MKBOOTIMG_INIT_ARGS) --output $@
# and INTERNAL_INIT_BOOT_IMAGE_ARGS is only "--ramdisk <x> --pagesize <y>".
# BOARD_MKBOOTIMG_ARGS is NOT used here either - that one is for boot.img.
#
# With no --header_version reaching mkbootimg it defaults to 0, so this port
# shipped a v0 init_boot while stock is v4. ABL could not load the generic
# ramdisk out of a v0 header, so /init never started. Symptoms: splash for well
# under a second then an immediate reset, no init trigger ever reached (the
# rawdump dump was empty even at "on early-init"), and no fatal path taken
# (androidboot.init_fatal_reboot_target=recovery never fired, because init was
# never running to honour it).
#
# Recovery was unaffected throughout: recovery.img carries its own ramdisk and
# never touches init_boot. That is exactly why recovery booted fine on the same
# kernel, same dtb/dtbo and same 304 modules while the system boot died - the
# one component only the system boot uses was the broken one.
BOARD_MKBOOTIMG_INIT_ARGS += --header_version $(BOARD_INIT_BOOT_HEADER_VERSION)
BOARD_MKBOOTIMG_ARGS += --header_version $(BOARD_BOOT_HEADER_VERSION)
BOARD_MKBOOTIMG_ARGS += --pagesize $(BOARD_KERNEL_PAGESIZE)
BOARD_MKBOOTIMG_ARGS += --ramdisk_offset $(BOARD_RAMDISK_OFFSET)
BOARD_MKBOOTIMG_ARGS += --dtb_offset $(BOARD_DTB_OFFSET)
BOARD_MKBOOTIMG_ARGS += --tags_offset $(BOARD_KERNEL_TAGS_OFFSET)

# Verbatim from the stock vendor_boot_a header.
BOARD_KERNEL_CMDLINE := \
    video=vfb:640x400,bpp=32,memsize=3072000 \
    nosoftlockup \
    console=ttynull \
    qcom_geni_serial.con_enabled=0 \
    bootconfig

# Verbatim from the stock vendor_boot_a bootconfig section.
BOARD_BOOTCONFIG := \
    androidboot.hardware=qcom \
    androidboot.memcg=1 \
    androidboot.usbcontroller=a600000.dwc3 \
    androidboot.load_modules_parallel=true \
    androidboot.hypervisor.protected_vm.supported=true \
    androidboot.vendor.qspa=true \
    androidboot.serialconsole=0 \
    androidboot.init_fatal_reboot_target=recovery

# SELinux: ENFORCING. androidboot.selinux=permissive removed. UDFPS HBM write
# is allowed via KSU module nx789j_sepolicy (surfaceflinger -> proc_ztelcd_set).

# Ride the stock GKI kernel + DTB. ZTE publishes kernel source under NX789S but,
# as on the NX809J, it is expected to be incomplete; revisit once boot is stable.
TARGET_NO_KERNEL := false
TARGET_FORCE_PREBUILT_KERNEL := true
TARGET_PREBUILT_KERNEL := $(DEVICE_PATH)/prebuilts/kernel

# The DTB. build/make/core/Makefile builds $(PRODUCT_OUT)/dtb.img by globbing
#   $(BOARD_PREBUILT_DTBIMAGE_DIR)/*.dtb
# and concatenating them, so the blob must live in a DIRECTORY as a *.dtb file.
# (There is no "TARGET_PREBUILT_DTB" variable - a prebuilt dtb.img pointed at by
# such a name is simply ignored, and the build then fails with
#   ninja: 'out/target/product/nx789j/dtb.img', needed by '...recovery.img',
#   missing and no known rule to make it
# because recovery/boot/vendor_boot all depend on INSTALLED_DTBIMAGE_TARGET.)
# prebuilts/dtb/nx789j.dtb is the dtb section lifted out of vendor_boot_a.img.
BOARD_PREBUILT_DTBIMAGE_DIR := $(DEVICE_PATH)/prebuilts/dtb
BOARD_INCLUDE_DTB_IN_BOOTIMG := true
BOARD_PREBUILT_DTBOIMAGE := $(DEVICE_PATH)/prebuilts/dtbo.img
BOARD_INCLUDE_RECOVERY_DTBO := false

# Kernel modules.
#
# THE FIRST BOOT FAILED BECAUSE THIS SECTION WAS COMMENTED OUT. A GKI kernel on
# its own has no UFS host driver, no PMIC/regulator/clock drivers, no display and
# no USB gadget - all of those are vendor modules. The kernel started and then had
# nothing to drive, so the bootloader's splash stayed on screen and USB never
# enumerated. Stock vendor_ramdisk is 8,596,737 bytes; ours was 3,271,301, and the
# 5.3 MB difference was exactly these .ko files.
#
# All three sets are lifted verbatim from the stock images, and the kernel is the
# stock kernel byte-for-byte, so vermagic and module signatures match.
#   prebuilts/modules/vendor_ramdisk/  308 .ko  (from stock vendor_boot_a ramdisk)
#   prebuilts/modules/vendor_dlkm/     281 .ko  (from stock vendor_dlkm)
#   prebuilts/modules/system_dlkm/      94 .ko  (from stock system_dlkm - GKI)
#
# Load orders are the stock modules.load files with duplicate entries removed
# (stock lists vendor_dlkm 420 times over 281 modules; a repeat load is a no-op,
# so keeping the first occurrence preserves the order exactly).
BOARD_VENDOR_RAMDISK_KERNEL_MODULES := \
    $(wildcard $(DEVICE_PATH)/prebuilts/modules/vendor_ramdisk/*.ko)
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD := \
    $(strip $(shell cat $(DEVICE_PATH)/prebuilts/modules/vendor_ramdisk.load))
BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD := \
    $(strip $(shell cat $(DEVICE_PATH)/prebuilts/modules/vendor_ramdisk.load.recovery))

BOARD_VENDOR_KERNEL_MODULES := \
    $(wildcard $(DEVICE_PATH)/prebuilts/modules/vendor_dlkm/*.ko)
BOARD_VENDOR_KERNEL_MODULES_LOAD := \
    $(strip $(shell cat $(DEVICE_PATH)/prebuilts/modules/vendor_dlkm.load))

BOARD_SYSTEM_KERNEL_MODULES := \
    $(wildcard $(DEVICE_PATH)/prebuilts/modules/system_dlkm/*.ko)

BOARD_VENDOR_RAMDISK_KERNEL_MODULES_BLOCKLIST_FILE := \
    $(DEVICE_PATH)/prebuilts/modules/vendor_ramdisk.blocklist
BOARD_VENDOR_KERNEL_MODULES_BLOCKLIST_FILE := \
    $(DEVICE_PATH)/prebuilts/modules/vendor_dlkm.blocklist

# -----------------------------------------------------------------------------
# Partitions (sizes verbatim from rawprogram0/4.xml and the LP metadata)
# -----------------------------------------------------------------------------
# BOARD_KERNEL_PAGESIZE * 64
BOARD_FLASH_BLOCK_SIZE := 262144

BOARD_BOOTIMAGE_PARTITION_SIZE := 100663296
BOARD_VENDOR_BOOTIMAGE_PARTITION_SIZE := 100663296
BOARD_INIT_BOOT_IMAGE_PARTITION_SIZE := 8388608
BOARD_DTBOIMG_PARTITION_SIZE := 25165824
BOARD_RECOVERYIMAGE_PARTITION_SIZE := 104857600

# super: 4194304 sectors * 4096 B
BOARD_SUPER_PARTITION_SIZE := 17179869184
BOARD_SUPER_PARTITION_GROUPS := qti_dynamic_partitions
# super - 4 MiB, matches stock metadata
BOARD_QTI_DYNAMIC_PARTITIONS_SIZE := 17175674880
BOARD_QTI_DYNAMIC_PARTITIONS_PARTITION_LIST := \
    odm \
    product \
    system \
    system_dlkm \
    system_ext \
    vendor \
    vendor_dlkm

# Virtual A/B, userspace snapshots, compression + XOR (from stock vendor build.prop)
BOARD_USES_RECOVERY_AS_BOOT := false
BOARD_MOVE_RECOVERY_RESOURCES_TO_VENDOR_BOOT := false
BOARD_EXCLUDE_KERNEL_FROM_RECOVERY_IMAGE := true
TARGET_NO_RECOVERY := false
BOARD_USES_METADATA_PARTITION := true

# All seven dynamic partitions are real images, so every TARGET_COPY_OUT_* must
# name its own partition rather than defaulting under /system. Omitting
# TARGET_COPY_OUT_PRODUCT fails board_config.mk with
#   "TARGET_COPY_OUT_PRODUCT must be set to 'product' to use a product image."
TARGET_COPY_OUT_VENDOR := vendor
TARGET_COPY_OUT_ODM := odm
TARGET_COPY_OUT_PRODUCT := product
TARGET_COPY_OUT_SYSTEM_EXT := system_ext
TARGET_COPY_OUT_VENDOR_DLKM := vendor_dlkm
TARGET_COPY_OUT_SYSTEM_DLKM := system_dlkm

# -----------------------------------------------------------------------------
# Filesystems
#
# IMPORTANT: the stock fstab (vendor/etc/fstab.qcom, and the identical copy in
# the vendor_boot ramdisk at first_stage_ramdisk/fstab.qcom) gives BOTH an erofs
# and an ext4 line for /system, but ONLY an erofs line for system_ext, product
# and odm.  Since this port rides the stock vendor_boot (and therefore the stock
# fstab), those three partitions MUST be built as EROFS or first_stage_mount
# will not find a matching entry and the device will not boot.
# -----------------------------------------------------------------------------
TARGET_USERIMAGES_USE_EXT4 := true
TARGET_USERIMAGES_USE_F2FS := true

# Every one of the seven dynamic partitions is erofs in the stock fstab, and
# board_config.mk requires an explicit FS type (or a prebuilt image) for each
# partition whose TARGET_COPY_OUT_* is declared.
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_ODMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_VENDOR_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_SYSTEM_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs

# Stock uses lz4 with 4K pclusters and the 0padding feature (confirmed by
# dump.erofs on vendor_a.img: "Filesystem features: sb_csum mtime xattr_filter 0padding").
BOARD_EROFS_COMPRESSOR := lz4hc,9
BOARD_EROFS_PCLUSTER_SIZE := 4096

BOARD_USERDATAIMAGE_FILE_SYSTEM_TYPE := f2fs
TARGET_USERIMAGES_SPARSE_EROFS_DISABLED := false
TARGET_RECOVERY_FSTAB := $(DEVICE_PATH)/rootdir/etc/fstab.qcom
TARGET_RECOVERY_PIXEL_FORMAT := RGBX_8888

# -----------------------------------------------------------------------------
# AVB — vbmeta covers vendor/odm/dlkm; vbmeta_system covers system/system_ext/product
# -----------------------------------------------------------------------------
BOARD_AVB_ENABLE := true
BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += --flags 3
BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += --set_hashtree_disabled_flag
BOARD_AVB_VBMETA_SYSTEM := system system_ext product
BOARD_AVB_VBMETA_SYSTEM_KEY_PATH := external/avb/test/data/testkey_rsa2048.pem
BOARD_AVB_VBMETA_SYSTEM_ALGORITHM := SHA256_RSA2048
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX := $(PLATFORM_SECURITY_PATCH_TIMESTAMP)
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX_LOCATION := 2
# Stock fstab carries avb_keys=/avb/*-gsi.avbpubkey, so the GSI keys must live
# in the vendor ramdisk (same as the OnePlus sm8750 common tree).
BOARD_MOVE_GSI_AVB_KEYS_TO_VENDOR_BOOT := true

# -----------------------------------------------------------------------------
# Display — BF237_RM692H5_6P8 (Raydium RM692H5, 6.8"), 1216x2688, DSC cmd mode,
# modes 60/90/120/144 Hz, 72 mm x 158 mm  => 428.98 x 432.1 dpi
# -----------------------------------------------------------------------------
TARGET_SCREEN_WIDTH := 1216
TARGET_SCREEN_HEIGHT := 2688
TARGET_SCREEN_DENSITY := 520

# Adreno 830 is a Vulkan 1.3 device and the stock vendor sets
# ro.hwui.use_vulkan=true. The build system emits that prop itself from
# TARGET_USES_VULKAN (build/make/core/sysprop_config.mk), unconditionally and
# even when the variable is empty, so it must be set HERE rather than written
# into properties/vendor.prop - doing both makes post_process_props abort with
# "found duplicate sysprop assignments: ro.hwui.use_vulkan=true / =".
TARGET_USES_VULKAN := true

# -----------------------------------------------------------------------------
# Properties
# -----------------------------------------------------------------------------
TARGET_ODM_PROP += $(DEVICE_PATH)/properties/odm.prop
TARGET_SYSTEM_PROP += $(DEVICE_PATH)/properties/system.prop
TARGET_SYSTEM_EXT_PROP += $(DEVICE_PATH)/properties/system_ext.prop
TARGET_VENDOR_PROP += $(DEVICE_PATH)/properties/vendor.prop

# -----------------------------------------------------------------------------
# SELinux
#
# The stock odm ships a precompiled sepolicy (odm/etc/selinux/precompiled_sepolicy,
# 1,561,938 B, + three .sha256 stamps). Framework and vendor are built separately
# here, so that hash can never match and init fails with
# "Failed to initialize property area". It must be stripped from the odm image —
# see the NX809J port's Wall 9b/10. Handled in device.mk / the super assembly step.
# -----------------------------------------------------------------------------
include device/qcom/sepolicy_vndr/SEPolicy.mk
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/private
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/public

# -----------------------------------------------------------------------------
# Assert
# -----------------------------------------------------------------------------
TARGET_OTA_ASSERT_DEVICE := NX789J,nx789j

# Include the proprietary files BoardConfig.
-include vendor/nubia/nx789j/BoardConfigVendor.mk
