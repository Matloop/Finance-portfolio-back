package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.AssetPositionDto;
import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetCategory;
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

@Service
public class PortfolioCalculatorService {

    private static final Logger logger = LoggerFactory.getLogger(PortfolioCalculatorService.class);

    private final MarketDataService marketDataService;
    private final FixedIncomeService fixedIncomeService;
    private final ExchangeRateService exchangeRateService;

    // Records permanecem os mesmos
    private record PositionCalculationResult(BigDecimal quantity, BigDecimal totalInvested) {}
    private record AssetKey(String ticker, AssetType assetType, Market market) {}

    public PortfolioCalculatorService(MarketDataService marketDataService,
                                      FixedIncomeService fixedIncomeService,
                                      ExchangeRateService exchangeRateService) {
        this.marketDataService = marketDataService;
        this.fixedIncomeService = fixedIncomeService;
        this.exchangeRateService = exchangeRateService;
    }

    // Método público principal, agora chama a versão privada sem o cache de preço local
    public List<AssetPositionDto> calculateConsolidatedPortfolio(List<Transaction> transactions, LocalDate calculationDate) {
        return calculateConsolidatedPortfolio(transactions, calculationDate, new HashMap<>());
    }

    // Versão privada sem o `priceCache`
    private List<AssetPositionDto> calculateConsolidatedPortfolio(
            List<Transaction> transactions,
            LocalDate calculationDate,
            Map<LocalDate, Optional<BigDecimal>> exchangeRateCache
    ) {
        // --- INÍCIO DA MUDANÇA PRINCIPAL ---

        // 1. Separe as transações por categoria de ativo
        Map<Boolean, List<Transaction>> partitionedTransactions = transactions.stream()
                .collect(Collectors.partitioningBy(t -> t.getAssetType().getCategory() == AssetCategory.FIXED_INCOME));

        List<Transaction> fixedIncomeTransactions = partitionedTransactions.get(true);
        List<Transaction> variableIncomeTransactions = partitionedTransactions.get(false);

        // 2. Calcule as posições de Renda Fixa usando o serviço correto
        List<AssetPositionDto> fixedIncomePositions = calculateFixedIncomePositions(fixedIncomeTransactions, calculationDate);

        // 3. Calcule as posições de Renda Variável (lógica existente)
        List<AssetPositionDto> variableIncomePositions = calculateVariableIncomePositions(variableIncomeTransactions, calculationDate, exchangeRateCache);

        // 4. Junte os dois resultados
        List<AssetPositionDto> allPositions = new ArrayList<>();
        allPositions.addAll(fixedIncomePositions);
        allPositions.addAll(variableIncomePositions);

        return allPositions;
        // --- FIM DA MUDANÇA PRINCIPAL ---
    }

