package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.*;
import com.example.carteira.repository.TransactionRepository;
import org.springframework.stereotype.Service;

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

    public PortfolioService(TransactionRepository transactionRepository,
                            PortfolioCalculatorService calculatorService,
                            DashboardViewService viewService) {
        this.transactionRepository = transactionRepository;
        this.calculatorService = calculatorService;
        this.viewService = viewService;
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
}