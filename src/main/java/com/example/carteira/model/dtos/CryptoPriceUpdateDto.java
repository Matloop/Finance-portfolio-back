package com.example.carteira.model.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@AllArgsConstructor
public class CryptoPriceUpdateDto {
    private String ticker; // "BTC", "ETH"
    private BigDecimal price; // O novo preço
}