package io.paradaux.treasuryapi.model;

/** An administrator-configured source; selector identity also fences stale snapshots. */
public record GroupSource(int groupId, String source, String selector) {}
