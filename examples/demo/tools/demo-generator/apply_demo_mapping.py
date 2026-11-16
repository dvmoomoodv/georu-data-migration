#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
데모 플러그인 수기 매핑 적용기 - 코드 생성기(--company demo) 산출물(Source XML, Processor, Target XML)에
설계 문서(00_데모_서비스_설명.md)의 변환 규칙을 넣는다. 재생성(--force) 뒤 다시 돌리면 같은 결과가 된다(멱등).

  python3 tools/demo-generator/apply_demo_mapping.py
"""
import glob
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
X = os.path.join(ROOT, "src/main/resources/georu/demo/source/")
T = os.path.join(ROOT, "src/main/resources/georu/demo/target/")
J = os.path.join(ROOT, "src/main/java/com/georu/examples/demo/")

AUDIT = {"INUSERID": 'A."REG_ID"', "LUSERID": 'A."MOD_ID"'}
SEQ = "NULL"   # 시퀀스 채번 컬럼: Source 는 NULL, Processor(core.sequence)가 번호 부여

def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

def save(p, s):
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)

def set_col(s, col, expr, note):
    """NULL AS "COL" <!-- ... TODO ← ASIS ? --> 또는 이미 치환된 줄을 expr 로 맞춘다."""
    pat = re.compile(r'^(\s*[, ]*)([^\n]*?) AS "%s"(\s*)<!--([^>]*?)-->' % re.escape(col), re.M)
    m = pat.search(s)
    if not m:
        raise SystemExit("column not found: %s" % col)
    head = m.group(4)
    base = head.split("←")[0].split("TODO")[0].rstrip()
    return s[:m.start()] + "%s%s AS \"%s\"%s<!--%s ← %s -->" % (m.group(1), expr, col, m.group(3), base, note) + s[m.end():]

def set_where(s, where_add=None, period_col=None):
    m = re.search(r'    <sql id="where">\n(.*?)    </sql>', s, re.S)
    if not m:
        raise SystemExit("where block not found")
    lines = ['    <sql id="where">', "        WHERE 1 = 1"]
    if where_add:
        lines.append("        " + where_add)
    if period_col:
        lines.append("        <if test=\"startDate != null and startDate != ''\">AND A.\"%s\" &gt;= TO_DATE(#{startDate}, 'YYYYMMDD')</if>" % period_col)
        lines.append("        <if test=\"endDate != null and endDate != ''\">AND A.\"%s\" &lt; TO_DATE(#{endDate}, 'YYYYMMDD') + 1</if>" % period_col)
    lines.append("    </sql>")
    return s[:m.start()] + "\n".join(lines) + s[m.end():]

def xml(name, cols, where_add=None, period_col=None, raw=()):
    p = X + name + "SourceMapper.xml"
    s = read(p)
    for col, (expr, note) in cols.items():
        s = set_col(s, col, expr, note)
    s = set_where(s, where_add, period_col)
    for old, new in raw:
        if new not in s:
            if old not in s:
                raise SystemExit("%s: raw anchor missing: %s" % (name, old))
            s = s.replace(old, new, 1)
    s = re.sub(r"자동 매핑 (\d+) / TODO (\d+) \(TOBE 컬럼 (\d+)\)", r"자동 매핑 \1 / 수기 매핑 \2 (TOBE 컬럼 \3, apply_demo_mapping.py 적용)", s)
    save(p, s)

def processor(biz, name, builder=(), validate=(), item_id=None, out_id=None, imports=()):
    p = J + "%s/processor/%sProcessor.java" % (biz, name)
    s = read(p)
    for imp in imports:
        if imp not in s:
            s = s.replace("import com.georu.core.utils.ValidationUtils;", "import com.georu.core.utils.ValidationUtils;\n" + imp, 1)
    for field, expr, note in builder:
        getter = "item.get%s()" % (field[0].upper() + field[1:])
        pat = re.compile(r"\.%s\([^\n]*\)(\s*//[^\n]*)?\n" % re.escape(field))
        if not pat.search(s):
            raise SystemExit("%s: builder field not found: %s" % (name, field))
        s = pat.sub(".%s(%s)    // %s\n" % (field, expr.replace("$", getter), note), s, count=1)
    if validate:
        body = "        // 수기 검증 시작 (apply_demo_mapping.py)\n" + "\n".join("        if (%s) {\n            return ValidationUtils.ValidationResult.failure(\"%s\");\n        }" % (c, m) for c, m in validate) + "\n        // 수기 검증 끝\n"
        if "// 수기 검증 시작" in s:
            s = re.sub(r"        // 수기 검증 시작[^\n]*\n.*?        // 수기 검증 끝\n", lambda _: body, s, count=1, flags=re.S)
        elif "// TODO 필수값 / 이관 제외 조건 검증" in s:
            s = s.replace("        // TODO 필수값 / 이관 제외 조건 검증\n", body, 1)
        else:
            s = re.sub(r"(return ValidationUtils\.ValidationResult\.failure\(\"item is null\"\);\n        }\n)", lambda m: m.group(1) + body, s, count=1)
    if item_id:
        s = re.sub(r"(protected Object getItemId\([^)]*\) \{\n        return item != null \? )[^;]*;[^\n]*", r"\g<1>%s : null;    // 입력 식별 (%s)" % (item_id[0], item_id[1]), s, count=1)
    if out_id:
        s = re.sub(r"(protected Object getOutputItemId\([^)]*\) \{\n        return item != null \? )[^;]*;[^\n]*", r"\g<1>%s : null;    // 출력 식별 (%s)" % (out_id[0], out_id[1]), s, count=1)
    s = s.replace("    // TODO PK 확인", "    // PK (05 CSV 비고)")
    s = re.sub(r"\(자동 매핑 (\d+) / TODO (\d+), TOBE 컬럼 (\d+)\)", r"(자동 매핑 \1 / 수기 매핑 \2, TOBE 컬럼 \3 - 완료)", s)
    save(p, s)

RULES = "import com.georu.examples.demo.utils.DemoCodeRules;"
PATHS = "import com.georu.examples.demo.utils.DemoPathRules;"
YN = ("DemoCodeRules.yn($)", "Y/N → 1/0")
ST = ("DemoCodeRules.status($)", "A/I/D → ACTIVE/INACTIVE/DELETED")

def main():
    a = lambda: {k: (v, v.split('"')[1] + " (수기)") for k, v in AUDIT.items()}
    # ── 코드
    xml("TbCodeGroup", a())
    xml("TbCode", a())
    processor("code", "TbCodeGroup", builder=[("useYn",) + YN], validate=[("item.getGroupCd() == null || item.getGroupCd().isBlank()", "GROUP_CD 누락")], imports=[RULES])
    processor("code", "TbCode", builder=[("useYn",) + YN], validate=[("item.getGroupCd() == null || item.getCode() == null", "GROUP_CD/CODE 누락")],
              item_id=('item.getGroupCd() + ":" + item.getCode()', "PK GROUP_CD, CODE"), out_id=('item.getGroupCd() + ":" + item.getCode()', "PK GROUP_CD, CODE"), imports=[RULES])
    # ── 회원
    xml("TbMemberGrade", a())
    processor("member", "TbMemberGrade", builder=[("useYn",) + YN], validate=[("item.getGradeCd() == null", "GRADE_CD 누락")], imports=[RULES])
    xml("TbMember", dict(a(), **{"BIRTH_YMD": ("TO_CHAR(A.\"BIRTH_DT\", 'YYYYMMDD')", "BIRTH_DT (수기)"),
                                  "JOIN_DATE": ("TO_CHAR(A.\"JOIN_DT\", 'YYYYMMDDHH24MISS')", "JOIN_DT (수기)"),
                                  "LAST_LOGIN_DATE": ("TO_CHAR(A.\"LAST_LOGIN_DT\", 'YYYYMMDDHH24MISS')", "LAST_LOGIN_DT (수기)")}),
        where_add="AND A.\"STATUS\" &lt;&gt; 'D'    <!-- 탈퇴(삭제) 회원 제외 -->", period_col="JOIN_DT",
        raw=[('A."PHONE" AS "PHONE"', 'REPLACE(A."PHONE", \'-\', \'\') AS "PHONE"')])
    processor("member", "TbMember", builder=[("gender", "DemoCodeRules.gender($)", "M/F → 1/2"), ("status",) + ST],
              validate=[("item.getMemberId() == null", "MEMBER_ID 누락"), ("item.getLoginId() == null || item.getLoginId().isBlank()", "LOGIN_ID 누락"),
                        ("item.getMemberNm() == null || item.getMemberNm().length() > 50", "MEMBER_NM 누락 또는 50자 초과"), ("item.getJoinDate() == null", "JOIN_DATE 누락"),
                        ("item.getEmail() != null && !item.getEmail().contains(\"@\")", "EMAIL 형식 오류")], imports=[RULES])
    xml("TbMemberLoginLog", {"LOGIN_LOG_ID": (SEQ, "시퀀스 채번 (DemoSequenceColumns)"), "ASIS_ID": ('A."LOGIN_HIS_ID"', "LOGIN_HIS_ID (원본 보존)"),
                             "LOGIN_DATE": ("TO_CHAR(A.\"LOGIN_DT\", 'YYYYMMDDHH24MISS')", "LOGIN_DT (수기)")}, period_col="LOGIN_DT")
    processor("member", "TbMemberLoginLog", validate=[("item.getAsisId() == null || item.getMemberId() == null", "ASIS_ID/MEMBER_ID 누락"),
                                                      ("item.getLoginDate() == null || item.getLoginDate().startsWith(\"0001\")", "LOGIN_DATE 누락 또는 비정상 일시")],
              item_id=("item.getAsisId()", "ASIS LOGIN_HIS_ID, LOGIN_LOG_ID 는 채번 전"), out_id=("item.getLoginLogId()", "채번된 LOGIN_LOG_ID"))
    # ── 게시판
    xml("TbBoard", a())
    processor("board", "TbBoard", builder=[("useYn",) + YN, ("anonYn",) + YN], validate=[("item.getBoardId() == null || item.getBoardCd() == null", "BOARD_ID/BOARD_CD 누락")], imports=[RULES])
    xml("TbPost", a(), where_add="AND A.\"STATUS\" &lt;&gt; 'D'    <!-- 삭제 게시글 제외 -->", period_col="REG_DATE")
    processor("board", "TbPost", builder=[("noticeYn",) + YN, ("secretYn",) + YN, ("status",) + ST],
              validate=[("item.getPostId() == null || item.getBoardId() == null", "POST_ID/BOARD_ID 누락"), ("item.getTitle() == null || item.getTitle().isBlank()", "TITLE 누락")], imports=[RULES])
    xml("TbPostFile", dict(a(), **{"FILE_ID": (SEQ, "시퀀스 채번 (DemoSequenceColumns)"), "ASIS_ID": ('A."FILE_ID"', "FILE_ID (원본 보존)"), "FILE_PATH": ('A."STORED_PATH"', "STORED_PATH (수기, 경로 변환은 Processor)")}))
    processor("board", "TbPostFile", builder=[("filePath", "DemoPathRules.toTobePath($)", "/Upload_Board/… → file/board/…")],
              validate=[("item.getAsisId() == null || item.getPostId() == null", "ASIS_ID/POST_ID 누락"), ("item.getFilePath() == null", "FILE_PATH 누락")],
              item_id=("item.getAsisId()", "ASIS FILE_ID, FILE_ID 는 채번 전"), out_id=("item.getFileId()", "채번된 FILE_ID"), imports=[PATHS])
    xml("TbPostComment", a(), where_add="AND A.\"STATUS\" &lt;&gt; 'D'    <!-- 삭제 댓글 제외 -->")
    processor("board", "TbPostComment", builder=[("status",) + ST],
              validate=[("item.getCommentId() == null || item.getPostId() == null", "COMMENT_ID/POST_ID 누락"), ("item.getContent() == null", "CONTENT 누락")], imports=[RULES])
    # ── 통계
    xml("TbStatDaily", {"STAT_ID": (SEQ, "시퀀스 채번 (TOBE 대리키)"), "STAT_YMD": ("TO_CHAR(A.\"STAT_DT\", 'YYYYMMDD')", "STAT_DT (수기)")}, period_col="STAT_DT")
    processor("stats", "TbStatDaily", validate=[("item.getStatYmd() == null || item.getBoardId() == null", "STAT_YMD/BOARD_ID 누락")],
              item_id=('item.getStatYmd() + ":" + item.getBoardId()', "자연키 STAT_YMD, BOARD_ID"), out_id=("item.getStatId()", "채번된 STAT_ID"))
    xml("TbStatMenu", {"STAT_MENU_ID": (SEQ, "시퀀스 채번 (TOBE 대리키)")}, raw=[('A."DEVICE_CD" AS "DEVICE_CD"', 'UPPER(A."DEVICE_CD") AS "DEVICE_CD"')])
    processor("stats", "TbStatMenu", validate=[("item.getStatYm() == null || item.getMenuCd() == null || item.getDeviceCd() == null", "STAT_YM/MENU_CD/DEVICE_CD 누락")],
              item_id=('item.getStatYm() + ":" + item.getMenuCd() + ":" + item.getDeviceCd()', "자연키"), out_id=("item.getStatMenuId()", "채번된 STAT_MENU_ID"))
    # ── 시퀀스
    xml("TbSequence", {"TABLES": ('A."TARGET_TABLE"', "TARGET_TABLE (수기)"), "COL": ('A."TARGET_COLUMN"', "TARGET_COLUMN (수기)"), "SEQ": ('A."CURRENT_VAL"', "CURRENT_VAL (수기)")})
    processor("sequence", "TbSequence", validate=[("item.getTables() == null || item.getCol() == null", "TABLES/COL 누락")],
              item_id=('item.getTables() + ":" + item.getCol()', "PK TABLES, COL"), out_id=('item.getTables() + ":" + item.getCol()', "PK TABLES, COL"))
    # ── Target XML 주석
    for f in glob.glob(T + "*.xml"):
        s = read(f).replace("        <!-- TODO PK 확정 후 재실행 대비: ON DUPLICATE KEY UPDATE `컬럼` = VALUES(`컬럼`), ... -->\n",
                            "        <!-- PK 있음. 재실행은 제품 정책상 \"기존 데이터 있으면 경고 후 중단\" → 데모 리셋 SQL(21_demo_tobe_reset.sql) 후 재실행 -->\n")
        save(f, s)
    # ── Writer PK 주석
    for f in glob.glob(J + "*/writer/*Writer.java"):
        save(f, read(f).replace("    // TODO PK 확인", "    // PK (05 CSV 비고)"))
    left = sum(read(f).count("TODO") for f in glob.glob(J + "**/*.java", recursive=True) + glob.glob(X + "*.xml") + glob.glob(T + "*.xml"))
    print("applied. TODO left:", left)

if __name__ == "__main__":
    main()
