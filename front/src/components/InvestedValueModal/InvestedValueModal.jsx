// src/components/InvestedValueModal/InvestedValueModal.jsx
import React, { useMemo } from 'react';
import './InvestedValueModal.css';

const formatCurrency = (value = 0) =>
  Number(value).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });

const InvestedValueModal = ({ isOpen, onClose, detailsData, isLoading }) => {
  if (!isOpen) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <button className="close-button" onClick={onClose}>×</button>
        <h2>Valor Investido por Ativo</h2>
        
        <div className="invested-list">
          {/* 2. ADICIONADO ESTADO DE CARREGAMENTO */}
          {isLoading ? (
            <p style={{ textAlign: 'center' }}>Carregando detalhes...</p>
          ) : detailsData.length > 0 ? (
            // 3. MAPEIA DIRETAMENTE A LISTA RECEBIDA (detailsData)
            detailsData.map((asset, index) => (
              <div key={`${asset.name}-${index}`} className="invested-list-item">
                <span className="invested-asset-name">{asset.name}</span>
                <span className="invested-asset-value">{formatCurrency(asset.investedValue)}</span>
              </div>
            ))
          ) : (
            <p>Nenhum dado de ativo disponível.</p>
          )}
        </div>
      </div>
    </div>
  );
};

export default InvestedValueModal;