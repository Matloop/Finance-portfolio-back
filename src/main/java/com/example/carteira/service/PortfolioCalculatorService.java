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

    // =======================> CORREÇÃO APLICADA AQUI <=======================
    // Adicionando a definição do record que estava faltando.
    private record PositionCalculationResult(BigDecimal quantity, BigDecimal totalInvested) {}
    private record AssetKey(String ticker, AssetType assetType, Market market) {}
    private record MarketDataKey(String ticker, LocalDate date) {}

    public PortfolioCalculatorService(MarketDataService marketDataService,
                                      FixedIncomeService fixedIncomeService,
                                      ExchangeRateService exchangeRateService) {
        this.marketDataService = marketDataService;
        this.fixedIncomeService = fixedIncomeService;
        this.exchangeRateService = exchangeRateService;
    }

    public List<AssetPositionDto> calculateConsolidatedPortfolio(List<Transaction> transactions, LocalDate calculationDate) {
        return calculateConsolidatedPortfolio(transactions, calculationDate, new HashMap<>(), new HashMap<>());
    }

    private List<AssetPositionDto> calculateConsolidatedPortfolio(
            List<Transaction> transactions,
            LocalDate calculationDate,
            Map<MarketDataKey, Optional<BigDecimal>> priceCache,
            Map<LocalDate, Optional<BigDecimal>> exchangeRateCache) {

        Map<AssetKey, List<Transaction>> groupedTransactions = transactions.stream()
                .filter(t -> t.getTicker() != null)
                .collect(Collectors.groupingBy(t -> new AssetKey(t.getTicker(), t.getAssetType(), t.getMarket())));

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

        PositionCalculationResult initialPosition = calculateInitialPosition(transactions);
        if (initialPosition == null) {
            return null;
        }

        Optional<BigDecimal> priceInOriginalCurrencyOpt = fetchAndCachePrice(key, calculationDate, priceCache);

        BigDecimal priceInBRL = null;
        BigDecimal totalInvestedInBRL = initialPosition.totalInvested();
        BigDecimal averagePriceInBRL = totalInvestedInBRL.divide(initialPosition.quantity(), 8, RoundingMode.HALF_UP);

        if (priceInOriginalCurrencyOpt.isPresent()) {
            priceInBRL = applyCurrencyConversion(
                    priceInOriginalCurrencyOpt.get(), key, calculationDate, exchangeRateCache, true
            );
        }
        totalInvestedInBRL = applyCurrencyConversion(
                initialPosition.totalInvested(), key, calculationDate, exchangeRateCache, false
        );
        averagePriceInBRL = totalInvestedInBRL.divide(initialPosition.quantity(), 8, RoundingMode.HALF_UP);

        BigDecimal currentValue;
        BigDecimal profitability;

        if (priceInBRL == null) {
            currentValue = totalInvestedInBRL;
            priceInBRL = averagePriceInBRL;
            profitability = BigDecimal.ZERO;
        } else {
            currentValue = priceInBRL.multiply(initialPosition.quantity());
            BigDecimal profitOrLoss = currentValue.subtract(totalInvestedInBRL);
            profitability = totalInvestedInBRL.compareTo(BigDecimal.ZERO) > 0
                    ? profitOrLoss.divide(totalInvestedInBRL, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;
        }

        AssetPositionDto position = new AssetPositionDto();
        position.setTicker(key.ticker());
        position.setName(null); // ou o nome correto se você o tiver
        position.setAssetType(key.assetType());
        position.setMarket(key.market());
        position.setTotalQuantity(initialPosition.quantity());
        position.setAveragePrice(averagePriceInBRL);
        position.setCurrentPrice(priceInBRL);
        position.setTotalInvested(totalInvestedInBRL);
        position.setCurrentValue(currentValue);
        position.setProfitOrLoss(currentValue.subtract(totalInvestedInBRL));
        position.setProfitability(profitability);

        return position;
    }

    private PositionCalculationResult calculateInitialPosition(List<Transaction> transactions) {
        transactions.sort(Comparator.comparing(Transaction::getTransactionDate));

        BigDecimal currentQuantity = BigDecimal.ZERO;
        BigDecimal totalInvestedValue = BigDecimal.ZERO;

        for (Transaction t : transactions) {
            if (TransactionType.BUY.equals(t.getTransactionType())) {
                BigDecimal transactionCost = t.getQuantity().multiply(t.getPricePerUnit());
                if (t.getOtherCosts() != null) transactionCost = transactionCost.add(t.getOtherCosts());
                totalInvestedValue = totalInvestedValue.add(transactionCost);
                currentQuantity = currentQuantity.add(t.getQuantity());
            } else { // SELL
                if (currentQuantity.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal avgPrice = totalInvestedValue.divide(currentQuantity, 16, RoundingMode.HALF_UP);
                    totalInvestedValue = totalInvestedValue.subtract(t.getQuantity().multiply(avgPrice));
                }
                currentQuantity = currentQuantity.subtract(t.getQuantity());
            }
        }

        if (currentQuantity.compareTo(BigDecimal.ZERO) <= 0) return null;
        return new PositionCalculationResult(currentQuantity, totalInvestedValue);
    }

    private Optional<BigDecimal> fetchAndCachePrice(AssetKey key, LocalDate date, Map<MarketDataKey, Optional<BigDecimal>> priceCache) {
        MarketDataKey cacheKey = new MarketDataKey(key.ticker(), date);
        return priceCache.computeIfAbsent(cacheKey, k -> {
            Mono<PriceData> priceMono = date.isEqual(LocalDate.now())
                    ? getCurrentPriceWithFallback(new AssetToFetch(k.ticker(), key.market(), key.assetType()))
                    : getHistoricalPriceWithFallback(new AssetToFetch(k.ticker(), key.market(), key.assetType()), k.date());
            return priceMono.map(PriceData::price).map(Optional::of).defaultIfEmpty(Optional.empty()).block();
        });
    }

    private BigDecimal applyCurrencyConversion(BigDecimal valueToConvert, AssetKey key, LocalDate date, Map<LocalDate, Optional<BigDecimal>> exchangeRateCache, boolean isPrice) {
        boolean needsConversion = isPrice
                ? (AssetType.CRYPTO.equals(key.assetType()) || Market.US.equals(key.market()))
                : Market.US.equals(key.market());

        if (!needsConversion) {
            return valueToConvert;
        }

        BigDecimal usdToBrlRate = exchangeRateCache.computeIfAbsent(date, k ->
                (date.isEqual(LocalDate.now()) ? exchangeRateService.fetchUsdToBrlRate() : exchangeRateService.fetchHistoricalUsdToBrlRate(k))
                        .map(Optional::of).defaultIfEmpty(Optional.empty()).block()
        ).orElse(null);

        if (usdToBrlRate != null) {
            return valueToConvert.multiply(usdToBrlRate);
        }

        logger.warn("Taxa de câmbio não encontrada para {}. Não foi possível converter o valor do ativo {}", date, key.ticker());
        return valueToConvert;
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