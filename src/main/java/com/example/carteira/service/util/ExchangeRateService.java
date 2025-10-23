// Em service/util/ExchangeRateService.java
package com.example.carteira.service.util;

// --- CORREÇÃO: Imports corretos para os DTOs públicos ---

import com.example.carteira.model.dtos.yahooscraper.ChartDataDto;
import com.example.carteira.model.dtos.yahooscraper.YahooChartResponseDto;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExchangeRateService {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeRateService.class);
    private static final String EXCHANGE_RATE_URL = "https://finance.yahoo.com/quote/USDBRL=X/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36";
    private final Map<LocalDate, BigDecimal> historicalUsdBrlRateCache = new ConcurrentHashMap<>();
    private final WebClient yahooChartWebClient;
    private static final String CURRENT_RATE_CACHE_KEY = "exchange-rate";
    private final RedisTemplate<String, String> redisTemplate;
    private static final String HISTORICAL_VALUES_CACHE_KEY = "historical-values";

    public ExchangeRateService(WebClient.Builder webClientBuilder, RedisTemplate<String, String> redisTemplate) {
        this.yahooChartWebClient = webClientBuilder.baseUrl("https://query1.finance.yahoo.com").build();
        this.redisTemplate = redisTemplate;
    }

    /**
     * Busca a taxa de câmbio atual USD -> BRL via web scraping.
     */
    public Mono<BigDecimal> fetchUsdToBrlRate() {
        return Mono.fromCallable(() -> {
                    try {
                        logger.info("Buscando taxa de câmbio USD -> BRL via web scraping...");
                        Document doc = Jsoup.connect(EXCHANGE_RATE_URL)
                                .userAgent(USER_AGENT)
                                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
                                .header("Accept-Language", "en-US,en;q=0.9")
                                .header("Accept-Encoding", "gzip, deflate, br")
                                .header("Upgrade-Insecure-Requests", "1")
                                .header("Cache-Control", "max-age=0")
                                .get();
                        Element priceElement = doc.selectFirst("[data-testid=\"quote-hdr\"] [data-testid=\"qsp-price\"]");
                        if (priceElement != null) {
                            String priceText = priceElement.text().replace(",", ".");
                            return new BigDecimal(priceText);
                        }
                        return null;
                    } catch (Exception e) {
                        throw new RuntimeException("Falha ao fazer scraping da taxa de câmbio: " + e.getMessage(), e);
                    }
                })
                .filter(Objects::nonNull)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> logger.error("Erro no fluxo de scraping da taxa de câmbio: {}", error.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Busca a taxa de câmbio histórica USD -> BRL para uma data específica.
     */
    public Mono<BigDecimal> fetchHistoricalUsdToBrlRate(LocalDate date) {
        if (historicalUsdBrlRateCache.containsKey(date)) {
            logger.debug("💾 [Câmbio Histórico Cache] Usando preço de câmbio de: {}", date);
            return Mono.just(historicalUsdBrlRateCache.get(date));
        }
        logger.info("Buscando taxa de c�mbio hist�rica para a data {}", date);
        final String ticker = "USDBRL=X";

        return yahooChartWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v8/finance/chart/" + ticker)
                        .queryParam("range", "5y")
                        .queryParam("interval", "1d")
                        .build())
                .retrieve()
                // --- CORREÇÃO: Usa o DTO público e compartilhado ---
                .bodyToMono(YahooChartResponseDto.class)
                .map(response -> {
                    // --- CORREÇÃO: Navega na hierarquia correta dos DTOs ---
                    if (response != null && response.chart() != null && !response.chart().result().isEmpty()) {
                        ChartDataDto data = response.chart().result().get(0);
                        if (data != null && data.timestamp() != null && data.indicators() != null && !data.indicators().quote().isEmpty()) {
                            List<Long> timestamps = data.timestamp();
                            List<BigDecimal> prices = data.indicators().quote().get(0).close();

                            if (prices != null && timestamps.size() == prices.size()) {
                                for (int i = timestamps.size() - 1; i >= 0; i--) {
                                    if (timestamps.get(i) == null || prices.get(i) == null) continue;
                                    LocalDate candleDate = Instant.ofEpochSecond(timestamps.get(i)).atZone(ZoneOffset.UTC).toLocalDate();
                                    if (!candleDate.isAfter(date)) {
                                        return prices.get(i); // Retorna o primeiro preço encontrado
                                    }
                                }
                            }
                        }
                    }
                    logger.warn("Não foi possível encontrar uma taxa de câmbio histórica para a data {}", date);
                    return null;
                })
                .filter(Objects::nonNull)
                .doOnNext(rate -> {
                    historicalUsdBrlRateCache.put(date, rate);
                    logger.debug("✅ [Câmbio Histórico Cache] Taxa para {} adicionada ao cache: {}", date, rate);
                })
                .onErrorResume(e -> {
                    logger.error("Erro ao buscar dados históricos da taxa de câmbio: {}", e.getMessage());
                    return Mono.empty();
                });
    }
}