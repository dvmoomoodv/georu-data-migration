package com.georu.codegen;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 올릴 파일의 예제 양식 (xlsx). 첫 시트가 데이터 시트라 예제 행을 지우고 채워 그대로 올린다.
 * 열 머리에 메모(설명), Y/N · BATCH/REUSE 칸에 드롭다운, 둘째 시트에 작성 안내.
 *
 * <ul>
 *   <li>{@link #codegen()}: 코드 생성 - TOBE 정의 (업무구분 · 이관대상 · 원천 테이블 · Writer 포함)</li>
 *   <li>{@link #definition()}: 관계 분석 · ASIS 정의 - 테이블 구조만</li>
 * </ul>
 */
public final class SpecTemplate {

    /** 열 하나: 이름 · 필수 · 설명 · 예 · 드롭다운 값 */
    record Col(String name, boolean required, String help, String example, String[] choices, int width) {
    }

    private static final String[] YN = {"Y", "N"};
    private static final String[] Y = {"Y"};

    static final List<Col> CODEGEN = List.of(
            new Col("테이블명", true, "물리 테이블명 (TOBE). 영문으로 시작하는 영문 · 숫자 · 밑줄", "tb_member", null, 22),
            new Col("테이블 설명", false, "테이블 논리명. 주석 · 리포트에 쓴다 (테이블 단위, 한 행만 써도 됨)", "회원", null, 18),
            new Col("컬럼명", true, "물리 컬럼명. 테이블 안에서 컬럼 순서대로", "MEMBER_ID", null, 20),
            new Col("컬럼 설명", false, "컬럼 논리명. DTO · XML 주석에 쓴다", "회원ID", null, 18),
            new Col("타입", true, "TOBE 자료형. VARCHAR · CHAR · INT · BIGINT · DECIMAL · DATE · DATETIME · TEXT 등. VARCHAR(50) 처럼 길이를 함께 써도 된다", "BIGINT", null, 12),
            new Col("길이", false, "VARCHAR 길이, DECIMAL 은 10,2 처럼 정밀도,소수", "", null, 8),
            new Col("PK", false, "PK 컬럼이면 Y. 행 식별 · 정렬 · 필수값 검증에 쓴다", "Y", Y, 6),
            new Col("Not Null", false, "필수 컬럼이면 Y", "Y", Y, 9),
            new Col("업무구분", true, "업무 묶음 (예: 회원 · 게시판 · 코드). 값마다 패키지 하나와 Strategy 하나가 생긴다. 한글이면 화면에서 영문 패키지 이름을 넣는다 (테이블 단위, 한 행만 써도 됨)", "회원", null, 12),
            new Col("업무 패키지", false, "업무구분의 영문 패키지 이름 (소문자 영문으로 시작하는 영문·숫자, 예: member). 비우면 업무구분이 영문이면 그대로, 한글이면 사전(회원→member · 게시판→board · 과정→course …)이나 테이블 이름 접두어로 제안하고, 그래도 없으면 화면에서 입력받는다 (테이블 단위)", "member", null, 11),
            new Col("이관대상", true, "Y 면 Reader · Processor · Writer · DTO · XML 을 만든다. N 이면 만들지 않고 리포트에 제외로 남긴다 (테이블 단위)", "Y", YN, 10),
            new Col("원천 테이블", false, "ASIS 원천 테이블명 (스키마 없이). 있으면 Source SELECT 를 원천 메타로 채우고, 비면 골격(WHERE 1 = 0)으로 만든다 (테이블 단위)", "MEM_MEMBER", null, 16),
            new Col("Writer", false, "BATCH(기본, 대량 · 빠름) 또는 REUSE(행마다, 실패 행 확인). 비면 화면의 기본값. 이관대상 테이블마다 고른다 (테이블 단위). 기준: 운영 UI 안내서 \"Writer 고르기\"", "BATCH", new String[]{"BATCH", "REUSE"}, 10));

    static final List<Col> DEFINITION = List.of(
            CODEGEN.get(0), CODEGEN.get(1), CODEGEN.get(2), CODEGEN.get(3), CODEGEN.get(4), CODEGEN.get(5),
            new Col("PK", false, "PK 컬럼이면 Y. 비면 첫 컬럼 · 앞쪽 NOT NULL 키형 컬럼으로 추정한다", "Y", Y, 6),
            new Col("Not Null", false, "필수 컬럼이면 Y. PK 추정에 쓴다", "Y", Y, 9));

    static final List<Col> CODES = List.of(
            new Col("코드그룹", true, "코드그룹 ID (ASIS CODE_ID · TOBE GUBUN 등)", "GENDER", null, 16),
            new Col("코드", true, "코드값", "M", null, 12),
            new Col("코드명", true, "코드 이름. ASIS · TOBE 코드그룹 대응을 코드명 일치로 추정한다", "남자", null, 20),
            new Col("순서", false, "정렬 순서", "1", null, 8),
            new Col("사용여부", false, "Y · N (1 · 0 도 알아본다)", "Y", YN, 10));

    static final String[][] CODES_ROWS = {
            {"GENDER", "M", "남자", "1", "Y"},
            {"GENDER", "F", "여자", "2", "Y"},
            {"MEM_STAT", "10", "정상", "1", "Y"},
            {"MEM_STAT", "90", "탈퇴", "2", "Y"},
    };

    /** 예제 행: 일반 · 공통 컬럼 · REUSE · 원천 없음(골격) · 이관대상 아님 */
    static final String[][] CODEGEN_ROWS = {
            {"tb_member", "회원", "MEMBER_ID", "회원ID", "BIGINT", "", "Y", "Y", "회원", "member", "Y", "MEM_MEMBER", "BATCH"},
            {"tb_member", "", "MEMBER_NM", "회원명", "VARCHAR", "50", "", "Y", "", "", "", "", ""},
            {"tb_member", "", "BIRTH_YMD", "생년월일", "VARCHAR", "8", "", "", "", "", "", "", ""},
            {"tb_member", "", "POINT", "포인트", "DECIMAL", "10,2", "", "", "", "", "", "", ""},
            {"tb_member", "", "REG_DATE", "등록일시", "DATETIME", "", "", "", "", "", "", "", ""},
            {"tb_member", "", "REG_ID", "등록자", "VARCHAR", "20", "", "", "", "", "", "", ""},
            {"tb_member", "", "USE_YN", "사용여부", "CHAR", "1", "", "", "", "", "", "", ""},
            {"tb_code", "공통코드", "CODE_ID", "코드", "VARCHAR", "20", "Y", "Y", "코드", "", "Y", "CD_CODE", "REUSE"},
            {"tb_code", "", "CODE_NM", "코드명", "VARCHAR", "100", "", "Y", "", "", "", "", ""},
            {"tb_member_log", "로그인 이력", "LOG_ID", "이력ID (채번)", "BIGINT", "", "Y", "Y", "회원", "member", "Y", "", "BATCH"},
            {"tb_member_log", "", "MEMBER_ID", "회원ID", "BIGINT", "", "", "Y", "", "", "", "", ""},
            {"tb_member_log", "", "LOGIN_DATE", "로그인일시", "VARCHAR", "14", "", "", "", "", "", "", ""},
            {"tb_temp_stat", "임시 집계", "STAT_YMD", "집계일", "VARCHAR", "8", "Y", "Y", "회원", "member", "N", "", ""},
    };

    static final String[][] DEFINITION_ROWS = {
            {"MEMBER", "회원", "MEMBER_SEQ", "회원번호", "NUMBER", "10", "Y", "Y"},
            {"MEMBER", "", "MEMBER_NM", "회원명", "VARCHAR2", "50", "", "Y"},
            {"BOARD_POST", "게시글", "POST_SEQ", "글번호", "NUMBER", "10", "Y", "Y"},
            {"BOARD_POST", "", "WRITER_MEMBER_SEQ", "작성자 회원번호", "NUMBER", "10", "", ""},
            {"BOARD_POST", "", "PARENT_POST_SEQ", "상위 글번호", "NUMBER", "10", "", ""},
    };

    private SpecTemplate() {
    }

    /** 코드 생성용 TOBE 정의 양식 */
    public static byte[] codegen() {
        return write("TOBE 정의", CODEGEN, CODEGEN_ROWS, new String[]{
                "코드 생성용 TOBE 컬럼 정의 양식",
                "이 시트(첫 시트)를 채워 그대로 올린다. 예제 행은 지우고 쓴다. 첫 행(열 이름)은 바꾸지 않는다. 열 이름 칸에 마우스를 올리면 설명이 나온다.",
                "한 행 = 컬럼 하나. 테이블 단위 값(테이블 설명 · 업무구분 · 업무 패키지 · 이관대상 · 원천 테이블 · Writer)은 그 테이블의 첫 행에 한 번만 써도 되고 모든 행에 같게 써도 된다. 다른 값을 쓰면 경고하고 처음 값을 쓴다.",
                "필수 열: 테이블명 · 컬럼명 · 타입 · 업무구분 · 이관대상. 나머지는 비워도 된다.",
                "",
                "예제 테이블",
                "tb_member: 원천 MEM_MEMBER 가 있는 일반 테이블 (Writer BATCH). REG_DATE · REG_ID · USE_YN 은 공통 컬럼 규칙으로 원천의 흔한 이름을 찾고 비면 기본값",
                "tb_code: 작은 코드 테이블이라 Writer REUSE (행마다, 실패 행 확인). 업무 패키지를 비워 두어 사전으로 '코드 → code' 가 제안된다",
                "tb_member_log: 원천 테이블이 비어 골격(WHERE 1 = 0)으로 만들어진다. LOG_ID 는 원천이 없는 PK 라 채번 안내(TODO PK)가 붙는다",
                "tb_temp_stat: 이관대상 N 이라 만들지 않고 리포트에 제외로 남는다",
                "",
                "Writer 고르기 (이관대상 테이블마다)",
                "BATCH(기본): 모아서 한 번에 보낸다. 이력 · 로그 · 거래 같은 대량 테이블",
                "REUSE: 행마다 바로 실행해 실패한 행이 로그에 남는다. 회원 마스터 첫 적재 · 조직 · 공통코드 · 첨부(큰 행) · 원인 행을 찾아야 할 때",
                "비워 두면 코드 생성 화면의 \"Writer 기본\" 값을 쓴다. 자세한 기준: 운영 UI 안내서 \"Writer 고르기\"",
        });
    }

    /** 관계 분석 · ASIS 정의 양식 (테이블 구조만) */
    public static byte[] definition() {
        return write("테이블 정의", DEFINITION, DEFINITION_ROWS, new String[]{
                "테이블 구조 정의 양식 (관계 분석 · ASIS 정의)",
                "이 시트(첫 시트)를 채워 그대로 올린다. 예제 행은 지우고 쓴다. 첫 행(열 이름)은 바꾸지 않는다.",
                "한 행 = 컬럼 하나. 컬럼은 테이블 안의 실제 순서대로 적는다 (PK 추정에 쓴다).",
                "필수 열: 테이블명 · 컬럼명 · 타입. PK 열이 비면 첫 컬럼 · 앞쪽 NOT NULL 키형(…_ID · _SEQ · _CODE · _NO) 컬럼으로 추정한다.",
                "",
                "예제: BOARD_POST.WRITER_MEMBER_SEQ → MEMBER.MEMBER_SEQ (접두어 + 부모 키), PARENT_POST_SEQ → BOARD_POST.POST_SEQ (자기참조)",
        });
    }

    /** 공통코드 표 양식 (매핑 정의서 초안의 ASIS · TOBE 코드표) */
    public static byte[] codes() {
        return write("공통코드", CODES, CODES_ROWS, new String[]{
                "공통코드 표 양식 (매핑 정의서 초안)",
                "ASIS · TOBE 코드표를 각각 이 양식으로 올린다. 이 시트(첫 시트)를 채워 그대로 올리고, 예제 행은 지운다.",
                "한 행 = 코드 하나. 필수 열: 코드그룹 · 코드 · 코드명.",
                "코드그룹 대응은 코드명이 겹치는 정도로 추정한다 (2건 이상 · 50% 이상). 코드는 코드명이 같은 것끼리 잇는다.",
        });
    }

    private static byte[] write(String sheetName, List<Col> cols, String[][] rows, String[] guide) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle req = fill(wb, IndexedColors.LIGHT_ORANGE, bold);
            CellStyle opt = fill(wb, IndexedColors.GREY_25_PERCENT, bold);
            CellStyle sample = wb.createCellStyle();
            Font gray = wb.createFont();
            gray.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            sample.setFont(gray);

            Sheet s = wb.createSheet(sheetName);
            Drawing<?> drawing = s.createDrawingPatriarch();
            Row h = s.createRow(0);
            for (int i = 0; i < cols.size(); i++) {
                Col c = cols.get(i);
                Cell cell = h.createCell(i);
                cell.setCellValue(c.name());
                cell.setCellStyle(c.required() ? req : opt);
                ClientAnchor anchor = wb.getCreationHelper().createClientAnchor();
                anchor.setCol1(i);
                anchor.setCol2(i + 4);
                anchor.setRow1(0);
                anchor.setRow2(5);
                Comment comment = drawing.createCellComment(anchor);
                comment.setString(wb.getCreationHelper().createRichTextString((c.required() ? "[필수] " : "[선택] ") + c.help()
                        + (c.example().isEmpty() ? "" : "\n예: " + c.example())));
                cell.setCellComment(comment);
                s.setColumnWidth(i, c.width() * 256);
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r + 1);
                for (int i = 0; i < rows[r].length; i++) {
                    Cell cell = row.createCell(i);
                    cell.setCellValue(rows[r][i]);
                    cell.setCellStyle(sample);
                }
            }
            s.createFreezePane(0, 1);
            DataValidationHelper dv = s.getDataValidationHelper();
            for (int i = 0; i < cols.size(); i++) {
                if (cols.get(i).choices() == null) continue;
                DataValidation v = dv.createValidation(dv.createExplicitListConstraint(cols.get(i).choices()),
                        new CellRangeAddressList(1, 5000, i, i));
                v.setShowErrorBox(true);
                v.createErrorBox("값 확인", String.join(" · ", cols.get(i).choices()) + " 중 하나 (또는 빈칸)");
                s.addValidationData(v);
            }

            Sheet g = wb.createSheet("작성 안내");
            g.setColumnWidth(0, 14 * 256);
            g.setColumnWidth(1, 8 * 256);
            g.setColumnWidth(2, 110 * 256);
            int r = 0;
            for (String line : guide) {
                Row row = g.createRow(r++);
                Cell cell = row.createCell(0);
                cell.setCellValue(line);
                if (r == 1) cell.setCellStyle(opt);
            }
            r++;
            Row gh = g.createRow(r++);
            String[] head = {"열", "필수", "설명"};
            for (int i = 0; i < head.length; i++) {
                Cell cell = gh.createCell(i);
                cell.setCellValue(head[i]);
                cell.setCellStyle(opt);
            }
            for (Col c : cols) {
                Row row = g.createRow(r++);
                row.createCell(0).setCellValue(c.name());
                row.createCell(1).setCellValue(c.required() ? "필수" : "");
                row.createCell(2).setCellValue(c.help() + (c.choices() == null ? "" : " (값: " + String.join(" · ", c.choices()) + ")"));
            }
            r++;
            g.createRow(r++).createCell(0).setCellValue("CSV(UTF-8) 로 저장해 올려도 된다. 열 이름은 한글 · 영문 · 업체 정의서 이름도 알아보고, 모르는 열은 무시한다.");
            g.createRow(r).createCell(0).setCellValue("빈칸 · [NULL] = 값 없음.");
            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CellStyle fill(XSSFWorkbook wb, IndexedColors color, Font font) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(color.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setBorderBottom(BorderStyle.THIN);
        s.setFont(font);
        return s;
    }
}
