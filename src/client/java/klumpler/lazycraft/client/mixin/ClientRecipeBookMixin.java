package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.planner.RecipeIndex;
import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.minecraft.client.ClientRecipeBook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientRecipeBook.class)
public class ClientRecipeBookMixin {
    @Inject(method = "rebuildCollections", at = @At("TAIL"))
    private void lazycraft$rebuildLookup(CallbackInfo ci) {
        ClientRecipeBook recipeBook = (ClientRecipeBook) (Object) this;
        RecipeIndex.rebuildLookup(recipeBook.getCollections());
        VisibleRecipeCraftability.clear();
    }
}
