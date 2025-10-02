package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.AssetPositionDto;
import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.example.carteira.model.enums.TransactionType;
import com.example.carteira.service.util.ExchangeRateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class PortfolioCalculatorService {

    private static final Logger logger = LoggerFactory.getLogger(PortfolioCalculatorService.class);

    private final MarketDataService marketDataService;
    private final FixedIncomeService fixedIncomeService;
    private final ExchangeRateService exchangeRateService;

    // Chaves privadas para a lógica interna deste serviço
    private record AssetKey(String ticker, AssetType assetType, Market market) {}
    private record MarketDataKey(String ticker, LocalDate date) {}

    public PortfolioCalculatorService(MarketDataService marketDataService,
                                      FixedIncomeService fixedIncomeService,
                                      ExchangeRateService exchangeRateService) {
        this.marketDataService = marketDataService;
        this.fixedIncomeService = fixedIncomeService;
        this.exchangeRateService = exchangeRateService;
    }

    /**
     * Ponto de entrada principal para calcular as posições consolidadas da carteira.
     * Este método público inicializa os caches para uma única execução.
     */
    public List<AssetPositionDto> calculateConsolidatedPortfolio(List<Transaction> transactions, LocalDate calculationDate) {
        return calculateConsolidatedPortfolio(transactions, calculationDate, new HashMap<>(), new HashMap<>());
    }

    /**
     * Lógica interna que utiliza caches para otimizar chamadas repetidas à API.
     */
    private List<AssetPositionDto> calculateConsolidatedPortfolio(
            List<Transaction> transactions,
            LocalDate calculationDate,
            Map<MarketDataKey, Optional<BigDecimal>> priceCache,
            Map<LocalDate, Optional<BigDecimal>> exchangeRateCache) {

        Map<AssetKey, List<Transaction>> groupedTransactions = transactions.stream()
                .filter(t -> t.getTicker() != null)
                .collect(Collectors.groupingBy(t -> new AssetKey(t.getTicker(), t.getAssetType(), t.getMarket())));

        // CORREÇÃO: Usando .stream() para evitar problemas de concorrência com HashMap.
        Stream<AssetPositionDto> transactionalAssetsStream = groupedTransactions.entrySet().stream()
                .map(entry -> calculateSinglePosition(entry.getKey(), entry.getValue(), calculationDate, priceCache, exchangeRateCache))
                .filter(Objects::nonNull);

        Stream<AssetPositionDto> fixedIncomeAssetsStream = fixedIncomeService
                .getAllFixedIncomePositionsForDate(calculationDate)
                .stream()
                .filter(Objects::nonNull);

        return Stream.concat(transactionalAssetsStream, fixedIncomeAssetsStream)
                .collect(Collectors.toList());
    }

    private AssetPositionDto calculateSinglePosition(
            AssetKey key,
            List<Transaction> transactions,
            LocalDate calculationDate,
            Map<MarketDataKey, Optional<BigDecimal>> priceCache,
            Map<LocalDate, Optional<BigDecimal>> exchangeRateCache) {

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
        BigDecimal averagePrice = totalCost.divide(totalBuyQuantity, 8, RoundingMode.HALF_UP);

        boolean isToday = calculationDate.isEqual(LocalDate.now());
        MarketDataKey cacheKey = new MarketDataKey(key.ticker(), calculationDate);

        BigDecimal price = priceCache.computeIfAbsent(cacheKey, k -> {
            Mono<PriceData> priceMono = isToday
                    ? getCurrentPriceWithFallback(new AssetToFetch(k.ticker(), key.market(), key.assetType()))
                    : getHistoricalPriceWithFallback(new AssetToFetch(k.ticker(), key.market(), key.assetType()), k.date());

            return priceMono.map(PriceData::price)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .block();
        }).orElse(null);

        if (price == null) {
            logger.warn("Não foi possível encontrar o preço para {} na data {}", key.ticker(), calculationDate);
            return null;
        }

        if (Market.US.equals(key.market()) ) {
            BigDecimal usdToBrlRate = exchangeRateCache.computeIfAbsent(calculationDate, k ->
                    (isToday ? exchangeRateService.fetchUsdToBrlRate() : exchangeRateService.fetchHistoricalUsdToBrlRate(k))
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .block()
            ).orElse(null);

            if (usdToBrlRate != null) {
                price = price.multiply(usdToBrlRate);
                averagePrice = averagePrice.multiply(usdToBrlRate);
            } else {
                logger.warn("Taxa de câmbio não encontrada para {}. Valor de {} será ignorado.", calculationDate, key.ticker());
                return null;
            }
        }

        BigDecimal currentValue = price.multiply(totalQuantity);
        BigDecimal currentPositionCost = totalQuantity.multiply(averagePrice);
        BigDecimal profitOrLoss = currentValue.subtract(currentPositionCost);
        BigDecimal profitability = currentPositionCost.compareTo(BigDecimal.ZERO) > 0
                ? profitOrLoss.divide(currentPositionCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

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

    private Mono<PriceData> getCurrentPriceWithFallback(AssetToFetch asset) {
        List<MarketDataProvider> availableProviders = marketDataService.findProvidersFor(asset.assetType());
        if (availableProviders.isEmpty()) {
            logger.warn("[Atual] Nenhum provedor encontrado para o tipo de ativo: {}", asset.assetType());
            return Mono.empty();
        }
        return Flux.fromIterable(availableProviders)
                .concatMap(provider -> {
                    logger.info("[Atual] Tentando provedor '{}' para {}", provider.getClass().getSimpleName(), asset.ticker());
                    return provider.fetchPrices(List.of(asset));
                })
                .next();
    }

    private Mono<PriceData> getHistoricalPriceWithFallback(AssetToFetch asset, LocalDate date) {
        List<MarketDataProvider> availableProviders = marketDataService.findProvidersFor(asset.assetType());
        if (availableProviders.isEmpty()) {
            logger.warn("[Histórico] Nenhum provedor encontrado para o tipo de ativo: {}", asset.assetType());
            return Mono.empty();
        }
        return Flux.fromIterable(availableProviders)
                .concatMap(provider -> {
                    logger.info("[Histórico] Tentando provedor '{}' para {} em {}", provider.getClass().getSimpleName(), asset.ticker(), date);
                    return provider.fetchHistoricalPrice(asset, date);
                })
                .next();
    }
}