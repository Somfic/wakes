package sh.somfic.wherestherum;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class WakesMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String t, ClassNode tn, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode tn, String m, IMixinInfo i) {}

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("PropellerBearingContraptionEntityMixin")) {
            return ModCompat.AERONAUTICS_LOADED;
        }
        if (mixinClassName.contains(".sodium.")) {
            return ModCompat.SODIUM_LOADED;
        }
        if (mixinClassName.contains(".sable.")) {
            return ModCompat.SABLE_LOADED;
        }
        if (mixinClassName.contains(".iris.")) {
            return ModCompat.IRIS_LOADED;
        }
        return true;
    }
}
