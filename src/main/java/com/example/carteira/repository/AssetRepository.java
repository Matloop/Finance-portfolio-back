
// Location: src/main/java/com/yourportfolio/repository/AssetRepository.java
package com.example.carteira.repository;


import com.example.carteira.model.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {
}
