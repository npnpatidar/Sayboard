# Nix shell providing JDK 17 + Android SDK for building Sayboard on NixOS (aarch64).
#
# Pinned toolchain (B32/S33 — keep in sync with CI and app/build.gradle):
# - JDK: Temurin/JDK 17 (AGP 9.x requires JDK 17)
# - Android compileSdk: android-37.1 / platform "37"
# - Android build-tools: 37.0.0
# - Gradle wrapper: 9.6.1 (see gradle/wrapper/gradle-wrapper.properties)
#
# nixpkgs pinning: this shell still tracks floating <nixpkgs>. To pin
# honestly, resolve a nixpkgs rev and its fetchTarball sha256, e.g.:
#   NIXPKGS_REV=<rev>; curl -fsSL "https://github.com/NixOS/nixpkgs/archive/$NIXPKGS_REV.tar.gz" -o nixpkgs.tar.gz; sha256sum nixpkgs.tar.gz
# then replace `import <nixpkgs>` with:
#   import (builtins.fetchTarball { url = "https://github.com/NixOS/nixpkgs/archive/<rev>.tar.gz"; sha256 = "<hash>"; })
# No rev+sha256 is written here because none was resolved during this change
# (hashes must never be invented).
#
# Usage:
#   nix-shell                       # first run downloads SDK components
#   nix-shell --run "./gradlew compileDebugKotlin"
#   nix-shell --run "./gradlew assembleDebug"
#
# Notes:
# - AGP 9.x requires JDK 17.
# - Google does not publish an aarch64-linux aapt2 via Maven, and nixpkgs'
#   build-tools for aarch64 also bundle the x86_64 binary. We work around
#   this by running aapt2 under qemu-user emulation (with the x86_64 glibc
#   from pkgsCross) and handing the wrapper to AGP via
#   android.aapt2FromMavenOverride.
# - Everything else in the build (kotlin, d8/r8, apksigner, zipflinger) is
#   pure Java, so no other emulation is needed.
{ pkgs ? import <nixpkgs> { config = { allowUnfree = true; android_sdk.accept_license = true; }; } }:

let
  jdk = pkgs.jdk17;

  androidSdk = (pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "37" ];
    buildToolsVersions = [ "37.0.0" ];
    includeNDK = false;
    includeEmulator = false;
    includeSources = false;
    includeSystemImages = false;
  }).androidsdk;

  aapt2x86 =
    "${androidSdk}/libexec/android-sdk/build-tools/37.0.0/aapt2";

  # x86_64 glibc so qemu-user can load/resolve the x86_64 aapt2 binary
  glibc64 = pkgs.pkgsCross.gnu64.glibc;

  # Wrapper AGP will invoke instead of the Maven aapt2 binary
  aapt2Wrapper = pkgs.writeShellScript "aapt2" ''
    exec ${pkgs.qemu}/bin/qemu-x86_64 -L ${glibc64} ${aapt2x86} "$@"
  '';
in
pkgs.mkShell {
  name = "sayboard-dev";

  buildInputs = [
    jdk
    androidSdk
    pkgs.unzip
    pkgs.git
  ];

  JAVA_HOME = "${jdk}";
  ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";

  shellHook = ''
    # Hand AGP an aarch64-executable aapt2 (Google's Maven artifact is x86_64-only)
    export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2Wrapper} $GRADLE_OPTS"
    export PATH="${jdk}/bin:$PATH"
    echo "Java:   $(java -version 2>&1 | head -1)"
    echo "SDK:    $ANDROID_SDK_ROOT"
    echo "aapt2:  ${aapt2Wrapper} (qemu-emulated x86_64)"
  '';
}
