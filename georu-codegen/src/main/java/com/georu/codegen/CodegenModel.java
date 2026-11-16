package com.georu.codegen;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 템플릿에 넘기는 값. 이름·SQL 식·주석은 여기서 다 만들고 템플릿은 배치만 한다 (템플릿을 바꿔도 규칙이 어긋나지 않게).
 *
 * <ul>
 *   <li>{@code s}: {@link Company} - 업체·패키지·DataSource</li>
 *   <li>{@code w}: {@link Work} - 업무 하나와 그 테이블</li>
 *   <li>{@code t}: {@link Table} - 테이블 하나</li>
 *   <li>{@code gen}: {@link Gen} - 생성기 버전·시각·올린 파일</li>
 * </ul>
 */
public final class CodegenModel {

    private CodegenModel() {
    }

    @Getter
    public static final class Company {
        /** acme */
        private final String company;
        /** Acme */
        private final String pascal;
        /** com.georu.acme */
        private final String basePackage;
        private final String sourceDataSource;
        private final String targetDataSource;
        private final String sourceDialect;
        private final String targetDialect;
        private final String sourceDriver;
        private final String targetDriver;
        private final String sourceUrlExample;
        private final String targetUrlExample;
        private final List<Work> works = new ArrayList<>();

        Company(CodegenSettings settings) {
            this.company = settings.company();
            this.pascal = settings.companyPascal();
            this.basePackage = settings.basePackage();
            this.sourceDataSource = settings.sourceDataSource();
            this.targetDataSource = settings.targetDataSource();
            this.sourceDialect = settings.sourceDialect().name().toLowerCase(java.util.Locale.ROOT);
            this.targetDialect = settings.targetDialect().name().toLowerCase(java.util.Locale.ROOT);
            this.sourceDriver = settings.sourceDialect().driver();
            this.targetDriver = settings.targetDialect().driver();
            this.sourceUrlExample = settings.sourceDialect().urlExample();
            this.targetUrlExample = settings.targetDialect().urlExample();
        }
    }

    @Getter
    public static final class Work {
        /** 엑셀 업무구분 값 (예: 회원) */
        private final String label;
        /** 패키지 마디 (예: member) */
        private final String key;
        /** com.georu.acme.member */
        private final String pkg;
        /** Member */
        private final String pascal;
        /** acme.member */
        private final String plugin;
        /** 자바 문자열 안에 넣을 업무 설명 */
        private final String description;
        private final List<Table> tables = new ArrayList<>();

        /** 업무 클래스가 reader 패키지를 import 해야 하는지 */
        public boolean isAnyReaderFile() { return tables.stream().anyMatch(Table::isReaderFile); }

        /** 업무 클래스가 writer 패키지를 import 해야 하는지 */
        public boolean isAnyWriterFile() { return tables.stream().anyMatch(Table::isWriterFile); }

        Work(String label, String key, Company company) {
            this.label = label;
            this.key = key;
            this.pkg = company.getBasePackage() + "." + key;
            this.pascal = Character.toUpperCase(key.charAt(0)) + key.substring(1);
            this.plugin = company.getCompany() + "." + key;
            this.description = Text.javaString(label);
        }
    }

