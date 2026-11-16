package com.georu.api.exception;

import com.georu.api.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 전역 예외 처리기
 * 
 * 🎯 목적:
 * - 시나리오별 API의 일관된 예외 처리
 * - 표준화된 에러 응답 형식 제공
 * - 상세한 검증 오류 정보 제공
 * 
 * 🔗 기존 시스템 연계:
 * - 기존 ApiResponse 형식과 일관성 유지
 * - 기존 예외 처리 패턴 확장
 * - 로깅 및 모니터링 연계
 * 
 * 💡 설계 원칙:
 * - 사용자 친화적인 오류 메시지
 * - 개발자를 위한 상세 정보 제공
 * - 보안을 고려한 정보 노출 제한
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.georu.api.controller")
public class GlobalExceptionHandler {

    /**
     * Bean Validation 예외 처리 (@Valid 어노테이션)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {
        
        log.warn("입력 검증 실패: {}", ex.getMessage());
        
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        String mainMessage = "입력 값 검증에 실패했습니다.";
        if (!errors.isEmpty()) {
            String firstError = errors.values().iterator().next();
            mainMessage = firstError;
        }
        
        return ResponseEntity.badRequest()
            .body(ApiResponse.error("VALIDATION_ERROR", mainMessage, errors));
    }

    /**
     * Bean Validation 예외 처리 (BindException)
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleBindExceptions(
            BindException ex) {
        
        log.warn("바인딩 검증 실패: {}", ex.getMessage());
        
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        return ResponseEntity.badRequest()
            .body(ApiResponse.error("BINDING_ERROR", "요청 데이터 바인딩에 실패했습니다.", errors));
    }

    /**
     * Constraint Validation 예외 처리
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleConstraintViolationExceptions(
            ConstraintViolationException ex) {
        
        log.warn("제약 조건 검증 실패: {}", ex.getMessage());
        
        Map<String, String> errors = ex.getConstraintViolations().stream()
            .collect(Collectors.toMap(
                violation -> violation.getPropertyPath().toString(),
                ConstraintViolation::getMessage
            ));
        
        return ResponseEntity.badRequest()
            .body(ApiResponse.error("CONSTRAINT_VIOLATION", "제약 조건 검증에 실패했습니다.", errors));
    }

    /**
     * 타입 변환 예외 처리
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatchExceptions(
            MethodArgumentTypeMismatchException ex) {
        
        log.warn("타입 변환 실패: {} - {}", ex.getName(), ex.getMessage());
        
        @SuppressWarnings("null")
        String message = String.format("'%s' 파라미터의 값 '%s'을(를) %s 타입으로 변환할 수 없습니다.",
            ex.getName(), ex.getValue(), ex.getRequiredType().getSimpleName());
        
        return ResponseEntity.badRequest()
            .body(ApiResponse.error(message, "TYPE_MISMATCH"));
    }

    /**
     * IllegalArgumentException 처리
     */
    /** 전역 단일 실행 게이트: 다른 이관이 실행 중 → 409 + 실행 중 단위 정보 */
    @ExceptionHandler(com.georu.core.internal.gate.ScheduleActiveException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleScheduleActive(com.georu.core.internal.gate.ScheduleActiveException ex) {
        log.warn("실행 거절(활성 스케줄): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("SCHEDULE_ACTIVE", ex.getMessage(),
                Map.of("schedules", ex.getSchedules())));
    }

