package com.example.carteira.service.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Serviço especializado para buscar a taxa de câmbio USD/BRL
 * via web scraping no Yahoo Finance.
 */
@Service
public class ExchangeRateService {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeRateService.class);
    private static final String EXCHANGE_RATE_URL = "https://finance.yahoo.com/quote/USDBRL=X/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36";

    /**
     * Busca a taxa de câmbio atual USD -> BRL.
     * @return um Mono contendo a taxa como BigDecimal, ou Mono.empty() em caso de falha.
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
                            BigDecimal rate = new BigDecimal(priceText);
                            logger.info("Taxa de câmbio USD -> BRL obtida com sucesso: {}", rate);
                            return rate;
                        } else {
                            logger.warn("Elemento da taxa de câmbio não encontrado. O HTML do Yahoo pode ter mudado.");
                            return null; // Será filtrado
                        }
                    } catch (Exception e) {
                        // Captura qualquer exceção (IOException, NumberFormatException, etc.)
                        throw new RuntimeException("Falha ao fazer scraping da taxa de câmbio: " + e.getMessage(), e);
                    }
                })
                .filter(rate -> rate != null)
                .subscribeOn(Schedulers.boundedElastic()) // Executa a tarefa bloqueante em uma thread separada
                .doOnError(error -> logger.error(error.getMessage()))
                .onErrorResume(e -> Mono.empty()); // Em caso de erro, retorna um Mono vazio para não quebrar o fluxo
    }
}