package klumpler.recursivecraft.client.planner;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class InventorySnapshot {
    private final Map<Item, Integer> items;

    public InventorySnapshot(Map<Item, Integer> items) {
        this.items = new HashMap<>(items);
    }

    public static InventorySnapshot from(Player player) {
        Map<Item, Integer> items = new HashMap<>();

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                items.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        return new InventorySnapshot(items);
    }

    public int getAmount(Item item) {
        return items.getOrDefault(item, 0);
    }
}
