plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.9.0"
}

group = "org.hackerkhu"
version = "0.0.1-SNAPSHOT"
description = "hacker_HP API"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    // Boot BOM이 관리하지 않아 버전을 직접 적는다. Boot 3.5는 springdoc 2.8.x다.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")
    // 세션은 RDS에 둔다. Fargate Spot이라 메모리에 두면 회수 시 전원 로그아웃된다 (3-1 §3-1-5).
    implementation("org.springframework.session:spring-session-jdbc")
    // 파일 바이트는 서버를 거치지 않는다 (2-1 §2-1-2 MUST). presigned URL 발급과
    // 업로드 뒤 크기 검증에만 쓴다. BOM으로 묶어 모듈 버전이 갈리지 않게 한다.
    implementation(platform("software.amazon.awssdk:bom:2.54.2"))
    implementation("software.amazon.awssdk:s3")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // 활동사진 업로드(#57) — presigned URL 발급·원본 다운로드용 S3 클라이언트.
    implementation(platform("software.amazon.awssdk:bom:2.46.18"))
    implementation("software.amazon.awssdk:s3")
    // 리사이즈(가로 최대 1920px, JPEG 품질 85) — spec 2-1 §2-1-7.
    implementation("net.coobird:thumbnailator:0.4.21")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // S3 흐름(업로드→리사이즈→정리)을 진짜 오브젝트 스토어로 검증한다 — Postgres를
    // Testcontainers로 검증하는 것과 같은 이유다.
    testImplementation("org.testcontainers:minio")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}
