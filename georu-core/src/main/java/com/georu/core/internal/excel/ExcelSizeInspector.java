package com.georu.core.internal.excel;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * xlsx 크기 사전 확인. POI 로 워크북을 열기 전에 첫 시트 XML 을 StAX 로 흘려 읽어 행·셀 수를 센다 (메모리를 거의 쓰지 않는다).
 * POI(XSSF)는 워크북 전체를 메모리에 올리므로, 큰 파일을 그대로 열면 앱이 메모리 부족으로 종료될 수 있다
 * ({@code -XX:+ExitOnOutOfMemoryError}). 상한을 넘는 파일은 열지 않고 나눠 올리도록 안내한다.
 */
public final class ExcelSizeInspector {

    /** 첫 시트의 데이터 행 수(헤더 제외)·최대 열 수·셀 수(값이 있는 셀 기준 아님, XML 의 c 요소 수) */
    public record SheetSize(int rows, int columns, long cells, long sheetXmlBytes) {
        public int dataRows() {
            return Math.max(0, rows - 1);
        }
    }

    private ExcelSizeInspector() {
    }

    public static SheetSize inspectXlsx(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            String sheetPath = firstSheetPath(zip);
            ZipEntry entry = zip.getEntry(sheetPath);
            if (entry == null) {
                throw new IOException("첫 시트를 찾을 수 없습니다: " + sheetPath);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return count(in, entry.getSize());
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("xlsx 를 열 수 없습니다 (손상되었거나 xlsx 가 아님): " + e.getMessage(), e);
        }
    }

    static SheetSize count(InputStream in, long xmlBytes) throws IOException {
        XMLStreamReader r = null;
        try {
            r = factory().createXMLStreamReader(in);
            int rows = 0;
            int maxCols = 0;
            int colsInRow = 0;
            long cells = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if ("row".equals(name)) {
                        rows++;
                        colsInRow = 0;
                    } else if ("c".equals(name)) {
                        cells++;
                        colsInRow++;
                        String ref = r.getAttributeValue(null, "r");
                        int col = ref == null ? colsInRow : columnIndex(ref);
                        if (col > maxCols) {
                            maxCols = col;
                        }
                    }
                }
            }
            return new SheetSize(rows, maxCols, cells, xmlBytes);
        } catch (XMLStreamException e) {
            throw new IOException("시트 XML 을 읽을 수 없습니다: " + e.getMessage(), e);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException ignored) {
                    // 무시
                }
            }
        }
    }

    /** workbook.xml 의 첫 sheet → workbook.xml.rels 의 Target. 못 찾으면 sheet1.xml */
    static String firstSheetPath(ZipFile zip) throws IOException {
        String rid = null;
        ZipEntry wb = zip.getEntry("xl/workbook.xml");
        if (wb != null) {
            try (InputStream in = zip.getInputStream(wb)) {
                XMLStreamReader r = factory().createXMLStreamReader(in);
                while (r.hasNext() && rid == null) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(r.getLocalName())) {
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            if ("id".equals(r.getAttributeLocalName(i))) {
                                rid = r.getAttributeValue(i);
                            }
                        }
                    }
                }
                r.close();
            } catch (XMLStreamException e) {
                throw new IOException("workbook.xml 을 읽을 수 없습니다: " + e.getMessage(), e);
            }
        }
        ZipEntry rels = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (rid != null && rels != null) {
            try (InputStream in = zip.getInputStream(rels)) {
                XMLStreamReader r = factory().createXMLStreamReader(in);
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())
                            && rid.equals(r.getAttributeValue(null, "Id"))) {
                        String target = r.getAttributeValue(null, "Target");
                        r.close();
                        if (target == null) {
                            break;
                        }
                        return target.startsWith("/") ? target.substring(1) : "xl/" + target;
                    }
                }
                r.close();
            } catch (XMLStreamException e) {
                throw new IOException("workbook.xml.rels 를 읽을 수 없습니다: " + e.getMessage(), e);
            }
        }
        return "xl/worksheets/sheet1.xml";
    }

    /** "AB12" → 28 */
    static int columnIndex(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                col = col * 26 + (c - 'A' + 1);
            } else if (c >= 'a' && c <= 'z') {
                col = col * 26 + (c - 'a' + 1);
            } else {
                break;
            }
        }
        return col;
    }

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }
}
