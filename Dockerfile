# --- Estágio 1: Build da Aplicação ---
# Esta imagem do Maven baseada em Temurin está correta e funcionando.
FROM public.ecr.aws/docker/library/maven:3.9-eclipse-temurin-17 AS build

# Define o diretório de trabalho dentro do contêiner.
WORKDIR /app

# Otimização de cache do Docker
COPY pom.xml .
RUN mvn dependency:go-offline

# Copia o código-fonte e executa o build.
COPY src ./src
RUN mvn clean package -DskipTests


# --- Estágio 2: Criação da Imagem de Execução (Runtime) ---
# CORREÇÃO: Usamos a imagem oficial padrão do Amazon Corretto para Java 17.
# Ela é baseada no Amazon Linux, que é otimizado para a AWS (e funciona perfeitamente no Render).
FROM public.ecr.aws/amazoncorretto/amazoncorretto:17

# Define o diretório de trabalho.
WORKDIR /app

# Copia o arquivo .jar que foi gerado no Estágio 1 para dentro da imagem final.
COPY --from=build /app/target/*.jar app.jar

# Expõe a porta 8080.
EXPOSE 8080

# O comando que será executado quando o contêiner iniciar.
ENTRYPOINT ["java", "-jar", "app.jar"]