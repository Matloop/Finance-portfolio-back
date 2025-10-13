# CarteiraPro - Gerenciador de Carteira de Investimentos

Sistema completo para gerenciamento e análise de carteiras de investimentos, com busca de cotações em tempo real, cálculo de rentabilidade, visualização de alocação e acompanhamento da evolução do patrimônio.

## 📜 Tabela de Conteúdos

- [📂 Estrutura do Projeto](#-estrutura-do-projeto)
- [🚀 Como Rodar o Projeto](#-como-rodar-o-projeto)
- [📊 Funcionalidades](#-funcionalidades)
- [🏛️ Arquitetura da Solução](#️-arquitetura-da-solução)
- [🛠️ Tecnologias Utilizadas](#️-tecnologias-utilizadas)
- [☁️ Deploy no Render](#️-deploy-no-render)
- [✍️ Autores](#️-autores)

## 📂 Estrutura do Projeto

O projeto é um monorepo contendo um backend Spring Boot e um frontend Astro/React.

```
├── src/
│   ├── main/
│   │   ├── java/com/example/carteira/    # Código-fonte do Backend (Java)
│   │   │   ├── config/                   # Configurações (CORS, etc.)
│   │   │   ├── controller/               # Endpoints da API REST
│   │   │   ├── model/                    # Entidades JPA, Enums e DTOs
│   │   │   ├── repository/               # Interfaces Spring Data JPA
│   │   │   └── service/                  # Lógica de negócio e provedores de dados
│   │   └── resources/                    # Arquivos de configuração do Spring
│   │       ├── application.properties    # Seletor de perfil
│   │       └── application-*.properties  # Perfis de dev e prod
│   ├── components/                       # Componentes do Frontend (React)
│   │   ├── AddAssetModal/
│   │   ├── Assets/
│   │   ├── Dashboard/
│   │   ├── ...
│   │   └── DashboardApp.jsx              # Componente raiz da aplicação
│   ├── pages/                            # Páginas/Rotas do Frontend (Astro)
│   │   ├── index.astro                   # Landing Page
│   │   └── dashboard.astro               # Página principal da aplicação
│   └── layouts/                          # Layouts base do Astro
│
├── Dockerfile                            # Receita para containerizar o Backend
├── pom.xml                               # Dependências e build do Backend (Maven)
└── package.json                          # Dependências e scripts do Frontend (NPM)
```

## 🚀 Como Rodar o Projeto

Siga os passos abaixo para executar a aplicação completa em seu ambiente local.

### Pré-requisitos

- Java (JDK) 17+
- Maven 3.8+
- Node.js 18+
- Docker & Docker Compose (Recomendado para o banco de dados)
- Uma IDE (IntelliJ ou VS Code)

### 1. Clone o Repositório

```bash
git clone <URL_DO_SEU_REPOSITORIO>
cd <NOME_DO_REPOSITORIO>
```

### 2. Configuração do Backend

O backend precisa das chaves de API para funcionar.

1. Vá para a pasta `src/main/resources/`
2. Crie um arquivo chamado `application-local.properties`
3. Adicione suas chaves de API a este arquivo (ele será ignorado pelo Git):

```properties
# src/main/resources/application-local.properties
coinmarketcap.apikey=SUA_CHAVE_COINMARKETCAP
alphavantage.apikey=SUA_CHAVE_ALPHAVANTAGE
```

### 3. Execução (Duas Opções)

#### Opção A: Rodando os Serviços Separadamente (Recomendado para Desenvolvimento)

**Inicie o Banco de Dados (PostgreSQL via Docker):**

(Opcional) Crie um `docker-compose.yml` para o banco:

```yaml
version: '3.8'
services:
  db:
    image: postgres:15-alpine
    environment:
      POSTGRES_USER: user
      POSTGRES_PASSWORD: password
      POSTGRES_DB: carteira
    ports:
      - "5432:5432"
```

Execute:

```bash
docker-compose up -d db
```

**Inicie o Backend (Spring Boot):**

Abra o projeto em sua IDE e execute a classe principal `CarteiraApplication.java`.

O backend estará disponível em **http://localhost:8080**.

**Inicie o Frontend (Astro/React):**

Abra um novo terminal na raiz do projeto e execute:

```bash
npm install
npm run dev
```

O frontend estará disponível em **http://localhost:4321**.

#### Opção B: Rodando Tudo com Docker (Simula a Produção)

1. Garanta que o `Dockerfile` existe na raiz do projeto
2. Execute o Docker Compose:

```bash
# Este comando irá construir a imagem do backend e iniciar o banco de dados
docker-compose up --build
```

Você ainda precisará rodar o frontend separadamente (`npm run dev`).

## 📊 Funcionalidades

### 📊 Dashboard Completo
Visualização consolidada de Patrimônio, Valor Investido e Rentabilidade.

### 🥧 Gráfico de Alocação Interativo
Gráfico de pizza com funcionalidade de "drill-down" para analisar a alocação por país, tipo de ativo e ativo individual.

### 📈 Evolução do Patrimônio
Gráfico de barras empilhadas mostrando o crescimento do valor aplicado e do ganho de capital nos últimos 12 meses.

### 📋 Lista de Ativos Hierárquica
Visualização de todos os ativos organizada em abas (Brasil, EUA, Cripto) e seções expansíveis (Ações, ETFs, Renda Fixa).

### 🔍 Busca de Ativos
Campo de busca interativo no modal de adição, com sugestões e preenchimento automático de cotação.

### ↔️ Importação e Exportação de CSV
Funcionalidades para exportar todas as transações para um arquivo CSV e importar dados a partir de um arquivo.

### 🌙 Tema Claro e Escuro
Interface adaptável para preferência de tema.

## 🏛️ Arquitetura da Solução

O sistema foi desenhado com uma arquitetura de microsserviços desacoplada, ideal para deploy na nuvem.

### Backend (Java / Spring Boot)

- **API REST**: Expõe os endpoints para o frontend
- **Arquitetura de Múltiplos Provedores**: A busca de dados de mercado é feita por múltiplos "provedores" (`MarketDataProvider`). Isso permite:
  - **Fallback**: Se um provedor falha (ex: CoinMarketCap), o sistema tenta o próximo (WebScraperService)
  - **Especialização**: Cada provedor é especialista em uma fonte de dados (API do CoinMarketCap, Web Scraping no Yahoo, API do BCB)
- **Dados Históricos**: Lógica robusta para "viajar no tempo", recalculando a carteira em diferentes datas para o gráfico de evolução
- **Banco de Dados**: Usa PostgreSQL para produção e H2 em memória para desenvolvimento

### Frontend (Astro / React)

- **Astro**: Usado para renderização rápida no servidor (SSR) das páginas e para o roteamento (`/` e `/dashboard`)
- **Ilhas de React**: Todos os componentes interativos (DashboardApp, gráficos, modais) são renderizados no lado do cliente (`client:load`), provendo uma experiência de SPA (Single Page Application)

### Fluxo de Dados de Cotação

```
PortfolioService → MarketDataService (Orquestrador) → [Provedor A, Provedor B] (Especialistas)
```

## 🛠️ Tecnologias Utilizadas

### Backend
- Java 17
- Spring Boot 3
- Spring Data JPA
- Maven
- PostgreSQL
- H2

### Frontend
- Astro
- React
- JSX
- react-chartjs-2

### Busca de Dados
- Jsoup (Web Scraping)
- WebClient (APIs REST)

### Ambiente
- Docker
- Render

## ☁️ Deploy no Render

O projeto está configurado para deploy contínuo no Render com 3 serviços:

1. **Banco de Dados**: Um serviço de PostgreSQL gerenciado
2. **Backend (Web Service)**: Containerizado com Docker, lê as configurações do banco e as chaves de API das variáveis de ambiente
3. **Frontend (Static Site)**: Compilado com `npm run build`, publica a pasta `dist`. A variável de ambiente `VITE_API_BASE_URL` o conecta ao backend

## ✍️ Autores

Projeto desenvolvido por Matheus Dias Estacio.

---

### 💼 CarteiraPro
*Gerenciamento inteligente de investimentos com análise em tempo real e visualizações interativas.*
