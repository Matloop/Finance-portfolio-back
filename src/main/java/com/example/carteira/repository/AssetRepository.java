
// Location: src/main/java/com/yourportfolio/repository/AssetRepository.java
package com.example.carteira.repository;


import com.example.carteira.model.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {
    //search only for the assets the the user owns
    @Query("SELECT DISTINCT c.ticker FROM Crypto c")
    List<String> findDistinctCryptoTickers();
}
