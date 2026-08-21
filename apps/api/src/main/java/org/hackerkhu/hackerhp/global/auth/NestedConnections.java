package org.hackerkhu.hackerhp.global.auth;

import java.util.concurrent.Semaphore;

/**
 * 커넥션을 <b>두 개 겹쳐 잡는</b> 작업의 동시 실행 상한 (#31 리뷰, #145).
 *
 * <p>계정 행을 잠근 트랜잭션이 커넥션 하나를 쥔 채, 세션 저장소가 자기 트랜잭션으로 하나를 더 연다 — {@code JdbcIndexedSessionRepository}는
 * 그 전파 방식을 생성자에서만 받아 바깥에서 바꿀 수 없다. <b>겹쳐 잡는 스레드가 풀 크기만큼 모이면 모두가 두 번째 커넥션을 기다리며 서로를 막는다.</b>
 *
 * <p>상한을 두는 것은 <b>우회이지 해결이 아니다</b> ({@code #145}). 그래도 한 곳에 모아 두는 이유는, 겹쳐 잡는 경로가 둘로 늘었기 때문이다 — 상태
 * 변경 반영({@link SessionSynchronizer})과 로그인 발급({@link LoginSessionIssuer})이 같은 풀을 나눠 쓴다. 각자 따로 세면 합이
 * 풀 크기를 넘길 수 있다.
 *
 * <p>풀 기본값이 10이므로 둘이면 넉넉하다. 로그인도 관리자 조작도 초당 몇 건이 아니어서 이 상한이 병목이 되지 않는다.
 */
final class NestedConnections {

  private static final Semaphore PERMITS = new Semaphore(2);

  private NestedConnections() {}

  /**
   * 자리가 날 때까지 기다렸다가 실행한다.
   *
   * @return 실행했으면 {@code true}. 기다리다 중단됐으면 {@code false} — 부르는 쪽이 실패로 다뤄야 한다.
   */
  static boolean run(Runnable work) {
    try {
      PERMITS.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    try {
      work.run();
      return true;
    } finally {
      PERMITS.release();
    }
  }
}
