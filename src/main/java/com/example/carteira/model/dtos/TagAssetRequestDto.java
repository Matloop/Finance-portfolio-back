package com.example.carteira.model.dtos;

public class TagAssetRequestDto {
    private String assetIdentifier;
    private boolean isCash;

    public TagAssetRequestDto() {
    }

    public TagAssetRequestDto(String assetIdentifier, boolean isCash) {
        this.assetIdentifier = assetIdentifier;
        this.isCash = isCash;
    }

    public String getAssetIdentifier() {
        return assetIdentifier;
    }

    public void setAssetIdentifier(String assetIdentifier) {
        this.assetIdentifier = assetIdentifier;
    }

    public boolean isCash() {
        return isCash;
    }

    public boolean getIsCash() {
        return isCash;
    }

    public void setIsCash(boolean isCash) {
        this.isCash = isCash;
    }


}