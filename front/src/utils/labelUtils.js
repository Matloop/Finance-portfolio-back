// Este mapa agora é a única fonte da verdade para as traduções de labels.
const LABEL_MAP = {
    // Categorias
    brazil: 'Brasil',
    usa: 'EUA',
    crypto: 'Cripto',
    
    // Tipos de Ativo
    'ações': 'Ações',
    'stock': 'Ações',
    'etfs': 'ETFs',
    'renda fixa': 'Renda Fixa',
    'criptomoedas': 'Criptomoedas',
    'fixed_income': 'Renda Fixa',
    'etf': 'ETFs',
};

/**
 * Recebe uma chave (label) e retorna sua versão traduzida do LABEL_MAP.
 * Se não encontrar, retorna a label original.
 * @param {string} label A chave a ser traduzida (ex: "stock", "brazil").
 * @returns {string} O nome amigável traduzido.
 */
export const getFriendlyLabel = (label) => {
    if (!label) return '';
    const lowerCaseLabel = label.toLowerCase();
    
    if (Object.prototype.hasOwnProperty.call(LABEL_MAP, lowerCaseLabel)) {
        return LABEL_MAP[lowerCaseLabel];
    }
    return label;
};