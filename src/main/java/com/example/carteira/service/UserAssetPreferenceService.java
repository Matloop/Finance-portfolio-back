package com.example.carteira.service;

import com.example.carteira.model.User;
import com.example.carteira.model.UserAssetPreference;
import com.example.carteira.repository.UserAssetPreferenceRepository;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserAssetPreferenceService {
    private final UserAssetPreferenceRepository userAssetPreferenceRepository;
    private static final Logger log = LoggerFactory.getLogger(UserAssetPreferenceService.class);

    public UserAssetPreferenceService(UserAssetPreferenceRepository userAssetPreferenceRepository) {
        this.userAssetPreferenceRepository = userAssetPreferenceRepository;
    }
    @Transactional
    public void tagAssetAsCash(User user, String assetIdentifier,boolean isCash){
        log.info("==> [PREFERENCE_SERVICE] Tentando salvar preferência para '{}' como isCash={}", assetIdentifier, isCash);
        UserAssetPreference preference = userAssetPreferenceRepository.findByUserIdAndAssetIdentifier(user.getId(), assetIdentifier)
                .orElseGet(() -> {
                    log.info("==> [PREFERENCE_SERVICE] Nenhuma preferência encontrada. CRIANDO nova para '{}'.", assetIdentifier);
                    UserAssetPreference newPref = new UserAssetPreference();
                    newPref.setUser(user);
                    newPref.setAssetIdentifier(assetIdentifier);
                    return newPref;
                });
        preference.setTreatedAsCash(isCash);
        log.info("==> [PREFERENCE_SERVICE] Preferência para '{}' SALVA com sucesso.", assetIdentifier);

        userAssetPreferenceRepository.save(preference);
    }

    public Set<String> getCashEquivalentAssetIdentifiers(User user){
        log.info("==> [PREFERENCE_SERVICE] Buscando identificadores de ativos marcados como caixa...");
        List<UserAssetPreference> preferences = userAssetPreferenceRepository.findByUserIdAndIsTreatedAsCash(user.getId(),true);
        Set<String> identifiers = preferences.stream()
                .map(UserAssetPreference::getAssetIdentifier)
                .collect(Collectors.toSet());
        log.info("==> [PREFERENCE_SERVICE] Ativos encontrados como caixa: {}", identifiers);
        return identifiers;
    }


}
