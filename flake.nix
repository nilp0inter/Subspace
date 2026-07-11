{
  description = "Subspace Android PTT scaffold development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    fenix.url = "github:nix-community/fenix";
    fenix.inputs.nixpkgs.follows = "nixpkgs";
    omni-keymap = {
      url = "github:nilp0inter/OmniKeymap/main";
      flake = false;
    };
    sleepwalker = {
      url = "github:nilp0inter/sleepwalker/aeb5adc6c48d3f8f779314b7401964f6c5e5e905";
      flake = false;
    };
  };

  outputs = { nixpkgs, flake-utils, fenix, omni-keymap, sleepwalker, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        androidBuildToolsVersion = "35.0.0";

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ androidBuildToolsVersion ];
          platformVersions = [ "31" "35" ];
          includeEmulator = false;
          includeCmake = false;
          includeSources = false;
          includeSystemImages = false;
          includeNDK = true;
        };

        androidSdk = androidComposition.androidsdk;
        androidNdk = androidComposition.ndk-bundle;

        rustToolchain = fenix.packages.${system}.combine [
          fenix.packages.${system}.stable.toolchain
          fenix.packages.${system}.targets.armv7-linux-androideabi.stable.rust-std
          fenix.packages.${system}.targets.aarch64-linux-android.stable.rust-std
          fenix.packages.${system}.targets.i686-linux-android.stable.rust-std
          fenix.packages.${system}.targets.x86_64-linux-android.stable.rust-std
        ];
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.rtk
            androidSdk
            pkgs.gradle
            pkgs.jdk17
            pkgs.kotlin
            rustToolchain
            pkgs.cargo-ndk
            pkgs.pkg-config
            pkgs.openssl
            (pkgs.python3.withPackages (p: [
              p.huggingface-hub
              p.hf-transfer
            ]))
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          ANDROID_NDK_HOME = "${androidNdk}/libexec/android-sdk/ndk-bundle";
          ANDROID_NDK_ROOT = "${androidNdk}/libexec/android-sdk/ndk-bundle";
          NDK_DIR = "${androidNdk}/libexec/android-sdk/ndk-bundle";
          JAVA_HOME = pkgs.jdk17.home;
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/${androidBuildToolsVersion}/aapt2";
          HF_HUB_ENABLE_HF_TRANSFER = "1";
          OMNI_KEYMAP_PATH = "${omni-keymap}";
          SLEEPWALKER_CORE_PATH = "${sleepwalker}/android/sleepwalker-core";

          shellHook = ''
            export GRADLE_USER_HOME="$PWD/.gradle"
          '';
        };
      });
}
