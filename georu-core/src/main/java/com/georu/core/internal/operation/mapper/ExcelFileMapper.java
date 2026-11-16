package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.excel.ExcelFileRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 엑셀 이관 파일 대장 매퍼 (migration_excel_file, V7). SQL 은 H2(MODE=MySQL)·MySQL 공통 문법만 쓴다.
 */
@Mapper
public interface ExcelFileMapper {

    @Insert("""
        INSERT INTO migration_excel_file (file_id, original_name, stored_name, folder, plugin_name, table_name, format, status,
            file_size, sha256, row_count, uploaded_by, uploaded_at, updated_at)
        VALUES (#{fileId}, #{originalName}, #{storedName}, #{folder}, #{pluginName}, #{tableName}, #{format}, #{status},
            #{fileSize}, #{sha256}, #{rowCount}, #{uploadedBy}, #{uploadedAt}, #{updatedAt})
    """)
    int insert(ExcelFileRecord r);

    @Select("SELECT * FROM migration_excel_file WHERE file_id = #{fileId}")
    ExcelFileRecord select(@Param("fileId") String fileId);

    @Select("""
        <script>
        SELECT * FROM migration_excel_file
        <where>
            <if test="status != null and status != ''">AND status = #{status}</if>
            <if test="tableName != null and tableName != ''">AND table_name = #{tableName}</if>
            <if test="pluginName != null and pluginName != ''">AND plugin_name = #{pluginName}</if>
        </where>
        ORDER BY uploaded_at DESC, file_id DESC
        LIMIT #{limit}
        </script>
    """)
    List<ExcelFileRecord> selectList(@Param("status") String status, @Param("pluginName") String pluginName,
                                     @Param("tableName") String tableName, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM migration_excel_file WHERE status = #{status}")
    int countByStatus(@Param("status") String status);

    /** 검증 결과 반영 */
    @Update("""
        UPDATE migration_excel_file SET status = #{status}, row_count = #{rowCount}, block_count = #{blockCount},
            warn_count = #{warnCount}, message = #{message}, issues_json = #{issuesJson},
            validated_at = #{validatedAt}, updated_at = #{updatedAt}
        WHERE file_id = #{fileId}
    """)
    int updateValidation(ExcelFileRecord r);

    /** 실행 시작 */
    @Update("""
        UPDATE migration_excel_file SET status = #{status}, folder = #{folder}, stored_name = #{storedName},
            migration_id = #{migrationId}, on_row_error = #{onRowError}, last_test_mode = #{lastTestMode},
            written_count = NULL, skipped_count = NULL, started_at = #{startedAt}, finished_at = NULL,
            message = #{message}, updated_at = #{updatedAt}
        WHERE file_id = #{fileId}
    """)
    int updateRunStart(ExcelFileRecord r);

    /** 실행 종료 */
    @Update("""
        UPDATE migration_excel_file SET status = #{status}, folder = #{folder}, stored_name = #{storedName},
            written_count = #{writtenCount}, skipped_count = #{skippedCount}, message = #{message},
            issues_json = #{issuesJson}, finished_at = #{finishedAt}, updated_at = #{updatedAt}
        WHERE file_id = #{fileId}
    """)
    int updateRunEnd(ExcelFileRecord r);

    /** 폴더·저장명만 변경 (실행 외 이동) */
    @Update("UPDATE migration_excel_file SET folder = #{folder}, stored_name = #{storedName}, updated_at = #{updatedAt} WHERE file_id = #{fileId}")
    int updateLocation(ExcelFileRecord r);

    @Delete("DELETE FROM migration_excel_file WHERE file_id = #{fileId}")
    int delete(@Param("fileId") String fileId);
}
