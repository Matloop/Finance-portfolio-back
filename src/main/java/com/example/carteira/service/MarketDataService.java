package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.repository.TransactionRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Serviço completo e autocontido para buscar dados de mercado de diferentes fontes.
 * - Ações: Utiliza a API alphavantage.co (adaptado da Brapi).
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

    // MUDANÇA: WebClient renomeado para refletir a nova API
    private final WebClient alphaVantageWebClient;
    private final WebClient coingeckoWebClient;
    private final TransactionRepository transactionRepository;

    // MUDANÇA: Chave da API da Alpha Vantage
    private final String alphaVantageApiKey;

    private final Map<String, BigDecimal> priceCache = new ConcurrentHashMap<>();
    //cache to map the id in coingecko
    private final Map<String, String> tickerToCoingeckoIdCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * Construtor que injeta as dependências necessárias e configura os WebClients.
     * O token da Alpha Vantage é lido do arquivo application.properties.
     */
    public MarketDataService(
            WebClient.Builder webClientBuilder,
            TransactionRepository transactionRepository,
            // MUDANÇA: Injetando a chave da Alpha Vantage
            @Value("${alphavantage.apikey}") String alphaVantageApiKey) {

        this.transactionRepository = transactionRepository;
        this.alphaVantageApiKey = alphaVantageApiKey;

        // MUDANÇA: Cliente configurado para a API da Alpha Vantage
        this.alphaVantageWebClient = webClientBuilder
                .baseUrl("https://www.alphavantage.co")
                .build();

        // Cliente para a API do CoinGecko (Criptomoedas) - SEM MUDANÇAS AQUI
        this.coingeckoWebClient = webClientBuilder
                .baseUrl("https://api.coingecko.com/api/v3")
                .build();
    }

    @PostConstruct
    private void initialize() {
        logger.info("Iniciando MarketDataService...");
        populateCryptoIdCache()
                .doOnSuccess(aVoid -> { // o argumento aqui é ignorado, mas necessário pela assinatura
                    logger.info("Cache de IDs de Criptomoedas populado com sucesso!");
                    logger.info("A primeira busca de dados de preços ocorrerá em 10 segundos.");
                    scheduler.scheduleAtFixedRate(this::refreshAllMarketData, 10, 300, TimeUnit.SECONDS);
                })
                .doOnError(error -> {
                    logger.error("Falha ao popular o cache de IDs de Criptomoedas! O serviço pode não funcionar corretamente para criptoativos.", error);
                    // Mesmo com erro, agenda a tarefa.
                    scheduler.scheduleAtFixedRate(this::refreshAllMarketData, 10, 300, TimeUnit.SECONDS);
                })
                .subscribe(); // Apenas "aciona" o Mono.
    }


    private Mono<Void> populateCryptoIdCache() {
        logger.info("Populando caches");
        return coingeckoWebClient.get()
                .uri("/coins/list")
                .retrieve()
                .bodyToFlux(CoinGeckoCoin.class)
                // Passo 1: Em vez de coletar para um mapa, colete todos os itens em uma lista.
                // O Mono resultante será um Mono<List<CoinGeckoCoin>>.
                .collectList()
                // Passo 2: Quando a lista estiver pronta, use 'doOnNext' para processá-la.
                .doOnNext(coinList -> {
                    // Use a API de Streams do Java, que é extremamente robusta.
                    Map<String, String> newCacheMap = coinList.stream()
                            .collect(Collectors.toMap(
                                    // A chave do mapa será o símbolo (ticker) em maiúsculas
                                    coin -> coin.symbol().toUpperCase(),
                                    // O valor será o ID
                                    CoinGeckoCoin::id,
                                    // Função de merge para resolver conflitos de chaves duplicadas
                                    (existingId, newId) -> existingId
                            ));
                    // Atualize seu cache de uma só vez com o novo mapa.
                    tickerToCoingeckoIdCache.putAll(newCacheMap);
                })
                // Passo 3: Após o processamento, transforme o resultado de volta em um Mono<Void>.
                .then();
    }
    public BigDecimal getPrice(String ticker) {
        return priceCache.getOrDefault(ticker.toUpperCase(), BigDecimal.ZERO);
    }

    public Map<String, BigDecimal> getAllPrices() {
        return Collections.unmodifiableMap(priceCache);
    }

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
        // O plano gratuito da Alpha Vantage tem um limite de ~5 chamadas por minuto.
        // Um delay de 15 segundos entre cada chamada é uma abordagem segura.
        Flux.fromIterable(tickers)
                .delayElements(Duration.ofSeconds(15)) // IMPORTANTE: Respeita o rate limit
                .flatMap(this::fetchSingleStockPrice) // Chama a API para cada ticker
                .subscribe(
                        // onNext: o que fazer quando um preço é recebido com sucesso
                        quote -> {
                            // A API retorna o símbolo com sufixo ".SA", removemos para manter o cache consistente
                            String ticker = quote.symbol().replace(".SA", "");
                            priceCache.put(ticker.toUpperCase(), quote.price());
                            logger.info("Cache de Ações atualizado: {} = {}", ticker.toUpperCase(), quote.price());
                        },
                        // onError: o que fazer se ocorrer um erro no fluxo
                        error -> logger.error("Erro no fluxo de busca de preços de ações: {}", error.getMessage())
                );
    }

    /**
     * MUDANÇA: Novo método auxiliar para buscar o preço de um único ativo na Alpha Vantage.
     * Retorna um Mono, que pode conter o resultado, um erro ou ser vazio.
     */
    private Mono<GlobalQuote> fetchSingleStockPrice(String ticker) {
        // Ativos da B3 precisam do sufixo ".SA" na Alpha Vantage
        String tickerForApi = ticker + ".SA";

        return alphaVantageWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/query")
                        .queryParam("function", "GLOBAL_QUOTE")
                        .queryParam("symbol", tickerForApi)
                        .queryParam("apikey", this.alphaVantageApiKey)
                        .build())
                .retrieve()
                .bodyToMono(AlphaVantageQuoteResponse.class)
                .map(AlphaVantageQuoteResponse::quote)
                .filter(Objects::nonNull) // Filtra respostas onde o objeto "Global Quote" é nulo (ex: ticker inválido ou limite de API)
                .doOnError(error -> logger.error("Erro ao buscar preço para {}: {}", ticker, error.getMessage()))
                .onErrorResume(e -> Mono.empty()); // Se um ticker falhar, não quebra o fluxo dos outros
    }

    private void fetchCryptoPrices(List<String> tickers) {
        String idsForApi = tickers.stream().map(this::mapTickerToCoingeckoId).filter(Objects::nonNull).collect(Collectors.joining(","));
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
        String id = tickerToCoingeckoIdCache.get(ticker.toUpperCase());
        if (id == null) {
            logger.warn("ID não encontrado");
        }
        return id;
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AlphaVantageQuoteResponse(@JsonProperty("Global Quote") GlobalQuote quote) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GlobalQuote(
            @JsonProperty("01. symbol") String symbol,
            @JsonProperty("05. price") BigDecimal price) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CoinGeckoCoin(String id, String symbol, String name) {}
}