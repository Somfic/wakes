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

# Copy the built jar to a target mods folder, e.g. `just install ~/minecraft/mods`
install dest: build
    @mkdir -p "{{dest}}"
    cp build/libs/wakes-*.jar "{{dest}}/"
    @ls -lh "{{dest}}"/wakes-*.jar

# Run a NeoForge dev client with the mod loaded
run-client:
    ./gradlew runClient

# Run a NeoForge dev server with the mod loaded
run-server:
    ./gradlew runServer

# Download the compileOnly jars (Sable, Create, Create Aeronautics, Sodium) into libs/
fetch-libs:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p libs
    fetch() {
        local url="$1" name="$2"
        if [[ -f "libs/$name" ]]; then
            echo "skip  libs/$name (exists)"
        else
            echo "fetch libs/$name"
            curl -fsSL -o "libs/$name" "$url"
        fi
    }
    fetch "https://cdn.modrinth.com/data/T9PomCSv/versions/g8CObHcP/sable-neoforge-1.21.1-1.1.3.jar" "sable-neoforge-1.21.1-1.1.3.jar"
    fetch "https://cdn.modrinth.com/data/LNytGWDc/versions/UjX6dr61/create-1.21.1-6.0.10.jar" "create-1.21.1-6.0.10.jar"
    fetch "https://cdn.modrinth.com/data/oWaK0Q19/versions/1sv6OtSz/create-aeronautics-bundled-1.21.1-1.1.3.jar" "create-aeronautics-bundled-1.21.1-1.1.3.jar"
    fetch "https://cdn.modrinth.com/data/AANobbMI/versions/Pb3OXVqC/sodium-neoforge-0.6.13%2Bmc1.21.1.jar" "sodium-neoforge-0.6.13+mc1.21.1.jar"
    fetch "https://cdn.modrinth.com/data/YL57xq9U/versions/t3ruzodq/iris-neoforge-1.8.12%2Bmc1.21.1.jar" "iris-neoforge-1.8.12+mc1.21.1.jar"
    # Sable bundles its companion-math classes (Pose3d, BoundingBox3dc) and Veil
    # as jar-in-jars — the wave mixins reference companion-math directly, and
    # Sable's own classes transitively reference Veil. Gradle's fileTree doesn't
    # see inside JIJ, so extract them to top-level jars in libs/.
    extracted_any=0
    for inner in 'sable-companion-common-*.jar' 'veil-neoforge-*.jar'; do
        if ! ls libs/$inner >/dev/null 2>&1; then
            echo "extract $inner from sable JIJ"
            unzip -j -o libs/sable-neoforge-1.21.1-1.1.3.jar "META-INF/jarjar/$inner" -d libs/ >/dev/null
            extracted_any=1
        fi
    done
    ls -lh libs/*.jar

# Generate the gradle wrapper if it's missing.
# Done in a scratch dir so the system gradle doesn't try to evaluate build.gradle
# (which requires Gradle 8.8+ for the moddev plugin).
wrapper:
    #!/usr/bin/env bash
    set -euo pipefail
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    : > "$tmp/settings.gradle"
    (cd "$tmp" && gradle wrapper --gradle-version 8.10)
    cp -r "$tmp"/gradle "$tmp"/gradlew "$tmp"/gradlew.bat .
    chmod +x ./gradlew
    echo "wrapper installed — try: ./gradlew --version"
