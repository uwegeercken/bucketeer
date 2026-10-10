FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=build /app/target/bucketeer-*.jar app.jar
EXPOSE 8444
ENTRYPOINT ["java", "-jar", "app.jar"]
