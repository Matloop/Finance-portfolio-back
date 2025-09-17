package com.example.carteira.model.dtos;

import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;

// Usando record para um DTO simples e imutável
public record AssetSearchResultDto(
        String ticker,
        String name,
        AssetType assetType,
        Market market
) {}