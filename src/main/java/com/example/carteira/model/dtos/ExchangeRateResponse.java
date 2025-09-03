package com.example.carteira.model.dtos;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExchangeRateResponse(@JsonProperty("Realtime Currency Exchange Rate") ExchangeRateData exchangeRateData) {}