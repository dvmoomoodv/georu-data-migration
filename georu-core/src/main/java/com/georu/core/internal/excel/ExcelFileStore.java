package com.georu.core.internal.excel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 엑셀 파일 보관소. {@code georu.excel.dir} 아래 pending/processing/completed/failed 폴더를 관리한다.
 * 파일명은 {@code <fileId>_<원본명(안전화)>} 로 저장해 같은 이름의 재업로드가 서로 덮어쓰지 않게 한다.
 */
@Slf4j
@Component
public class ExcelFileStore {

    private final Path root;

    public ExcelFileStore(ExcelLoadProperties props) {
        this.root = Paths.get(props.getDir()).toAbsolutePath().normalize();
        try {
            for (ExcelFolder f : ExcelFolder.values()) {
                Files.createDirectories(root.resolve(f.dirName()));
            }
            log.info("[Excel] 파일 보관소: {}", root);
        } catch (IOException e) {
            throw new IllegalStateException("엑셀 보관 폴더를 만들 수 없습니다: " + root + " (" + e.getMessage() + ")", e);
        }
    }

    public Path root() {
        return root;
    }

    public Path dir(ExcelFolder folder) {
        return root.resolve(folder.dirName());
    }

    public Path resolve(ExcelFolder folder, String storedName) {
        Path p = dir(folder).resolve(storedName).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("보관 폴더 밖 경로: " + storedName);
        }
        return p;
    }

    public Path resolve(ExcelFileRecord r) {
        return resolve(r.folderEnum(), r.getStoredName());
    }

    /** 저장 파일명: fileId_원본명. 경로 구분자·제어 문자는 '_' 로 바꾼다 */
    public static String storedName(String fileId, String originalName) {
        String base = originalName == null ? "file" : originalName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (char c : base.toCharArray()) {
            sb.append(c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|' ? '_' : c);
        }
        String safe = sb.toString().trim();
        if (safe.isEmpty()) {
            safe = "file";
        }
        if (safe.length() > 180) {
            safe = safe.substring(0, 180);
        }
        return fileId + "_" + safe;
    }

    /** 확장자 (소문자, 점 없이). 없으면 빈 문자열 */
    public static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** pending 폴더에 저장하고 SHA-256 을 돌려준다 */
    public String saveToPending(InputStream in, String storedName) throws IOException {
        Path target = resolve(ExcelFolder.PENDING, storedName);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var out = Files.newOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                }
            }
            return toHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 폴더 이동. 대상에 같은 이름이 있으면 덮어쓰지 않고 실패 */
    public Path move(ExcelFileRecord r, ExcelFolder to) throws IOException {
        Path from = resolve(r);
        Path target = resolve(to, r.getStoredName());
        if (from.equals(target)) {
            return target;
        }
        if (!Files.exists(from)) {
            throw new IOException("이동할 파일이 없습니다: " + from);
        }
        if (Files.exists(target)) {
            throw new IOException("대상 폴더에 같은 파일이 이미 있습니다: " + target);
        }
        Files.createDirectories(target.getParent());
        Files.move(from, target, StandardCopyOption.ATOMIC_MOVE);
        log.info("[Excel] 파일 이동 {} → {}: {}", r.getFolder(), to, r.getStoredName());
        return target;
    }

    public boolean delete(ExcelFileRecord r) throws IOException {
        return Files.deleteIfExists(resolve(r));
    }

    public boolean exists(ExcelFileRecord r) {
        return Files.exists(resolve(r));
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
