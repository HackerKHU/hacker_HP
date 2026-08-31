package org.hackerkhu.hackerhp.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 글자 수 상한을 <b>코드포인트</b>로 센다.
 *
 * <p><b>{@code @Size}를 쓰면 안 되는 자리가 있다</b> (#236 리뷰). {@code @Size}는 {@code String.length()} —
 * UTF-16 단위 — 를 세므로 이모지 같은 보충 문자를 두 글자로 센다. 반면 PostgreSQL의 {@code LENGTH(text)}는 코드포인트를 센다. 그래서 DB에
 * {@code CHECK (LENGTH(...) <= N)}이 걸린 컬럼에 {@code @Size(max = N)}을 붙이면 <b>두 단위가 어긋나</b>, DB가 받아 줄
 * 이모지 글을 API가 먼저 {@code 400}으로 거절한다. 사용자가 세는 "자"도 코드포인트 쪽이다.
 *
 * <p>{@code null}은 통과시킨다. 비었는지는 {@code @NotBlank}가 본다 — 상한 검사가 빈 값까지 맡으면 어느 쪽이 깨졌는지 메시지로 구분되지 않는다.
 *
 * <p>DB에 길이 {@code CHECK}이 없는 {@code VARCHAR} 컬럼들은 아직 {@code @Size}를 쓴다. 그쪽은 Java가 더 엄격해서 DB 오류로는
 * 번지지 않고, 함께 바꾸려면 각자 경계 테스트가 필요해 이 PR 범위 밖이다.
 */
@Documented
@Constraint(validatedBy = CodePointSizeValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodePointSize {

  /** 코드포인트 기준 상한. */
  int max();

  /**
   * {@code true}면 Java {@link String#trim()}과 같이 양끝의 U+0000~U+0020 문자만 제거한 뒤 센다. NBSP(U+00A0) 등 그
   * 밖의 문자는 의미 문자로 보존한다.
   */
  boolean trim() default false;

  String message() default "너무 깁니다.";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
