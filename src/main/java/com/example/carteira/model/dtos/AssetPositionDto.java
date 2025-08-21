package com.example.carteira.model.dtos;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
public class AssetPositionDto {
    private Long id; // ID da transação ou do ativo de RF
    private String ticker;
    private String name;// Ticker ou Nome do Ativo
    private String assetType; // "STOCK", "CRYPTO", ou "FIXED_INCOME"
    private BigDecimal totalInvested;
    private BigDecimal currentValue;
    private BigDecimal profitOrLoss;
    private BigDecimal profitability;

    // Campos que só se aplicam a Ações/Cripto (podem ser nulos para Renda Fixa)
    private BigDecimal totalQuantity;
    private BigDecimal averagePrice;
}