package com.georu.examples.demo.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_post_file : 게시글첨부 ← ASIS(Oracle) BRD_POST_FILE : 첨부파일 (매핑 추정, 미확정)
 * TbPostFileSourceMapper.selectPageData 결과 (ASIS 값을 TOBE 컬럼명·타입으로 변환한 값)
 * Reader 출력 = Processor 입력. Processor 가 TbPostFileTargetDto 로 옮긴다.
 * 자동 생성: 코드 생성기
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbPostFileSourceDto {

    /** FILE_ID BIGINT : 파일ID */
    private Long fileId;

    /** ASIS_ID BIGINT : ASIS파일ID */
    private Long asisId;

    /** POST_ID BIGINT : 게시글ID */
    private Long postId;

    /** ORIGIN_NM VARCHAR(255) : 원본파일명 */
    private String originNm;

    /** FILE_PATH VARCHAR(500) : 저장경로 */
    private String filePath;

    /** FILE_SIZE BIGINT : 크기 */
    private Long fileSize;

    /** MIME_TYPE VARCHAR(100) : MIME */
    private String mimeType;

    /** DOWNLOAD_CNT INT : 다운로드수 */
    private Integer downloadCnt;

    /** INUSERID VARCHAR(40) : 등록자 */
    private String inuserid;

    /** INDATE VARCHAR(14) : 등록일시 */
    private String indate;

    /** LUSERID VARCHAR(40) : 수정자 */
    private String luserid;

    /** LDATE VARCHAR(14) : 수정일시 */
    private String ldate;
}
