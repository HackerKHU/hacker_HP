package org.hackerkhu.hackerhp.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

/**
 * enum이 계약 표(spec/3-2-DESIGN-CONTRACT.md §3-2-7)와 어긋나지 않는지 지킨다.
 *
 * <p>아래 표는 스펙을 그대로 옮긴 것이다. 코드를 더하거나 상태를 바꾸면 이 테스트가 먼저 깨지므로, 스펙·웹 목록을 같이 고쳤는지 되묻게 된다.
 */
class ErrorCodeTest {

  private static Map<ErrorCode, HttpStatus> contract() {
    Map<ErrorCode, HttpStatus> table = new LinkedHashMap<>();
    table.put(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST);
    table.put(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED);
    table.put(ErrorCode.PENDING_APPROVAL, HttpStatus.FORBIDDEN);
    table.put(ErrorCode.SUSPENDED, HttpStatus.FORBIDDEN);
    table.put(ErrorCode.INACTIVE, HttpStatus.FORBIDDEN);
    table.put(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN);
    table.put(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND);
    table.put(ErrorCode.DUPLICATE_STUDENT_NO, HttpStatus.CONFLICT);
    table.put(ErrorCode.CONCURRENT_CHANGE, HttpStatus.CONFLICT);
    table.put(ErrorCode.FILE_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE);
    table.put(ErrorCode.UNSUPPORTED_FILE_TYPE, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    return table;
  }

  /* T-125 — 계약의 코드 11개가 전부 있고, 그 밖의 코드는 없다. */
  @Test
  void enumMatchesContractExactly() {
    assertThat(ErrorCode.values()).containsExactlyElementsOf(contract().keySet());
  }

  @Test
  void everyCodeMapsToContractStatus() {
    assertThat(contract())
        .allSatisfy((code, status) -> assertThat(code.getStatus()).isEqualTo(status));
  }

  /* 메시지는 화면에 그대로 뜬다. 비어 있으면 사용자는 아무 안내도 못 받는다 (§5-4). */
  @ParameterizedTest
  @EnumSource(ErrorCode.class)
  void everyCodeHasUserFacingMessage(ErrorCode errorCode) {
    assertThat(errorCode.getMessage()).isNotBlank();
  }
}
