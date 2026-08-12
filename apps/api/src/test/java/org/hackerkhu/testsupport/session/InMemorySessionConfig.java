package org.hackerkhu.testsupport.session;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;

/**
 * 세션 자동설정을 끈 테스트 컨텍스트에 세션 저장소를 대신 넣는다.
 *
 * <p><b>왜 필요한가.</b> 일부 통합 테스트는 {@code SessionAutoConfiguration}을 제외한다 — 그러지 않으면 {@code
 * SessionRepositoryFilter}가 {@code MockHttpSession}을 무시해 인증이 성립하지 않는다. 그런데 제외하면 세션 저장소 빈도 함께 사라져,
 * 그것을 요구하는 {@code SessionSynchronizer}가 뜨지 못한다.
 *
 * <p><b>운영 코드를 무르게 만들지 않는다.</b> 저장소를 선택 의존성으로 바꾸면 설정이 빠졌을 때 세션 반영이 <b>조용히 아무 일도 하지 않게</b> 된다 — 정지가
 * 반영되지 않는데 어디에서도 오류가 나지 않는다. 그래서 운영은 그대로 요구하게 두고, 테스트 쪽이 대역을 넣는다.
 *
 * <p>이 대역으로는 세션 반영을 검증할 수 없다(그 테스트들은 {@code MockHttpSession}을 쓴다). 실제 확인은 진짜 저장소를 쓰는 {@code
 * SessionSynchronizationIntegrationTest}가 한다.
 */
@TestConfiguration
public class InMemorySessionConfig {

  @Bean
  public FindByIndexNameSessionRepository<MapSession> sessionRepository() {
    return new InMemorySessions();
  }

  static class InMemorySessions implements FindByIndexNameSessionRepository<MapSession> {

    private final Map<String, MapSession> stored = new ConcurrentHashMap<>();

    @Override
    public MapSession createSession() {
      MapSession session = new MapSession();
      session.setMaxInactiveInterval(Duration.ofMinutes(30));
      return session;
    }

    @Override
    public void save(MapSession session) {
      stored.put(session.getId(), session);
    }

    @Override
    public MapSession findById(String id) {
      return stored.get(id);
    }

    @Override
    public void deleteById(String id) {
      stored.remove(id);
    }

    @Override
    public Map<String, MapSession> findByIndexNameAndIndexValue(
        String indexName, String indexValue) {
      if (!PRINCIPAL_NAME_INDEX_NAME.equals(indexName)) {
        return Map.of();
      }
      Map<String, MapSession> found = new LinkedHashMap<>();
      stored.forEach(
          (id, session) -> {
            if (indexValue.equals(session.getAttribute(PRINCIPAL_NAME_INDEX_NAME))) {
              found.put(id, session);
            }
          });
      return found;
    }
  }
}
