package org.hackerkhu.hackerhp.domain.user.dto;

import org.hackerkhu.hackerhp.domain.user.entity.User;

/**
 * 작성자 자리에 넣는 <b>표시 이름</b> (spec 2-1 §2-1-1, 3-2 §3-2-2, 3-3 결정 18).
 *
 * <p>이름 뒤에 <b>학번 끝 두 글자</b>를 구분자 없이 붙인다 — {@code 권승원66}. 동아리에 이름이 같은 부원이 있으면 <b>누가 올린 것인지 알 방법이
 * 없었다.</b>
 *
 * <p><b>만드는 곳은 여기 하나다</b> (MUST). 자료·공지·활동사진·자유 게시판이 모두 이것만 쓴다 — 도메인마다 조립하면 규칙을 바꿀 때 한 곳이 빠지고, 그러면
 * <b>같은 사람이 화면마다 다른 이름으로 보인다.</b>
 *
 * <p><b>{@link User}를 통째로 받는 것도 그 규칙의 일부다.</b> 이름과 학번을 따로 받으면 이름만 넘기고 학번을 빼먹는 호출이 컴파일된다.
 *
 * <table>
 *   <caption>표시 이름</caption>
 *   <tr><th>계정</th><th>표시 이름</th></tr>
 *   <tr><td>이름 {@code 권승원}, 학번 {@code 2021102466}</td><td>{@code 권승원66}</td></tr>
 *   <tr><td>학번이 비어 있다</td><td>{@code 권승원} — 붙이지 않는다</td></tr>
 *   <tr><td>학번이 한 글자</td><td>{@code 권승원1} — 있는 만큼</td></tr>
 *   <tr><td>계정이 없다 (탈퇴)</td><td>{@code 탈퇴한 회원}</td></tr>
 * </table>
 */
public final class DisplayName {

  private DisplayName() {}

  /**
   * @param user 계정. {@code null}이면 탈퇴한 회원이다
   */
  public static String of(User user) {
    if (user == null) {
      return WithdrawnMember.NAME;
    }
    return user.getName() + suffixOf(user.getStudentNo());
  }

  /**
   * 학번 끝 두 글자. 없거나 비었으면 빈 문자열이다.
   *
   * <p><b>숫자인지 따지지 않는다.</b> 학번에는 형식 제약이 없어(3-2 §3-2-3) 편입·교환학생처럼 형태가 다른 값이 들어올 수 있고, <b>여기서 하려는 일은
   * 구별이지 학번 해석이 아니다.</b> 형식을 요구하면 그런 계정만 표시가 달라진다.
   *
   * <p><b>코드포인트로 센다</b> (MUST, T-434). {@code substring(length - 2)}는 UTF-16 코드 유닛을 자르므로, BMP 밖
   * 문자(이모지 등)가 걸리면 <b>서로게이트 쌍을 반으로 쪼개</b> 깨진 글자를 내보낸다. 형식이 없는 이상 그런 값도 저장될 수 있다 — 공백만 지우는 정규화는 이모지를
   * 걸러내지 않는다.
   */
  private static String suffixOf(String studentNo) {
    if (studentNo == null || studentNo.isBlank()) {
      return "";
    }
    int[] codePoints = studentNo.codePoints().toArray();
    int from = Math.max(0, codePoints.length - 2);
    return new String(codePoints, from, codePoints.length - from);
  }
}
