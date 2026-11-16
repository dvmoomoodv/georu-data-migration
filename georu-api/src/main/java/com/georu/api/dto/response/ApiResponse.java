package com.georu.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * API 응답 공통 래퍼 클래스
 * 
 * 🎯 목적:
 * - 모든 API 응답의 표준화된 형식 제공
 * - 성공/실패 상태 및 메시지 통합 관리
 * - 클라이언트에서 일관된 응답 처리 지원
 * 
 * 📊 응답 구조:
 * - success: 성공 여부
 * - message: 응답 메시지
 * - data: 실제 데이터
 * - timestamp: 응답 시간
 * - code: 응답 코드
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    
    /**
     * 성공 여부
     */
    private boolean success;
    
    /**
     * 응답 메시지
     */
    private String message;
    
    /**
     * 실제 데이터
     */
    private T data;
    
    /**
     * 응답 시간
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
    
    /**
     * 응답 코드
     */
    private String code;
    
    /**
     * 성공 응답 생성
     */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
            .success(true)
            .message("요청이 성공적으로 처리되었습니다")
            .data(data)
            .code("SUCCESS")
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 성공 응답 생성 (메시지 포함)
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
            .success(true)
            .message(message)
            .data(data)
            .code("SUCCESS")
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 실패 응답 생성
     */
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(null)
            .code("ERROR")
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 실패 응답 생성 (코드 포함). 인자 순서는 (사람이 읽는 메시지, 오류 코드) 다.
     * 3인자 {@code error(code, message, data)} 와 순서가 반대이니 주의한다.
     */
    public static <T> ApiResponse<T> error(String message, String code) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(null)
            .code(code)
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 검증 실패 응답 생성
     */
    public static <T> ApiResponse<T> validationError(T data, String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(data)
            .code("VALIDATION_ERROR")
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 리소스 없음 응답 생성
     */
    public static <T> ApiResponse<T> notFound(String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(null)
            .code("NOT_FOUND")
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 서버 오류 응답 생성
     */
    public static <T> ApiResponse<T> serverError(String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(null)
            .code("INTERNAL_SERVER_ERROR")
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 권한 없음 응답 생성
     */
    public static <T> ApiResponse<T> unauthorized(String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(null)
            .code("UNAUTHORIZED")
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 접근 금지 응답 생성
     */
    public static <T> ApiResponse<T> forbidden(String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(null)
            .code("FORBIDDEN")
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 타임아웃 응답 생성
     */
    public static <T> ApiResponse<T> timeout(String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(null)
            .code("TIMEOUT")
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 리소스 충돌 응답 생성
     */
    public static <T> ApiResponse<T> conflict(String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(null)
            .code("CONFLICT")
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 요청 제한 응답 생성
     */
    public static <T> ApiResponse<T> tooManyRequests(String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(null)
            .code("TOO_MANY_REQUESTS")
            .timestamp(LocalDateTime.now())
            .build();
    }
    
    /**
     * 사용자 정의 응답 생성
     */
    public static <T> ApiResponse<T> custom(boolean success, String message, T data, String code) {
        return ApiResponse.<T>builder()
            .success(success)
            .message(message)
            .data(data)
            .code(code)
            .timestamp(LocalDateTime.now())
            .build();
    }

    /**
     * 검증 실패 응답 생성 (코드, 메시지, 데이터 포함)
     */
    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(data)
            .code(code)
            .timestamp(LocalDateTime.now())
            .build();
    }
}
