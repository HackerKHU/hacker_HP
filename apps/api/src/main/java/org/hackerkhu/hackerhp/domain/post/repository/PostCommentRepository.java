package org.hackerkhu.hackerhp.domain.post.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.post.entity.PostComment;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

  /** 같은 댓글의 수정·삭제를 직렬화하고, 권한 판정에 쓰는 작성자 id를 최신 행에서 읽는다 — {@code PostRepository}와 같은 판단이다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from PostComment c where c.id = :id")
  Optional<PostComment> findByIdForUpdate(@Param("id") Long id);

  /** 한 게시글의 댓글 전부. 대화 순서로 읽으므로 오래된 것이 먼저다 — 게시글 목록(최신순)과 반대다. */
  List<PostComment> findByPostId(Long postId, Sort sort);
}
