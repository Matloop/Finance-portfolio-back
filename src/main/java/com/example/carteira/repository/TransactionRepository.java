package com.example.carteira.repository;

import com.example.carteira.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByTickerOrderByTransactionDateAsc(String ticker);

    @Query("SELECT DISTINCT t.ticker, t.market FROM Transaction t")
    List<String> findDistinctTickers();

    void deleteByTicker(String ticker);

}