package klumpler.lazycraft.client.planner;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InventorySnapshot {
    private final Map<Item, Integer> items;
    private final List<ItemStack> stacks;

    public String display() {
        return items.toString();
    }

    public static InventorySnapshot from(Player player) {
        Map<Item, Integer> items = new HashMap<>();
        List<ItemStack> stacks = new ArrayList<>();
        if (player == null) {
            throw new IllegalArgumentException("Player cannot be null");
        }

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);

            if (!stack.isEmpty()) {
                items.merge(stack.getItem(), stack.getCount(), Integer::sum);
                stacks.add(stack.copy());
            }
        }
        return new InventorySnapshot(items, stacks);
    }

    private InventorySnapshot(Map<Item, Integer> items, List<ItemStack> stacks) {
        this.items = new HashMap<>(items);
        this.stacks = new ArrayList<>(stacks);
    }

    public boolean canSatisfy(Ingredient ingredient) {
        return false;
    }

    public int getAmount(Item item) {
        return items.getOrDefault(item, 0);
    }

    public boolean has(Item item, int amount) {
        return getAmount(item) >= amount;
    }

    public void add(Item item, int amount) {
        items.merge(item, amount, Integer::sum);
    }

    public boolean remove(Item item, int amount) {
        int current = getAmount(item);

        if (current < amount) {
            return false;
        }

        if (current == amount) {
            items.remove(item);
        } else {
            items.put(item, current - amount);
        }

        return true;
    }
}
