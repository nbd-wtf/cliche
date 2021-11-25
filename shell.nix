{ jdk ? "openjdk11" }:

let
  pkgs = import nix/pkgs.nix { inherit jdk; };
in
  pkgs.mkShell {
    buildInputs = [
      pkgs.coursier
      pkgs.${jdk}
      pkgs.sbt
      pkgs.jetbrains.idea-community
    ];
  }
