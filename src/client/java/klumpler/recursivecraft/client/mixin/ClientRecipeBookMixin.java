package klumpler.recursivecraft.client.mixin;

import klumpler.recursivecraft.client.planner.*;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(ClientRecipeBook.class)
public class ClientRecipeBookMixin {
    @Shadow
    private List<RecipeCollection> allCollections;

    @Inject(method = "rebuildCollections", at = @At("TAIL"))
    private void recursivecraft$rebuildLookup(CallbackInfo ci) {
        RecipeIndex.rebuildLookup(this.allCollections);
        RecipeTree.log(Items.HOPPER_MINECART);
    }
}
