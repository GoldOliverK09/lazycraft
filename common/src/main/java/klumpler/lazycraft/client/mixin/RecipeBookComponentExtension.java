package klumpler.lazycraft.client.mixin;

import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

public interface RecipeBookComponentExtension {
    boolean lazycraft$tryTakeGhostResult(Slot slot, ContainerInput input);
}
