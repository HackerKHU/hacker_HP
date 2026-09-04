package org.hackerkhu.hackerhp.domain.post.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.post.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

  /** 같은 글의 수정·삭제를 직렬화하고, 권한 판정에 쓰는 작성자 id를 최신 행에서 읽는다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Post p where p.id = :id")
  Optional<Post> findByIdForUpdate(@Param("id") Long id);

  /**
   * <b>내가 쓴 글만</b> (#353, 3-3 결정 28).
   *
   * <p>정렬은 부르는 쪽이 {@code Pageable}에 실어 준다 — 전체 목록과 <b>같은 고정 정렬</b>이어야 필터를 켜고 끌 때 순서가 흔들리지 않는다.
   *
   * <p>작성자가 나간 글({@code authorId = null})은 어떤 {@code viewerId}와도 같지 않아 자연히 빠진다 — 인증 주체는 언제나 실재하는
   * 계정이다.
   */
  Page<Post> findByAuthorId(Long authorId, Pageable pageable);
}
