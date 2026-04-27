# Wakes

NeoForge 1.21.1 addon that bridges **Physics Mod**'s ocean waves into **Create Aeronautics**, so propeller-bearing contraptions visually rock with the swell instead of sitting on a flat plane.

## Status

**Phase 1 — visual-only.** Contraptions get heave + pitch + roll applied at render time, sampled at multiple hull points so a long ship rocks like a ship rather than bobbing like a cork. No effect on collisions, server-side physics, or buoyancy.

Phase 2 (real wave-driven server physics) is planned but not implemented.

## Build

You need a **Create** jar matching your target version on the compile classpath. Drop it into `libs/`:

```
libs/
  create-1.21.1-6.0.4-19.jar     # or whichever Create build matches Aeronautics
```

The bundled Create-Aeronautics jar (`create-aeronautics-bundled-1.21.1-1.1.3.jar`) and the Physics Mod jar (`physics-mod-3.0.27-mc-1.21.1-neoforge.jar`) at the repo root are referenced as `compileOnly`. Physics Mod is **reflective at runtime** — Wakes loads fine without it and degrades to a no-op.

```
./gradlew build
```

## Runtime requirements

| Mod | Required | Notes |
|---|---|---|
| NeoForge 21.1+ | yes | |
| Create 6.0+ | yes | |
| Create Aeronautics 1.1.3+ | yes | mixin target |
| Physics Mod 3.0+ | optional | when missing, no waves; everything still loads |

## Config

`config/wakes-client.toml`:

- `enabled` — master toggle
- `samplePointsPerAxis` — hull sample grid (1 = single-point bob, 3 = ship-like rocking)
- `heaveScale` / `pitchScale` / `rollScale` — per-axis multipliers
- `requireWaterUnderneath` — only apply when contraption footprint actually overlaps water
- `affectAllContraptions` — Phase 1 only targets propeller-bearing contraptions
