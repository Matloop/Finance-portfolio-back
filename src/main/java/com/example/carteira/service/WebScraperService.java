package com.example.carteira.service;

import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

@Service
@Primary
public class WebScraperService implements MarketDataProvider{
    private static final Logger logger = LoggerFactory.getLogger(WebScraperService.class);
    private static final String BASE_URL = "https://finance.yahoo.com/quote/";
    private final ExchangeRateService exchangeRateService;
    private BigDecimal usdToBrlRate = BigDecimal.ONE;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
            " (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36";

    public WebScraperService(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    @Override
    public Flux<PriceData> fetchPrices(List<AssetToFetch> assetsToFetch) {
        if(assetsToFetch.isEmpty()) return Flux.empty();
        return Flux.fromIterable(assetsToFetch)
                .flatMap(this::fetchSingleStockPrice);
    }

    @Override
    public boolean supports(AssetType assetType) {
        return assetType == AssetType.STOCK || assetType == AssetType.ETF;
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

    private Mono<PriceData> fetchSingleStockPrice(AssetToFetch asset) {
        // Usando o nome da classe do seu log.
        final Logger logger = LoggerFactory.getLogger(WebScraperService.class);

        String tickerForApi = (asset.market() == Market.B3) ? asset.ticker() + ".SA" : asset.ticker();
        String url = BASE_URL + tickerForApi;

        return Mono.fromCallable(() -> {
                    try {
                        logger.debug("Scraping URL: {}", url);

                        Document doc = Jsoup.connect(url)
                                .userAgent(USER_AGENT)
                                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
                                .header("Accept-Language", "en-US,en;q=0.9")
                                .get();

                        // CORREÇÃO FINAL: Usando o seletor que mira o data-testid do span.
                        Element priceElement = doc.selectFirst("[data-testid=\"quote-hdr\"] [data-testid=\"qsp-price\"]");

                        if (priceElement != null) {
                            // Como é um <span>, usamos .text() para pegar o conteúdo.
                            String priceText = priceElement.text();

                            // Lidamos com a possibilidade de vírgula como separador decimal.
                            priceText = priceText.replace(",", ".");

                            BigDecimal price = new BigDecimal(priceText);
                            return new PriceData(asset.ticker(), price);
                        } else {
                            logger.warn("Elemento de preço não encontrado para o ticker: {} na URL: {}. A página pode não existir (404) ou o HTML mudou.", asset.ticker(), url);
                            return null;
                        }

                    } catch (org.jsoup.HttpStatusException e) {
                        logger.warn("Falha ao buscar URL para {}: Status={}, URL=[{}]", asset.ticker(), e.getStatusCode(), url);
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        throw new RuntimeException("Falha de I/O ao fazer scraping para " + asset.ticker() + ": " + e.getMessage(), e);
                    } catch (NumberFormatException e) {
                        throw new RuntimeException("Falha ao parsear o preço de '" + asset.ticker() + "': " + e.getMessage(), e);
                    }
                })
                .filter(Objects::nonNull)
                .map(priceData -> {
                    if (asset.market() == Market.US) {
                        BigDecimal priceInUsd = priceData.price();
                        if (this.usdToBrlRate.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal priceInBrl = priceInUsd.multiply(this.usdToBrlRate)
                                    .setScale(2, RoundingMode.HALF_UP);
                            logger.info("Convertendo {}: ${} -> R$ {}", asset.ticker(), priceInUsd, priceInBrl);
                            return new PriceData(asset.ticker(), priceInBrl); // Retorna novo DTO com preço em BRL
                        } else {
                            logger.error("Taxa de câmbio USD/BRL inválida ({}). Não foi possível converter o preço de {}.", this.usdToBrlRate, asset.ticker());
                        }
                    }
                    return priceData;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> logger.error("Erro no fluxo reativo para WebScraperService: {}", error.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

}
