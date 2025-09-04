package com.example.carteira.model.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@AllArgsConstructor
@Setter
@Getter
public class PortfolioPercentagesDto {
    private BigDecimal stockPercentage;
    private BigDecimal cryptoPercentage;
    private BigDecimal fixedIncomePercentage;
}
