package com.georu.core.internal.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 파일 무결성 검증 엔티티
 * FILE_INTEGRITY_CHECK 테이블과 매핑
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileIntegrityCheck {
    
    private Long id;
    private String migrationId;
    private String filePath;
    private String checkType;
    private String expectedValue;
    private String actualValue;
    private String checkStatus;
    private LocalDateTime checkTime;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
