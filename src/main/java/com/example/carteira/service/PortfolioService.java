package com.example.carteira.service;

import com.example.carteira.model.CashBalance;
import com.example.carteira.model.FixedIncomeAsset;
import com.example.carteira.model.Transaction;
import com.example.carteira.model.User;
import com.example.carteira.model.dtos.*;
import com.example.carteira.model.enums.AssetCategory;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.example.carteira.model.enums.TransactionType;
import com.example.carteira.repository.CashBalanceRepository;
import com.example.carteira.repository.FixedIncomeRepository;
import com.example.carteira.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class PortfolioService {
    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
    private static final Map<String, Predicate<Transaction>> ASSET_TYPE_FILTERS = Map.of(
            "ações",      (Transaction t) -> t.getAssetType() == AssetType.STOCK,
            "etfs",       (Transaction t) -> t.getAssetType() == AssetType.ETF,
            "renda fixa", (Transaction t) -> t.getAssetType().getCategory() == AssetCategory.FIXED_INCOME,
            "cripto",     (Transaction t) -> t.getAssetType().getCategory() == AssetCategory.CRYPTO
    );
    private final TransactionRepository transactionRepository;
    private final PortfolioCalculatorService calculatorService;
    private final DashboardViewService viewService;
    private final FixedIncomeRepository fixedIncomeRepository;
    private final UserAssetPreferenceService userAssetPreferenceService;
    private final CashBalanceRepository cashBalanceRepository;

    public PortfolioService(TransactionRepository transactionRepository,
                            PortfolioCalculatorService calculatorService,
                            DashboardViewService viewService, FixedIncomeRepository fixedIncomeRepository, UserAssetPreferenceService userAssetPreferenceService, CashBalanceRepository cashBalanceRepository) {
        this.transactionRepository = transactionRepository;
        this.calculatorService = calculatorService;
        this.viewService = viewService;
        this.fixedIncomeRepository = fixedIncomeRepository;


        this.userAssetPreferenceService = userAssetPreferenceService;
        this.cashBalanceRepository = cashBalanceRepository;
    }

    private List<Transaction> getFilteredTransactions(List<Transaction> allTransactions, String category, String assetType, String ticker) {
        Stream<Transaction> filteredStream = allTransactions.stream();

        // Lógica para Ticker (continua igual)
        if (ticker != null && !ticker.isBlank() && !"all".equalsIgnoreCase(ticker)) {
            return filteredStream.filter(t -> ticker.equalsIgnoreCase(t.getTicker())).collect(Collectors.toList());
        }

        // Lógica para Categoria Geográfica (continua igual)
        if (category != null && !category.isBlank() && !"all".equalsIgnoreCase(category)) {
            if ("cripto".equalsIgnoreCase(category)) {
                // Se o filtro geográfico já for "cripto", não precisamos do filtro de tipo de ativo.
                filteredStream = filteredStream.filter(t -> t.getAssetType().getCategory() == AssetCategory.CRYPTO);
            } else if ("brasil".equalsIgnoreCase(category)) {
                filteredStream = filteredStream.filter(t -> t.getMarket() == Market.B3 || t.getAssetType().getCategory() == AssetCategory.FIXED_INCOME);
            } else if ("eua".equalsIgnoreCase(category)) {
                filteredStream = filteredStream.filter(t -> t.getMarket() == Market.US);
            }
        }

        if (assetType != null && !assetType.isBlank() && !"all".equalsIgnoreCase(assetType)) {
            // 1. Pega a lógica de filtro correta do mapa.
            Predicate<Transaction> filter = ASSET_TYPE_FILTERS.get(assetType.toLowerCase());

            // 2. Se encontrou uma lógica, aplica ela.
            if (filter != null) {
                filteredStream = filteredStream.filter(filter);
            }
        }

        return filteredStream.collect(Collectors.toList());
    }


    public PortfolioDashboardDto getPortfolioDashboardData(User user) {
        log.info("==> [PORTFOLIO_SERVICE] Iniciando cálculo do dashboard...");
        LocalDate today = LocalDate.now();
        LocalDate twelveMonthsAgo = today.minusMonths(12);

        List<Transaction> allUserAssets = getAllUserAssetsAsTransaction(user);

        log.info("==> [PORTFOLIO_SERVICE] Calculando posições consolidadas...");
        List<AssetPositionDto> allCurrentAssets = calculatorService.calculateConsolidatedPortfolio(allUserAssets, today);
        log.info("==> [PORTFOLIO_SERVICE] Posições calculadas ({} itens): {}", allCurrentAssets.size(), allCurrentAssets.stream().map(a -> a.getTicker() != null ? a.getTicker() : a.getName()).collect(Collectors.toList()));

        Set<String> cashEquivalentIdentifiers = userAssetPreferenceService.getCashEquivalentAssetIdentifiers(user);

        List<CashBalance> cashBalances = cashBalanceRepository.findByUser(user);
        BigDecimal pureCashValue = cashBalances.stream()
                .map(CashBalance::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("==> [PORTFOLIO_SERVICE] Valor de Caixa Puro encontrado: {}", pureCashValue);

        List<AssetPositionDto> finalAssetsForAllocation = new ArrayList<>();
        BigDecimal cashEquivalentValue = BigDecimal.ZERO;

        log.info("==> [PORTFOLIO_SERVICE] Iniciando re-categorização dos ativos...");
        for (AssetPositionDto asset : allCurrentAssets) {
            String identifier = asset.getTicker() != null ? asset.getTicker() : asset.getName();

            if (cashEquivalentIdentifiers.contains(identifier)) {
                // Seu log "!!! ENCONTRADO ATIVO DE CAIXA" já está ótimo aqui.
                System.out.println("!!! ENCONTRADO ATIVO DE CAIXA: " + identifier);
                cashEquivalentValue = cashEquivalentValue.add(asset.getCurrentValue());
            } else {
                finalAssetsForAllocation.add(asset);
            }
        }
        log.info("==> [PORTFOLIO_SERVICE] Valor total dos ATIVOS equivalentes a caixa: {}", cashEquivalentValue);

        BigDecimal totalCashValue = cashEquivalentValue.add(pureCashValue);

        if (totalCashValue.compareTo(BigDecimal.ZERO) > 0) {
            log.info("==> [PORTFOLIO_SERVICE] Valor total de Caixa (puro + equivalentes): {}. Criando ativo virtual...", totalCashValue);
            AssetPositionDto cashPosition = new AssetPositionDto();
            cashPosition.setName("Caixa");
            cashPosition.setDisplayCategory("Caixa");
            cashPosition.setAssetType(AssetType.CASH);
            cashPosition.setCurrentValue(totalCashValue);
            cashPosition.setTotalInvested(totalCashValue);
            cashPosition.setTotalQuantity(BigDecimal.ONE);
            cashPosition.setAveragePrice(totalCashValue);
            cashPosition.setProfitability(BigDecimal.ZERO);
            cashPosition.setProfitOrLoss(BigDecimal.ZERO);

            finalAssetsForAllocation.add(cashPosition);
        }
        log.info("==> [PORTFOLIO_SERVICE] Lista final para alocação ({} itens): {}", finalAssetsForAllocation.size(), finalAssetsForAllocation.stream().map(a -> a.getTicker() != null ? a.getTicker() : a.getName()).collect(Collectors.toList()));


        BigDecimal totalHeritage = finalAssetsForAllocation.stream()
                .map(AssetPositionDto::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalInvested = finalAssetsForAllocation.stream()
                .map(AssetPositionDto::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<AssetPositionDto> assetsTwelveMonthsAgo = calculatorService.calculateConsolidatedPortfolio(allUserAssets, twelveMonthsAgo);

        BigDecimal heritageTwelveMonthsAgo = assetsTwelveMonthsAgo.stream()
                .map(AssetPositionDto::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal profitability = totalHeritage.compareTo(totalInvested) != 0 && totalInvested.compareTo(BigDecimal.ZERO) != 0
                ? (totalHeritage.subtract(totalInvested)).divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        BigDecimal yearlyProfitability;
        if (heritageTwelveMonthsAgo.compareTo(BigDecimal.ZERO) > 0) {
            yearlyProfitability = totalHeritage.subtract(heritageTwelveMonthsAgo)
                    .divide(heritageTwelveMonthsAgo, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        } else {
            yearlyProfitability = BigDecimal.ZERO;
        }

        PortfolioSummaryDto summary = new PortfolioSummaryDto(totalHeritage, totalInvested, profitability, yearlyProfitability);
        log.info("==> [PORTFOLIO_SERVICE] Chamando viewService para construir a resposta...");
        Map<String, AllocationNodeDto> percentages = viewService.buildAllocationTree(finalAssetsForAllocation, totalHeritage);
        Map<String, List<AssetSubCategoryDto>> assetsGrouped = viewService.buildAssetHierarchy(finalAssetsForAllocation, totalHeritage,cashEquivalentIdentifiers);
        log.info("==> [PORTFOLIO_SERVICE] Cálculo do dashboard concluído.");
        return new PortfolioDashboardDto(summary, percentages, assetsGrouped);
    }

    public PortfolioEvolutionDto getPortfolioEvolutionData(User user, String category, String assetType, String ticker) {
        LocalDate today = LocalDate.now();
        List<Transaction> allUserAssets = getAllUserAssetsAsTransaction(user);

        // 1. RESPONSABILIDADE ÚNICA: Obter a lista de transações já filtrada.
        List<Transaction> filteredTransactions = getFilteredTransactions(allUserAssets, category, assetType, ticker);

        if (filteredTransactions.isEmpty()) {
            return new PortfolioEvolutionDto(Collections.emptyList());
        }

        // 2. RESPONSABILIDADE ÚNICA: Calcular a evolução com base nos dados filtrados.
        Optional<LocalDate> firstTransactionDateOpt = filteredTransactions.stream()
                .map(Transaction::getTransactionDate)
                .min(LocalDate::compareTo);

        LocalDate firstTransactionDate = firstTransactionDateOpt.get();
        LocalDate twelveMonthsAgo = today.minusMonths(12);
        LocalDate chartStartDate = firstTransactionDate.isAfter(twelveMonthsAgo) ? firstTransactionDate : twelveMonthsAgo;

        Set<LocalDate> dates = new LinkedHashSet<>();
        dates.add(chartStartDate);
        LocalDate currentDate = chartStartDate.plusMonths(1).withDayOfMonth(1);
        while (!currentDate.isAfter(today)) {
            dates.add(currentDate);
            currentDate = currentDate.plusMonths(1);
        }
        dates.add(today);

        List<PortfolioEvolutionPointDto> evolutionPoints = dates.parallelStream()
                .map(date -> calculatePortfolioSnapshot(filteredTransactions, date))
                .collect(Collectors.toList());
        evolutionPoints.sort(Comparator.comparing(dto -> {
            if ("Hoje".equals(dto.getDate())) return LocalDate.MAX;
            try {
                // Converte "MMM/yy" de volta para uma data para ordenação
                return LocalDate.parse("01/" + dto.getDate(), DateTimeFormatter.ofPattern("dd/MMM/yy", Locale.ENGLISH));
            } catch (Exception e) {
                return LocalDate.MIN;
            }
        }));
        return new PortfolioEvolutionDto(evolutionPoints);
    }

    public PortfolioEvolutionDto getPortfolioEvolutionData() {
        return getPortfolioEvolutionData(null,null, null,null);
    }

    private PortfolioEvolutionPointDto calculatePortfolioSnapshot(List<Transaction> allTransactions, LocalDate date) {
        List<Transaction> transactionsUpToDate = allTransactions.stream()
                .filter(t -> !t.getTransactionDate().isAfter(date))
                .collect(Collectors.toList());

        List<AssetPositionDto> positions = calculatorService.calculateConsolidatedPortfolio(transactionsUpToDate, date);

        BigDecimal patrimonio = positions.stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal valorAplicado = positions.stream().map(AssetPositionDto::getTotalInvested).reduce(BigDecimal.ZERO, BigDecimal::add);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM/yy", Locale.ENGLISH);
        if(date.equals(LocalDate.now())){
            return new PortfolioEvolutionPointDto(
                    "Hoje",
                    patrimonio.setScale(2, RoundingMode.HALF_UP),
                    valorAplicado.setScale(2, RoundingMode.HALF_UP)
            );
        }
        return new PortfolioEvolutionPointDto(
                date.format(formatter),
                patrimonio.setScale(2, RoundingMode.HALF_UP),
                valorAplicado.setScale(2, RoundingMode.HALF_UP)
        );
    }

    public List<InvestedDetailDto> getInvestedValueDetails(User user) {
        // 1. Busca todas as transações e renda fixa
        List<Transaction> allUserAssets = getAllUserAssetsAsTransaction(user);

        // 2. Calcula a posição atual de todos os ativos
        List<AssetPositionDto> allCurrentAssets = calculatorService.calculateConsolidatedPortfolio(allUserAssets, LocalDate.now());

        // 3. Mapeia a lista de posições para o DTO de resposta da API
        return allCurrentAssets.stream()
                .map(asset -> new InvestedDetailDto(
                        asset.getTicker() != null ? asset.getTicker() : asset.getName(),
                        asset.getTotalInvested()
                ))
                .sorted(Comparator.comparing(InvestedDetailDto::investedValue).reversed()) // Ordena do maior para o menor
                .collect(Collectors.toList());
    }

    public List<Transaction> getTransactionsForAsset(User user, String identifier, AssetType assetType) {
        if (assetType.getCategory() == AssetCategory.FIXED_INCOME) {
            // Se for Renda Fixa, busca no repositório de Renda Fixa
            return fixedIncomeRepository.findByNameAndUser(identifier,user)
                    .map(this::convertFixedIncomeToTransaction) // Converte o resultado para uma transação
                    .map(Collections::singletonList) // Coloca em uma lista
                    .orElse(Collections.emptyList()); // Retorna lista vazia se não encontrar
        } else {
            // Para outros ativos (Ações, Criptos, ETFs), busca no repositório de transações
            return transactionRepository.findByTickerAndUserOrderByTransactionDateAsc(identifier,user);
        }
    }

    private List<Transaction> getAllUserAssetsAsTransaction(User user){
        List<Transaction> allTransactions = transactionRepository.findByUser(user);

        List<FixedIncomeAsset> fixedIncomeAssets = fixedIncomeRepository.findByUser(user);

        List<Transaction> fixedIncomeAsTransaction = fixedIncomeAssets.stream()
                .map(this::convertFixedIncomeToTransaction)
                .collect(Collectors.toList());

        List<Transaction> allAssets = new ArrayList<>();
        allAssets.addAll(allTransactions);
        allAssets.addAll(fixedIncomeAsTransaction);

        return allAssets;
    }

    @Transactional
    public void deleteAsset(User user, String identifier, AssetType assetType) {
        if (assetType.getCategory() == AssetCategory.FIXED_INCOME) {

            fixedIncomeRepository.deleteByNameAndUser(identifier,user);
        } else {
            transactionRepository.deleteByTickerAndUser(identifier,user);
        }
    }

    private Transaction convertFixedIncomeToTransaction(FixedIncomeAsset fi) {
        Transaction tx = new Transaction();
        tx.setId(fi.getId()); // Usa o mesmo ID para referência
        tx.setTicker(fi.getName());
        tx.setAssetType(fi.getAssetType());
        tx.setTransactionType(TransactionType.BUY);
        tx.setTransactionDate(fi.getInvestmentDate());
        tx.setQuantity(fi.getInvestedAmount());
        tx.setPricePerUnit(BigDecimal.ONE); // Preço unitário de Renda Fixa é sempre 1
        // Outros campos como 'otherCosts' e 'market' podem ser nulos ou definidos com padrões.
        return tx;
    }
}