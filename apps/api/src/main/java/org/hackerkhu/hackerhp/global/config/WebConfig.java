package org.hackerkhu.hackerhp.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;

/**
 * 목록 API가 {@code Page}를 그대로 반환해도 계약(spec/3-2 §3-2-8)의 {@code content}/{@code page} 형태로 직렬화되게 한다.
 *
 * <p>끄면 {@code pageable}·{@code sort} 같은 내부 구현 필드가 응답에 노출된다.
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class WebConfig {}
