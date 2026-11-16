package com.georu.api.exception;

import com.georu.api.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 업로드 크기 초과(멀티파트 해석 단계, 컨트롤러가 정해지기 전) → 413 JSON.
 * {@link GlobalExceptionHandler} 는 basePackages 로 범위를 정해 컨트롤러가 없는 이 예외를 받지 못하므로 따로 둔다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadLimitExceptionHandler {

    @Value("${spring.servlet.multipart.max-file-size:1MB}")
    private DataSize maxFileSize;

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handle(MaxUploadSizeExceededException e) {
        long mb = maxFileSize.toMegabytes();
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.error("UPLOAD_TOO_LARGE",
                "업로드 파일이 상한 " + mb + "MB 를 넘습니다. 같은 테이블에 행 범위로 나눠 여러 파일로 올리세요 (예: 1~1,000행 파일 1개, 1,001~2,000행 파일 1개). 안내: /ui/excel",
                Map.of("maxUploadBytes", maxFileSize.toBytes())));
    }
}
