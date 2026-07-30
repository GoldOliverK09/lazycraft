package klumpler.recursivecraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import klumpler.recursivecraft.planner.RecursivePlanner;

import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(RecipeDisplayEntry.class)
public class RecipeDisplayEntryMixin {
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("recursivecraft");
    @Final
    @Shadow
    private RecipeDisplay display;

    @ModifyReturnValue(method = "canCraft", at = @At("RETURN"))
    private boolean modifyCanCraft(boolean original, StackedItemContents providedContents) {
        LOGGER.info("Craft {} = {}", RecursivePlanner.canCraft((RecipeDisplayEntry)(Object)this, providedContents), original);
        return original;
    }
}