package com.example.carteira.model.dtos;

import com.example.carteira.model.enums.AssetType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Novo DTO para a exibição no frontend
@Getter
@Setter
@AllArgsConstructor // Útil para criar na etapa de transformação
public class AssetTableRowDto {
    private String ticker;
    private String name;
    private BigDecimal totalQuantity;
    private BigDecimal averagePrice;
    private BigDecimal currentPrice;
    private BigDecimal currentValue;
    private BigDecimal profitability;
    private BigDecimal portfolioPercentage;
    AssetType assetType;
}