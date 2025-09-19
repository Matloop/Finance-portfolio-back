package com.example.carteira.service;

import com.example.carteira.model.dtos.AssetSearchResultDto;
import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
// @Primary // Mantenha comentado se o WebScraperService for o seu fallback
public class CoinMarketCapScraperProvider implements MarketDataProvider {

    private static final Logger logger = LoggerFactory.getLogger(CoinMarketCapScraperProvider.class);
    private final WebClient cmcApiClient;
    private final String apiKey;
    private final Map<String, CmcMapData> tickerToCmcDataCache = new ConcurrentHashMap<>();

    public CoinMarketCapScraperProvider(WebClient.Builder webClientBuilder, @Value("${coinmarketcap.apikey}") String apiKey) {
        this.apiKey = apiKey;
        final int maxMemorySize = 5 * 1024 * 1024;
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxMemorySize))
                .build();
        this.cmcApiClient = webClientBuilder
                .baseUrl("https://pro-api.coinmarketcap.com")
                .defaultHeader("X-CMC_PRO_API_KEY", this.apiKey)
                .exchangeStrategies(strategies)
                .build();
    }

    @Override
    public boolean supports(AssetType assetType) {
        return assetType == AssetType.CRYPTO;
    }

    @Override
    public Mono<Void> initialize() {
        return cmcApiClient.get()
                .uri("/v1/cryptocurrency/map")
                .retrieve()
                .bodyToMono(CmcIdMapResponse.class)
                .doOnSuccess(response -> {
                    if (response != null && response.data() != null) {
                        response.data().forEach(coin -> tickerToCmcDataCache.putIfAbsent(coin.symbol().toUpperCase(), coin));
                        logger.info("Cache do CoinMarketCap populado com {} ativos.", tickerToCmcDataCache.size());
                    }
                })
                .onErrorResume(e -> {
                    logger.error("Falha CRÍTICA ao popular cache do CoinMarketCap. Verifique sua chave de API e plano. Erro: {}", e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    @Override
    public Flux<AssetSearchResultDto> search(String term) {
        String upperCaseTerm = term.toUpperCase();
        List<AssetSearchResultDto> results = tickerToCmcDataCache.values().stream()
                .filter(coin ->
                        coin.symbol().toUpperCase().startsWith(upperCaseTerm) ||
                                coin.name().toUpperCase().contains(upperCaseTerm)
                )
                .limit(10)
                .map(coin -> new AssetSearchResultDto(
                        coin.symbol(),
                        coin.name(),
                        AssetType.CRYPTO,
                        null
                ))
                .collect(Collectors.toList());
        return Flux.fromIterable(results);
    }

    @Override
    public Flux<PriceData> fetchPrices(List<AssetToFetch> assetsToFetch) {
        String symbols = assetsToFetch.stream()
                .map(AssetToFetch::ticker)
                .collect(Collectors.joining(","));
        if (symbols.isEmpty()) return Flux.empty();

        logger.info("Buscando preços atuais na API do CoinMarketCap para: {}", symbols);

        return cmcApiClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/cryptocurrency/quotes/latest")
                        .queryParam("symbol", symbols)
                        .queryParam("convert", "BRL") // Pede o preço já em Reais
                        .build())
                .retrieve()
                .bodyToMono(CmcQuoteResponse.class)
                .flatMapMany(response -> {
                    if (response == null || response.data() == null) return Flux.empty();

                    List<PriceData> prices = response.data().values().stream()
                            .flatMap(List::stream)
                            .map(quoteData -> {
                                CmcQuotePrice quote = quoteData.quote().get("BRL");
                                if (quote != null && quote.price() != null) {
                                    return new PriceData(quoteData.symbol(), quote.price());
                                }
                                return null;
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    return Flux.fromIterable(prices);
                })
                .onErrorResume(e -> {
                    logger.error("Erro ao buscar preços atuais no CoinMarketCap: {}", e.getMessage());
                    return Flux.empty(); // Retorna vazio para acionar o fallback, se houver
                });
    }


    // ***** MÉTODO DE SCRAPING CORRIGIDO *****
    private Mono<PriceData> fetchSingleCryptoPrice(AssetToFetch asset) {
        CmcMapData cmcData = tickerToCmcDataCache.get(asset.ticker().toUpperCase());
        if (cmcData == null) {
            logger.warn("Não foi possível encontrar dados no cache do CoinMarketCap para o ticker: {}", asset.ticker());
            return Mono.empty();
        }

        String url = "https://coinmarketcap.com/currencies/" + cmcData.slug() + "/";

        return Mono.fromCallable(() -> {
                    logger.debug("Scraping para preço atual na URL: {}", url);
                    Document doc = Jsoup.connect(url).get();

                    // CORREÇÃO: Novo seletor, mais robusto
                    Element priceElement = doc.selectFirst("div[data-testid='price-section'] span");

                    if (priceElement != null) {
                        String priceText = priceElement.text(); // Ex: "$63,018.66"

                        // Lógica de parsing robusta para remover símbolos e separadores
                        priceText = priceText.replaceAll("[^\\d.]", ""); // Remove tudo que não for dígito ou ponto

                        return new PriceData(asset.ticker(), new BigDecimal(priceText));
                    }

                    logger.warn("Seletor de preço não foi encontrado em {} para o ticker {}", url, asset.ticker());
                    return null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .filter(Objects::nonNull)
                .onErrorResume(e -> {
                    logger.error("Erro ao fazer scraping no CoinMarketCap para {}: {}", asset.ticker(), e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<PriceData> fetchHistoricalPrice(AssetToFetch asset, LocalDate date) {
        logger.warn("A busca de preços históricos de cripto não é suportada por este provedor. Ativo: {}", asset.ticker());
        return Mono.empty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true) private record CmcIdMapResponse(@JsonProperty("data") List<CmcMapData> data) {}
    @JsonIgnoreProperties(ignoreUnknown = true) private record CmcMapData( String name, String symbol, String slug,int id) {}
    @JsonIgnoreProperties(ignoreUnknown = true) private record CmcQuoteResponse(@JsonProperty("data") Map<String, List<CmcQuoteData>> data) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CmcQuoteData(
            int id, // <-- Adiciona o ID para referência
            String symbol, // <-- Adiciona o símbolo para referência
            @JsonProperty("quote") Map<String, CmcQuotePrice> quote
    ) {}
    @JsonIgnoreProperties(ignoreUnknown = true) private record CmcQuotePrice(BigDecimal price) {}
}