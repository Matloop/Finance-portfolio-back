
package com.example.carteira.repository;

import com.example.carteira.model.UserAssetPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAssetPreferenceRepository extends JpaRepository<UserAssetPreference, Long> {
    Optional<UserAssetPreference> findByUserIdAndAssetIdentifier(Long userId, String assetIdentifier);
    List<UserAssetPreference> findByUserIdAndIsTreatedAsCash(Long userId, boolean isTreatedAsCash);
}