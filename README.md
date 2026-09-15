# Wakes

NeoForge 1.21.1 mod that adds wave-driven buoyancy and matching ocean shaders so **Create Aeronautics** ships and vanilla boats rock with the swell instead of sitting on a flat plane.

## Build

The mixins compile against jars from Create, Create Aeronautics, Sable, Sodium, and Iris. Run:

```
just fetch-libs
just build
```

`fetch-libs` resolves each dependency against the Modrinth API at the version named in `gradle.properties`, downloads it into `libs/`, and extracts the jar-in-jars Gradle's fileTree can't see. All `compileOnly` — runtime resolves them from the actual installed mods.

Note that several of these ship the classes we compile against as jar-in-jars, so `libs/` ends up with more jars than were downloaded:

- **Sable** bundles companion-math (`Pose3d`, `BoundingBox3dc`), Veil, and rapier.
- **Sodium 0.8+** bundles the entire mod; the outer jar is only a NeoForge locator stub.
- **Create Aeronautics** bundles the real `aeronautics` mod, plus offroad and simulated.

To change versions, edit `gradle.properties` and re-run `just fetch-libs` (use `just clean-libs` first to drop the old jars).

## Runtime requirements

Versions below are what the mod is currently built and tested against.

| Mod | Required | Notes |
|---|---|---|
| NeoForge 21.1.250 | yes | |
| Create 6.0.10+ | yes | |
| Create Aeronautics 1.3.2+ | yes | mixin target |
| Sable 2.0.5+ | optional | enables wave buoyancy on assembled SubLevels |
| Sodium 0.8.x | optional | patches its chunk shader for animated water |
| Iris 1.8.x | optional | patches the loaded shader pack's water vsh |

## Config

`config/wakes-client.toml`:

- `enabled` — master toggle
- `samplePointsPerAxis` — hull sample grid (1 = single-point bob, 3 = ship-like rocking)
- `heaveScale` / `pitchScale` / `rollScale` — per-axis multipliers
- `requireWaterUnderneath` — only apply when contraption footprint actually overlaps water
- `affectAllContraptions` — Phase 1 only targets propeller-bearing contraptions
- `debugParticles` — dust particles at each wave-force sample point; noisy in normal play
- `debugLog` — per-ship, per-second physics diagnostics (mass, angular velocity, net torque)

### `[buoyancy]`

Depth-proportional lift for blocks tagged `#wherestherum:floats` — the full-cube coral
blocks by default. Unlike Aeronautics levitite this adds **no** rotational drag, so a hull
keeps rocking with the swell; the only damping is vertical and is applied at the centre of
mass (zero lever arm, so it cannot induce torque).

Lift scales with block **count**, not ship mass, and grows with submersion depth — so a hull
settles at its own waterline rather than launching, and a tilted hull's deeper side lifts
harder, giving a righting moment that falls out of the geometry.

- `enabled` — master toggle
- `liftPerBlock` — lift per floating block per block of submersion depth
- `maxDepth` — depth at which a block's lift stops growing. Set to `1.0` for a sharp,
  predictable waterline (a block then saturates once fully submerged, as real displacement
  does); larger values let a hull sink deeper before lift balances weight
- `verticalDamping` — vertical-only damping (1/s); `0` for a completely undamped hull

Minimum blocks to float a hull is `Weight / (liftPerBlock × maxDepth)` — below that, lift
saturates and she sinks regardless.
