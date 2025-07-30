'use strict';

document.addEventListener('DOMContentLoaded', () => {

    // --- ELEMENTOS DO DOM ---
    const statusElement = document.getElementById('connection-status');
    const portfolioListElement = document.getElementById('portfolio-list');
    const addCryptoForm = document.getElementById('add-crypto-form');
    const addFixedIncomeForm = document.getElementById('add-fixed-income-form');
    const notificationsElement = document.getElementById('notifications');
    let stompClient = null;

    // --- FUNÇÕES DE UI E FEEDBACK ---

    /**
     * Mostra uma notificação na tela (substitui o alert).
     * @param {string} message - A mensagem a ser exibida.
     * @param {string} type - 'success' ou 'error'.
     */
    function showNotification(message, type = 'success') {
        const notification = document.createElement('div');
        notification.className = `notification ${type}`;
        notification.textContent = message;
        notificationsElement.appendChild(notification);

        // Força o navegador a aplicar o estilo inicial antes de adicionar a classe 'show'
        setTimeout(() => {
            notification.classList.add('show');
        }, 10);

        // Remove a notificação após 5 segundos
        setTimeout(() => {
            notification.classList.remove('show');
            setTimeout(() => notification.remove(), 500);
        }, 5000);
    }

    /**
     * Cria o HTML para um card de ativo com base nos dados.
     * @param {object} asset - O objeto de ativo vindo da API.
     * @returns {string} - O HTML do card.
     */
    function createAssetCardHTML(asset) {
        const netProfitClass = asset.netProfit >= 0 ? 'profit' : 'loss';
        const formatted = (value) => (value || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });

        return `
            <div class="asset-card" data-type="${asset.type}">
                <button class="delete-btn" data-id="${asset.id}" title="Remover ativo">×</button>
                <h3>
                    ${asset.name}
                    <span class="asset-type">${asset.type.replace('_', ' ')}</span>
                </h3>
                <p><span>Valor Investido:</span> <span>${formatted(asset.investedAmount)}</span></p>
                <p><span>Valor Bruto:</span> <span>${formatted(asset.grossValue)}</span></p>
                <p><span>Imposto (IR):</span> <span>${formatted(asset.incomeTax)}</span></p>
                <p><strong><span>Valor Líquido:</span> <span class="price">${formatted(asset.netValue)}</span></strong></p>
                <p><span>Lucro Líquido:</span> <span class="${netProfitClass}">${formatted(asset.netProfit)}</span></p>
                <p><span>Rentabilidade:</span> <span class="${netProfitClass}">${asset.profitability.toFixed(2)}%</span></p>
            </div>
        `;
    }

    // --- FUNÇÕES DE DADOS E API ---

    /**
     * Busca os dados do portfólio da API e renderiza na tela.
     */
    async function fetchAndRenderPortfolio() {
        try {
            const response = await fetch('/api/portfolio/detailed');
            if (!response.ok) throw new Error('Falha ao buscar o portfólio.');

            const assets = await response.json();
            portfolioListElement.innerHTML = ''; // Limpa a lista

            if (assets.length === 0) {
                portfolioListElement.innerHTML = '<p>Seu portfólio está vazio. Adicione um ativo!</p>';
                return;
            }

            assets.forEach(asset => {
                portfolioListElement.innerHTML += createAssetCardHTML(asset);
            });
        } catch (error) {
            console.error(error);
            portfolioListElement.innerHTML = '<p style="color:red;">Erro ao carregar os ativos.</p>';
        }
    }

    /**
     * Envia uma requisição genérica para a API e lida com a resposta.
     * @param {string} url - O endpoint da API.
     * @param {string} method - O método HTTP (POST, DELETE).
     * @param {object} [body=null] - O corpo da requisição para POST.
     * @returns {boolean} - True se a operação foi bem-sucedida.
     */
    async function apiRequest(url, method, body = null) {
        try {
            const options = {
                method: method,
                headers: { 'Content-Type': 'application/json' },
            };
            if (body) {
                options.body = JSON.stringify(body);
            }

            const response = await fetch(url, options);

            if (response.ok) {
                return true;
            } else {
                const errorData = await response.json();
                // Tenta extrair a mensagem de erro da validação do Spring Boot
                const errorMessage = errorData.defaultMessage || errorData.message || 'Erro desconhecido.';
                showNotification(`Erro: ${errorMessage}`, 'error');
                return false;
            }
        } catch (error) {
            console.error(error);
            showNotification('Erro de conexão com o servidor.', 'error');
            return false;
        }
    }

    // --- LÓGICA DE WEBSOCKET ---

    function connectWebSocket() {
        stompClient = Stomp.over(new SockJS('/ws'));
        stompClient.debug = null; // Desativa logs verbosos do STOMP
        stompClient.connect({},
            () => { // onConnected
                statusElement.textContent = 'Conectado! Preços de cripto atualizados em tempo real.';
                // Ao receber qualquer atualização de preço, renderiza o portfólio todo.
                // Isso também atualizará os valores de Renda Fixa, pois eles são baseados no tempo.
                stompClient.subscribe('/topic/crypto-prices', () => fetchAndRenderPortfolio());
            },
            () => { // onError
                statusElement.textContent = 'Desconectado. Tentando reconectar...';
                setTimeout(connectWebSocket, 5000); // Tenta reconectar a cada 5 segundos
            }
        );
    }

    // --- EVENT LISTENERS ---

    addCryptoForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        const success = await apiRequest('/api/portfolio/crypto', 'POST', {
            ticker: document.getElementById('crypto-ticker').value,
            quantity: parseFloat(document.getElementById('crypto-quantity').value),
            investedAmount: parseFloat(document.getElementById('crypto-invested').value)
        });
        if (success) {
            addCryptoForm.reset();
            showNotification('Criptoativo adicionado com sucesso!');
            fetchAndRenderPortfolio();
        }
    });

    addFixedIncomeForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        const success = await apiRequest('/api/portfolio/fixed-income', 'POST', {
            name: document.getElementById('fi-name').value,
            investedAmount: parseFloat(document.getElementById('fi-invested').value),
            investmentDate: document.getElementById('fi-investment-date').value,
            maturityDate: document.getElementById('fi-maturity-date').value,
            indexType: document.getElementById('fi-index-type').value,
            contractedRate: parseFloat(document.getElementById('fi-rate').value)
        });
        if (success) {
            addFixedIncomeForm.reset();
            showNotification('Ativo de Renda Fixa adicionado com sucesso!');
            fetchAndRenderPortfolio();
        }
    });

    // Delegação de Evento para os botões de deletar. Mais eficiente que adicionar um listener por botão.
    portfolioListElement.addEventListener('click', async (event) => {
        if (event.target.classList.contains('delete-btn')) {
            const assetId = event.target.getAttribute('data-id');
            if (confirm(`Tem certeza que deseja remover o ativo ${assetId}?`)) {
                const success = await apiRequest(`/api/portfolio/${assetId}`, 'DELETE');
                if (success) {
                    showNotification('Ativo removido com sucesso!');
                    fetchAndRenderPortfolio();
                }
            }
        }
    });

    // --- PONTO DE ENTRADA DA APLICAÇÃO ---
    fetchAndRenderPortfolio();
    connectWebSocket();
});