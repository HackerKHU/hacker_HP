package org.hackerkhu.hackerhp.domain.post.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.post.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

  /** 같은 글의 수정·삭제를 직렬화하고, 권한 판정에 쓰는 작성자 id를 최신 행에서 읽는다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Post p where p.id = :id")
  Optional<Post> findByIdForUpdate(@Param("id") Long id);
}
