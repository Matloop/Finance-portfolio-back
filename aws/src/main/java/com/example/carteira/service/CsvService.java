package com.example.carteira.service;

import com.example.carteira.model.FixedIncomeAsset;
import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.ImportSummaryDto;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.FixedIncomeIndex;
import com.example.carteira.model.enums.Market;
import com.example.carteira.model.enums.TransactionType;
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

    /**
     * Exporta transações e renda fixa para CSV.
     * Formato: Data,Ticker,Tipo,Quantidade,PrecoUnitario,Custos,TipoAtivo,Mercado,LiquidezDiaria,Vencimento,Indexador,Taxa
     */
    public String exportAllToCsv() {
        StringWriter sw = new StringWriter();

        String[] headers = new String[]{
                "Data", "Ticker", "Tipo", "Quantidade", "PrecoUnitario", "Custos",
                "TipoAtivo", "Mercado", "LiquidezDiaria", "Vencimento", "Indexador", "Taxa"
        };

        try (CSVPrinter csvPrinter = new CSVPrinter(sw, CSVFormat.DEFAULT.withHeader(headers))) {

            // Exporta transações normais
            List<Transaction> transactions = transactionRepository.findAll();
            for (Transaction tx : transactions) {
                csvPrinter.printRecord(
                        tx.getTransactionDate(),
                        tx.getTicker(),
                        tx.getTransactionType(),
                        tx.getQuantity(),
                        tx.getPricePerUnit(),
                        tx.getOtherCosts() != null ? tx.getOtherCosts() : "",
                        tx.getAssetType(),
                        tx.getMarket() != null ? tx.getMarket().name() : "",
                        "", "", "", "" // Campos vazios para renda fixa
                );
            }

            // Exporta renda fixa
            List<FixedIncomeAsset> fixedIncomes = fixedIncomeRepository.findAll();
            for (FixedIncomeAsset fi : fixedIncomes) {
                csvPrinter.printRecord(
                        fi.getInvestmentDate(),
                        fi.getName(),
                        "BUY", // Renda fixa sempre é compra
                        fi.getInvestedAmount(),
                        "1.0000", // Preço unitário sempre 1 para renda fixa
                        "", // Sem outros custos
                        "FIXED_INCOME",
                        "BR", // Renda fixa sempre Brasil
                        fi.isDailyLiquid() ? "SIM" : "NAO",
                        fi.getMaturityDate(),
                        fi.getIndexType(),
                        fi.getContractedRate()
                );
            }

        } catch (IOException e) {
            System.out.println("Csv error : " + e.getMessage());
        }

        return sw.toString();
    }

    /**
     * Mantém compatibilidade com o método antigo (só transações)
     */
    public String exportTransactionsToCsv() {
        return exportAllToCsv();
    }

    /**
     * Importa transações e renda fixa de um CSV.
     * Formato esperado:
     * - Campos obrigatórios para todos: Data,Ticker,Tipo,Quantidade,PrecoUnitario,Custos,TipoAtivo,Mercado
     * - Campos adicionais para FIXED_INCOME: LiquidezDiaria,Vencimento,Indexador,Taxa
     */
    public ImportSummaryDto importAllFromCsv(InputStream inputStream) {
        int successCount = 0;
        int errorCount = 0;
        List<String> errors = new ArrayList<>();
        List<Transaction> validTransactions = new ArrayList<>();
        List<FixedIncomeAsset> validFixedIncome = new ArrayList<>();

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader("Data", "Ticker", "Tipo", "Quantidade", "PrecoUnitario", "Custos",
                        "TipoAtivo", "Mercado", "LiquidezDiaria", "Vencimento", "Indexador", "Taxa")
                .setSkipHeaderRecord(true)
                .build();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
             CSVParser csvParser = new CSVParser(reader, csvFormat)) {

            for (CSVRecord record : csvParser) {
                try {
                    String assetTypeStr = record.get("TipoAtivo").toUpperCase();
                    AssetType assetType = AssetType.valueOf(assetTypeStr);

                    if (assetType == AssetType.FIXED_INCOME) {
                        // Processa renda fixa
                        FixedIncomeAsset fixedIncome = parseFixedIncomeFromRecord(record);
                        validFixedIncome.add(fixedIncome);
                        successCount++;
                    } else {
                        // Processa transação normal
                        Transaction transaction = parseTransactionFromRecord(record);
                        validTransactions.add(transaction);
                        successCount++;
                    }

                } catch (Exception e) {
                    errorCount++;
                    errors.add("Erro na linha " + record.getRecordNumber() + ": " + e.getMessage());
                }
            }

            // Salva tudo no banco
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

    /**
     * Mantém compatibilidade com o método antigo (só transações)
     */
    public ImportSummaryDto importTransactionsFromCsv(InputStream inputStream) {
        return importAllFromCsv(inputStream);
    }

    private Transaction parseTransactionFromRecord(CSVRecord record) {
        Transaction transaction = new Transaction();

        transaction.setTransactionDate(LocalDate.parse(record.get("Data")));
        transaction.setTicker(record.get("Ticker").toUpperCase());
        transaction.setTransactionType(TransactionType.valueOf(record.get("Tipo").toUpperCase()));
        transaction.setAssetType(AssetType.valueOf(record.get("TipoAtivo").toUpperCase()));
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

    private FixedIncomeAsset parseFixedIncomeFromRecord(CSVRecord record) {
        FixedIncomeAsset asset = new FixedIncomeAsset();

        asset.setName(record.get("Ticker"));
        asset.setInvestmentDate(LocalDate.parse(record.get("Data")));
        asset.setInvestedAmount(new BigDecimal(record.get("Quantidade")));

        // Liquidez diária
        String liquidezStr = record.get("LiquidezDiaria");
        asset.setDailyLiquid("SIM".equalsIgnoreCase(liquidezStr) || "TRUE".equalsIgnoreCase(liquidezStr));

        // Data de vencimento
        String vencimentoStr = record.get("Vencimento");
        if (vencimentoStr != null && !vencimentoStr.isBlank()) {
            asset.setMaturityDate(LocalDate.parse(vencimentoStr));
        } else {
            throw new IllegalArgumentException("Data de vencimento é obrigatória para renda fixa");
        }

        // Indexador
        String indexadorStr = record.get("Indexador");
        if (indexadorStr != null && !indexadorStr.isBlank()) {
            asset.setIndexType(FixedIncomeIndex.valueOf(indexadorStr.toUpperCase()));
        } else {
            throw new IllegalArgumentException("Indexador é obrigatório para renda fixa");
        }

        // Taxa contratada
        String taxaStr = record.get("Taxa");
        if (taxaStr != null && !taxaStr.isBlank()) {
            asset.setContractedRate(new BigDecimal(taxaStr));
        } else {
            // Se não fornecida, assume 100% (padrão)
            asset.setContractedRate(new BigDecimal("100"));
        }

        return asset;
    }
}