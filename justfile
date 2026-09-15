_default:
    @just --list

# Build the mod jar into build/libs/
build:
    ./gradlew build

# Fast incremental compile (no tests, no jar packaging)
compile:
    ./gradlew classes

# Clean all gradle outputs
clean:
    ./gradlew clean

# Build from scratch
rebuild: clean build

# Show where the produced jar landed
jar: build
    @ls -lh build/libs/*.jar

# Build, then atomically swap the jar into a mods folder, e.g. `just install ~/minecraft/mods`
install dest: build
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p "{{dest}}"
    if pgrep -f "org.prismlauncher.EntryPoint" > /dev/null 2>&1; then
        echo "warning: Minecraft appears to be RUNNING." >&2
        echo "         The jar will be swapped atomically so the running game is not corrupted," >&2
        echo "         but it will keep using the OLD jar until you fully quit and relaunch." >&2
    fi
    src=$(ls build/libs/wherestherum-*.jar | head -1)
    tmp="{{dest}}/.$(basename "$src").tmp$$"
    cp "$src" "$tmp"
    mv -f "$tmp" "{{dest}}/$(basename "$src")"
    ls -lh "{{dest}}"/wherestherum-*.jar

# Run a NeoForge dev client with the mod loaded
run-client:
    ./gradlew runClient

# Run a NeoForge dev server with the mod loaded
run-server:
    ./gradlew runServer


# Download the compileOnly jars into libs/ at the versions named in gradle.properties
fetch-libs:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p libs

    prop() { sed -n "s/^$1=//p" gradle.properties | tr -d '\r'; }
    MC=$(prop minecraft_version)

    # Modrinth project IDs (stable; slugs can be renamed out from under us).
    declare -A PROJECT=(
        [create]=LNytGWDc
        [aeronautics]=oWaK0Q19
        [sable]=T9PomCSv
        [sodium]=AANobbMI
        [iris]=YL57xq9U
    )
    declare -A WANT=(
        [create]=$(prop create_version)
        [aeronautics]=$(prop createaeronautics_version)
        [sable]=$(prop sable_version)
        [sodium]=$(prop sodium_version)
        [iris]=$(prop iris_version)
    )

    # Resolve "which file does <project> <version> ship for this MC + neoforge"
    # and download it under its own published filename.
    fetch() {
        local key="$1" id="${PROJECT[$1]}" want="${WANT[$1]}"
        local meta filename url
        meta=$(curl -fsSL \
            --get "https://api.modrinth.com/v2/project/$id/version" \
            --data-urlencode "loaders=[\"neoforge\"]" \
            --data-urlencode "game_versions=[\"$MC\"]")
        # version_number has no common shape across these projects:
        #   create/aeronautics/sable  6.0.10+mc1.21.1
        #   iris                      1.8.14-beta.1+1.21.1-neoforge
        #   sodium                    mc1.21.1-0.8.13-neoforge
        # So match `want` as a delimited token anywhere in the string, then take
        # the SHORTEST match — that drops "0.8.13-beta.2" in favour of plain
        # "0.8.13" while still letting an explicitly-requested beta through.
        read -r filename url < <(printf '%s' "$meta" | jq -r --arg w "$want" '
            ($w | gsub("\\."; "\\.")) as $wq
            | [ .[] | select(.version_number
                  | test("(^|[^0-9A-Za-z.])" + $wq + "([^0-9A-Za-z.]|$)")) ]
            | sort_by(.version_number | length)
            | first
            | .files
            | (map(select(.primary)) + .)[0]
            | "\(.filename) \(.url)"')
        if [[ -z "${filename:-}" || "$filename" == "null" ]]; then
            echo "error: no $key $want for $MC/neoforge on Modrinth" >&2
            return 1
        fi
        if [[ -f "libs/$filename" ]]; then
            echo "skip  libs/$filename (exists)" >&2
        else
            echo "fetch libs/$filename" >&2
            curl -fsSL -o "libs/$filename" "$url"
        fi
        printf '%s' "$filename"
    }

    SABLE_JAR=$(fetch sable);  echo
    SODIUM_JAR=$(fetch sodium); echo
    AERO_JAR=$(fetch aeronautics); echo
    fetch create > /dev/null; echo
    fetch iris   > /dev/null; echo

    # Several of these ship the classes we actually compile against as
    # jar-in-jars, which Gradle's fileTree can't see into. Extract those to
    # top-level jars in libs/.
    #
    #   sable       -> companion-math (Pose3d, BoundingBox3dc), Veil, rapier
    #   sodium 0.8+ -> the whole mod; the outer jar is just a NeoForge locator stub
    #   aeronautics -> the real `aeronautics` mod, plus offroad/simulated
    extract() {
        local outer="$1"; shift
        for pattern in "$@"; do
            if ! ls libs/${pattern} >/dev/null 2>&1; then
                echo "extract $pattern from $(basename "$outer")" >&2
                unzip -j -o "$outer" "META-INF/jarjar/$pattern" -d libs/ >/dev/null
            fi
        done
    }
    extract "libs/$SABLE_JAR"  'sable-companion-common-*.jar' 'veil-neoforge-*.jar' '*sable_rapier*.jar'
    extract "libs/$SODIUM_JAR" 'net.caffeinemc.sodium-neoforge-*-mod.jar'
    extract "libs/$AERO_JAR"   'dev.eriksonn.aeronautics.*.jar' 'dev.ryanhcode.offroad.*.jar' 'dev.simulated_team.*.jar'

    ls -lh libs/*.jar

# Remove every downloaded/extracted jar from libs/ so fetch-libs starts clean.
clean-libs:
    find libs -name '*.jar' -delete
    @echo 'libs/ cleared — run: just fetch-libs'
