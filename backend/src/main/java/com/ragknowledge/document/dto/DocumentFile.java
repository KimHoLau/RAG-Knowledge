package com.ragknowledge.document.dto;

import org.springframework.core.io.Resource;

/**
 * 源文件下载结果。
 * fileName 是上传时的原始名（用于回写给浏览器的下载名），
 * resource 指向 {docId}_{fileName} 的实际落盘文件。
 */
public record DocumentFile(String fileName, Resource resource) {
}
