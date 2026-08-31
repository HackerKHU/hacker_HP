package org.hackerkhu.hackerhp.global.storage;

import java.net.URI;
import java.util.Optional;

/**
 * 파일 저장소 (spec 2-1 §2-1-2 MUST).
 *
 * <p><b>바이트를 주고받는 메서드가 대부분 없다.</b> 서버는 파일을 받지도 보내지도 않는다 — 브라우저가 S3와 직접 주고받고, 서버는 <b>그 문을 열어 주고 결과를
 * 확인할 뿐</b>이다. Vercel 프록시의 본문 제한(4.5MB)을 피하는 구조이기도 하다. {@link #download}·{@link #upload}는 예외다 —
 * 활동사진(#57)은 리사이즈를 위해 서버가 실제로 바이트를 만져야 한다(#213). 그 둘을 빼면 나머지는 여전히 "문을 열어 주는" 메서드다.
 *
 * <p>인터페이스를 두는 이유는 <b>테스트가 AWS 자격증명 없이 돌기 위해서다</b> (#53 D6). 우리가 지켜야 할 것은 발급 조건·크기 검증·정리 순서이지
 * presigned 서명 그 자체가 아니다 — 서명은 SDK가 한다. 실제 S3 연동은 배포 리허설(#48)의 수동 점검이 맡는다.
 */
public interface FileStorage {

  /**
   * 오브젝트의 크기와 <b>내용을 가리키는 표식</b>.
   *
   * <p>{@code etag}를 함께 돌려주는 이유는 <b>재고 나서 옮기는 사이에 그 오브젝트가 바뀔 수 있기 때문이다</b> (#207 리뷰). 발급한 presigned
   * URL은 만료까지 살아 있어 같은 키에 다시 올릴 수 있다 — 작은 것을 재게 하고 큰 것으로 갈아치우면 <b>크기 제한이 통째로 무력해진다.</b>
   *
   * @param sizeBytes 실제로 올라온 바이트 수
   * @param etag 그 순간의 내용을 가리킨다. {@link FileStorage#copyIfUnchanged}에 그대로 넘긴다
   */
  record StoredObject(long sizeBytes, String etag) {}

  /**
   * 그 키에 <b>올릴 수 있는</b> 임시 URL을 만든다.
   *
   * <p><b>용량을 강제하지 못한다</b> (2-1 §2-1-2). presigned PUT은 서명에 크기를 담지 않으므로, 20MB를 넘겨 올려도 S3는 받는다. 그래서
   * 등록 단계에서 {@link #describe}로 실제 크기를 확인한다.
   *
   * @param contentType 서명에 함께 실을 {@code Content-Type}. {@code null}이면 싣지 않는다 — 자료(#207)는 업로드하는 파일의
   *     형식이 다양해 강제하지 않고, 활동사진(#57)은 확장자별로 정해진 형식을 강제한다(#213 통합).
   */
  URI presignPut(String key, String contentType);

  /**
   * 그 키를 <b>내려받을 수 있는</b> 임시 URL을 만든다 (2-1 §2-1-4 MUST, #55).
   *
   * <p><b>파일명을 서명에 함께 넣는다.</b> S3 키는 {@code uuid}라, 그냥 받으면 사용자 디스크에 {@code 0f8c….pdf}로 저장된다. 프론트의
   * {@code <a download="…">}로는 고칠 수 없다 — <b>그 힌트는 다른 오리진 링크에서 브라우저가 무시하기 때문이다.</b> S3가 {@code
   * Content-Disposition}을 직접 내려주는 길뿐이다.
   *
   * <p><b>언제나 {@code attachment}다.</b> 자료 사이트의 기본 동작은 "받는" 것이고, {@code inline}이 필요하면 {@link
   * #presignGet(String)}을 쓴다.
   *
   * @param originalName 사용자에게 보일 이름. 한글이면 RFC 5987로 인코딩해 싣는다
   */
  URI presignGet(String key, String originalName);

  /**
   * 그 키를 <b>화면에 그대로 띄울 수 있는</b> 임시 URL을 만든다 (활동사진 #57, #213 통합).
   *
   * <p>{@code Content-Disposition}을 담지 않는다 — 자료 다운로드({@link #presignGet(String, String)})와 달리
   * {@code <img src>}가 바로 그리는 자리라 "받는" 동작을 강제하면 안 된다.
   */
  URI presignGet(String key);

  /**
   * 실제로 올라온 오브젝트를 잰다.
   *
   * <p>없으면 비어 있다 — <b>"올리지 않고 등록만 시도했다"와 "올렸다"를 가르는 유일한 방법</b>이다. 없는 것을 예외로 만들지 않는 이유는, 그것이 흔한
   * 클라이언트 실수이지 서버 장애가 아니기 때문이다.
   */
  Optional<StoredObject> describe(String key);

  /**
   * <b>잰 그 오브젝트가 맞을 때만</b> 옮긴다. 서버를 거치지 않는 복사다.
   *
   * @param expectedEtag {@link #describe}가 준 값. 그 사이에 내용이 바뀌었으면 옮기지 않는다
   * @return 옮겼으면 {@code true}, 내용이 바뀌었거나 사라졌으면 {@code false}
   */
  boolean copyIfUnchanged(String fromKey, String toKey, String expectedEtag);

  /**
   * 오브젝트를 지운다. 없는 키를 지워도 조용히 지나간다 — 정리 경로가 그 차이로 갈라질 이유가 없다.
   *
   * <p><b>그 밖의 실패는 던진다</b> (#207 리뷰). 최종 자리({@code notes/…})에는 만료 규칙이 없어, 정리 실패를 삼키면 <b>DB 행도 만료
   * 규칙도 없는 오브젝트가 영원히 쌓인다.</b> 삼킬지 말지는 부르는 쪽이 정한다.
   */
  void delete(String key);

  /**
   * 오브젝트를 통째로 내려받는다 (활동사진 #57, #213 통합).
   *
   * <p><b>다른 메서드와 달리 서버가 바이트를 실제로 만진다.</b> 리사이즈하려면 원본을 읽어야 하므로 불가피하다 — 자료(#207)는 이 메서드를 쓰지 않는다. 큰
   * 오브젝트를 그대로 메모리에 올리므로, 부르기 전에 {@link #describe}로 크기를 확인해야 한다 (2-1 §2-1-2).
   */
  byte[] download(String key);

  /**
   * 오브젝트를 통째로 올린다 (활동사진 #57, #213 통합).
   *
   * <p>{@link #download}와 한 쌍이다 — 리사이즈한 결과를 최종 키에 쓴다. 자료(#207)는 브라우저가 presigned PUT으로 직접 올리므로 이
   * 메서드를 쓰지 않는다.
   */
  void upload(String key, byte[] content, String contentType);
}
