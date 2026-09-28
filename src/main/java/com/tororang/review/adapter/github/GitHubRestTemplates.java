package com.tororang.review.adapter.github;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * GitHub REST API 호출용 RestTemplate 팩토리. HttpComponentsClientHttpRequestFactory를 쓰는 이유는
 * 기본 SimpleClientHttpRequestFactory(HttpURLConnection)가 PATCH를 안정적으로 지원하지 않기 때문이다
 * (요약 코멘트 갱신에 PATCH가 필요, 설계서 8장).
 */
public final class GitHubRestTemplates {

    private GitHubRestTemplates() {
    }

    public static RestTemplate withToken(String token) {
        RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory());
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            request.getHeaders().set(HttpHeaders.ACCEPT, "application/vnd.github+json");
            request.getHeaders().set("X-GitHub-Api-Version", "2022-11-28");
            return execution.execute(request, body);
        });
        return restTemplate;
    }
}
