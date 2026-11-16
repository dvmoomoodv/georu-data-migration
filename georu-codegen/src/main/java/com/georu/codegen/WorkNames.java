package com.georu.codegen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 업무구분 값의 패키지 이름 찾기. 순서대로 보고 처음 맞는 것을 쓴다.
 *
 * <ol>
 *   <li>파일의 업무 패키지 열 · 화면 설정 (여기서는 보지 않고 {@link CodeGenerator} 가 먼저 본다)</li>
 *   <li>업무구분이 영문이면 그대로 소문자 ("Member" → member)</li>
 *   <li>사전: "02 회원관리" 처럼 앞 번호 · 기호와 끝의 관리 · 정보 · 업무 를 뗀 뒤 같은 말, 없으면 가장 긴 포함어 (회원 → member, 과정 → course)</li>
 *   <li>그 업무 테이블 이름의 공통 접두어 (tb_member · tb_member_log → member)</li>
 * </ol>
 */
public final class WorkNames {

    /** 제안 결과: 이름과 어떻게 찾았는지 */
    public record Suggestion(String pkg, String how) {
    }

    static final Map<String, String> DICTIONARY = new LinkedHashMap<>();

    static {
        String[][] d = {
                {"회원", "member"}, {"멤버", "member"}, {"고객", "customer"}, {"사용자", "user"}, {"유저", "user"}, {"계정", "account"},
                {"관리자", "admin"}, {"조직", "org"}, {"부서", "dept"}, {"사원", "employee"}, {"직원", "employee"}, {"인사", "hr"},
                {"급여", "payroll"}, {"회사", "company"}, {"법인", "company"}, {"지점", "branch"}, {"거래처", "vendor"}, {"협력사", "partner"},
                {"게시판", "board"}, {"게시글", "post"}, {"게시", "board"}, {"공지", "notice"}, {"공지사항", "notice"}, {"뉴스", "news"},
                {"댓글", "comment"}, {"자료실", "archive"}, {"첨부", "file"}, {"파일", "file"}, {"문서", "document"}, {"콘텐츠", "content"},
                {"컨텐츠", "content"}, {"코드", "code"}, {"공통코드", "code"}, {"공통", "common"}, {"기준정보", "master"}, {"마스터", "master"},
                {"설정", "config"}, {"환경설정", "config"}, {"시스템", "system"}, {"메뉴", "menu"}, {"권한", "auth"}, {"인증", "auth"},
                {"통계", "stats"}, {"집계", "stats"}, {"현황", "status"}, {"이력", "history"}, {"로그", "log"}, {"접속", "access"},
                {"알림", "notification"}, {"메일", "mail"}, {"문자", "sms"}, {"푸시", "push"}, {"배치", "batch"}, {"스케줄", "schedule"},
                {"일정", "schedule"}, {"캘린더", "calendar"}, {"예약", "reservation"}, {"주문", "order"}, {"결제", "payment"}, {"정산", "settlement"},
                {"청구", "billing"}, {"회계", "accounting"}, {"매출", "sales"}, {"상품", "product"}, {"제품", "product"}, {"재고", "inventory"},
                {"배송", "delivery"}, {"쿠폰", "coupon"}, {"포인트", "point"}, {"마일리지", "mileage"}, {"이벤트", "event"}, {"프로모션", "promotion"},
                {"리뷰", "review"}, {"후기", "review"}, {"평가", "evaluation"}, {"설문", "survey"}, {"문의", "inquiry"}, {"상담", "counsel"},
                {"민원", "complaint"}, {"계약", "contract"}, {"약관", "terms"}, {"과정", "course"}, {"강좌", "course"}, {"강의", "lecture"},
                {"차시", "lesson"}, {"수강", "enrollment"}, {"수료", "completion"}, {"학습", "learning"}, {"교육", "education"}, {"강사", "tutor"},
                {"교수", "professor"}, {"학생", "student"}, {"수강생", "student"}, {"시험", "exam"}, {"문제", "question"}, {"과제", "assignment"},
                {"출석", "attendance"}, {"증명", "certificate"}, {"증명서", "certificate"}, {"프로젝트", "project"}, {"자산", "asset"},
                {"장비", "equipment"}, {"채팅", "chat"}, {"검색", "search"}, {"즐겨찾기", "bookmark"}, {"북마크", "bookmark"}, {"홈", "home"},
                {"기타", "etc"}, {"운영", "operation"}, {"관리", "manage"}, {"신청", "apply"}, {"접수", "receipt"}, {"승인", "approval"},
                {"결재", "approval"}, {"지원", "support"}, {"도움말", "help"}, {"매핑", "mapping"}, {"이관", "migration"}, {"임시", "temp"},
        };
        for (String[] e : d) DICTIONARY.put(e[0], e[1]);
    }

