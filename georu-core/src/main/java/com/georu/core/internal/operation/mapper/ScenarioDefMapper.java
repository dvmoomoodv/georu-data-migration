package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.scenarioflow.ScenarioDefRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 사용자 정의 시나리오 매퍼 (migration_scenario_def). ScenarioRegistry 만 쓴다. 삭제 SQL 은 두지 않는다(수동 삭제 원칙).
 * SQL 은 H2(MODE=MySQL)·MySQL 공통 문법만 쓴다.
 */
@Mapper
public interface ScenarioDefMapper {

    @Select("SELECT * FROM migration_scenario_def ORDER BY scenario_id")
    List<ScenarioDefRecord> selectAll();

    @Select("SELECT * FROM migration_scenario_def WHERE scenario_id = #{id}")
    ScenarioDefRecord select(@Param("id") String id);

    @Insert("""
        INSERT INTO migration_scenario_def (scenario_id, name, definition_json, enabled, version, created_by, created_at, updated_by, updated_at)
        VALUES (#{scenarioId}, #{name}, #{definitionJson}, #{enabled}, 1, #{createdBy}, #{createdAt}, #{updatedBy}, #{updatedAt})
    """)
    int insert(ScenarioDefRecord r);

    @Update("""
        UPDATE migration_scenario_def SET name = #{name}, definition_json = #{definitionJson}, version = version + 1,
            updated_by = #{updatedBy}, updated_at = #{updatedAt}
        WHERE scenario_id = #{scenarioId}
    """)
    int update(ScenarioDefRecord r);

    @Update("UPDATE migration_scenario_def SET enabled = #{enabled}, updated_by = #{by}, updated_at = #{at} WHERE scenario_id = #{id}")
    int updateEnabled(@Param("id") String id, @Param("enabled") boolean enabled, @Param("by") String by, @Param("at") LocalDateTime at);

    // ---------------------------------------------------------------- 변경 이력 (V6)

    @Insert("""
        INSERT INTO migration_scenario_def_history (scenario_id, version, action, name, definition_json, saved_by, saved_at)
        VALUES (#{scenarioId}, #{version}, #{action}, #{name}, #{definitionJson}, #{savedBy}, #{savedAt})
    """)
    int insertHistory(com.georu.core.internal.scenarioflow.ScenarioDefHistory h);

    @Select("SELECT id, scenario_id, version, action, name, saved_by, saved_at FROM migration_scenario_def_history WHERE scenario_id = #{id} ORDER BY id DESC")
    List<com.georu.core.internal.scenarioflow.ScenarioDefHistory> selectHistory(@Param("id") String id);

    @Select("SELECT * FROM migration_scenario_def_history WHERE id = #{historyId}")
    com.georu.core.internal.scenarioflow.ScenarioDefHistory selectHistoryEntry(@Param("historyId") long historyId);
}
