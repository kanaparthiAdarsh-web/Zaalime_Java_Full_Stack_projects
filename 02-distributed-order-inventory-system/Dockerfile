# One image definition for all three services; MODULE picks which jar goes in.
# Build the jars first:  mvn -DskipTests package
FROM eclipse-temurin:21-jre-alpine

ARG MODULE
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY ${MODULE}/target/${MODULE}.jar app.jar
USER app

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
