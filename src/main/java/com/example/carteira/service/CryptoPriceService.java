package com.example.carteira.service;

import com.example.carteira.model.dtos.CryptoPriceUpdateDto;
import com.example.carteira.repository.AssetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
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
    private final AssetRepository assetRepository;

    private final ConcurrentHashMap<String, BigDecimal> priceCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public CryptoPriceService(WebClient.Builder webClientBuilder, SimpMessagingTemplate messagingTemplate, AssetRepository assetRepository) {
        this.messagingTemplate = messagingTemplate;
        this.webClient = webClientBuilder.baseUrl("https://api.coingecko.com/api/v3").build();
        this.assetRepository = assetRepository;

    }

    @PostConstruct // Esta anotação garante que o método rode após a inicialização do serviço.
    private void initialize() {
        System.out.println("Iniciando CryptoPriceService. Fazendo a busca inicial de preços...");
        fetchAndPublishPrices(); // Faz a primeira busca imediatamente ao iniciar.

        // Agenda as próximas execuções. Inicia após 90 segundos e repete a cada 90 segundos.
        scheduler.scheduleAtFixedRate(this::fetchAndPublishPrices, 60, 60, TimeUnit.SECONDS);
    }

    public BigDecimal getCurrentPrice(String ticker) {
        return priceCache.getOrDefault(ticker, BigDecimal.ZERO);
    }

    private void fetchAndPublishPrices() {
        List<String> monitoredTickers = assetRepository.findDistinctCryptoTickers();
        System.out.println("[DIAGNÓSTICO] Iniciando busca de preços...");
        if (CollectionUtils.isEmpty(monitoredTickers)) {
            System.out.println("Nenhum criptoativo no portfólio para buscar preços.");
            return;
        }

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
            case "SOL" -> "solana";
            default -> "";
        };
    }
}