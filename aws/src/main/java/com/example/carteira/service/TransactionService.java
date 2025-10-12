package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.CreateTransactionDto;
import com.example.carteira.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final MarketDataService marketDataService;

    public TransactionService(TransactionRepository transactionRepository, MarketDataService marketDataService) {
        this.transactionRepository = transactionRepository;
        this.marketDataService = marketDataService;

    }

    public Transaction addTransaction(CreateTransactionDto dto) {
        Transaction transaction = new Transaction();
        transaction.setTicker(dto.getTicker().toUpperCase());
        transaction.setAssetType(dto.getAssetType());
        transaction.setTransactionType(dto.getTransactionType());
        transaction.setQuantity(dto.getQuantity());
        transaction.setMarket(dto.getMarket());
        transaction.setPricePerUnit(dto.getPricePerUnit());
        transaction.setTransactionDate(dto.getTransactionDate());
        Transaction savedTransaction = transactionRepository.save(transaction);
        marketDataService.updatePricesForTransactions(List.of(savedTransaction));
        return savedTransaction;
    }

    public void deleteTransaction(Long id) {
        transactionRepository.deleteById(id);
    }
}