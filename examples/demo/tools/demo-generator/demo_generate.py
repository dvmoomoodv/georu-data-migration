#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
데모 플러그인(사내 포털: 코드·회원·게시판·통계·시퀀스) 생성기 - 단일 사양(SPEC)에서 아래를 만든다.

  1) 생성기 입력 CSV (코드 생성기 입력 형식)
     document/2.logic_analysis/demo/asis-table-column.csv
     document/2.logic_analysis/demo/tobe-table-column.csv
     document/docs/demo/05_tobe_테이블_인벤토리.csv
     document/docs/demo/06_asis_테이블_인벤토리.csv
  2) Docker 초기화 SQL
     docker/oracle/init-sql/40_demo_user_sys.sql      (SYSTEM 으로 실행: DEMO 사용자 생성)
     docker/oracle/init-sql/41_demo_schema.sql        (DEMO 로 실행: 테이블·PK·FK·인덱스·시퀀스, migrator(앱 접속 계정) 에 SELECT 권한)
     docker/oracle/init-sql/42_demo_data.sql          (DEMO 로 실행: 기본 데이터)
     docker/oracle/init-sql/43_demo_dirty.sql         (--dirty 일 때만: 오류 데이터)
     docker/mysql/init/20_demo_tobe_schema.sql        (demo_tobe DB + TOBE 테이블, 데이터 없음)
     docker/mysql/tools/21_demo_tobe_reset.sql         (개발 환경 전용: TOBE 비우기. 자동 실행 안 됨)

실행 (저장소 루트):
  python3 tools/demo-generator/demo_generate.py                       # small (로그인 이력 1만)
  python3 tools/demo-generator/demo_generate.py --rows-profile large  # 로그인 이력 10만, 메뉴 통계 1.2만
  python3 tools/demo-generator/demo_generate.py --dirty               # 43_demo_dirty.sql 생성
  python3 tools/demo-generator/demo_generate.py --seed 7

