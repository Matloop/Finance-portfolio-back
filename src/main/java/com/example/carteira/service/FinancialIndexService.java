package com.example.carteira.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class FinancialIndexService {
    private final WebClient webClient;
    private final Map<String, Map<LocalDate, BigDecimal>> periodCache = new ConcurrentHashMap<>();

    private static final String CDI_SERIES_CODE = "12";
    private static final String IPCA_SERIES_CODE = "433"; // Código para o IPCA mensal
    private static final DateTimeFormatter API_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public FinancialIndexService(WebClient.Builder webClientBuilder) {
        // A base da URL agora é a raiz da API, para dar mais flexibilidade.
        this.webClient = webClientBuilder.baseUrl("https://api.bcb.gov.br").build();
    }

    /**
     * Busca um mapa com as taxas CDI diárias para um determinado período.
     */
    public Map<LocalDate, BigDecimal> getCdiRatesForPeriod(LocalDate startDate, LocalDate endDate) {
        String cacheKey = "CDI:" + startDate + ":" + endDate;
        if (periodCache.containsKey(cacheKey)) {
            return periodCache.get(cacheKey);
        }

        String formattedStartDate = startDate.format(API_DATE_FORMATTER);
        String formattedEndDate = endDate.format(API_DATE_FORMATTER);

        try {
            System.out.println("Buscando série CDI de " + formattedStartDate + " até " + formattedEndDate);

            // ***** A CORREÇÃO ESTÁ AQUI *****
            // O caminho completo é construído no .uri(), usando o formato correto "bcdata.sgs.{codigo}"
            JsonNode[] response = webClient.get()
                    .uri("/dados/serie/bcdata.sgs.{seriesCode}/dados?formato=json&dataInicial={startDate}&dataFinal={endDate}",
                            CDI_SERIES_CODE, formattedStartDate, formattedEndDate)
                    .retrieve()
                    .bodyToMono(JsonNode[].class)
                    .block(); // .block() irá propagar o erro se a chamada falhar (ex: 404)

            if (response != null && response.length > 0) {
                // A API retorna um array JSON, então usamos Arrays.stream para processá-lo.
                Map<LocalDate, BigDecimal> ratesMap = Arrays.stream(response)
                        .collect(Collectors.toMap(
                                node -> LocalDate.parse(node.get("data").asText(), API_DATE_FORMATTER),
                                // O valor vem como percentual. Ex: 0.04. Dividimos por 100 para ter a taxa.
                                node -> new BigDecimal(node.get("valor").asText()).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
                        ));

                periodCache.put(cacheKey, ratesMap);
                System.out.println("Sucesso! " + ratesMap.size() + " taxas CDI foram carregadas e cacheadas.");
                return ratesMap;
            } else {
                System.err.println("A resposta da API do BACEN para o CDI foi vazia ou nula.");
            }
        } catch (WebClientResponseException e) {
            System.err.printf("Falha ao buscar série histórica do CDI. Status: %s, Body: %s%n", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            System.err.println("Ocorreu um erro inesperado ao buscar dados do CDI: " + e.getMessage());
        }
        return Collections.emptyMap(); // Retorna mapa vazio em caso de falha.
    }
}