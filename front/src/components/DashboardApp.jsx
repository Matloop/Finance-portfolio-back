import React, { useState, useEffect, useCallback, useRef } from 'react';
import styles from './DashboardApp.module.css';
import Dashboard from './Dashboard/Dashboard.jsx';
import Informations from './Informations/Informations.jsx';
import Assets from './Assets/Assets.jsx';
import AddAssetModal from './AddAssetModal/AddAssetModal.jsx';
import InvestedValueModal from './InvestedValueModal/InvestedValueModal.jsx';
import ThemeToggleButton from '../ThemeToggleButton.jsx';
import { useAuth } from '../context/AuthContext.jsx';
import { fetchWithAuth } from '../../apiConfig.js';

// --- Componentes Auxiliares (Modais, etc.) ---

const Redirecting = () => {
    useEffect(() => {
        window.location.href = '/';
    }, []);
    return <div className="loading-fullscreen">Acesso negado. Redirecionando...</div>;
};

const EditAssetModal = ({ isOpen, onClose, asset }) => {
    if (!isOpen) return null;
    return (
        <div className="modal-overlay" onClick={onClose}>
            <div className="modal-content" onClick={e => e.stopPropagation()}>
                <button className="close-button" onClick={onClose}>×</button>
                <h2>Editar Ativo</h2>
                <p>Aqui você poderá ver e editar as transações do ativo:</p>
                <p><strong>{asset?.ticker || asset?.name}</strong></p>
            </div>
        </div>
    );
};

const DeleteConfirmationModal = ({ isOpen, onClose, asset, onConfirm }) => {
    if (!isOpen) return null;
    return (
        <div className="modal-overlay" onClick={onClose}>
            <div className="modal-content" onClick={e => e.stopPropagation()}>
                <button className="close-button" onClick={onClose}>×</button>
                <h2>Confirmar Exclusão</h2>
                <p>Tem certeza que deseja excluir todas as transações do ativo <strong>{asset?.ticker || asset?.name}</strong>?</p>
                <p style={{ color: 'var(--danger-red)' }}>Esta ação não pode ser desfeita.</p>
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '1rem', marginTop: '1.5rem' }}>
                    <button className="button button-secondary" onClick={onClose}>Cancelar</button>
                    <button className="button button-primary" style={{ backgroundColor: 'var(--danger-red)' }} onClick={onConfirm}>Excluir</button>
                </div>
            </div>
        </div>
    );
};

// --- Componente Principal ---

