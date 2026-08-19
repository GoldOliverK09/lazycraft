package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeButton.class)
public abstract class RecipeButtonMixin {
    @Shadow
    public abstract RecipeDisplayId getCurrentRecipe();

    @Inject(method = "init", at = @At("TAIL"))
    private void lazycraft$trackVisibleRecipes(
            RecipeCollection collection,
            boolean isFiltering,
            RecipeBookPage page,
            ContextMap resolutionContext,
            CallbackInfo ci
    ) {
        VisibleRecipeCraftability.track(collection, resolutionContext);
    }

    @Redirect(
            method = "extractWidgetRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeCollection;hasCraftable()Z"
            )
    )
    private boolean lazycraft$showRecursiveCraftability(RecipeCollection collection) {
        return collection.hasCraftable()
                || VisibleRecipeCraftability.isRecursivelyCraftable(getCurrentRecipe());
    }
}
