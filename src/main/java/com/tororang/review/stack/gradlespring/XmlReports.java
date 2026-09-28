package com.tororang.review.stack.gradlespring;

import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Checkstyle/PMD/SpotBugs XML 리포트 파싱 공통 유틸. 신뢰할 수 없는 대상 레포의 소스에서
 * 파생된 XML을 다루므로 DTD/외부 엔티티는 항상 비활성화한다 (XXE 방지).
 */
final class XmlReports {

    private XmlReports() {
    }

    /**
     * Checkstyle/PMD 리포트의 file name 속성(보통 절대경로)을 repoRoot 기준 상대경로로 바꾼다.
     * java.nio.file.Path#isAbsolute()는 Windows에서 드라이브 문자가 없는 "/repo/..." 형태를
     * 절대경로로 인식하지 않으므로, NIO API 대신 문자열 접두어 비교로 처리한다.
     */
    static String relativize(Path repoRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        String normalizedRaw = rawPath.replace('\\', '/');
        String normalizedRoot = repoRoot.toString().replace('\\', '/');
        if (!normalizedRoot.isEmpty() && normalizedRaw.startsWith(normalizedRoot)) {
            String stripped = normalizedRaw.substring(normalizedRoot.length());
            return stripped.startsWith("/") ? stripped.substring(1) : stripped;
        }
        return normalizedRaw;
    }

    static Document parse(Path xmlFile) {
        try (InputStream in = java.nio.file.Files.newInputStream(xmlFile)) {
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read xml report: " + xmlFile, e);
        }
    }

    static Document parse(InputStream in) {
        try {
            // JaCoCo 리포트는 정상적인 DOCTYPE(report.dtd)을 포함하므로 DOCTYPE 자체는 허용하되,
            // 외부 DTD/엔티티 로딩은 전부 차단해서 XXE/SSRF를 막는다.
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(in);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("failed to parse xml report", e);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read xml report", e);
        }
    }
}
