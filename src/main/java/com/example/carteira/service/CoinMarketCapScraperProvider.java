// Crie este novo arquivo em: src/main/java/com/example/carteira/service/
package com.example.carteira.service;

import com.example.carteira.model.dtos.AssetSearchResultDto;
import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Primary // Defina este como o provedor primário para CRIPTO
public class CoinMarketCapScraperProvider implements MarketDataProvider {

    private static final Logger logger = LoggerFactory.getLogger(CoinMarketCapScraperProvider.class);
    private final WebClient cmcApiClient;
    private final String apiKey;
    private final Map<String, CmcMapData> tickerToCmcDataCache = new ConcurrentHashMap<>();

    // --- DTOs para a API Profissional do CoinMarketCap ---
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CmcIdMapResponse(@JsonProperty("data") List<CmcMapData> data) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CmcMapData(int id, String name, String symbol, String slug) {}
    // DTOs históricos não são mais necessários com a API gratuita

    // Construtor atualizado para injetar a chave da API
    public CoinMarketCapScraperProvider(WebClient.Builder webClientBuilder, @Value("${coinmarketcap.apikey}") String apiKey) {
        this.apiKey = apiKey;
        final int maxMemorySize = 5 * 1024 * 1024; // 500 KB (ajuste se necessário)
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxMemorySize))
                .build();

        this.cmcApiClient = webClientBuilder
                .baseUrl("https://pro-api.coinmarketcap.com")
                .defaultHeader("X-CMC_PRO_API_KEY", this.apiKey)
                .exchangeStrategies(strategies) // <-- APLICA AS NOVAS ESTRATÉGIAS
                .build();
    }

    @Override
    public boolean supports(AssetType assetType) {
        return assetType == AssetType.CRYPTO;
    }

    @Override
    public Mono<Void> initialize() {
        // CORREÇÃO: Usa o endpoint correto da API Profissional
        return cmcApiClient.get()
                .uri("/v1/cryptocurrency/map")
                .retrieve()
                .bodyToMono(CmcIdMapResponse.class)
                .doOnSuccess(response -> {
                    if (response != null && response.data() != null) {
                        // putIfAbsent para não sobrescrever com moedas menos conhecidas com o mesmo ticker
                        response.data().forEach(coin -> tickerToCmcDataCache.putIfAbsent(coin.symbol().toUpperCase(), coin));
                        logger.info("Cache do CoinMarketCap populado com {} ativos.", tickerToCmcDataCache.size());
                    }
                })
                .onErrorResume(e -> {
                    logger.error("Falha CRÍTICA ao popular cache do CoinMarketCap. Verifique sua chave de API e plano. Erro: {}", e.getMessage());
                    return Mono.empty(); // Continua para não quebrar a inicialização inteira
                })
                .then();
    }

    @Override
    public Flux<AssetSearchResultDto> search(String term) {
        String upperCaseTerm = term.toUpperCase();

        // A busca é feita em memória, o que é ultra-rápido.
        List<AssetSearchResultDto> results = tickerToCmcDataCache.values().stream()
                .filter(coin ->
                        coin.symbol().toUpperCase().startsWith(upperCaseTerm) ||
                                coin.name().toUpperCase().contains(upperCaseTerm)
                )
                .limit(10) // Limita a 10 resultados para não sobrecarregar o frontend
                .map(coin -> new AssetSearchResultDto(
                        coin.symbol(),
                        coin.name(),
                        AssetType.CRYPTO,
                        null // Criptomoedas não têm um 'Market' como B3 ou US
                ))
                .collect(Collectors.toList());

        return Flux.fromIterable(results);
    }
    @Override
    public Flux<PriceData> fetchPrices(List<AssetToFetch> assetsToFetch) {
        // Web scraping para o preço atual
        return Flux.fromIterable(assetsToFetch)
                .flatMap(this::fetchSingleCryptoPrice);
    }

    private Mono<PriceData> fetchSingleCryptoPrice(AssetToFetch asset) {
        CmcMapData cmcData = tickerToCmcDataCache.get(asset.ticker().toUpperCase());
        if (cmcData == null) {
            logger.warn("Não foi possível encontrar dados no cache do CoinMarketCap para o ticker: {}", asset.ticker());
            return Mono.empty();
        }

        String url = "https://coinmarketcap.com/currencies/" + cmcData.slug() + "/";

        return Mono.fromCallable(() -> {
                    logger.debug("Scraping URL: {}", url);
                    Document doc = Jsoup.connect(url).get();
                    // CORREÇÃO: Seletor mais específico e robusto
                    Element priceElement = doc.selectFirst(".sc-d1307656-0.jsJtkO > span");

                    if (priceElement != null) {
                        String priceText = priceElement.text()
                                .replace("$", "")
                                .replace(",", "");
                        return new PriceData(asset.ticker(), new BigDecimal(priceText));
                    }
                    logger.warn("Seletor de preço não encontrado em {} para o ticker {}", url, asset.ticker());
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
        // CORREÇÃO: Implementação segura que informa a limitação do plano.
        logger.warn("A busca de preços históricos de cripto não é suportada pelo plano gratuito da API do CoinMarketCap. Para o ativo: {}", asset.ticker());
        // Retorna um Mono vazio para não quebrar a lógica de evolução do patrimônio.
        return Mono.empty();
    }
}