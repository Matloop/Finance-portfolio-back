package com.example.carteira.model.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CoinGeckoCoin(String id, String symbol, String name) {}
