package com.example.carteira.model.dtos;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AlphaVantageQuoteResponse(@JsonProperty("Global Quote") GlobalQuote quote) {}