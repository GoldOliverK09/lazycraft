package klumpler.lazycraft.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractRecipeBookScreen.class)
public abstract class AbstractRecipeBookScreenMixin {
    @Shadow
    @Final
    private RecipeBookComponent<?> recipeBookComponent;

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void lazycraft$executeGhostOutput(
            Slot slot,
            int slotId,
            int mouseButton,
            ContainerInput input,
            CallbackInfo ci
    ) {
        if (((RecipeBookComponentExtension) recipeBookComponent)
                .lazycraft$tryTakeGhostResult(slot, input)) {
            ci.cancel();
        }
    }
}