    @Getter
    public static final class Table {
        /** 엔진 테이블 키 (소문자, 예: tb_member) */
        private final String table;
        /** 파일에 적힌 물리명 */
        private final String physical;
        /** TbMember */
        private final String pascal;
        /** " : 설명" (설명이 없으면 빈 문자열) */
        private final String javaComment;
        private final String xmlComment;
        /** 설명 (자바 주석용, 없으면 빈 문자열) */
        private final String javaText;
        /** 원천 테이블 (없으면 빈 문자열) */
        private final String source;
        /** 원천이 있어 SELECT 를 채웠는지 */
        private final boolean mapped;
        private final List<Column> columns;
        private final List<Column> pkColumns;
        /** 원천이 있는 PK (Processor 에서 비었는지 검증) */
        private List<Column> pkChecked;
        /** 원천이 없는 PK (채번 또는 SELECT 로 채워야 한다. 검증하지 않고 TODO PK) */
        private List<Column> pkUnmapped;
        /** Processor·Writer 의 행 식별 식 (예: item.getMemberId()), PK 가 없으면 null */
        private final String idExpr;
        /** FROM 절 (따옴표 포함, 원천이 없으면 자리 표시) */
        private final String fromClause;
        /** FROM 줄 끝 주석 (원천 메타에 테이블이 없으면 TODO) */
        private final String fromComment;
        /** 이름 일치 · 감사 컬럼 · 가정 · TODO 컬럼 수 */
        private final int nameCount;
        private final int auditCount;
        private final int commonCount;
        private final int assumedCount;
        /** Processor 기본값을 넣는 컬럼 */
        private final List<Column> defaults;
        /** Processor 가 {Acme}ConvertUtils 를 쓰는지 */
        private final boolean usesConvertUtils;
        private final int todoCount;
        /** Source XML 머리 주석의 매핑 요약 */
        private final String mappingSummary;
        /** ORDER BY 식 */
        private final String orderBy;
        /** ORDER BY 줄 끝 주석 (PK 가 없으면 TODO 정렬키) */
        private final String orderByComment;
        private final String paging;
        /** INSERT INTO 대상 (따옴표·스키마 포함) */
        private final String targetTable;
        /** Source DTO 의 import 줄 */
        private final List<String> imports;
        private final int pageSize;
        /** Writer 기반 (BATCH · REUSE) */
        private final String writerMode;
        /** Reader 파일을 만드는지 (FILES) */
        private final boolean readerFile;
        /** Writer 파일을 만드는지 (FILES, 또는 REUSE 테이블 - 엔진 공용 Writer 는 BATCH 뿐) */
        private final boolean writerFile;
        /** 페이지 크기가 엔진 기본(1000)과 달라 Reader 파일이 없을 때 선언에 .pageSize 를 적어야 하는지 */
        private final boolean customPageSize;
        /** 리포트 비고: 설명에 채번이 있는 PK 가 이름 일치로 복사된 경우 (없으면 빈 문자열) */
        private final String note;

