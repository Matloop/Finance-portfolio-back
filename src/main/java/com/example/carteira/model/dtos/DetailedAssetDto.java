package com.example.carteira.model.dtos;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
public class DetailedAssetDto {
    private Long id;
    private String name;
    private String type;
    private BigDecimal investedAmount; // Valor Investido
    private BigDecimal grossValue;     // Valor Bruto (Antes do IR)
    private BigDecimal netValue;       // Valor Líquido (Atual, após o IR)
    private BigDecimal incomeTax;      // Imposto de Renda Devido
    private BigDecimal netProfit;      // Lucro Líquido
    private BigDecimal profitability;  // Rentabilidade Líquida
}