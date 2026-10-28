# Build the jar first:  mvn -DskipTests package
FROM eclipse-temurin:21-jre

WORKDIR /app
RUN useradd --create-home --shell /usr/sbin/nologin app
COPY target/rag-assistant.jar app.jar
USER app

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s \
    CMD wget -qO- http://localhost:8000/health >/dev/null 2>&1 || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
