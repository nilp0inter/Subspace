{
  description = "Subspace Android PTT scaffold development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { nixpkgs, flake-utils, ... }:
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
          includeNDK = false;
        };

        androidSdk = androidComposition.androidsdk;
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            androidSdk
            pkgs.gradle
            pkgs.jdk17
            pkgs.kotlin
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = pkgs.jdk17.home;
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/${androidBuildToolsVersion}/aapt2";

          shellHook = ''
            export GRADLE_USER_HOME="$PWD/.gradle"
          '';
        };
      });
}
