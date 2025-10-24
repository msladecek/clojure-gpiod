{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/25.05";
    flake-utils.url = "github:numtide/flake-utils";
    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    lefthook = {
      url = "github:sudosubin/lefthook.nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    inputs@{
      self,
      nixpkgs,
      flake-utils,
      treefmt-nix,
      lefthook,
      ...
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs { inherit system; };
        dependencies = with pkgs; [
          babashka
          clj-kondo
          libgpiod
        ];
        treefmtEval = treefmt-nix.lib.evalModule pkgs {
          projectRootFile = "flake.nix";
          programs.nixfmt.enable = true;
          programs.cljfmt.enable = true;
        };
        lefthook-check = lefthook.lib.${system}.run {
          src = ./.;
          config = {
            colors = false;
            pre-commit.fail_on_changes = "always";
            pre-commit.commands = {
              clj-kondo = {
                run = "clj-kondo --repro --lint {staged_files}";
                glob = [
                  "*.cljc"
                  "*.clj"
                ];
              };
              nix-fmt = {
                run = "nix fmt {staged_files}";
                glob = "*.nix";
              };
            };
            output = [
              "execution"
              "failure"
            ];
          };
        };
      in
      {
        devShells.default = pkgs.mkShell {
          shellHook = lefthook-check.shellHook;
          buildInputs = dependencies;
        };
        formatter = treefmtEval.config.build.wrapper;
      }
    );
}
