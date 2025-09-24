# --- Estágio 1: Build da Aplicação ---
# CORREÇÃO: Usamos a imagem espelhada do Amazon ECR Public para evitar limites do Docker Hub.
FROM public.ecr.aws/docker/library/maven:3.9-eclipse-temurin-17 AS build

# Define o diretório de trabalho dentro do contêiner.
WORKDIR /app

# Copia os arquivos de definição do projeto (pom.xml) primeiro.
COPY pom.xml .

# Baixa todas as dependências do projeto.
RUN mvn dependency:go-offline

# Copia o código-fonte da sua aplicação para dentro do contêiner.
COPY src ./src

# Executa o build do Maven, que compila o código e empacota em um arquivo .jar.
RUN mvn clean package -DskipTests


# --- Estágio 2: Criação da Imagem de Execução (Runtime) ---
# CORREÇÃO: Também usamos a imagem JRE espelhada do Amazon ECR Public.
FROM public.ecr.aws/eclipse-temurin/temurin:17-jre-jammy

# Define o diretório de trabalho.
WORKDIR /app

# Copia o arquivo .jar que foi gerado no Estágio 1 para dentro da imagem final.
COPY --from=build /app/target/*.jar app.jar

# Expõe a porta 8080.
EXPOSE 8080

# O comando que será executado quando o contêiner iniciar.
ENTRYPOINT ["java", "-jar", "app.jar"]