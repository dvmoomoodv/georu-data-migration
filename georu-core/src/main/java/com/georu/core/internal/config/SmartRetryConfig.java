package com.georu.core.internal.config;

import com.georu.core.internal.exception.ErrorClassifier;
import com.georu.core.internal.retry.SmartRetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * 스마트 재시도 시스템 설정
 */
@Configuration
@EnableRetry
@ConfigurationProperties(prefix = "georu.retry")
public class SmartRetryConfig {
    
    private boolean enabled = true;
    private int defaultMaxAttempts = 3;
    private long defaultDelayMs = 1000;
    private double defaultBackoffMultiplier = 2.0;
    
    // Database retry settings
    private DatabaseRetrySettings database = new DatabaseRetrySettings();
    
    // Network retry settings  
    private NetworkRetrySettings network = new NetworkRetrySettings();
    
    // System retry settings
    private SystemRetrySettings system = new SystemRetrySettings();
    
    @Bean
    public ErrorClassifier errorClassifier() {
        return new ErrorClassifier();
    }
    
    @Bean
    public SmartRetryPolicy smartRetryPolicy(ErrorClassifier errorClassifier) {
        return new SmartRetryPolicy();
    }
    
    // Getters and Setters
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }
    
    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }
    
    public long getDefaultDelayMs() {
        return defaultDelayMs;
    }
    
    public void setDefaultDelayMs(long defaultDelayMs) {
        this.defaultDelayMs = defaultDelayMs;
    }
    
    public double getDefaultBackoffMultiplier() {
        return defaultBackoffMultiplier;
    }
    
    public void setDefaultBackoffMultiplier(double defaultBackoffMultiplier) {
        this.defaultBackoffMultiplier = defaultBackoffMultiplier;
    }
    
    public DatabaseRetrySettings getDatabase() {
        return database;
    }
    
    public void setDatabase(DatabaseRetrySettings database) {
        this.database = database;
    }
    
    public NetworkRetrySettings getNetwork() {
        return network;
    }
    
    public void setNetwork(NetworkRetrySettings network) {
        this.network = network;
    }
    
    public SystemRetrySettings getSystem() {
        return system;
    }
    
    public void setSystem(SystemRetrySettings system) {
        this.system = system;
    }
    
    /**
     * 데이터베이스 재시도 설정
     */
    public static class DatabaseRetrySettings {
        private int connectionMaxAttempts = 3;
        private long connectionBaseWaitSeconds = 5;
        private int timeoutMaxAttempts = 2;
        private long timeoutBaseWaitSeconds = 3;
        private int deadlockMaxAttempts = 3;
        private long deadlockBaseWaitSeconds = 1;
        
        // Getters and Setters
        public int getConnectionMaxAttempts() {
            return connectionMaxAttempts;
        }
        
        public void setConnectionMaxAttempts(int connectionMaxAttempts) {
            this.connectionMaxAttempts = connectionMaxAttempts;
        }
        
        public long getConnectionBaseWaitSeconds() {
            return connectionBaseWaitSeconds;
        }
        
        public void setConnectionBaseWaitSeconds(long connectionBaseWaitSeconds) {
            this.connectionBaseWaitSeconds = connectionBaseWaitSeconds;
        }
        
        public int getTimeoutMaxAttempts() {
            return timeoutMaxAttempts;
        }
        
        public void setTimeoutMaxAttempts(int timeoutMaxAttempts) {
            this.timeoutMaxAttempts = timeoutMaxAttempts;
        }
        
        public long getTimeoutBaseWaitSeconds() {
            return timeoutBaseWaitSeconds;
        }
        
        public void setTimeoutBaseWaitSeconds(long timeoutBaseWaitSeconds) {
            this.timeoutBaseWaitSeconds = timeoutBaseWaitSeconds;
        }
        
        public int getDeadlockMaxAttempts() {
            return deadlockMaxAttempts;
        }
        
        public void setDeadlockMaxAttempts(int deadlockMaxAttempts) {
            this.deadlockMaxAttempts = deadlockMaxAttempts;
        }
        
        public long getDeadlockBaseWaitSeconds() {
            return deadlockBaseWaitSeconds;
        }
        
        public void setDeadlockBaseWaitSeconds(long deadlockBaseWaitSeconds) {
            this.deadlockBaseWaitSeconds = deadlockBaseWaitSeconds;
        }
    }
    
    /**
     * 네트워크 재시도 설정
     */
    public static class NetworkRetrySettings {
        private int connectionMaxAttempts = 2;
        private long connectionBaseWaitSeconds = 5;
        private int timeoutMaxAttempts = 3;
        private long timeoutBaseWaitSeconds = 3;
        
        // Getters and Setters
        public int getConnectionMaxAttempts() {
            return connectionMaxAttempts;
        }
        
        public void setConnectionMaxAttempts(int connectionMaxAttempts) {
            this.connectionMaxAttempts = connectionMaxAttempts;
        }
        
        public long getConnectionBaseWaitSeconds() {
            return connectionBaseWaitSeconds;
        }
        
        public void setConnectionBaseWaitSeconds(long connectionBaseWaitSeconds) {
            this.connectionBaseWaitSeconds = connectionBaseWaitSeconds;
        }
        
        public int getTimeoutMaxAttempts() {
            return timeoutMaxAttempts;
        }
        
        public void setTimeoutMaxAttempts(int timeoutMaxAttempts) {
            this.timeoutMaxAttempts = timeoutMaxAttempts;
        }
        
        public long getTimeoutBaseWaitSeconds() {
            return timeoutBaseWaitSeconds;
        }
        
        public void setTimeoutBaseWaitSeconds(long timeoutBaseWaitSeconds) {
            this.timeoutBaseWaitSeconds = timeoutBaseWaitSeconds;
        }
    }
    
    /**
     * 시스템 재시도 설정
     */
    public static class SystemRetrySettings {
        private int memoryMaxAttempts = 1;
        private long memoryBaseWaitSeconds = 2;
        private boolean diskRetryEnabled = false;
        
        // Getters and Setters
        public int getMemoryMaxAttempts() {
            return memoryMaxAttempts;
        }
        
        public void setMemoryMaxAttempts(int memoryMaxAttempts) {
            this.memoryMaxAttempts = memoryMaxAttempts;
        }
        
        public long getMemoryBaseWaitSeconds() {
            return memoryBaseWaitSeconds;
        }
        
        public void setMemoryBaseWaitSeconds(long memoryBaseWaitSeconds) {
            this.memoryBaseWaitSeconds = memoryBaseWaitSeconds;
        }
        
        public boolean isDiskRetryEnabled() {
            return diskRetryEnabled;
        }
        
        public void setDiskRetryEnabled(boolean diskRetryEnabled) {
            this.diskRetryEnabled = diskRetryEnabled;
        }
    }
}
