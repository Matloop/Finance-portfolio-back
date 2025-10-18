package com.example.carteira.model.dtos;

import com.example.carteira.model.enums.AssetCategory;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
public class AssetPositionDto {
    private Long id; // ID da transação ou do ativo de RF
    private String ticker;
    private String name;// Ticker ou Nome do Ativo
    private AssetType assetType; // "STOCK", "CRYPTO", ou "FIXED_INCOME"
    private BigDecimal totalInvested;
    private BigDecimal currentValue;
    private BigDecimal profitOrLoss;
    private BigDecimal profitability;
    private Market market;
    private BigDecimal currentPrice;
    private BigDecimal totalQuantity;
    private BigDecimal averagePrice;

    public String getDisplayCategoryKey() {
        if (this.getAssetType().getCategory() == AssetCategory.CRYPTO) {
            return "Cripto";
        }
        if (this.getMarket() == Market.US) {
            return "EUA";
        }
        // Renda Fixa e Ações/ETFs do Brasil caem aqui.
        return "Brasil";
    }
}