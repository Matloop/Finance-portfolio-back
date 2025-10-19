package com.example.carteira.repository;


import com.example.carteira.model.FixedIncomeAsset;
import com.example.carteira.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FixedIncomeRepository extends JpaRepository<FixedIncomeAsset, Long> {
    Optional<FixedIncomeAsset> findByName(String name);
    List<FixedIncomeAsset> findByNameIn(List<String> names);
    void deleteByName(String name);
    List<FixedIncomeAsset> findByUser(User user);
    Optional<FixedIncomeAsset> findByIdAndUser(Long id, User user);
    void deleteByNameAndUser(String name, User user);
    Optional<FixedIncomeAsset> findByNameAndUser(String name, User user);

}
