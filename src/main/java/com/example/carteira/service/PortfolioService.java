package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.*;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.example.carteira.model.enums.TransactionType;
import com.example.carteira.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.example.carteira.model.enums.Market.US;

@Service
public class PortfolioService {

    private final TransactionRepository transactionRepository;
    private final MarketDataService marketDataService;
    private final FixedIncomeService fixedIncomeService;

    public PortfolioService(TransactionRepository transactionRepository,
                            MarketDataService marketDataService,
                            FixedIncomeService fixedIncomeService) {
        this.transactionRepository = transactionRepository;
        this.marketDataService = marketDataService;
        this.fixedIncomeService = fixedIncomeService;
    }

    private AssetPositionDto consolidateTicker(String ticker) {
        List<Transaction> transactions = transactionRepository.findByTickerOrderByTransactionDateAsc(ticker);
        if (transactions.isEmpty()) {
            return null;
        }

        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalBuyQuantity = BigDecimal.ZERO;
        Market market = US;// Precisamos da quantidade total comprada para o preço médio

        for (Transaction t : transactions) {
            if (t.getTransactionType() == TransactionType.BUY) {
                BigDecimal costOfThisTransaction = t.getQuantity().multiply(t.getPricePerUnit());
                totalCost = totalCost.add(costOfThisTransaction);
                totalQuantity = totalQuantity.add(t.getQuantity());
                totalBuyQuantity = totalBuyQuantity.add(t.getQuantity());
                market = t.getMarket();
            } else { // Venda (SELL)
                totalQuantity = totalQuantity.subtract(t.getQuantity());
            }
        }

        if (totalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal averagePrice = totalBuyQuantity.compareTo(BigDecimal.ZERO) == 0 ?
                BigDecimal.ZERO :
                totalCost.divide(totalBuyQuantity, 4, RoundingMode.HALF_UP);

        BigDecimal currentPrice = marketDataService.getPrice(ticker);
        BigDecimal currentValue = currentPrice.multiply(totalQuantity);

        // O custo total para o cálculo de lucro deve refletir a posição atual
        BigDecimal currentPositionCost = totalQuantity.multiply(averagePrice);
        BigDecimal profitOrLoss = currentValue.subtract(currentPositionCost);

        BigDecimal profitability = currentPositionCost.compareTo(BigDecimal.ZERO) == 0 ?
                BigDecimal.ZERO :
                profitOrLoss.divide(currentPositionCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

        AssetPositionDto position = new AssetPositionDto();
        position.setTicker(ticker);
        position.setAssetType(transactions.get(0).getAssetType());
        position.setTotalQuantity(totalQuantity);
        position.setAveragePrice(averagePrice);
        position.setMarket(market);
        position.setTotalInvested(currentPositionCost.setScale(2, RoundingMode.HALF_UP));
        position.setCurrentValue(currentValue.setScale(2, RoundingMode.HALF_UP));
        position.setProfitOrLoss(profitOrLoss.setScale(2, RoundingMode.HALF_UP));
        position.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));

        return position;
    }

    private AssetPositionDto calculateAssetPosition(AssetKey key, List<Transaction> transactions) {
        // A lógica de cálculo de preço médio, etc., é a mesma de antes.
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalBuyQuantity = BigDecimal.ZERO;

        for (Transaction t : transactions) {
            if (t.getTransactionType() == TransactionType.BUY) {
                totalCost = totalCost.add(t.getQuantity().multiply(t.getPricePerUnit()));
                totalQuantity = totalQuantity.add(t.getQuantity());
                totalBuyQuantity = totalBuyQuantity.add(t.getQuantity());
            } else {
                totalQuantity = totalQuantity.subtract(t.getQuantity());
            }
        }

        if (totalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null; // A posição foi zerada.
        }

        BigDecimal averagePrice = totalCost.divide(totalBuyQuantity, 4, RoundingMode.HALF_UP);
        BigDecimal currentPrice = marketDataService.getPrice(key.ticker());
        BigDecimal currentValue = currentPrice.multiply(totalQuantity);
        BigDecimal currentPositionCost = totalQuantity.multiply(averagePrice);
        BigDecimal profitOrLoss = currentValue.subtract(currentPositionCost);
        BigDecimal profitability = currentPositionCost.compareTo(BigDecimal.ZERO) > 0 ?
                profitOrLoss.divide(currentPositionCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)) :
                BigDecimal.ZERO;

