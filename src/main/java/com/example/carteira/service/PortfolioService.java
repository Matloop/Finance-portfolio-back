package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.*;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.example.carteira.model.enums.TransactionType;
import com.example.carteira.repository.TransactionRepository;
import com.example.carteira.service.util.ExchangeRateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Serviço principal para consolidar e calcular os dados da carteira de investimentos.
 */
@Service
public class PortfolioService {

    private static final Logger logger = LoggerFactory.getLogger(PortfolioService.class);
    private final TransactionRepository transactionRepository;
    private final MarketDataService marketDataService;
    private final FixedIncomeService fixedIncomeService;
    private final ExchangeRateService exchangeRateService;

    public PortfolioService(TransactionRepository transactionRepository,
                            MarketDataService marketDataService,
                            FixedIncomeService fixedIncomeService,
                            ExchangeRateService exchangeRateService) {
        this.transactionRepository = transactionRepository;
        this.marketDataService = marketDataService;
        this.fixedIncomeService = fixedIncomeService;
        this.exchangeRateService = exchangeRateService;
    }

    // --- PONTOS DE ENTRADA PÚBLICOS (CHAMADOS PELO CONTROLLER) ---

    /**
     * Monta o DTO completo para o estado atual do dashboard.
     */
    public PortfolioDashboardDto getPortfolioDashboardData() {
        List<AssetPositionDto> allCurrentAssets = getConsolidatedPortfolio(LocalDate.now());

        BigDecimal totalHeritage = allCurrentAssets.stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalInvested = allCurrentAssets.stream().map(AssetPositionDto::getTotalInvested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profitability = totalHeritage.compareTo(totalInvested) != 0 && totalInvested.compareTo(BigDecimal.ZERO) != 0
                ? (totalHeritage.subtract(totalInvested)).divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        PortfolioSummaryDto summary = new PortfolioSummaryDto(totalHeritage, totalInvested, profitability);
        Map<String, AllocationNodeDto> percentages = buildAllocationTree(allCurrentAssets, totalHeritage);
        Map<String, List<AssetSubCategoryDto>> assetsGrouped = buildAssetHierarchy(allCurrentAssets, totalHeritage);

        return new PortfolioDashboardDto(summary, percentages, assetsGrouped);
    }

    /**
     * Monta o DTO com os dados históricos para o gráfico de evolução do patrimônio.
     */
    public PortfolioEvolutionDto getPortfolioEvolutionData() {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 12; i++) {
            dates.add(today.minusMonths(i).withDayOfMonth(1));
        }
        Collections.reverse(dates);

        List<Transaction> allTransactions = transactionRepository.findAll();

        List<PortfolioEvolutionPointDto> evolutionPoints = dates.stream()
                .map(date -> calculatePortfolioSnapshot(allTransactions, date))
                .collect(Collectors.toList());

        // Adiciona o ponto de "hoje" para garantir consistência visual com os cards.
        PortfolioEvolutionPointDto todaySnapshot = calculatePortfolioSnapshot(allTransactions, today);
        evolutionPoints.add(todaySnapshot);

        return new PortfolioEvolutionDto(evolutionPoints);
    }


    // --- MÉTODOS DE CÁLCULO E CONSOLIDAÇÃO ---

    /**
     * Cria um "snapshot" da carteira em uma data específica.
     */
    private PortfolioEvolutionPointDto calculatePortfolioSnapshot(List<Transaction> allTransactions, LocalDate date) {
        List<Transaction> transactionsUpToDate = allTransactions.stream()
                .filter(t -> !t.getTransactionDate().isAfter(date))
                .collect(Collectors.toList());

        List<AssetPositionDto> positions = getConsolidatedPortfolio(transactionsUpToDate, date);

        BigDecimal patrimonio = positions.stream().filter(Objects::nonNull).map(AssetPositionDto::getCurrentValue).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal valorAplicado = positions.stream().filter(Objects::nonNull).map(AssetPositionDto::getTotalInvested).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PortfolioEvolutionPointDto(
                date.format(DateTimeFormatter.ofPattern("MM/yy")),
                patrimonio.setScale(2, RoundingMode.HALF_UP),
                valorAplicado.setScale(2, RoundingMode.HALF_UP)
        );
    }

    /**
     * Ponto de entrada público para a consolidação, busca todas as transações.
     */
    private List<AssetPositionDto> getConsolidatedPortfolio(LocalDate calculationDate) {
        List<Transaction> allTransactions = transactionRepository.findAll();
        return getConsolidatedPortfolio(allTransactions, calculationDate);
    }

