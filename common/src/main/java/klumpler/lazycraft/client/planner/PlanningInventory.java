package klumpler.lazycraft.client.planner;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

final class PlanningInventory {
    private Item[] items;
    private int[] counts;
    private int size;
    private static final ThreadLocal<Item[]> TL_ITEMS = ThreadLocal.withInitial(() -> new Item[64]);
    private static final ThreadLocal<int[]> TL_COUNTS = ThreadLocal.withInitial(() -> new int[64]);

    private PlanningInventory(Item[] items, int[] counts, int size) {
        this.items = items;
        this.counts = counts;
        this.size = size;
    }

    static PlanningInventory from(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        int inventorySize = player.getInventory().getContainerSize();
        List<Slot> inputSlots = player.containerMenu instanceof CraftingMenu
                || player.containerMenu instanceof InventoryMenu
                ? inputGridSlots((RecipeBookMenu<?, ?>) player.containerMenu)
                : List.of();
        int capacity = inventorySize + inputSlots.size() + 1;
        Item[] items = TL_ITEMS.get();
        int[] counts = TL_COUNTS.get();
        if (items.length < capacity) {
            items = Arrays.copyOf(items, capacity);
            counts = Arrays.copyOf(counts, capacity);
            TL_ITEMS.set(items);
            TL_COUNTS.set(counts);
        }

        int size = 0;
        for (int slot = 0; slot < inventorySize; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                size = addToBuffer(items, counts, size, stack.getItem(), stack.getCount());
            }
        }

        for (Slot slot : inputSlots) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                size = addToBuffer(items, counts, size, stack.getItem(), stack.getCount());
            }
        }

        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty()) {
            size = addToBuffer(items, counts, size, carried.getItem(), carried.getCount());
        }

        return new PlanningInventory(
                Arrays.copyOf(items, size),
                Arrays.copyOf(counts, size),
                size
        );
    }

    private static List<Slot> inputGridSlots(RecipeBookMenu<?, ?> menu) {
        int firstInputSlot = menu.getResultSlotIndex() + 1;
        return menu.slots.subList(firstInputSlot, firstInputSlot + menu.getSize());
    }

    private static int addToBuffer(Item[] items, int[] counts, int size, Item item, int count) {
        // Check if item already exists in buffer (linear search, small N)
        for (int i = 0; i < size; i++) {
            if (items[i] == item) {
                counts[i] += count;
                return size;
            }
        }
        items[size] = item;
        counts[size] = count;
        return size + 1;
    }

    Map<Item, Integer> itemCounts() {
        Map<Item, Integer> itemCounts = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            itemCounts.merge(items[i], counts[i], Math::addExact);
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
        for (int i = 0; i < size; i++) {
            if (acceptedItems.contains(items[i])) {
                available += counts[i];
            }
        }
        return available;
    }

    void add(Item item, int count) {
        Objects.requireNonNull(item, "item cannot be null");
        if (count <= 0) return;

        for (int i = size - 1; i >= 0; i--) {
            if (items[i] == item) {
                counts[i] = Math.addExact(counts[i], count);
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
        if (quantity < 0) throw new IllegalArgumentException("quantity cannot be negative");
        if (quantity == 0) return true;

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
        if (requiredCapacity <= items.length) return;
        int expanded = Math.max(requiredCapacity, items.length + (items.length >> 1) + 1);
        items = Arrays.copyOf(items, expanded);
        counts = Arrays.copyOf(counts, expanded);
        TL_ITEMS.set(items);
        TL_COUNTS.set(counts);
    }
}
