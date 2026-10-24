package io.paradaux.treasury.model;

import java.util.List;

/**
 * Generic paginated result set.
 *
 * @param items      the items on this page
 * @param totalCount total number of matching rows across all pages
 * @param offset     zero-based row offset of this page
 * @param limit      maximum items per page
 */
public record Page<T>(List<T> items, int totalCount, int offset, int limit) {

    public Page {
        // Defensive, unmodifiable copy (ADT-40) so a caller can't mutate the page
        // contents through the list reference they passed in / received back.
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean hasMore() {
        return offset + items.size() < totalCount;
    }

    public int pageNumber() {
        return limit > 0 ? (offset / limit) + 1 : 1;
    }

    public int totalPages() {
        return limit > 0 ? (int) Math.ceil((double) totalCount / limit) : 1;
    }
}
