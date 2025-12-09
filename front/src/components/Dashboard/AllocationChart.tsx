// --- components/Dashboard/AllocationChart.tsx ---
import React, { useMemo } from 'react';
import { ResponsivePie } from '@nivo/pie';
import { getFriendlyLabel } from '../../utils/labelUtils';

// --- DEFINIÇÃO DE TIPOS ---
interface ChartNode {
    percentage: number;
    children?: DataNode;
}

interface DataNode {
    [key: string]: ChartNode;
}

interface AllocationChartProps {
    dataNode: DataNode | null;
    colorKey: string;
    onSliceClick: (key: string) => void;
    isDarkMode: boolean;
}

const COLORS = {
    category: ['#3b82f6', '#16a34a', '#f97316', '#9333ea', '#ec4899', '#f59e0b'],
    brazil: ['#3498db', '#1abc9c', '#27ae60', '#f1c40f'],
    usa: ['#2ecc71', '#16a085', '#d35400'],
    crypto: ['#f7931a', '#627eea', '#f3ba2f', '#26a17b', '#e84142', '#a6b9c7', '#222222'],
};



const AllocationChart: React.FC<AllocationChartProps> = ({ dataNode, colorKey, onSliceClick, isDarkMode }) => {

    const chartData = useMemo(() => {
        if (!dataNode || Object.keys(dataNode).length === 0) return [];
        const colors = COLORS[colorKey as keyof typeof COLORS] || COLORS.category;
        return Object.entries(dataNode).map(([key, node], index) => ({
            id: getFriendlyLabel(key), // <-- 3. USAR A FUNÇÃO IMPORTADA
            label: getFriendlyLabel(key), // <-- 3. USAR A FUNÇÃO IMPORTADA
            value: node.percentage,
            originalKey: key,
            color: colors[index % colors.length],
        }));
    }, [dataNode, colorKey]);

    const theme = {
        tooltip: {
            container: {
                background: isDarkMode ? 'rgba(30, 41, 59, 0.98)' : 'rgba(255, 255, 255, 0.98)',
                color: isDarkMode ? '#f1f5f9' : '#111827',
                border: `1px solid ${isDarkMode ? '#334155' : '#e5e7eb'}`,
                borderRadius: '0.75rem',
                boxShadow: `0 5px 15px rgba(0, 0, 0, ${isDarkMode ? 0.3 : 0.1})`,
            },
        },
        labels: { text: { fill: isDarkMode ? '#e2e8f0' : '#374151', fontSize: 12, fontWeight: 600 } },
        legends: { text: { fill: isDarkMode ? '#e2e8f0' : '#4b5563', fontSize: 14 } },
    };

    // =====> CORREÇÃO APLICADA AQUI <=====
    // O div wrapper agora apenas ocupa 100% do espaço, sem usar flex.
    // A centralização já é feita pelo seu contêiner pai (.chart-wrapper).
    return (
        <div style={{ width: '100%', height: '100%' }}>
            <ResponsivePie
                data={chartData}
                theme={theme}
                margin={{ top: 40, right: 80, bottom: 80, left: 80 }}
                innerRadius={0.55}
                padAngle={1.5}
                cornerRadius={4}
                activeOuterRadiusOffset={10}
                colors={{ datum: 'data.color' }}
                borderWidth={1}
                borderColor={{ from: 'color', modifiers: [['darker', 0.4]] }}
                arcLinkLabelsSkipAngle={10}
                arcLinkLabelsTextColor={isDarkMode ? '#94a3b8' : '#6b7280'}
                arcLinkLabelsThickness={2}
                arcLinkLabelsColor={{ from: 'color' }}
                arcLabel={(d) => `${Number(d.value).toFixed(1)}%`}
                arcLabelsSkipAngle={15}
                arcLabelsTextColor={{ from: 'color', modifiers: [['darker', isDarkMode ? 4 : 2]] }}
                enableArcLinkLabels={true} // Recomendo habilitar para melhor visualização
                tooltip={({ datum: { id, value, color } }) => (
                    <div style={{ 
                        padding: '0.5rem 0.75rem', 
                        background: isDarkMode ? '#1e293b' : '#ffffff', 
                        border: `1px solid ${color}`, 
                        borderRadius: '0.375rem', 
                        display: 'flex', 
                        alignItems: 'center', 
                        boxShadow: `0 2px 5px rgba(0,0,0, ${isDarkMode ? 0.2 : 0.1})` 
                    }}>
                        <div style={{ 
                            width: 12, 
                            height: 12, 
                            backgroundColor: color, 
                            marginRight: '0.5rem', 
                            borderRadius: '50%' 
                        }} />
                        <strong>{id}</strong>: {Number(value).toFixed(2)}%
                    </div>
                )}
                legends={[
                    {
                        anchor: 'bottom',
                        direction: 'row',
                        justify: false,
                        translateX: 0,
                        translateY: 60,
                        itemsSpacing: 4,
                        itemWidth: 100,
                        itemHeight: 20,
                        itemTextColor: isDarkMode ? '#cbd5e1' : '#4b5563',
                        itemDirection: 'left-to-right',
                        itemOpacity: 1,
                        symbolShape: 'circle',
                        symbolSize: 14,
                        effects: [
                            {
                                on: 'hover',
                                style: {
                                    itemTextColor: isDarkMode ? '#ffffff' : '#000000'
                                }
                            }
                        ]
                    }
                ]}
                onClick={(node) => onSliceClick(node.data.originalKey as string)}
            />
        </div>
    );
};

export default AllocationChart;