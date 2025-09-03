package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.repository.TransactionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Serviço Orquestrador de Dados de Mercado.
 *
 * Responsabilidades:
 * 1. Gerenciar um cache central de preços de ativos (`priceCache`).
 * 2. Coordenar uma lista de provedores de dados (`MarketDataProvider`).
 * 3. Inicializar todos os provedores na inicialização da aplicação.
 * 4. Agendar uma tarefa recorrente para atualizar os preços de todos os ativos da carteira.
 * 5. Delegar a busca de dados ao provedor correto para cada tipo de ativo.
 */
@Service
public class MarketDataService {

    private static final Logger logger = LoggerFactory.getLogger(MarketDataService.class);

    private final TransactionRepository transactionRepository;
    private final List<MarketDataProvider> providers;
    private final Map<String, BigDecimal> priceCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * Construtor que injeta todas as implementações de MarketDataProvider
     * encontradas pelo Spring, além do repositório de transações.
     */
    public MarketDataService(TransactionRepository transactionRepository, List<MarketDataProvider> providers) {
        this.transactionRepository = transactionRepository;
        this.providers = providers;
        logger.info("MarketDataService carregado com {} provedor(es) de dados.", providers.size());
    }

    /**
     * Inicializa o serviço após a construção.
     * Primeiro, inicializa todos os provedores de dados e, em caso de sucesso,
     * agenda a atualização recorrente de preços.
     */
    // DENTRO DE MarketDataService.java

    @PostConstruct
    private void initialize() {
        logger.info("Inicializando todos os provedores de dados...");

        Flux.fromIterable(providers)
                .flatMap(MarketDataProvider::initialize)
                .then() // Aguarda a conclusão de todas as inicializações
                .doOnSuccess(aVoid -> {
                    logger.info("Todos os provedores foram inicializados com sucesso!");
                    logger.info("A primeira busca de dados de preços ocorrerá em 10 segundos.");

                    // CORREÇÃO: Mova o agendador para DENTRO do doOnSuccess.
                    // Isso garante que ele só será agendado DEPOIS que a inicialização (o .then())
                    // for concluída com sucesso.
                    scheduler.scheduleAtFixedRate(this::refreshAllMarketData, 10, 300, TimeUnit.SECONDS);
                })
                .doOnError(error -> {
                    logger.error("Falha ao inicializar um ou mais provedores! A atualização de preços não será agendada.", error);
                    // DECISÃO DE DESIGN: Se a inicialização falhar, não agendamos a tarefa para evitar
                    // executar com dados potencialmente incorretos.
                })
                .subscribe();
    }

    /**
     * Retorna o preço de um ativo específico do cache.
     *
     * @param ticker O ticker do ativo (ex: "PETR4").
     * @return O preço em BigDecimal, ou BigDecimal.ZERO se não for encontrado.
     */
    public BigDecimal getPrice(String ticker) {
        return priceCache.getOrDefault(ticker.toUpperCase(), BigDecimal.ZERO);
    }

    /**
     * Retorna uma visão não modificável de todo o cache de preços.
     */
    public Map<String, BigDecimal> getAllPrices() {
        return Collections.unmodifiableMap(priceCache);
    }

