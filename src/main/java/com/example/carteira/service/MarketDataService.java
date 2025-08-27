package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
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
import org.yaml.snakeyaml.error.Mark;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
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

    private static final Map<String, String> PRIORITY_MAP = Map.of(
            "BTC", "bitcoin",
            "ETH", "ethereum",
            "USDT", "tether",
            "BNB", "binancecoin",
            "SOL", "solana",
            "USDC", "usd-coin",
            "XRP", "ripple",
            "ADA", "cardano"
    );

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
        tickerToCoingeckoIdCache.clear();
        tickerToCoingeckoIdCache.putAll(PRIORITY_MAP);
        logger.info("Cache de prioridade carregado com {} ativos.", PRIORITY_MAP.size());
        return coingeckoWebClient.get()
                .uri("/coins/list")
                .retrieve()
                .bodyToFlux(CoinGeckoCoin.class)
                // Passo 1: Em vez de coletar para um mapa, colete todos os itens em uma lista.
                // O Mono resultante será um Mono<List<CoinGeckoCoin>>.
                // Passo 2: Quando a lista estiver pronta, use 'doOnNext' para processá-la.
                .doOnNext(coin -> {
                    // A chave é o símbolo em maiúsculas
                    String symbol = coin.symbol().toUpperCase();
                    // [MUDANÇA CRÍTICA] Usa putIfAbsent para não sobrescrever os prioritários.
                    if (symbol != null && !symbol.isBlank()) {
                        tickerToCoingeckoIdCache.putIfAbsent(symbol, coin.id());
                    }
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

        // Passo 1: Busca todas as transações de uma só vez.
        List<Transaction> allTransactions = transactionRepository.findAll();

        // Passo 2: Delega o trabalho de processamento para o método unificado.
        if (!allTransactions.isEmpty()) {
            updatePriceForTickers(allTransactions);
        } else {
            logger.info("Nenhuma transação na carteira para atualizar.");
        }
    }

    public void updatePriceForTickers(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return;
        }
        logger.info("Processando atualização de preço para {} transações...", transactions.size());

        // Agrupa as transações por tipo, para chamar o fetcher correto.
        Map<AssetType, List<Transaction>> groupedByType = transactions.stream()
                .collect(Collectors.groupingBy(Transaction::getAssetType));

        // Itera sobre cada grupo (STOCK, ETF, CRYPTO, etc.)
        groupedByType.forEach((assetType, transactionList) -> {
            switch (assetType) {
                case STOCK, ETF -> {
                    // PASSO CRÍTICO: Garantir que cada ativo seja único.
                    // Se o usuário tem 5 compras de PETR4, só queremos buscar o preço uma vez.
                    Set<AssetToFetch> uniqueAssets = transactionList.stream()
                            .map(t -> new AssetToFetch(t.getTicker().toUpperCase(), t.getMarket()))
                            .collect(Collectors.toSet());

                    // Agora, extraímos as listas paralelas a partir do conjunto único.
                    List<String> tickers = uniqueAssets.stream().map(AssetToFetch::ticker).toList();
                    List<Market> markets = uniqueAssets.stream().map(AssetToFetch::market).toList();

                    if (!tickers.isEmpty()) {
                        fetchStockPrices(tickers, markets);
                    }
                }
                case CRYPTO -> {
                    // Para cripto, a unicidade é apenas pelo ticker.
                    Set<String> uniqueTickers = transactionList.stream()
                            .map(t -> t.getTicker().toUpperCase())
                            .collect(Collectors.toSet());

                    if (!uniqueTickers.isEmpty()) {
                        // O método fetchCryptoPrices já espera uma List, então convertemos.
                        fetchCryptoPrices(new ArrayList<>(uniqueTickers));
                    }
                }
                default -> logger.warn("Tipo de ativo desconhecido para atualização: {}", assetType);
            }
        });
    }

    private void fetchStockPrices(List<String> tickers, List<Market> markets) {
        if (tickers.size() != markets.size()) {
            logger.error("Erro crítico: As listas de tickers e markets estão dessincronizadas.");
            return;
        }

        // A criação do AssetToFetch já estava correta, pois esperava um Market
        List<AssetToFetch> assetsToFetch = new ArrayList<>();
        for (int i = 0; i < tickers.size(); i++) {
            assetsToFetch.add(new AssetToFetch(tickers.get(i), markets.get(i)));
        }


        Flux.fromIterable(assetsToFetch)
                .delayElements(Duration.ofSeconds(15))
                .flatMap(this::fetchSingleStockPrice) // Agora passamos o objeto completo
                .subscribe(
                        quote -> {
                            String ticker = quote.symbol().replace(".SA", "");
                            priceCache.put(ticker.toUpperCase(), quote.price());
                            logger.info("Cache de Ações atualizado: {} = {}", ticker.toUpperCase(), quote.price());
                        },
                        error -> logger.error("Erro no fluxo de busca de preços de ações: {}", error.getMessage())
                );
    }

    private Mono<GlobalQuote> fetchSingleStockPrice(AssetToFetch asset) {
        String tickerForApi;

        // A lógica crucial: decidir se o sufixo ".SA" é necessário.
        if (asset.market() == Market.B3) {
            tickerForApi = asset.ticker() + ".SA";
        } else {
            tickerForApi = asset.ticker();
        }

        // O resto da chamada da API é o mesmo de antes.
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
                .filter(Objects::nonNull)
                .doOnError(error -> logger.error("Erro ao buscar preço para {}: {}", asset.ticker(), error.getMessage()))
                .onErrorResume(e -> Mono.empty());
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
    private record MarketAssetKey(AssetType type, Market market) {}
    private record AssetIdentifier(String ticker, String market,AssetType type) {}
    private record AssetToFetch(String ticker, Market market) {}
}