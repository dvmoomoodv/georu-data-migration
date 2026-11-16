package com.georu.core.internal.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import jakarta.validation.ValidationException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * 예외 객체를 분석하여 적절한 MigrationErrorType으로 분류하는 서비스
 */
@Component
public class ErrorClassifier {
    
    private static final Logger logger = LoggerFactory.getLogger(ErrorClassifier.class);
    
    // 데이터베이스 에러 패턴
    private static final Pattern TIMEOUT_PATTERN = Pattern.compile("timeout", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONNECTION_PATTERN = Pattern.compile("connection", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEADLOCK_PATTERN = Pattern.compile("deadlock", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRAINT_PATTERN = Pattern.compile("constraint|duplicate|unique", Pattern.CASE_INSENSITIVE);
    
    // 네트워크 에러 패턴
    private static final Pattern NETWORK_TIMEOUT_PATTERN = Pattern.compile("read timeout|connect timeout", Pattern.CASE_INSENSITIVE);
    
    // 메모리 에러 패턴
    private static final Pattern MEMORY_PATTERN = Pattern.compile("out of memory|heap space", Pattern.CASE_INSENSITIVE);
    
    // 디스크 에러 패턴
    private static final Pattern DISK_PATTERN = Pattern.compile("no space left|disk full", Pattern.CASE_INSENSITIVE);
    
    /**
     * 예외 객체를 분석하여 MigrationErrorType으로 분류
     * 
     * @param throwable 분류할 예외 객체
     * @return 분류된 에러 타입
     */
    public MigrationErrorType classifyError(Throwable throwable) {
        if (throwable == null) {
            logger.warn("Null throwable provided for classification");
            return MigrationErrorType.UNKNOWN;
        }
        
        logger.debug("Classifying error: {} - {}", throwable.getClass().getSimpleName(), throwable.getMessage());
        
        // 1. 예외 클래스 기반 분류
        MigrationErrorType typeByClass = classifyByExceptionClass(throwable);
        if (typeByClass != MigrationErrorType.UNKNOWN) {
            logger.debug("Classified by exception class: {}", typeByClass);
            return typeByClass;
        }
        
        // 2. 메시지 기반 분류
        MigrationErrorType typeByMessage = classifyByMessage(throwable);
        if (typeByMessage != MigrationErrorType.UNKNOWN) {
            logger.debug("Classified by message: {}", typeByMessage);
            return typeByMessage;
        }
        
        // 3. 원인 예외 분석
        if (throwable.getCause() != null) {
            MigrationErrorType typeByRootCause = classifyError(throwable.getCause());
            if (typeByRootCause != MigrationErrorType.UNKNOWN) {
                logger.debug("Classified by root cause: {}", typeByRootCause);
                return typeByRootCause;
            }
        }
        
        logger.warn("Unable to classify error, returning UNKNOWN: {} - {}", 
            throwable.getClass().getSimpleName(), throwable.getMessage());
        return MigrationErrorType.UNKNOWN;
    }
    
    /**
     * 예외 클래스 기반 분류
     */
    private MigrationErrorType classifyByExceptionClass(Throwable throwable) {
        Class<?> exceptionClass = throwable.getClass();
        
        // 데이터베이스 관련 예외
        if (SQLException.class.isAssignableFrom(exceptionClass) || 
            DataAccessException.class.isAssignableFrom(exceptionClass)) {
            return classifyDatabaseError(throwable);
        }
        
        // 데이터 검증 관련 예외
        if (IllegalArgumentException.class.isAssignableFrom(exceptionClass) ||
            ValidationException.class.isAssignableFrom(exceptionClass)) {
            return MigrationErrorType.DATA_VALIDATION;
        }
        
        // 네트워크 관련 예외
        if (ConnectException.class.isAssignableFrom(exceptionClass)) {
            return MigrationErrorType.NETWORK_CONNECTION;
        }
        
        if (SocketTimeoutException.class.isAssignableFrom(exceptionClass)) {
            return MigrationErrorType.NETWORK_TIMEOUT;
        }
        
        // 메모리 관련 예외
        if (OutOfMemoryError.class.isAssignableFrom(exceptionClass)) {
            return MigrationErrorType.MEMORY_OVERFLOW;
        }
        
        // 보안 관련 예외
        if (SecurityException.class.isAssignableFrom(exceptionClass)) {
            return MigrationErrorType.PERMISSION_DENIED;
        }
        
        return MigrationErrorType.UNKNOWN;
    }
    
    /**
     * 데이터베이스 에러 세부 분류
     */
    private MigrationErrorType classifyDatabaseError(Throwable throwable) {
        String message = getFullErrorMessage(throwable);
        
        if (DEADLOCK_PATTERN.matcher(message).find()) {
            return MigrationErrorType.DATABASE_DEADLOCK;
        }
        
        if (TIMEOUT_PATTERN.matcher(message).find()) {
            return MigrationErrorType.DATABASE_TIMEOUT;
        }
        
        if (CONNECTION_PATTERN.matcher(message).find()) {
            return MigrationErrorType.DATABASE_CONNECTION;
        }
        
        if (CONSTRAINT_PATTERN.matcher(message).find()) {
            return MigrationErrorType.DATABASE_CONSTRAINT;
        }
        
        return MigrationErrorType.DATABASE_CONNECTION; // 기본값
    }
    
    /**
     * 메시지 기반 분류
     */
    private MigrationErrorType classifyByMessage(Throwable throwable) {
        String message = getFullErrorMessage(throwable);
        
        if (NETWORK_TIMEOUT_PATTERN.matcher(message).find()) {
            return MigrationErrorType.NETWORK_TIMEOUT;
        }
        
        if (MEMORY_PATTERN.matcher(message).find()) {
            return MigrationErrorType.MEMORY_OVERFLOW;
        }
        
        if (DISK_PATTERN.matcher(message).find()) {
            return MigrationErrorType.DISK_FULL;
        }
        
        return MigrationErrorType.UNKNOWN;
    }
    
    /**
     * 예외와 원인 예외의 모든 메시지를 결합
     */
    private String getFullErrorMessage(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        Throwable current = throwable;
        
        while (current != null) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(" ");
            }
            current = current.getCause();
        }
        
        return sb.toString();
    }
}
