package sh.somfic.wherestherum;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Wakes.MOD_ID)
public final class Wakes {
    public static final String MOD_ID = "wherestherum";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public Wakes(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, WakesConfig.SPEC, MOD_ID + "-client.toml");
        LOG.info("Where's the Rum loaded — aeronautics: {}, sodium: {}",
                ModCompat.AERONAUTICS_LOADED, ModCompat.SODIUM_LOADED);
    }
}
