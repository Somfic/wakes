package sh.somfic.wherestherum.mixin.vanilla;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sh.somfic.wherestherum.buoyancy.WaveBuoyancy;

/**
 * Lets loose entities ride the wave surface instead of the flat vanilla sea.
 *
 * <h2>Why baseTick and not tick</h2>
 * {@code Entity.tick()} is overridden by essentially every interesting subclass
 * — {@link net.minecraft.world.entity.vehicle.Boat} included — and many of them
 * never call {@code super.tick()}. Injecting there would silently miss the one
 * entity type this feature exists for. {@code baseTick()} is the shared floor:
 * it is not overridden by Boat, and LivingEntity's override calls
 * {@code super.baseTick()} first thing.
 *
 * <p>TAIL specifically, because {@code baseTick} calls
 * {@code updateInWaterStateAndDoFluidPushing()} roughly a third of the way in.
 * That is what refreshes the {@code wasTouchingWater} flag behind
 * {@code isInWater()}. Injecting at HEAD would read last tick's water state,
 * which is wrong for exactly the entity that just hit the water — and wrong in
 * the direction that produces a one-tick force spike. At TAIL the flag is
 * current. {@code baseTick} has no early returns, so TAIL is unambiguous.
 *
 * <p>Signature verified against the decompiled 1.21.1 + NeoForge sources:
 * {@code public void baseTick()} in {@code net/minecraft/world/entity/Entity.java}.
 *
 * <h2>Why this is a trampoline</h2>
 * Entity is one of the most heavily mixed-in classes in any modpack. Keeping
 * the body to a single static call means we add exactly one method and no
 * fields to the class, minimising the surface for collisions with other mods.
 * The actual physics — and the reasoning behind it — lives in
 * {@link WaveBuoyancy}.
 */
@Mixin(Entity.class)
public abstract class EntityBuoyancyMixin {

    /**
     * Runs for every entity in the world every tick, so the cost of the common
     * case matters more than anything else here.
     * {@link WaveBuoyancy#tick(Entity)} opens with a chain of field-read
     * early-outs and does not touch the world until an entity has already
     * proven it is floating at the ocean surface.
     */
    @Inject(method = "baseTick", at = @At("TAIL"))
    private void wakes$rideWaves(CallbackInfo ci) {
        WaveBuoyancy.tick(wakes$self());
    }

    @Unique
    private Entity wakes$self() {
        return (Entity) (Object) this;
    }
}
