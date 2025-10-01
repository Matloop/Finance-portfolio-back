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
        List<Transaction> allTransactions = transactionRepository.findAll();
        List<AssetPositionDto> allCurrentAssets = calculatorService.calculateConsolidatedPortfolio(allTransactions, LocalDate.now());

        BigDecimal totalHeritage = allCurrentAssets.stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalInvested = allCurrentAssets.stream().map(AssetPositionDto::getTotalInvested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profitability = totalHeritage.compareTo(totalInvested) != 0 && totalInvested.compareTo(BigDecimal.ZERO) != 0
                ? (totalHeritage.subtract(totalInvested)).divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        PortfolioSummaryDto summary = new PortfolioSummaryDto(totalHeritage, totalInvested, profitability);
        Map<String, AllocationNodeDto> percentages = viewService.buildAllocationTree(allCurrentAssets, totalHeritage);
        Map<String, List<AssetSubCategoryDto>> assetsGrouped = viewService.buildAssetHierarchy(allCurrentAssets, totalHeritage);

        return new PortfolioDashboardDto(summary, percentages, assetsGrouped);
    }

    public PortfolioEvolutionDto getPortfolioEvolutionData() {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 12; i++) {
            dates.add(today.minusMonths(i).withDayOfMonth(1));
        }
        Collections.reverse(dates);

        List<Transaction> allTransactions = transactionRepository.findAll();

        List<PortfolioEvolutionPointDto> evolutionPoints = dates.stream()
                .map(date -> calculatePortfolioSnapshot(allTransactions, date))
                .collect(Collectors.toList());

        evolutionPoints.add(calculatePortfolioSnapshot(allTransactions, today));

        return new PortfolioEvolutionDto(evolutionPoints);
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