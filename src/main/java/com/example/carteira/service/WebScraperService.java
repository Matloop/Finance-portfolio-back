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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Primary
public class WebScraperService implements MarketDataProvider {
    private static final Logger logger = LoggerFactory.getLogger(WebScraperService.class);
    private static final String BASE_URL = "https://finance.yahoo.com/quote/";
    private final ExchangeRateService exchangeRateService;
    private BigDecimal usdToBrlRate = BigDecimal.ONE;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
            " (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36";
    private final WebClient webClient;

    public WebScraperService(ExchangeRateService exchangeRateService, WebClient.Builder webClientBuilder) {
        this.exchangeRateService = exchangeRateService;
        this.webClient = webClientBuilder.baseUrl("https://query1.finance.yahoo.com").build();
    }

    private PriceData extractPriceFromDocument(Document doc, String originalTicker) throws NumberFormatException {
        Element priceElement = doc.selectFirst("section[data-testid=\"quote-price\"] span[data-testid=\"qsp-price\"]");

        if (priceElement != null) {
            String priceText = priceElement.text(); // Ex: "112,882.20"
            logger.info("Texto do preço bruto para {}: '{}'", originalTicker, priceText);
            // Lógica de parsing robusta que já está correta
            if (priceText.contains(",") && priceText.contains(".")) {
                // Formato americano: "112,882.20" -> Removemos a vírgula de milhar.
                priceText = priceText.replace(",", "");
            } else {
                // Formato brasileiro/europeu: "35,52" -> Substituímos a vírgula por ponto.
                priceText = priceText.replace(",", ".");
            }
            logger.info("Texto do preço processado para {}: '{}'", originalTicker, priceText);

            try {
                BigDecimal price = new BigDecimal(priceText);
                logger.info("Preço extraído com sucesso para {}: {}", originalTicker, price);
                return new PriceData(originalTicker, price);
            } catch (NumberFormatException e) {
                logger.error("Falha ao converter o texto limpo '{}' para BigDecimal para o ticker {}", priceText, originalTicker);
                throw e;
            }

        } else {
            logger.warn("Elemento de preço não encontrado no documento HTML para o ticker: {}. A estrutura da página pode ter mudado.", originalTicker);
            return null;
        }
    }

