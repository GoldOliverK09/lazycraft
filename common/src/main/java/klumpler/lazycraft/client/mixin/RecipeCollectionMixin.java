package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(RecipeCollection.class)
public class RecipeCollectionMixin {
    @Inject(method = "getDisplayRecipes", at = @At("RETURN"), cancellable = true)
    private void lazycraft$includeRecursiveEntries(
            boolean craftableOnly,
            CallbackInfoReturnable<List<RecipeHolder<?>>> cir
    ) {
        RecipeCollection collection = (RecipeCollection) (Object) this;
        List<RecipeHolder<?>> vanillaRecipes = cir.getReturnValue();
        List<RecipeHolder<?>> expanded = VisibleRecipeCraftability.includeRecursivelyCraftableEntries(
                collection,
                craftableOnly,
                vanillaRecipes
        );
        if (expanded != vanillaRecipes) {
            cir.setReturnValue(expanded);
        }
    }
}
