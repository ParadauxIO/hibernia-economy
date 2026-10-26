package io.paradaux.treasuryrestapi.util;

import io.paradaux.treasuryrestapi.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Shared pagination arithmetic. Converts a validated 1-based {@code page} and
 * {@code limit} into a SQL {@code OFFSET}, computing in {@code long} so a large
 * page can't overflow {@code int} into a negative value (which MariaDB rejects,
 * surfacing as a 500). Any page whose offset can't be served is a clean 400.
 */
public final class Pagination {

    private Pagination() {}

    /** Returns {@code (page - 1) * limit} as an int offset, or 400s if it overflows. */
    public static int offset(int page, int limit) {
        long offsetLong = (long) (page - 1) * limit;
        if (offsetLong > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Query parameter 'page' is too large.");
        }
        return (int) offsetLong;
    }
}
