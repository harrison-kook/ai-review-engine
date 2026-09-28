package com.tororang.review.adapter.github;

import com.tororang.review.core.adapter.DiffScope;
import com.tororang.review.core.adapter.DiffSource;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * GitHub REST API의 PR files 엔드포인트(GET /repos/{owner}/{repo}/pulls/{number}/files)로
 * 변경 파일과 patch(unified diff hunk)를 가져와 {@link DiffScope}로 변환한다.
 */
public class GitHubDiffCollector implements DiffSource {

    private static final int PAGE_SIZE = 100;

    private final RestTemplate restTemplate;
    private final PullRequestRef pullRequest;
    private final UnifiedDiffParser diffParser = new UnifiedDiffParser();

    public GitHubDiffCollector(RestTemplate restTemplate, PullRequestRef pullRequest) {
        this.restTemplate = restTemplate;
        this.pullRequest = pullRequest;
    }

    @Override
    public DiffScope fetchChangedLines() {
        Map<String, Set<Integer>> changedLinesByFile = new LinkedHashMap<>();
        int page = 1;
        while (true) {
            PrFile[] files = fetchPage(page);
            if (files == null || files.length == 0) {
                break;
            }
            for (PrFile file : files) {
                if (file.patch() != null && !"removed".equals(file.status())) {
                    changedLinesByFile.put(file.filename(), diffParser.parseAddedLines(file.patch()));
                }
            }
            if (files.length < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return new DiffScope(changedLinesByFile);
    }

    private PrFile[] fetchPage(int page) {
        String url = "https://api.github.com/repos/%s/%s/pulls/%d/files?per_page=%d&page=%d"
                .formatted(pullRequest.owner(), pullRequest.repo(), pullRequest.number(), PAGE_SIZE, page);
        try {
            return restTemplate.getForObject(url, PrFile[].class);
        } catch (RestClientException e) {
            throw new GitHubApiException("failed to fetch PR files: " + url, e);
        }
    }

    private record PrFile(String filename, String status, String patch) {
    }
}
