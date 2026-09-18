package klumpler.lazycraft.client.recipebook;

import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

public interface RecipeBookComponentExtension {
    boolean lazycraft$tryTakeGhostResult(Slot slot, ClickType input);
}
