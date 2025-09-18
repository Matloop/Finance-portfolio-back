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
            Element priceElement = doc.selectFirst("[data-testid=\"quote-hdr\"] [data-testid=\"qsp-price\"]");

            if (priceElement != null) {
                String priceText = priceElement.text(); // Ex: "116,487.28" ou "116.487,28"

                // ***** LÓGICA DE PARSING ROBUSTA APLICADA AQUI *****

                // 1. Remove os pontos, que são usados como separador de milhar no formato brasileiro/europeu.
                // "116.487,28" -> "116487,28"
                priceText = priceText.replace(".", "");

                // 2. Substitui a vírgula (que agora é garantidamente o separador decimal) por um ponto.
                // "116487,28" -> "116487.28"
                priceText = priceText.replace(",", ".");

                // Se o formato original fosse americano ("116,487.28"), o passo 1 não faria nada.
                // O passo 2 transformaria em "116487.28", o que também está correto.

                try {
                    BigDecimal price = new BigDecimal(priceText);
                    logger.info("Preço extraído com sucesso para {}: {}", originalTicker, price);
                    return new PriceData(originalTicker, price);
                } catch (NumberFormatException e) {
                    logger.error("Falha ao converter o texto '{}' para BigDecimal para o ticker {}", priceText, originalTicker);
                    throw e; // Lança a exceção para que o fluxo reativo a capture
                }

            } else {
                logger.warn("Elemento de preço não encontrado no documento HTML para o ticker: {}. A estrutura da página pode ter mudado.", originalTicker);
                return null;
            }
        }

        private Mono<PriceData> fetchPriceViaSearch(AssetToFetch originalAsset) {
            final String searchTerm = originalAsset.ticker();
            logger.info("Ticker '{}' não encontrado diretamente. Tentando via API de busca...", searchTerm);

            return this.webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/finance/search").queryParam("q", searchTerm).build())
                    .retrieve()
                    .bodyToMono(YahooSearchResponseDto.class)
                    .flatMap(response -> {
                        if (response.quotes() == null || response.quotes().isEmpty()) {
                            logger.warn("Nenhum resultado encontrado na busca do Yahoo para o termo: {}", searchTerm);
                            return Mono.empty();
                        }

                        // 1. Encontra o resultado de busca mais relevante
                        Optional<YahooQuoteDto> bestMatch = response.quotes().stream()
                                .filter(q -> q.quoteType().equalsIgnoreCase(originalAsset.assetType().name()))
                                .findFirst()
                                .or(() -> response.quotes().stream().findFirst());

                        if (bestMatch.isEmpty()) {
                            return Mono.empty();
                        }

                        // 2. Converte o resultado para nosso DTO padronizado para obter os detalhes
                        AssetSearchResultDto searchResult = mapYahooQuoteToSearchResult(bestMatch.get());
                        if (searchResult == null) return Mono.empty();

                        logger.info("Busca via API encontrou o ticker: {} para o termo '{}'", searchResult.ticker(), searchTerm);

                        // 3. Cria um NOVO AssetToFetch com os dados CORRETOS encontrados na busca
                        AssetToFetch foundAsset = new AssetToFetch(
                                searchResult.ticker(),
                                searchResult.market(),
                                searchResult.assetType()
                        );

                        // 4. CHAMA A LÓGICA DE SCRAPING NOVAMENTE, mas com o ticker e tipo corretos
                        // Usamos fetchSingleStockPrice SEM a lógica de fallback para evitar loops infinitos.
                        String url = BASE_URL + foundAsset.ticker();
                        return Mono.fromCallable(() -> {
                                    Document doc = Jsoup.connect(url).userAgent(USER_AGENT).get();
                                    return extractPriceFromDocument(doc, foundAsset.ticker());
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .filter(Objects::nonNull)
                                // 5. CRUCIAL: Retorna o DTO com o ticker ORIGINAL do usuário
                                .map(priceData -> new PriceData(originalAsset.ticker(), priceData.price()));
                    });
        }

        @Override
        public Mono<PriceData> fetchHistoricalPrice(AssetToFetch asset, LocalDate date) {
            // A lógica existente já é genérica e funcionará com a correção no findCanonicalTicker.
            return findCanonicalTicker(asset)
                    .flatMap(canonicalTicker -> {
                        logger.info("Buscando preço histórico para {} ({}) na data {}", asset.ticker(), canonicalTicker, date);
                        return webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/v8/finance/chart/" + canonicalTicker)
                                        .queryParam("range", "5y") // Range longo para garantir a data
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

            // --- CORREÇÃO 1: Lógica específica para Cripto ---
            if (AssetType.CRYPTO.equals(asset.assetType())) {
                // Para cripto, sempre usamos a busca para encontrar o ticker "-USD"
                return searchAndFilterYahooAPI(searchTerm, "CRYPTOCURRENCY");
            }

            // --- Lógica existente para Ações/ETFs ---
            if (!searchTerm.contains(" ") && searchTerm.length() < 10) {
                if (asset.market() == Market.B3 && !searchTerm.contains(".")) {
                    return Mono.just(searchTerm + ".SA");
                }
                return Mono.just(searchTerm);
            }

            // Para nomes completos (ex: "Petrobras"), a busca genérica é acionada
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

                        // Converte a lista de tipos desejados para facilitar a busca
                        List<String> desiredTypesList = Arrays.asList(desiredQuoteTypes);

                        // Encontra o primeiro resultado que corresponda a um dos tipos desejados
                        Optional<String> foundTicker = response.quotes().stream()
                                .filter(q -> desiredTypesList.contains(q.quoteType().toUpperCase()))
                                .findFirst()
                                .map(YahooQuoteDto::symbol);

                        // Se não encontrar um tipo exato, pega o primeiro resultado como fallback
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
            String cleanedTerm = term.replace("&", ""); // Limpeza básica para evitar problemas de URL

            return webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/finance/search").queryParam("q", cleanedTerm).build())
                    .retrieve()
                    .bodyToMono(YahooSearchResponseDto.class)
                    .flatMapMany(response -> {
                        if (response == null || response.quotes() == null || response.quotes().isEmpty()) {
                            return Flux.empty();
                        }

                        List<AssetSearchResultDto> results = response.quotes().stream()
                                .map(this::mapYahooQuoteToSearchResult) // Mapeia cada resultado para nosso DTO
                                .filter(Objects::nonNull) // Filtra qualquer resultado que não conseguimos mapear
                                .limit(10) // Limita o número de resultados
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

            // Acessar os campos do record com a sintaxe correta (quote.quoteType(), quote.exchange())
            switch (quote.quoteType().toUpperCase()) {
                case "EQUITY":
                    assetType = AssetType.STOCK;
                    // CORREÇÃO: Usa List.of(...) para criar uma coleção para a verificação.
                    if ("SAO".equals(quote.exchange())) market = Market.B3;
                    else if (List.of("NMS", "NYQ").contains(quote.exchange())) market = Market.US;
                    break;

                case "ETF":
                    assetType = AssetType.ETF;
                    // CORREÇÃO: Usa List.of(...) para criar uma coleção para a verificação.
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
            String tickerForApi = asset.ticker();

            // Adiciona o sufixo .SA apenas se for mercado B3 E não tiver um ponto (para evitar B3SA3.SA.SA)
            if (asset.market() == Market.B3 && !tickerForApi.contains(".")) {
                tickerForApi += ".SA";
            }
            String url = BASE_URL + tickerForApi;

            return Mono.fromCallable(() -> {
                        logger.debug("Tentativa de scraping direto na URL: {}", url);
                        Document doc = Jsoup.connect(url)
                                .userAgent(USER_AGENT)
                                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
                                .header("Accept-Language", "en-US,en;q=0.9")
                                .get();

                        // Delega a lógica de extração para o método auxiliar
                        return extractPriceFromDocument(doc, asset.ticker());
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(priceData -> { // A conversão de moeda continua aqui
                        if (asset.market() == Market.US && priceData != null) {
                            BigDecimal priceInUsd = priceData.price();
                            if (this.usdToBrlRate.compareTo(BigDecimal.ZERO) > 0) {
                                BigDecimal priceInBrl = priceInUsd.multiply(this.usdToBrlRate)
                                        .setScale(2, RoundingMode.HALF_UP);
                                logger.info("Convertendo {}: ${} -> R$ {}", asset.ticker(), priceInUsd, priceInBrl);
                                return new PriceData(asset.ticker(), priceInBrl);
                            } else {
                                logger.error("Taxa de câmbio USD/BRL inválida ({}). Não foi possível converter o preço de {}.", this.usdToBrlRate, asset.ticker());
                            }
                        }
                        return priceData;
                    })
                    .filter(Objects::nonNull)
                    .doOnError(error -> logger.error("Erro na tentativa de busca direta para {}: {}", asset.ticker(), error.getMessage()))
                    .onErrorResume(e -> {
                        // Lógica de fallback para a busca
                        if (e instanceof org.jsoup.HttpStatusException hse && hse.getStatusCode() == 404) {
                            return fetchPriceViaSearch(asset);
                        }
                        return Mono.empty();
                    });
        }




    }