        Table(SpecTable spec, List<Column> columns, CodegenSettings settings, SourceCatalog.SourceTable meta) {
            SqlDialect src = settings.sourceDialect();
            this.table = spec.name().toLowerCase(java.util.Locale.ROOT);
            this.physical = spec.name();
            this.pascal = Names.pascal(spec.name());
            this.javaComment = spec.comment().isEmpty() ? "" : " : " + Text.javaComment(spec.comment());
            this.xmlComment = spec.comment().isEmpty() ? "" : " : " + Text.xmlComment(spec.comment());
            this.javaText = Text.javaComment(spec.comment());
            this.source = spec.source();
            this.mapped = !spec.source().isEmpty();
            this.columns = columns;
            this.pkColumns = columns.stream().filter(Column::isPk).toList();
            this.idExpr = pkColumns.isEmpty() ? null : String.join(" + \"|\" + ",
                    pkColumns.stream().map(c -> "item." + c.getGetter() + "()").toList());
            for (Column c : columns) c.select(mapped, meta, settings);
            // 메타가 테이블을 찾았으면 그 실제 이름 (DB 에 소문자로 있는 테이블을 대문자로 적어도 맞게)
            String sourceTable = meta != null && !spec.source().contains(".") ? meta.name() : spec.source();
            String sourceRef = sourceTable.contains(".") || settings.sourceSchema().isEmpty() ? sourceTable : settings.sourceSchema() + "." + sourceTable;
            this.fromClause = mapped ? src.quote(settings.sourceName(sourceRef)) : "TODO_SOURCE_TABLE";
            this.fromComment = mapped && settings.sourceCatalog() != null && meta == null
                    ? "    <!-- TODO 원천 테이블 " + Text.xmlComment(spec.source()) + " 을 소스 메타에서 찾지 못함 -->" : "";
            String[] order = orderBy(mapped, meta, src, settings);
            this.orderBy = order[0];
            this.orderByComment = order[1];
            // 검증은 채번보다 먼저 돈다. 원천 없는 PK(시퀀스 대리키 등)를 검증하면 채번을 설정해도 모든 행이 걸러진다
            this.pkChecked = pkColumns.stream().filter(c -> c.getSource() != Column.Source.TODO).toList();
            this.pkUnmapped = pkColumns.stream().filter(c -> c.getSource() == Column.Source.TODO).toList();
            this.nameCount = count(columns, Column.Source.NAME);
            this.auditCount = count(columns, Column.Source.AUDIT);
            this.commonCount = count(columns, Column.Source.COMMON);
            this.defaults = columns.stream().filter(c -> c.getDefaultValue() != null).toList();
            this.usesConvertUtils = defaults.stream().anyMatch(c -> c.getDefaultValue().contains("ConvertUtils."));
            this.assumedCount = count(columns, Column.Source.ASSUMED);
            this.todoCount = count(columns, Column.Source.TODO);
            this.mappingSummary = !mapped ? "원천 미지정" : meta == null ? "원천 메타 없음 · 같은 이름 가정 " + assumedCount
                    : "이름 일치 " + nameCount + " · 공통 컬럼 " + commonCount + " · 감사 컬럼 " + auditCount
                    + " · 미매핑 " + todoCount + " (TOBE 컬럼 " + columns.size() + ")";
            this.paging = src.paging();
            String schema = settings.targetSchema();
            this.targetTable = settings.targetDialect().quote(schema.isEmpty() ? spec.name() : schema + "." + spec.name());
            Set<String> imp = new TreeSet<>();
            for (Column c : columns) {
                if (c.getJavaImport() != null) imp.add(c.getJavaImport());
            }
            this.imports = new ArrayList<>(imp);
            this.pageSize = settings.pageSize();
            this.writerMode = (spec.writer() != null ? spec.writer() : settings.writerMode()).name();
            boolean files = settings.readerWriter() == ReaderWriterMode.FILES;
            this.readerFile = files;
            this.writerFile = files || WriterMode.REUSE.name().equals(writerMode);
            this.customPageSize = pageSize != CodegenSettings.DEFAULT_PAGE_SIZE;
            List<String> kept = columns.stream().filter(Column::isKeepNumberHint).map(Column::getName).toList();
            this.note = kept.isEmpty() ? "" : "PK 번호 유지 (설명에 채번): " + String.join(" · ", kept) + " — 새 번호를 받으려면 SELECT 를 NULL 로 바꾸고 Strategy 에 sequenceColumns";
        }

        private static int count(List<Column> columns, Column.Source source) {
            return (int) columns.stream().filter(c -> c.getSource() == source).count();
        }

        /** 원천 PK → 원천에 있는 TOBE PK → (Oracle) ROWID · 첫 컬럼 + TODO 정렬키 */
        private String[] orderBy(boolean mapped, SourceCatalog.SourceTable meta, SqlDialect src, CodegenSettings settings) {
            String todo = "    <!-- TODO 정렬키: PK 가 없어 임시 정렬. 중복 없는 컬럼으로 바꾼다 -->";
            if (!mapped) return new String[]{"1", pkColumns.isEmpty() ? todo : ""};
            if (meta != null && !meta.pk().isEmpty()) {
                return new String[]{String.join(", ", meta.pk().stream().map(k -> "A." + src.quote(settings.sourceName(k))).toList()), ""};
            }
            List<String> keys = pkColumns.stream().map(Column::getName)
                    .filter(n -> meta == null || meta.column(n) != null)
                    .map(n -> meta == null ? n : meta.column(n).name()).toList();
            if (!keys.isEmpty()) return new String[]{String.join(", ", keys.stream().map(k -> "A." + src.quote(settings.sourceName(k))).toList()), ""};
            if (src.fallbackOrder() != null) return new String[]{src.fallbackOrder(), todo};
            String first = meta != null && !meta.columns().isEmpty() ? meta.columns().get(0).name() : columns.get(0).getName();
            return new String[]{"A." + src.quote(settings.sourceName(first)), todo};
        }
    }

