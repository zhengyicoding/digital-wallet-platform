FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY auth-service/pom.xml auth-service/
COPY wallet-service/pom.xml wallet-service/
COPY bank-simulator/pom.xml bank-simulator/
COPY notification-service/pom.xml notification-service/
COPY web/pom.xml web/
COPY notification-service/src/ notification-service/src/
RUN ./mvnw -B package -pl notification-service -am -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/notification-service/target/*.jar app.jar
USER app
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
