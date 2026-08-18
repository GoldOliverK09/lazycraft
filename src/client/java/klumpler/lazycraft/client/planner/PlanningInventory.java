package klumpler.lazycraft.client.planner;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * Detached item/count inventory used by the pure planning search.
 * Entries stay in player-slot order because alternative ingredients are consumed in that order.
 */
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
        Item[] items = new Item[inventorySize];
        int[] counts = new int[inventorySize];
        int size = 0;

        for (int slot = 0; slot < inventorySize; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                items[size] = stack.getItem();
                counts[size] = stack.getCount();
                size++;
            }
        }

        return new PlanningInventory(items, counts, size);
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

        ensureCapacity(size + 1);
        items[size] = item;
        counts[size] = count;
        size++;
    }

    /**
     * Consumes in stable entry order. A failed attempt may have consumed part of this
     * inventory, so callers must use a disposable branch copy when success is uncertain.
     */
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
