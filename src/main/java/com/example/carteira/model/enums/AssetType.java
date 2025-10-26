// no arquivo: src/main/java/com/example/carteira/model/enums/AssetType.java
package com.example.carteira.model.enums;

public enum AssetType {

    STOCK(AssetCategory.EQUITY, "Ações"),
    ETF(AssetCategory.EQUITY, "ETFs"),
    DOLLAR(AssetCategory.CURRENCY, "Dólar"),
    CASH(AssetCategory.CURRENCY, "CAIXA"),

    CRYPTO(AssetCategory.CRYPTO, "Criptomoedas"),

    LCI(AssetCategory.FIXED_INCOME, "Renda Fixa"),
    LCA(AssetCategory.FIXED_INCOME, "Renda Fixa"),
    CRI(AssetCategory.FIXED_INCOME, "Renda Fixa"),
    CRA(AssetCategory.FIXED_INCOME, "Renda Fixa"),
    CDB(AssetCategory.FIXED_INCOME, "Renda Fixa"),
    FUND(AssetCategory.FUND, "Fundos imobiliários"),
    TESOURO_DIRETO(AssetCategory.FIXED_INCOME, "Renda Fixa"),
    DEBENTURE(AssetCategory.FIXED_INCOME, "Renda Fixa");

    private final AssetCategory category;
    private final String friendlyName;
    AssetType(AssetCategory category, String friendlyName) {
        this.category = category;
        this.friendlyName = friendlyName;
    }

    public AssetCategory getCategory() {
        return this.category;
    }

    public String getFriendlyName() {
        return friendlyName;
    }
}