package com.kvita.diskmapper.shizuku;

interface IShizukuCleanerService {
    String scanPaths(String basePath, boolean telegramOnly, int maxItems);
    boolean deleteFile(String absolutePath);
    String diagnostics();
}
