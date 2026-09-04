package org.hackerkhu.hackerhp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <b>{@code @EnableScheduling}은 #339의 S3 고아 오브젝트 정리({@code OrphanObjectCleanupJob})가 쓴다</b> — 이
 * 저장소에서 유일한 {@code @Scheduled} 사용처다.
 */
@SpringBootApplication
@EnableScheduling
public class HackerHpApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(HackerHpApiApplication.class, args);
  }
}
