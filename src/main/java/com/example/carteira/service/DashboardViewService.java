package com.example.carteira.service;

import com.example.carteira.model.dtos.AllocationNodeDto;
import com.example.carteira.model.dtos.AssetPositionDto;
import com.example.carteira.model.dtos.AssetSubCategoryDto;
import com.example.carteira.model.dtos.AssetTableRowDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardViewService {
        private static final Logger log = LoggerFactory.getLogger(DashboardViewService.class);
        //Organiza ativos para exibição no front com base no valor
        public Map<String, List<AssetSubCategoryDto>> buildAssetHierarchy(List<AssetPositionDto> allAssets, BigDecimal totalHeritage, Set<String> cashEquivalentIdentifiers) {
            //Agrupa por tipo de ativo
            Map<String, Map<String, List<AssetPositionDto>>> groupedMap = allAssets.stream()
                    .collect(Collectors.groupingBy(
                            AssetPositionDto::getDisplayCategoryKey, // Usa o novo método do DTO
                            Collectors.groupingBy(asset -> asset.getAssetType().getFriendlyName()) // Usa o novo método do Enum
                    ));
            //Cria o resultado final, mostrando o valor de cada tipo de ativo e ativo, em hierarquia
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
                                        String identifier = asset.getTicker() != null ? asset.getTicker() : asset.getName();
                                        boolean isCash = cashEquivalentIdentifiers.contains(identifier);
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
                                                asset.getAssetType(),
                                                isCash
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
    //Organiza ativos para exibição no front com base na porcentagem
    public Map<String, AllocationNodeDto> buildAllocationTree(List<AssetPositionDto> allAssets, BigDecimal totalHeritage) {
        if (totalHeritage.compareTo(BigDecimal.ZERO) <= 0) return Map.of();
        log.info("==> [VIEW_SERVICE] Agrupando para o Gráfico de Pizza...");
        //Organiza por tipo de ativo
        Map<String, List<AssetPositionDto>> byCategory = allAssets.stream()
                .collect(Collectors.groupingBy(AssetPositionDto::getDisplayCategoryKey));
        log.info("==> [VIEW_SERVICE] Categorias de topo encontradas para o gráfico: {}", byCategory.keySet());

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
        if ("Caixa".equals(category)) {
            return Collections.emptyMap();
        }
            //Já que crypto não tem subcategoria, só retorna por ativos
        if ("Cripto".equalsIgnoreCase(category)) {
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
                .collect(Collectors.groupingBy(asset -> asset.getAssetType().getFriendlyName()));

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

}