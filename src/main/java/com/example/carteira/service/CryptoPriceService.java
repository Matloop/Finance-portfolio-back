package com.example.carteira.service;

import com.example.carteira.model.dtos.CryptoPriceUpdateDto;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CryptoPriceService {

    private final WebClient webClient;
    private final SimpMessagingTemplate messagingTemplate;

    private final ConcurrentHashMap<String, BigDecimal> priceCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<String> monitoredTickers = List.of("BTC", "ETH");

    public CryptoPriceService(WebClient.Builder webClientBuilder, SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        this.webClient = webClientBuilder.baseUrl("https://api.coingecko.com/api/v3").build();
        this.schedulePriceUpdates();
    }

    public BigDecimal getCurrentPrice(String ticker) {
        return priceCache.getOrDefault(ticker, BigDecimal.ZERO);
    }

    private void schedulePriceUpdates() {
        scheduler.scheduleAtFixedRate(this::fetchAndPublishPrices, 5, 10, TimeUnit.SECONDS);
    }

    private void fetchAndPublishPrices() {
        System.out.println("[DIAGNÓSTICO] Iniciando busca de preços...");

        Map<String, String> tickerToIdMap = monitoredTickers.stream()
                .collect(Collectors.toMap(Function.identity(), this::getCoingeckoId));

        String apiIds = String.join(",", tickerToIdMap.values());

        webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/simple/price")
                        .queryParam("ids", apiIds)
                        .queryParam("vs_currencies", "usd")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnError(error -> error.printStackTrace()) // Imprime qualquer erro da chamada
                .subscribe(jsonNode -> {
                    if (jsonNode == null) {
                        System.err.println("[ERRO] Resposta da API foi nula.");
                        return;
                    }

                    System.out.println("[DIAGNÓSTICO] Resposta da API: " + jsonNode.toString());

                    tickerToIdMap.forEach((ticker, coingeckoId) -> {
                        if (jsonNode.has(coingeckoId) && jsonNode.get(coingeckoId).has("usd")) {
                            // ***** LINHA CORRIGIDA *****
                            BigDecimal newPrice = new BigDecimal(jsonNode.get(coingeckoId).get("usd").asText());
                            priceCache.put(ticker, newPrice);

                            CryptoPriceUpdateDto priceUpdate = new CryptoPriceUpdateDto(ticker, newPrice);

                            System.out.println("-> Publicando atualização: " + ticker + " - " + newPrice);
                            messagingTemplate.convertAndSend("/topic/crypto-prices", priceUpdate);
                        } else {
                            System.err.println("Não foi possível encontrar o preço para: " + ticker);
                        }
                    });
                });
    }

    private String getCoingeckoId(String ticker) {
        return switch (ticker.toUpperCase()) {
            case "BTC" -> "bitcoin";
            case "ETH" -> "ethereum";
            default -> "";
        };
    }
}