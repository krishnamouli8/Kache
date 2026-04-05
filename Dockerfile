FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache Maven dependencies separately for faster rebuilds
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build the fat JAR (tests skipped for Docker build speed)
COPY src ./src
RUN mvn package -DskipTests -B

# --- Runtime stage: minimal JRE image ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy the shaded fat JAR from the build stage
COPY --from=build /app/target/kache-1.0.jar kache.jar

# TCP protocol port + HTTP API port
EXPOSE 7379 8080

# Run the cache server
CMD ["java", "-jar", "kache.jar"]