    @Override
    public Mono<PriceData> fetchHistoricalPrice(AssetToFetch asset, LocalDate date) {
        return findCanonicalTicker(asset)
                .flatMap(canonicalTicker -> {
                    logger.info("Buscando preço histórico para {} ({}) na data {}", asset.ticker(), canonicalTicker, date);
                    return webClient.get()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/v8/finance/chart/" + canonicalTicker)
                                    .queryParam("range", "5y")
                                    .queryParam("interval", "1d")
                                    .build())
                            .retrieve()
                            .bodyToMono(YahooChartResponseDto.class)
                            .map(response -> {
                                if (response != null && response.chart() != null && !response.chart().result().isEmpty()) {
                                    ChartDataDto data = response.chart().result().get(0);
                                    if (data != null && data.timestamp() != null && data.indicators() != null && !data.indicators().quote().isEmpty()) {
                                        List<Long> timestamps = data.timestamp();
                                        List<BigDecimal> prices = data.indicators().quote().get(0).close();
                                        if (prices != null && timestamps.size() == prices.size()) {
                                            for (int i = timestamps.size() - 1; i >= 0; i--) {
                                                if (timestamps.get(i) != null && prices.get(i) != null) {
                                                    LocalDate candleDate = Instant.ofEpochSecond(timestamps.get(i)).atZone(ZoneOffset.UTC).toLocalDate();
                                                    if (!candleDate.isAfter(date)) {
                                                        return new PriceData(asset.ticker(), prices.get(i));
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                return null;
                            });
                })
                .filter(Objects::nonNull)
                .onErrorResume(e -> {
                    logger.error("Erro ao buscar dados do gráfico histórico para {}: {}", asset.ticker(), e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<String> findCanonicalTicker(AssetToFetch asset) {
        final String searchTerm = asset.ticker();

        // Lógica específica para Cripto
        if (AssetType.CRYPTO.equals(asset.assetType())) {
            return searchAndFilterYahooAPI(searchTerm, "CRYPTOCURRENCY");
        }

        // Lógica existente para Ações/ETFs
        if (!searchTerm.contains(" ") && searchTerm.length() < 10) {
            if (asset.market() == Market.B3 && !searchTerm.contains(".")) {
                return Mono.just(searchTerm + ".SA");
            }
            return Mono.just(searchTerm);
        }

        return searchAndFilterYahooAPI(searchTerm, "EQUITY", "ETF", "INDEX");
    }

    private Mono<String> searchAndFilterYahooAPI(String searchTerm, String... desiredQuoteTypes) {
        logger.info("Buscando ticker canônico para '{}', tipos desejados: {}", searchTerm, Arrays.toString(desiredQuoteTypes));

        return this.webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/finance/search").queryParam("q", searchTerm).build())
                .retrieve()
                .bodyToMono(YahooSearchResponseDto.class)
                .flatMap(response -> {
                    if (response.quotes() == null || response.quotes().isEmpty()) {
                        logger.warn("Nenhum resultado encontrado na API de busca do Yahoo para o termo: {}", searchTerm);
                        return Mono.empty();
                    }

                    List<String> desiredTypesList = Arrays.asList(desiredQuoteTypes);

                    Optional<String> foundTicker = response.quotes().stream()
                            .filter(q -> desiredTypesList.contains(q.quoteType().toUpperCase()))
                            .findFirst()
                            .map(YahooQuoteDto::symbol);

                    String canonicalTicker = foundTicker.orElse(response.quotes().get(0).symbol());

                    logger.info("Busca via API encontrou o ticker canônico: {} para o termo '{}'", canonicalTicker, searchTerm);
                    return Mono.just(canonicalTicker);
                })
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Flux<PriceData> fetchPrices(List<AssetToFetch> assetsToFetch) {
        if (assetsToFetch.isEmpty()) return Flux.empty();
        return Flux.fromIterable(assetsToFetch)
                .flatMap(this::fetchSingleStockPrice);
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
                    } else {
                        logger.warn("Não foi possível obter a taxa de câmbio do Yahoo. Usando o valor anterior/padrão: {}", this.usdToBrlRate);
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
                    logger.error("Erro na API de busca do Yahoo para o termo '{}': {}", term, e.getMessage());
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

    private Mono<PriceData> fetchSingleStockPrice(AssetToFetch asset) {
        // Redireciona para o método específico baseado no tipo de ativo
        if (AssetType.CRYPTO.equals(asset.assetType())) {
            return fetchCryptocurrencyPrice(asset);
        } else {
            return fetchStockOrEtfPrice(asset);
        }
    }

    /**
     * Método específico para buscar preços de ações e ETFs via scraping do Yahoo Finance
     */
    private Mono<PriceData> fetchStockOrEtfPrice(AssetToFetch asset) {
        // Para nomes completos ou tickers muito longos, usa busca primeiro
        if (asset.ticker().contains(" ") || asset.ticker().length() > 10) {
            logger.info("Ticker '{}' parece ser nome completo. Usando busca para encontrar ticker correto.", asset.ticker());
            return fetchStockPriceViaSearch(asset);
        }

        // Para casos simples (ações B3 e US com tickers conhecidos)
        String tickerForApi = asset.ticker();
        if (asset.market() == Market.B3 && !tickerForApi.contains(".")) {
            tickerForApi += ".SA";
        }

        String url = BASE_URL + tickerForApi;

        return Mono.fromCallable(() -> {
                    logger.debug("Tentativa de scraping direto para Stock/ETF na URL: {}", url);
                    Document doc = Jsoup.connect(url)
                            .userAgent(USER_AGENT)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .get();

                    return extractPriceFromDocument(doc, asset.ticker());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .filter(Objects::nonNull)
                .doOnError(error -> logger.error("Erro na tentativa de busca direta para Stock/ETF {}: {}", asset.ticker(), error.getMessage()))
                .onErrorResume(e -> {
                    // Fallback para busca se o scraping direto falhar
                    if (e instanceof org.jsoup.HttpStatusException hse && hse.getStatusCode() == 404) {
                        logger.info("URL direta falhou com 404 para {}, tentando via busca...", asset.ticker());
                        return fetchStockPriceViaSearch(asset);
                    }
                    return Mono.empty();
                });
    }

    /**
     * Método específico para buscar preços de criptomoedas usando exclusivamente APIs do Yahoo Finance
     * Evita scraping que causa erro 404 para criptos
     */
    private Mono<PriceData> fetchCryptocurrencyPrice(AssetToFetch asset) {
        logger.info("Buscando preço de criptomoeda para: {} via APIs do Yahoo Finance", asset.ticker());

        // Primeiro tenta encontrar o ticker canônico via busca
        return findCanonicalCryptoTicker(asset.ticker())
                .flatMap(canonicalTicker -> {
                    logger.info("Ticker canônico encontrado para crypto {}: {}", asset.ticker(), canonicalTicker);

                    // Usa a API de chart para obter o preço atual (mais confiável que scraping)
                    return webClient.get()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/v8/finance/chart/" + canonicalTicker)
                                    .queryParam("range", "1d")
                                    .queryParam("interval", "1m")
                                    .build())
                            .retrieve()
                            .bodyToMono(YahooChartResponseDto.class)
                            .map(response -> extractCurrentPriceFromChart(response, asset.ticker()))
                            .filter(Objects::nonNull)
                            .filter(Objects::nonNull);
                })
                .onErrorResume(e -> {
                    logger.error("Erro ao buscar preço da criptomoeda {} via API: {}", asset.ticker(), e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Busca específica para encontrar ticker canônico de criptomoedas
     */
    private Mono<String> findCanonicalCryptoTicker(String searchTerm) {
        logger.info("Buscando ticker canônico para criptomoeda: '{}'", searchTerm);

        return this.webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/finance/search").queryParam("q", searchTerm).build())
                .retrieve()
                .bodyToMono(YahooSearchResponseDto.class)
                .flatMap(response -> {
                    if (response.quotes() == null || response.quotes().isEmpty()) {
                        logger.warn("Nenhum resultado encontrado na busca do Yahoo para a criptomoeda: {}", searchTerm);
                        return Mono.empty();
                    }

                    // Procura especificamente por criptomoedas
                    Optional<String> cryptoTicker = response.quotes().stream()
                            .filter(q -> "CRYPTOCURRENCY".equalsIgnoreCase(q.quoteType()))
                            .findFirst()
                            .map(YahooQuoteDto::symbol);

                    if (cryptoTicker.isPresent()) {
                        logger.info("Ticker canônico encontrado para crypto '{}': {}", searchTerm, cryptoTicker.get());
                        return Mono.just(cryptoTicker.get());
                    } else {
                        logger.warn("Nenhuma criptomoeda encontrada na busca para: {}", searchTerm);
                        return Mono.empty();
                    }
                })
                .onErrorResume(e -> {
                    logger.error("Erro na busca de ticker canônico para crypto '{}': {}", searchTerm, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Extrai o preço atual dos dados do gráfico retornados pela API
     */
    private PriceData extractCurrentPriceFromChart(YahooChartResponseDto response, String originalTicker) {
        if (response == null || response.chart() == null || response.chart().result().isEmpty()) {
            logger.warn("Resposta da API de chart vazia ou inválida para {}", originalTicker);
            return null;
        }

        ChartDataDto data = response.chart().result().get(0);
        if (data == null || data.indicators() == null || data.indicators().quote().isEmpty()) {
            logger.warn("Dados de indicadores ausentes na resposta da API de chart para {}", originalTicker);
            return null;
        }

        List<BigDecimal> prices = data.indicators().quote().get(0).close();
        if (prices == null || prices.isEmpty()) {
            logger.warn("Lista de preços vazia na resposta da API de chart para {}", originalTicker);
            return null;
        }

        // Pega o último preço disponível (mais recente)
        BigDecimal currentPrice = null;
        for (int i = prices.size() - 1; i >= 0; i--) {
            if (prices.get(i) != null) {
                currentPrice = prices.get(i);
                break;
            }
        }

        if (currentPrice != null) {
            logger.info("Preço atual extraído da API de chart para {}: {}", originalTicker, currentPrice);
            return new PriceData(originalTicker, currentPrice);
        } else {
            logger.warn("Não foi possível encontrar um preço válido nos dados de chart para {}", originalTicker);
            return null;
        }
    }

    /**
     * Método para buscar preços de ações/ETFs via API de busca (fallback do scraping)
     */
    private Mono<PriceData> fetchStockPriceViaSearch(AssetToFetch originalAsset) {
        final String searchTerm = originalAsset.ticker();
        logger.info("Stock/ETF '{}' não encontrado diretamente. Tentando via API de busca...", searchTerm);

        return this.webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/finance/search").queryParam("q", searchTerm).build())
                .retrieve()
                .bodyToMono(YahooSearchResponseDto.class)
                .flatMap(response -> {
                    if (response.quotes() == null || response.quotes().isEmpty()) {
                        logger.warn("Nenhum resultado encontrado na busca do Yahoo para o termo: {}", searchTerm);
                        return Mono.empty();
                    }

                    // A busca retorna um Optional<YahooQuoteDto> (uma "caixa")
                    Optional<YahooQuoteDto> bestMatch = response.quotes().stream()
                            .filter(q -> "EQUITY".equalsIgnoreCase(q.quoteType()) || "ETF".equalsIgnoreCase(q.quoteType()))
                            .findFirst()
                            .or(() -> response.quotes().stream().findFirst());

                    // 1. Verificamos se a caixa está vazia. Se estiver, paramos aqui.
                    if (bestMatch.isEmpty()) {
                        logger.warn("Nenhum resultado compatível (EQUITY/ETF) encontrado para '{}'", searchTerm);
                        return Mono.empty();
                    }

                    // 2. Se a caixa não está vazia, pegamos o objeto de dentro dela.
                    YahooQuoteDto foundQuote = bestMatch.get();

                    AssetSearchResultDto searchResult = mapYahooQuoteToSearchResult(foundQuote);
                    if (searchResult == null) return Mono.empty();

                    logger.info("Busca via API encontrou o ticker: {} para o termo '{}'", searchResult.ticker(), searchTerm);

                    AssetToFetch foundAsset = new AssetToFetch(
                            searchResult.ticker(),
                            searchResult.market(),
                            searchResult.assetType()
                    );

                    String url = BASE_URL + foundAsset.ticker();
                    return Mono.fromCallable(() -> {
                                Document doc = Jsoup.connect(url).userAgent(USER_AGENT).get();
                                return extractPriceFromDocument(doc, foundAsset.ticker());
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .filter(Objects::nonNull)
                            .map(priceData -> new PriceData(originalAsset.ticker(), priceData.price())); // Retorna o preço na moeda original
                });
    }
}