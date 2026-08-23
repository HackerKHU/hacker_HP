package org.hackerkhu.hackerhp.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link CodePointSize} 구현. */
public class CodePointSizeValidator implements ConstraintValidator<CodePointSize, String> {

  private int max;

  @Override
  public void initialize(CodePointSize constraint) {
    this.max = constraint.max();
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    // null은 @NotBlank의 몫이다.
    return value == null || value.codePointCount(0, value.length()) <= max;
  }
}
