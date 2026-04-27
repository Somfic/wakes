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
