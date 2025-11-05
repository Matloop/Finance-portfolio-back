package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.User;
import com.example.carteira.model.dtos.CreateTransactionDto;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.example.carteira.model.enums.TransactionType;
import com.example.carteira.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private MarketDataService marketDataService;
    @InjectMocks
    private  TransactionService transactionService;
    @Test
    @DisplayName("Should save transaction and update prices when adding new transaction")

    void addTransaction() {
        CreateTransactionDto dto = new CreateTransactionDto();
        dto.setTicker("AAPL");
        dto.setAssetType(AssetType.STOCK);
        dto.setMarket(Market.US);
        dto.setTransactionType(TransactionType.BUY);
        dto.setQuantity(BigDecimal.valueOf(10));
        dto.setPricePerUnit(BigDecimal.valueOf(150.50));
        dto.setTransactionDate(LocalDate.now());
        User user = new User();
        Transaction savedTransaction = new Transaction();
        savedTransaction.setId(1L);
        savedTransaction.setTicker("AAPL");
        savedTransaction.setAssetType(AssetType.STOCK);
        savedTransaction.setMarket(Market.US);
        savedTransaction.setTransactionType(TransactionType.BUY);
        savedTransaction.setQuantity(BigDecimal.valueOf(10));
        savedTransaction.setPricePerUnit(BigDecimal.valueOf(150.50));
        savedTransaction.setTransactionDate(LocalDate.now());
        savedTransaction.setUser(user);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        Transaction result = transactionService.addTransaction(user, dto);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        verify(transactionRepository).save(any(Transaction.class));
        verify(marketDataService).updatePricesForTransactions(anyList());

    }

    @Test
    @DisplayName("Should delete transaction when it exists and belongs to user")
    void deleteTransactionWhenExistsAndBelongsToUser() {
        User user = new  User();
        Long transactionId = 1L;

        when(transactionRepository.findByIdAndUser(transactionId,user)).thenReturn(Optional.of(new Transaction()));


        transactionService.deleteTransaction(user, transactionId);

        verify(transactionRepository).deleteById(transactionId);
        verify(transactionRepository).findByIdAndUser(transactionId,user);
    }
    @Test
    @DisplayName("Should throw an exception when user does not exists")
    void deleteTransactionWhenUserNotFound(){
        User user = new User();
        Long transactionId = 1L;
        when(transactionRepository.findByIdAndUser(transactionId,user)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> transactionService.deleteTransaction(user,transactionId));

        verify(transactionRepository, never()).deleteById(any());
    }

}