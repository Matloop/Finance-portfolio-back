package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.*;
import com.example.carteira.model.enums.TransactionType;
import com.example.carteira.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class PortfolioService {

    private final TransactionRepository transactionRepository;
    private final MarketDataService marketDataService;
    private final FixedIncomeService fixedIncomeService;

    public PortfolioService(TransactionRepository transactionRepository,
                            MarketDataService marketDataService,
                            FixedIncomeService fixedIncomeService) {
        this.transactionRepository = transactionRepository;
        this.marketDataService = marketDataService;
        this.fixedIncomeService = fixedIncomeService;
    }

    /**
     * NOVO MÉTODO: Adiciona uma transação e busca o preço do ativo imediatamente.
     */
    public Transaction addTransaction(TransactionRequest request) {
        Transaction transaction = new Transaction();
        transaction.setTicker(request.ticker().toUpperCase());
        transaction.setAssetType(request.assetType());
        transaction.setTransactionType(request.transactionType());
        transaction.setQuantity(request.quantity());
        transaction.setPricePerUnit(request.pricePerUnit());
        transaction.setTransactionDate(LocalDate.now());

        Transaction savedTransaction = transactionRepository.save(transaction);

        // ATUALIZAÇÃO AUTOMÁTICA AO ADICIONAR UM NOVO ATIVO:
        // Chama o MarketDataService para buscar o preço do novo ativo imediatamente.
        marketDataService.updatePriceForTickers(
                List.of(savedTransaction.getTicker()),
                List.of(savedTransaction.getMarket()),
                savedTransaction.getAssetType()
        );

        return savedTransaction;
    }

    /**
     * Ponto de entrada principal para o frontend.
     * Busca as posições de todos os tipos de ativos e as unifica em uma única lista.
     */
    public List<AssetPositionDto> getConsolidatedPortfolio() {
        Stream<AssetPositionDto> transactionalAssetsStream = transactionRepository.findDistinctTickers().stream()
                .map(this::consolidateTicker)
                .filter(Objects::nonNull);

        Stream<AssetPositionDto> fixedIncomeAssetsStream = fixedIncomeService.getAllFixedIncomePositions().stream();

        return Stream.concat(transactionalAssetsStream, fixedIncomeAssetsStream)
                .collect(Collectors.toList());
    }

    private AssetPositionDto consolidateTicker(String ticker) {
        List<Transaction> transactions = transactionRepository.findByTickerOrderByTransactionDateAsc(ticker);
        if (transactions.isEmpty()) {
            return null;
        }

        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalBuyQuantity = BigDecimal.ZERO; // Precisamos da quantidade total comprada para o preço médio

        for (Transaction t : transactions) {
            if (t.getTransactionType() == TransactionType.BUY) {
                BigDecimal costOfThisTransaction = t.getQuantity().multiply(t.getPricePerUnit());
                totalCost = totalCost.add(costOfThisTransaction);
                totalQuantity = totalQuantity.add(t.getQuantity());
                totalBuyQuantity = totalBuyQuantity.add(t.getQuantity());
            } else { // Venda (SELL)
                totalQuantity = totalQuantity.subtract(t.getQuantity());
            }
        }

        if (totalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal averagePrice = totalBuyQuantity.compareTo(BigDecimal.ZERO) == 0 ?
                BigDecimal.ZERO :
                totalCost.divide(totalBuyQuantity, 4, RoundingMode.HALF_UP);

        BigDecimal currentPrice = marketDataService.getPrice(ticker);
        BigDecimal currentValue = currentPrice.multiply(totalQuantity);

        // O custo total para o cálculo de lucro deve refletir a posição atual
        BigDecimal currentPositionCost = totalQuantity.multiply(averagePrice);
        BigDecimal profitOrLoss = currentValue.subtract(currentPositionCost);

        BigDecimal profitability = currentPositionCost.compareTo(BigDecimal.ZERO) == 0 ?
                BigDecimal.ZERO :
                profitOrLoss.divide(currentPositionCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

        AssetPositionDto position = new AssetPositionDto();
        position.setTicker(ticker);
        position.setAssetType(transactions.get(0).getAssetType());
        position.setTotalQuantity(totalQuantity);
        position.setAveragePrice(averagePrice);
        position.setTotalInvested(currentPositionCost.setScale(2, RoundingMode.HALF_UP));
        position.setCurrentValue(currentValue.setScale(2, RoundingMode.HALF_UP));
        position.setProfitOrLoss(profitOrLoss.setScale(2, RoundingMode.HALF_UP));
        position.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));

        return position;
    }

    public PortfolioDashboardDto getPortfolioDashboardData() {
        // 1. CALCULA A LISTA COMPLETA APENAS UMA VEZ
        List<AssetPositionDto> allAssets = getConsolidatedPortfolio();

        // 2. REUTILIZA A LISTA para gerar as partes necessárias

        // Lógica do Summary (adaptada do seu método getPortfolioSummary)
        BigDecimal totalHeritage = allAssets.stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalInvested = allAssets.stream().map(AssetPositionDto::getTotalInvested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profitability = BigDecimal.ZERO;
        if (totalInvested.compareTo(BigDecimal.ZERO) > 0) {
            profitability = (totalHeritage.subtract(totalInvested)).divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }
        PortfolioSummaryDto summary = new PortfolioSummaryDto(totalHeritage, totalInvested, profitability);


        // Lógica das Porcentagens (adaptada do seu método getAssetPercentage)
        Map<String, BigDecimal> totalsByCategory = allAssets.stream()
                .collect(Collectors.groupingBy(
                        this::getAssetCategoryKey, // Usa o método auxiliar para criar chaves como "STOCK_B3"
                        Collectors.reducing(BigDecimal.ZERO, AssetPositionDto::getCurrentValue, BigDecimal::add)
                ));
        Map<String, BigDecimal> percentages;

        if (totalHeritage.compareTo(BigDecimal.ZERO) > 0) {
            percentages = totalsByCategory.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey, // A chave é a mesma (ex: "STOCK_B3")
                            entry -> entry.getValue() // Pega o valor (ex: 4550.00)
                                    .divide(totalHeritage, 4, RoundingMode.HALF_UP) // Divide pelo total
                                    .multiply(BigDecimal.valueOf(100)) // Multiplica por 100
                    ));
        } else {
            percentages = Map.of(); // Retorna um mapa vazio se não houver patrimônio
        }



        // Lógica da Lista Agrupada (adaptada do seu método getConsolidatedPortfolioGrouped)
        Map<String, List<AssetPositionDto>> assetsGrouped = allAssets.stream()
                .collect(Collectors.groupingBy(asset -> {
                    switch (asset.getAssetType()) {
                        case CRYPTO:
                            return "crypto";
                        case STOCK:
                            return "stock";
                        case FIXED_INCOME:
                            return "fixedIncome"; // AQUI ESTÁ A CORREÇÃO CRUCIAL
                        default:
                            return "other"; // Para qualquer outro tipo futuro
                    }
                }));


        // 3. RETORNA O DTO PAI COM TUDO DENTRO
        return new PortfolioDashboardDto(summary, percentages, assetsGrouped);
    }

    private String getAssetCategoryKey(AssetPositionDto asset) {
        // Para Renda Fixa e Cripto, o "mercado" pode ser o próprio tipo para consistência.
        String market = (asset.getMarket() != null) ? asset.getMarket().name() : asset.getAssetType().name();
        return asset.getAssetType().name() + "_" + market;
    }

    public PortfolioSummaryDto getPortfolioSummary() {
        //get total heritage
        BigDecimal totalHeritage = BigDecimal.ZERO;
        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal profitability;

        for(AssetPositionDto dto : getConsolidatedPortfolio()) {
            if(dto != null) {
                totalHeritage = totalHeritage.add(dto.getCurrentValue());
                totalInvested = totalInvested.add(dto.getTotalInvested());
            }
        }

        if (totalInvested.compareTo(BigDecimal.ZERO) <= 0) {
            return new PortfolioSummaryDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        profitability = (totalHeritage.subtract(totalInvested)).divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));


        return new PortfolioSummaryDto(totalHeritage,totalInvested,profitability);
    }

}