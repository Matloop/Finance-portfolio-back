package com.example.carteira.repository;

import com.example.carteira.model.CashBalance;
import com.example.carteira.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CashBalanceRepository extends JpaRepository<CashBalance, Long> {
    List<CashBalance> findByUser(User user);
}
