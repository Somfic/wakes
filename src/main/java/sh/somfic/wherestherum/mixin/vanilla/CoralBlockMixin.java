package sh.somfic.wherestherum.mixin.vanilla;

import sh.somfic.wherestherum.WakesConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.CoralBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps full-cube coral blocks from drying out and turning into their dead
 * variants.
 *
 * Coral is the default buoyancy material ({@code #wherestherum:floats}), and a
 * hull packs it in dry, enclosed spaces where vanilla would kill it: a live
 * coral block schedules its own death tick whenever no neighbouring fluid can
 * hydrate it. Dead coral is in the floats tag too, so this was never a physics
 * problem — the ship floated regardless — but it turns a colourful hull grey,
 * and the block you placed is not the block you end up with.
 *
 * <h2>Why scanForWater and not tick</h2>
 * {@link CoralBlock} consults {@code scanForWater} in three places: {@code tick}
 * kills the block when it returns false, while {@code updateShape} and
 * {@code getStateForPlacement} both SCHEDULE that death tick when it returns
 * false. Cancelling only {@code tick} would leave the game endlessly scheduling
 * doomed ticks. Forcing {@code scanForWater} true short-circuits all three, so
 * no death tick is ever queued in the first place.
 *
 * This applies everywhere, not just inside SubLevels, which is deliberate: you
 * build the ship in the world before assembling it, and coral dying on the
 * slipway would be just as annoying as coral dying at sea. Set
 * {@code preventCoralDeath = false} to restore vanilla behaviour.
 */
@Mixin(CoralBlock.class)
public abstract class CoralBlockMixin {

    @Inject(method = "scanForWater", at = @At("HEAD"), cancellable = true)
    private void wakes$keepCoralAlive(BlockGetter level, BlockPos pos,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (wakes$preventDeath()) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Coral death is server-side, but {@link WakesConfig} is registered as
     * {@code ModConfig.Type.CLIENT}, so on a dedicated server the spec is never
     * loaded and {@code get()} throws rather than returning a default. Single
     * player is unaffected (integrated server shares the client's JVM and its
     * loaded config), but a block tick is the wrong place to discover that.
     * Fall back to the declared default instead of propagating out of a tick.
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean wakes$preventDeath() {
        try {
            return WakesConfig.PREVENT_CORAL_DEATH.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }
}
