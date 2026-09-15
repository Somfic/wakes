package sh.somfic.wherestherum.sound;

import sh.somfic.wherestherum.Wakes;
import sh.somfic.wherestherum.WakesConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Our own sound events, aliased onto vanilla audio.
 *
 * <h2>Why wrap vanilla sounds instead of playing them directly</h2>
 * Vanilla has no creak asset, so hull creaks are built from the only three
 * sounds Mojang itself describes as creaking — door, fence gate and trapdoor
 * toggles (their subtitles are literally "Door creaks", "Fence Gate creaks",
 * "Trapdoor creaks"). Playing those events directly works, but anyone with
 * subtitles switched on reads "Fence Gate creaks" every few seconds aboard a
 * ship that has no fence gate, which is worse for immersion than the sound is
 * good for it.
 *
 * A sound entry may reference another sound EVENT rather than a file
 * ({@code "type": "event"}), so {@code sounds.json} wraps all six vanilla
 * toggles in one event of ours. That buys a correct subtitle — "Timbers creak"
 * — and random selection across the pool, while shipping no audio whatsoever.
 * No .ogg files, no asset licensing question, nothing to download.
 *
 * <p>The pitch shift stays in code rather than in {@code sounds.json}: it is
 * randomised per creak and scaled by sea state, which a static definition
 * cannot express.
 */
public final class WakesSoundRegistry {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, Wakes.MOD_ID);

    /** Randomly one of the vanilla wooden-hinge creaks; see class javadoc. */
    public static final DeferredHolder<SoundEvent, SoundEvent> HULL_CREAK =
            SOUNDS.register("hull_creak", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(Wakes.MOD_ID, "hull_creak")));

    private WakesSoundRegistry() {}

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }

    /** Cached result of resolving the configured creak ids. */
    private static SoundEvent resolvedCreak;

    /**
     * The creak sound to play, resolved from {@code sound.creakSounds} the first
     * time it is needed.
     *
     * <h2>Why resolve at runtime instead of in sounds.json</h2>
     * The default list prefers {@code sounds:block.ice.stress} from the optional
     * Sounds mod. Referencing a missing event from {@code sounds.json} would be a
     * resource-load error and could drop the whole definition; looking it up in
     * the registry instead simply returns empty, so listing optional-mod sounds
     * costs nothing when they are absent. Each id is tried in order and the first
     * that is actually registered wins, with our own vanilla-only
     * {@link #HULL_CREAK} as the guaranteed backstop.
     */
    public static SoundEvent creak() {
        if (resolvedCreak != null) return resolvedCreak;

        for (String id : WakesConfig.SOUND_CREAK_IDS.get()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                Wakes.LOG.warn("Wakes: creakSounds entry '{}' is not a valid id; skipping", id);
                continue;
            }
            var found = BuiltInRegistries.SOUND_EVENT.getOptional(rl);
            if (found.isPresent()) {
                Wakes.LOG.info("Wakes: hull creak using '{}'", rl);
                resolvedCreak = found.get();
                return resolvedCreak;
            }
        }

        Wakes.LOG.info("Wakes: no configured creak sound was registered; falling back to {}",
                HULL_CREAK.getId());
        resolvedCreak = HULL_CREAK.get();
        return resolvedCreak;
    }
}
