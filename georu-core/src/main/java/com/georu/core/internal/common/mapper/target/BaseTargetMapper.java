package com.georu.core.internal.common.mapper.target;

import org.apache.ibatis.annotations.Param;

/*
 * 기능: 공통 Target DB 매퍼 인터페이스
 *       모든 도메인의 Target 매퍼가 공통으로 구현해야 하는 메서드 정의
 * @param <T> 엔티티 타입
 */
public interface BaseTargetMapper<T> {

    /*
     * 기능: Target DB에 데이터 삽입 또는 업데이트 (공통)
     */
    int insertOrUpdate(T entity);

    /*
     * 기능: Target DB에 데이터 삽입 (공통)
     */
    int insert(T entity);

    /*
     * 기능: Target DB의 데이터 업데이트 (공통)
     */
    int update(T entity);

    /*
     * 기능: Target DB의 데이터 삭제 (공통)
     */
    int delete(@Param("id") Long id);

    /*
     * 기능: Target DB에서 ID로 데이터 조회 (공통)
     */
    T selectById(@Param("id") Long id);

    /*
     * 기능: Target DB에서 데이터 존재 여부 확인 (공통)
     */
    boolean existsById(@Param("id") Long id);
}
