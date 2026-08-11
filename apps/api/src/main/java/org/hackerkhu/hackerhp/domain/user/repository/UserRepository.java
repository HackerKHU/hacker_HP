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

  /** 이메일이 <b>다른</b> 계정에 이미 쓰이고 있는지 (T-55). 같은 계정은 세지 않는다. */
  boolean existsByEmailAndGoogleSubNot(String email, String googleSub);
}
