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
 * PMD의 pmd-result.xml (format=xml) 파싱.
 * &lt;pmd&gt;&lt;file name="..."&gt;&lt;violation beginline="10" rule="..." priority="3"&gt;message&lt;/violation&gt;
 */
public final class PmdReportParser {

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

            NodeList violationNodes = fileElement.getElementsByTagName("violation");
            for (int j = 0; j < violationNodes.getLength(); j++) {
                Element violation = (Element) violationNodes.item(j);
                String ruleId = violation.getAttribute("rule");
                int line = parseIntOrDefault(violation.getAttribute("beginline"), 1);
                String message = violation.getTextContent() == null ? "" : violation.getTextContent().strip();
                Severity severity = mapPriority(violation.getAttribute("priority"));

                candidates.add(new FindingCandidate(
                        ruleId.isBlank() ? "PMD" : ruleId,
                        severity,
                        Source.PMD,
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

    private Severity mapPriority(String priority) {
        int p = parseIntOrDefault(priority, 3);
        if (p <= 2) {
            return Severity.HIGH;
        }
        if (p == 3) {
            return Severity.MEDIUM;
        }
        return Severity.LOW;
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
