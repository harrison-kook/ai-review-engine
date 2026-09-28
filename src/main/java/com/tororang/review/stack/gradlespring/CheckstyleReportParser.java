package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checkstyle의 checkstyle-result.xml (formatter=xml) 파싱.
 * &lt;checkstyle&gt;&lt;file name="..."&gt;&lt;error line="10" severity="error|warning|info" message="..." source="..."/&gt;
 */
public final class CheckstyleReportParser {

    public List<FindingCandidate> parse(Path xmlFile, Path repoRoot) {
        return toFindings(XmlReports.parse(xmlFile), repoRoot);
    }

    public List<FindingCandidate> parse(InputStream xml, Path repoRoot) {
        return toFindings(XmlReports.parse(xml), repoRoot);
    }

    private List<FindingCandidate> toFindings(Document document, Path repoRoot) {
        List<FindingCandidate> candidates = new ArrayList<>();
        NodeList fileNodes = document.getElementsByTagName("file");
        for (int i = 0; i < fileNodes.getLength(); i++) {
            Element fileElement = (Element) fileNodes.item(i);
            String relativePath = XmlReports.relativize(repoRoot, fileElement.getAttribute("name"));

            NodeList errorNodes = fileElement.getElementsByTagName("error");
            for (int j = 0; j < errorNodes.getLength(); j++) {
                Element error = (Element) errorNodes.item(j);
                String source = error.getAttribute("source");
                String ruleId = source.isBlank() ? "CHECKSTYLE" : lastSegment(source);
                int line = parseIntOrDefault(error.getAttribute("line"), 1);
                String message = error.getAttribute("message");
                Severity severity = mapSeverity(error.getAttribute("severity"));

                candidates.add(new FindingCandidate(
                        ruleId,
                        severity,
                        Source.CHECKSTYLE,
                        relativePath,
                        line,
                        message,
                        message,
                        null
                ));
            }
        }
        return candidates;
    }

    private Severity mapSeverity(String checkstyleSeverity) {
        return switch (checkstyleSeverity == null ? "" : checkstyleSeverity.toLowerCase()) {
            case "error" -> Severity.HIGH;
            case "warning" -> Severity.MEDIUM;
            case "info" -> Severity.INFO;
            default -> Severity.LOW;
        };
    }

    private String lastSegment(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
