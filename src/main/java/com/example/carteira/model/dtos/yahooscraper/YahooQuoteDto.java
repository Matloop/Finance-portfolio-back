package com.example.carteira.model.dtos.yahooscraper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;



@JsonIgnoreProperties(ignoreUnknown = true)
public record YahooQuoteDto(String symbol, String shortname, String quoteType) {}

