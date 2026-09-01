-- 자유 게시판 좋아요 (spec 2-1 §2-1-8, 3-2 §3-2-5, #345, 3-3 결정 26).
CREATE TABLE post_likes (
    user_id    BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- 게시글이 지워지면 그 좋아요도 함께 지운다 — 댓글(post_comments)과 같은 판단이다.
    post_id    BIGINT    NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    -- 복합 PK로 (회원, 게시글) 중복 누름을 막는다 — bookmarks와 같은 판단(spec 3-2 §3-2-2).
    PRIMARY KEY (user_id, post_id)
);
