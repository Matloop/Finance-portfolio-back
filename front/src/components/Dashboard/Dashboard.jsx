import React, { useState, useEffect, useMemo } from 'react';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, ResponsiveContainer, Legend as RechartsLegend } from 'recharts';
import AllocationChart from './AllocationChart';
import styles from './dashboard.module.css';
import { getFriendlyLabel } from '../../utils/labelUtils';

// --- FUNÇÕES AUXILIARES ---
const formatChartDate = (dateString) => {
    if (!dateString || !dateString.includes('/')) return dateString;
    const [month, year] = dateString.split('/');
    const monthMap = {
        'Jan': 'Jan', 'Feb': 'Fev', 'Mar': 'Mar', 'Apr': 'Abr',
        'May': 'Mai', 'Jun': 'Jun', 'Jul': 'Jul', 'Aug': 'Ago',
        'Sep': 'Set', 'Oct': 'Out', 'Nov': 'Nov', 'Dec': 'Dez'
    };
    const translatedMonth = monthMap[month] || month;
    return `${translatedMonth}/${year}`;
};

const formatCurrency = (value = 0) =>
    Number(value).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });

const formatPercentageChange = (value = 0) => {
    const formatted = Number(value).toLocaleString('pt-BR', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
    });
    return `${value >= 0 ? '+' : ''}${formatted}%`;
};

// --- Componente de Tooltip customizado (usa o objeto 'styles') ---
const CustomTooltip = ({ active, payload, chartType }) => {
    if (active && payload && payload.length) {
        const data = payload[0].payload;
        return (
            <div className={styles.customTooltip}>
                <p className={styles.tooltipDate}>{data.date}</p>
                {chartType === 'twr' ? (
                    <p className={styles.tooltipItem}>
                        Desempenho: {data.performance.toFixed(2)}%
                    </p>
                ) : (
                    <>
                        <p className={`${styles.tooltipItem} ${styles.valorAplicado}`}>Valor Aplicado: {formatCurrency(data.valorAplicado)}</p>
                        <p className={`${styles.tooltipItem} ${styles.ganhoCapital}`}>Ganho Capital: {formatCurrency(data.ganhoCapital)}</p>
                        <p className={`${styles.tooltipItem} ${styles.patrimonio}`}>Patrimônio: {formatCurrency(data.patrimonio)}</p>
                    </>

                )}

            </div>
        );
    }
    return null;
};

