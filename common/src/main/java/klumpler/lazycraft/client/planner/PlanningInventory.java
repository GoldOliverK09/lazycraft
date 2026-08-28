package klumpler.lazycraft.client.planner;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

final class PlanningInventory {
    private Item[] items;
    private int[] counts;
    private int size;

    private PlanningInventory(Item[] items, int[] counts, int size) {
        this.items = items;
        this.counts = counts;
        this.size = size;
    }

    static PlanningInventory from(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        int inventorySize = player.getInventory().getContainerSize();
        List<Slot> inputSlots = player.containerMenu instanceof AbstractCraftingMenu menu
                ? menu.getInputGridSlots()
                : List.of();
        int capacity = inventorySize + inputSlots.size() + 1;
        PlanningInventory inventory = new PlanningInventory(
                new Item[capacity],
                new int[capacity],
                0
        );

        for (int slot = 0; slot < inventorySize; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                inventory.add(stack.getItem(), stack.getCount());
            }
        }

        for (Slot slot : inputSlots) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                inventory.add(stack.getItem(), stack.getCount());
            }
        }

        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty()) {
            inventory.add(carried.getItem(), carried.getCount());
        }

        return inventory;
    }

    Map<Item, Integer> itemCounts() {
        Map<Item, Integer> itemCounts = new HashMap<>();
        for (int index = 0; index < size; index++) {
            itemCounts.merge(items[index], counts[index], Math::addExact);
        }
        return Collections.unmodifiableMap(itemCounts);
    }

    PlanningInventory copy() {
        return new PlanningInventory(
                Arrays.copyOf(items, size),
                Arrays.copyOf(counts, size),
                size
        );
    }

    int availableItems(Collection<Item> acceptedItems) {
        Objects.requireNonNull(acceptedItems, "acceptedItems cannot be null");
        int available = 0;
        for (int index = 0; index < size; index++) {
            if (acceptedItems.contains(items[index])) {
                available += counts[index];
            }
        }
        return available;
    }

    void add(Item item, int count) {
        Objects.requireNonNull(item, "item cannot be null");
        if (count <= 0) {
            return;
        }

        for (int index = size - 1; index >= 0; index--) {
            if (items[index] == item) {
                counts[index] = Math.addExact(counts[index], count);
                return;
            }
        }

        ensureCapacity(size + 1);
        items[size] = item;
        counts[size] = count;
        size++;
    }

    boolean consumeItems(Collection<Item> acceptedItems, int quantity) {
        Objects.requireNonNull(acceptedItems, "acceptedItems cannot be null");
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity cannot be negative");
        }
        if (quantity == 0) {
            return true;
        }

        int remaining = quantity;
        int writeIndex = 0;

        for (int readIndex = 0; readIndex < size; readIndex++) {
            int count = counts[readIndex];
            if (remaining > 0 && acceptedItems.contains(items[readIndex])) {
                int consumed = Math.min(count, remaining);
                count -= consumed;
                remaining -= consumed;
            }

            if (count > 0) {
                items[writeIndex] = items[readIndex];
                counts[writeIndex] = count;
                writeIndex++;
            }
        }

        Arrays.fill(items, writeIndex, size, null);
        size = writeIndex;
        return remaining == 0;
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= items.length) {
            return;
        }

        int expandedCapacity = Math.max(requiredCapacity, items.length + (items.length >> 1) + 1);
        items = Arrays.copyOf(items, expandedCapacity);
        counts = Arrays.copyOf(counts, expandedCapacity);
    }
}
