package com.example.carteira.service;

import com.example.carteira.model.dtos.AssetSearchResultDto;
import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.dtos.yahooscraper.ChartDataDto;
import com.example.carteira.model.dtos.yahooscraper.YahooChartResponseDto;
import com.example.carteira.model.dtos.yahooscraper.YahooQuoteDto;
import com.example.carteira.model.dtos.yahooscraper.YahooSearchResponseDto;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.example.carteira.service.util.ExchangeRateService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CORREÇÕES APLICADAS:
 * 1. Batch fetching via API de chart (evita scraping individual)
 * 2. Fallback inteligente: API primeiro, scraping como último recurso
 * 3. Paralelização controlada para scraping
 * 4. Cache de tickers canônicos
 * 5. Otimização do fluxo de criptomoedas (apenas API, sem scraping)
 */
@Service
@Primary
public class WebScraperService implements MarketDataProvider {
    private static final Logger logger = LoggerFactory.getLogger(WebScraperService.class);
    private static final String BASE_URL = "https://finance.yahoo.com/quote/";
    private static final int MAX_CONCURRENT_SCRAPING = 5; // Limita scraping concorrente

    private final ExchangeRateService exchangeRateService;
    private BigDecimal usdToBrlRate = BigDecimal.ONE;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
            " (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36";
    private final WebClient webClient;

    // CORREÇÃO #1: Cache de tickers canônicos para evitar buscas repetidas
    private final Map<String, String> canonicalTickerCache = new HashMap<>();

    public WebScraperService(ExchangeRateService exchangeRateService, WebClient.Builder webClientBuilder) {
        this.exchangeRateService = exchangeRateService;
        this.webClient = webClientBuilder.baseUrl("https://query1.finance.yahoo.com").build();
    }

    private PriceData extractPriceFromDocument(Document doc, String originalTicker) throws NumberFormatException {
        Element priceElement = doc.selectFirst("section[data-testid=\"quote-price\"] span[data-testid=\"qsp-price\"]");

        if (priceElement != null) {
            String priceText = priceElement.text();
            logger.debug("Texto do preço bruto para {}: '{}'", originalTicker, priceText);

            if (priceText.contains(",") && priceText.contains(".")) {
                priceText = priceText.replace(",", "");
            } else {
                priceText = priceText.replace(",", ".");
            }

            try {
                BigDecimal price = new BigDecimal(priceText);
                logger.debug("Preço extraído com sucesso para {}: {}", originalTicker, price);
                return new PriceData(originalTicker, price);
            } catch (NumberFormatException e) {
                logger.error("Falha ao converter '{}' para BigDecimal para {}", priceText, originalTicker);
                throw e;
            }
        } else {
            logger.warn("Elemento de preço não encontrado para {}", originalTicker);
            return null;
        }
    }

