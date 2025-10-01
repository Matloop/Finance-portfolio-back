package com.example.carteira.repository;


import com.example.carteira.model.FixedIncomeAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FixedIncomeRepository extends JpaRepository<FixedIncomeAsset, Long> {
    Optional<FixedIncomeAsset> findByName(String name);
    void deleteByName(String name);

}
