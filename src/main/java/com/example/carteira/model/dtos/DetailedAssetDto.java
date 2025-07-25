// Location: src/main/java/com/example/carteira/dto/DetailedAssetDto.java
package com.example.carteira.model.dtos;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
@Getter
@Setter
public class DetailedAssetDto {

    private Long id;
    private String name;
    private String type;
    private BigDecimal investedAmount;
    private BigDecimal currentValue;
    private BigDecimal profit;
    private BigDecimal profitability;


}