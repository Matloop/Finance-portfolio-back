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

        List<AssetKey> assetsNeedingPrices = groupedTransactions.keySet().stream()
                .collect(Collectors.toList());

        if (calculationDate.isEqual(LocalDate.now())) {
            preloadCurrentPricesInBatch(assetsNeedingPrices, priceCache);
        }

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

    private void preloadCurrentPricesInBatch(List<AssetKey> assets, Map<MarketDataKey, Optional<BigDecimal>> priceCache) {
        logger.info("🔄 Pré-carregando preços para {} ativos...", assets.size());

        // Agrupa por tipo de ativo para busca eficiente
        Map<AssetType, List<AssetKey>> byType = assets.stream()
                .collect(Collectors.groupingBy(AssetKey::assetType));

        byType.forEach((assetType, assetKeys) -> {
            // Verifica quais ativos ainda não estão no cache do MarketDataService
            List<AssetKey> uncachedAssets = assetKeys.stream()
                    .filter(key -> marketDataService.getPrice(key.ticker()).compareTo(BigDecimal.ZERO) == 0)
                    .collect(Collectors.toList());

            if (!uncachedAssets.isEmpty()) {
                logger.info("📥 Buscando {} ativos do tipo {} que não estão no cache",
                        uncachedAssets.size(), assetType);

                // Busca todos os ativos não cacheados de uma vez
                List<AssetToFetch> toFetch = uncachedAssets.stream()
                        .map(key -> new AssetToFetch(key.ticker(), key.market(), key.assetType()))
                        .collect(Collectors.toList());

                // Dispara a busca (o resultado será armazenado no cache do MarketDataService)
                marketDataService.updatePricesForTransactions(
                        toFetch.stream()
                                .map(asset -> {
                                    Transaction tx = new Transaction();
                                    tx.setTicker(asset.ticker());
                                    tx.setMarket(asset.market());
                                    tx.setAssetType(asset.assetType());
                                    return tx;
                                })
                                .collect(Collectors.toList())
                );
            }
        });
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

        BigDecimal priceInBRL;
        BigDecimal totalInvestedInBRL = initialPosition.totalInvested();
        BigDecimal averagePriceInBRL = totalInvestedInBRL.divide(initialPosition.quantity(), 8, RoundingMode.HALF_UP);

        if (priceInOriginalCurrencyOpt.isPresent()) {
            priceInBRL = applyCurrencyConversion(
                    priceInOriginalCurrencyOpt.get(), key, calculationDate, exchangeRateCache, true
            );
        } else {
            priceInBRL = null;
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
        position.setName(null);
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
            } else {
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
            // CORREÇÃO: Se for data atual, tenta o cache do MarketDataService primeiro
            if (date.isEqual(LocalDate.now())) {
                BigDecimal cachedPrice = marketDataService.getPrice(k.ticker());
                if (cachedPrice.compareTo(BigDecimal.ZERO) > 0) {
                    logger.debug("💾 Usando preço do cache central para {}: {}", k.ticker(), cachedPrice);
                    return Optional.of(cachedPrice);
                }

                // Se não está no cache, busca com fallback
                logger.debug("🔍 Preço não encontrado no cache para {}, buscando...", k.ticker());
                Mono<PriceData> priceMono = getCurrentPriceWithFallback(
                        new AssetToFetch(k.ticker(), key.market(), key.assetType())
                );
                return priceMono.map(PriceData::price).map(Optional::of).defaultIfEmpty(Optional.empty()).block();
            } else {
                // Para datas históricas, sempre busca
                Mono<PriceData> priceMono = getHistoricalPriceWithFallback(
                        new AssetToFetch(k.ticker(), key.market(), key.assetType()),
                        k.date()
                );
                return priceMono.map(PriceData::price).map(Optional::of).defaultIfEmpty(Optional.empty()).block();
            }
        });
    }

    private BigDecimal applyCurrencyConversion(
            BigDecimal valueToConvert,
            AssetKey key,
            LocalDate date,
            Map<LocalDate, Optional<BigDecimal>> exchangeRateCache,
            boolean isPrice) {

        boolean needsConversion = isPrice
                ? (AssetType.CRYPTO.equals(key.assetType()) || Market.US.equals(key.market()))
                : Market.US.equals(key.market());

        if (!needsConversion) {
            return valueToConvert;
        }

        BigDecimal usdToBrlRate = exchangeRateCache.computeIfAbsent(date, k -> {
            logger.debug("💱 Buscando taxa de câmbio para {}", k);
            Mono<BigDecimal> rateMono = date.isEqual(LocalDate.now())
                    ? exchangeRateService.fetchUsdToBrlRate()
                    : exchangeRateService.fetchHistoricalUsdToBrlRate(k);
            return rateMono.map(Optional::of).defaultIfEmpty(Optional.empty()).block();
        }).orElse(null);

        if (usdToBrlRate != null) {
            return valueToConvert.multiply(usdToBrlRate);
        }

        logger.warn("⚠️ Taxa de câmbio não encontrada para {}. Não foi possível converter o valor do ativo {}",
                date, key.ticker());
        return valueToConvert;
    }

    private Mono<PriceData> getCurrentPriceWithFallback(AssetToFetch asset) {
        List<MarketDataProvider> availableProviders = marketDataService.findProvidersFor(asset.assetType());
        if (availableProviders.isEmpty()) {
            logger.warn("[Atual] Nenhum provedor encontrado para o tipo de ativo: {}", asset.assetType());
            return Mono.empty();
        }
        return marketDataService.getPriceWithFallback(asset);
    }

    private Mono<PriceData> getHistoricalPriceWithFallback(AssetToFetch asset, LocalDate date) {
        return marketDataService.getHistoricalPriceWithFallback(asset, date);
    }
}