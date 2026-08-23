package org.hackerkhu.testsupport.storage;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 진짜 S3 대신 가짜를 쓰는 설정 (#53 D6).
 *
 * <p><b>한 곳에 두는 이유는 스프링 컨텍스트를 아끼기 위해서다.</b> 테스트 클래스마다 중첩 {@code @TestConfiguration}을 따로 선언하면 설정이
 * 같아도 <b>캐시 키가 달라져 컨텍스트가 한 벌씩 더 뜬다.</b> 그만큼 커넥션 풀도 한 벌씩 늘어, 컨테이너의 접속 상한에 먼저 부딪힌다.
 *
 * <p>{@code @MockitoBean}이 아니라 {@code @Primary} 빈인 이유는, 이 테스트들이 확인하려는 것이 <b>호출 여부가 아니라 저장소에 무엇이
 * 남았는가</b>이기 때문이다.
 */
@TestConfiguration
public class FakeStorageConfig {

  @Bean
  @Primary
  public FakeFileStorage fakeFileStorage() {
    return new FakeFileStorage();
  }
}
