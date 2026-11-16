package com.georu.core.internal.utils;

import com.georu.core.utils.FormatUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/*
 * 기능: 보안 관련 유틸리티 클래스
 *       암호화/복호화, 해시 생성, 토큰 관리, 개인정보 마스킹 등의 기능 제공
 */
public class SecurityUtils {

    private static final Logger logger = LoggerFactory.getLogger(SecurityUtils.class);
    
    // AES 암호화 키 (실제 운영에서는 외부 설정으로 관리)
    /** 기본 AES 키는 소스에 두지 않는다. 환경변수 APP_SECURITY_AES_KEY (16/24/32바이트) 또는 시스템 프로퍼티 georu.security.aes-key */
    private static final String ENV_AES_KEY = "APP_SECURITY_AES_KEY";

    private static String defaultSecretKey() {
        String v = System.getenv(ENV_AES_KEY);
        if (v == null || v.isBlank()) v = System.getProperty("georu.security.aes-key");
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("AES 키가 설정되지 않았습니다. 환경변수 " + ENV_AES_KEY + " 를 지정하거나 encrypt(text, key) 를 사용하세요");
        }
        return v;
    }
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
    
    // 토큰 저장소 (실제 운영에서는 Redis 등 외부 저장소 사용 권장)
    private static final ConcurrentMap<String, TokenInfo> tokenStore = new ConcurrentHashMap<>();
    
    // SecureRandom 인스턴스
    private static final SecureRandom secureRandom = new SecureRandom();

    private SecurityUtils() {
        // 유틸리티 클래스는 인스턴스화 방지
    }

    /*
     * 기능: 문자열 AES 암호화
     */
    public static String encrypt(String plainText) {
        return encrypt(plainText, defaultSecretKey());
    }

    /*
     * 기능: 문자열 AES 암호화 (키 지정)
     */
    public static String encrypt(String plainText, String secretKey) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        
        try {
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encryptedBytes);
            
        } catch (Exception e) {
            logger.error("Encryption failed for text length: {}", plainText.length(), e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /*
     * 기능: 문자열 AES 복호화
     */
    public static String decrypt(String encryptedText) {
        return decrypt(encryptedText, defaultSecretKey());
    }

    /*
     * 기능: 문자열 AES 복호화 (키 지정)
     */
    public static String decrypt(String encryptedText, String secretKey) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        
        try {
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedText);
            byte[] decryptedBytes = cipher.doFinal(decodedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            logger.error("Decryption failed for encrypted text length: {}", encryptedText.length(), e);
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /*
     * 기능: SHA-256 해시 생성
     */
    public static String generateHash(String input) {
        return generateHash(input, "SHA-256");
    }

    /*
     * 기능: 지정된 알고리즘으로 해시 생성
     */
    public static String generateHash(String input, String algorithm) {
        if (input == null) {
            return null;
        }
        
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
            
        } catch (NoSuchAlgorithmException e) {
            logger.error("Hash generation failed with algorithm: {}", algorithm, e);
            throw new RuntimeException("Hash generation failed", e);
        }
    }

    /*
     * 기능: 솔트를 사용한 해시 생성
     */
    public static String generateSaltedHash(String input, String salt) {
        if (input == null || salt == null) {
            return null;
        }
        
        return generateHash(input + salt);
    }

    /*
     * 기능: 해시 검증
     */
    public static boolean verifyHash(String input, String hash) {
        if (input == null || hash == null) {
            return false;
        }
        
        String inputHash = generateHash(input);
        return hash.equals(inputHash);
    }

    /*
     * 기능: 솔트를 사용한 해시 검증
     */
    public static boolean verifySaltedHash(String input, String salt, String hash) {
        if (input == null || salt == null || hash == null) {
            return false;
        }
        
        String inputHash = generateSaltedHash(input, salt);
        return hash.equals(inputHash);
    }

    /*
     * 기능: 랜덤 솔트 생성
     */
    public static String generateSalt() {
        byte[] saltBytes = new byte[16];
        secureRandom.nextBytes(saltBytes);
        return Base64.getEncoder().encodeToString(saltBytes);
    }

    /*
     * 기능: JWT 스타일 토큰 생성
     */
    public static String generateToken(String userId, long expirationSeconds) {
        String tokenId = UUID.randomUUID().toString();
        long expirationTime = System.currentTimeMillis() + (expirationSeconds * 1000);
        
        TokenInfo tokenInfo = new TokenInfo(userId, expirationTime);
        tokenStore.put(tokenId, tokenInfo);
        
        // 간단한 토큰 형식: tokenId.userId.expirationTime (실제로는 JWT 사용 권장)
        String tokenData = tokenId + "." + userId + "." + expirationTime;
        return Base64.getEncoder().encodeToString(tokenData.getBytes(StandardCharsets.UTF_8));
    }

    /*
     * 기능: 토큰 검증
     */
    public static boolean validateToken(String token) {
        try {
            String decodedToken = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decodedToken.split("\\.");
            
            if (parts.length != 3) {
                return false;
            }
            
            String tokenId = parts[0];
            long expirationTime = Long.parseLong(parts[2]);
            
            // 만료 시간 확인
            if (System.currentTimeMillis() > expirationTime) {
                tokenStore.remove(tokenId);
                return false;
            }
            
            // 토큰 저장소에서 확인
            return tokenStore.containsKey(tokenId);
            
        } catch (Exception e) {
            logger.debug("Token validation failed", e);
            return false;
        }
    }

    /*
     * 기능: 토큰에서 사용자 ID 추출
     */
    public static String extractUserIdFromToken(String token) {
        try {
            String decodedToken = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decodedToken.split("\\.");
            
            if (parts.length == 3 && validateToken(token)) {
                return parts[1];
            }
            
        } catch (Exception e) {
            logger.debug("Failed to extract user ID from token", e);
        }
        
        return null;
    }

    /*
     * 기능: 토큰 무효화
     */
    public static void invalidateToken(String token) {
        try {
            String decodedToken = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decodedToken.split("\\.");
            
            if (parts.length == 3) {
                String tokenId = parts[0];
                tokenStore.remove(tokenId);
            }
            
        } catch (Exception e) {
            logger.debug("Failed to invalidate token", e);
        }
    }

    /*
     * 기능: 만료된 토큰들 정리
     */
    public static void cleanupExpiredTokens() {
        long currentTime = System.currentTimeMillis();
        int removedCount = 0;
        
        for (Map.Entry<String, TokenInfo> entry : tokenStore.entrySet()) {
            if (entry.getValue().isExpired(currentTime)) {
                tokenStore.remove(entry.getKey());
                removedCount++;
            }
        }
        
        if (removedCount > 0) {
            logger.debug("Cleaned up {} expired tokens", removedCount);
        }
    }

    /*
     * 기능: 개인정보 마스킹 (FormatUtils 확장)
     */
    public static String maskPersonalInfo(String value, MaskType maskType) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        switch (maskType) {
            case EMAIL:
                return FormatUtils.maskEmail(value);
            case SSN:
                return FormatUtils.maskSocialSecurityNumber(value);
            case PHONE:
                return maskPhoneNumber(value);
            case NAME:
                return maskName(value);
            case CREDIT_CARD:
                return maskCreditCard(value);
            default:
                return value;
        }
    }

    /*
     * 기능: 전화번호 마스킹
     */
    private static String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 4) {
            return phoneNumber;
        }
        
        // 뒤 4자리만 보이고 나머지는 *로 마스킹
        int visibleLength = 4;
        int maskLength = phoneNumber.length() - visibleLength;
        
        return "*".repeat(maskLength) + phoneNumber.substring(maskLength);
    }

    /*
     * 기능: 이름 마스킹
     */
    private static String maskName(String name) {
        if (name == null || name.length() <= 1) {
            return name;
        }
        
        // 첫 글자만 보이고 나머지는 *로 마스킹
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /*
     * 기능: 신용카드 번호 마스킹
     */
    private static String maskCreditCard(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 8) {
            return cardNumber;
        }
        
        // 앞 4자리와 뒤 4자리만 보이고 나머지는 *로 마스킹
        String cleaned = cardNumber.replaceAll("[^0-9]", "");
        if (cleaned.length() < 8) {
            return cardNumber;
        }
        
        String prefix = cleaned.substring(0, 4);
        String suffix = cleaned.substring(cleaned.length() - 4);
        String middle = "*".repeat(cleaned.length() - 8);
        
        return prefix + middle + suffix;
    }

    /*
     * 토큰 정보 클래스
     */
    private static class TokenInfo {
        @SuppressWarnings("unused")
        private final String userId;
        private final long expirationTime;

        public TokenInfo(String userId, long expirationTime) {
            this.userId = userId;
            this.expirationTime = expirationTime;
        }

        public boolean isExpired(long currentTime) {
            return currentTime > expirationTime;
        }
    }

    /*
     * 마스킹 타입 열거형
     */
    public enum MaskType {
        EMAIL, SSN, PHONE, NAME, CREDIT_CARD
    }
}
