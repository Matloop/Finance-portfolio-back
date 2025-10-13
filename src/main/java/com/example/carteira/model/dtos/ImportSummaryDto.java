
package com.example.carteira.model.dtos;

import java.util.List;

public record ImportSummaryDto(
        int successCount,
        int errorCount,
        List<String> errors
) {}