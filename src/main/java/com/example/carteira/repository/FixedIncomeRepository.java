package com.example.carteira.repository;


import com.example.carteira.model.FixedIncomeAsset;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FixedIncomeRepository extends JpaRepository<FixedIncomeAsset, Long> {

}
