#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixups,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

# These become `soong_namespace { imports: [...] }` in the generated
# vendor/nubia/nx789j/Android.bp. A blob module living in the vendor namespace
# can only reference modules in namespaces listed here — PRODUCT_SOONG_NAMESPACES
# alone is not enough. hardware/qcom-caf/wlan and
# vendor/qcom/opensource/dataservices were tried here to satisfy libcld80211 /
# librmnetctl, but building those from source drags in qti_kernel_headers ->
# generated_kernel_includes, which needs a kernel source tree we do not have.
# Both are shipped as blobs instead, so neither namespace is imported.
namespace_imports = [
    'vendor/qcom/opensource/commonsys-intf/display',
]

# Blob fixups.
#
# The vendor partition is Android 15 / SDK 35 while the platform builds at
# SDK 36, so vendor blobs that link versioned AIDL NDK stubs need those bumped
# to whatever the SDK 36 platform provides. Entries below are seeded from the
# equivalent OnePlus sm8750 fixups; each needs confirming against readelf on
# our own blobs before it is trusted.
blob_fixups: blob_fixups_user_type = {
    # Fingerprint: the init rc names a binary that does not exist on the device
    # (vendor/bin/hw/android.hardware.biometrics.fingerprint.aidl-service) and
    # marks it `disabled`. The service that actually provides IFingerprint/default
    # is vendor.zte.fingerprint.ztecmdaidl-service. Drop the dead entry so init
    # does not log a missing-exec error every boot.
    'vendor/etc/init/hw/init.fingerprint.gf95xx.rc': blob_fixup()
        .regex_replace(
            r'service ztecmd /vendor/bin/hw/android\.hardware\.biometrics\.fingerprint\.aidl-service',
            r'service ztecmd /vendor/bin/hw/vendor.zte.fingerprint.ztecmdaidl-service',
        ),
    # ZTE built this HAL against android.hardware.biometrics.fingerprint V5 while
    # V5 was still the in-development version in their Android 15 tree. In the
    # Lineage 23 tree, aidl_api/android.hardware.biometrics.fingerprint/ has
    # frozen dumps 1-4 plus "current" (= V5), and soong refuses to let a vendor
    # module link an unfrozen version. V4 is the right target: the HAL's own
    # VINTF fragment (vendor/etc/vintf/manifest/fingerprint-aidl.xml) already
    # declares <version>4</version>.
    # Audited across all 121 AIDL -ndk libs our blobs link - this is the only one
    # on an unfrozen version.
    'vendor/bin/hw/vendor.zte.fingerprint.ztecmdaidl-service': blob_fixup()
        .replace_needed(
            'android.hardware.biometrics.fingerprint-V5-ndk.so',
            'android.hardware.biometrics.fingerprint-V4-ndk.so',
        ),
    # Soong rejects a dependency graph that pulls two versions of the same
    # platform-generated aidl_interface. A scan of the transitive NEEDED closure
    # of every blob found 35 such graphs in exactly two clusters, and both trace
    # back to a single file each:
    #
    #   29 camera blobs (com.zte.node.*, com.zte.sensor.*_aston, libcamx*,
    #   libcom.qti.chinodeutils, libcommonchiutils, ...) mix
    #   android.hardware.graphics.allocator V1 and V2, all through
    #   libcamximageformatutils.so - which links BOTH V1 and V2 itself.
    #   The OnePlus 13 (dodge) tree carries the identical fixup for this file.
    'vendor/lib64/libcamximageformatutils.so': blob_fixup()
        .replace_needed(
            'android.hardware.graphics.allocator-V1-ndk.so',
            'android.hardware.graphics.allocator-V2-ndk.so',
        ),
    #   6 WiFi-Display blobs (libwfdsessionmodule, libwfdsourcesession_proprietary,
    #   libwfdsourcesm_proprietary, wifidisplayhalservice, ...) mix
    #   android.media.audio.common.types V2 and V3, all through this one file.
    #   V3 is what the rest of the vendor audio stack uses (libquasar,
    #   libhapticeffectsw_aidl).
    'vendor/lib64/libwfdmmsrc_proprietary.so': blob_fixup()
        .replace_needed(
            'android.media.audio.common.types-V2-ndk.so',
            'android.media.audio.common.types-V3-ndk.so',
        ),
    # libqcodec2_core is the only blob linking android.hardware.graphics.common,
    # and it ships on V5. V5 is frozen here (aidl_api has 1-6 + current), so this
    # is not the unfrozen case - the clash is with the version the platform's own
    # codec2 stack pulls in:
    #     libqcodec2_core -> libcodec2_vndk -> libgralloctypes -> common-V7-ndk
    # so the target is V7 (= "current"), NOT the newest frozen version. dodge
    # applies the identical V5 -> V7 replacement.
    'vendor/lib64/libqcodec2_core.so': blob_fixup()
        .replace_needed(
            'android.hardware.graphics.common-V5-ndk.so',
            'android.hardware.graphics.common-V7-ndk.so',
        ),
    # The stock display composer links graphics.composer3 V3 while the platform
    # side of its graph pulls V4, so soong rejects it with
    #   "depends on multiple versions of the same aidl_interface:
    #    android.hardware.graphics.composer3-V3-ndk-source, ...-V4-ndk-source"
    # aidl_api has 1-4 frozen plus current, and V4 is what the platform uses.
    #
    # This blob matters: without it soong builds the composer from
    # hardware/qcom-caf/sm8750/display/hal/qmaa, which is the QMAA *stub*, and
    # the device would come up with a non-functional display HAL.
    'vendor/bin/hw/vendor.qti.hardware.display.composer-service': blob_fixup()
        .replace_needed(
            'android.hardware.graphics.composer3-V3-ndk.so',
            'android.hardware.graphics.composer3-V4-ndk.so',
        ),
}  # fmt: skip

module = ExtractUtilsModule(
    'nx789j',
    'nubia',
    namespace_imports=namespace_imports,
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    # No proprietary-firmware.txt yet: the radio/bootloader images (modem, abl,
    # xbl, tz ...) are already on the device and are not reflashed for a first
    # boot. Revisit when packaging a full installable ROM.
    add_firmware_proprietary_file=False,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
