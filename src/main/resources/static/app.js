'use strict';

const statusElement = document.getElementById('connection-status');
const btcPriceElement = document.getElementById('btc-price');
const ethPriceElement = document.getElementById('eth-price');

let stompClient = null;

function connect() {
    // Cria uma conexão usando SockJS para o endpoint que configuramos no backend
    const socket = new SockJS('/ws');
    stompClient = Stomp.over(socket);

    // Conecta ao broker STOMP
    stompClient.connect({}, onConnected, onError);
}

function onConnected() {
    statusElement.textContent = 'Conectado! Aguardando preços...';
    console.log('Conectado ao WebSocket');

    // Se inscreve no tópico "/topic/crypto-prices" para receber as atualizações
    stompClient.subscribe('/topic/crypto-prices', onPriceReceived);
}

function onError(error) {
    statusElement.textContent = 'Não foi possível conectar ao servidor WebSocket. Por favor, recarregue a página.';
    console.error('Erro na conexão WebSocket:', error);
}

function onPriceReceived(payload) {
    // O corpo do payload é uma string JSON, então precisamos fazer o parse
    const priceUpdate = JSON.parse(payload.body);

    const ticker = priceUpdate.ticker;
    const price = parseFloat(priceUpdate.price).toFixed(2);

    // Atualiza o elemento HTML correspondente ao ticker
    if (ticker === 'BTC') {
        btcPriceElement.textContent = `$ ${price}`;
    } else if (ticker === 'ETH') {
        ethPriceElement.textContent = `$ ${price}`;
    }
}

// Inicia a conexão quando o script é carregado
connect();