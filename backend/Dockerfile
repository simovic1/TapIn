# ---- build ----
FROM eclipse-temurin:21.0.12.1_1-jdk-alpine AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src ./src
RUN ./mvnw -q -B -DskipTests package && cp target/tapin-backend-*.jar app.jar

# ---- runtime ----
FROM eclipse-temurin:21.0.12.1_1-jre-alpine
WORKDIR /app
RUN addgroup -S tapin && adduser -S tapin -G tapin
COPY --from=build /app/app.jar app.jar
USER tapin
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
