package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.recipebook.RecipeBookEvents;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(
            method = {
                    "handleContainerSetSlot",
                    "handleContainerContent",
                    "handleSetCursorItem",
                    "handleSetPlayerInventory"
            },
            at = @At("TAIL")
    )
    private void lazycraft$inventoryChanged(CallbackInfo ci) {
        RecipeBookEvents.inventoryChanged();
    }

    @Inject(method = "refreshRecipeBook", at = @At("TAIL"))
    private void lazycraft$recipesChanged(ClientRecipeBook recipeBook, CallbackInfo ci) {
        RecipeBookEvents.recipesChanged(recipeBook);
    }
}
