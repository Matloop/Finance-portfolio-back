package com.example.carteira.model.dtos;

import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.TransactionType;

import java.math.BigDecimal;

public record TransactionRequest(
        String ticker,
        AssetType assetType,
        TransactionType transactionType,
        BigDecimal quantity,
        BigDecimal pricePerUnit
) {}