package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.User;
import com.example.carteira.model.dtos.CreateTransactionDto;
import com.example.carteira.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final MarketDataService marketDataService;

    public TransactionService(TransactionRepository transactionRepository, MarketDataService marketDataService) {
        this.transactionRepository = transactionRepository;
        this.marketDataService = marketDataService;

    }

    public Transaction addTransaction(User user, CreateTransactionDto dto) {
        Transaction transaction = new Transaction();
        transaction.setTicker(dto.getTicker().toUpperCase());
        transaction.setAssetType(dto.getAssetType());
        transaction.setTransactionType(dto.getTransactionType());
        transaction.setQuantity(dto.getQuantity());
        transaction.setMarket(dto.getMarket());
        transaction.setUser(user);
        transaction.setPricePerUnit(dto.getPricePerUnit());
        transaction.setTransactionDate(dto.getTransactionDate());
        Transaction savedTransaction = transactionRepository.save(transaction);
        marketDataService.updatePricesForTransactions(List.of(savedTransaction));
        return savedTransaction;
    }

    public void deleteTransaction(User user,Long id) {
        Transaction transaction = transactionRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transação não encontrada"));
        transactionRepository.deleteById(id);
    }
}