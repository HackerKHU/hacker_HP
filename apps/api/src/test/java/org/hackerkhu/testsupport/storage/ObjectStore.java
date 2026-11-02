package org.hackerkhu.testsupport.storage;

import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 테스트용 S3 대역 (#407, 3-3 결정 31).
 *
 * <p><b>진짜 S3 동작을 재야 하는 테스트만 쓴다</b> — presigned PUT 직접 업로드, 서명 검증, {@code HeadObject}, etag 대조, 버킷
 * 나열·삭제. S3를 건드리지 않는 기능(좋아요 등)은 {@link FakeStorageConfig}로 가볍게 돈다.
 *
 * <p><b>왜 MinIO도 LocalStack도 아닌가.</b> 둘 다 자격증명 없이는 쓸 수 없게 됐다 (2026-11-02 확인).
 *
 * <ul>
 *   <li>MinIO — Docker Hub의 {@code minio/minio}가 사라지고(#402가 이때 {@code quay.io}로 옮겼다), 그 {@code
 *       quay.io}마저 익명 pull을 막았다. 익명 토큰은 발급되지만 그 토큰으로 manifest가 {@code 401}이다 — 같은 절차로 {@code
 *       quay.io/prometheus/prometheus}는 {@code 200}이라 레지스트리 장애가 아니다. {@code bitnami/minio}는 태그가 0개고
 *       {@code ghcr.io/minio/minio}는 {@code 403}이다.
 *   <li>LocalStack — 이미지는 받히지만 <b>뜨지 않는다.</b> {@code localstack/localstack:2026.10.4}은 {@code
 *       LOCALSTACK_AUTH_TOKEN}을 요구하며 종료 코드 {@code 55}로 죽는다 ({@code License activation failed}).
 * </ul>
 *
 * <p><b>S3Mock이 아닌 이유는 따로다.</b> 받히고 뜨지만 <b>인증을 전혀 검증하지 않는다</b> — 헤더 없이 버킷 생성·{@code PUT}·{@code
 * GET}이 모두 성공하는 것을 확인했다. {@code PhotoApiIntegrationTest}는 <b>서명이 틀리면 거부된다는 것에 기대어</b> 쓰여 있으므로, 그쪽으로
 * 가면 그 단정이 통과하면서 아무것도 재지 않는 상태가 된다 — 깨진 테스트보다 나쁘다.
 *
 * <p>SeaweedFS는 세 조건을 모두 만족한다. 신원을 주면 서명을 강제한다 — 인증 없는 요청과 엉터리 서명 모두 {@code 403}인 것을 확인했다.
 *
 * <p><b>{@code latest}를 쓰지 않는다</b> (결정 30이 세운 규칙). 떠 있는 태그는 우리가 아무것도 안 해도 어느 날 CI를 깨뜨린다. 올릴 때는 여기
 * 적힌 태그를 실제로 <b>받아서 띄워 보고</b> 바꾼다 — 받히는 것만 확인하면 LocalStack에서 겪은 실수를 반복한다.
 *
 * <p><b>컨테이너를 JVM 전체에서 하나만 쓴다.</b> 두 테스트 클래스가 이 클래스를 통해 같은 것을 공유한다 — 클래스마다 띄우면 무거운 이미지를 두 번 올린다.
 */
public final class ObjectStore {

  /** 기본 신원이 없으면 SeaweedFS는 모든 요청을 허용한다 — 그러면 서명 검증을 재지 못한다. */
  private static final String CONFIG = "objectstore-s3.json";

  public static final String ACCESS_KEY = "testkey";
  public static final String SECRET_KEY = "testsecret";
  public static final String REGION = "us-east-1";

  private static final int S3_PORT = 8333;

  private static final GenericContainer<?> CONTAINER =
      new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.51"))
          .withCopyFileToContainer(MountableFile.forClasspathResource(CONFIG), "/etc/s3.json")
          .withCommand("server", "-s3", "-s3.config=/etc/s3.json", "-dir=/data")
          .withExposedPorts(S3_PORT)
          // 포트가 열리는 것과 S3 API가 받을 준비가 된 것은 다르다 — 로그로 기다린다.
          .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*\\n", 1));

  static {
    CONTAINER.start();
  }

  private ObjectStore() {}

  /** {@code app.storage.endpoint}와 SDK의 {@code endpointOverride}에 함께 쓴다. */
  public static URI endpoint() {
    return URI.create("http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(S3_PORT));
  }
}
