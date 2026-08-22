package org.hackerkhu.hackerhp.global.storage;

import java.net.URI;
import java.util.OptionalLong;

/**
 * 파일 저장소 (spec 2-1 §2-1-2 MUST).
 *
 * <p><b>바이트를 주고받는 메서드가 없다.</b> 서버는 파일을 받지도 보내지도 않는다 — 브라우저가 S3와 직접 주고받고, 서버는 <b>그 문을 열어 주고 결과를 확인할
 * 뿐</b>이다. Vercel 프록시의 본문 제한(4.5MB)을 피하는 구조이기도 하다.
 *
 * <p>인터페이스를 두는 이유는 <b>테스트가 AWS 자격증명 없이 돌기 위해서다</b> (#53 D6). 우리가 지켜야 할 것은 발급 조건·크기 검증·정리 순서이지
 * presigned 서명 그 자체가 아니다 — 서명은 SDK가 한다. 실제 S3 연동은 배포 리허설(#48)의 수동 점검이 맡는다.
 */
public interface FileStorage {

  /**
   * 그 키에 <b>올릴 수 있는</b> 임시 URL을 만든다.
   *
   * <p><b>용량을 강제하지 못한다</b> (2-1 §2-1-2). presigned PUT은 서명에 크기를 담지 않으므로, 20MB를 넘겨 올려도 S3는 받는다. 그래서
   * 등록 단계에서 {@link #sizeOf}로 실제 크기를 확인한다.
   */
  URI presignPut(String key);

  /**
   * 실제로 올라온 오브젝트의 크기.
   *
   * <p>없으면 비어 있다 — <b>"올리지 않고 등록만 시도했다"와 "올렸다"를 가르는 유일한 방법</b>이다. 없는 것을 예외로 만들지 않는 이유는, 그것이 흔한
   * 클라이언트 실수이지 서버 장애가 아니기 때문이다.
   */
  OptionalLong sizeOf(String key);

  /** 서버를 거치지 않는 복사. 바이트가 우리 쪽으로 오지 않는다. */
  void copy(String fromKey, String toKey);

  /** 없는 키를 지워도 조용히 지나간다 — 정리 경로가 그 차이로 갈라질 이유가 없다. */
  void delete(String key);
}