        // CRIAÇÃO CORRETA DO DTO, AGORA COM TODOS OS DADOS
        AssetPositionDto position = new AssetPositionDto();
        position.setTicker(key.ticker());
        position.setAssetType(key.assetType()); // Usa o tipo da chave
        position.setMarket(key.market());       // USA O MERCADO DA CHAVE (CORREÇÃO CRÍTICA)
        position.setTotalQuantity(totalQuantity);
        position.setAveragePrice(averagePrice);
        position.setTotalInvested(currentPositionCost.setScale(2, RoundingMode.HALF_UP));
        position.setCurrentValue(currentValue.setScale(2, RoundingMode.HALF_UP));
        position.setProfitOrLoss(profitOrLoss.setScale(2, RoundingMode.HALF_UP));
        position.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));

        return position;
    }

    private Map<String, List<AssetSubCategoryDto>> getConsolidatedAssets() {
        // Passo 1: Busca todas as transações em UMA ÚNICA query.
        List<Transaction> allTransactions = transactionRepository.findAll();

        // Passo 2: Agrupa as transações em memória por sua chave única (ticker, tipo, mercado).
        Map<AssetKey, List<Transaction>> groupedTransactions = allTransactions.stream()
                .filter(t -> t.getTicker() != null) // Filtro de segurança
                .collect(Collectors.groupingBy(t -> new AssetKey(t.getTicker(), t.getAssetType(), t.getMarket())));

        // Passo 3: Mapeia cada grupo de transações para uma Posição de Ativo (AssetPositionDto).
        Stream<AssetPositionDto> transactionalAssetsStream = groupedTransactions.entrySet().stream()
                .map(entry -> calculateAssetPosition(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull);

        // Passo 4: Busca as posições de Renda Fixa.
        Stream<AssetPositionDto> fixedIncomeAssetsStream = fixedIncomeService.getAllFixedIncomePositions().stream();
        List<AssetPositionDto> allAssets = Stream.concat(transactionalAssetsStream, fixedIncomeAssetsStream)
                .collect(Collectors.toList());
        Map<String, Map<String, List<AssetPositionDto>>> groupedAndSubGroupedAssets = allAssets.stream()
                .collect(Collectors.groupingBy(
                        // Regra de classificação para o Nível 1 (Abas: Brasil, EUA, Cripto)
                        asset -> {
                            if (asset.getAssetType() == AssetType.CRYPTO) return "Cripto";
                            if (asset.getMarket() == Market.US) return "EUA";
                            return "Brasil";
                        },
                        // Coletor aninhado para o Nível 2 (Accordions: Ações, ETFs, Renda Fixa)
                        Collectors.groupingBy(
                                asset -> getFriendlyAssetTypeName(asset.getAssetType()) // Usa um helper para nomes amigáveis
                        )
                ));
        Map<String, List<AssetSubCategoryDto>> finalAssetMap = new HashMap<>();
        groupedAndSubGroupedAssets.forEach((categoryName, subCategoryMap) -> {
            List<AssetSubCategoryDto> subCategoryList = subCategoryMap.entrySet().stream()
                    .map(entry -> {
                        String subCategoryName = entry.getKey();
                        List<AssetPositionDto> assetsInSubCategory = entry.getValue();

                        BigDecimal totalValue = assetsInSubCategory.stream()
                                .map(AssetPositionDto::getCurrentValue)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                        return new AssetSubCategoryDto(subCategoryName, totalValue, assetsInSubCategory);
                    })
                    .collect(Collectors.toList());
            finalAssetMap.put(categoryName, subCategoryList);
        });

        // Passo 5: Concatena os dois streams e retorna a lista final.
        return finalAssetMap;
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

    private String getAssetCategoryKeyForPercentages(AssetPositionDto asset) {
        String market = (asset.getMarket() != null) ? asset.getMarket().name() : asset.getAssetType().name();
        return asset.getAssetType().name() + "_" + market;
    }

    private Map<String, BigDecimal> calculatePercentages(List<AssetPositionDto> allAssets, BigDecimal totalHeritage) {
        if (totalHeritage.compareTo(BigDecimal.ZERO) <= 0) {
            return Map.of();
        }

        Map<String, BigDecimal> totalsByCategory = allAssets.stream()
                .collect(Collectors.groupingBy(
                        this::getAssetCategoryKeyForPercentages,
                        Collectors.reducing(BigDecimal.ZERO, AssetPositionDto::getCurrentValue, BigDecimal::add)
                ));

        return totalsByCategory.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue()
                                .divide(totalHeritage, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                ));
    }

    public PortfolioDashboardDto getPortfolioDashboardData() {
        // 1. CALCULA A LISTA COMPLETA DE POSIÇÕES DE FORMA EFICIENTE
        List<AssetPositionDto> allAssets = getConsolidatedPortfolio();

        // 2. GERA OS DADOS PARA O DASHBOARD A PARTIR DA LISTA CONSOLIDADA

        // --- Lógica do Resumo (Summary) ---
        BigDecimal totalHeritage = allAssets.stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalInvested = allAssets.stream().map(AssetPositionDto::getTotalInvested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profitability = BigDecimal.ZERO;
        if (totalInvested.compareTo(BigDecimal.ZERO) > 0) {
            profitability = (totalHeritage.subtract(totalInvested)).divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }
        PortfolioSummaryDto summary = new PortfolioSummaryDto(totalHeritage, totalInvested, profitability);
        //faz a montagem das porcentagens
        Map<String, AllocationNodeDto> percentages = buildAllocationTree(allAssets, totalHeritage);

        Map<String, List<AssetSubCategoryDto>> assetsGrouped = buildAssetHierarchy(allAssets);

        return new PortfolioDashboardDto(summary, percentages, assetsGrouped);
    }

    private List<AssetPositionDto> getConsolidatedPortfolio() {
        List<Transaction> allTransactions = transactionRepository.findAll();
        Map<AssetKey, List<Transaction>> groupedTransactions = allTransactions.stream()
                .filter(t -> t.getTicker() != null)
                .collect(Collectors.groupingBy(t -> new AssetKey(t.getTicker(), t.getAssetType(), t.getMarket())));

        Stream<AssetPositionDto> transactionalAssetsStream = groupedTransactions.entrySet().stream()
                .map(entry -> calculateAssetPosition(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull);

        Stream<AssetPositionDto> fixedIncomeAssetsStream = fixedIncomeService.getAllFixedIncomePositions().stream();

        return Stream.concat(transactionalAssetsStream, fixedIncomeAssetsStream).collect(Collectors.toList());
    }

    private Map<String, List<AssetSubCategoryDto>> buildAssetHierarchy(List<AssetPositionDto> allAssets) {
        // Passo 1: Agrupamento em dois níveis (Categoria Principal -> Tipo de Ativo -> Lista de Ativos)
        Map<String, Map<String, List<AssetPositionDto>>> groupedMap = allAssets.stream()
                .collect(Collectors.groupingBy(
                        // Classificador de Nível 1 (Chaves: "Brasil", "EUA", "Cripto")
                        asset -> {
                            if (asset.getAssetType() == AssetType.CRYPTO) return "Cripto";
                            if (asset.getMarket() == Market.US) return "EUA";
                            return "Brasil";
                        },
                        // Coletor de Nível 2: Agrupa novamente por tipo de ativo
                        Collectors.groupingBy(
                                asset -> getFriendlyAssetTypeName(asset.getAssetType())
                        )
                ));

        // Passo 2: Transformar o mapa agrupado na estrutura de DTOs final
        Map<String, List<AssetSubCategoryDto>> finalResult = new HashMap<>();
        groupedMap.forEach((categoryName, subCategoryMap) -> {
            List<AssetSubCategoryDto> subCategoryList = subCategoryMap.entrySet().stream()
                    .map(entry -> {
                        String subCategoryName = entry.getKey();
                        List<AssetPositionDto> assetsInSubCategory = entry.getValue();

                        BigDecimal totalValue = assetsInSubCategory.stream()
                                .map(AssetPositionDto::getCurrentValue)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                        return new AssetSubCategoryDto(subCategoryName, totalValue, assetsInSubCategory);
                    })
                    .collect(Collectors.toList());

            finalResult.put(categoryName, subCategoryList);
        });

        return finalResult;
    }

    private Map<String, AllocationNodeDto> buildAllocationTree(List<AssetPositionDto> allAssets, BigDecimal totalHeritage) {
        if (totalHeritage.compareTo(BigDecimal.ZERO) <= 0) {
            return Map.of();
        }

        // NÍVEL 1: Agrupar por Categoria Principal (Brazil, USA, Crypto)
        Map<String, List<AssetPositionDto>> byCategory = allAssets.stream()
                .collect(Collectors.groupingBy(asset -> {
                    if (asset.getAssetType() == AssetType.CRYPTO) return "crypto";
                    if (asset.getMarket() == Market.US) return "usa";
                    return "brazil"; // B3 e Renda Fixa caem aqui
                }));

        // Construir a árvore final
        return byCategory.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            String categoryKey = entry.getKey();
                            List<AssetPositionDto> categoryAssets = entry.getValue();

                            BigDecimal categoryTotal = categoryAssets.stream()
                                    .map(AssetPositionDto::getCurrentValue)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                            BigDecimal categoryPercentage = categoryTotal.divide(totalHeritage, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

                            // NÍVEL 2: Construir os filhos de cada categoria
                            Map<String, AllocationNodeDto> children = buildChildrenForCategory(categoryKey, categoryAssets, categoryTotal);

                            return new AllocationNodeDto(categoryPercentage, children);
                        }
                ));
    }

    private Map<String, AllocationNodeDto> buildChildrenForCategory(String category, List<AssetPositionDto> assets, BigDecimal categoryTotal) {
        if (category.equals("crypto")) {
            // Para cripto, o próximo nível são os próprios ativos
            return assets.stream().collect(Collectors.toMap(
                    AssetPositionDto::getTicker,
                    asset -> new AllocationNodeDto(
                            asset.getCurrentValue().divide(categoryTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    )
            ));
        }

        // Para Brasil e EUA, o próximo nível é por tipo de ativo (Ações, ETFs, Renda Fixa)
        Map<String, List<AssetPositionDto>> byAssetType = assets.stream()
                .collect(Collectors.groupingBy(asset -> asset.getAssetType().name().toLowerCase()));

        return byAssetType.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            String assetTypeKey = entry.getKey();
                            List<AssetPositionDto> assetTypeAssets = entry.getValue();

                            BigDecimal assetTypeTotal = assetTypeAssets.stream()
                                    .map(AssetPositionDto::getCurrentValue)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                            BigDecimal assetTypePercentage = assetTypeTotal.divide(categoryTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

                            // NÍVEL 3: Construir os filhos para cada tipo de ativo (os tickers individuais)
                            Map<String, AllocationNodeDto> grandchildren = assetTypeAssets.stream()
                                    .collect(Collectors.toMap(
                                            AssetPositionDto::getTicker,
                                            asset -> new AllocationNodeDto(
                                                    asset.getCurrentValue().divide(assetTypeTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                                            )
                                    ));

                            return new AllocationNodeDto(assetTypePercentage, grandchildren);
                        }
                ));
    }




    private String getAssetDisplayCategoryKey(AssetPositionDto asset) {
        return asset.getAssetType().name().toLowerCase();
    }

    private String getAssetCategoryKey(AssetPositionDto asset) {
        // Para Renda Fixa e Cripto, o "mercado" pode ser o próprio tipo para consistência.
        String market = (asset.getMarket() != null) ? asset.getMarket().name() : asset.getAssetType().name();
        return asset.getAssetType().name() + "_" + market;
    }

    public PortfolioSummaryDto getPortfolioSummary() {
        //get total heritage
        BigDecimal totalHeritage = BigDecimal.ZERO;
        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal profitability;

        for(AssetPositionDto dto : getConsolidatedPortfolio()) {
            if(dto != null) {
                totalHeritage = totalHeritage.add(dto.getCurrentValue());
                totalInvested = totalInvested.add(dto.getTotalInvested());
            }
        }

        if (totalInvested.compareTo(BigDecimal.ZERO) <= 0) {
            return new PortfolioSummaryDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        profitability = (totalHeritage.subtract(totalInvested)).divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));


        return new PortfolioSummaryDto(totalHeritage,totalInvested,profitability);
    }

    private record AssetKey(String ticker, AssetType assetType, Market market) {}

}