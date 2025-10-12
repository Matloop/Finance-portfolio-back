package com.example.carteira.model.dtos;

import java.math.BigDecimal;

public record InvestedDetailDto(
        String name,
        BigDecimal investedValue
) {}