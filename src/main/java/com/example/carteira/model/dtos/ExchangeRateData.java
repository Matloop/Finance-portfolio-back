package com.example.carteira.model.dtos;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExchangeRateData(@JsonProperty("5. Exchange Rate") BigDecimal exchangeRate) {}