package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.CreateTransactionDto;
import com.example.carteira.repository.TransactionRepository;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {
    private final TransactionRepository transactionRepository;

    public TransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public Transaction addTransaction(CreateTransactionDto dto) {
        Transaction transaction = new Transaction();
        transaction.setTicker(dto.getTicker().toUpperCase());
        transaction.setAssetType(dto.getAssetType());
        transaction.setTransactionType(dto.getTransactionType());
        transaction.setQuantity(dto.getQuantity());
        transaction.setPricePerUnit(dto.getPricePerUnit());
        transaction.setTransactionDate(dto.getTransactionDate());
        return transactionRepository.save(transaction);
    }

    public void deleteTransaction(Long id) {
        transactionRepository.deleteById(id);
    }
}