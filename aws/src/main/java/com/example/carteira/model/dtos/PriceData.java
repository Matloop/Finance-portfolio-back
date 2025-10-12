package com.example.carteira.model.dtos;

import java.math.BigDecimal;

// O DTO que representa o resultado de uma busca de preço
public record PriceData(String ticker, BigDecimal price) {}