# TWRP for the NX789J

The installer's TWRP package (`twrp-2026-09-25`) is
[reminon/twrp_device_nubia_nx789j](https://github.com/reminon/twrp_device_nubia_nx789j)
`twrp-16.0` @ `5aa121c`, built on the TWRP `twrp-16.0` minimal manifest, with two changes:

- `twrp-add-hals.sh` + `twrp-recovery-hals.xml`: declare the HALs recovery starts, so
  decryption (KeyMint/Gatekeeper) works.
- `twrp-adsp-fix2.sh`: start the ADSP remoteproc by name, which brings up USB-C and the
  battery gauge.

`sync-twrp.sh` and `build-twrp.sh` are the sync and build steps (lunch `twrp_NX789J-bp2a-eng`,
`m recoveryimage`). Paths are the author's.