// --- Componente Principal (código restante está correto) ---
const Dashboard = ({
    summaryData,
    percentagesData,
    evolutionData,
    isPercentagesLoading,
    isEvolutionLoading,
    evolutionError,
    onFilterChange,
    assetsData
}) => {

    const [viewStack, setViewStack] = useState([{ path: [], title: 'Alocação por Categoria' }]);
    const [isDarkMode, setIsDarkMode] = useState(false);

    const [selectedCategory, setSelectedCategory] = useState('all');
    const [selectedAssetType, setSelectedAssetType] = useState('all');
    const [selectedTicker, setSelectedTicker] = useState('all');
    const [evolutionChartType, setEvolutionChartType] = useState('mwr');
    useEffect(() => {
        const checkTheme = () => setIsDarkMode(document.documentElement.classList.contains('dark'));
        checkTheme();
        const observer = new MutationObserver(checkTheme);
        observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
        return () => observer.disconnect();
    }, []);

    const { currentDataNode, colorKey } = useMemo(() => {
        if (!percentagesData) return { currentDataNode: null, colorKey: 'category' };
        const currentView = viewStack[viewStack.length - 1];
        let dataNode = percentagesData;
        currentView.path.forEach(key => { dataNode = dataNode[key]?.children || {}; });
        const cKey = currentView.path.length > 0 ? currentView.path[0] : 'category';
        return { currentDataNode: dataNode, colorKey: cKey };
    }, [percentagesData, viewStack]);

    const filterOptions = useMemo(() => {
        if (!assetsData) return { categories: [], assetTypes: [], tickers: [] };

        const categories = Object.keys(assetsData).sort();
        let assetTypes = [];
        let tickers = [];

        if (selectedCategory !== 'all' && assetsData[selectedCategory]) {
            if (selectedCategory === 'cripto') {
                tickers = assetsData.cripto
                    .flatMap(subCat => subCat.assets || [])
                    .map(asset => asset.ticker)
                    .sort();
            } else {
                assetTypes = assetsData[selectedCategory]
                    .map(subCat => subCat.categoryName)
                    .sort();

                if (selectedAssetType !== 'all') {
                    tickers = assetsData[selectedCategory]
                        .find(subCat => subCat.categoryName === selectedAssetType)
                        ?.assets.map(asset => asset.ticker || asset.name)
                        .sort() || [];
                }
            }
        }

        return { categories, assetTypes, tickers };
    }, [assetsData, selectedCategory, selectedAssetType]);

    useEffect(() => {
        if (onFilterChange && !isPercentagesLoading) {
            const filters = {
                category: selectedCategory !== 'all' ? selectedCategory : null,
                assetType: selectedAssetType !== 'all' ? selectedAssetType : null,
                ticker: selectedTicker !== 'all' ? selectedTicker : null,
                chartType: evolutionChartType
            };
            onFilterChange(filters);
        }
    }, [selectedCategory, selectedAssetType, selectedTicker, onFilterChange, isPercentagesLoading, evolutionChartType]);

    const handlePieClick = (originalLabel) => {
        const clickedNode = currentDataNode?.[originalLabel];
        if (clickedNode && clickedNode.children && Object.keys(clickedNode.children).length > 0) {
            const currentView = viewStack[viewStack.length - 1];
            const newPath = [...currentView.path, originalLabel];
            const newTitle = `Alocação em ${getFriendlyLabel(originalLabel)}`;
            setViewStack([...viewStack, { path: newPath, title: newTitle }]);
        }
    };

    const handleBack = () => {
        if (viewStack.length > 1) {
            setViewStack(viewStack.slice(0, -1));
        }
    };

    const rechartsData = useMemo(() => {
        if (!evolutionData || evolutionData.length === 0) return [];
        if (evolutionChartType === "twr") {
            return evolutionData.map(d => ({
                date: formatChartDate(d.date),
                performance: d.patrimonio - 100
            }));
        }
        return evolutionData.map(d => ({
            date: formatChartDate(d.date),
            valorAplicado: d.valorAplicado,
            ganhoCapital: d.patrimonio - d.valorAplicado,
            patrimonio: d.patrimonio
        }));
    }, [evolutionData, evolutionChartType]);


    // O JSX que eu forneci antes já estava correto, usando o objeto 'styles'
    return (
        <div className={styles.dashboardContainer}>
            <div className={`card ${styles.chartCard}`}>
                <div className={styles.chartHeader}>
                    <h2 className={styles.chartTitle}>{viewStack[viewStack.length - 1].title}</h2>
                    {viewStack.length > 1 && (
                        <button className={styles.backButton} onClick={handleBack}>← Voltar</button>
                    )}
                </div>
                <div className={styles.chartWrapper}>
                    {isPercentagesLoading && <p className={styles.loadingText}>Carregando alocação...</p>}
                    {!isPercentagesLoading && currentDataNode && Object.keys(currentDataNode).length > 0 && (
                        <AllocationChart
                            dataNode={currentDataNode}
                            colorKey={colorKey}
                            onSliceClick={handlePieClick}
                            isDarkMode={isDarkMode}
                        />
                    )}
                    {!isPercentagesLoading && (!percentagesData || Object.keys(percentagesData).length === 0) && (
                        <p className={styles.infoMessage}>Adicione ativos para ver a alocação.</p>
                    )}
                </div>
            </div>

            <div className={`card ${styles.chartCard}`}>
                <div className={styles.chartHeader}>
                    <div className={styles.chartTitleWrapper}>
                        <h2 className={styles.chartTitle}>Evolução do Patrimônio</h2>

                        {/* ADICIONE ESTE BLOCO */}
                        <div className={styles.toggleButtonGroup}>
                            <button
                                className={`${styles.toggleButton} ${evolutionChartType === 'mwr' ? styles.active : ''}`}
                                onClick={() => setEvolutionChartType('mwr')}
                            >
                                Patrimônio (R$)
                            </button>
                            <button
                                className={`${styles.toggleButton} ${evolutionChartType === 'twr' ? styles.active : ''}`}
                                onClick={() => setEvolutionChartType('twr')}
                            >
                                Desempenho (%)
                            </button>
                        </div>
                    </div>
                    <div className={styles.evolutionFilters}>
                        <select
                            value={selectedCategory}
                            onChange={e => {
                                setSelectedCategory(e.target.value);
                                setSelectedAssetType('all');
                                setSelectedTicker('all');
                            }}
                            disabled={isEvolutionLoading || !assetsData}
                        >
                            <option value="all">Todas as Categorias</option>
                            {filterOptions.categories.map(cat => (
                                <option key={cat} value={cat}>{getFriendlyLabel(cat)}</option>
                            ))}
                        </select>

                        {selectedCategory === 'cripto' ? (
                            <select
                                value={selectedTicker}
                                onChange={e => setSelectedTicker(e.target.value)}
                                disabled={isEvolutionLoading || selectedCategory === 'all'}
                            >
                                <option value="all">Todas as Criptos</option>
                                {filterOptions.tickers.map(ticker => <option key={ticker} value={ticker}>{ticker}</option>)}
                            </select>
                        ) : (
                            <select
                                value={selectedAssetType}
                                onChange={e => {
                                    setSelectedAssetType(e.target.value);
                                    setSelectedTicker('all');
                                }}
                                disabled={isEvolutionLoading || selectedCategory === 'all'}
                            >
                                <option value="all">Todos os Tipos</option>
                                {filterOptions.assetTypes.map(type => <option key={type} value={type}>{getFriendlyLabel(type)}</option>)}
                            </select>
                        )}

                        {selectedCategory !== 'all' && selectedCategory !== 'cripto' && selectedAssetType !== 'all' && (
                            <select
                                value={selectedTicker}
                                onChange={e => setSelectedTicker(e.target.value)}
                                disabled={isEvolutionLoading}
                            >
                                <option value="all">Todos os Ativos</option>
                                {filterOptions.tickers.map(ticker => <option key={ticker} value={ticker}>{ticker}</option>)}
                            </select>
                        )}
                    </div>
                </div>
                <div className={styles.chartWrapper}>
                    {isEvolutionLoading && <p className={styles.loadingText}>Carregando evolução...</p>}
                    {!isEvolutionLoading && evolutionError && (
                        <div className="error-message">
                            <p>Erro ao carregar dados de evolução.</p>
                            <small>{evolutionError}</small>
                        </div>
                    )}
                    {!isEvolutionLoading && !evolutionError && rechartsData.length > 0 && (
                        <ResponsiveContainer width="100%" height="100%">
                            <AreaChart data={rechartsData} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
                                <defs>
                                    <linearGradient id="colorValorAplicado" x1="0" y1="0" x2="0" y2="1">
                                        <stop offset="5%" stopColor={isDarkMode ? "#60a5fa" : "#3b82f6"} stopOpacity={0.8} />
                                        <stop offset="95%" stopColor={isDarkMode ? "#60a5fa" : "#3b82f6"} stopOpacity={0.1} />
                                    </linearGradient>
                                    <linearGradient id="colorGanhoCapital" x1="0" y1="0" x2="0" y2="1">
                                        <stop offset="5%" stopColor={isDarkMode ? "#4ade80" : "#10b981"} stopOpacity={0.8} />
                                        <stop offset="95%" stopColor={isDarkMode ? "#4ade80" : "#10b981"} stopOpacity={0.1} />
                                    </linearGradient>
                                    <linearGradient id="colorPerformance" x1="0" y1="0" x2="0" y2="1">
                                        <stop offset="5%" stopColor="#8884d8" stopOpacity={0.8} />
                                        <stop offset="95%" stopColor="#8884d8" stopOpacity={0.1} />
                                    </linearGradient>
                                </defs>
                                <CartesianGrid strokeDasharray="3 3" stroke={isDarkMode ? "#334155" : "#e5e7eb"} vertical={false} />
                                <XAxis dataKey="date" stroke={isDarkMode ? "#94a3b8" : "#6b7280"} style={{ fontSize: '0.75rem' }} />
                                <YAxis stroke={isDarkMode ? "#94a3b8" : "#6b7280"} style={{ fontSize: '0.75rem' }} tickFormatter={(value) => {
                                    if (evolutionChartType === 'twr') {
                                        return `${value.toFixed(0)}%`; // Para TWR, apenas o número do índice
                                    }
                                    if (Math.abs(value) >= 1000000) return `${(value / 1000000).toFixed(1)}M`;
                                    if (Math.abs(value) >= 1000) return `${(value / 1000).toFixed(0)}k`;
                                    return value;
                                }} />
                                <RechartsTooltip content={<CustomTooltip chartType={evolutionChartType}/>} />
                                <RechartsLegend wrapperStyle={{ fontSize: '0.875rem', paddingTop: '1rem' }} iconType="circle" />
                                {evolutionChartType === "twr" ? (
                                    <Area
                                        type="monotone"
                                        dataKey="performance"
                                        stroke="#8884d8" // Uma cor diferente para o desempenho
                                        strokeWidth={2}
                                        fillOpacity={1}
                                        fill="url(#colorPerformance)" // Precisaremos definir este gradiente
                                        name="Desempenho (Índice)"
                                    />
                                ) : (
                                    <>
                                        <Area
                                            type="monotone"
                                            dataKey="valorAplicado"
                                            stroke={isDarkMode ? "#60a5fa" : "#3b82f6"}
                                            strokeWidth={2}
                                            fillOpacity={1}
                                            fill="url(#colorValorAplicado)"
                                            name="Valor Aplicado"
                                        />
                                        <Area
                                            type="monotone"
                                            dataKey="ganhoCapital"
                                            stroke={isDarkMode ? "#4ade80" : "#10b981"}
                                            strokeWidth={2}
                                            fillOpacity={1}
                                            fill="url(#colorGanhoCapital)"
                                            name="Ganho Capital"
                                        />
                                    </>
                                )
                                }
                            </AreaChart>
                        </ResponsiveContainer>
                    )}
                    {!isEvolutionLoading && !evolutionError && (!evolutionData || evolutionData.length === 0) && (
                        <p className={styles.infoMessage}>Adicione transações para ver a evolução do seu patrimônio.</p>
                    )}
                </div>
            </div>
        </div>
    );
};

export default Dashboard;