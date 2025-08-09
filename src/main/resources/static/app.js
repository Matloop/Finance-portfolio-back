'use strict';

document.addEventListener('DOMContentLoaded', () => {

    // --- SELETORES DO DOM ---
    const portfolioListElement = document.getElementById('portfolio-list');
    const transactionForm = document.getElementById('transaction-form');
    const addFixedIncomeForm = document.getElementById('add-fixed-income-form');

    // --- FUNÇÕES DE RENDERIZAÇÃO ---

    /**
     * Renderiza o card para um ativo transacional (Ação ou Cripto).
     */
    const createTransactionalAssetCard = (asset) => {
        const profitClass = asset.profitOrLoss >= 0 ? 'profit' : 'loss';
        return `
            <div class="card asset-card ${asset.assetType}" data-id="${asset.id}" data-type="transactional">
                <button class="delete-btn" title="Remover todas as transações deste ativo">×</button>
                <h3>${asset.ticker} <span>(${asset.assetType})</span></h3>
                <p><span>Quantidade:</span> <strong>${asset.totalQuantity.toFixed(6)}</strong></p>
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
            // Este único endpoint no backend já deve retornar a lista unificada.
            const response = await fetch('/api/portfolio');
            if (!response.ok) throw new Error('Falha ao buscar dados do portfólio.');

            const positions = await response.json();
            portfolioListElement.innerHTML = '';

            if (positions.length === 0) {
                portfolioListElement.innerHTML = '<p>Nenhuma posição na carteira. Registre uma transação ou adicione um ativo.</p>';
                return;
            }

            positions.forEach(pos => {
                // Lógica para decidir qual card renderizar
                if (pos.assetType === 'STOCK' || pos.assetType === 'CRYPTO') {
                    portfolioListElement.innerHTML += createTransactionalAssetCard(pos);
                } else { // Assume que qualquer outra coisa é Renda Fixa
                    portfolioListElement.innerHTML += createFixedIncomeAssetCard(pos);
                }
            });
        } catch (error) {
            console.error('Falha ao renderizar portfólio:', error);
            portfolioListElement.innerHTML = '<p style="color:red;">Erro ao carregar o portfólio.</p>';
        }
    };

    // --- EVENT LISTENERS ---

    // Listener para o formulário de transações (Ações/Cripto)
    transactionForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const data = {
            ticker: document.getElementById('ticker').value,
            assetType: document.getElementById('assetType').value,
            transactionType: document.getElementById('transactionType').value,
            quantity: document.getElementById('quantity').value,
            pricePerUnit: document.getElementById('pricePerUnit').value,
            transactionDate: document.getElementById('transactionDate').value
        };

        const response = await fetch('/api/transactions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });

        if (response.ok) {
            transactionForm.reset();
            fetchAndRenderPortfolio();
        } else {
            alert('Erro ao registrar transação.');
        }
    });

    // Listener para o formulário de Renda Fixa
    addFixedIncomeForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const data = {
            name: document.getElementById('fi-name').value,
            investedAmount: document.getElementById('fi-invested').value,
            investmentDate: document.getElementById('fi-investment-date').value,
            maturityDate: document.getElementById('fi-maturity-date').value,
            indexType: document.getElementById('fi-index-type').value,
            contractedRate: document.getElementById('fi-rate').value,
        };

        // Assumindo que seu backend tem este endpoint
        const response = await fetch('/api/fixed-income', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });

        if (response.ok) {
            addFixedIncomeForm.reset();
            fetchAndRenderPortfolio();
        } else {
            alert('Erro ao adicionar ativo de Renda Fixa.');
        }
    });

    // Listener para deleção (delegação de evento)
    portfolioListElement.addEventListener('click', async (e) => {
        if (e.target.classList.contains('delete-btn')) {
            const card = e.target.closest('.asset-card');
            const id = card.dataset.id;
            const type = card.dataset.type; // 'transactional' ou 'fixed_income'

            let confirmMessage = `Tem certeza que deseja remover este ativo (ID: ${id})?`;
            if (type === 'transactional') {
                confirmMessage += "\n\nAVISO: Isso removerá TODAS as transações associadas a este ticker.";
            }

            if (confirm(confirmMessage)) {
                // Assumindo que seu backend tem endpoints de deleção separados
                const url = type === 'transactional' ? `/api/transactions/ticker/${id}` : `/api/fixed-income/${id}`;
                const response = await fetch(url, { method: 'DELETE' });

                if (response.ok) {
                    fetchAndRenderPortfolio();
                } else {
                    alert('Falha ao remover o ativo.');
                }
            }
        }
    });

    // --- PONTO DE ENTRADA ---
    fetchAndRenderPortfolio();
    setInterval(fetchAndRenderPortfolio, 60000); // Atualiza os dados a cada minuto
});