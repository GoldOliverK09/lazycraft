package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeBookPage.class)
public class RecipeBookPageMixin {
    @Inject(method = "updateButtonsForPage", at = @At("HEAD"))
    private void lazycraft$beginVisibleRecipeRefresh(CallbackInfo ci) {
        VisibleRecipeCraftability.beginRefresh();
    }

    @Inject(method = "updateButtonsForPage", at = @At("RETURN"))
    private void lazycraft$finishVisibleRecipeRefresh(CallbackInfo ci) {
        VisibleRecipeCraftability.finishRefresh();
    }

    @Inject(method = "setInvisible", at = @At("TAIL"))
    private void lazycraft$clearVisibleRecipes(CallbackInfo ci) {
        VisibleRecipeCraftability.clear();
    }
}
