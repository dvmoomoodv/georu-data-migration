package com.georu.core.internal.excel;

/** 업로드 파일이 크기 상한을 넘음. 나눠 올리는 안내({@link ExcelSplitPlan})를 담는다 */
public class ExcelSizeLimitException extends IllegalArgumentException {

    private final transient ExcelSplitPlan plan;

    public ExcelSizeLimitException(String message, ExcelSplitPlan plan) {
        super(message);
        this.plan = plan;
    }

    public ExcelSplitPlan getPlan() {
        return plan;
    }
}
