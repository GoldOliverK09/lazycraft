package klumpler.recursivecraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import klumpler.recursivecraft.client.planner.RecursivePlanner;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(RecipeDisplayEntry.class)
public class RecipeDisplayEntryMixin {
    @ModifyReturnValue(method = "canCraft", at = @At("RETURN"))
    private boolean modifyCanCraft(boolean original, StackedItemContents providedContents) {
        return original;
    }
}