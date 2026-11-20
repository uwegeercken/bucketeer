FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-noble
# wget is installed by the base image but unused at runtime
RUN apt-get purge -y wget && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/bucketeer-*.jar app.jar
EXPOSE 8444
# Java4Shell (CVE-2026-114938): disable the AVX-512 Base64 intrinsic until the base image ships a
# fixed JDK (Temurin 21.0.13.1 / 21.0.14+). Harmless on ARM and on patched JDKs; Bucketeer
# decodes hardly any Base64, so there is no measurable slowdown.
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "-jar", "app.jar"]