    @Override
    public Mono<PriceData> fetchHistoricalPrice(AssetToFetch asset, LocalDate date) {
        return findCanonicalTicker(asset)
                .flatMap(canonicalTicker -> {
                    logger.info("Buscando preço histórico para {} ({}) na data {}",
                            asset.ticker(), canonicalTicker, date);
                    return webClient.get()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/v8/finance/chart/" + canonicalTicker)
                                    .queryParam("range", "5y")
                                    .queryParam("interval", "1d")
                                    .build())
                            .retrieve()
                            .bodyToMono(YahooChartResponseDto.class)
                            .map(response -> extractHistoricalPriceFromChart(response, asset.ticker(), date));
                })
                .filter(Objects::nonNull)
                .onErrorResume(e -> {
                    logger.error("Erro ao buscar preço histórico para {}: {}", asset.ticker(), e.getMessage());
                    return Mono.empty();
                });
    }

    private PriceData extractHistoricalPriceFromChart(YahooChartResponseDto response,
                                                      String originalTicker,
                                                      LocalDate date) {
        if (response != null && response.chart() != null && !response.chart().result().isEmpty()) {
            ChartDataDto data = response.chart().result().get(0);
            if (data != null && data.timestamp() != null &&
                    data.indicators() != null && !data.indicators().quote().isEmpty()) {

                List<Long> timestamps = data.timestamp();
                List<BigDecimal> prices = data.indicators().quote().get(0).close();

                if (prices != null && timestamps.size() == prices.size()) {
                    for (int i = timestamps.size() - 1; i >= 0; i--) {
                        if (timestamps.get(i) != null && prices.get(i) != null) {
                            LocalDate candleDate = Instant.ofEpochSecond(timestamps.get(i))
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate();
                            if (!candleDate.isAfter(date)) {
                                return new PriceData(originalTicker, prices.get(i));
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * CORREÇÃO #2: Busca ticker canônico com cache para evitar requisições repetidas
     */
    private Mono<String> findCanonicalTicker(AssetToFetch asset) {
        final String searchTerm = asset.ticker();

        // Verifica cache primeiro
        String cached = canonicalTickerCache.get(searchTerm);
        if (cached != null) {
            logger.debug("💾 Ticker canônico recuperado do cache: {} -> {}", searchTerm, cached);
            return Mono.just(cached);
        }

        // Lógica específica para Cripto
        if (AssetType.CRYPTO.equals(asset.assetType())) {
            return searchAndFilterYahooAPI(searchTerm, "CRYPTOCURRENCY")
                    .doOnNext(ticker -> canonicalTickerCache.put(searchTerm, ticker));
        }

        // Lógica existente para Ações/ETFs
        if (!searchTerm.contains(" ") && searchTerm.length() < 10) {
            if (asset.market() == Market.B3 && !searchTerm.contains(".")) {
                String ticker = searchTerm + ".SA";
                canonicalTickerCache.put(searchTerm, ticker);
                return Mono.just(ticker);
            }
            canonicalTickerCache.put(searchTerm, searchTerm);
            return Mono.just(searchTerm);
        }

        return searchAndFilterYahooAPI(searchTerm, "EQUITY", "ETF", "INDEX")
                .doOnNext(ticker -> canonicalTickerCache.put(searchTerm, ticker));
    }

    private Mono<String> searchAndFilterYahooAPI(String searchTerm, String... desiredQuoteTypes) {
        logger.debug("Buscando ticker canônico para '{}', tipos: {}", searchTerm, Arrays.toString(desiredQuoteTypes));

        return this.webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/finance/search").queryParam("q", searchTerm).build())
                .retrieve()
                .bodyToMono(YahooSearchResponseDto.class)
                .flatMap(response -> {
                    if (response.quotes() == null || response.quotes().isEmpty()) {
                        logger.warn("Nenhum resultado na busca do Yahoo para: {}", searchTerm);
                        return Mono.empty();
                    }

                    List<String> desiredTypesList = Arrays.asList(desiredQuoteTypes);
                    Optional<String> foundTicker = response.quotes().stream()
                            .filter(q -> desiredTypesList.contains(q.quoteType().toUpperCase()))
                            .findFirst()
                            .map(YahooQuoteDto::symbol);

                    String canonicalTicker = foundTicker.orElse(response.quotes().get(0).symbol());
                    logger.debug("Ticker canônico encontrado: {} para '{}'", canonicalTicker, searchTerm);
                    return Mono.just(canonicalTicker);
                })
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * CORREÇÃO #3: Batch fetching via API de chart (muito mais rápido que scraping)
     * Agrupa ativos por tipo e usa a estratégia mais eficiente
     */
    @Override
    public Flux<PriceData> fetchPrices(List<AssetToFetch> assetsToFetch) {
        if (assetsToFetch.isEmpty()) return Flux.empty();

        // Separa criptomoedas de ações/ETFs
        Map<Boolean, List<AssetToFetch>> grouped = assetsToFetch.stream()
                .collect(Collectors.partitioningBy(a -> AssetType.CRYPTO.equals(a.assetType())));

        List<AssetToFetch> cryptos = grouped.get(true);
        List<AssetToFetch> stocksAndEtfs = grouped.get(false);

        logger.info("📊 Buscando preços em lote: {} criptos, {} ações/ETFs",
                cryptos.size(), stocksAndEtfs.size());

        Flux<PriceData> cryptoFlux = fetchCryptoPricesBatch(cryptos);
        Flux<PriceData> stockFlux = fetchStockPricesBatch(stocksAndEtfs);

        return Flux.merge(cryptoFlux, stockFlux);
    }

    /**
     * CORREÇÃO #4: Busca em lote de criptomoedas via API (sem scraping)
     */
    private Flux<PriceData> fetchCryptoPricesBatch(List<AssetToFetch> cryptos) {
        if (cryptos.isEmpty()) return Flux.empty();

        logger.info("🪙 Buscando {} criptomoedas via API do Yahoo", cryptos.size());

        return Flux.fromIterable(cryptos)
                .flatMap(this::fetchCryptocurrencyPriceViaAPI, 3); // 3 requisições concorrentes
    }

    /**
     * CORREÇÃO #5: Busca em lote de ações/ETFs com fallback inteligente
     * Tenta API primeiro (rápido), scraping só como último recurso
     */
    private Flux<PriceData> fetchStockPricesBatch(List<AssetToFetch> stocks) {
        if (stocks.isEmpty()) return Flux.empty();

        logger.info("📈 Buscando {} ações/ETFs (API primeiro, scraping como fallback)", stocks.size());

        // Tenta buscar todos via API primeiro (MUITO mais rápido)
        return Flux.fromIterable(stocks)
                .flatMap(asset -> fetchStockPriceViaAPI(asset)
                                .switchIfEmpty(Mono.defer(() -> {
                                    logger.debug("⚠️ API falhou para {}, tentando scraping...", asset.ticker());
                                    return fetchStockPriceViaScraping(asset);
                                })),
                        MAX_CONCURRENT_SCRAPING); // Limita scraping concorrente
    }

    /**
     * CORREÇÃO #6: Novo método para buscar via API de chart (rápido e confiável)
     */
    private Mono<PriceData> fetchStockPriceViaAPI(AssetToFetch asset) {
        return findCanonicalTicker(asset)
                .flatMap(canonicalTicker -> {
                    logger.debug("🔍 API: Buscando {} ({})", asset.ticker(), canonicalTicker);
                    return webClient.get()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/v8/finance/chart/" + canonicalTicker)
                                    .queryParam("range", "1d")
                                    .queryParam("interval", "1m")
                                    .build())
                            .retrieve()
                            .bodyToMono(YahooChartResponseDto.class)
                            .map(response -> extractCurrentPriceFromChart(response, asset.ticker()))
                            .filter(Objects::nonNull);
                })
                .onErrorResume(e -> {
                    logger.debug("❌ API falhou para {}: {}", asset.ticker(), e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * CORREÇÃO #7: Scraping agora é o último recurso (paralelização controlada)
     */
    private Mono<PriceData> fetchStockPriceViaScraping(AssetToFetch asset) {
        String tickerForUrl = asset.ticker();
        if (asset.market() == Market.B3 && !tickerForUrl.contains(".")) {
            tickerForUrl += ".SA";
        }

        String url = BASE_URL + tickerForUrl;

        return Mono.fromCallable(() -> {
                    logger.debug("🕷️ Scraping: {}", url);
                    Document doc = Jsoup.connect(url)
                            .userAgent(USER_AGENT)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .timeout(5000) // Timeout de 5s
                            .get();
                    return extractPriceFromDocument(doc, asset.ticker());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .filter(Objects::nonNull)
                .doOnError(error -> logger.warn("❌ Scraping falhou para {}: {}",
                        asset.ticker(), error.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Busca preço de criptomoeda APENAS via API (sem scraping que causa 404)
     */
    private Mono<PriceData> fetchCryptocurrencyPriceViaAPI(AssetToFetch asset) {
        logger.debug("🪙 Buscando cripto via API: {}", asset.ticker());

        return findCanonicalCryptoTicker(asset.ticker())
                .flatMap(canonicalTicker -> {
                    return webClient.get()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/v8/finance/chart/" + canonicalTicker)
                                    .queryParam("range", "1d")
                                    .queryParam("interval", "1m")
                                    .build())
                            .retrieve()
                            .bodyToMono(YahooChartResponseDto.class)
                            .map(response -> extractCurrentPriceFromChart(response, asset.ticker()))
                            .filter(Objects::nonNull);
                })
                .onErrorResume(e -> {
                    logger.error("❌ Erro ao buscar cripto {}: {}", asset.ticker(), e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<String> findCanonicalCryptoTicker(String searchTerm) {
        // Verifica cache primeiro
        String cached = canonicalTickerCache.get(searchTerm);
        if (cached != null) {
            return Mono.just(cached);
        }

        return this.webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/finance/search").queryParam("q", searchTerm).build())
                .retrieve()
                .bodyToMono(YahooSearchResponseDto.class)
                .flatMap(response -> {
                    if (response.quotes() == null || response.quotes().isEmpty()) {
                        return Mono.empty();
                    }

                    Optional<String> cryptoTicker = response.quotes().stream()
                            .filter(q -> "CRYPTOCURRENCY".equalsIgnoreCase(q.quoteType()))
                            .findFirst()
                            .map(YahooQuoteDto::symbol);

                    if (cryptoTicker.isPresent()) {
                        canonicalTickerCache.put(searchTerm, cryptoTicker.get());
                        return Mono.just(cryptoTicker.get());
                    }
                    return Mono.empty();
                })
                .onErrorResume(e -> Mono.empty());
    }

    private PriceData extractCurrentPriceFromChart(YahooChartResponseDto response, String originalTicker) {
        if (response == null || response.chart() == null || response.chart().result().isEmpty()) {
            return null;
        }

        ChartDataDto data = response.chart().result().get(0);
        if (data == null || data.indicators() == null || data.indicators().quote().isEmpty()) {
            return null;
        }

        List<BigDecimal> prices = data.indicators().quote().get(0).close();
        if (prices == null || prices.isEmpty()) {
            return null;
        }

        BigDecimal currentPrice = null;
        for (int i = prices.size() - 1; i >= 0; i--) {
            if (prices.get(i) != null) {
                currentPrice = prices.get(i);
                break;
            }
        }

        if (currentPrice != null) {
            logger.debug("✅ Preço extraído via API para {}: {}", originalTicker, currentPrice);
            return new PriceData(originalTicker, currentPrice);
        }
        return null;
    }

    @Override
    public boolean supports(AssetType assetType) {
        return assetType == AssetType.STOCK || assetType == AssetType.ETF || assetType == AssetType.CRYPTO;
    }

    @Override
    public Mono<Void> initialize() {
        return exchangeRateService.fetchUsdToBrlRate()
                .doOnSuccess(rate -> {
                    if (rate != null) {
                        this.usdToBrlRate = rate;
                        logger.info("✅ Taxa USD/BRL carregada: {}", rate);
                    } else {
                        logger.warn("⚠️ Usando taxa USD/BRL padrão: {}", this.usdToBrlRate);
                    }
                })
                .then();
    }

    @Override
    public Flux<AssetSearchResultDto> search(String term) {
        String cleanedTerm = term.replace("&", "");

        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/finance/search").queryParam("q", cleanedTerm).build())
                .retrieve()
                .bodyToMono(YahooSearchResponseDto.class)
                .flatMapMany(response -> {
                    if (response == null || response.quotes() == null || response.quotes().isEmpty()) {
                        return Flux.empty();
                    }

                    List<AssetSearchResultDto> results = response.quotes().stream()
                            .map(this::mapYahooQuoteToSearchResult)
                            .filter(Objects::nonNull)
                            .limit(10)
                            .collect(Collectors.toList());

                    return Flux.fromIterable(results);
                })
                .onErrorResume(e -> {
                    logger.error("Erro na busca para '{}': {}", term, e.getMessage());
                    return Flux.empty();
                });
    }

    private AssetSearchResultDto mapYahooQuoteToSearchResult(YahooQuoteDto quote) {
        AssetType assetType;
        Market market = null;

        switch (quote.quoteType().toUpperCase()) {
            case "EQUITY":
                assetType = AssetType.STOCK;
                if ("SAO".equals(quote.exchange())) market = Market.B3;
                else if (List.of("NMS", "NYQ").contains(quote.exchange())) market = Market.US;
                break;

            case "ETF":
                assetType = AssetType.ETF;
                if ("SAO".equals(quote.exchange())) market = Market.B3;
                else if (List.of("NMS", "NYQ", "PCX").contains(quote.exchange())) market = Market.US;
                break;

            case "CRYPTOCURRENCY":
                assetType = AssetType.CRYPTO;
                market = null;
                break;

            default:
                return null;
        }

        return new AssetSearchResultDto(quote.symbol(), quote.shortname(), assetType, market);
    }
}