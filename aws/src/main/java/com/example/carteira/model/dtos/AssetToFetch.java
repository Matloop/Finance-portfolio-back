package com.example.carteira.model.dtos;

import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;

public record AssetToFetch(String ticker, Market market, AssetType assetType) {
}
