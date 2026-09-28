package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SpotBugs의 spotbugsXml.xml(withMessages=true) 파싱.
 * &lt;BugCollection&gt;&lt;BugInstance type="..." priority="1"&gt;&lt;LongMessage&gt;...&lt;/LongMessage&gt;
 *   &lt;SourceLine start="10" sourcepath="com/example/OrderService.java"/&gt;
 * sourcepath는 소스 루트(src/main/java) 기준 상대경로만 담고 있어, 일반적인 Gradle/Maven
 * 레이아웃을 가정해 "src/main/java/"를 붙인다. 레이아웃이 다르면 부정확할 수 있다.
 */
public final class SpotBugsReportParser {

    private static final String DEFAULT_SOURCE_ROOT = "src/main/java/";

    public List<FindingCandidate> parse(Path xmlFile, Path repoRoot) {
        return toFindings(XmlReports.parse(xmlFile));
    }

    public List<FindingCandidate> parse(InputStream xml, Path repoRoot) {
        return toFindings(XmlReports.parse(xml));
    }

    private List<FindingCandidate> toFindings(Document document) {
        List<FindingCandidate> candidates = new ArrayList<>();
        NodeList bugNodes = document.getElementsByTagName("BugInstance");
        for (int i = 0; i < bugNodes.getLength(); i++) {
            Element bug = (Element) bugNodes.item(i);
            String ruleId = bug.getAttribute("type");
            Severity severity = mapPriority(bug.getAttribute("priority"));
            String message = firstNonBlankText(bug, "LongMessage", "ShortMessage");

            Element sourceLine = firstDirectChild(bug, "SourceLine");
            int line = sourceLine != null ? parseIntOrDefault(sourceLine.getAttribute("start"), 1) : 1;
            String sourcePath = sourceLine != null ? sourceLine.getAttribute("sourcepath") : "";
            String file = sourcePath.isBlank() ? "unknown" : DEFAULT_SOURCE_ROOT + sourcePath.replace('\\', '/');

            candidates.add(new FindingCandidate(
                    ruleId.isBlank() ? "SPOTBUGS" : ruleId,
                    severity,
                    Source.SPOTBUGS,
                    file,
                    line,
                    message,
                    message,
                    null
            ));
        }
        return candidates;
    }

    private Severity mapPriority(String priority) {
        int p = parseIntOrDefault(priority, 2);
        if (p <= 1) {
            return Severity.HIGH;
        }
        if (p == 2) {
            return Severity.MEDIUM;
        }
        return Severity.LOW;
    }

    private String firstNonBlankText(Element parent, String... tagNames) {
        for (String tag : tagNames) {
            NodeList nodes = parent.getElementsByTagName(tag);
            if (nodes.getLength() > 0) {
                String text = nodes.item(0).getTextContent();
                if (text != null && !text.isBlank()) {
                    return text.strip();
                }
            }
        }
        return "";
    }

    private Element firstDirectChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
