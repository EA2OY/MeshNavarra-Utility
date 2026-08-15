# usb-serial-for-android (vendored source)

Vendored copy of [mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
tag `3.11.0` (commit 8e694372dee4531d140766df7af3189d3d9531c5), license **LGPL-2.1** (see `LICENSE.txt`).

- Source code (package `com.hoho.android.usbserial`): `app/src/main/java/com/hoho/android/usbserial/`
- Why vendored: F-Droid's build server does not resolve JitPack dependencies
  (`com.github.mik3y:usb-serial-for-android`); building from in-tree source is
  the supported path.
- Single adaptation vs upstream tag: removed the `BuildConfig.DEBUG` guard in
  `Ch34xSerialDriver` (and its unused import in `ProlificSerialDriver`) — the
  JitPack 3.11.0 release AAR was built with `DEBUG=false`, so the removed
  branch was dead code. No behavioral change.
