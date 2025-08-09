package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.repository.TransactionRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class MarketDataService {

    private final WebClient brasilApiWebClient;
    private final WebClient coingeckoWebClient;
    private final TransactionRepository transactionRepository;

    private final Map<String, BigDecimal> priceCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public MarketDataService(WebClient.Builder webClientBuilder, TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
        this.brasilApiWebClient = webClientBuilder.baseUrl("https://brasilapi.com.br/api").build();
        this.coingeckoWebClient = webClientBuilder.baseUrl("https://api.coingecko.com/api/v3").build();
    }

    @PostConstruct
    private void initialize() {
        scheduler.scheduleAtFixedRate(this::fetchMarketData, 5, 90, TimeUnit.SECONDS);
    }

    public BigDecimal getPrice(String ticker) {
        return priceCache.getOrDefault(ticker.toUpperCase(), BigDecimal.ZERO);
    }

    private void fetchMarketData() {
        System.out.println("[MarketDataService] Iniciando busca de dados de mercado...");
        Map<AssetType, List<String>> tickersByType = transactionRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        Transaction::getAssetType,
                        Collectors.mapping(t -> t.getTicker().toUpperCase(), Collectors.collectingAndThen(Collectors.toSet(), List::copyOf))
                ));

        if (tickersByType.containsKey(AssetType.STOCK)) fetchStockPrices(tickersByType.get(AssetType.STOCK));
        if (tickersByType.containsKey(AssetType.CRYPTO)) fetchCryptoPrices(tickersByType.get(AssetType.CRYPTO));
    }

    private void fetchStockPrices(List<String> tickers) {
        String tickersForApi = String.join(",", tickers);
        brasilApiWebClient.get()
                .uri("/quote/v1/{tickers}", tickersForApi)
                .retrieve()
                .bodyToMono(QuoteResponse.class)
                .subscribe(response -> response.results().forEach(quote -> {
                    priceCache.put(quote.symbol().toUpperCase(), quote.regularMarketPrice());
                }));
    }

    private void fetchCryptoPrices(List<String> tickers) {
        String idsForApi = tickers.stream().map(this::mapTickerToCoingeckoId).collect(Collectors.joining(","));
        coingeckoWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/simple/price").queryParam("ids", idsForApi).queryParam("vs_currencies", "brl").build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .subscribe(response -> tickers.forEach(ticker -> {
                    String coingeckoId = mapTickerToCoingeckoId(ticker);
                    if (response != null && response.has(coingeckoId)) {
                        BigDecimal price = new BigDecimal(response.get(coingeckoId).get("brl").asText());
                        priceCache.put(ticker.toUpperCase(), price);
                    }
                }));
    }

    private String mapTickerToCoingeckoId(String ticker) {
        return switch (ticker.toUpperCase()) {
            case "BTC" -> "bitcoin";
            case "ETH" -> "ethereum";
            default -> ticker.toLowerCase();
        };
    }

    // --- DTOs Internos para Parsers das APIs ---
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QuoteResponse(List<QuoteResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QuoteResult(String symbol, BigDecimal regularMarketPrice) {}
}