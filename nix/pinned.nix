{
  nixpkgs = fetchTarball {
    name   = "nixos-unstable-2021-11-17";
    url    = "https://github.com/NixOS/nixpkgs-channels/archive/4762fba469e2baa82f983b262e2c06ac2fdaae67.tar.gz";
    sha256 = "1sidky93vc2bpnwb8avqlym1p70h2szhkfiam549377v9r5ld2r1";
  };

  sbt-derivation = fetchTarball {
    name   = "sbt-derivation-2020-10-08";
    url    = "https://github.com/zaninime/sbt-derivation/archive/9666b2b.tar.gz";
    sha256 = "17r74avh4i3llxbskfjhvbys3avqb2f26pzydcdkd8a9k204rg9z";
  };
}