function DashboardApp() {
    // --- HOOKS DE ESTADO ---
    const { isAuthenticated, isLoading: isAuthLoading, login, logout } = useAuth();
    const [isAddAssetModalOpen, setIsAddAssetModalOpen] = useState(false);
    const [isInvestedModalOpen, setInvestedModalOpen] = useState(false);
    const [isEditModalOpen, setIsEditModalOpen] = useState(false);
    const [isDeleteModalOpen, setIsDeleteModalOpen] = useState(false);
    const [selectedAsset, setSelectedAsset] = useState(null);
    const [dashboardData, setDashboardData] = useState(null);
    const [evolutionData, setEvolutionData] = useState(null);
    const [investedDetails, setInvestedDetails] = useState([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isEvolutionLoading, setIsEvolutionLoading] = useState(true);
    const [isInvestedDetailsLoading, setIsInvestedDetailsLoading] = useState(false);
    const [dashboardError, setDashboardError] = useState(null);
    const [evolutionError, setEvolutionError] = useState(null);
    const [isRefreshing, setIsRefreshing] = useState(false);
    const [dataRefreshTrigger, setDataRefreshTrigger] = useState(0);
    const [isImporting, setIsImporting] = useState(false);
    const [isExporting, setIsExporting] = useState(false);
    const [importResult, setImportResult] = useState(null);
    const fileInputRef = useRef(null);

    // --- FUNÇÕES DE CALLBACK E MANIPULADORES DE EVENTOS ---
    const handleTransactionSuccess = () => setDataRefreshTrigger(prev => prev + 1);

    const handleEvolutionFilterChange = useCallback(async (filters) => {
        console.log("Buscando dados de evolução com os filtros:", filters);
        setIsEvolutionLoading(true);
        setEvolutionError(null);

        try {
            const chartType = filters.chartType || 'mwr';
            const endpoint = chartType === 'twr' ? '/api/portfolio/evolution/twr' : '/api/portfolio/evolution/mwr';

            const cleanFilters = {};
            for (const key in filters) {
                if (key !== 'chartType' && filters[key] && filters[key] !== 'all') {
                    cleanFilters[key] = filters[key];
                }
            }

            const params = new URLSearchParams(cleanFilters).toString();
            const url = `${endpoint}?${params}`;
            console.log("Chamando URL:", url);

            const response = await fetchWithAuth(url);
            if (!response.ok) {
                throw new Error('Falha na resposta da rede ao buscar dados de evolução.');
            }

            const evoData = await response.json();
            setEvolutionData(evoData.evolution);

        } catch (e) {
            console.error("Erro em handleEvolutionFilterChange:", e);
            setEvolutionError("Ocorreu um erro ao atualizar os dados de evolução.");
        } finally {
            setIsEvolutionLoading(false);
        }
    }, []); // useCallback para evitar recriações desnecessárias

    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        const tokenFromUrl = params.get('token');

        const fetchData = async () => {
            if (!localStorage.getItem('jwt_token')) return;
            setIsLoading(true);
            setDashboardError(null);
            try {
                const response = await fetchWithAuth('/api/portfolio/dashboard');
                if (response.ok) {
                    setDashboardData(await response.json());
                } else {
                    setDashboardError('Falha ao carregar dados do dashboard.');
                }
            } catch (e) {
                setDashboardError("Ocorreu um erro inesperado.");
            } finally {
                setIsLoading(false);
                setIsRefreshing(false);
            }
        };

        if (tokenFromUrl) {
            login(tokenFromUrl);
            window.history.replaceState({}, document.title, window.location.pathname);
        } else if (isAuthenticated) {
            fetchData();
        }
    }, [isAuthenticated, dataRefreshTrigger, login]);

    const handleOpenInvestedDetails = async () => {
        setIsInvestedDetailsLoading(true);
        setInvestedModalOpen(true);
        try {
            const response = await fetchWithAuth('/api/portfolio/invested-details');
            if (!response.ok) throw new Error('Falha ao buscar detalhes.');
            setInvestedDetails(await response.json());
        } catch (error) {
            setInvestedDetails([]);
        } finally {
            setIsInvestedDetailsLoading(false);
        }
    };

    const handleTagAssetAsCash = async (asset, isCash) => {
        const identifier = asset.ticker || asset.name;
        if (!identifier) return alert('Não foi possível identificar o ativo.');
        try {
            const response = await fetchWithAuth('/api/portfolio/preferences/tag-asset', {
                method: 'POST',
                body: JSON.stringify({ assetIdentifier: identifier, isCash }),
            });
            if (!response.ok) throw new Error('Falha ao atualizar a preferência do ativo.');
            handleTransactionSuccess();
        } catch (error) {
            alert(error.message);
        }
    };

    const confirmDelete = async () => {
        if (!selectedAsset) return;
        const identifier = selectedAsset.ticker || selectedAsset.name;
        try {
            await fetchWithAuth(`/api/portfolio/assets/${identifier}?assetType=${selectedAsset.assetType}`, { method: 'DELETE' });
            handleTransactionSuccess();
        } catch (error) {
            alert('Não foi possível excluir o ativo.');
        } finally {
            setIsDeleteModalOpen(false);
            setSelectedAsset(null);
        }
    };
    
    const handleRefreshAssets = async () => {
        setIsRefreshing(true);
        try {
            await fetchWithAuth('/api/portfolio/refresh', { method: 'POST' });
            setTimeout(() => setDataRefreshTrigger(prev => prev + 1), 2000);
        } catch (err) {
            alert('Não foi possível atualizar as cotações.');
            setIsRefreshing(false);
        }
    };
    
    const handleFileImport = async (event) => {
        const file = event.target.files[0];
        if (!file) return;
        setIsImporting(true);
        setImportResult(null);
        const formData = new FormData();
        formData.append('file', file);
        try {
            const response = await fetchWithAuth('/api/csv/import/transactions', { method: 'POST', body: formData });
            const result = await response.json();
            setImportResult(result);
            if (response.ok && result.successCount > 0) handleTransactionSuccess();
        } catch (err) {
            setImportResult({ successCount: 0, errorCount: 1, errors: ['Erro de rede.'] });
        } finally {
            setIsImporting(false);
            event.target.value = null;
        }
    };
    
    const handleExport = async () => {
        setIsExporting(true);
        try {
            const response = await fetchWithAuth('/api/csv/export/transactions');
            if (!response.ok) throw new Error('Falha na resposta da rede ao exportar.');
            const blob = await response.blob();
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'carteira_transacoes.csv';
            document.body.appendChild(a);
            a.click();
            a.remove();
        } catch (error) {
            alert("Não foi possível exportar as transações.");
        } finally {
            setIsExporting(false);
        }
    };

    const handleEditAsset = (asset) => { setSelectedAsset(asset); setIsEditModalOpen(true); };
    const handleDeleteAsset = (asset) => { setSelectedAsset(asset); setIsDeleteModalOpen(true); };
    const handleImportClick = () => { fileInputRef.current.click(); };

    // --- RETORNOS ANTECIPADOS (LOADING, AUTH) ---
    if (isAuthLoading) {
        return <div className="loading-fullscreen">Verificando autenticação...</div>;
    }
    if (!isAuthenticated) {
        return <Redirecting />;
    }

    // --- RENDERIZAÇÃO PRINCIPAL ---
    return (
        <div className={styles.appContainer}>
            <header className={styles.appHeader}>
                <h1>Minha Carteira</h1>
                <div className={styles.headerActions}>
                    <ThemeToggleButton />
                    <button className="button button-secondary" onClick={handleRefreshAssets} disabled={isRefreshing || isLoading || isEvolutionLoading || isImporting || isExporting}>
                        {isRefreshing ? 'Atualizando...' : 'Atualizar Cotações'}
                    </button>
                    <button className="button button-secondary" onClick={handleExport} disabled={isExporting || isLoading}>
                        {isExporting ? 'Exportando...' : 'Exportar CSV'}
                    </button>
                    <input type="file" ref={fileInputRef} onChange={handleFileImport} accept=".csv" style={{ display: 'none' }} />
                    <button className="button button-secondary" onClick={handleImportClick} disabled={isImporting || isExporting}>
                        {isImporting ? 'Importando...' : 'Importar CSV'}
                    </button>
                    <button className="button button-primary" onClick={() => setIsAddAssetModalOpen(true)}>
                        Adicionar Ativo
                    </button>
                    <button className={styles.logoutButton} onClick={logout}>Sair</button>
                </div>
            </header>

            <main>
                {importResult && (
                    <div className={`${styles.importSummary} ${importResult.errorCount > 0 ? styles.error : styles.success}`}>
                        {/* Conteúdo do sumário de importação */}
                    </div>
                )}

                <Informations
                    summaryData={dashboardData?.summary}
                    isLoading={isLoading}
                    error={dashboardError}
                    onOpenInvestedDetails={handleOpenInvestedDetails}
                />
                <Dashboard
                    summaryData={dashboardData?.summary}
                    percentagesData={dashboardData?.percentages}
                    evolutionData={evolutionData}
                    isPercentagesLoading={isLoading}
                    isEvolutionLoading={isEvolutionLoading}
                    onFilterChange={handleEvolutionFilterChange}
                    evolutionError={evolutionError}
                    assetsData={dashboardData?.assets} 
                />
                <Assets
                    assetsData={dashboardData?.assets}
                    isLoading={isLoading}
                    error={dashboardError}
                    onEditAsset={handleEditAsset}
                    onDeleteAsset={handleDeleteAsset}
                    onTagAssetAsCash={handleTagAssetAsCash}
                />
            </main>

            <AddAssetModal isOpen={isAddAssetModalOpen} onClose={() => setIsAddAssetModalOpen(false)} onTransactionSuccess={handleTransactionSuccess} />
            <InvestedValueModal isOpen={isInvestedModalOpen} onClose={() => setInvestedModalOpen(false)} detailsData={investedDetails} isLoading={isInvestedDetailsLoading} />
            <EditAssetModal isOpen={isEditModalOpen} onClose={() => setIsEditModalOpen(false)} asset={selectedAsset} />
            <DeleteConfirmationModal isOpen={isDeleteModalOpen} onClose={() => setIsDeleteModalOpen(false)} asset={selectedAsset} onConfirm={confirmDelete} />
        </div>
    );
}

export default DashboardApp;