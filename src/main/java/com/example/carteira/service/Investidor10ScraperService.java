package com.example.carteira.service;

import com.example.carteira.model.dtos.AssetSearchResultDto;
import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Provedor de dados de mercado que extrai informações do site Investidor10.
 * Focado em ativos da B3 (Ações, FIIs, ETFs).
 * Utilizado como uma fonte de dados secundária (fallback), principalmente para dados históricos anuais.
 */
@Service
public class Investidor10ScraperService implements MarketDataProvider {

    private static final Logger logger = LoggerFactory.getLogger(Investidor10ScraperService.class);
    private static final String BASE_URL_ACOES = "https://investidor10.com.br/acoes/";
    private static final String BASE_URL_FIIS = "https://investidor10.com.br/fiis/";
    private static final String BASE_URL_ETFS = "https://investidor10.com.br/etfs/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36";
    private static final int MAX_CONCURRENT_REQUESTS = 5; // Limite para não sobrecarregar o site

    @Override
    public boolean supports(AssetType assetType) {
        // Suporta Ações, FIIs e ETFs da B3
        return assetType == AssetType.STOCK || assetType == AssetType.ETF || assetType == AssetType.FUND;
    }

    @Override
    public Mono<Void> initialize() {
        logger.info("✅ Investidor10ScraperService inicializado.");
        return Mono.empty();
    }

    @Override
    public Flux<AssetSearchResultDto> search(String term) {
        // A busca é melhor tratada por provedores baseados em API, como o YahooFinance.
        // Este provedor foca em obter dados de ativos já conhecidos.
        return Flux.empty();
    }

    /**
     * Busca os preços atuais para uma lista de ativos.
     * Este método é menos eficiente que uma API, pois realiza um scraping para cada ativo.
     * É usado como fallback pelo MarketDataService.
     */
    @Override
    public Flux<PriceData> fetchPrices(List<AssetToFetch> assetsToFetch) {
        return Flux.fromIterable(assetsToFetch)
                .parallel(MAX_CONCURRENT_REQUESTS)
                .runOn(Schedulers.boundedElastic())
                .flatMap(this::fetchSingleCurrentPrice)
                .sequential();
    }

    /**
     * Busca o preço histórico para um único ativo em uma data específica.
     * A granularidade do Investidor10 é anual, então ele buscará o "Valor Patrimonial por Ação (VPA)"
     * do ano correspondente à data solicitada, que serve como um proxy do valor do ativo.
     */
    @Override
    public Mono<PriceData> fetchHistoricalPrice(AssetToFetch asset, LocalDate date) {
        if (!supports(asset.assetType()) || asset.market() != Market.B3) {
            return Mono.empty();
        }

        String url = buildUrlForAsset(asset);
        logger.debug("[Histórico I10] Scraping para {} na URL: {}", asset.ticker(), url);

        return Mono.fromCallable(() -> {
                    Document doc = Jsoup.connect(url).userAgent(USER_AGENT).get();
                    return extractVpaForYear(doc, asset.ticker(), date.getYear());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(10))
                .filter(Objects::nonNull)
                .doOnError(e -> logger.warn("[Histórico I10] Falha no scraping para {}: {}", asset.ticker(), e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Método auxiliar para buscar o preço atual de um único ativo.
     */
    private Mono<PriceData> fetchSingleCurrentPrice(AssetToFetch asset) {
        if (!supports(asset.assetType()) || asset.market() != Market.B3) {
            return Mono.empty();
        }

        String url = buildUrlForAsset(asset);
        logger.debug("[Atual I10] Scraping para {} na URL: {}", asset.ticker(), url);

        return Mono.fromCallable(() -> {
                    Document doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(5000).get();
                    return extractCurrentPriceFromDocument(doc, asset.ticker());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(7))
                .filter(Objects::nonNull)
                .doOnError(e -> logger.warn("[Atual I10] Falha no scraping para {}: {}", asset.ticker(), e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    private String buildUrlForAsset(AssetToFetch asset) {
        String ticker = asset.ticker().toUpperCase();
        switch (asset.assetType()) {
            case STOCK:
                return BASE_URL_ACOES + ticker;
            case FUND:
                return BASE_URL_FIIS + ticker;
            case ETF:
                return BASE_URL_ETFS + ticker;
            default:
                throw new IllegalArgumentException("Tipo de ativo não suportado por este serviço: " + asset.assetType());
        }
    }

    private PriceData extractCurrentPriceFromDocument(Document doc, String originalTicker) {
        try {
            // Seletor para o card de cotação
            Element priceCard = doc.selectFirst("div._card.cotacao");
            if (priceCard != null) {
                Element priceElement = priceCard.selectFirst("span.value");
                if (priceElement != null) {
                    String priceText = priceElement.text()
                            .replace("R$", "")
                            .replace(".", "")
                            .replace(",", ".")
                            .trim();
                    BigDecimal price = new BigDecimal(priceText);
                    logger.info("✅ [Atual I10] Preço encontrado para {}: {}", originalTicker, price);
                    return new PriceData(originalTicker, price);
                }
            }
        } catch (Exception e) {
            logger.error("❌ [Atual I10] Erro ao extrair preço atual para {}: {}", originalTicker, e.getMessage());
        }
        logger.warn("⚠️ [Atual I10] Elemento de preço atual não encontrado para {}", originalTicker);
        return null;
    }


    private PriceData extractVpaForYear(Document doc, String originalTicker, int year) {
        try {
            Element historyTable = doc.selectFirst("table#table-indicators-history");
            if (historyTable == null) {
                logger.warn("[Histórico I10] Tabela de histórico de indicadores não encontrada para {}", originalTicker);
                return null;
            }

            Elements headerCells = historyTable.select("thead th.year");
            Elements vpaRowCells = historyTable.select("tbody tr:has(td:contains(VPA)) td");

            if (headerCells.isEmpty() || vpaRowCells.isEmpty()) {
                logger.warn("[Histórico I10] Estrutura da tabela de histórico inesperada para {}", originalTicker);
                return null;
            }

            int targetColumnIndex = -1;
            // Começa em 1 para pular a primeira coluna de nome do indicador
            for (int i = 0; i < headerCells.size(); i++) {
                if (headerCells.get(i).text().equals(String.valueOf(year))) {
                    targetColumnIndex = i + 1; // +1 porque a primeira célula da linha é o nome 'VPA'
                    break;
                }
            }

            if (targetColumnIndex != -1 && vpaRowCells.size() > targetColumnIndex) {
                String vpaText = vpaRowCells.get(targetColumnIndex).text()
                        .replace(".", "")
                        .replace(",", ".")
                        .trim();
                BigDecimal vpa = new BigDecimal(vpaText);
                logger.info("✅ [Histórico I10] VPA de {} para {} encontrado: {}", year, originalTicker, vpa);
                return new PriceData(originalTicker, vpa);
            } else {
                logger.warn("[Histórico I10] Ano {} não encontrado na tabela de histórico para {}", year, originalTicker);
            }

        } catch (Exception e) {
            logger.error("❌ [Histórico I10] Erro ao extrair VPA para {} no ano {}: {}", originalTicker, year, e.getMessage());
        }
        return null;
    }
}
