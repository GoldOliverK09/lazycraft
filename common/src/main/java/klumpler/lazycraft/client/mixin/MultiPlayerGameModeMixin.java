package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.recipebook.RecipeBookEvents;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "handleInventoryMouseClick", at = @At("TAIL"))
    private void lazycraft$inventoryMouseClick(
            int containerId,
            int slotNum,
            int buttonNum,
            ClickType clickType,
            Player player,
            CallbackInfo ci
    ) {
        RecipeBookEvents.inventoryChanged();
    }
}
