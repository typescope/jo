{
  description = "Development shell for the Jo language toolchain";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { nixpkgs, ... }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];

      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              jdk17
              scala-cli

              nodejs_24
              ruby
              python3

              bash
              coreutils
              curl
              diffutils
              findutils
              git
              gnugrep
              gnused
              gnumake
              which
            ];

            shellHook = ''
              export JAVA_HOME=${pkgs.jdk17}
              export JO_HOME="$PWD"

              echo "Jo dev shell"
              echo "  Java:      $(java -version 2>&1 | head -n 1)"
              echo "  Scala CLI: $(scala-cli version 2>/dev/null | head -n 1)"
              echo "  Node:      $(node --version)"
              echo "  Ruby:      $(ruby --version)"
              echo "  Python:    $(python --version)"
            '';
          };
        });
    };
}
