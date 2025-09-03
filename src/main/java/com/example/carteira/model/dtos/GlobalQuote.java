package com.example.carteira.model.dtos;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GlobalQuote(@JsonProperty("01. symbol") String symbol, @JsonProperty("05. price") BigDecimal price) {}