    private static final Set<String> GENERIC_PREFIX = Set.of("data", "info", "mst", "master", "sys", "cm", "cd", "tb", "t", "tbl");

    private WorkNames() {
    }

    /** 업무구분 값과 그 업무의 테이블 이름으로 패키지 이름을 제안한다. 못 찾으면 null */
    public static Suggestion suggest(String work, Collection<String> tables) {
        return suggest(work, tables, Map.of());
    }

    /**
     * 업체 규칙 파일의 사전({@code extra}: 단어 → 패키지)을 일반 사전 위에 덮어 제안한다.
     * 같은 단어는 업체 값이 우선이고, 가장 긴 포함어 동률도 업체 단어가 먼저다
     */
    public static Suggestion suggest(String work, Collection<String> tables, Map<String, String> extra) {
        if (work == null || work.isBlank()) return null;
        String w = work.trim();
        String lower = w.toLowerCase(Locale.ROOT);
        // 업체 사전이 가장 먼저 (업무구분 전체 · 앞 번호와 "관리" 를 뗀 단어 모두 본다)
        Map<String, String> custom = new LinkedHashMap<>();
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) continue;
                String v = e.getValue().trim().toLowerCase(Locale.ROOT);
                custom.putIfAbsent(e.getKey().trim(), v);
                custom.putIfAbsent(stripWord(e.getKey()), v);
            }
        }
        String key = stripWord(w);
        if (!custom.isEmpty()) {
            String hit = custom.get(w);
            if (hit == null) hit = custom.get(key);
            if (hit != null) return new Suggestion(hit, "업체 사전 (" + (custom.containsKey(w) ? w : key) + ")");
        }
        if (Names.packageWord(lower)) return new Suggestion(lower, "업무구분 영문");
        // 영문이 섞여 있으면 영문 부분만 (예: "회원(member)")
        String latin = lower.replaceAll("[^a-z0-9]", "");
        if (!latin.isEmpty() && Names.packageWord(latin) && lower.matches(".*[a-z].*") && !lower.matches(".*[가-힣].*[a-z].*[가-힣].*")) {
            if (latin.length() >= 3) return new Suggestion(latin, "업무구분의 영문 부분");
        }
        String hit = DICTIONARY.get(key);
        if (hit != null) return new Suggestion(hit, "사전 (" + key + ")");
        String longest = null;
        String longestHow = null;
        for (Map.Entry<String, String> e : custom.entrySet()) {
            if (key.contains(e.getKey()) && (longest == null || e.getKey().length() > longest.length())) { longest = e.getKey(); longestHow = "업체 사전"; }
        }
        for (String k : DICTIONARY.keySet()) {
            if (key.contains(k) && (longest == null || k.length() > longest.length())) { longest = k; longestHow = "사전"; }
        }
        if (longest != null && longest.length() >= 2) {
            String v = longestHow.equals("업체 사전") ? custom.get(longest) : DICTIONARY.get(longest);
            return new Suggestion(v, longestHow + " (" + longest + ")");
        }
        String prefix = commonPrefix(tables);
        if (prefix != null) return new Suggestion(prefix, "테이블 이름 접두어");
        return null;
    }

    /** "02 회원관리" → "회원": 앞 번호 · 기호와 끝의 관리 · 정보 · 업무 · 시스템 · 영역을 뗀다 */
    static String stripWord(String w) {
        String key = w.trim().replaceAll("^[\\d\\s.\\-_()\\[\\]]+", "").replaceAll("[\\s()\\[\\]/·,]+", "");
        for (String suffix : List.of("관리", "정보", "업무", "시스템", "영역")) {
            if (key.length() > suffix.length() && key.endsWith(suffix) && !DICTIONARY.containsKey(key)) key = key.substring(0, key.length() - suffix.length());
        }
        return key;
    }

    /** tb_ · t_ · tbl_ 를 뗀 첫 마디가 모든 테이블에서 같으면 그것 (data · info 같은 일반어는 제외) */
    static String commonPrefix(Collection<String> tables) {
        if (tables == null || tables.isEmpty()) return null;
        String common = null;
        for (String t : tables) {
            List<String> tk = new ArrayList<>(List.of(t.toLowerCase(Locale.ROOT).split("_")));
            while (tk.size() > 1 && Set.of("tb", "t", "tbl").contains(tk.get(0))) tk.remove(0);
            String first = tk.get(0);
            if (common == null) common = first;
            else if (!common.equals(first)) return null;
        }
        return common != null && common.length() >= 2 && Names.packageWord(common) && !GENERIC_PREFIX.contains(common) ? common : null;
    }
}
