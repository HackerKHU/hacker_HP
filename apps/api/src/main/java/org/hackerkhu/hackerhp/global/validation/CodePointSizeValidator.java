package org.hackerkhu.hackerhp.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link CodePointSize} 구현. */
public class CodePointSizeValidator implements ConstraintValidator<CodePointSize, String> {

  private int max;
  private boolean trim;

  @Override
  public void initialize(CodePointSize constraint) {
    this.max = constraint.max();
    this.trim = constraint.trim();
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    // null은 @NotBlank의 몫이다.
    if (value == null) {
      return true;
    }
    String measured = trim ? value.trim() : value;
    return measured.codePointCount(0, measured.length()) <= max;
  }
}
