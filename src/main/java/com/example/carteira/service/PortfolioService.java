package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.AssetPositionDto;
import com.example.carteira.model.enums.TransactionType;
import com.example.carteira.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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
     * Ponto de entrada principal para o frontend.
     * Busca as posições de todos os tipos de ativos e as unifica em uma única lista.
     * @return Uma lista de AssetPositionDto representando o portfólio completo.
     */
    public List<AssetPositionDto> getConsolidatedPortfolio() {
        // 1. Busca e processa os ativos transacionais (Ações e Cripto)
        Stream<AssetPositionDto> transactionalAssetsStream = transactionRepository.findDistinctTickers().stream()
                .map(this::consolidateTicker)
                .filter(Objects::nonNull);

        // 2. Busca e processa os ativos de Renda Fixa
        Stream<AssetPositionDto> fixedIncomeAssetsStream = fixedIncomeService.getAllFixedIncomePositions().stream();

        // 3. Concatena os dois fluxos de dados em uma lista única e a retorna
        return Stream.concat(transactionalAssetsStream, fixedIncomeAssetsStream)
                .collect(Collectors.toList());
    }

    /**
     * Consolida todas as transações de um ticker (Ação ou Cripto) em uma única posição.
     * @param ticker O ticker a ser consolidado (ex: "PETR4", "BTC").
     * @return Um AssetPositionDto com a posição atual, ou null se a quantidade for zerada.
     */
    private AssetPositionDto consolidateTicker(String ticker) {
        List<Transaction> transactions = transactionRepository.findByTickerOrderByTransactionDateAsc(ticker);
        if (transactions.isEmpty()) {
            return null;
        }

        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        // Itera sobre o histórico de transações para calcular a posição atual
        for (Transaction t : transactions) {
            if (t.getTransactionType() == TransactionType.BUY) {
                totalQuantity = totalQuantity.add(t.getQuantity());
                totalCost = totalCost.add(t.getQuantity().multiply(t.getPricePerUnit()));
            } else { // Venda (SELL)
                totalQuantity = totalQuantity.subtract(t.getQuantity());
            }
        }

        // Se a quantidade final for zero ou negativa, o ativo não está mais na carteira
        if (totalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        // Calcula o preço médio ponderado
        BigDecimal averagePrice = totalCost.compareTo(BigDecimal.ZERO) == 0 ?
                BigDecimal.ZERO :
                totalCost.divide(totalQuantity, 4, RoundingMode.HALF_UP);

        // Busca o preço de mercado atual
        BigDecimal currentPrice = marketDataService.getPrice(ticker);
        BigDecimal currentValue = currentPrice.multiply(totalQuantity);
        BigDecimal profitOrLoss = currentValue.subtract(totalCost);
        BigDecimal profitability = totalCost.compareTo(BigDecimal.ZERO) == 0 ?
                BigDecimal.ZERO :
                profitOrLoss.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

        // Monta o DTO de resposta
        AssetPositionDto position = new AssetPositionDto();

        // Para ativos transacionais, não há um ID único de "posição".
        // Podemos deixar o ID nulo ou usar um ID de transação se o frontend precisar para algo.
        // O mais seguro é deixar nulo e usar o ticker como identificador único.
        position.setTicker(ticker);

        // *** A CORREÇÃO PRINCIPAL ESTÁ AQUI ***
        // Convertendo o Enum para uma String para corresponder ao DTO e ao que o FixedIncomeService produz.
        position.setAssetType(transactions.get(0).getAssetType().name());

        position.setTotalQuantity(totalQuantity);
        position.setAveragePrice(averagePrice);
        position.setTotalInvested(totalCost);
        position.setCurrentValue(currentValue.setScale(2, RoundingMode.HALF_UP));
        position.setProfitOrLoss(profitOrLoss.setScale(2, RoundingMode.HALF_UP));
        position.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));

        return position;
    }
}