FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/versao-java-1.0.0.jar app.jar
COPY dados_alunos.json ./

ENV PORT=8080
EXPOSE 8080

CMD ["java", "-Dfile.encoding=UTF-8", "-jar", "app.jar"]