    /**
     * Dispara a atualização de preços para todos os ativos presentes no banco de dados.
     * Este é o método chamado pelo agendador.
     */
    public void refreshAllMarketData() {
        logger.info("ATUALIZAÇÃO GERAL: Iniciando busca de dados para todos os ativos da carteira...");
        List<Transaction> allTransactions = transactionRepository.findAll();

        if (allTransactions.isEmpty()) {
            logger.info("Nenhuma transação na carteira para atualizar.");
            return;
        }

        // Agrupa todas as transações por tipo de ativo (STOCK, CRYPTO, etc.)
        Map<AssetType, List<Transaction>> groupedByType = allTransactions.stream()
                .collect(Collectors.groupingBy(Transaction::getAssetType));

        // Para cada grupo, encontra o provedor correto e delega a busca de dados
        groupedByType.forEach((assetType, transactions) -> {
            // 1. Encontrar o provedor certo
            Optional<MarketDataProvider> providerOptional = findProviderFor(assetType);

            providerOptional.ifPresentOrElse(
                    provider -> {
                        // 2. Extrair os ativos únicos para não buscar o mesmo ticker várias vezes
                        Set<AssetToFetch> uniqueAssets = transactions.stream()
                                .map(t -> new AssetToFetch(t.getTicker().toUpperCase(), t.getMarket()))
                                .collect(Collectors.toSet());

                        logger.info("Usando o provedor '{}' para buscar {} ativo(s) do tipo {}",
                                provider.getClass().getSimpleName(), uniqueAssets.size(), assetType);

                        // 3. Delegar a chamada e se inscrever no resultado
                        provider.fetchPrices(new ArrayList<>(uniqueAssets))
                                .subscribe(
                                        this::updateCache, // onNext: chama o método para atualizar o cache
                                        error -> logger.error("Erro no fluxo do provedor {}: {}", provider.getClass().getSimpleName(), error.getMessage()), // onError
                                        () -> logger.info("Fluxo de preços para o tipo {} concluído.", assetType) // onComplete
                                );
                    },
                    () -> logger.warn("Nenhum provedor de dados encontrado para o tipo de ativo: {}", assetType)
            );
        });
    }

    /**
     * Atualiza o cache central com os dados de preço recebidos de um provedor.
     * @param priceData O DTO contendo o ticker e o preço.
     */
    private void updateCache(PriceData priceData) {
        priceCache.put(priceData.ticker().toUpperCase(), priceData.price());
        logger.info("Cache atualizado: {} = {}", priceData.ticker().toUpperCase(), priceData.price());
    }

    /**
     * Encontra o primeiro provedor na lista que suporta o tipo de ativo fornecido.
     * @param assetType O tipo de ativo (STOCK, CRYPTO, etc.).
     * @return um Optional contendo o provedor, ou um Optional vazio se nenhum for encontrado.
     */
    private Optional<MarketDataProvider> findProviderFor(AssetType assetType) {
        return providers.stream()
                .filter(p -> p.supports(assetType))
                .findFirst();
    }

    public void updatePricesForTransactions(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            logger.warn("A atualização de preços sob demanda foi chamada com uma lista de transações vazia.");
            return;
        }
        logger.info("ATUALIZAÇÃO SOB DEMANDA: Iniciando busca para {} transação(ões).", transactions.size());

        // A lógica é idêntica à de refreshAllMarketData, mas opera sobre a lista fornecida
        // em vez de buscar todas as transações do repositório.
        Map<AssetType, List<Transaction>> groupedByType = transactions.stream()
                .collect(Collectors.groupingBy(Transaction::getAssetType));

        groupedByType.forEach((assetType, transactionList) -> {
            Optional<MarketDataProvider> providerOptional = findProviderFor(assetType);

            providerOptional.ifPresentOrElse(
                    provider -> {
                        Set<AssetToFetch> uniqueAssets = transactionList.stream()
                                .map(t -> new AssetToFetch(t.getTicker().toUpperCase(), t.getMarket()))
                                .collect(Collectors.toSet());

                        logger.info("Usando o provedor '{}' para buscar {} ativo(s) do tipo {} (sob demanda)",
                                provider.getClass().getSimpleName(), uniqueAssets.size(), assetType);

                        provider.fetchPrices(new ArrayList<>(uniqueAssets))
                                .subscribe(
                                        this::updateCache,
                                        error -> logger.error("Erro no fluxo do provedor {}: {}", provider.getClass().getSimpleName(), error.getMessage())
                                );
                    },
                    () -> logger.warn("Nenhum provedor de dados encontrado para o tipo de ativo: {}", assetType)
            );
        });
    }
}