설계: document/docs/demo/00_데모_서비스_설명.md
"""
import argparse
import csv
import datetime as dt
import hashlib
import io
import os
import random
import sys

NL = "\n"
ASIS_SCHEMA = "DEMO"
TOBE_DB = "demo_tobe"
APP_USER = "migrator"

# ─────────────────────────────────────────────────────────────────────────────
# 사양: (플러그인, 업무코드, 업무분류, ASIS 테이블, ASIS 설명, TOBE 테이블, TOBE 논리명, 정렬키(ASIS), PK(TOBE))
#   컬럼: asis = (이름, Oracle 타입, NN, 코멘트) / tobe = (논리명, 물리명, TYPE, 길이, NN, 코멘트)
# ─────────────────────────────────────────────────────────────────────────────
AUDIT_ASIS = [("REG_ID", "VARCHAR2(40)", "N", "등록자"), ("REG_DATE", "DATE", "N", "등록일시"),
              ("MOD_ID", "VARCHAR2(40)", "N", "수정자"), ("MOD_DATE", "DATE", "N", "수정일시")]
AUDIT_TOBE = [("등록자", "INUSERID", "VARCHAR", "40", "N", "등록자"), ("등록일시", "INDATE", "VARCHAR", "14", "N", "등록일시"),
              ("수정자", "LUSERID", "VARCHAR", "40", "N", "수정자"), ("수정일시", "LDATE", "VARCHAR", "14", "N", "수정일시")]

SPEC = [
    dict(plugin="demo.code", biz="01", bizname="코드관리", asis="CD_GROUP", adesc="코드 그룹", tobe="tb_code_group", tdesc="코드그룹",
         sort="GROUP_CD", pk=["GROUP_CD"],
         acols=[("GROUP_CD", "VARCHAR2(20)", "Y", "그룹코드"), ("GROUP_NM", "VARCHAR2(100)", "Y", "그룹명"),
                ("USE_YN", "CHAR(1)", "N", "사용여부 Y/N"), ("SORT_NO", "NUMBER(5)", "N", "정렬순서")] + AUDIT_ASIS,
         tcols=[("그룹코드", "GROUP_CD", "VARCHAR", "20", "Y", "그룹코드"), ("그룹명", "GROUP_NM", "VARCHAR", "100", "Y", "그룹명"),
                ("사용여부", "USE_YN", "CHAR", "1", "N", "사용여부 1/0"), ("정렬순서", "SORT_NO", "INT", "", "N", "정렬순서")] + AUDIT_TOBE),
    dict(plugin="demo.code", biz="01", bizname="코드관리", asis="CD_CODE", adesc="코드", tobe="tb_code", tdesc="코드",
         sort="GROUP_CD, CODE", pk=["GROUP_CD", "CODE"],
         acols=[("GROUP_CD", "VARCHAR2(20)", "Y", "그룹코드"), ("CODE", "VARCHAR2(20)", "Y", "코드"), ("CODE_NM", "VARCHAR2(100)", "Y", "코드명"),
                ("CODE_DESC", "VARCHAR2(500)", "N", "설명"), ("USE_YN", "CHAR(1)", "N", "사용여부 Y/N"), ("SORT_NO", "NUMBER(5)", "N", "정렬순서")] + AUDIT_ASIS,
         tcols=[("그룹코드", "GROUP_CD", "VARCHAR", "20", "Y", "그룹코드"), ("코드", "CODE", "VARCHAR", "20", "Y", "코드"), ("코드명", "CODE_NM", "VARCHAR", "100", "Y", "코드명"),
                ("설명", "CODE_DESC", "VARCHAR", "500", "N", "설명"), ("사용여부", "USE_YN", "CHAR", "1", "N", "사용여부 1/0"), ("정렬순서", "SORT_NO", "INT", "", "N", "정렬순서")] + AUDIT_TOBE),
    dict(plugin="demo.member", biz="02", bizname="회원관리", asis="MEM_GRADE", adesc="회원 등급", tobe="tb_member_grade", tdesc="회원등급",
         sort="GRADE_CD", pk=["GRADE_CD"],
         acols=[("GRADE_CD", "VARCHAR2(10)", "Y", "등급코드"), ("GRADE_NM", "VARCHAR2(50)", "Y", "등급명"), ("MIN_POINT", "NUMBER(10)", "N", "최소포인트"),
                ("DISCOUNT_RATE", "NUMBER(5,2)", "N", "할인율"), ("USE_YN", "CHAR(1)", "N", "사용여부")] + AUDIT_ASIS,
         tcols=[("등급코드", "GRADE_CD", "VARCHAR", "10", "Y", "등급코드"), ("등급명", "GRADE_NM", "VARCHAR", "50", "Y", "등급명"), ("최소포인트", "MIN_POINT", "INT", "", "N", "최소포인트"),
                ("할인율", "DISCOUNT_RATE", "DECIMAL", "5,2", "N", "할인율"), ("사용여부", "USE_YN", "CHAR", "1", "N", "사용여부 1/0")] + AUDIT_TOBE),
    dict(plugin="demo.member", biz="02", bizname="회원관리", asis="MEM_MEMBER", adesc="회원", tobe="tb_member", tdesc="회원",
         sort="MEMBER_ID", pk=["MEMBER_ID"],
         acols=[("MEMBER_ID", "NUMBER(10)", "Y", "회원ID"), ("LOGIN_ID", "VARCHAR2(50)", "Y", "로그인ID"), ("MEMBER_NM", "VARCHAR2(50)", "Y", "회원명"),
                ("PASSWORD_HASH", "VARCHAR2(128)", "N", "비밀번호 해시"), ("GENDER", "CHAR(1)", "N", "성별 M/F"), ("BIRTH_DT", "DATE", "N", "생년월일"),
                ("EMAIL", "VARCHAR2(100)", "N", "이메일"), ("PHONE", "VARCHAR2(20)", "N", "전화 (하이픈 포함)"), ("GRADE_CD", "VARCHAR2(10)", "N", "등급코드"),
                ("STATUS", "CHAR(1)", "Y", "상태 A/I/D"), ("JOIN_DT", "DATE", "Y", "가입일시"), ("LAST_LOGIN_DT", "DATE", "N", "최근로그인"),
                ("POINT", "NUMBER(10)", "N", "포인트"), ("REMARK", "VARCHAR2(100)", "N", "비고(오류데이터 표식)")] + AUDIT_ASIS,
         tcols=[("회원ID", "MEMBER_ID", "INT", "", "Y", "회원ID"), ("로그인ID", "LOGIN_ID", "VARCHAR", "50", "Y", "로그인ID"), ("회원명", "MEMBER_NM", "VARCHAR", "50", "Y", "회원명"),
                ("비밀번호해시", "PASSWORD_HASH", "VARCHAR", "128", "N", "비밀번호 해시"), ("성별", "GENDER", "CHAR", "1", "N", "성별 1/2"), ("생년월일", "BIRTH_YMD", "VARCHAR", "8", "N", "생년월일"),
                ("이메일", "EMAIL", "VARCHAR", "100", "N", "이메일"), ("전화", "PHONE", "VARCHAR", "20", "N", "전화 (숫자만)"), ("등급코드", "GRADE_CD", "VARCHAR", "10", "N", "등급코드"),
                ("상태", "STATUS", "VARCHAR", "10", "Y", "ACTIVE/INACTIVE"), ("가입일시", "JOIN_DATE", "VARCHAR", "14", "Y", "가입일시"), ("최근로그인", "LAST_LOGIN_DATE", "VARCHAR", "14", "N", "최근로그인"),
                ("포인트", "POINT", "INT", "", "N", "포인트")] + AUDIT_TOBE),
    dict(plugin="demo.member", biz="02", bizname="회원관리", asis="MEM_LOGIN_HIS", adesc="로그인 이력", tobe="tb_member_login_log", tdesc="로그인이력",
         sort="LOGIN_HIS_ID", pk=["LOGIN_LOG_ID"],
         acols=[("LOGIN_HIS_ID", "NUMBER(19)", "Y", "이력ID"), ("MEMBER_ID", "NUMBER(10)", "Y", "회원ID"), ("LOGIN_DT", "DATE", "Y", "로그인일시"),
                ("LOGIN_IP", "VARCHAR2(45)", "N", "IP"), ("DEVICE_CD", "VARCHAR2(10)", "N", "기기 PC/MOBILE/TABLET"), ("RESULT_CD", "VARCHAR2(10)", "N", "결과 SUCCESS/FAIL/LOCK"),
                ("FAIL_REASON", "VARCHAR2(200)", "N", "실패사유"), ("REG_DATE", "DATE", "N", "등록일시")],
         tcols=[("이력ID", "LOGIN_LOG_ID", "BIGINT", "", "Y", "이력ID (TOBE 시퀀스 채번)"), ("ASIS이력ID", "ASIS_ID", "BIGINT", "", "N", "ASIS LOGIN_HIS_ID (역추적)"), ("회원ID", "MEMBER_ID", "INT", "", "Y", "회원ID"), ("로그인일시", "LOGIN_DATE", "VARCHAR", "14", "Y", "로그인일시"),
                ("IP", "LOGIN_IP", "VARCHAR", "45", "N", "IP"), ("기기", "DEVICE_CD", "VARCHAR", "10", "N", "기기"), ("결과", "RESULT_CD", "VARCHAR", "10", "N", "결과"),
                ("실패사유", "FAIL_REASON", "VARCHAR", "200", "N", "실패사유"), ("등록일시", "INDATE", "VARCHAR", "14", "N", "등록일시")]),
    dict(plugin="demo.board", biz="03", bizname="게시판", asis="BRD_BOARD", adesc="게시판 마스터", tobe="tb_board", tdesc="게시판",
         sort="BOARD_ID", pk=["BOARD_ID"],
         acols=[("BOARD_ID", "NUMBER(10)", "Y", "게시판ID"), ("BOARD_CD", "VARCHAR2(30)", "Y", "게시판코드"), ("BOARD_NM", "VARCHAR2(100)", "Y", "게시판명"),
                ("BOARD_TYPE_CD", "VARCHAR2(10)", "N", "유형 NOTICE/FREE/QNA/FAQ"), ("USE_YN", "CHAR(1)", "N", "사용여부"), ("ANON_YN", "CHAR(1)", "N", "익명허용"),
                ("ATTACH_LIMIT", "NUMBER(3)", "N", "첨부 최대 수")] + AUDIT_ASIS,
         tcols=[("게시판ID", "BOARD_ID", "INT", "", "Y", "게시판ID"), ("게시판코드", "BOARD_CD", "VARCHAR", "30", "Y", "게시판코드"), ("게시판명", "BOARD_NM", "VARCHAR", "100", "Y", "게시판명"),
                ("유형", "BOARD_TYPE_CD", "VARCHAR", "10", "N", "유형"), ("사용여부", "USE_YN", "CHAR", "1", "N", "사용여부 1/0"), ("익명허용", "ANON_YN", "CHAR", "1", "N", "익명허용 1/0"),
                ("첨부최대수", "ATTACH_LIMIT", "TINYINT", "", "N", "첨부 최대 수")] + AUDIT_TOBE),
    dict(plugin="demo.board", biz="03", bizname="게시판", asis="BRD_POST", adesc="게시글", tobe="tb_post", tdesc="게시글",
         sort="POST_ID", pk=["POST_ID"],
         acols=[("POST_ID", "NUMBER(19)", "Y", "게시글ID"), ("BOARD_ID", "NUMBER(10)", "Y", "게시판ID"), ("TITLE", "VARCHAR2(200)", "Y", "제목"),
                ("CONTENT", "CLOB", "N", "본문"), ("WRITER_ID", "NUMBER(10)", "N", "작성자 회원ID"), ("VIEW_CNT", "NUMBER(10)", "N", "조회수"),
                ("NOTICE_YN", "CHAR(1)", "N", "공지여부"), ("SECRET_YN", "CHAR(1)", "N", "비밀글"), ("STATUS", "CHAR(1)", "Y", "상태 A/D")] + AUDIT_ASIS,
         tcols=[("게시글ID", "POST_ID", "BIGINT", "", "Y", "게시글ID"), ("게시판ID", "BOARD_ID", "INT", "", "Y", "게시판ID"), ("제목", "TITLE", "VARCHAR", "200", "Y", "제목"),
                ("본문", "CONTENT", "LONGTEXT", "", "N", "본문"), ("작성자", "WRITER_ID", "INT", "", "N", "작성자 회원ID"), ("조회수", "VIEW_CNT", "INT", "", "N", "조회수"),
                ("공지여부", "NOTICE_YN", "CHAR", "1", "N", "공지여부 1/0"), ("비밀글", "SECRET_YN", "CHAR", "1", "N", "비밀글 1/0"), ("상태", "STATUS", "VARCHAR", "10", "Y", "ACTIVE/DELETED")] + AUDIT_TOBE),
    dict(plugin="demo.board", biz="03", bizname="게시판", asis="BRD_POST_FILE", adesc="첨부파일", tobe="tb_post_file", tdesc="게시글첨부",
         sort="FILE_ID", pk=["FILE_ID"],
         acols=[("FILE_ID", "NUMBER(19)", "Y", "파일ID"), ("POST_ID", "NUMBER(19)", "Y", "게시글ID"), ("ORIGIN_NM", "VARCHAR2(255)", "Y", "원본파일명"),
                ("STORED_PATH", "VARCHAR2(500)", "Y", "저장경로 /Upload_Board/..."), ("FILE_SIZE", "NUMBER(19)", "N", "크기(byte)"), ("MIME_TYPE", "VARCHAR2(100)", "N", "MIME"),
                ("DOWNLOAD_CNT", "NUMBER(10)", "N", "다운로드수"), ("REMARK", "VARCHAR2(100)", "N", "비고(오류데이터 표식)")] + AUDIT_ASIS,
         tcols=[("파일ID", "FILE_ID", "BIGINT", "", "Y", "파일ID (TOBE 시퀀스 채번)"), ("ASIS파일ID", "ASIS_ID", "BIGINT", "", "N", "ASIS FILE_ID (역추적)"), ("게시글ID", "POST_ID", "BIGINT", "", "Y", "게시글ID"), ("원본파일명", "ORIGIN_NM", "VARCHAR", "255", "Y", "원본파일명"),
                ("저장경로", "FILE_PATH", "VARCHAR", "500", "Y", "저장경로 file/board/..."), ("크기", "FILE_SIZE", "BIGINT", "", "N", "크기(byte)"), ("MIME", "MIME_TYPE", "VARCHAR", "100", "N", "MIME"),
                ("다운로드수", "DOWNLOAD_CNT", "INT", "", "N", "다운로드수")] + AUDIT_TOBE),
    dict(plugin="demo.board", biz="03", bizname="게시판", asis="BRD_COMMENT", adesc="댓글", tobe="tb_post_comment", tdesc="게시글댓글",
         sort="COMMENT_ID", pk=["COMMENT_ID"],
         acols=[("COMMENT_ID", "NUMBER(19)", "Y", "댓글ID"), ("POST_ID", "NUMBER(19)", "Y", "게시글ID"), ("PARENT_ID", "NUMBER(19)", "N", "부모댓글ID"),
                ("WRITER_ID", "NUMBER(10)", "N", "작성자 회원ID"), ("CONTENT", "VARCHAR2(2000)", "Y", "내용"), ("DEPTH", "NUMBER(2)", "N", "깊이"),
                ("STATUS", "CHAR(1)", "Y", "상태 A/D")] + AUDIT_ASIS,
         tcols=[("댓글ID", "COMMENT_ID", "BIGINT", "", "Y", "댓글ID"), ("게시글ID", "POST_ID", "BIGINT", "", "Y", "게시글ID"), ("부모댓글ID", "PARENT_ID", "BIGINT", "", "N", "부모댓글ID"),
                ("작성자", "WRITER_ID", "INT", "", "N", "작성자 회원ID"), ("내용", "CONTENT", "VARCHAR", "2000", "Y", "내용"), ("깊이", "DEPTH", "TINYINT", "", "N", "깊이"),
                ("상태", "STATUS", "VARCHAR", "10", "Y", "ACTIVE/DELETED")] + AUDIT_TOBE),
    dict(plugin="demo.stats", biz="04", bizname="통계", asis="STAT_DAILY_VISIT", adesc="일별 방문 통계", tobe="tb_stat_daily", tdesc="일별통계",
         sort="STAT_DT, BOARD_ID", pk=["STAT_ID"],
         acols=[("STAT_DT", "DATE", "Y", "통계일"), ("BOARD_ID", "NUMBER(10)", "Y", "게시판ID"), ("VISIT_CNT", "NUMBER(10)", "N", "방문수"),
                ("UNIQUE_VISIT_CNT", "NUMBER(10)", "N", "순방문수"), ("POST_CNT", "NUMBER(10)", "N", "게시글수"), ("COMMENT_CNT", "NUMBER(10)", "N", "댓글수"),
                ("AVG_STAY_SEC", "NUMBER(8,2)", "N", "평균체류초"), ("REG_DATE", "DATE", "N", "등록일시")],
         tcols=[("통계ID", "STAT_ID", "BIGINT", "", "Y", "통계ID (TOBE 대리키, 시퀀스 채번)"), ("통계일", "STAT_YMD", "VARCHAR", "8", "Y", "통계일"), ("게시판ID", "BOARD_ID", "INT", "", "Y", "게시판ID"), ("방문수", "VISIT_CNT", "INT", "", "N", "방문수"),
                ("순방문수", "UNIQUE_VISIT_CNT", "INT", "", "N", "순방문수"), ("게시글수", "POST_CNT", "INT", "", "N", "게시글수"), ("댓글수", "COMMENT_CNT", "INT", "", "N", "댓글수"),
                ("평균체류초", "AVG_STAY_SEC", "DECIMAL", "8,2", "N", "평균체류초"), ("등록일시", "INDATE", "VARCHAR", "14", "N", "등록일시")]),
    dict(plugin="demo.stats", biz="04", bizname="통계", asis="STAT_MENU_HIT", adesc="메뉴 클릭 통계(월)", tobe="tb_stat_menu", tdesc="메뉴통계",
         sort="STAT_YM, MENU_CD, DEVICE_CD", pk=["STAT_MENU_ID"],
         acols=[("STAT_YM", "VARCHAR2(6)", "Y", "통계연월"), ("MENU_CD", "VARCHAR2(20)", "Y", "메뉴코드"), ("DEVICE_CD", "VARCHAR2(10)", "Y", "기기"),
                ("HIT_CNT", "NUMBER(12)", "N", "클릭수"), ("MEMBER_HIT_CNT", "NUMBER(12)", "N", "회원클릭"), ("GUEST_HIT_CNT", "NUMBER(12)", "N", "비회원클릭"),
                ("REMARK", "VARCHAR2(100)", "N", "비고(오류데이터 표식)"), ("REG_DATE", "DATE", "N", "등록일시")],
         tcols=[("메뉴통계ID", "STAT_MENU_ID", "BIGINT", "", "Y", "메뉴통계ID (TOBE 대리키, 시퀀스 채번)"), ("통계연월", "STAT_YM", "VARCHAR", "6", "Y", "통계연월"), ("메뉴코드", "MENU_CD", "VARCHAR", "20", "Y", "메뉴코드"), ("기기", "DEVICE_CD", "VARCHAR", "10", "Y", "기기"),
                ("클릭수", "HIT_CNT", "BIGINT", "", "N", "클릭수"), ("회원클릭", "MEMBER_HIT_CNT", "BIGINT", "", "N", "회원클릭"), ("비회원클릭", "GUEST_HIT_CNT", "BIGINT", "", "N", "비회원클릭"),
                ("등록일시", "INDATE", "VARCHAR", "14", "N", "등록일시")]),
    dict(plugin="demo.sequence", biz="05", bizname="시퀀스관리", asis="SEQ_MASTER", adesc="시퀀스 현재값", tobe="tb_sequence", tdesc="공통시퀀스관리",
         sort="SEQ_NM", pk=["TABLES", "COL"],
         acols=[("SEQ_NM", "VARCHAR2(30)", "Y", "시퀀스명"), ("TARGET_TABLE", "VARCHAR2(30)", "Y", "대상 TOBE 테이블"), ("TARGET_COLUMN", "VARCHAR2(30)", "Y", "대상 컬럼"),
                ("CURRENT_VAL", "NUMBER(19)", "Y", "현재값(마지막 사용)"), ("INCREMENT_BY", "NUMBER(5)", "N", "증가값"), ("MAX_VAL", "NUMBER(19)", "N", "최대값"), ("MOD_DATE", "DATE", "N", "수정일시")],
         tcols=[("테이블명", "TABLES", "VARCHAR", "50", "Y", "테이블명"), ("시퀀스컬럼", "COL", "VARCHAR", "50", "Y", "시퀀스컬럼"), ("시퀀스번호", "SEQ", "DECIMAL", "18,0", "N", "시퀀스번호")]),
]

SEQUENCES = [("MEMBER_SEQ", "tb_member", "MEMBER_ID"), ("LOGIN_SEQ", "tb_member_login_log", "LOGIN_LOG_ID"),
             ("POST_SEQ", "tb_post", "POST_ID"), ("FILE_SEQ", "tb_post_file", "FILE_ID"), ("COMMENT_SEQ", "tb_post_comment", "COMMENT_ID")]
# TOBE 에서 새로 생기는 대리키 (ASIS 시퀀스 없음, 관리행 0 으로 시작 → 첫 번호 1)
TOBE_ONLY_SEQ = [("STAT_DAILY_SEQ", "tb_stat_daily", "STAT_ID"), ("STAT_MENU_SEQ", "tb_stat_menu", "STAT_MENU_ID")]

FK = [("CD_CODE", "GROUP_CD", "CD_GROUP", "GROUP_CD"), ("MEM_MEMBER", "GRADE_CD", "MEM_GRADE", "GRADE_CD"),
      ("MEM_LOGIN_HIS", "MEMBER_ID", "MEM_MEMBER", "MEMBER_ID"), ("BRD_POST", "BOARD_ID", "BRD_BOARD", "BOARD_ID"),
      ("BRD_POST", "WRITER_ID", "MEM_MEMBER", "MEMBER_ID"), ("BRD_POST_FILE", "POST_ID", "BRD_POST", "POST_ID"),
      ("BRD_COMMENT", "POST_ID", "BRD_POST", "POST_ID"), ("BRD_COMMENT", "PARENT_ID", "BRD_COMMENT", "COMMENT_ID"),
      ("STAT_DAILY_VISIT", "BOARD_ID", "BRD_BOARD", "BOARD_ID")]
ASIS_PK = {"CD_GROUP": ["GROUP_CD"], "CD_CODE": ["GROUP_CD", "CODE"], "MEM_GRADE": ["GRADE_CD"], "MEM_MEMBER": ["MEMBER_ID"],
           "MEM_LOGIN_HIS": ["LOGIN_HIS_ID"], "BRD_BOARD": ["BOARD_ID"], "BRD_POST": ["POST_ID"], "BRD_POST_FILE": ["FILE_ID"],
           "BRD_COMMENT": ["COMMENT_ID"], "STAT_DAILY_VISIT": ["STAT_DT", "BOARD_ID"], "STAT_MENU_HIT": ["STAT_YM", "MENU_CD", "DEVICE_CD"],
           "SEQ_MASTER": ["SEQ_NM"]}
ASIS_UNIQUE = {"MEM_MEMBER": [["LOGIN_ID"]], "BRD_BOARD": [["BOARD_CD"]]}
ASIS_INDEX = {"MEM_LOGIN_HIS": [["MEMBER_ID"], ["LOGIN_DT"]], "BRD_POST": [["BOARD_ID"], ["WRITER_ID"]], "BRD_POST_FILE": [["POST_ID"]],
              "BRD_COMMENT": [["POST_ID"], ["PARENT_ID"]], "STAT_DAILY_VISIT": [["BOARD_ID"]]}
TOBE_UNIQUE = {"tb_member": [["LOGIN_ID"]], "tb_board": [["BOARD_CD"]], "tb_stat_daily": [["STAT_YMD", "BOARD_ID"]], "tb_stat_menu": [["STAT_YM", "MENU_CD", "DEVICE_CD"]]}
TOBE_INDEX = {"tb_member_login_log": [["MEMBER_ID"], ["LOGIN_DATE"]], "tb_post": [["BOARD_ID"]], "tb_post_file": [["POST_ID"]],
              "tb_post_comment": [["POST_ID"]]}

PROFILES = {"small": dict(members=1000, logins=10_000, posts=5000, comments=20_000, files=3000, menu_months=12),
            "large": dict(members=1000, logins=100_000, posts=5000, comments=20_000, files=3000, menu_months=120)}

# ─────────────────────────────────────────────────────────────────────────────
def pascal(s):
    return "".join(p[:1].upper() + p[1:].lower() for p in s.lower().split("_"))

def camel(s):
    p = pascal(s)
    return p[:1].lower() + p[1:]

def q(s):
    return "'" + str(s).replace("'", "''") + "'"

def odate(d):
    return "TO_DATE('%s','YYYYMMDDHH24MISS')" % d.strftime("%Y%m%d%H%M%S")

def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    print("write", path, "(%d lines)" % text.count("\n"))

def write_csv(path, header, rows, quote=False):
    buf = io.StringIO()
    w = csv.writer(buf, quoting=csv.QUOTE_ALL if quote else csv.QUOTE_MINIMAL, lineterminator="\n")
    w.writerow(header)
    for r in rows:
        w.writerow(r)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        f.write(buf.getvalue())
    print("write", path, "(%d rows)" % len(rows))

# ─────────────────────────────────────────────────────────────────────────────
# 1) CSV
# ─────────────────────────────────────────────────────────────────────────────
def gen_csv(root):
    asis_rows, tobe_rows, inv05, inv06 = [], [], [], []
    for t in SPEC:
        for i, (cn, ty, nn, cm) in enumerate(t["acols"], start=1):
            asis_rows.append([t["asis"], t["adesc"], cn, cm, i, ty, nn, "[NULL]"])
        for i, (lg, ph, ty, ln, nn, cm) in enumerate(t["tcols"], start=1):
            tobe_rows.append([t["biz"], t["bizname"], t["tdesc"], t["tobe"], t["tdesc"], i, lg, ph, "", ty, ln])
        inv05.append([t["biz"], t["bizname"], t["plugin"], t["tobe"], t["tdesc"], len(t["tcols"]), camel(t["tobe"]), "Y", "", "N", t["asis"], "Y", "PK " + ",".join(t["pk"])])
        inv06.append([t["asis"], t["adesc"], len(t["acols"]), "Asis" + pascal(t["asis"]), "asis" + pascal(t["asis"]) + "SourceMapper", "N", t["tobe"], "Y", t["sort"]])
    write_csv(os.path.join(root, "document/2.logic_analysis/demo/asis-table-column.csv"),
              ["테이블명", "테이블설명", "컬럼명", "컬럼코멘트", "컬럼순서", "컬럼타입", "Not Null 여부", "Default 값"], asis_rows)
    write_csv(os.path.join(root, "document/2.logic_analysis/demo/tobe-table-column.csv"),
              ["업무코드", "업무분류", "테이블명(논리)", "테이블명(물리)", "테이블 설명", "NO", "컬럼명(논리)", "컬럼명(물리)", "자료형태", "TYPE", "길이"], tobe_rows)
    write_csv(os.path.join(root, "document/docs/demo/05_tobe_테이블_인벤토리.csv"),
              ["업무코드", "업무분류", "plugin_name", "tobe_table", "tobe_논리명", "컬럼수", "bean_prefix", "생성대상", "중복_원소유업무", "미사용표기", "asis_후보_추정", "매핑확정", "비고"], inv05, quote=True)
    write_csv(os.path.join(root, "document/docs/demo/06_asis_테이블_인벤토리.csv"),
              ["asis_table", "설명", "컬럼수", "class_prefix", "source_mapper_bean", "제외후보", "사용_tobe_추정", "이관대상확정", "정렬키"], inv06, quote=True)

# ─────────────────────────────────────────────────────────────────────────────
# 2) Oracle DDL
# ─────────────────────────────────────────────────────────────────────────────
def oracle_type(ty):
    t = ty.upper()
    if t.startswith("VARCHAR2(") or t.startswith("CHAR("):
        base, n = t.split("(")
        return "%s(%s CHAR)" % (base, n.rstrip(")"))
    return t

def gen_oracle_user(root):
    s = ["-- DEMO 사용자 생성 / 자동 생성: demo_generate.py", "-- SYSTEM 계정으로 PDB 에서 실행 (01_init.sh 가 *_sys.sql 을 SYSTEM 으로 돌린다)",
         "WHENEVER SQLERROR CONTINUE", "",
         "CREATE USER \"%s\" IDENTIFIED BY \"&1\" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;" % ASIS_SCHEMA,
         "GRANT CONNECT, RESOURCE, CREATE SEQUENCE, CREATE VIEW TO \"%s\";" % ASIS_SCHEMA, "", "EXIT"]
    write(os.path.join(root, "docker/oracle/init-sql/40_demo_user_sys.sql"), NL.join(s) + NL)

def gen_oracle_schema(root):
    s = ["-- 데모 ASIS(Oracle) 스키마 / 자동 생성: demo_generate.py (사양: tools/demo-generator/demo_generate.py SPEC)",
         "-- DEMO 계정 소유. PK/FK/인덱스/시퀀스 포함 (제약 있는 원천 가정). VARCHAR2 는 CHAR 단위",
         "SET DEFINE OFF", "WHENEVER SQLERROR CONTINUE", ""]
    for t in SPEC:
        s.append("-- %s : %s" % (t["asis"], t["adesc"]))
        s.append("CREATE TABLE \"%s\" (" % t["asis"])
        lines = []
        for cn, ty, nn, cm in t["acols"]:
            lines.append("      \"%s\" %s%s" % (cn, oracle_type(ty), " NOT NULL" if nn == "Y" else ""))
        lines.append("      CONSTRAINT \"PK_%s\" PRIMARY KEY (%s)" % (t["asis"], ", ".join('"%s"' % c for c in ASIS_PK[t["asis"]])))
        s.append((NL + "    , ").join(l.lstrip() if i else l for i, l in enumerate(lines)))
        s.append(");")
        for cn, ty, nn, cm in t["acols"]:
            s.append("COMMENT ON COLUMN \"%s\".\"%s\" IS %s;" % (t["asis"], cn, q(cm)))
        s.append("COMMENT ON TABLE \"%s\" IS %s;" % (t["asis"], q(t["adesc"])))
        for cols in ASIS_UNIQUE.get(t["asis"], []):
            s.append("CREATE UNIQUE INDEX \"UX_%s_%s\" ON \"%s\" (%s);" % (t["asis"], "_".join(cols), t["asis"], ", ".join('"%s"' % c for c in cols)))
        for cols in ASIS_INDEX.get(t["asis"], []):
            s.append("CREATE INDEX \"IX_%s_%s\" ON \"%s\" (%s);" % (t["asis"], "_".join(cols), t["asis"], ", ".join('"%s"' % c for c in cols)))
        s.append("")
    s.append("-- FK")
    for i, (ct, cc, pt, pc) in enumerate(FK, start=1):
        s.append("ALTER TABLE \"%s\" ADD CONSTRAINT \"FK_%s_%02d\" FOREIGN KEY (\"%s\") REFERENCES \"%s\" (\"%s\");" % (ct, ct, i, cc, pt, pc))
    s.append("")
    s.append("-- 시퀀스 (현재값은 42_demo_data.sql 의 데이터 최대값과 맞춘다)")
    for nm, tb, col in SEQUENCES:
        s.append("CREATE SEQUENCE \"%s\" START WITH 1 INCREMENT BY 1 NOCACHE;" % nm)
    s.append("")
    s.append("-- migrator(앱 접속 계정) 조회 권한: 매퍼는 DEMO.\"TABLE\" 로 조회한다")
    for t in SPEC:
        s.append("GRANT SELECT ON \"%s\" TO \"%s\";" % (t["asis"], APP_USER.upper()))
    for nm, tb, col in SEQUENCES:
        s.append("GRANT SELECT ON \"%s\" TO \"%s\";" % (nm, APP_USER.upper()))
    s.append("")
    write(os.path.join(root, "docker/oracle/init-sql/41_demo_schema.sql"), NL.join(s) + NL)

# ─────────────────────────────────────────────────────────────────────────────
# 3) 데이터
# ─────────────────────────────────────────────────────────────────────────────
KO_LAST = ["김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오", "서", "신", "권", "황", "안", "송", "류", "홍"]
KO_FIRST = ["민수", "서연", "지훈", "수아", "우진", "하은", "현우", "지민", "도윤", "예진", "준호", "다은", "시우", "채원", "지호", "유나", "건우", "소율", "승현", "나연"]
WORDS = ["공지", "안내", "회의", "자료", "요청", "문의", "교육", "일정", "배포", "장애", "점검", "복지", "휴가", "채용", "보안", "정책", "결과", "보고", "협업", "리뷰"]
MENUS = ["HOME", "NOTICE", "FREE", "QNA", "FAQ", "MYPAGE", "SEARCH", "LOGIN", "MEMBER", "STATS", "CODE", "ADMIN", "FILE", "COMMENT", "POST_WRITE", "POST_VIEW", "PROFILE", "HELP", "TERMS", "LOGOUT"]
DEVICES = ["PC", "MOBILE", "TABLET"]
MIMES = [("pdf", "application/pdf"), ("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), ("png", "image/png"), ("jpg", "image/jpeg"), ("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), ("zip", "application/zip")]
BASE = dt.datetime(2025, 1, 1, 9, 0, 0)
NOW = dt.datetime(2026, 10, 1, 9, 0, 0)

class Data:
    def __init__(self, seed, prof):
        self.r = random.Random(seed)
        self.p = prof
        self.rows = {}

    def dt_between(self, a, b):
        sec = int((b - a).total_seconds())
        return a + dt.timedelta(seconds=self.r.randint(0, max(sec, 1)))

    def audit(self, when):
        mod = when + dt.timedelta(days=self.r.randint(0, 200))
        if mod > NOW:
            mod = NOW
        return ["admin", odate(when), ("admin" if self.r.random() < 0.7 else "system"), odate(mod)]

    def build(self):
        r = self.r
        groups = [("GENDER", "성별"), ("MEMBER_STATUS", "회원상태"), ("BOARD_TYPE", "게시판유형"), ("FILE_TYPE", "파일유형"), ("MENU", "메뉴"),
                  ("GRADE", "등급"), ("POST_STATUS", "게시글상태"), ("NOTICE_YN", "공지여부"), ("LOGIN_RESULT", "로그인결과"), ("DEVICE", "기기")]
        self.rows["CD_GROUP"] = [[g, n, "Y" if i < 9 else "N", i + 1] + self.audit(BASE) for i, (g, n) in enumerate(groups)]
        codes = []
        for g, n in groups:
            for i in range(10):
                codes.append([g, "%s%02d" % (g[:3], i + 1), "%s %d" % (n, i + 1), "%s 코드 %d 설명" % (n, i + 1), "Y" if i < 8 else "N", i + 1] + self.audit(BASE))
        self.rows["CD_CODE"] = codes
        grades = [("BRONZE", "브론즈", 0, 0), ("SILVER", "실버", 1000, 3), ("GOLD", "골드", 5000, 5), ("PLATINUM", "플래티넘", 20000, 8), ("DIAMOND", "다이아몬드", 50000, 12)]
        self.rows["MEM_GRADE"] = [[g, n, mp, dr, "Y"] + self.audit(BASE) for g, n, mp, dr in grades]
        members = []
        for i in range(1, self.p["members"] + 1):
            nm = r.choice(KO_LAST) + r.choice(KO_FIRST)
            join = self.dt_between(BASE - dt.timedelta(days=900), NOW - dt.timedelta(days=30))
            status = "A" if i <= self.p["members"] * 0.9 else ("I" if i <= self.p["members"] * 0.98 else "D")
            last = self.dt_between(join, NOW) if status != "D" else None
            point = r.randint(0, 60000)
            grade = next((g for g, n, mp, dr in reversed(grades) if point >= mp), "BRONZE")
            members.append([i, "user%04d" % i, nm, hashlib.sha256(("pw%d" % i).encode()).hexdigest(), r.choice(["M", "F"]),
                            odate(self.dt_between(dt.datetime(1965, 1, 1), dt.datetime(2005, 12, 31)).replace(hour=0, minute=0, second=0)),
                            "user%04d@example.com" % i, "010-%04d-%04d" % (r.randint(1000, 9999), r.randint(1000, 9999)), grade, status,
                            odate(join), odate(last) if last else None, point, None] + self.audit(join))
        self.rows["MEM_MEMBER"] = members
        active_ids = [m[0] for m in members if m[9] != "D"]
        logins = []
        for i in range(1, self.p["logins"] + 1):
            when = self.dt_between(NOW - dt.timedelta(days=365), NOW)
            res = "SUCCESS" if r.random() < 0.93 else ("FAIL" if r.random() < 0.8 else "LOCK")
            logins.append([i, r.choice(active_ids), odate(when), "10.%d.%d.%d" % (r.randint(0, 255), r.randint(0, 255), r.randint(1, 254)),
                           r.choice(DEVICES), res, None if res == "SUCCESS" else ("비밀번호 불일치" if res == "FAIL" else "5회 실패 잠금"), odate(when)])
        self.rows["MEM_LOGIN_HIS"] = logins
        boards = [("NOTICE", "공지사항", "NOTICE"), ("FREE", "자유게시판", "FREE"), ("QNA", "질문과답변", "QNA"), ("FAQ", "자주묻는질문", "FAQ"), ("DEV", "개발팀", "FREE"),
                  ("HR", "인사공지", "NOTICE"), ("SALES", "영업자료", "FREE"), ("EVENT", "이벤트", "FREE"), ("SUPPORT", "고객지원", "QNA"), ("ARCHIVE", "자료실", "FREE")]
        self.rows["BRD_BOARD"] = [[i + 1, cd, nm, ty, "Y" if i < 9 else "N", "Y" if ty == "FREE" and i % 2 else "N", 5] + self.audit(BASE) for i, (cd, nm, ty) in enumerate(boards)]
        posts = []
        for i in range(1, self.p["posts"] + 1):
            when = self.dt_between(BASE, NOW)
            status = "A" if i % 25 else "D"
            body = "%s 관련 안내입니다. %s 일정과 %s 자료를 확인해 주세요. (게시글 %d)" % (r.choice(WORDS), r.choice(WORDS), r.choice(WORDS), i)
            if i % 50 == 0:
                body = (body + " ") * 40
            posts.append([i, r.randint(1, 10), "[%s] %s %d" % (r.choice(WORDS), r.choice(WORDS), i), body, r.choice(active_ids), r.randint(0, 5000),
                          "Y" if i % 40 == 0 else "N", "Y" if i % 15 == 0 else "N", status] + self.audit(when))
        self.rows["BRD_POST"] = posts
        live_posts = [p[0] for p in posts if p[8] == "A"]
        files = []
        for i in range(1, self.p["files"] + 1):
            pid = r.choice(live_posts)
            ext, mime = r.choice(MIMES)
            d = self.dt_between(BASE, NOW)
            files.append([i, pid, "%s_%d.%s" % (r.choice(WORDS), i, ext), "/Upload_Board/%s/%s/%d.%s" % (d.strftime("%Y"), d.strftime("%m"), i, ext),
                          r.randint(1024, 20_000_000), mime, r.randint(0, 300), None] + self.audit(d))
        self.rows["BRD_POST_FILE"] = files
        comments = []
        roots = []
        for i in range(1, self.p["comments"] + 1):
            pid = r.choice(live_posts)
            parent, depth = None, 0
            if roots and r.random() < 0.3:
                pc, ppid = r.choice(roots)
                parent, depth, pid = pc, 1, ppid
            status = "A" if i % 40 else "D"
            comments.append([i, pid, parent, r.choice(active_ids), "%s 에 대한 댓글 %d 입니다." % (r.choice(WORDS), i), depth, status] + self.audit(self.dt_between(BASE, NOW)))
            if depth == 0:
                roots.append((i, pid))
        self.rows["BRD_COMMENT"] = comments
        daily = []
        for d in range(365):
            day = NOW - dt.timedelta(days=365 - d)
            for b in range(1, 11):
                v = r.randint(50, 3000)
                daily.append([odate(day.replace(hour=0, minute=0, second=0)), b, v, int(v * r.uniform(0.4, 0.9)), r.randint(0, 40), r.randint(0, 120),
                              round(r.uniform(20, 600), 2), odate(day.replace(hour=1, minute=0, second=0))])
        self.rows["STAT_DAILY_VISIT"] = daily
        menu = []
        for m in range(self.p["menu_months"]):
            ym = (dt.date(2026, 9, 1) - dt.timedelta(days=30 * m)).strftime("%Y%m")
            for mc in MENUS:
                for dv in DEVICES:
                    hit = r.randint(100, 100000)
                    mem = int(hit * r.uniform(0.3, 0.8))
                    menu.append([ym, mc, dv, hit, mem, hit - mem, None, odate(NOW)])
        self.rows["STAT_MENU_HIT"] = menu
        maxes = {"tb_member": self.p["members"], "tb_member_login_log": self.p["logins"], "tb_post": self.p["posts"], "tb_post_file": self.p["files"], "tb_post_comment": self.p["comments"]}
        self.rows["SEQ_MASTER"] = [[nm, tb, col, maxes[tb], 1, 999999999999, odate(NOW)] for nm, tb, col in SEQUENCES] \
            + [[nm, tb, col, 0, 1, 999999999999, odate(NOW)] for nm, tb, col in TOBE_ONLY_SEQ]

LITERAL_MAX_BYTES = 3000   # Oracle 문자열 리터럴 한도 4000바이트(ORA-01704) 아래로 쪼갠다 (한글 3바이트)

def clob_lit(v):
    """4000바이트를 넘는 문자열(CLOB 본문)은 TO_CLOB('..') || TO_CLOB('..') 로 분할해 리터럴 한도를 피한다."""
    chunks, cur, size = [], [], 0
    for ch in v:
        b = len(ch.encode("utf-8"))
        if size + b > LITERAL_MAX_BYTES:
            chunks.append("".join(cur)); cur, size = [], 0
        cur.append(ch); size += b
    if cur:
        chunks.append("".join(cur))
    return "\n    || ".join("TO_CLOB(%s)" % q(c) for c in chunks)

def lit(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, str) and v.startswith("TO_DATE("):
        return v
    if isinstance(v, str) and len(v.encode("utf-8")) > LITERAL_MAX_BYTES:
        return clob_lit(v)
    return q(v)

def insert_all(table, cols, rows, batch=100):
    out = []
    collist = ", ".join('"%s"' % c for c in cols)
    for i in range(0, len(rows), batch):
        chunk = rows[i:i + batch]
        out.append("INSERT ALL")
        for row in chunk:
            out.append("  INTO \"%s\" (%s) VALUES (%s)" % (table, collist, ", ".join(lit(v) for v in row)))
        out.append("SELECT 1 FROM DUAL;")
    return out

def gen_oracle_data(root, data, profile, seed):
    s = ["-- 데모 ASIS 데이터 / 자동 생성: demo_generate.py (seed=%d, profile=%s)" % (seed, profile),
         "-- FK 정합성 유지. 삭제건(STATUS 'D')·미사용(USE_YN 'N') 포함. 실행 순서 = FK 순서",
         "SET DEFINE OFF", "WHENEVER SQLERROR CONTINUE", ""]
    for t in SPEC:
        cols = [c[0] for c in t["acols"]]
        rows = data.rows[t["asis"]]
        s.append("-- %s (%d rows)" % (t["asis"], len(rows)))
        if t["asis"] == "BRD_COMMENT":
            # 부모 먼저 (PARENT_ID NULL) → 자식
            rows = [r for r in rows if r[2] is None] + [r for r in rows if r[2] is not None]
        s.extend(insert_all(t["asis"], cols, rows))
        s.append("COMMIT;")
        s.append("")
    s.append("-- 시퀀스 현재값을 데이터 최대값으로")
    for nm, tb, col in SEQUENCES:
        mx = {"tb_member": data.p["members"], "tb_member_login_log": data.p["logins"], "tb_post": data.p["posts"], "tb_post_file": data.p["files"], "tb_post_comment": data.p["comments"]}[tb]
        s.append("DROP SEQUENCE \"%s\";" % nm)
        s.append("CREATE SEQUENCE \"%s\" START WITH %d INCREMENT BY 1 NOCACHE;" % (nm, mx + 1))
        s.append("GRANT SELECT ON \"%s\" TO \"%s\";" % (nm, APP_USER.upper()))
    s.append("")
    write(os.path.join(root, "docker/oracle/init-sql/42_demo_data.sql"), NL.join(s) + NL)

def gen_oracle_dirty(root, data):
    m = data.p["members"]
    s = ["-- 데모 오류 데이터 (--dirty) / 자동 생성: demo_generate.py. REMARK = 'DIRTY:<종류>'", "SET DEFINE OFF", "WHENEVER SQLERROR CONTINUE", ""]
    s.append("-- MEM_MEMBER: 길이 초과(회원명 60자), 필수 NULL(JOIN_DT 는 NOT NULL 이라 ASIS 에서는 불가 → LAST_LOGIN 대신 EMAIL 형식 오류), 성별 범위 밖")
    s.append("INSERT INTO \"MEM_MEMBER\" (\"MEMBER_ID\",\"LOGIN_ID\",\"MEMBER_NM\",\"GENDER\",\"STATUS\",\"JOIN_DT\",\"REMARK\",\"REG_ID\",\"REG_DATE\") VALUES (%d,'dirty_len',%s,'M','A',%s,'DIRTY:LENGTH','admin',%s);"
             % (m + 1, q("가" * 60), odate(NOW), odate(NOW)))
    s.append("INSERT INTO \"MEM_MEMBER\" (\"MEMBER_ID\",\"LOGIN_ID\",\"MEMBER_NM\",\"GENDER\",\"EMAIL\",\"STATUS\",\"JOIN_DT\",\"REMARK\",\"REG_ID\",\"REG_DATE\") VALUES (%d,'dirty_email','이메일오류','F','not-an-email','A',%s,'DIRTY:EMAIL','admin',%s);"
             % (m + 2, odate(NOW), odate(NOW)))
    s.append("INSERT INTO \"MEM_MEMBER\" (\"MEMBER_ID\",\"LOGIN_ID\",\"MEMBER_NM\",\"GENDER\",\"STATUS\",\"JOIN_DT\",\"REMARK\",\"REG_ID\",\"REG_DATE\") VALUES (%d,'dirty_gender','성별오류','X','A',%s,'DIRTY:CODE','admin',%s);"
             % (m + 3, odate(NOW), odate(NOW)))
    s.append("-- BRD_POST_FILE: 규칙 없는 경로")
    s.append("INSERT INTO \"BRD_POST_FILE\" (\"FILE_ID\",\"POST_ID\",\"ORIGIN_NM\",\"STORED_PATH\",\"FILE_SIZE\",\"REMARK\",\"REG_ID\",\"REG_DATE\") VALUES (%d,1,'legacy.pdf','/Legacy/2019/legacy.pdf',1024,'DIRTY:PATH','admin',%s);"
             % (data.p["files"] + 1, odate(NOW)))
    s.append("INSERT INTO \"BRD_POST_FILE\" (\"FILE_ID\",\"POST_ID\",\"ORIGIN_NM\",\"STORED_PATH\",\"FILE_SIZE\",\"REMARK\",\"REG_ID\",\"REG_DATE\") VALUES (%d,2,'temp.zip','/Upload_Temp/temp.zip',2048,'DIRTY:PATH','admin',%s);"
             % (data.p["files"] + 2, odate(NOW)))
    s.append("-- STAT_MENU_HIT: TOBE 에서 중복 키가 되도록 같은 키를 소문자 기기로 (TOBE 는 대문자 치환 후 PK 충돌)")
    s.append("INSERT INTO \"STAT_MENU_HIT\" (\"STAT_YM\",\"MENU_CD\",\"DEVICE_CD\",\"HIT_CNT\",\"REMARK\",\"REG_DATE\") VALUES ('202609','HOME','pc',1,'DIRTY:DUPKEY',%s);" % odate(NOW))
    s.append("-- MEM_LOGIN_HIS: 비정상 일시 (0001-01-01)")
    s.append("INSERT INTO \"MEM_LOGIN_HIS\" (\"LOGIN_HIS_ID\",\"MEMBER_ID\",\"LOGIN_DT\",\"RESULT_CD\",\"REG_DATE\") VALUES (%d,1,TO_DATE('00010101','YYYYMMDD'),'SUCCESS',%s);" % (data.p["logins"] + 1, odate(NOW)))
    s.append("COMMIT;")
    s.append("")
    write(os.path.join(root, "docker/oracle/init-sql/43_demo_dirty.sql"), NL.join(s) + NL)

# ─────────────────────────────────────────────────────────────────────────────
# 4) MySQL
# ─────────────────────────────────────────────────────────────────────────────
def mysql_type(ty, ln):
    t = ty.upper()
    if t in ("VARCHAR", "CHAR", "DECIMAL") and ln:
        return "%s(%s)" % (t, ln)
    return t

def gen_mysql(root):
    s = ["-- 데모 TOBE(MySQL) 스키마 / 자동 생성: demo_generate.py", "-- PK·UNIQUE·인덱스 포함 (재실행 시 중복이 실패로 드러나게). FK 없음. 데이터 없음",
         "CREATE DATABASE IF NOT EXISTS `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" % TOBE_DB, "USE `%s`;" % TOBE_DB, ""]
    for t in SPEC:
        s.append("CREATE TABLE IF NOT EXISTS `%s` (" % t["tobe"])
        lines = ["`%s` %s%s COMMENT %s" % (ph, mysql_type(ty, ln), " NOT NULL" if nn == "Y" else "", q(cm)) for lg, ph, ty, ln, nn, cm in t["tcols"]]
        lines.append("PRIMARY KEY (%s)" % ", ".join("`%s`" % c for c in t["pk"]))
        for cols in TOBE_UNIQUE.get(t["tobe"], []):
            lines.append("UNIQUE KEY `ux_%s_%s` (%s)" % (t["tobe"], "_".join(c.lower() for c in cols), ", ".join("`%s`" % c for c in cols)))
        for cols in TOBE_INDEX.get(t["tobe"], []):
            lines.append("KEY `ix_%s_%s` (%s)" % (t["tobe"], "_".join(c.lower() for c in cols), ", ".join("`%s`" % c for c in cols)))
        s.append("      " + (NL + "    , ").join(lines))
        s.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT=%s;" % q(t["tdesc"]))
        s.append("")
    write(os.path.join(root, "docker/mysql/init/20_demo_tobe_schema.sql"), NL.join(s) + NL)
    r = ["-- 데모 TOBE 비우기 (개발 환경 전용, 자동 실행되지 않음. 제품은 데이터를 지우지 않는다)",
         "-- 사용: docker exec -i batch-mysql mysql -uroot -p<pw> < docker/mysql/tools/21_demo_tobe_reset.sql", "USE `%s`;" % TOBE_DB, ""]
    for t in reversed(SPEC):
        r.append("TRUNCATE TABLE `%s`;" % t["tobe"])
    write(os.path.join(root, "docker/mysql/tools/21_demo_tobe_reset.sql"), NL.join(r) + NL)

# ─────────────────────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--rows-profile", choices=sorted(PROFILES), default="small")
    ap.add_argument("--seed", type=int, default=20261017)
    ap.add_argument("--dirty", action="store_true")
    ap.add_argument("--csv-only", action="store_true")
    a = ap.parse_args()
    gen_csv(a.root)
    if a.csv_only:
        return
    gen_oracle_user(a.root)
    gen_oracle_schema(a.root)
    data = Data(a.seed, PROFILES[a.rows_profile])
    data.build()
    gen_oracle_data(a.root, data, a.rows_profile, a.seed)
    if a.dirty:
        gen_oracle_dirty(a.root, data)
    else:
        p = os.path.join(a.root, "docker/oracle/init-sql/43_demo_dirty.sql")
        if os.path.exists(p):
            os.remove(p)
            print("remove", p)
    gen_mysql(a.root)
    for t in SPEC:
        print("  %-16s %-22s rows=%d" % (t["asis"], t["tobe"], len(data.rows[t["asis"]])))

if __name__ == "__main__":
    main()
