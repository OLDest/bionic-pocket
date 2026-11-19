package ru.oldest.bionicpocket;

import java.util.HashSet;
import java.util.Set;

/**
 * Delivery policy for retry decisions based on HTTP status codes.
 */
public class DeliveryPolicy {

    // Retryable status codes: network errors, 408, 429, and server errors (5xx)
    private static final Set<Integer> RETRYABLE_CODES = new HashSet<>();
    static {
        RETRYABLE_CODES.add(0);           // Network error / offline
        RETRYABLE_CODES.add(408);         // Request timeout
        RETRYABLE_CODES.add(429);         // Too many requests
        for (int i = 500; i <= 599; i++) {
            RETRYABLE_CODES.add(i);       // Server errors
        }
    }

    // Permanent error codes: not generally retryable
    private static final Set<Integer> PERMANENT_CODES = new HashSet<>();
    static {
        PERMANENT_CODES.add(400);         // Bad request
        PERMANENT_CODES.add(401);         // Unauthorized
        PERMANENT_CODES.add(403);         // Forbidden
        PERMANENT_CODES.add(404);         // Not found (special handling for GET)
        PERMANENT_CODES.add(409);         // Conflict
    }

    /**
     * Check if a status code is retryable.
     */
    public static boolean isRetryable(int statusCode) {
        return RETRYABLE_CODES.contains(statusCode);
    }

    /**
     * Check if a status code is permanently failed (not retryable).
     */
    public static boolean isPermanentError(int statusCode) {
        return PERMANENT_CODES.contains(statusCode);
    }

    /**
     * Special handling for GET 404 - should preserve original requestId for repost.
     */
    public static boolean isGetNotFound(String method, int statusCode) {
        // Returns true only for GET requests with 404 status
        return "GET".equals(method) && statusCode == 404;
    }

}
