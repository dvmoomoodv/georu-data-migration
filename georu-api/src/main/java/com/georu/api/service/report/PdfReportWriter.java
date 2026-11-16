package com.georu.api.service.report;

import com.georu.api.service.report.ReportModels.JobReport;
import com.georu.api.service.report.ReportModels.SummaryReport;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;

/**
 * PDF 출력: Thymeleaf 템플릿(templates/report/*.html, XHTML) → openhtmltopdf.
 * 한글은 NanumGothic(OFL, resources/fonts) 을 임베딩한다.
 */
@Component
@RequiredArgsConstructor
public class PdfReportWriter {

    private final TemplateEngine templateEngine;

    public void writeJobReport(JobReport r, OutputStream out) throws IOException {
        render("report/job", Map.of("r", r), out);
    }

    public void writeSummary(SummaryReport s, OutputStream out) throws IOException {
        render("report/summary", Map.of("s", s), out);
    }

    private void render(String template, Map<String, Object> model, OutputStream out) throws IOException {
        Context ctx = new Context(Locale.KOREA);
        ctx.setVariables(model);
        String html = templateEngine.process(template, ctx);
        PdfRendererBuilder b = new PdfRendererBuilder();
        b.useFastMode();
        b.useFont(() -> PdfReportWriter.class.getResourceAsStream("/fonts/NanumGothic-Regular.ttf"),
                "NanumGothic", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
        b.useFont(() -> PdfReportWriter.class.getResourceAsStream("/fonts/NanumGothic-Bold.ttf"),
                "NanumGothic", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
        b.withHtmlContent(html, null);
        b.toStream(out);
        b.run();
    }
}
