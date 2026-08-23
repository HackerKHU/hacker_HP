package org.hackerkhu.hackerhp.domain.post.repository;

import org.hackerkhu.hackerhp.domain.post.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostRepository extends JpaRepository<Post, Long> {}
