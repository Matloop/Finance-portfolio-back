package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.*;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
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

import static com.example.carteira.model.enums.Market.US;

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

    private AssetPositionDto consolidateTicker(String ticker) {
        List<Transaction> transactions = transactionRepository.findByTickerOrderByTransactionDateAsc(ticker);
        if (transactions.isEmpty()) {
            return null;
        }

        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalBuyQuantity = BigDecimal.ZERO;
        Market market = US;// Precisamos da quantidade total comprada para o preço médio

        for (Transaction t : transactions) {
            if (t.getTransactionType() == TransactionType.BUY) {
                BigDecimal costOfThisTransaction = t.getQuantity().multiply(t.getPricePerUnit());
                totalCost = totalCost.add(costOfThisTransaction);
                totalQuantity = totalQuantity.add(t.getQuantity());
                totalBuyQuantity = totalBuyQuantity.add(t.getQuantity());
                market = t.getMarket();
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
        position.setMarket(market);
        position.setTotalInvested(currentPositionCost.setScale(2, RoundingMode.HALF_UP));
        position.setCurrentValue(currentValue.setScale(2, RoundingMode.HALF_UP));
        position.setProfitOrLoss(profitOrLoss.setScale(2, RoundingMode.HALF_UP));
        position.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));

        return position;
    }

    private AssetPositionDto calculateAssetPosition(AssetKey key, List<Transaction> transactions) {
        // A lógica de cálculo de preço médio, etc., é a mesma de antes.
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalBuyQuantity = BigDecimal.ZERO;

        for (Transaction t : transactions) {
            if (t.getTransactionType() == TransactionType.BUY) {
                totalCost = totalCost.add(t.getQuantity().multiply(t.getPricePerUnit()));
                totalQuantity = totalQuantity.add(t.getQuantity());
                totalBuyQuantity = totalBuyQuantity.add(t.getQuantity());
            } else {
                totalQuantity = totalQuantity.subtract(t.getQuantity());
            }
        }

        if (totalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null; // A posição foi zerada.
        }

        BigDecimal averagePrice = totalCost.divide(totalBuyQuantity, 4, RoundingMode.HALF_UP);
        BigDecimal currentPrice = marketDataService.getPrice(key.ticker());
        BigDecimal currentValue = currentPrice.multiply(totalQuantity);
        BigDecimal currentPositionCost = totalQuantity.multiply(averagePrice);
        BigDecimal profitOrLoss = currentValue.subtract(currentPositionCost);
        BigDecimal profitability = currentPositionCost.compareTo(BigDecimal.ZERO) > 0 ?
                profitOrLoss.divide(currentPositionCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)) :
                BigDecimal.ZERO;

        // CRIAÇÃO CORRETA DO DTO, AGORA COM TODOS OS DADOS
        AssetPositionDto position = new AssetPositionDto();
        position.setTicker(key.ticker());
        position.setAssetType(key.assetType()); // Usa o tipo da chave
        position.setMarket(key.market());       // USA O MERCADO DA CHAVE (CORREÇÃO CRÍTICA)
        position.setTotalQuantity(totalQuantity);
        position.setAveragePrice(averagePrice);
        position.setTotalInvested(currentPositionCost.setScale(2, RoundingMode.HALF_UP));
        position.setCurrentValue(currentValue.setScale(2, RoundingMode.HALF_UP));
        position.setProfitOrLoss(profitOrLoss.setScale(2, RoundingMode.HALF_UP));
        position.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));

        return position;
    }

    private List<AssetPositionDto> getConsolidatedPortfolio() {
        // Passo 1: Busca todas as transações em UMA ÚNICA query.
        List<Transaction> allTransactions = transactionRepository.findAll();

        // Passo 2: Agrupa as transações em memória por sua chave única (ticker, tipo, mercado).
        Map<AssetKey, List<Transaction>> groupedTransactions = allTransactions.stream()
                .filter(t -> t.getTicker() != null) // Filtro de segurança
                .collect(Collectors.groupingBy(t -> new AssetKey(t.getTicker(), t.getAssetType(), t.getMarket())));

        // Passo 3: Mapeia cada grupo de transações para uma Posição de Ativo (AssetPositionDto).
        Stream<AssetPositionDto> transactionalAssetsStream = groupedTransactions.entrySet().stream()
                .map(entry -> calculateAssetPosition(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull);

        // Passo 4: Busca as posições de Renda Fixa.
        Stream<AssetPositionDto> fixedIncomeAssetsStream = fixedIncomeService.getAllFixedIncomePositions().stream();

        // Passo 5: Concatena os dois streams e retorna a lista final.
        return Stream.concat(transactionalAssetsStream, fixedIncomeAssetsStream)
                .collect(Collectors.toList());
    }

    private String getAssetCategoryKeyForPercentages(AssetPositionDto asset) {
        String market = (asset.getMarket() != null) ? asset.getMarket().name() : asset.getAssetType().name();
        return asset.getAssetType().name() + "_" + market;
    }

    private Map<String, BigDecimal> calculatePercentages(List<AssetPositionDto> allAssets, BigDecimal totalHeritage) {
        if (totalHeritage.compareTo(BigDecimal.ZERO) <= 0) {
            return Map.of();
        }

        Map<String, BigDecimal> totalsByCategory = allAssets.stream()
                .collect(Collectors.groupingBy(
                        this::getAssetCategoryKeyForPercentages,
                        Collectors.reducing(BigDecimal.ZERO, AssetPositionDto::getCurrentValue, BigDecimal::add)
                ));

        return totalsByCategory.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue()
                                .divide(totalHeritage, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                ));
    }

    public PortfolioDashboardDto getPortfolioDashboardData() {
        // 1. CALCULA A LISTA COMPLETA DE POSIÇÕES DE FORMA EFICIENTE
        List<AssetPositionDto> allAssets = getConsolidatedPortfolio();

        // 2. GERA OS DADOS PARA O DASHBOARD A PARTIR DA LISTA CONSOLIDADA

        // --- Lógica do Resumo (Summary) ---
        BigDecimal totalHeritage = allAssets.stream().map(AssetPositionDto::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalInvested = allAssets.stream().map(AssetPositionDto::getTotalInvested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profitability = BigDecimal.ZERO;
        if (totalInvested.compareTo(BigDecimal.ZERO) > 0) {
            profitability = (totalHeritage.subtract(totalInvested)).divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }
        PortfolioSummaryDto summary = new PortfolioSummaryDto(totalHeritage, totalInvested, profitability);

        // --- Lógica das Porcentagens (Percentages) ---
        Map<String, BigDecimal> percentages = calculatePercentages(allAssets, totalHeritage);

        // --- Lógica da Lista Agrupada (Assets) ---
        Map<String, List<AssetPositionDto>> assetsGrouped = allAssets.stream()
                .collect(Collectors.groupingBy(this::getAssetDisplayCategoryKey));

        // 3. RETORNA O DTO PAI COM TUDO DENTRO
        return new PortfolioDashboardDto(summary, percentages, assetsGrouped);
    }

    private String getAssetDisplayCategoryKey(AssetPositionDto asset) {
        return asset.getAssetType().name().toLowerCase();
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

    private record AssetKey(String ticker, AssetType assetType, Market market) {}

}