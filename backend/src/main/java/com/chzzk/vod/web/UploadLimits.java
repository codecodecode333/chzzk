package com.chzzk.vod.web;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Multipart limits derived from the one setting {@code vod.upload.max-file-size} ({@code VOD_MAX_FILE_SIZE}):
 * the whole request may be that plus {@link #REQUEST_HEADROOM} for the title, description and multipart framing.
 * Enforced while parsing, before anything is stored; oversize requests become a {@code 413} in {@link VodProblemHandler}.
 */
@Configuration
class UploadLimits {

    /** Covers a max-length title and description (2,100 chars, at most 4 UTF-8 bytes each) plus part headers. */
    static final DataSize REQUEST_HEADROOM = DataSize.ofKilobytes(64);

    @Bean
    MultipartConfigElement multipartConfigElement(@Value("${vod.upload.max-file-size}") DataSize maxFileSize) {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(maxFileSize);
        factory.setMaxRequestSize(DataSize.ofBytes(maxFileSize.toBytes() + REQUEST_HEADROOM.toBytes()));
        return factory.createMultipartConfig();
    }
}
