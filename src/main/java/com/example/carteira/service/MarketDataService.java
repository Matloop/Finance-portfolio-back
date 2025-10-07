package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.AssetSearchResultDto;
import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.repository.TransactionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * CORREÇÕES APLICADAS:
 * 1. Scheduler movido para dentro do doOnSuccess (Bug #1)
 * 2. Adicionado método getCachedPrice para uso pelo PortfolioCalculatorService
 * 3. Implementado batch fetching por tipo de ativo
 * 4. Adicionado PreDestroy para shutdown gracioso
 */
@Service
public class MarketDataService {

    private static final Logger logger = LoggerFactory.getLogger(MarketDataService.class);

    private final TransactionRepository transactionRepository;
    private final List<MarketDataProvider> providers;
    private final Map<String, BigDecimal> priceCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public MarketDataService(TransactionRepository transactionRepository, List<MarketDataProvider> providers) {
        this.transactionRepository = transactionRepository;
        this.providers = providers;
        logger.info("MarketDataService carregado com {} provedor(es) de dados.", providers.size());
    }


    @PostConstruct
    private void initialize() {
        logger.info("Inicializando todos os provedores de dados...");

        Flux.fromIterable(providers)
                .flatMap(MarketDataProvider::initialize)
                .then()
                .doOnSuccess(aVoid -> {
                    logger.info("✅ Todos os provedores foram inicializados com sucesso!");
                    logger.info("📅 A primeira busca de dados de preços ocorrerá em 10 segundos.");

                    // CORREÇÃO: Agendador só inicia APÓS inicialização completa
                    scheduler.scheduleAtFixedRate(
                            this::refreshAllMarketData,
                            10,
                            300,
                            TimeUnit.SECONDS
                    );
                })
                .doOnError(error -> {
                    logger.error("❌ Falha ao inicializar provedores! A atualização de preços não será agendada.", error);
                })
                .subscribe();
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Desligando scheduler de atualização de preços...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public Flux<AssetSearchResultDto> searchAssets(String term) {
        String cleanedTerm = term.replace("&", "");

        List<Flux<AssetSearchResultDto>> searchFluxes = providers.stream()
                .map(provider -> provider.search(cleanedTerm)
                        .onErrorResume(e -> {
                            logger.error("Erro na busca do provedor {}: {}",
                                    provider.getClass().getSimpleName(), e.getMessage());
                            return Flux.empty();
                        }))
                .collect(Collectors.toList());

        return Flux.merge(searchFluxes);
    }

    public Mono<BigDecimal> getCachedPrice(String ticker) {
        String upperTicker = ticker.toUpperCase();
        BigDecimal cachedPrice = priceCache.get(upperTicker);

        if (cachedPrice != null) {
            logger.debug("💾 Preço recuperado do cache para {}: {}", upperTicker, cachedPrice);
            return Mono.just(cachedPrice);
        }

        logger.debug("⚠️ Preço não encontrado no cache para {}", upperTicker);
        return Mono.empty();
    }

    public BigDecimal getPrice(String ticker) {
        return priceCache.getOrDefault(ticker.toUpperCase(), BigDecimal.ZERO);
    }

    public void invalidateCache(String ticker) {
        priceCache.remove(ticker.toUpperCase());
        logger.info("🗑️ Cache invalidado para: {}", ticker.toUpperCase());
    }

    public void invalidateAllCache() {
        priceCache.clear();
        logger.info("🗑️ Todo o cache de preços foi invalidado");
    }

    public Mono<PriceData> getPriceWithFallback(AssetToFetch asset) {
        List<MarketDataProvider> availableProviders = findProvidersFor(asset.assetType());
        if (availableProviders.isEmpty()) return Mono.empty();

        Flux<PriceData> priceFlux = Flux.empty();
        for (MarketDataProvider provider : availableProviders) {
            Flux<PriceData> providerFlux = Flux.defer(() -> provider.fetchPrices(List.of(asset)));
            priceFlux = priceFlux.switchIfEmpty(providerFlux);
        }
        return priceFlux.next();
    }

    public Map<String, BigDecimal> getAllPrices() {
        return Collections.unmodifiableMap(priceCache);
    }

    public void refreshAllMarketData() {
        logger.info("🔄 ATUALIZAÇÃO GERAL: Iniciando busca de dados para todos os ativos da carteira...");
        List<Transaction> allTransactions = transactionRepository.findAll();

        if (allTransactions.isEmpty()) {
            logger.info("📭 Nenhuma transação na carteira para atualizar.");
            return;
        }

        updatePricesForTransactions(allTransactions, "ATUALIZAÇÃO PERIÓDICA");
    }

    public void updatePricesForTransactions(List<Transaction> transactions) {
        updatePricesForTransactions(transactions, "ATUALIZAÇÃO SOB DEMANDA");
    }

    private void updateCache(PriceData priceData) {
        priceCache.put(priceData.ticker().toUpperCase(), priceData.price());
        logger.debug("💾 Cache atualizado: {} = {}", priceData.ticker().toUpperCase(), priceData.price());
    }

    List<MarketDataProvider> findProvidersFor(AssetType assetType) {
        return providers.stream()
                .filter(p -> p.supports(assetType))
                .sorted(Comparator.comparing(p -> !p.getClass().isAnnotationPresent(Primary.class)))
                .collect(Collectors.toList());
    }

    public Mono<PriceData> getHistoricalPriceWithFallback(AssetToFetch asset, LocalDate date) {
        List<MarketDataProvider> availableProviders = findProvidersFor(asset.assetType());
        if (availableProviders.isEmpty()) {
            logger.warn("[Histórico] Nenhum provedor encontrado para o tipo de ativo: {}", asset.assetType());
            return Mono.empty();
        }

        Flux<PriceData> priceFlux = Flux.empty();
        for (MarketDataProvider provider : availableProviders) {
            Flux<PriceData> providerFlux = Flux.defer(() -> {
                logger.info("[Histórico] Tentando provedor '{}' para {} em {}",
                        provider.getClass().getSimpleName(), asset.ticker(), date);
                return provider.fetchHistoricalPrice(asset, date).flux();
            });
            priceFlux = priceFlux.switchIfEmpty(providerFlux);
        }
        return priceFlux.next();
    }

    private void updatePricesForTransactions(List<Transaction> transactions, String logContext) {
        if (transactions == null || transactions.isEmpty()) {
            logger.warn("[{}] Chamada com uma lista de transações vazia.", logContext);
            return;
        }
        logger.info("[{}] 🚀 Iniciando busca para {} transação(ões).", logContext, transactions.size());

        // Agrupa transações por tipo de ativo
        Map<AssetType, List<Transaction>> groupedByType = transactions.stream()
                .collect(Collectors.groupingBy(Transaction::getAssetType));

        groupedByType.forEach((assetType, transactionList) -> {
            List<MarketDataProvider> availableProviders = findProvidersFor(assetType);

            if (availableProviders.isEmpty()) {
                logger.warn("[{}] ⚠️ Nenhum provedor de dados encontrado para o tipo de ativo: {}",
                        logContext, assetType);
                return;
            }

            // CORREÇÃO: Cria lista única de ativos (sem duplicatas)
            List<AssetToFetch> uniqueAssets = transactionList.stream()
                    .map(t -> new AssetToFetch(
                            t.getTicker().toUpperCase(),
                            t.getMarket(),
                            t.getAssetType()
                    ))
                    .distinct()
                    .collect(Collectors.toList());

            logger.info("[{}] 📊 Buscando {} ativo(s) únicos do tipo {}",
                    logContext, uniqueAssets.size(), assetType);

            // CORREÇÃO: Busca TODOS os ativos de uma vez (batch)
            fetchPricesWithFallback(availableProviders, uniqueAssets, logContext, assetType);
        });
    }

    private void fetchPricesWithFallback(
            List<MarketDataProvider> providers,
            List<AssetToFetch> assets,
            String logContext,
            AssetType assetType) {

        Flux<PriceData> priceFlux = Flux.empty();

        for (int i = 0; i < providers.size(); i++) {
            final MarketDataProvider currentProvider = providers.get(i);
            final boolean isLastProvider = i == providers.size() - 1;

            Flux<PriceData> providerFlux = Flux.defer(() -> {
                logger.info("[{}] 🔍 Tentando provedor '{}' para {} ativo(s) do tipo {}",
                        logContext,
                        currentProvider.getClass().getSimpleName(),
                        assets.size(),
                        assetType);

                // CORREÇÃO: Passa TODOS os ativos de uma vez (batch fetching)
                return currentProvider.fetchPrices(assets);
            });

            if (isLastProvider) {
                priceFlux = priceFlux.switchIfEmpty(providerFlux);
            } else {
                priceFlux = priceFlux.switchIfEmpty(providerFlux)
                        .collectList()
                        .flatMapMany(list -> {
                            if (list.isEmpty()) {
                                logger.warn("[{}] ⚠️ Provedor '{}' não retornou dados. Tentando fallback...",
                                        logContext, currentProvider.getClass().getSimpleName());
                                return Flux.empty();
                            }
                            return Flux.fromIterable(list);
                        });
            }
        }

        // Inscreve-se no resultado final
        priceFlux.subscribe(
                this::updateCache,
                error -> logger.error("[{}] ❌ Erro irrecuperável no fluxo de busca para o tipo {}: {}",
                        logContext, assetType, error.getMessage()),
                () -> logger.info("[{}] ✅ Fluxo de preços para o tipo {} concluído.",
                        logContext, assetType)
        );
    }
}