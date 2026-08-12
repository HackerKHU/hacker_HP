package org.hackerkhu.hackerhp.domain.user.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
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
   * 활성 관리자 수.
   *
   * <p><b>세는 것과 바꾸는 것이 한 연산이어야 한다</b> (spec 2-2 §2-2-7 MUST). 잠그지 않고 세면 두 정지 요청이 둘 다 "활성 관리자 2명"을
   * 보고 통과해 <b>0명이 된다</b> — 아무도 로그인해서 운영할 수 없는 상태다 (T-15). <b>해당 행들을 먼저 잠근 뒤에 부른다.</b>
   *
   * <p>{@code SUSPENDED}인 관리자는 세지 않는다 (MUST). 로그인할 수 없으므로 DB에 role만 남아 있어도 운영을 보장하지 못한다.
   */
  long countByRoleAndStatus(Role role, Status status);
}