    @ExceptionHandler(com.georu.core.internal.gate.ExecutionBusyException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleExecutionBusy(com.georu.core.internal.gate.ExecutionBusyException ex) {
        Map<String, Object> running = new java.util.LinkedHashMap<>();
        com.georu.core.internal.gate.ExecutionUnit u = ex.getRunning();
        if (u != null) {
            running.put("unitId", u.getUnitId()); running.put("type", u.getType()); running.put("description", u.getDescription());
            running.put("requestedBy", u.getRequestedBy()); running.put("acquiredAt", u.getAcquiredAt()); running.put("elapsedMs", u.getElapsedMs());
            running.put("jobExecutionIds", new java.util.ArrayList<>(u.getJobExecutionIds()));
        }
        log.warn("실행 거절(단일 실행 규칙): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("EXECUTION_BUSY", ex.getMessage(), running));
    }

    // ------------------------------------------------------------------ 요청 형식 오류
    // 아래 예외는 호출자 실수다. 처리기가 없으면 RuntimeException/Exception 처리기로 떨어져 500 이 되므로 4xx 로 돌려준다.
    // 응답 클래스의 2인자 error 는 (message, code), 3인자 error 는 (code, message, data) 순서다.

    /** 필수 쿼리·폼 파라미터 누락 → 400 */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        log.warn("필수 파라미터 누락: {} ({})", ex.getParameterName(), ex.getParameterType());
        return ResponseEntity.badRequest().body(ApiResponse.error(
                String.format("필수 파라미터 '%s'(%s) 가 없습니다.", ex.getParameterName(), ex.getParameterType()), "MISSING_PARAMETER"));
    }

    /** 필수 멀티파트 파트 누락 (예: 업로드의 file) → 400 */
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingPart(
            org.springframework.web.multipart.support.MissingServletRequestPartException ex) {
        log.warn("필수 파트 누락: {}", ex.getRequestPartName());
        return ResponseEntity.badRequest().body(ApiResponse.error(
                String.format("필수 파트 '%s' 가 없습니다 (multipart/form-data 로 보내야 합니다).", ex.getRequestPartName()), "MISSING_PART"));
    }

    /** 필수 헤더 누락 → 400 */
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingHeader(
            org.springframework.web.bind.MissingRequestHeaderException ex) {
        log.warn("필수 헤더 누락: {}", ex.getHeaderName());
        return ResponseEntity.badRequest().body(ApiResponse.error(
                String.format("필수 헤더 '%s' 가 없습니다.", ex.getHeaderName()), "MISSING_HEADER"));
    }

    /** 요청 본문 없음·JSON 형식 오류 → 400. 파서 원문은 로그에만 남긴다 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.warn("요청 본문 해석 실패: {}", ex.getMessage());
        String msg = ex.getMessage() != null && ex.getMessage().startsWith("Required request body is missing")
                ? "요청 본문(JSON)이 없습니다."
                : "요청 본문을 JSON 으로 읽을 수 없습니다. 형식과 필드 타입을 확인하세요.";
        return ResponseEntity.badRequest().body(ApiResponse.error(msg, "INVALID_BODY"));
    }

    /** 지원하지 않는 Content-Type → 415 */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnsupportedMediaType(
            org.springframework.web.HttpMediaTypeNotSupportedException ex) {
        log.warn("지원하지 않는 Content-Type: {}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiResponse.error(
                String.format("Content-Type '%s' 는 지원하지 않습니다 (지원: %s).", ex.getContentType(), ex.getSupportedMediaTypes()),
                "UNSUPPORTED_MEDIA_TYPE"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgumentExceptions(
            IllegalArgumentException ex) {
        
        log.warn("잘못된 인수: {}", ex.getMessage());
        
        return ResponseEntity.badRequest()
            .body(ApiResponse.error(ex.getMessage(), "INVALID_ARGUMENT"));
    }

    /**
     * IllegalStateException 처리
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalStateExceptions(
            IllegalStateException ex) {
        
        log.warn("잘못된 상태: {}", ex.getMessage());
        
        return ResponseEntity.badRequest()
            .body(ApiResponse.error(ex.getMessage(), "INVALID_STATE"));
    }

    /**
     * 시나리오 관련 커스텀 예외 처리
     */
    @ExceptionHandler(ScenarioExecutionException.class)
    public ResponseEntity<ApiResponse<Object>> handleScenarioExecutionExceptions(
            ScenarioExecutionException ex) {
        
        log.error("시나리오 실행 오류: {}", ex.getMessage(), ex);
        
        HttpStatus status = switch (ex.getErrorType()) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case EXECUTION_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case TIMEOUT_ERROR -> HttpStatus.REQUEST_TIMEOUT;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        
        return ResponseEntity.status(status)
            .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    /**
     * JSON 파싱 예외 처리
     */
    @ExceptionHandler(com.fasterxml.jackson.core.JsonProcessingException.class)
    public ResponseEntity<ApiResponse<Object>> handleJsonProcessingExceptions(
            com.fasterxml.jackson.core.JsonProcessingException ex) {
        
        log.warn("JSON 파싱 오류: {}", ex.getMessage());
        
        return ResponseEntity.badRequest()
            .body(ApiResponse.error("JSON 형식이 올바르지 않습니다: " + ex.getMessage(), "JSON_PARSING_ERROR"));
    }

    /**
     * 리소스 없음 예외 처리
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleResourceNotFoundExceptions(
            ResourceNotFoundException ex) {
        
        log.warn("리소스 없음: {}", ex.getMessage());
        
        return ResponseEntity.notFound().build();
    }

    /**
     * 동시성 예외 처리
     */
    @ExceptionHandler(java.util.concurrent.RejectedExecutionException.class)
    public ResponseEntity<ApiResponse<Object>> handleRejectedExecutionExceptions(
            java.util.concurrent.RejectedExecutionException ex) {
        
        log.error("실행 거부: {}", ex.getMessage(), ex);
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.error("현재 서버가 과부하 상태입니다. 잠시 후 다시 시도해주세요.", "SERVICE_UNAVAILABLE"));
    }

    /**
     * 타임아웃 예외 처리
     */
    @ExceptionHandler(java.util.concurrent.TimeoutException.class)
    public ResponseEntity<ApiResponse<Object>> handleTimeoutExceptions(
            java.util.concurrent.TimeoutException ex) {
        
        log.error("타임아웃: {}", ex.getMessage(), ex);
        
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
            .body(ApiResponse.error("요청 처리 시간이 초과되었습니다.", "TIMEOUT"));
    }

    /**
     * 일반적인 RuntimeException 처리
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Object>> handleRuntimeExceptions(
            RuntimeException ex) {
        
        log.error("런타임 오류: {}", ex.getMessage(), ex);
        
        // 개발 환경에서는 상세 정보 제공, 운영 환경에서는 일반적인 메시지
        String message = isProductionEnvironment() ? 
            "서버 내부 오류가 발생했습니다." : 
            ex.getMessage();
        
        return ResponseEntity.internalServerError()
            .body(ApiResponse.error(message, "RUNTIME_ERROR"));
    }

    /**
     * 모든 예외의 최종 처리
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleAllExceptions(
            Exception ex) {
        
        log.error("예상치 못한 오류: {}", ex.getMessage(), ex);
        
        // 개발 환경에서는 상세 정보 제공, 운영 환경에서는 일반적인 메시지
        String message = isProductionEnvironment() ? 
            "서버에서 예상치 못한 오류가 발생했습니다." : 
            ex.getMessage();
        
        return ResponseEntity.internalServerError()
            .body(ApiResponse.error(message, "INTERNAL_SERVER_ERROR"));
    }

    /**
     * 운영 환경 여부 확인
     */
    private boolean isProductionEnvironment() {
        String profile = System.getProperty("spring.profiles.active", "");
        return profile.contains("prod") || profile.contains("production");
    }

    /**
     * 시나리오 실행 예외 클래스
     */
    public static class ScenarioExecutionException extends RuntimeException {
        
        public enum ErrorType {
            VALIDATION_ERROR,
            EXECUTION_ERROR,
            TIMEOUT_ERROR,
            RESOURCE_NOT_FOUND,
            PERMISSION_DENIED
        }
        
        private final String errorCode;
        private final ErrorType errorType;
        
        public ScenarioExecutionException(String errorCode, ErrorType errorType, String message) {
            super(message);
            this.errorCode = errorCode;
            this.errorType = errorType;
        }
        
        public ScenarioExecutionException(String errorCode, ErrorType errorType, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
            this.errorType = errorType;
        }
        
        public String getErrorCode() {
            return errorCode;
        }
        
        public ErrorType getErrorType() {
            return errorType;
        }
    }

    /**
     * 리소스 없음 예외 클래스
     */
    public static class ResourceNotFoundException extends RuntimeException {
        
        public ResourceNotFoundException(String message) {
            super(message);
        }
        
        public ResourceNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