    @Getter
    public static final class Column {
        private final String name;
        /** gradeCd */
        private final String field;
        /** getGradeCd */
        private final String getter;
        private final String javaType;
        private final String javaImport;
        /** VARCHAR(10) */
        private final String dbType;
        private final String javaComment;
        private final String xmlComment;
        private final boolean pk;
        private final boolean notNull;
        /** SELECT 식 (예: A."GRADE_CD", 원천이 없으면 NULL) */
        private String selectExpr;
        /** SELECT 별칭 (따옴표 포함) */
        private String alias;
        /** SELECT 줄 끝 주석 */
        private String selectComment;
        /** 원천을 어떻게 찾았는지 */
        private Source source = Source.TODO;
        /** INSERT 컬럼 (따옴표 포함) */
        private final String targetColumn;
        /** TOBE 길이 첫 숫자 (VARCHAR(8) → 8, 없으면 0) */
        private final int length;
        /** SELECT 별칭 이름. 자바 예약어 컬럼은 필드 classValue 에 맞춰 CLASS_VALUE */
        private final String aliasName;
        /** setGradeCd */
        private final String setter;
        /** Processor 에서 값이 비면 넣을 식 (공통 컬럼, 없으면 null) */
        private final String defaultValue;
        /** PK 가 이름 일치로 복사됐는데 설명에 "채번" · "시퀀스" 가 있다 (새 번호가 아니라 번호 유지로 생성됐음을 알린다) */
        private boolean keepNumberHint;

        /** 원천 컬럼을 찾은 방법 */
        public enum Source { NAME, AUDIT, COMMON, ASSUMED, TODO }

        Column(SpecColumn spec, JavaTypes.Mapping type, CodegenSettings settings) {
            this.name = spec.name();
            this.field = Names.field(spec.name());
            this.aliasName = Names.keyword(Names.camel(spec.name())) ? spec.name().toUpperCase(java.util.Locale.ROOT) + "_VALUE" : spec.name();
            this.length = firstNumber(spec.length());
            this.getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            this.setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            this.javaType = type.simpleName();
            this.javaImport = type.importName();
            this.dbType = spec.dbType();
            this.javaComment = Text.javaComment(spec.comment());
            this.xmlComment = Text.xmlComment(spec.comment());
            this.pk = spec.pk();
            this.notNull = spec.notNull();
            this.targetColumn = settings.targetDialect().quote(spec.name());
            this.defaultValue = settings.commonColumns()
                    ? CommonColumns.defaultValue(spec.name(), type.simpleName(), length, settings.companyPascal() + "ConvertUtils") : null;
        }

