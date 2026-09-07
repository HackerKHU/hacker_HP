-- 활동사진 좋아요 (spec 2-1 §2-1-7, 3-2 §3-2-5, #346, 3-3 결정 27).
CREATE TABLE photo_likes (
    user_id    BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- 사진이 지워지면 그 좋아요도 함께 지운다 — bookmarks·post_likes와 같은 판단이다.
    photo_id   BIGINT    NOT NULL REFERENCES photos (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    -- 복합 PK로 (회원, 사진) 중복 누름을 막는다 — bookmarks와 같은 판단(spec 3-2 §3-2-2).
    PRIMARY KEY (user_id, photo_id)
);
