package com.example.carteira.service;

import com.example.carteira.model.FixedIncomeAsset;
import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.*;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.TransactionType;
import com.example.carteira.repository.FixedIncomeRepository;
import com.example.carteira.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class PortfolioService {

    private final TransactionRepository transactionRepository;
    private final PortfolioCalculatorService calculatorService;
    private final DashboardViewService viewService;
    private final FixedIncomeRepository fixedIncomeRepository;

    public PortfolioService(TransactionRepository transactionRepository,
                            PortfolioCalculatorService calculatorService,
                            DashboardViewService viewService, FixedIncomeRepository fixedIncomeRepository) {
        this.transactionRepository = transactionRepository;
        this.calculatorService = calculatorService;
        this.viewService = viewService;
        this.fixedIncomeRepository = fixedIncomeRepository;


    }

    public PortfolioDashboardDto getPortfolioDashboardData() {
        LocalDate today = LocalDate.now();
        LocalDate twelveMonthsAgo = today.minusMonths(12);
        List<Transaction> allTransactions = transactionRepository.findAll();

        List<AssetPositionDto> allCurrentAssets = calculatorService.calculateConsolidatedPortfolio(allTransactions, today);

        // =======================> CORREÇÃO APLICADA AQUI <=======================
        // Usando getters (ex: .getCurrentValue()) em vez de referências de método incorretas
        BigDecimal totalHeritage = allCurrentAssets.stream()
                .map(AssetPositionDto::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalInvested = allCurrentAssets.stream()
                .map(AssetPositionDto::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<AssetPositionDto> assetsTwelveMonthsAgo = calculatorService.calculateConsolidatedPortfolio(allTransactions, twelveMonthsAgo);

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
        Map<String, AllocationNodeDto> percentages = viewService.buildAllocationTree(allCurrentAssets, totalHeritage);
        Map<String, List<AssetSubCategoryDto>> assetsGrouped = viewService.buildAssetHierarchy(allCurrentAssets, totalHeritage);

        return new PortfolioDashboardDto(summary, percentages, assetsGrouped);
    }

    public PortfolioEvolutionDto getPortfolioEvolutionData(AssetType assetType, String ticker) {
        LocalDate today = LocalDate.now();
        List<Transaction> allTransactions = transactionRepository.findAll();

        Stream<Transaction> filteredStream = allTransactions.stream();
        if (assetType != null) {
            filteredStream = filteredStream.filter(t -> assetType.equals(t.getAssetType()));
        }
        if (ticker != null && !ticker.isBlank()) {
            filteredStream = filteredStream.filter(t -> ticker.equalsIgnoreCase(t.getTicker()));
        }
        List<Transaction> filteredTransactions = filteredStream.collect(Collectors.toList());

        if (filteredTransactions.isEmpty()) {
            return new PortfolioEvolutionDto(Collections.emptyList());
        }

        Optional<LocalDate> firstTransactionDateOpt = filteredTransactions.stream()
                .map(Transaction::getTransactionDate)
                .min(LocalDate::compareTo);

        LocalDate firstTransactionDate = firstTransactionDateOpt.get();
        LocalDate twelveMonthsAgo = today.minusMonths(12);
        LocalDate chartStartDate = firstTransactionDate.isAfter(twelveMonthsAgo) ? firstTransactionDate : twelveMonthsAgo;

        Set<LocalDate> dates = new LinkedHashSet<>(); // Usar LinkedHashSet para evitar datas duplicadas e manter a ordem

        // 1. Adiciona a data de início exata como o primeiro ponto.
        dates.add(chartStartDate);

        // 2. Adiciona o primeiro dia de cada mês subsequente.
        LocalDate currentDate = chartStartDate.plusMonths(1).withDayOfMonth(1);
        while (!currentDate.isAfter(today)) {
            dates.add(currentDate);
            currentDate = currentDate.plusMonths(1);
        }

        // 3. Adiciona a data de hoje como o último ponto.
        dates.add(today);

        // 4. Calcula os snapshots para a lista de datas única e ordenada.
        List<PortfolioEvolutionPointDto> evolutionPoints = dates.stream()
                .map(date -> calculatePortfolioSnapshot(filteredTransactions, date))
                .collect(Collectors.toList());

        return new PortfolioEvolutionDto(evolutionPoints);
    }

    public PortfolioEvolutionDto getPortfolioEvolutionData() {
        return getPortfolioEvolutionData(null, null);
    }

    private PortfolioEvolutionPointDto calculatePortfolioSnapshot(List<Transaction> allTransactions, LocalDate date) {
        List<Transaction> transactionsUpToDate = allTransactions.stream()
                .filter(t -> !t.getTransactionDate().isAfter(date))
                .collect(Collectors.toList());

        List<AssetPositionDto> positions = calculatorService.calculateConsolidatedPortfolio(transactionsUpToDate, date);

        BigDecimal patrimonio = positions.stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal valorAplicado = positions.stream().map(AssetPositionDto::getTotalInvested).reduce(BigDecimal.ZERO, BigDecimal::add);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM/yy", Locale.ENGLISH);
        return new PortfolioEvolutionPointDto(
                date.format(formatter),
                patrimonio.setScale(2, RoundingMode.HALF_UP),
                valorAplicado.setScale(2, RoundingMode.HALF_UP)
        );
    }

    public List<InvestedDetailDto> getInvestedValueDetails() {
        // 1. Busca todas as transações
        List<Transaction> allTransactions = transactionRepository.findAll();

        // 2. Calcula a posição atual de todos os ativos
        List<AssetPositionDto> allCurrentAssets = calculatorService.calculateConsolidatedPortfolio(allTransactions, LocalDate.now());

        // 3. Mapeia a lista de posições para o DTO de resposta da API
        return allCurrentAssets.stream()
                .map(asset -> new InvestedDetailDto(
                        asset.getTicker() != null ? asset.getTicker() : asset.getName(),
                        asset.getTotalInvested()
                ))
                .sorted(Comparator.comparing(InvestedDetailDto::investedValue).reversed()) // Ordena do maior para o menor
                .collect(Collectors.toList());
    }

    public List<Transaction> getTransactionsForAsset(String identifier, AssetType assetType) {
        if (assetType == AssetType.FIXED_INCOME) {
            // Se for Renda Fixa, busca no repositório de Renda Fixa
            return fixedIncomeRepository.findByName(identifier)
                    .map(this::convertFixedIncomeToTransaction) // Converte o resultado para uma transação
                    .map(Collections::singletonList) // Coloca em uma lista
                    .orElse(Collections.emptyList()); // Retorna lista vazia se não encontrar
        } else {
            // Para outros ativos (Ações, Criptos, ETFs), busca no repositório de transações
            return transactionRepository.findByTickerOrderByTransactionDateAsc(identifier);
        }
    }

    @Transactional
    public void deleteAsset(String identifier, AssetType assetType) {
        if (assetType == AssetType.FIXED_INCOME) {
            fixedIncomeRepository.deleteByName(identifier);
        } else {
            transactionRepository.deleteByTicker(identifier);
        }
    }

    private Transaction convertFixedIncomeToTransaction(FixedIncomeAsset fi) {
        Transaction tx = new Transaction();
        tx.setId(fi.getId()); // Usa o mesmo ID para referência
        tx.setTicker(fi.getName());
        tx.setAssetType(AssetType.FIXED_INCOME);
        tx.setTransactionType(TransactionType.BUY);
        tx.setTransactionDate(fi.getInvestmentDate());
        tx.setQuantity(fi.getInvestedAmount());
        tx.setPricePerUnit(BigDecimal.ONE); // Preço unitário de Renda Fixa é sempre 1
        // Outros campos como 'otherCosts' e 'market' podem ser nulos ou definidos com padrões.
        return tx;
    }
}