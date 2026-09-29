package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.stack.MutationReport;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * PIT의 mutations.xml 파싱.
 * &lt;mutations&gt;&lt;mutation detected='true' status='KILLED'&gt;...&lt;/mutation&gt;
 */
public final class PitestReportParser {

    public MutationReport parse(Path xmlFile) {
        return toReport(XmlReports.parse(xmlFile));
    }

    public MutationReport parse(InputStream xml) {
        return toReport(XmlReports.parse(xml));
    }

    private MutationReport toReport(Document document) {
        NodeList mutationNodes = document.getElementsByTagName("mutation");
        int total = mutationNodes.getLength();
        if (total == 0) {
            return MutationReport.EMPTY;
        }

        int killed = 0;
        for (int i = 0; i < total; i++) {
            Element mutation = (Element) mutationNodes.item(i);
            if (Boolean.parseBoolean(mutation.getAttribute("detected"))) {
                killed++;
            }
        }

        double score = (100.0 * killed) / total;
        return new MutationReport(score, total, killed);
    }
}