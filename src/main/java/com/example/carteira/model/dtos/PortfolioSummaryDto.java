package com.example.carteira.model.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@AllArgsConstructor
public class PortfolioSummaryDto {
    BigDecimal totalHeritage;
    BigDecimal totalInvested;
    BigDecimal profitability;
    BigDecimal yearlyProfitability;
}
