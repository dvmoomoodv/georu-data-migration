package com.georu.core.internal.excel;

import com.georu.core.internal.request.TableMigrationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExcelJobParameters · ExcelFileStore")
class ExcelJobParametersAndStoreTest {

    @Nested
    class JobParams {
        @Test
        void addAndRead() {
            TableMigrationRequest req = new TableMigrationRequest();
            req.setSource("excel");
            req.setExcelFileId(" XF-1 ");
            req.setOnRowError("skip");
            JobParametersBuilder b = new JobParametersBuilder();
            ExcelJobParameters.add(b, req);
            JobParameters p = b.toJobParameters();
            assertTrue(ExcelJobParameters.isExcel(p));
            assertEquals("XF-1", ExcelJobParameters.fileId(p));
            assertEquals("SKIP", ExcelJobParameters.onRowError(p));
            assertTrue(ExcelJobParameters.skipRows(p));
        }

        @Test
        void notExcel() {
            TableMigrationRequest req = new TableMigrationRequest();
            JobParametersBuilder b = new JobParametersBuilder();
            ExcelJobParameters.add(b, req);
            JobParameters p = b.toJobParameters();
            assertFalse(ExcelJobParameters.isExcel(p));
            assertNull(ExcelJobParameters.fileId(p));
            assertEquals("STOP", ExcelJobParameters.onRowError(p), "기본 STOP");
            assertFalse(req.isExcelSource());
        }

        @Test
        void normalize() {
            assertEquals("STOP", ExcelJobParameters.normalizeOnRowError(null));
            assertEquals("SKIP", ExcelJobParameters.normalizeOnRowError(" Skip "));
            assertThrows(IllegalArgumentException.class, () -> ExcelJobParameters.normalizeOnRowError("IGNORE"));
        }
    }

    @Nested
    class Store {
        @TempDir
        Path dir;

        private ExcelFileStore store() {
            ExcelLoadProperties p = new ExcelLoadProperties();
            p.setDir(dir.resolve("excel").toString());
            return new ExcelFileStore(p);
        }

        @Test
        void foldersAndNames() {
            ExcelFileStore s = store();
            for (ExcelFolder f : ExcelFolder.values()) {
                assertTrue(Files.isDirectory(s.dir(f)), f.name());
            }
            assertEquals("XF-1_a_b.xlsx", ExcelFileStore.storedName("XF-1", "C:\\tmp\\a/b.xlsx".replace("/b", "_b")));
            assertEquals("XF-1_x_y.csv", ExcelFileStore.storedName("XF-1", "x:y.csv"));
            assertEquals("xlsx", ExcelFileStore.extension("A.XLSX"));
            assertEquals("", ExcelFileStore.extension("noext"));
            assertThrows(IllegalArgumentException.class, () -> s.resolve(ExcelFolder.PENDING, "../../etc/passwd"));
        }

        @Test
        void saveMoveDelete() throws IOException {
            ExcelFileStore s = store();
            String name = ExcelFileStore.storedName("XF-2", "t.csv");
            String sha = s.saveToPending(new ByteArrayInputStream("CODE\nA\n".getBytes(StandardCharsets.UTF_8)), name);
            assertEquals(64, sha.length());
            ExcelFileRecord r = ExcelFileRecord.builder().fileId("XF-2").storedName(name).folder("PENDING").uploadedAt(LocalDateTime.now()).build();
            assertTrue(s.exists(r));
            Path moved = s.move(r, ExcelFolder.PROCESSING);
            assertTrue(Files.exists(moved));
            assertFalse(Files.exists(s.resolve(ExcelFolder.PENDING, name)));
            r.setFolder("PROCESSING");
            Path done = s.move(r, ExcelFolder.COMPLETED);
            assertTrue(Files.exists(done));
            r.setFolder("COMPLETED");
            assertTrue(s.delete(r));
            assertFalse(s.exists(r));
        }

        @Test
        void moveRefusesOverwrite() throws IOException {
            ExcelFileStore s = store();
            String name = ExcelFileStore.storedName("XF-3", "t.csv");
            s.saveToPending(new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)), name);
            Files.writeString(s.resolve(ExcelFolder.COMPLETED, name), "old", StandardCharsets.UTF_8);
            ExcelFileRecord r = ExcelFileRecord.builder().fileId("XF-3").storedName(name).folder("PENDING").build();
            assertThrows(IOException.class, () -> s.move(r, ExcelFolder.COMPLETED));
        }
    }
}
