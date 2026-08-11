package org.hackerkhu.hackerhp.domain.user.repository;

import java.util.Optional;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

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
}
