package com.example.carteira.model.dtos;

import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.example.carteira.model.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionRequest(
        String ticker,
        AssetType assetType,
        TransactionType transactionType,
        BigDecimal quantity,
        BigDecimal pricePerUnit,
        Market market,
        LocalDate transactionDate
) {}