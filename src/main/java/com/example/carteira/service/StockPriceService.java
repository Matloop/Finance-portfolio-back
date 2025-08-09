package com.example.carteira.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;

@Service
public class StockPriceService {
    private final WebClient webClient;

    public StockPriceService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("https://brasilapi.com.br/api/quote/v1").build();
    }

    public BigDecimal getCurrentPrice(String ticker) {
        try {
            QuoteResponse response = webClient.get()
                    .uri("/{ticker}", ticker)
                    .retrieve()
                    .bodyToMono(QuoteResponse.class)
                    .block();

            if (response != null && !response.results.isEmpty()) {
                return response.results.get(0).regularMarketPrice();
            }
        } catch (Exception e) {
            System.err.println("Falha ao buscar preço para a ação " + ticker + ": " + e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    // DTOs internos para mapear a resposta da BrasilAPI
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QuoteResponse(java.util.List<QuoteResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QuoteResult(BigDecimal regularMarketPrice) {}
}