package com.example.carteira.service;

import com.example.carteira.model.FixedIncomeAsset;
import com.example.carteira.model.Transaction;
import com.example.carteira.model.User;
import com.example.carteira.model.dtos.ImportSummaryDto;
import com.example.carteira.model.enums.*;
import com.example.carteira.repository.FixedIncomeRepository;
import com.example.carteira.repository.TransactionRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class CsvService {
    private final TransactionRepository transactionRepository;
    private final FixedIncomeRepository fixedIncomeRepository;

    public CsvService(TransactionRepository transactionRepository,
                      FixedIncomeRepository fixedIncomeRepository) {
        this.transactionRepository = transactionRepository;
        this.fixedIncomeRepository = fixedIncomeRepository;
    }

    // Os métodos de exportação não precisam de alteração
    public String exportAllToCsv(User user) {
        StringWriter sw = new StringWriter();
        String[] headers = {"Data", "Ticker", "Tipo", "Quantidade", "PrecoUnitario", "Custos", "TipoAtivo", "Mercado", "LiquidezDiaria", "Vencimento", "Indexador", "Taxa"};

        try (CSVPrinter csvPrinter = new CSVPrinter(sw, CSVFormat.DEFAULT.withHeader(headers))) {
            List<Transaction> transactions = transactionRepository.findByUser(user);
            for (Transaction tx : transactions) {
                csvPrinter.printRecord(tx.getTransactionDate(), tx.getTicker(), tx.getTransactionType(), tx.getQuantity(), tx.getPricePerUnit(), tx.getOtherCosts(), tx.getAssetType(), tx.getMarket(), "", "", "", "");
            }

            List<FixedIncomeAsset> fixedIncomes = fixedIncomeRepository.findByUser(user); // Corrigido para buscar por usuário
            for (FixedIncomeAsset fi : fixedIncomes) {
                csvPrinter.printRecord(fi.getInvestmentDate(), fi.getName(), "BUY", fi.getInvestedAmount(), "1.0000", "", fi.getAssetType(), "BR", fi.isDailyLiquid() ? "SIM" : "NAO", fi.getMaturityDate(), fi.getIndexType(), fi.getContractedRate());
            }
        } catch (IOException e) {
            throw new RuntimeException("Erro ao exportar CSV: " + e.getMessage());
        }
        return sw.toString();
    }

    public String exportTransactionsToCsv(User user) {
        return exportAllToCsv(user);
    }

    // Métodos de importação corrigidos
    public ImportSummaryDto importAllFromCsv(InputStream inputStream, User user) {
        int successCount = 0;
        int errorCount = 0;
        List<String> errors = new ArrayList<>();
        List<Transaction> validTransactions = new ArrayList<>();
        List<FixedIncomeAsset> validFixedIncome = new ArrayList<>();

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader("Data", "Ticker", "Tipo", "Quantidade", "PrecoUnitario", "Custos", "TipoAtivo", "Mercado", "LiquidezDiaria", "Vencimento", "Indexador", "Taxa")
                .setSkipHeaderRecord(true)
                .build();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
             CSVParser csvParser = new CSVParser(reader, csvFormat)) {

            for (CSVRecord record : csvParser) {
                try {
                    String assetTypeStr = record.get("TipoAtivo").toUpperCase();
                    AssetType assetType = AssetType.valueOf(assetTypeStr);
                    AssetCategory assetCategory = assetType.getCategory();

                    if (assetCategory == AssetCategory.FIXED_INCOME) {
                        FixedIncomeAsset fixedIncome = parseFixedIncomeFromRecord(record, user, assetType);
                        validFixedIncome.add(fixedIncome);
                        successCount++;
                    } else {
                        Transaction transaction = parseTransactionFromRecord(record, user, assetType);
                        validTransactions.add(transaction);
                        successCount++;
                    }

                } catch (Exception e) {
                    errorCount++;
                    errors.add("Erro na linha " + record.getRecordNumber() + ": " + e.getMessage());
                }
            }

            if (!validTransactions.isEmpty()) {
                transactionRepository.saveAll(validTransactions);
            }
            if (!validFixedIncome.isEmpty()) {
                fixedIncomeRepository.saveAll(validFixedIncome);
            }

        } catch (IOException e) {
            throw new RuntimeException("Falha ao ler o arquivo CSV: " + e.getMessage(), e);
        }

        return new ImportSummaryDto(successCount, errorCount, errors);
    }

    public ImportSummaryDto importTransactionsFromCsv(InputStream inputStream, User user) {
        return importAllFromCsv(inputStream, user);
    }

    private Transaction parseTransactionFromRecord(CSVRecord record, User user, AssetType assetType) {
        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setTransactionDate(LocalDate.parse(record.get("Data")));
        transaction.setTicker(record.get("Ticker").toUpperCase());
        transaction.setTransactionType(TransactionType.valueOf(record.get("Tipo").toUpperCase()));
        transaction.setAssetType(assetType);
        transaction.setQuantity(new BigDecimal(record.get("Quantidade")));
        transaction.setPricePerUnit(new BigDecimal(record.get("PrecoUnitario")));

        String marketStr = record.get("Mercado");
        if (marketStr != null && !marketStr.isBlank()) {
            transaction.setMarket(Market.valueOf(marketStr.toUpperCase()));
        }

        String costsStr = record.get("Custos");
        if (costsStr != null && !costsStr.isBlank()) {
            transaction.setOtherCosts(new BigDecimal(costsStr));
        }

        return transaction;
    }

    private FixedIncomeAsset parseFixedIncomeFromRecord(CSVRecord record, User user, AssetType assetType) {
        FixedIncomeAsset asset = new FixedIncomeAsset();
        asset.setUser(user);
        asset.setAssetType(assetType);
        asset.setName(record.get("Ticker"));
        asset.setInvestmentDate(LocalDate.parse(record.get("Data")));
        asset.setInvestedAmount(new BigDecimal(record.get("Quantidade")));

        String liquidezStr = record.get("LiquidezDiaria");
        asset.setDailyLiquid("SIM".equalsIgnoreCase(liquidezStr) || "TRUE".equalsIgnoreCase(liquidezStr));

        String vencimentoStr = record.get("Vencimento");
        if (vencimentoStr != null && !vencimentoStr.isBlank()) {
            asset.setMaturityDate(LocalDate.parse(vencimentoStr));
        } else {
            throw new IllegalArgumentException("Data de vencimento é obrigatória para renda fixa");
        }

        String indexadorStr = record.get("Indexador");
        if (indexadorStr != null && !indexadorStr.isBlank()) {
            asset.setIndexType(FixedIncomeIndex.valueOf(indexadorStr.toUpperCase()));
        } else {
            throw new IllegalArgumentException("Indexador é obrigatório para renda fixa");
        }

        String taxaStr = record.get("Taxa");
        if (taxaStr != null && !taxaStr.isBlank()) {
            asset.setContractedRate(new BigDecimal(taxaStr));
        } else {
            asset.setContractedRate(new BigDecimal("100"));
        }

        return asset;
    }
}