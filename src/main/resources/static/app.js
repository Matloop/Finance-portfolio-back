'use strict';

document.addEventListener('DOMContentLoaded', () => {

    // --- SELETORES DO DOM ---
    const portfolioListElement = document.getElementById('portfolio-list');
    const transactionForm = document.getElementById('transaction-form');
    const addFixedIncomeForm = document.getElementById('add-fixed-income-form');
    const refreshButton = document.getElementById('refresh-btn');

    // --- FUNÇÕES DE RENDERIZAÇÃO ---

    /**
     * Renderiza o card para um ativo transacional (Ação ou Cripto).
     */
    const createTransactionalAssetCard = (asset) => {
        const profitClass = asset.profitOrLoss >= 0 ? 'profit' : 'loss';
        return `
            <div class="card asset-card ${asset.assetType}" data-id="${asset.ticker}" data-type="transactional">
                <button class="delete-btn" title="Remover todas as transações deste ativo">×</button>
                <h3>${asset.ticker} <span>(${asset.assetType})</span></h3>
                <p><span>Quantidade:</span> <strong>${asset.totalQuantity.toFixed(8)}</strong></p>
                <p><span>Preço Médio:</span> <strong>R$ ${asset.averagePrice.toFixed(2)}</strong></p>
                <p><span>Total Investido:</span> <strong>R$ ${asset.totalInvested.toFixed(2)}</strong></p>
                <hr>
                <p><span>Valor Atual:</span> <strong>R$ ${asset.currentValue.toFixed(2)}</strong></p>
                <p><span>Lucro/Prejuízo:</span> <strong class="${profitClass}">R$ ${asset.profitOrLoss.toFixed(2)}</strong></p>
                <p><span>Rentabilidade:</span> <strong class="${profitClass}">${asset.profitability.toFixed(2)}%</strong></p>
            </div>
        `;
    };

    /**
     * Renderiza o card para um ativo de Renda Fixa.
     */
    const createFixedIncomeAssetCard = (asset) => {
        const profitClass = asset.profitOrLoss >= 0 ? 'profit' : 'loss';
        return `
            <div class="card asset-card FIXED_INCOME" data-id="${asset.id}" data-type="fixed_income">
                <button class="delete-btn" title="Remover ativo de renda fixa">×</button>
                <h3>${asset.ticker} <span>(RENDA FIXA)</span></h3>
                <p><span>Total Investido:</span> <strong>R$ ${asset.totalInvested.toFixed(2)}</strong></p>
                <hr>
                <p><span>Valor Líquido Atual:</span> <strong>R$ ${asset.currentValue.toFixed(2)}</strong></p>
                <p><span>Lucro Líquido:</span> <strong class="${profitClass}">R$ ${asset.profitOrLoss.toFixed(2)}</strong></p>
                <p><span>Rentabilidade Líquida:</span> <strong class="${profitClass}">${asset.profitability.toFixed(2)}%</strong></p>
            </div>
        `;
    };

    // --- FUNÇÃO PRINCIPAL DE DADOS ---

    const fetchAndRenderPortfolio = async () => {
        try {
            const response = await fetch('/api/portfolio');
            if (!response.ok) {
                throw new Error(`Falha ao buscar dados. Status: ${response.status}`);
            }

            const positions = await response.json();
            portfolioListElement.innerHTML = '';

            if (positions.length === 0) {
                portfolioListElement.innerHTML = '<p>Nenhuma posição na carteira. Registre uma transação ou adicione um ativo.</p>';
                return;
            }

            positions.sort((a, b) => b.totalInvested - a.totalInvested);

            positions.forEach(pos => {
                if (pos.assetType === 'STOCK' || pos.assetType === 'CRYPTO') {
                    portfolioListElement.innerHTML += createTransactionalAssetCard(pos);
                } else {
                    portfolioListElement.innerHTML += createFixedIncomeAssetCard(pos);
                }
            });
        } catch (error) {
            console.error('Falha ao renderizar portfólio:', error);
            portfolioListElement.innerHTML = `<p style="color:red;">Erro ao carregar o portfólio: ${error.message}</p>`;
        }
    };

    // --- EVENT LISTENERS ---

    transactionForm.addEventListener('submit', async (e) => {
        e.preventDefault();

        const data = {
            ticker: document.getElementById('ticker').value.toUpperCase(),
            assetType: document.getElementById('assetType').value,
            transactionType: document.getElementById('transactionType').value,
            quantity: parseFloat(document.getElementById('quantity').value),
            pricePerUnit: parseFloat(document.getElementById('pricePerUnit').value),
            transactionDate: document.getElementById('transactionDate').value
        };

        if (!data.ticker || !data.quantity || !data.pricePerUnit || !data.transactionDate) {
            alert('Por favor, preencha todos os campos obrigatórios.');
            return;
        }

        try {
            const response = await fetch('/api/transactions', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });

            if (response.ok) {
                transactionForm.reset();
                await fetchAndRenderPortfolio();
            } else {
                const errorData = await response.json().catch(() => ({}));
                alert(`Erro ao registrar transação: ${errorData.message || response.statusText}`);
            }
        } catch (error) {
            console.error('Falha na requisição:', error);
            alert('Falha de comunicação com o servidor.');
        }
    });

    addFixedIncomeForm.addEventListener('submit', async (e) => {
        e.preventDefault();

        const data = {
            name: document.getElementById('fi-name').value,
            investedAmount: parseFloat(document.getElementById('fi-invested').value),
            investmentDate: document.getElementById('fi-investment-date').value,
            maturityDate: document.getElementById('fi-maturity-date').value,
            indexType: document.getElementById('fi-index-type').value,
            contractedRate: parseFloat(document.getElementById('fi-rate').value),
        };

        if(!data.name || !data.investedAmount || !data.investmentDate || !data.maturityDate || !data.contractedRate) {
            alert("Por favor, preencha todos os campos de Renda Fixa.");
            return;
        }

        try {
            const response = await fetch('/api/fixed-income', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });

            if (response.ok) {
                addFixedIncomeForm.reset();
                await fetchAndRenderPortfolio();
            } else {
                const errorData = await response.json().catch(() => ({}));
                alert(`Erro ao adicionar Renda Fixa: ${errorData.message || response.statusText}`);
            }
        } catch (error) {
            console.error('Falha na requisição:', error);
            alert('Falha de comunicação com o servidor.');
        }
    });

    portfolioListElement.addEventListener('click', async (e) => {
        if (e.target.classList.contains('delete-btn')) {
            const card = e.target.closest('.asset-card');
            const id = card.dataset.id;
            const type = card.dataset.type;
            let url;
            let confirmMessage = `Tem certeza que deseja remover este ativo (${id})?`;

            if (type === 'transactional') {
                url = `/api/transactions/ticker/${id}`;
                confirmMessage = `Tem certeza que deseja remover TODAS as transações do ticker ${id}? Esta ação não pode ser desfeita.`;
            } else {
                url = `/api/fixed-income/${id}`;
            }

            if (confirm(confirmMessage)) {
                try {
                    const response = await fetch(url, { method: 'DELETE' });
                    if (response.ok) {
                        await fetchAndRenderPortfolio();
                    } else {
                        alert(`Falha ao remover o ativo. Status: ${response.status}`);
                    }
                } catch (error) {
                    console.error('Falha na requisição de deleção:', error);
                    alert('Falha de comunicação com o servidor.');
                }
            }
        }
    });

    refreshButton.addEventListener('click', async () => {
        refreshButton.disabled = true;
        refreshButton.textContent = 'Atualizando...';

        try {
            const response = await fetch('/api/portfolio/refresh', { method: 'POST' });
            if (!response.ok) throw new Error('Falha ao solicitar a atualização.');

            console.log('Solicitação de refresh enviada. Atualizando a visualização em 2 segundos...');
            setTimeout(() => {
                fetchAndRenderPortfolio();
            }, 2000);

        } catch (error) {
            console.error('Erro no refresh:', error);
            alert('Não foi possível atualizar as cotações.');
        } finally {
            setTimeout(() => {
                refreshButton.disabled = false;
                refreshButton.textContent = 'Atualizar Cotações';
            }, 2000);
        }
    });

    // --- PONTO DE ENTRADA ---
    fetchAndRenderPortfolio();
});