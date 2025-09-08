package com.example.carteira.model.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
@AllArgsConstructor
@Getter
@Setter
public class AssetSubCategoryDto {
    String categoryName;
    BigDecimal totalValue;
    List<AssetPositionDto> assets;
}
