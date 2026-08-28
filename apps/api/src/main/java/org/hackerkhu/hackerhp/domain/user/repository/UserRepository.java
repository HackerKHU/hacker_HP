package org.hackerkhu.hackerhp.domain.user.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

  /**
   * 계정의 신원 키는 이메일이 아니라 {@code google_sub}다 (spec 3-2 §3-2-2).
   *
   * <p>학교 정책에 따라 이메일은 바뀔 수 있다. 이메일로 찾으면 주소가 바뀐 사람에게 새 계정이 생긴다.
   */
  Optional<User> findByGoogleSub(String googleSub);

  /**
   * 그 이메일을 쓰고 있는 <b>다른</b> 계정 (T-55). 같은 계정은 찾지 않는다.
   *
   * <p>존재 여부가 아니라 계정을 돌려준다. 충돌이 나면 <b>두 계정의 {@code id}를 로그에 남겨야</b> 관리자가 어느 쪽이 유효한지 판단해 정리할 수 있다
   * (spec 3-2 §3-2-2).
   */
  Optional<User> findByEmailAndGoogleSubNot(String email, String googleSub);

  /**
   * 행을 잠근 채 읽는다 ({@code SELECT ... FOR UPDATE}).
   *
   * <p><b>신청서 저장은 승인과 직렬화해야 한다</b> (spec 3-1 §3-1-4 MUST). 잠그지 않으면 두 트랜잭션이 각자 읽어둔 {@code status}를
   * 보고 모두 통과하고, 나중에 쓰는 쪽이 앞의 변경을 덮는다 — <b>관리자가 심사한 내용과 저장된 내용이 달라진다</b> (T-56).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> findByIdForUpdate(@Param("id") Long id);

  /** 그 학번을 쓰는 <b>다른</b> 계정이 있는지. 한 학번으로 여러 계정을 만드는 것을 막는다 (T-24). */
  boolean existsByStudentNoAndIdNot(String studentNo, Long id);

  /**
   * 그 역할·상태인 계정의 id.
   *
   * <p><b>잠그지 않고 읽는다.</b> 무엇을 잠글지 정하려고 미리 훑는 용도다 — 실제 잠금은 여기서 얻은 id를 요청자·대상과 합쳐 <b>오름차순으로</b> 하나씩
   * 건다. 범위째 잠그면 행을 id 순으로 잠그는 다른 서비스와 순서가 어긋나 교착한다.
   */
  @Query("select u.id from User u where u.role = :role and u.status = :status")
  List<Long> findIdsByRoleAndStatus(@Param("role") Role role, @Param("status") Status status);

  /**
   * 그 이메일을 쓰는 계정의 id — <b>대소문자를 가리지 않고</b> 찾는다.
   *
   * <p>{@code users.email}의 {@code UNIQUE}는 대소문자를 구분하므로 {@code a@b}와 {@code A@B}가 <b>함께 존재할 수
   * 있다.</b> 최초 관리자 승격은 이메일을 대소문자 없이 견주므로 <b>자격을 만족하는 행이 둘 이상일 수 있고</b>, 그 둘이 동시에 호출하면 각자 자기 행만 잠근 채
   * "활성 관리자 0명"을 함께 보고 <b>둘 다 관리자가 된다.</b> 잠글 후보를 이 질의로 모은다.
   */
  @Query("select u.id from User u where lower(u.email) = lower(:email)")
  List<Long> findIdsByEmailIgnoreCase(@Param("email") String email);

  /**
   * 학기 전환 — <b>조건에 맞는 전원을 한 문장으로 내린다</b> (spec 3-2 §3-2-6 MUST, #230).
   *
   * <p><b>세는 것과 바꾸는 것이 한 연산이어야 한다.</b> 대상을 먼저 조회하고 나중에 갱신하면 동시에 도착한 두 요청이 <b>같은 {@code ACTIVE} 집합을
   * 읽어</b> 양쪽 응답에 같은 id가 담기고 이력도 두 벌 쌓인다. 그러면 응답이 <i>"내가 바꾼 것"</i>이 아니게 되어 <b>되돌리기가 남이 방금 내린 사람까지
   * 올린다.</b>
   *
   * <p><b>{@code RETURNING}이 실제로 바뀐 행만 준다.</b> 이미 {@code INACTIVE}였던 사람은 {@code WHERE}에 걸리지 않으므로
   * 응답에 들어가지 않는다 — 멱등성과 "바뀐 id만 담는다"가 같은 문장에서 나온다.
   *
   * <p><b>{@code version}을 손으로 올린다.</b> JPA를 우회하므로 낙관적 잠금이 자동으로 걸리지 않는다. 빠뜨리면 그 사이 열려 있던 영속성 컨텍스트가
   * 낡은 값을 그대로 덮어쓴다 (§3-1-4의 직렬화 요구).
   *
   * <p>{@code deactivated_at}에 <b>같은 시각을 전원에게</b> 쓴다. 행마다 따로 찍으면 한 배치가 시각으로 갈려 "직전 배치"를 고를 수 없다.
   *
   * @return 실제로 내려간 계정의 id
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE users
             SET status = 'INACTIVE', deactivated_at = :at, version = version + 1
           WHERE role = 'USER' AND status = 'ACTIVE'
          RETURNING id
          """,
      nativeQuery = true)
  List<Long> deactivateActiveMembers(@Param("at") Instant at);

  /**
   * 세션을 다시 맞출 대상 — <b>{@code deactivated} 보다 넓다</b> (spec 2-2 §2-2-3 MUST).
   *
   * <p>비활성화는 차단이 강해지는 변경이라 세션에 닿아야 성공인데(§2-2-5), 그 규칙은 <b>"같은 요청을 다시 보내는 것이 복구 수단"</b>에 기댄다. 대상을
   * {@code ACTIVE}로만 잡으면 <b>이미 {@code INACTIVE}가 된 사람은 재요청의 대상에서 빠져 세션이 영영 낡은 채 남는다.</b>
   *
   * <p>그래서 반영은 {@code ACTIVE}·{@code INACTIVE} 일반 부원 전원에게 하고, 상태를 실제로 바꾸는 것과 이력에 남기는 것만 {@code
   * ACTIVE}였던 사람으로 한정한다.
   */
  @Query("select u.id from User u where u.role = :role and u.status in :statuses")
  List<Long> findIdsByRoleAndStatusIn(
      @Param("role") Role role, @Param("statuses") Collection<Status> statuses);

  /**
   * 활성 관리자 수.
   *
   * <p><b>세는 것과 바꾸는 것이 한 연산이어야 한다</b> (spec 2-2 §2-2-7 MUST). 잠그지 않고 세면 두 정지 요청이 둘 다 "활성 관리자 2명"을
   * 보고 통과해 <b>0명이 된다</b> — 아무도 로그인해서 운영할 수 없는 상태다 (T-15). <b>해당 행들을 먼저 잠근 뒤에 부른다.</b>
   *
   * <p>{@code SUSPENDED}인 관리자는 세지 않는다 (MUST). 로그인할 수 없으므로 DB에 role만 남아 있어도 운영을 보장하지 못한다.
   */
  long countByRoleAndStatus(Role role, Status status);
}