    private List<AssetPositionDto> calculateVariableIncomePositions(
            List<Transaction> transactions,
            LocalDate calculationDate,
            Map<LocalDate, Optional<BigDecimal>> exchangeRateCache
    ) {
        if (transactions.isEmpty()) {
            return Collections.emptyList();
        }

        Map<AssetKey, List<Transaction>> groupedTransactions = transactions.stream()
                .filter(t -> t.getTicker() != null)
                .collect(Collectors.groupingBy(t -> new AssetKey(t.getTicker(), t.getAssetType(), t.getMarket())));

        List<AssetKey> assetsNeedingPrices = new ArrayList<>(groupedTransactions.keySet());

        if (calculationDate.isEqual(LocalDate.now())) {
            preloadCurrentPricesInBatch(assetsNeedingPrices);
        }

        return groupedTransactions.entrySet().stream()
                .map(entry -> calculateSinglePosition(entry.getKey(), entry.getValue(), calculationDate, exchangeRateCache))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<AssetPositionDto> calculateFixedIncomePositions(List<Transaction> fixedIncomeTransactions, LocalDate calculationDate) {
        if (fixedIncomeTransactions.isEmpty()) {
            return Collections.emptyList();
        }
        logger.info("📊 Calculando {} posição(ões) de Renda Fixa para a data {}", fixedIncomeTransactions.size(), calculationDate);

        // Extrai os nomes (que usamos como identificadores) dos ativos de Renda Fixa
        Set<String> fixedIncomeNames = fixedIncomeTransactions.stream()
                .map(Transaction::getTicker)
                .collect(Collectors.toSet());

        // Delega o cálculo para o FixedIncomeService, que sabe como fazer isso
        return fixedIncomeService.getFixedIncomePositionsForDate(new ArrayList<>(fixedIncomeNames), calculationDate);
    }

    private void preloadCurrentPricesInBatch(List<AssetKey> assets) {
        if (assets.isEmpty()) {
            return;
        }
        logger.info("🔄 Pré-carregando preços para {} ativos...", assets.size());

        Map<AssetType, List<AssetKey>> byType = assets.stream()
                .collect(Collectors.groupingBy(AssetKey::assetType));

        byType.forEach((assetType, assetKeys) -> {
            List<AssetKey> uncachedAssets = assetKeys.stream()
                    .filter(key -> marketDataService.getPrice(key.ticker()).compareTo(BigDecimal.ZERO) == 0)
                    .collect(Collectors.toList());

            if (!uncachedAssets.isEmpty()) {
                logger.info("📥 Buscando {} ativos do tipo {} que não estão no cache central (Redis)",
                        uncachedAssets.size(), assetType);

                List<AssetToFetch> toFetch = uncachedAssets.stream()
                        .map(key -> new AssetToFetch(key.ticker(), key.market(), key.assetType()))
                        .collect(Collectors.toList());

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
            Map<LocalDate, Optional<BigDecimal>> exchangeRateCache) {

        PositionCalculationResult initialPosition = calculateInitialPosition(transactions);
        if (initialPosition == null) {
            return null;
        }

        // Chamada corrigida para o novo método `fetchPrice`
        Optional<BigDecimal> priceInOriginalCurrencyOpt = fetchPrice(key, calculationDate);

        BigDecimal priceInBRL;
        BigDecimal totalInvestedInBRL = initialPosition.totalInvested();

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

        // Evita divisão por zero se a quantidade for zero
        BigDecimal averagePriceInBRL = (initialPosition.quantity().compareTo(BigDecimal.ZERO) > 0)
                ? totalInvestedInBRL.divide(initialPosition.quantity(), 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

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

    private Optional<BigDecimal> fetchPrice(AssetKey key, LocalDate date) {
        Mono<PriceData> priceMono;

        if (date.isEqual(LocalDate.now())) {
            // Tenta pegar do cache (Redis) primeiro, se não encontrar, busca na web
            priceMono = marketDataService.getCachedPrice(key.ticker())
                    .map(price -> new PriceData(key.ticker(), price))
                    .switchIfEmpty(Mono.defer(() -> {
                        logger.debug("🔍 Preço para {} não encontrado no cache central. Buscando na web...", key.ticker());
                        return marketDataService.getPriceWithFallback(
                                new AssetToFetch(key.ticker(), key.market(), key.assetType())
                        );
                    }));
        } else {
            // Para preços históricos, sempre delegamos a busca
            // (O MarketDataProvider pode ter seu próprio cache para isso)
            priceMono = marketDataService.getHistoricalPriceWithFallback(
                    new AssetToFetch(key.ticker(), key.market(), key.assetType()),
                    date
            );
        }

        // .blockOptional() espera o resultado e o converte para um Optional
        return priceMono.map(PriceData::price).blockOptional();
    }

    private BigDecimal applyCurrencyConversion(
            BigDecimal valueToConvert,
            AssetKey key,
            LocalDate date,
            Map<LocalDate, Optional<BigDecimal>> exchangeRateCache,
            boolean isPrice) {
        if (key.assetType() == AssetType.DOLLAR) {
            return valueToConvert;
        }
        boolean needsConversion = isPrice
                ? (key.assetType() == AssetType.CRYPTO || key.market() == Market.US)
                : key.market() == Market.US;

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
}