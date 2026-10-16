package io.paradaux.treasuryrestapi.dto;

import java.util.List;

public record BaltopResponse(int page,
                             int totalPages,
                             long totalItems,
                             List<BaltopEntry> items) {}
