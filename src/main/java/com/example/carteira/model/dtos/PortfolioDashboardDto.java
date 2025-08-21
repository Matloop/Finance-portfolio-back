// Crie este novo arquivo: PortfolioDashboardDto.java
package com.example.carteira.model.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@AllArgsConstructor // Construtor prático
public class PortfolioDashboardDto {
    private PortfolioSummaryDto summary;
    private AssetPercentage percentages;
    private Map<String, List<AssetPositionDto>> assets;
}