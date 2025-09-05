package com.example.carteira.model.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

@AllArgsConstructor
@Setter
@Getter
public class PortfolioPercentagesDto {
    private Map<String, BigDecimal> byAssetBrazil;
    private Map<String,BigDecimal> byAssetUsa;
    private Map<String, BigDecimal> byAssetCrypto;
    private Map<String, BigDecimal> byCategory;


}
