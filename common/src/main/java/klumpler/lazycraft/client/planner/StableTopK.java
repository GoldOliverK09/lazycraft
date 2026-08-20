package klumpler.lazycraft.client.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

final class StableTopK<T> {
    private final int limit;
    private final Comparator<? super T> comparator;
    private final List<T> values;

    StableTopK(int limit, Comparator<? super T> comparator) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit cannot be negative");
        }

        this.limit = limit;
        this.comparator = Objects.requireNonNull(comparator, "comparator cannot be null");
        this.values = new ArrayList<>(Math.min(limit, 16));
    }

    void add(T value) {
        Objects.requireNonNull(value, "value cannot be null");
        if (limit == 0) {
            return;
        }
        if (values.size() == limit
                && comparator.compare(value, values.getLast()) >= 0) {
            return;
        }

        int low = 0;
        int high = values.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (comparator.compare(value, values.get(middle)) < 0) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }

        if (low == limit) {
            return;
        }

        values.add(low, value);
        if (values.size() > limit) {
            values.removeLast();
        }
    }

    List<T> takeValues() {
        return values;
    }
}
