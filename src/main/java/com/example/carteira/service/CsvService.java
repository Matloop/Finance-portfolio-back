package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.ImportSummaryDto;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.example.carteira.model.enums.TransactionType;
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
    public CsvService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public String exportTransactionsToCsv(){
        List<Transaction> transactions = transactionRepository.findAll();

        StringWriter sw = new StringWriter();

        String[] headers =  new String[]{"Data","Ticker","Tipo","Quantidade","PrecoUnitario","Custos","TipoAtivo","Mercado"};

        try(CSVPrinter csvPrinter = new CSVPrinter(sw, CSVFormat.DEFAULT.withHeader(headers))){
            for(Transaction tx : transactions){
                csvPrinter.printRecord(
                        tx.getTransactionDate(),
                        tx.getTicker(),
                        tx.getTransactionType(),
                        tx.getQuantity(),
                        tx.getPricePerUnit(),
                        tx.getOtherCosts() != null ? tx.getOtherCosts() : "",
                        tx.getAssetType(),
                        tx.getMarket() != null ? tx.getMarket().name() : ""
                );
            }
        } catch (IOException e){
            System.out.println("Csv error : " + e.getMessage());;
        }

        return sw.toString();

    }
    public ImportSummaryDto importTransactionsFromCsv(InputStream inputStream) {
        int successCount = 0;
        int errorCount = 0;
        List<String> errors = new ArrayList<>();
        List<Transaction> validTransactions = new ArrayList<>();

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader("Data", "Ticker", "Tipo", "Quantidade", "PrecoUnitario", "Custos", "TipoAtivo", "Mercado")
                .setSkipHeaderRecord(true)
                .build();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
             CSVParser csvParser = new CSVParser(reader, csvFormat)) {

            for (CSVRecord record : csvParser) {
                try {
                    // ***** CORREÇÃO APLICADA AQUI *****
                    // 1. Cria um objeto Transaction vazio.
                    Transaction transaction = new Transaction();

                    // 2. Preenche o objeto usando os métodos setter.
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

                    validTransactions.add(transaction);
                    successCount++;

                } catch (Exception e) {
                    errorCount++;
                    errors.add("Erro na linha " + record.getRecordNumber() + ": " + e.getMessage());
                }
            }

            if (!validTransactions.isEmpty()) {
                transactionRepository.saveAll(validTransactions);
            }

        } catch (IOException e) {
            throw new RuntimeException("Falha ao ler o arquivo CSV: " + e.getMessage(), e);
        }

        return new ImportSummaryDto(successCount, errorCount, errors);
    }
}

