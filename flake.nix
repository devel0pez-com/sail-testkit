{
  description = "sail-testkit - run a Sail Spark Connect server from JVM tests";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    devshell.url = "github:numtide/devshell";
    devshell.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, flake-utils, devshell }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ devshell.overlays.default ];
        };

        # Spark 4 is compiled with `maven.compiler.release=17`: anything older
        # will not even link against it.
        jdk = pkgs.jdk17;

        # Same file build.sbt reads, so the client and the server can never
        # drift apart.
        versions = builtins.fromJSON (builtins.readFile ./versions.json);

        python = pkgs.python312;

        # Sail is a Rust binary shipped as a Python wheel. nixpkgs does have
        # `pysail`, but it builds it from source, ships no darwin binary for it
        # and pairs it with an older `pyspark`, so this venv is a decision and
        # not a workaround — the reasoning is in AGENTS.md. `pyspark` goes in
        # next to it on purpose: Sail reads the Spark version from that module,
        # and without it `spark.version` fails with ModuleNotFoundError.
        venvSail = ''
          venv="$PRJ_ROOT/.venv"
          want="pysail==${versions.pysail} pyspark==${versions.spark}"

          # The stamp alone is not enough: a half-written install leaves
          # `sail` in place with the right stamp but a `pyspark` that
          # imports as an empty namespace package, and the failure only
          # shows up much later as "no attribute '__version__'".
          if [ ! -x "$venv/bin/sail" ] \
             || [ "$(cat "$venv/.stamp" 2>/dev/null)" != "$want" ] \
             || ! "$venv/bin/python" -c "import pyspark; pyspark.__version__" >/dev/null 2>&1; then
            echo "Installing the Sail server ($want)..."
            rm -rf "$venv"
            ${python}/bin/python -m venv "$venv"
            "$venv/bin/pip" install --quiet --upgrade pip
            if "$venv/bin/pip" install --quiet $want; then
              echo "$want" > "$venv/.stamp"
            else
              echo "Could not install Sail: the tests will not be able to start a server." >&2
            fi
          fi

          export PATH="$venv/bin:$PATH"
        '';
      in {
        devShells.default = pkgs.devshell.mkShell {
          name = "sail-testkit";

          motd = ''
            {202}sail-testkit{reset} - Sail ${versions.pysail} / Spark ${versions.spark}
            $(type -p menu &>/dev/null && menu)
          '';

          packages = [ jdk pkgs.sbt pkgs.scalafmt pkgs.fzf ];

          # An override, not a default: sbt reads JAVA_HOME before the PATH, so
          # one inherited from outside (SDKMAN and friends) would decide which
          # JVM runs and this jdk would never be used.
          env = [
            { name = "JAVA_HOME"; value = jdk.home; }
          ];

          commands = [
            {
              category = "test";
              name = "t";
              help = "Run the test suite";
              command = ''sbt -batch test "$@"'';
            }
            {
              category = "test";
              name = "tf";
              help = "Run Sail's feature corpus and write the compatibility report";
              command = ''sbt -batch corpus "$@"'';
            }
            {
              category = "test";
              name = "tfs";
              help = "Run one slice, e.g. tfs spark/function/features/string";
              # The path is relative to the corpus root, so what you type
              # matches what the feature tree looks like in the submodule.
              # `-D` goes before the task: sbt reads anything after it as
              # another command.
              command = ''
                if [ $# -eq 0 ]; then
                  echo "usage: tfs <path under sail-features/python/pysail/tests/>" >&2
                  echo "   eg. tfs spark/function/features/string" >&2
                  exit 2
                fi
                slice="sail-features/python/pysail/tests/$1"
                if [ ! -e "$slice" ]; then
                  echo "no such slice: $slice" >&2
                  exit 2
                fi
                sbt -batch -Dcucumber.features="$slice" corpus
              '';
            }
            {
              category = "build";
              name = "c";
              help = "Compile main and test sources";
              command = ''sbt -batch compile Test/compile "$@"'';
            }
            {
              category = "console";
              name = "sail-server";
              help = "Start a Sail server in the foreground (port 50051)";
              command = ''sail spark server "$@"'';
            }
            {
              category = "lint";
              name = "f";
              help = "Format with scalafmt";
              command = ''scalafmt "''${@:-.}"'';
            }
            {
              category = "release";
              name = "publish-local";
              help = "Publish to the local ivy repo, to try it from another project";
              command = ''sbt -batch publishLocal'';
            }
          ];

          devshell.startup.sail.text = venvSail;

          devshell.interactive.fzf.text = ''
            eval "$(fzf --bash)"
            export PS1="sail \[\e[36m\]\W\[\e[0m\] $ "
          '';
        };
      }
    );
}
