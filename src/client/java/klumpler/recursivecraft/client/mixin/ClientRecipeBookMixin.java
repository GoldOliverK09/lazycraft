package klumpler.recursivecraft.client.mixin;

import klumpler.recursivecraft.client.planner.RecipeIndex;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ClientRecipeBook.class)
public class ClientRecipeBookMixin {
    @Shadow
    private List<RecipeCollection> allCollections;

    @Inject(method = "rebuildCollections", at = @At("TAIL"))
    private void recursivecraft$rebuildLookup(CallbackInfo ci) {
        RecipeIndex.rebuildLookup(this.allCollections);
    }
}