    /**
     * Método central que consolida uma lista de transações para uma data específica.
     */
    private List<AssetPositionDto> getConsolidatedPortfolio(List<Transaction> transactions, LocalDate calculationDate) {
        Map<AssetKey, List<Transaction>> groupedTransactions = transactions.stream()
                .filter(t -> t.getTicker() != null)
                .collect(Collectors.groupingBy(t -> new AssetKey(t.getTicker(), t.getAssetType(), t.getMarket())));

        Stream<AssetPositionDto> transactionalAssetsStream = groupedTransactions.entrySet().parallelStream()
                .map(entry -> calculateSinglePosition(entry.getKey(), entry.getValue(), calculationDate))
                .filter(Objects::nonNull);

        // TODO: Implementar lógica de renda fixa histórica
        // Stream<AssetPositionDto> fixedIncomeAssetsStream = fixedIncomeService.getAllFixedIncomePositionsForDate(calculationDate).stream();

        return transactionalAssetsStream.collect(Collectors.toList());
    }

    /**
     * Calcula a posição de um único ativo para uma data específica (atual ou histórica).
     */
    private AssetPositionDto calculateSinglePosition(AssetKey key, List<Transaction> transactions, LocalDate calculationDate) {
        // 1. Calcula quantidade e preço médio na moeda original da transação.
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalBuyQuantity = BigDecimal.ZERO;
        for (Transaction t : transactions) {
            if (TransactionType.BUY.equals(t.getTransactionType())) {
                BigDecimal transactionCost = t.getQuantity().multiply(t.getPricePerUnit());
                if (t.getOtherCosts() != null) {
                    transactionCost = transactionCost.add(t.getOtherCosts());
                }
                totalCost = totalCost.add(transactionCost);
                totalQuantity = totalQuantity.add(t.getQuantity());
                totalBuyQuantity = totalBuyQuantity.add(t.getQuantity());
            } else {
                totalQuantity = totalQuantity.subtract(t.getQuantity());
            }
        }
        if (totalQuantity.compareTo(BigDecimal.ZERO) <= 0) return null;
        BigDecimal averagePrice = totalCost.divide(totalBuyQuantity, 4, RoundingMode.HALF_UP);

        // 2. Encontra provedor e busca o preço (ainda na moeda original).
        MarketDataProvider provider = marketDataService.findProvidersFor(key.assetType()).stream().findFirst().orElse(null);
        if (provider == null) return null;

        boolean isToday = calculationDate.isEqual(LocalDate.now());
        PriceData priceData = isToday
                ? provider.fetchPrices(List.of(new AssetToFetch(key.ticker(), key.market(), key.assetType()))).blockFirst()
                : provider.fetchHistoricalPrice(new AssetToFetch(key.ticker(), key.market(), key.assetType()), calculationDate).block();

        if (priceData == null) {
            logger.warn("Não foi possível encontrar o preço para {} na data {}", key.ticker(), calculationDate);
            return null;
        }
        BigDecimal price = priceData.price();

        // 3. ***** CORREÇÃO APLICADA AQUI *****
        // Converte o preço E o custo para BRL se o ativo for cotado em dólar.
        logger.info("Antes da conversão {}: '{}'", price);
        if (Market.US.equals(key.market()) || AssetType.CRYPTO.equals(key.assetType())) {
            BigDecimal usdToBrlRate = isToday
                    ? exchangeRateService.fetchUsdToBrlRate().block()
                    : exchangeRateService.fetchHistoricalUsdToBrlRate(calculationDate).block();

            if (usdToBrlRate != null) {
                price = price.multiply(usdToBrlRate);
                averagePrice = averagePrice.multiply(usdToBrlRate); // <-- ESTA LINHA CORRIGE O BUG
            } else {
                logger.warn("Taxa de câmbio não encontrada para {}. Valor de {} será ignorado.", calculationDate, key.ticker());
                return null;
            }

            logger.info("Depois da conversão {}: '{}'", price);
        }

        // 4. Agora TODOS os cálculos são feitos em BRL.
        BigDecimal currentValue = price.multiply(totalQuantity);
        BigDecimal currentPositionCost = totalQuantity.multiply(averagePrice);
        BigDecimal profitOrLoss = currentValue.subtract(currentPositionCost);
        BigDecimal profitability = currentPositionCost.compareTo(BigDecimal.ZERO) > 0
                ? profitOrLoss.divide(currentPositionCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        // 5. Monta e retorna o DTO da posição.
        AssetPositionDto position = new AssetPositionDto();
        position.setTicker(key.ticker());
        position.setAssetType(key.assetType());
        position.setMarket(key.market());
        position.setTotalQuantity(totalQuantity);
        position.setAveragePrice(averagePrice);
        position.setCurrentPrice(price);
        position.setTotalInvested(currentPositionCost);
        position.setCurrentValue(currentValue);
        position.setProfitOrLoss(profitOrLoss);
        position.setProfitability(profitability);
        return position;
    }

    public Mono<PriceData> getHistoricalPriceWithFallback(AssetToFetch asset, LocalDate date) {
        List<MarketDataProvider> availableProviders = marketDataService.findProvidersFor(asset.assetType());

        if (availableProviders.isEmpty()) {
            logger.warn("[Histórico] Nenhum provedor encontrado para o tipo de ativo: {}", asset.assetType());
            return Mono.empty();
        }

        Flux<PriceData> priceFlux = Flux.empty();

        for (MarketDataProvider provider : availableProviders) {
            // Flux.defer garante que a chamada ao provedor só aconteça se a anterior falhar.
            Flux<PriceData> providerFlux = Flux.defer(() -> {
                logger.info("[Histórico] Tentando provedor '{}' para {} em {}",
                        provider.getClass().getSimpleName(), asset.ticker(), date);
                // .flux() converte o Mono<PriceData> retornado pelo provedor em um Flux<PriceData>
                return provider.fetchHistoricalPrice(asset, date).flux();
            });

            // Constrói a cadeia de fallback
            priceFlux = priceFlux.switchIfEmpty(providerFlux);
        }

        // Retorna o primeiro preço encontrado por qualquer um dos provedores.
        return priceFlux.next();
    }


    // --- MÉTODOS DE AGRUPAMENTO PARA A UI (HIERARQUIA E GRÁFICO) ---

    private Map<String, List<AssetSubCategoryDto>> buildAssetHierarchy(List<AssetPositionDto> allAssets, BigDecimal totalHeritage) {

        Map<String, Map<String, List<AssetPositionDto>>> groupedMap = allAssets.stream()
                .collect(Collectors.groupingBy(
                        asset -> {
                            // asset AQUI é o AssetPositionDto
                            if (AssetType.CRYPTO.equals(asset.getAssetType())) {
                                return "Cripto";
                            }
                            if (Market.US.equals(asset.getMarket())) {
                                return "EUA";
                            }
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
                        BigDecimal totalValue = assetsInSubCategory.stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
                        List<AssetTableRowDto> assetTableRows = assetsInSubCategory.stream()
                                .map(asset -> {
                                    BigDecimal portfolioPercentage = totalHeritage.compareTo(BigDecimal.ZERO) > 0 ?
                                            asset.getCurrentValue().divide(totalHeritage, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)) :
                                            BigDecimal.ZERO;
                                    return new AssetTableRowDto(
                                            asset.getTicker(), asset.getName(), asset.getTotalQuantity(), asset.getAveragePrice(),
                                            asset.getCurrentPrice(), asset.getCurrentValue(), asset.getProfitability(),
                                            portfolioPercentage
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

    private Map<String, AllocationNodeDto> buildAllocationTree(List<AssetPositionDto> allAssets, BigDecimal totalHeritage) {
        if (totalHeritage.compareTo(BigDecimal.ZERO) <= 0) return Map.of();


        Map<String, List<AssetPositionDto>> byCategory = allAssets.stream()
                .collect(Collectors.groupingBy(asset -> {
                    // asset AQUI é o AssetPositionDto
                    if (AssetType.CRYPTO.equals(asset.getAssetType())) {
                        return "crypto";
                    }
                    // asset.getMarket() é chamado no objeto correto
                    if (Market.US.equals(asset.getMarket())) {
                        return "usa";
                    }
                    return "brazil";
                }));

        return byCategory.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    BigDecimal categoryTotal = entry.getValue().stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal categoryPercentage = categoryTotal.divide(totalHeritage, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    Map<String, AllocationNodeDto> children = buildChildrenForCategory(entry.getKey(), entry.getValue(), categoryTotal);
                    return new AllocationNodeDto(categoryPercentage, children);
                }
        ));
    }

    private Map<String, AllocationNodeDto> buildChildrenForCategory(String category, List<AssetPositionDto> assets, BigDecimal categoryTotal) {
        if ("crypto".equals(category)) {
            return assets.stream().collect(Collectors.toMap(
                    AssetPositionDto::getTicker,
                    asset -> new AllocationNodeDto(asset.getCurrentValue().divide(categoryTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)))
            ));
        }

        Map<String, List<AssetPositionDto>> byAssetType = assets.stream().collect(Collectors.groupingBy(asset -> asset.getAssetType().name().toLowerCase()));
        return byAssetType.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    BigDecimal assetTypeTotal = entry.getValue().stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal assetTypePercentage = assetTypeTotal.divide(categoryTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    Map<String, AllocationNodeDto> grandchildren = entry.getValue().stream().collect(Collectors.toMap(
                            AssetPositionDto::getTicker,
                            asset -> new AllocationNodeDto(asset.getCurrentValue().divide(assetTypeTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)))
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

    private record AssetKey(String ticker, AssetType assetType, Market market) {}
}