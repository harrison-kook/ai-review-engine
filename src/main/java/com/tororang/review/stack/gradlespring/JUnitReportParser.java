package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.stack.TestCaseResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Gradle/JUnit의 build/test-results/test/*.xml (surefire 호환 포맷) 파싱.
 * &lt;testsuite&gt;&lt;testcase name="method" classname="com.example.OrderServiceTest"&gt;
 *   &lt;failure message="..."&gt;...&lt;/failure&gt; (실패 시)
 */
public final class JUnitReportParser {

    public List<TestCaseResult> parse(Path xmlFile) {
        return toResults(XmlReports.parse(xmlFile));
    }

    public List<TestCaseResult> parse(InputStream xml) {
        return toResults(XmlReports.parse(xml));
    }

    private List<TestCaseResult> toResults(Document document) {
        List<TestCaseResult> results = new ArrayList<>();
        NodeList testcaseNodes = document.getElementsByTagName("testcase");
        for (int i = 0; i < testcaseNodes.getLength(); i++) {
            Element testcase = (Element) testcaseNodes.item(i);
            String methodName = normalizeMethodName(testcase.getAttribute("name"));
            String className = testcase.getAttribute("classname");

            String failureMessage = firstFailureMessage(testcase, "failure");
            if (failureMessage == null) {
                failureMessage = firstFailureMessage(testcase, "error");
            }

            results.add(new TestCaseResult(className, methodName, failureMessage == null, failureMessage));
        }
        return results;
    }

    /**
     * JUnit5의 Gradle XML 리포트는 무인자 메서드의 표시 이름에 "()"를 붙인다
     * (예: "duplicateApprove_isIdempotent()"). tester 에이전트가 보고하는 methodName에는
     * 괄호가 없으므로, 여기서 벗겨내지 않으면 TC-ID 상관관계 매칭이 항상 실패한다.
     */
    private String normalizeMethodName(String rawName) {
        if (rawName != null && rawName.endsWith("()")) {
            return rawName.substring(0, rawName.length() - 2);
        }
        return rawName;
    }

    private String firstFailureMessage(Element testcase, String tagName) {
        NodeList nodes = testcase.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        Element failure = (Element) nodes.item(0);
        String message = failure.getAttribute("message");
        if (message != null && !message.isBlank()) {
            return message;
        }
        String text = failure.getTextContent();
        return text == null || text.isBlank() ? "실패(사유 없음)" : text.strip();
    }
}