        private static int firstNumber(String length) {
            try {
                return length.isEmpty() ? 0 : Integer.parseInt(length.split(",")[0].trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /**
         * SELECT 식. 원천 미지정 → NULL(TODO). 원천 메타가 없으면 같은 이름이 있다고 가정.
         * 메타가 있으면 이름이 같은 컬럼 → 감사 컬럼 규칙 → NULL(TODO) 순서이고, 타입이 다르면 형변환한다
         */
        void select(boolean mapped, SourceCatalog.SourceTable meta, CodegenSettings settings) {
            SqlDialect src = settings.sourceDialect();
            this.alias = src.quote(aliasName);
            String what = (xmlComment.isEmpty() ? "" : xmlComment + " ") + dbType;
            if (!mapped) {
                todo(what + " TODO ← ASIS ?");
                return;
            }
            if (meta == null) {
                this.selectExpr = "A." + src.quote(settings.sourceName(name));
                this.selectComment = "    <!-- " + what + " ← " + name + " (같은 이름으로 가정) -->";
                this.source = Source.ASSUMED;
                return;
            }
            SourceCatalog.SourceColumn sc = meta.column(name);
            Source how = Source.NAME;
            if (sc == null && settings.audit().containsKey(name)) {
                sc = meta.column(settings.audit().get(name));
                how = Source.AUDIT;
            }
            if (sc == null && settings.commonColumns() && CommonColumns.standard(name)) {
                sc = CommonColumns.synonym(name, meta);
                how = Source.COMMON;
                String[] d = sc == null && javaType.equals("String") ? CommonColumns.derived(name, meta, src) : null;
                if (d != null) {
                    this.selectExpr = d[0];
                    this.selectComment = "    <!-- " + what + " ← " + d[1] + " (공통 컬럼) -->";
                    this.source = Source.COMMON;
                    return;
                }
            }
            if (sc == null) {
                todo(what + " TODO ← ASIS ?");
                return;
            }
            String[] e = convert("A." + src.quote(settings.sourceName(sc.name())), sc, src);
            if (e == null) {
                todo(what + " ← " + sc.name() + " TODO 형변환 " + sc.dbType() + " -> " + dbType);
                return;
            }
            this.selectExpr = e[0];
            this.keepNumberHint = how == Source.NAME && pk && (xmlComment.contains("채번") || xmlComment.contains("시퀀스"));
            this.selectComment = "    <!-- " + what + " ← " + sc.name()
                    + (keepNumberHint ? " (이름 일치 · 번호 유지. 설명에 채번: 새 번호를 받으려면 NULL 로 바꾸고 Strategy 에 sequenceColumns)"
                    : how == Source.NAME ? " (이름 일치)" : how == Source.AUDIT ? " (감사 컬럼)" : " (공통 컬럼)") + e[1] + " -->";
            this.source = how;
        }

        private void todo(String comment) {
            this.selectExpr = "NULL";
            this.selectComment = "    <!-- " + comment + " -->";
            this.source = Source.TODO;
        }

        /** 원천 식 → TOBE 타입. [식, 덧붙일 주석], 할 수 없으면 null */
        private String[] convert(String expr, SourceCatalog.SourceColumn sc, SqlDialect src) {
            String from = JavaTypes.family(JavaTypes.of(sc.type(), sc.length()).simpleName());
            String to = JavaTypes.family(javaType);
            if (from.equals(to)) return new String[]{expr, ""};
            String converted = switch (to) {
                case "text" -> from.equals("date") ? src.dateToChar(expr, length == 8)
                        : from.equals("number") ? src.numberToChar(expr) : null;
                case "number" -> from.equals("text") ? src.charToNumber(expr) : null;
                case "date" -> from.equals("text") ? src.charToDate(expr, javaType.equals("LocalDate")) : null;
                default -> null;
            };
            if (converted == null) return null;
            String note = from.equals("text") ? (to.equals("number") ? " (형변환, 숫자 아닌 값 확인)" : " (형변환, 형식 확인)") : "";
            return new String[]{converted, note};
        }
    }

    @Getter
    public static final class Gen {
        private final String version;
        private final String generatedAt;
        private final String fileName;

        Gen(String version, String generatedAt, String fileName) {
            this.version = version;
            this.generatedAt = generatedAt;
            this.fileName = fileName;
        }
    }

    /** 주석·문자열에 넣을 값 정리 */
    static final class Text {
        private Text() {
        }

        static String javaComment(String s) {
            return oneLine(s).replace("*/", "* /");
        }

        static String xmlComment(String s) {
            String v = oneLine(s).replace("--", "- -");
            return v.endsWith("-") ? v + " " : v;
        }

        static String javaString(String s) {
            return oneLine(s).replace("\\", "\\\\").replace("\"", "\\\"");
        }

        static String oneLine(String s) {
            return s == null ? "" : s.replaceAll("[\\r\\n\\t]+", " ").trim();
        }
    }
}
