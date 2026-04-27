{
  description = "Wakes — wave physics & shader injection for Create Aeronautics";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        # NeoForge 1.21.1 needs Java 21. Microsoft OpenJDK matches what the
        # game launcher (PrismLauncher) ships, so dev/runtime stay aligned.
        jdk = pkgs.jdk21;
      in
      {
        devShells.default = pkgs.mkShell {
          name = "wakes-dev";

          packages = [
            jdk
            pkgs.gradle               # one-time wrapper bootstrap (`just wrapper`)
            pkgs.just                 # task runner the project's `justfile` uses
            pkgs.git                  # for the various source-clone helpers
            pkgs.unzip                # used by extraction scripts
            pkgs.curl                 # used by the iris-jar download in build.gradle setup
          ];

          # Make sure JAVA_HOME points at the flake-supplied JDK so gradle and
          # the moddev plugin pick the right runtime.
          shellHook = ''
            export JAVA_HOME="${jdk}"
            export PATH="$JAVA_HOME/bin:$PATH"
            echo "wakes dev shell — java $(${jdk}/bin/java --version | head -1)"
            echo "  ./gradlew build       — full build"
            echo "  just build            — same, via justfile"
            echo "  just install <mods/>  — build + copy to a Minecraft mods folder"
            echo "  just run-client       — NeoForge dev client"
          '';
        };

        # `nix fmt` formats the flake itself.
        formatter = pkgs.nixpkgs-fmt;
      });
}
