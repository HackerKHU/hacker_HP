-- 자료 좋아요 (spec 2-1 §2-1-1, 3-2 §3-2-4, #344).
-- 즐겨찾기(bookmarks)와 완전히 별개 자원이다 (3-3 결정 25 D1) — 즐겨찾기는
-- "다시 보려고 담아둔다", 좋아요는 "품질에 공감한다"로 뜻이 다르다.
CREATE TABLE note_likes (
    -- 회원이 지워지면 그 좋아요도 함께 사라진다 — bookmarks와 같은 판단이다.
    user_id    BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    note_id    BIGINT    NOT NULL REFERENCES notes (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    -- 복합 PK로 (회원, 자료) 중복을 막는다 — bookmarks와 같은 판단(spec 3-2 §3-2-2).
    PRIMARY KEY (user_id, note_id)
);
