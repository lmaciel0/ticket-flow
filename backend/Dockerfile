# Stage 1: build the jar with the Maven Wrapper (JDK image).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# Stage 2: CDS (Class Data Sharing). A training run starts Spring once, without a database,
# and exits right after startup; the JVM saves the classes it loaded into app.jsa. Reading them
# back from that archive cut the startup from ~190 s to ~60 s on Render's free plan (0.1 CPU).
FROM eclipse-temurin:21-jre AS cds
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination application
WORKDIR /app/application
# Same JVM flags as the final image, so the archive is always accepted at runtime.
ENV JAVA_TOOL_OPTIONS="-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k"
RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh \
      -Dspring.flyway.enabled=false -Dspring.jpa.hibernate.ddl-auto=none \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      -jar app.jar

# Stage 3: run on a JRE image, as a non-root user.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=cds /app/application ./
USER app
EXPOSE 8080
# Tuned for the free host (512 MB, 0.1 CPU): only the fast C1 compiler (quicker startup),
# Serial GC (no extra GC threads), smaller thread stacks, heap up to 75% of the memory,
# and the CDS archive from stage 2.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k -XX:SharedArchiveFile=app.jsa"
ENTRYPOINT ["java", "-jar", "app.jar"]
