package com.example.carteira.service;

import com.example.carteira.model.dtos.AllocationNodeDto;
import com.example.carteira.model.dtos.AssetPositionDto;
import com.example.carteira.model.dtos.AssetSubCategoryDto;
import com.example.carteira.model.dtos.AssetTableRowDto;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DashboardViewService {

    public Map<String, List<AssetSubCategoryDto>> buildAssetHierarchy(List<AssetPositionDto> allAssets, BigDecimal totalHeritage) {
        Map<String, Map<String, List<AssetPositionDto>>> groupedMap = allAssets.stream()
                .collect(Collectors.groupingBy(
                        asset -> {
                            if (AssetType.FIXED_INCOME.equals(asset.getAssetType())) return "Brasil";
                            if (AssetType.CRYPTO.equals(asset.getAssetType())) return "Cripto";
                            if (Market.US.equals(asset.getMarket())) return "EUA";
                            return "Brasil";
                        },
                        Collectors.groupingBy(asset -> getFriendlyAssetTypeName(asset.getAssetType()))
                ));

        Map<String, List<AssetSubCategoryDto>> finalResult = new HashMap<>();
        groupedMap.forEach((categoryName, subCategoryMap) -> {
            List<AssetSubCategoryDto> subCategoryList = subCategoryMap.entrySet().stream()
                    .map(entry -> {
                        String subCategoryName = entry.getKey();
                        List<AssetPositionDto> assetsInSubCategory = entry.getValue();
                        BigDecimal totalValue = assetsInSubCategory.stream()
                                .map(AssetPositionDto::getCurrentValue)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                        List<AssetTableRowDto> assetTableRows = assetsInSubCategory.stream()
                                .map(asset -> {
                                    BigDecimal portfolioPercentage = totalHeritage.compareTo(BigDecimal.ZERO) > 0 ?
                                            asset.getCurrentValue()
                                                    .divide(totalHeritage, 4, RoundingMode.HALF_UP)
                                                    .multiply(BigDecimal.valueOf(100)) :
                                            BigDecimal.ZERO;
                                    return new AssetTableRowDto(
                                            asset.getTicker(),
                                            asset.getName(),
                                            asset.getTotalQuantity(),
                                            asset.getAveragePrice(),
                                            asset.getCurrentPrice(),
                                            asset.getCurrentValue(),
                                            asset.getProfitability(),
                                            portfolioPercentage,
                                            asset.getAssetType()
                                    );
                                })
                                .sorted(Comparator.comparing(AssetTableRowDto::getCurrentValue).reversed())
                                .collect(Collectors.toList());
                        return new AssetSubCategoryDto(subCategoryName, totalValue, assetTableRows);
                    })
                    .sorted(Comparator.comparing(AssetSubCategoryDto::getTotalValue).reversed())
                    .collect(Collectors.toList());
            finalResult.put(categoryName, subCategoryList);
        });
        return finalResult;
    }

    public Map<String, AllocationNodeDto> buildAllocationTree(List<AssetPositionDto> allAssets, BigDecimal totalHeritage) {
        if (totalHeritage.compareTo(BigDecimal.ZERO) <= 0) return Map.of();

        Map<String, List<AssetPositionDto>> byCategory = allAssets.stream()
                .collect(Collectors.groupingBy(asset -> {
                    if (AssetType.FIXED_INCOME.equals(asset.getAssetType())) return "brazil";
                    if (AssetType.CRYPTO.equals(asset.getAssetType())) return "crypto";
                    if (Market.US.equals(asset.getMarket())) return "usa";
                    return "brazil";
                }));

        return byCategory.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    BigDecimal categoryTotal = entry.getValue().stream()
                            .map(AssetPositionDto::getCurrentValue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal categoryPercentage = categoryTotal
                            .divide(totalHeritage, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                    Map<String, AllocationNodeDto> children = buildChildrenForCategory(
                            entry.getKey(),
                            entry.getValue(),
                            categoryTotal
                    );
                    return new AllocationNodeDto(categoryPercentage, children);
                }
        ));
    }

    private Map<String, AllocationNodeDto> buildChildrenForCategory(String category, List<AssetPositionDto> assets, BigDecimal categoryTotal) {
        if ("crypto".equals(category)) {
            Map<String, BigDecimal> aggregatedValues = assets.stream()
                    .collect(Collectors.groupingBy(
                            AssetPositionDto::getTicker,
                            Collectors.reducing(BigDecimal.ZERO, AssetPositionDto::getCurrentValue, BigDecimal::add)
                    ));
            return aggregatedValues.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> new AllocationNodeDto(
                                    entry.getValue().divide(categoryTotal, 4, RoundingMode.HALF_UP)
                                            .multiply(BigDecimal.valueOf(100))
                            )
                    ));
        }

        Map<String, List<AssetPositionDto>> byAssetType = assets.stream()
                .collect(Collectors.groupingBy(asset -> asset.getAssetType().name().toLowerCase()));

        return byAssetType.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    BigDecimal assetTypeTotal = entry.getValue().stream()
                            .map(AssetPositionDto::getCurrentValue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal assetTypePercentage = assetTypeTotal
                            .divide(categoryTotal, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));

                    Map<String, BigDecimal> aggregatedByTicker = entry.getValue().stream()
                            .collect(Collectors.groupingBy(
                                    AssetPositionDto::getTicker,
                                    Collectors.reducing(BigDecimal.ZERO, AssetPositionDto::getCurrentValue, BigDecimal::add)
                            ));

                    Map<String, AllocationNodeDto> grandchildren = aggregatedByTicker.entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    tickerEntry -> new AllocationNodeDto(
                                            tickerEntry.getValue()
                                                    .divide(assetTypeTotal, 4, RoundingMode.HALF_UP)
                                                    .multiply(BigDecimal.valueOf(100))
                                    )
                            ));
                    return new AllocationNodeDto(assetTypePercentage, grandchildren);
                }
        ));
    }

    private String getFriendlyAssetTypeName(AssetType assetType) {
        return switch (assetType) {
            case STOCK -> "Ações";
            case ETF -> "ETFs";
            case CRYPTO -> "Criptomoedas";
            case FIXED_INCOME -> "Renda Fixa";
            default -> assetType.name();
        };
    }
}