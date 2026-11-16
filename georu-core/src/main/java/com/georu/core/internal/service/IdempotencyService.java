package com.georu.core.internal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 멱등키 관리 서비스
 * 동일한 파라미터로 중복 호출을 방지하기 위한 멱등키 생성 및 관리
 */
@Slf4j
@Service
public class IdempotencyService {

    /** 멱등키 저장소 (실제 환경에서는 Redis 등 외부 저장소 사용 권장) */
    private final ConcurrentMap<String, IdempotencyRecord> idempotencyStore = new ConcurrentHashMap<>();
    
    /** 기본 만료 시간 (분) */
    private static final int DEFAULT_EXPIRY_MINUTES = 60;
    
    /** 날짜 포맷터 */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 멱등키 레코드
     */
    public static class IdempotencyRecord {
        private final String key;
        private final LocalDateTime createdAt;
        private final LocalDateTime expiresAt;
        private final String requestHash;
        private Object result;
        private boolean processed;

        public IdempotencyRecord(String key, String requestHash, int expiryMinutes) {
            this.key = key;
            this.requestHash = requestHash;
            this.createdAt = LocalDateTime.now();
            this.expiresAt = createdAt.plusMinutes(expiryMinutes);
            this.processed = false;
        }

        // Getters
        public String getKey() { return key; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        public String getRequestHash() { return requestHash; }
        public Object getResult() { return result; }
        public boolean isProcessed() { return processed; }
        public boolean isExpired() { return LocalDateTime.now().isAfter(expiresAt); }

        // Setters
        public void setResult(Object result) { 
            this.result = result; 
            this.processed = true;
        }
    }

    /**
     * 파라미터들로부터 멱등키를 생성합니다.
     *
     * @param parameters the parameters to generate idempotency key from
     * @return the generated idempotency key
     */
    public String generateIdempotencyKey(Object... parameters) {
        return generateIdempotencyKey(null, parameters);
    }

    /**
     * 접두사와 파라미터들로부터 멱등키를 생성합니다.
     *
     * @param prefix the prefix for the key
     * @param parameters the parameters to generate idempotency key from
     * @return the generated idempotency key
     */
    @SuppressWarnings("static-access")
    public String generateIdempotencyKey(String prefix, Object... parameters) {
        try {
            // 1. 파라미터들을 문자열로 변환
            StringBuilder paramBuilder = new StringBuilder();
            if (parameters != null) {
                for (Object param : parameters) {
                    if (param != null) {
                        paramBuilder.append(param.toString()).append("|");
                    }
                }
            }
            
            // 2. 현재 시간 추가 (분 단위까지만)
            String timestamp = LocalDateTime.now().format(DATE_FORMATTER.ofPattern("yyyyMMddHHmm"));
            paramBuilder.append(timestamp);
            
            // 3. SHA-256 해시 생성
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(paramBuilder.toString().getBytes(StandardCharsets.UTF_8));
            String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes);
            
            // 4. 접두사 추가
            String keyPrefix = prefix != null ? prefix + "_" : "IDEM_";
            return keyPrefix + hash.substring(0, 16); // 16자리로 제한
            
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to generate idempotency key", e);
            // 폴백: 단순 해시코드 사용
            String fallback = String.valueOf(Arrays.hashCode(parameters));
            return (prefix != null ? prefix + "_" : "IDEM_") + fallback;
        }
    }

    /**
     * 중복 요청인지 확인합니다.
     *
     * @param key the idempotency key
     * @return true if this is a duplicate request, false otherwise
     */
    public boolean isDuplicateRequest(String key) {
        return isDuplicateRequest(key, null);
    }

    /**
     * 중복 요청인지 확인하고 요청 해시를 검증합니다.
     *
     * @param key the idempotency key
     * @param requestHash the hash of the current request
     * @return true if this is a duplicate request, false otherwise
     */
    public boolean isDuplicateRequest(String key, String requestHash) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        
        // 만료된 키 정리
        cleanupExpiredKeys();
        
        IdempotencyRecord record = idempotencyStore.get(key);
        if (record == null) {
            return false;
        }
        
        // 만료 확인
        if (record.isExpired()) {
            idempotencyStore.remove(key);
            return false;
        }
        
        // 요청 해시 검증 (제공된 경우)
        if (requestHash != null && !requestHash.equals(record.getRequestHash())) {
            log.warn("Idempotency key collision detected: key={}, expected={}, actual={}", 
                    key, record.getRequestHash(), requestHash);
            return false;
        }
        
        log.debug("Duplicate request detected: key={}, created={}", key, record.getCreatedAt());
        return true;
    }

    /**
     * 멱등키를 등록합니다.
     *
     * @param key the idempotency key
     * @param requestHash the hash of the request
     * @return the created idempotency record
     */
    public IdempotencyRecord registerIdempotencyKey(String key, String requestHash) {
        return registerIdempotencyKey(key, requestHash, DEFAULT_EXPIRY_MINUTES);
    }

    /**
     * 멱등키를 등록합니다 (만료 시간 지정).
     *
     * @param key the idempotency key
     * @param requestHash the hash of the request
     * @param expiryMinutes the expiry time in minutes
     * @return the created idempotency record
     */
    public IdempotencyRecord registerIdempotencyKey(String key, String requestHash, int expiryMinutes) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency key cannot be null or empty");
        }
        
        IdempotencyRecord record = new IdempotencyRecord(key, requestHash, expiryMinutes);
        idempotencyStore.put(key, record);
        
        log.debug("Registered idempotency key: key={}, expires={}", key, record.getExpiresAt());
        return record;
    }

    /**
     * 멱등키에 대한 결과를 저장합니다.
     *
     * @param key the idempotency key
     * @param result the result to store
     */
    public void storeResult(String key, Object result) {
        IdempotencyRecord record = idempotencyStore.get(key);
        if (record != null && !record.isExpired()) {
            record.setResult(result);
            log.debug("Stored result for idempotency key: key={}", key);
        }
    }

    /**
     * 멱등키에 대한 결과를 조회합니다.
     *
     * @param key the idempotency key
     * @return the stored result or null if not found
     */
    public Object getResult(String key) {
        IdempotencyRecord record = idempotencyStore.get(key);
        if (record != null && !record.isExpired() && record.isProcessed()) {
            return record.getResult();
        }
        return null;
    }

    /**
     * 요청 해시를 생성합니다.
     *
     * @param request the request object
     * @return the request hash
     */
    public String generateRequestHash(Object request) {
        if (request == null) {
            return null;
        }
        
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(request.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to generate request hash", e);
            return String.valueOf(request.hashCode());
        }
    }

    /**
     * 만료된 키들을 정리합니다.
     *
     */
    public void cleanupExpiredKeys() {
        LocalDateTime now = LocalDateTime.now();
        idempotencyStore.entrySet().removeIf(entry -> entry.getValue().isExpired());
        
        log.debug("Cleaned up expired idempotency keys at {}", now);
    }

    /**
     * 현재 저장된 멱등키 개수를 반환합니다.
     *
     * @return the number of stored idempotency keys
     */
    public int getStoredKeyCount() {
        cleanupExpiredKeys();
        return idempotencyStore.size();
    }

    /**
     * 모든 멱등키를 삭제합니다 (테스트용).
     *
     */
    public void clearAll() {
        idempotencyStore.clear();
        log.debug("Cleared all idempotency keys");
    }
}
