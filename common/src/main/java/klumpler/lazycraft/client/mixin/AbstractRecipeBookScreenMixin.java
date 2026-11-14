package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.recipebook.RecipeBookComponentExtension;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({CraftingScreen.class, InventoryScreen.class})
public abstract class AbstractRecipeBookScreenMixin {
    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void lazycraft$executeGhostOutput(
            Slot slot,
            int slotId,
            int mouseButton,
            ClickType input,
            CallbackInfo ci
    ) {
        Object screen = this;
        RecipeBookComponent recipeBookComponent = screen instanceof CraftingScreen craftingScreen
                ? craftingScreen.getRecipeBookComponent()
                : ((InventoryScreen) screen).getRecipeBookComponent();
        if (((RecipeBookComponentExtension) recipeBookComponent)
                .lazycraft$tryTakeGhostResult(slot, slotId, input)) {
            ci.cancel();
        }
    }
}
