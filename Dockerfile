# --- Estágio 1: Build da Aplicação ---
# Usamos uma imagem oficial do Maven que já contém o JDK 17.
# Use a versão do Java correspondente à do seu projeto (ex: 17, 21).
FROM maven:3.9-eclipse-temurin-17 AS build

# Define o diretório de trabalho dentro do contêiner.
WORKDIR /app

# Copia os arquivos de definição do projeto (pom.xml) primeiro.
# Isso aproveita o cache do Docker. Se as dependências não mudarem,
# o Docker não precisará baixá-las novamente a cada build.
COPY pom.xml .

# Baixa todas as dependências do projeto.
RUN mvn dependency:go-offline

# Copia o código-fonte da sua aplicação para dentro do contêiner.
COPY src ./src

# Executa o build do Maven, que compila o código e empacota em um arquivo .jar.
# O -DskipTests pula a execução de testes unitários durante o build do Docker,
# o que é uma prática comum para acelerar o deploy.
RUN mvn clean package -DskipTests


# --- Estágio 2: Criação da Imagem de Execução (Runtime) ---
# Começamos com uma imagem base muito mais leve, que contém apenas o necessário para rodar Java.
# A tag 'jre' indica que é apenas o Java Runtime Environment.
FROM eclipse-temurin:17-jre-jammy

# Define o diretório de trabalho.
WORKDIR /app

# Copia o arquivo .jar que foi gerado no Estágio 1 para dentro da imagem final.
# O caminho do .jar é encontrado dentro da pasta 'target' do Maven.
# O nome do .jar pode variar, então usamos um curinga (*.jar).
COPY --from=build /app/target/*.jar app.jar

# Expõe a porta 8080, que é a porta padrão do Spring Boot.
# O Render usará isso para saber em qual porta sua aplicação está rodando.
EXPOSE 8080

# O comando que será executado quando o contêiner iniciar.
# Ele simplesmente executa o arquivo .jar da sua aplicação.
ENTRYPOINT ["java", "-jar", "app.jar"]