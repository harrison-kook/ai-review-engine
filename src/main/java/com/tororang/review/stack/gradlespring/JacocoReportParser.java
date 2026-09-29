package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.stack.CoverageReport;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * JaCoCo의 jacocoTestReport.xml 파싱.
 * &lt;report&gt; 밑에는 package/class/method마다 자기 몫의 &lt;counter&gt;가 있고, 맨 마지막에
 * 전체 합계 &lt;counter&gt;가 report의 "직계 자식"으로 딱 하나 더 있다. getElementsByTagName처럼
 * 문서 전체를 재귀 검색하면 안 된다 — 첫 번째로 걸리는 건 흔히 가장 먼저 나오는 메서드 하나의
 * 카운터일 뿐이라 완전히 틀린 값(대개 0%에 가까운 값)을 돌려주게 된다(실사용 중 실제 발생).
 */
public final class JacocoReportParser {

    public CoverageReport parse(Path xmlFile) {
        return toReport(XmlReports.parse(xmlFile));
    }

    public CoverageReport parse(InputStream xml) {
        return toReport(XmlReports.parse(xml));
    }

    private CoverageReport toReport(Document document) {
        Element root = document.getDocumentElement();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) node;
            if ("counter".equals(child.getTagName()) && "LINE".equals(child.getAttribute("type"))) {
                int missed = parseIntAttr(child, "missed");
                int covered = parseIntAttr(child, "covered");
                double percent = (missed + covered) == 0 ? 0.0 : (100.0 * covered) / (missed + covered);
                return new CoverageReport(percent, covered, missed);
            }
        }
        return CoverageReport.EMPTY;
    }

    private int parseIntAttr(Element element, String attr) {
        String value = element.getAttribute(attr);
        try {
            return value.isBlank() ? 0 : (int) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}