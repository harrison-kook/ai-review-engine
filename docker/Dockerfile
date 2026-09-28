# ai-review-engine 샌드박스 이미지
#
# 이 이미지는 build/report 두 단계 모두에서 같은 엔진 jar를 실행하는 데 쓰인다.
# 어떤 단계로 도는지는 `--phase` 인자와 `docker run` 호출 방식(네트워크/시크릿 유무)이 결정한다.
# 이미지 자체에는 시크릿을 절대 포함하지 않는다 (설계서 1.3) — 항상 `docker run -e`로 실행 시점에 주입한다.
#
# 네트워크/시크릿 모델 (docker/README.md 참고):
#   1) build 단계: 의존성 다운로드까지는 네트워크 허용, 대상 레포 코드 실행(gradlew build/check)은
#      `docker run --network none`, 시크릿 없음.
#   2) report 단계: 룰팩 주입 + LLM 리뷰 + PR 코멘트. 네트워크/시크릿 필요하지만 대상 레포 코드를
#      실행하지 않는다 (Read/Grep/Glob 전용 도구로만 접근).

FROM eclipse-temurin:21-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       git \
       curl \
       nodejs \
       npm \
    && npm install -g @anthropic-ai/claude-code \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

COPY build/libs/*-SNAPSHOT.jar /opt/ai-review-engine/app.jar

ENTRYPOINT ["java", "-jar", "/opt/ai-review-engine/app.jar"]
