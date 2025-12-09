// A URL base da sua API. Em um projeto real, isso viria de variáveis de ambiente.
// Use import.meta.env.VITE_API_URL se estiver usando Vite para gerenciar envs.
export const API_BASE_URL = 'http://localhost:8080';

/**
 * Uma função 'fetch' que automaticamente adiciona o token JWT de autenticação.
 * @param {string} url - A URL da API para a qual fazer a requisição (ex: '/api/portfolio/dashboard').
 * @param {object} options - Opções de fetch padrão (method, body, etc.).
 * @returns {Promise<Response>} - A promessa da resposta do fetch.
 */
export const fetchWithAuth = async (url, options = {}) => {
    const token = localStorage.getItem('jwt_token');

    const headers = {
        // Se o body for FormData, não definimos Content-Type para o navegador fazer isso
        ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
        ...options.headers,
    };

    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }

    const fullUrl = `${API_BASE_URL}${url}`;
    
    const response = await fetch(fullUrl, { ...options, headers });

    // Se o backend retornar 401 ou 403, o token é inválido/expirado, deslogar o usuário.
    if (response.status === 401 || response.status === 403) {
        localStorage.removeItem('jwt_token');
        window.location.href = '/?session_expired=true'; // Redireciona para a home
    }

    return response;
};