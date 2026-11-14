package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(RecipeButton.class)
public abstract class RecipeButtonMixin {
    @Shadow
    private List<RecipeHolder<?>> getOrderedRecipes() {
        throw new AssertionError();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void lazycraft$trackVisibleRecipes(
            RecipeCollection collection,
            RecipeBookPage page,
            CallbackInfo ci
    ) {
        VisibleRecipeCraftability.track(collection);
    }

    @Inject(method = "getOrderedRecipes", at = @At("RETURN"))
    private void lazycraft$removeDuplicateRecipes(
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<List<RecipeHolder<?>>> cir
    ) {
        List<RecipeHolder<?>> recipes = cir.getReturnValue();
        for (int i = 0; i < recipes.size(); i++) {
            for (int j = i + 1; j < recipes.size(); ) {
                if (recipes.get(i).id().equals(recipes.get(j).id())) {
                    recipes.remove(j);
                } else {
                    j++;
                }
            }
        }
    }

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void lazycraft$skipEmptyRecipeButtons(CallbackInfo ci) {
        if (getOrderedRecipes().isEmpty()) {
            ((AbstractWidget) (Object) this).visible = false;
            ci.cancel();
        }
    }

    @Redirect(
            method = "renderWidget",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeCollection;hasCraftable()Z"
            )
    )
    private boolean lazycraft$showRecursiveCraftability(RecipeCollection collection) {
        return collection.hasCraftable()
                || VisibleRecipeCraftability.hasRecursivelyCraftable(collection);
    }
}
