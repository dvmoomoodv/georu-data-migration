package com.georu.core.internal.plugin;

import java.util.Map;

/** 한 번 읽어 여러 곳에 쓰기에서 소스 행 하나를 타겟마다 변환한 결과 (타겟 이름 → 그 타겟의 DTO. 거른 타겟은 없음) */
public record FanOut(Map<String, Object> outputs) {

    public Object of(String target) {
        return outputs.get(target);
    }
}
