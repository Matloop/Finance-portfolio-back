package com.example.carteira.controller;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.User;
import com.example.carteira.model.dtos.CreateTransactionDto;
import com.example.carteira.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<Transaction> addTransaction(@AuthenticationPrincipal User user, @Valid @RequestBody CreateTransactionDto dto) {
        return new ResponseEntity<>(transactionService.addTransaction(user, dto), HttpStatus.CREATED);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(@AuthenticationPrincipal User user, @PathVariable Long id) {
        transactionService.deleteTransaction(user, id);
        return ResponseEntity.noContent().build();
    }
}