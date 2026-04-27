# Wakes

NeoForge 1.21.1 mod that adds wave-driven buoyancy and matching ocean shaders so **Create Aeronautics** ships and vanilla boats rock with the swell instead of sitting on a flat plane.

## Build

The mixins compile against jars from Create, Create Aeronautics, Sable, Sodium, and Iris. Run:

```
just fetch-libs
just build
```

`fetch-libs` downloads the jars from Modrinth into `libs/` and extracts the jar-in-jars Gradle's fileTree can't see (Sable's companion-math classes, Veil). All `compileOnly` — runtime resolves them from the actual installed mods.

## Runtime requirements

| Mod | Required | Notes |
|---|---|---|
| NeoForge 21.1+ | yes | |
| Create 6.0+ | yes | |
| Create Aeronautics 1.1.3+ | yes | mixin target |
| Sable 1.1.3+ | optional | enables wave buoyancy on assembled SubLevels |
| Sodium 0.6.x | optional | patches its chunk shader for animated water |
| Iris 1.8.x | optional | patches the loaded shader pack's water vsh |

## Config

`config/wakes-client.toml`:

- `enabled` — master toggle
- `samplePointsPerAxis` — hull sample grid (1 = single-point bob, 3 = ship-like rocking)
- `heaveScale` / `pitchScale` / `rollScale` — per-axis multipliers
- `requireWaterUnderneath` — only apply when contraption footprint actually overlaps water
- `affectAllContraptions` — Phase 1 only targets propeller-bearing contraptions
