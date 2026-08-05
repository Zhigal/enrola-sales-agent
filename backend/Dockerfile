FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -DskipTests package
CMD ["java", "-jar", "target/agent-0.0.1-SNAPSHOT.jar"]
