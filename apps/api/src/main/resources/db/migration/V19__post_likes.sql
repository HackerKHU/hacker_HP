-- 자유 게시판 좋아요 (spec 2-1 §2-1-8, 3-2 §3-2-5, #345, 3-3 결정 26).
CREATE TABLE post_likes (
    user_id    BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- 게시글이 지워지면 그 좋아요도 함께 지운다 — 댓글(post_comments)과 같은 판단이다.
    post_id    BIGINT    NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    -- 복합 PK로 (회원, 게시글) 중복 누름을 막는다 — bookmarks와 같은 판단(spec 3-2 §3-2-2).
    PRIMARY KEY (user_id, post_id)
);

-- post_id로 들어오는 조회를 위한 인덱스 (#368 리뷰). 복합 PK는 선두 열이 user_id라
-- 이 방향을 받쳐주지 못한다 — 목록·상세의 좋아요 집계와 게시글 삭제의 FK CASCADE가
-- 모두 post_id로 찾으므로, 없으면 좋아요가 쌓일수록 매 요청이 전체 스캔이 된다.
CREATE INDEX idx_post_likes_post_id ON post_likes (post_id);
