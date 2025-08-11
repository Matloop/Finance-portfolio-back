package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.repository.TransactionRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Serviço completo e autocontido para buscar dados de mercado de diferentes fontes.
 * - Ações: Utiliza a API brapi.dev.
 * - Criptomoedas: Utiliza a API coingecko.com.
 *
 * Funcionalidades:
 * 1. Atualização periódica em segundo plano de todos os ativos da carteira.
 * 2. Método público para forçar uma atualização de todos os ativos (para um "botão de atualizar").
 * 3. Método público para atualizar preços de ativos específicos sob demanda (ao adicionar um novo ativo).
 */
@Service
public class MarketDataService {

    private static final Logger logger = LoggerFactory.getLogger(MarketDataService.class);

    private final WebClient brapiWebClient;
    private final WebClient coingeckoWebClient;
    private final TransactionRepository transactionRepository;

    // Cache para armazenar os preços dos ativos (ticker -> preço)
    private final Map<String, BigDecimal> priceCache = new ConcurrentHashMap<>();

    // Agendador para as atualizações periódicas em background
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * Construtor que injeta as dependências necessárias e configura os WebClients.
     * O token da Brapi é lido do arquivo application.properties.
     */
    public MarketDataService(
            WebClient.Builder webClientBuilder,
            TransactionRepository transactionRepository,
            @Value("${brapi.token}") String brapiToken) {

        this.transactionRepository = transactionRepository;

        // Cliente para a API da Brapi (Ações), com token de autorização
        this.brapiWebClient = webClientBuilder
                .baseUrl("https://brapi.dev/api")
                .defaultHeader("Authorization", "Bearer " + brapiToken)
                .build();

        // Cliente para a API do CoinGecko (Criptomoedas)
        this.coingeckoWebClient = webClientBuilder
                .baseUrl("https://api.coingecko.com/api/v3")
                .build();
    }

    /**
     * Método executado uma vez após a inicialização do serviço.
     * Inicia a primeira busca de dados e agenda as futuras.
     */
    @PostConstruct
    private void initialize() {
        logger.info("Iniciando MarketDataService. A primeira busca de dados ocorrerá em 10 segundos.");
        // A atualização agendada executa a cada 5 minutos (300 segundos)
        scheduler.scheduleAtFixedRate(this::refreshAllMarketData, 10, 300, TimeUnit.SECONDS);
    }

    /**
     * Retorna o preço de um ativo específico a partir do cache.
     *
     * @param ticker O símbolo do ativo (ex: "PETR4", "BTC").
     * @return O preço em BigDecimal, ou BigDecimal.ZERO se não for encontrado.
     */
    public BigDecimal getPrice(String ticker) {
        return priceCache.getOrDefault(ticker.toUpperCase(), BigDecimal.ZERO);
    }

    /**
     * Retorna uma visão somente leitura de todos os preços atualmente no cache.
     */
    public Map<String, BigDecimal> getAllPrices() {
        return Collections.unmodifiableMap(priceCache);
    }

    /**
     * Dispara uma atualização completa de todos os ativos existentes na base de dados.
     * Ideal para ser chamado por um endpoint de "Atualizar".
     */
    public void refreshAllMarketData() {
        logger.info("ATUALIZAÇÃO GERAL: Iniciando busca de dados para todos os ativos...");
        Map<AssetType, List<String>> tickersByType = transactionRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        Transaction::getAssetType,
                        Collectors.mapping(t -> t.getTicker().toUpperCase(), Collectors.collectingAndThen(Collectors.toSet(), List::copyOf))
                ));

        tickersByType.forEach((type, tickers) -> {
            if (!tickers.isEmpty()) {
                updatePriceForTickers(tickers, type);
            }
        });
    }

    /**
     * Busca e atualiza o preço para uma lista específica de tickers de um determinado tipo.
     * Ideal para ser chamado logo após a criação de uma nova transação.
     *
     * @param tickers   Lista de símbolos dos ativos.
     * @param assetType O tipo dos ativos (STOCK ou CRYPTO).
     */
    public void updatePriceForTickers(List<String> tickers, AssetType assetType) {
        logger.info("ATUALIZAÇÃO SOB DEMANDA: Buscando preço para {} ({})", tickers, assetType);
        switch (assetType) {
            case STOCK -> fetchStockPrices(tickers);
            case CRYPTO -> fetchCryptoPrices(tickers);
            default -> logger.warn("Tipo de ativo desconhecido: {}", assetType);
        }
    }

    // --- MÉTODOS PRIVADOS DE BUSCA ---

    private void fetchStockPrices(List<String> tickers) {
        String tickersForApi = String.join(",", tickers);
        brapiWebClient.get()
                .uri("/quote/{tickers}", tickersForApi)
                .retrieve()
                .bodyToMono(QuoteResponse.class)
                .subscribe(
                        response -> response.results().forEach(quote -> {
                            priceCache.put(quote.symbol().toUpperCase(), quote.regularMarketPrice());
                            logger.info("Cache de Ações atualizado: {} = {}", quote.symbol(), quote.regularMarketPrice());
                        }),
                        error -> logger.error("Erro ao buscar preços de ações na Brapi: {}", error.getMessage())
                );
    }

    private void fetchCryptoPrices(List<String> tickers) {
        String idsForApi = tickers.stream().map(this::mapTickerToCoingeckoId).collect(Collectors.joining(","));
        coingeckoWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/simple/price").queryParam("ids", idsForApi).queryParam("vs_currencies", "brl").build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .subscribe(
                        response -> tickers.forEach(ticker -> {
                            String coingeckoId = mapTickerToCoingeckoId(ticker);
                            if (response != null && response.has(coingeckoId) && response.get(coingeckoId).has("brl")) {
                                BigDecimal price = new BigDecimal(response.get(coingeckoId).get("brl").asText());
                                priceCache.put(ticker.toUpperCase(), price);
                                logger.info("Cache de Cripto atualizado: {} = {}", ticker.toUpperCase(), price);
                            } else {
                                logger.warn("Não foi possível encontrar o preço para a cripto: {} (ID: {})", ticker, coingeckoId);
                            }
                        }),
                        error -> logger.error("Erro ao buscar preços de cripto no CoinGecko: {}", error.getMessage())
                );
    }

    private String mapTickerToCoingeckoId(String ticker) {
        return switch (ticker.toUpperCase()) {
            case "BTC" -> "bitcoin";
            case "ETH" -> "ethereum";
            // Adicione outros mapeamentos comuns aqui se desejar
            default -> ticker.toLowerCase();
        };
    }

    // --- DTOs INTERNOS PARA PARSE DAS RESPOSTAS DAS APIS ---
    // Mantidos como 'private record' para que o serviço seja totalmente autocontido.

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QuoteResponse(List<QuoteResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QuoteResult(String symbol, BigDecimal regularMarketPrice) {}
}