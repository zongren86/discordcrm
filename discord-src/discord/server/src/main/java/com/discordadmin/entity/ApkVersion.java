package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "apk_versions", indexes = {
    @Index(name = "idx_apk_version", columnList = "version"),
    @Index(name = "idx_apk_active", columnList = "is_active")
})
@Getter
@Setter
public class ApkVersion {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "version", nullable = false, length = 64)
    private String version;
    
    @Column(name = "original_filename", length = 256)
    private String originalFilename;
    
    @Column(name = "storage_path", length = 512)
    private String storagePath;
    
    @Column(name = "download_url", length = 512)
    private String downloadUrl;
    
    @Column(name = "file_size")
    private Long fileSize;
    
    @Column(name = "is_active")
    private Boolean isActive = false;
    
    @Column(name = "uploaded_at")
    private Instant uploadedAt = Instant.now();
}
