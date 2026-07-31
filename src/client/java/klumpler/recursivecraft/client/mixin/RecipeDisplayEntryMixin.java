package klumpler.recursivecraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RecipeDisplayEntry.class)
public class RecipeDisplayEntryMixin {
    @ModifyReturnValue(method = "canCraft", at = @At("RETURN"))
    private boolean modifyCanCraft(boolean original, StackedItemContents providedContents) {
        // Saving this for when my crafting algorithm can safely return a true or false
        return original;
    }
}